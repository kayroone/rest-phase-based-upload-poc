package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.batch.ItemUploadRequest;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import de.jwiegmann.upload.control.dto.UploadValidationResult;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UploadSessionManager {

    private final InMemoryUploadSessionRepository uploadSessionRepository;

    @Value("${upload.session.idle-timeout:PT2H}")
    private Duration sessionIdleTimeout;

    @Value("${upload.max-items-per-request:100}")
    private int maxItemsPerRequest;

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
}
