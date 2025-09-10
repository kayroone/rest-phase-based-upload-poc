package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.batch.BatchUploadResponse;
import de.jwiegmann.upload.boundary.dto.batch.BatchUploadResult;
import de.jwiegmann.upload.boundary.dto.batch.ItemUploadRequest;
import de.jwiegmann.upload.boundary.dto.init.UploadInitRequest;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.*;
import de.jwiegmann.upload.control.dto.UploadInboxItem;
import de.jwiegmann.upload.control.dto.UploadValidationResult;
import de.jwiegmann.upload.control.repository.InMemoryUploadInboxItemRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hauptservice für das phasenbasierte Upload-System mit Inbox Pattern.
 * Orchestriert die Upload-Session-Verwaltung und Item-Verarbeitung.
 */
@Service
public class UploadService {

    private final InMemoryUploadSessionRepository uploadSessionRepository;
    private final InMemoryUploadInboxItemRepository inboxItemRepository;
    private final UploadItemProcessor uploadItemProcessor;
    private final UploadSessionManager uploadSessionManager;

    public UploadService(InMemoryUploadSessionRepository uploadSessionRepository,
                         InMemoryUploadInboxItemRepository inboxItemRepository,
                         UploadItemProcessor uploadItemProcessor,
                         UploadSessionManager uploadSessionManager) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.inboxItemRepository = inboxItemRepository;
        this.uploadItemProcessor = uploadItemProcessor;
        this.uploadSessionManager = uploadSessionManager;
    }

    /**
     * Initialisiert eine neue Upload-Session für eine VSL-Nummer.
     * Erstellt eine eindeutige uploadId und setzt Expiry-Zeit.
     *
     * @param req Upload-Initialisierungs-Request mit VSL-Daten
     * @return Neue Upload-Session im ACTIVE Status
     * @throws ResponseStatusException bei ungültigen Request-Daten
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

    /**
     * Verarbeitet einen Batch von Upload-Items für eine bestehende Session.
     * Führt Validierungen durch und verarbeitet Items einzeln mit granularer Fehlerbehandlung.
     *
     * @param uploadId ID der Upload-Session
     * @param batch    Liste der zu verarbeitenden Items
     * @return BatchUploadResponse mit Ergebnis pro Item (ACCEPTED/INVALID/CONFLICT/REUPLOADED)
     * @throws ResponseStatusException wenn uploadId nicht existiert
     */
    public BatchUploadResponse uploadBatch(String uploadId, List<ItemUploadRequest> batch) {

        UploadSession session = uploadSessionRepository.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        // Session-Level Validierung
        UploadValidationResult sessionValidation = uploadSessionManager.validateSession(session, batch);

        if (!sessionValidation.isValid()) {
            List<BatchUploadResult> allInvalid = batch.stream()
                    .map(item -> BatchUploadResult.builder()
                            .seqNo(item.getSeqNo())
                            .status(BatchUploadResultStatus.INVALID)
                            .error(UploadErrorFactory.validationFailed(sessionValidation.getErrorMessage()))
                            .build())
                    .toList();
            return BatchUploadResponse.builder().uploadId(uploadId).results(allInvalid).build();
        }

        // Item-by-Item Processing mit inline Validation
        List<BatchUploadResult> allResults = new ArrayList<>();
        Set<Integer> seenSeqNos = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        int newlyAccepted = 0;
        boolean anyChanged = false;

        for (ItemUploadRequest item : batch) {
            BatchUploadResult result;

            // 1. Duplikat-Check
            if (seenSeqNos.contains(item.getSeqNo())) {
                result = BatchUploadResult.builder()
                        .seqNo(item.getSeqNo())
                        .status(BatchUploadResultStatus.INVALID)
                        .error(UploadErrorFactory.duplicateSeqNoInRequest(item.getSeqNo()))
                        .build();
            }

            // 2. Range-Check
            else if (item.getSeqNo() < 1 || item.getSeqNo() > session.getExpectedCount()) {
                result = BatchUploadResult.builder()
                        .seqNo(item.getSeqNo())
                        .status(BatchUploadResultStatus.INVALID)
                        .error(UploadErrorFactory.invalidSeqNo(item.getSeqNo(), session.getExpectedCount()))
                        .build();
            }

            // 3. Normale Verarbeitung
            else {
                seenSeqNos.add(item.getSeqNo()); // Merken für Duplikat-Check
                result = processSingleItem(session, item, now);

                if (result.getStatus() == BatchUploadResultStatus.ACCEPTED) {
                    newlyAccepted++;
                    anyChanged = true;
                } else if (result.getStatus() == BatchUploadResultStatus.REUPLOADED) {
                    anyChanged = true;
                }
            }

            allResults.add(result);
        }

        // Session updaten falls nötig
        if (anyChanged) {
            uploadSessionManager.updateAfterChanges(session, newlyAccepted, now);
        }

        return BatchUploadResponse.builder()
                .uploadId(uploadId)
                .results(allResults)
                .build();
    }

    /**
     * Liefert detaillierten Status einer Upload-Session inkl. Fortschritt und Diagnose-Daten.
     *
     * @param uploadId ID der Upload-Session
     * @return UploadStatusResponse mit Statistiken und fehlenden/fehlerhaften Sequenzen
     * @throws ResponseStatusException wenn uploadId nicht existiert
     */
    public UploadStatusResponse getStatus(String uploadId) {
        UploadSession s = uploadSessionRepository.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));
        List<UploadInboxItem> items = inboxItemRepository.findAll(uploadId);
        return buildStatusResponse(s, items);
    }

    /**
     * Liefert Übersicht über alle Upload-Sessions mit ihren Status-Informationen.
     *
     * @return UploadStatusListResponse mit Liste aller Upload-Sessions und deren Fortschritt
     */
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

    /**
     * Erstellt Status-Response mit Statistiken aus Session und Items.
     * Berechnet pending/processing/done/error Counts und identifiziert fehlende Sequenzen.
     */
    private UploadStatusResponse buildStatusResponse(UploadSession s, List<UploadInboxItem> items) {
        int expected = s.getExpectedCount();
        int received = items.size();

        int pending = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.PENDING).count();
        int processing = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.PROCESSING).count();
        int done = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.DONE).count();
        int error = (int) items.stream().filter(i -> i.getStatus() == UploadItemStatus.ERROR).count();

        Set<Integer> present = items.stream().map(UploadInboxItem::getSeqNo).collect(java.util.stream.Collectors.toSet());
        List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 1; i <= expected; i++) {
            if (!present.contains(i)) missing.add(i);
        }

        List<Integer> errorSeq = items.stream()
                .filter(i -> i.getStatus() == UploadItemStatus.ERROR)
                .map(UploadInboxItem::getSeqNo)
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

    /**
     * Verarbeitet ein einzelnes Item unter Berücksichtigung von Session-Status und vorhandenen Items.
     * Delegiert an entsprechende Processor-Methoden basierend auf Session-Zustand.
     */
    private BatchUploadResult processSingleItem(UploadSession session,
                                                ItemUploadRequest item,
                                                LocalDateTime now) {

        int seqNo = item.getSeqNo();

        // 1. Session Policy prüfen
        BatchUploadResult policyResult = uploadSessionManager.validateSessionPolicy(session, seqNo);
        if (policyResult != null) {
            return policyResult;
        }

        // 2. Bereits vorhandenes Item laden
        Optional<UploadInboxItem> existingItem = inboxItemRepository.find(session.getUploadId(), seqNo);

        // 3. Handling für item uploads im SEALED zustand
        if (session.getStatus() == UploadSessionStatus.SEALED) {
            return uploadItemProcessor.processItemInSealedSession(item, existingItem, session.getUploadId(), now);
        }

        // 4. Normales handling für neue items
        if (existingItem.isPresent()) {
            return uploadItemProcessor.processExistingItem(existingItem.get(), item, now);
        } else {
            return uploadItemProcessor.processNewItem(session, item, now);
        }
    }
}
