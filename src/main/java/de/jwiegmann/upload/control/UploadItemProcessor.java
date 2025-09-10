package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.batch.BatchUploadResult;
import de.jwiegmann.upload.boundary.dto.batch.ItemUploadRequest;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.BatchUploadResultStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import de.jwiegmann.upload.control.dto.UploadInboxItem;
import de.jwiegmann.upload.control.repository.InMemoryUploadInboxItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Verarbeitet Upload-Items basierend auf ihrem aktuellen Status und Session-Zustand.
 * Implementiert die Geschäftslogik für Idempotenz und Re-Upload-Verhalten.
 */
@Component
@RequiredArgsConstructor
public class UploadItemProcessor {

    private final InMemoryUploadInboxItemRepository inboxItemRepository;

    /**
     * Verarbeitet ein bereits existierendes Item basierend auf seinem aktuellen Status.
     *
     * @param existing Das bereits vorhandene Item aus der Inbox
     * @param item Das neue Upload-Request Item
     * @param now Aktueller Zeitstempel
     * @return BatchUploadResult mit entsprechendem Status (REUPLOADED/CONFLICT)
     */
    public BatchUploadResult processExistingItem(UploadInboxItem existing, ItemUploadRequest item, LocalDateTime now) {

        int seqNo = item.getSeqNo();

        return switch (existing.getStatus()) {
            case ERROR -> {
                updateExistingItem(existing, item, now);
                yield BatchUploadResult.builder()
                        .seqNo(seqNo)
                        .status(BatchUploadResultStatus.REUPLOADED)
                        .build(); // Kein error bei REUPLOADED
            }
            case PENDING, PROCESSING -> BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.itemNotFinishedYet(seqNo))
                    .build();
            case DONE -> BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.itemAlreadyProcessed(seqNo))
                    .build();
        };
    }

    /**
     * Erstellt ein neues Item in der Inbox.
     *
     * @param session Die Upload-Session
     * @param item Das Upload-Request Item
     * @param now Aktueller Zeitstempel
     * @return BatchUploadResult mit Status ACCEPTED oder CONFLICT bei Race-Conditions
     */
    public BatchUploadResult processNewItem(UploadSession session, ItemUploadRequest item, LocalDateTime now) {
        int seqNo = item.getSeqNo();

        UploadInboxItem newUploadInboxItem = UploadInboxItem.builder()
                .uploadId(session.getUploadId())
                .seqNo(seqNo)
                .payload(item.getPayload() != null ? item.getPayload().toString() : null)
                .status(UploadItemStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        boolean inserted = inboxItemRepository.saveIfAbsent(newUploadInboxItem);
        if (inserted) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.ACCEPTED)
                    .build();
        } else {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.itemAlreadyExists(seqNo))
                    .build();
        }
    }

    /**
     * Verarbeitet Item-Uploads in einer bereits versiegelten (SEALED) Session.
     * Erlaubt nur Re-Uploads von Items im ERROR-Status.
     *
     * @param item Das Upload-Request Item
     * @param existingItem Optional vorhandenes Item
     * @param uploadId Die Upload-ID für Error-Messages
     * @param now Aktueller Zeitstempel
     * @return BatchUploadResult mit Status REUPLOADED oder CONFLICT
     */
    public BatchUploadResult processItemInSealedSession(ItemUploadRequest item, Optional<UploadInboxItem> existingItem, String uploadId, LocalDateTime now) {
        int seqNo = item.getSeqNo();

        if (existingItem.isEmpty()) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.sealedNewItemsNotAllowed(uploadId))
                    .build();
        }

        UploadInboxItem existing = existingItem.get();
        if (existing.getStatus() == UploadItemStatus.ERROR) {
            updateExistingItem(existing, item, now);
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.REUPLOADED)
                    .build(); // Kein error bei REUPLOADED
        }

        return BatchUploadResult.builder()
                .seqNo(seqNo)
                .status(BatchUploadResultStatus.CONFLICT)
                .error(UploadErrorFactory.sealedOnlyErrorItemsAllowed(uploadId))
                .build();
    }

    private void updateExistingItem(UploadInboxItem existing, ItemUploadRequest item, LocalDateTime now) {
        existing.setPayload(item.getPayload() != null ? item.getPayload().toString() : null);
        existing.setStatus(UploadItemStatus.PENDING);
        existing.setErrorMessage(null);
        existing.setUpdatedAt(now);
    }
}
