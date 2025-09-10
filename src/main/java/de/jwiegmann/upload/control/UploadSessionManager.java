package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.batch.BatchUploadResult;
import de.jwiegmann.upload.boundary.dto.batch.ItemUploadRequest;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.BatchUploadResultStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import de.jwiegmann.upload.control.dto.UploadValidationResult;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Verwaltet Upload-Sessions: Validierung, Updates und Lifecycle-Management.
 * Zuständig für Session-Expiry, Auto-Sealing und Batch-Größen-Validierung.
 */
@Component
@RequiredArgsConstructor
public class UploadSessionManager {

    private final InMemoryUploadSessionRepository uploadSessionRepository;

    @Value("${upload.session.idle-timeout:PT2H}")
    private Duration sessionIdleTimeout;

    @Value("${upload.max-items-per-request:100}")
    private int maxItemsPerRequest;

    /**
     * Aktualisiert Session nach erfolgreichen Item-Uploads.
     * Erhöht receivedCount, prüft Auto-Sealing und erneuert Expiry-Zeit.
     *
     * @param session       Die zu aktualisierende Session
     * @param newlyAccepted Anzahl neu akzeptierter Items
     * @param now           Aktueller Zeitstempel für Expiry-Berechnung
     */
    public void updateAfterChanges(UploadSession session, int newlyAccepted, LocalDateTime now) {

        if (newlyAccepted > 0) {
            session.setReceivedCount(session.getReceivedCount() + newlyAccepted);
        }

        if (session.getStatus() == UploadSessionStatus.ACTIVE
                && session.getReceivedCount() >= session.getExpectedCount()) {
            session.setStatus(UploadSessionStatus.SEALED);
        }

        session.setExpiresAt(now.plus(sessionIdleTimeout));
        uploadSessionRepository.save(session);
    }

    /**
     * Validiert Session-Level-Constraints vor Batch-Verarbeitung.
     * Prüft Expiry, Batch-Größe und markiert abgelaufene Sessions als ABORTED.
     *
     * @param session Die zu validierende Session
     * @param items   Der Batch von Items
     * @return UploadValidationResult mit Ergebnis und ggf. Fehlermeldung
     */
    public UploadValidationResult validateSession(UploadSession session, List<ItemUploadRequest> items) {

        // Expiry prüfen
        if (session.getExpiresAt() != null && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.ABORTED);
            uploadSessionRepository.save(session);
            return UploadValidationResult.invalid("upload session expired");
        }

        // Batch-Größe prüfen
        if (items == null || items.isEmpty()) {
            return UploadValidationResult.invalid("empty batch");
        }

        if (items.size() > maxItemsPerRequest) {
            return UploadValidationResult.invalid("batch too large (max " + maxItemsPerRequest + ")");
        }

        return UploadValidationResult.valid();
    }

    /**
     * Validiert Session-Status-Policy für einzelne Items.
     * Prüft ob Session in einem Zustand ist, der Item-Uploads erlaubt.
     *
     * @param session Die Session
     * @param seqNo   Sequenznummer des Items (für Error-Messages)
     * @return null bei OK, BatchUploadResult mit CONFLICT bei Policy-Verletzung
     */
    public BatchUploadResult validateSessionPolicy(UploadSession session, int seqNo) {

        // Session ist bereits geschlossen
        if (session.getStatus() == UploadSessionStatus.SEALED) {
            return null; // Weiter mit handleSealedItem
        }

        // Session nicht aktiv
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.uploadSessionNotOpen(session.getUploadId()))
                    .build();
        }

        return null; // Session OK, normale Verarbeitung geht weiter
    }
}
