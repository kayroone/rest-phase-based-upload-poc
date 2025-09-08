package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.*;
import de.jwiegmann.upload.boundary.dto.status.BatchUploadResultStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import de.jwiegmann.upload.control.dto.InboxItem;
import de.jwiegmann.upload.control.repository.InMemoryInboxItemRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final int MAX_ITEMS_PER_REQUEST = 1000; // TODO: konfigurierbar machen
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(2);

    private final InMemoryUploadSessionRepository uploadSessionRepository;
    private final InMemoryInboxItemRepository inboxItemRepository;

    public UploadService(InMemoryUploadSessionRepository uploadSessionRepository,
                         InMemoryInboxItemRepository inboxItemRepository) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.inboxItemRepository = inboxItemRepository;
    }

    /**
     * InitUpload: legt eine Session für genau eine VSL an.
     */
    public UploadSession initUpload(final UploadInitRequest req) {

        // 1. Metadaten prüfen
        if (req == null || req.getBewNr() == null
                || req.getVslNummer() == null
                || req.getAnzahlDatensaetzeInsgesamt() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid init payload");
        }

        // 2. Generiere uploadId und expire date
        String uploadId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plus(Duration.ofHours(2));

        // 3. Erzeugen und persistieren der neuen Session
        UploadSession s = UploadSession.builder()
                .uploadId(uploadId)
                .status(UploadSessionStatus.ACTIVE)
                .createdAt(now)
                .expiresAt(expires)
                .bewNr(req.getBewNr())
                .vslNummer(req.getVslNummer())
                .expectedCount(req.getAnzahlDatensaetzeInsgesamt())
                .receivedCount(0)
                .build();

        return uploadSessionRepository.save(s);
    }

    public BatchUploadResponse uploadBatch(String uploadId, List<ItemUploadRequest> batch) {

        UploadSession session = uploadSessionRepository.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        // 1. Expire date der upload session prüfen
        if (session.getExpiresAt() != null && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.ABORTED);
            uploadSessionRepository.save(session);
            throw new ResponseStatusException(HttpStatus.GONE, "upload session expired");
        }

        // 2. Obergrenze für items pro batch prüfen
        if (batch == null || batch.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty batch");
        }
        if (batch.size() > MAX_ITEMS_PER_REQUEST) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "batch too large (max " + MAX_ITEMS_PER_REQUEST + ")");
        }

        // 3. Duplikate in Batch erkennen
        Set<Integer> dupSeqInRequest = findDuplicateSeqNos(batch);
        LocalDateTime now = LocalDateTime.now();

        List<BatchUploadResult> results = new ArrayList<>(batch.size());

        int newlyAccepted = 0;
        boolean anyChanged = false;

        // 4. Process batch items
        for (ItemUploadRequest item : batch) {

            BatchUploadResult result = processSingleItem(session, item, dupSeqInRequest, now);
            results.add(result);

            if (result.getStatus() == BatchUploadResultStatus.ACCEPTED) {
                newlyAccepted++;
                anyChanged = true;
            } else if (result.getStatus() == BatchUploadResultStatus.REUPLOADED) {
                anyChanged = true;
            }
        }

        // 5. Update session 
        if (anyChanged) {
            updateSessionAfterChanges(session, newlyAccepted, now);
        }

        return BatchUploadResponse.builder()
                .uploadId(uploadId)
                .results(results)
                .build();
    }

    public UploadStatusResponse getStatus(String uploadId) {
        UploadSession s = uploadSessionRepository.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));
        List<InboxItem> items = inboxItemRepository.findAll(uploadId);
        return buildStatusResponse(s, items);
    }


    public UploadStatusListResponse getAllStatus() {

        List<UploadSession> sessions = uploadSessionRepository.findAll();
        List<UploadStatusResponse> items = sessions.stream()
                .map(s -> buildStatusResponse(s, inboxItemRepository.findAll(s.getUploadId())))
                .toList();

        return UploadStatusListResponse.builder()
                .total(items.size())
                .items(items)
                .build();
    }

    private UploadStatusResponse buildStatusResponse(UploadSession s, List<InboxItem> items) {
        int expected = s.getExpectedCount();
        int received = items.size();

        int pending = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.PENDING).count();
        int processing = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.PROCESSING).count();
        int done = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.DONE).count();
        int error = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.ERROR).count();

        Set<Integer> present = items.stream().map(InboxItem::getSeqNo).collect(java.util.stream.Collectors.toSet());
        List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 1; i <= expected; i++) {
            if (!present.contains(i)) missing.add(i);
        }

        List<Integer> errorSeq = items.stream()
                .filter(i -> i.getStatus() == UploadItemStatus.ERROR)
                .map(InboxItem::getSeqNo)
                .sorted()
                .toList();

        return UploadStatusResponse.builder()
                .uploadId(s.getUploadId())
                .uploadStatus(s.getStatus().name())
                .expected(expected)
                .received(received)
                .pending(pending)
                .processing(processing)
                .done(done)
                .error(error)
                .missingSequence(missing)
                .errorSequence(errorSeq)
                .build();
    }

    private Set<Integer> findDuplicateSeqNos(List<ItemUploadRequest> items) {
        Map<Integer, Long> freq = items.stream()
                .collect(Collectors.groupingBy(ItemUploadRequest::getSeqNo, Collectors.counting()));
        return freq.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private BatchUploadResult processSingleItem(UploadSession session,
                                                ItemUploadRequest item,
                                                Set<Integer> dupSeqInRequest,
                                                LocalDateTime now) {

        int seqNo = item.getSeqNo();

        // 1) Ist dieses Item ein Duplikat?
        if (dupSeqInRequest.contains(seqNo)) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.INVALID)
                    .message("duplicate seqNo in request")
                    .build();
        }

        // 2) Liegt die laufende Nummer (seqNo) innerhalb der erwarteten Gesamtanzahl von Datensätzen?
        if (seqNo < 1 || seqNo > session.getExpectedCount()) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.INVALID)
                    .message("seqNo out of range (1.." + session.getExpectedCount() + ")")
                    .build();
        }

        // 3) Session-Status-Policy
        Optional<InboxItem> existingItem = inboxItemRepository.find(session.getUploadId(), seqNo);

        // Session ist bereits geschlossen - nur Reuploads werden erlaubt
        if (session.getStatus() == UploadSessionStatus.SEALED) {
            return handleSealedItem(item, existingItem, now);
        }

        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .message("upload session is not open")
                    .build();
        }

        // Handling für bereits existierende Datensätze
        if (existingItem.isPresent()) {

            InboxItem existing = existingItem.get();
            return switch (existing.getStatus()) {
                case ERROR -> {
                    existing.setPayload(item.getPayload() != null ? item.getPayload().toString() : null);
                    existing.setStatus(UploadItemStatus.PENDING);
                    existing.setErrorMessage(null);
                    existing.setUpdatedAt(now);
                    yield BatchUploadResult.builder()
                            .seqNo(seqNo)
                            .status(BatchUploadResultStatus.REUPLOADED)
                            .message("re-uploaded from ERROR")
                            .build();
                }
                case PENDING, PROCESSING -> BatchUploadResult.builder()
                        .seqNo(seqNo)
                        .status(BatchUploadResultStatus.CONFLICT)
                        .message("item not finished yet")
                        .build();
                case DONE -> BatchUploadResult.builder()
                        .seqNo(seqNo)
                        .status(BatchUploadResultStatus.CONFLICT)
                        .message("item already processed")
                        .build();
            };
        }

        InboxItem newInboxItem = InboxItem.builder()
                .uploadId(session.getUploadId())
                .seqNo(seqNo)
                .payload(item.getPayload() != null ? item.getPayload().toString() : null)
                .status(UploadItemStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        boolean inserted = inboxItemRepository.saveIfAbsent(newInboxItem);
        if (inserted) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.ACCEPTED)
                    .build();
        } else {
            // selten (Race) – als Konflikt melden
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .message("item already exists")
                    .build();
        }
    }

    private BatchUploadResult handleSealedItem(ItemUploadRequest item,
                                               Optional<InboxItem> existingItem,
                                               LocalDateTime now) {

        int seqNo = item.getSeqNo();

        if (existingItem.isEmpty()) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .message("upload session sealed: new items not allowed")
                    .build();
        }

        // Ersetzen des existierenden Items gegen das neue
        InboxItem existing = existingItem.get();
        if (existing.getStatus() == UploadItemStatus.ERROR) {
            existing.setPayload(item.getPayload() != null ? item.getPayload().toString() : null);
            existing.setStatus(UploadItemStatus.PENDING);
            existing.setErrorMessage(null);
            existing.setUpdatedAt(now);
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.REUPLOADED)
                    .message("re-uploaded from ERROR")
                    .build();
        }

        return BatchUploadResult.builder()
                .seqNo(seqNo)
                .status(BatchUploadResultStatus.CONFLICT)
                .message("upload session sealed: only ERROR items can be re-uploaded")
                .build();
    }

    private void updateSessionAfterChanges(UploadSession session, int newlyAccepted, LocalDateTime now) {
        if (newlyAccepted > 0) {
            session.setReceivedCount(session.getReceivedCount() + newlyAccepted);
        }
        if (session.getStatus() == UploadSessionStatus.ACTIVE
                && session.getReceivedCount() >= session.getExpectedCount()) {
            session.setStatus(UploadSessionStatus.SEALED);
        }
        session.setExpiresAt(now.plus(SESSION_IDLE_TIMEOUT));
        uploadSessionRepository.save(session);
    }
}
