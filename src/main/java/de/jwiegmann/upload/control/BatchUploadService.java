package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.UploadBatch;
import de.jwiegmann.upload.boundary.dto.UploadBatchStatus;
import de.jwiegmann.upload.boundary.dto.UploadSession;
import de.jwiegmann.upload.boundary.dto.UploadSessionStatus;
import de.jwiegmann.upload.control.repository.InMemoryUploadBatchRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BatchUploadService {

    private final InMemoryUploadBatchRepository batchRepo;
    private final InMemoryUploadSessionRepository sessionRepo;

    public BatchUploadService(InMemoryUploadBatchRepository batchRepo, InMemoryUploadSessionRepository sessionRepo) {
        this.batchRepo = batchRepo;
        this.sessionRepo = sessionRepo;
    }

    /**
     * Initialisiert eine neue Upload-Session mit einer eindeutigen ID und Ablaufzeit.
     */
    public UploadSession initUpload() {

        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plus(Duration.ofHours(2)); // Einfach +2 stunden

        return sessionRepo.save(UploadSession.builder()
                .uploadId(id)
                .status(UploadSessionStatus.ACTIVE)
                .createdAt(now)
                .expiresAt(expires)
                .build());
    }

    /**
     * Fügt der angegebenen Upload-Session einen neuen Batch hinzu.
     * Prüft auf Ablaufzeit, aktiven Status und Duplikate.
     */
    public void addBatch(String uploadId, String batchId, String payload) {

        // 1. Prüfen, ob es eine aktive Session zu der Batch gibt oder diese expired ist
        UploadSession session = sessionRepo.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        if (session.getExpiresAt() != null && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.ABORTED);
            sessionRepo.save(session);
            throw new ResponseStatusException(HttpStatus.GONE, "upload session expired");
        }

        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "upload session is not open");
        }

        // 2. Speichern der zu der Session gehörigen Batch
        LocalDateTime now = LocalDateTime.now();
        UploadBatch newBatch = UploadBatch.builder()
                .uploadId(uploadId)
                .batchId(batchId)
                .payload(payload)
                .status(UploadBatchStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        boolean inserted = batchRepo.saveIfAbsent(newBatch);
        if (!inserted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "upload batch already exists");
        }
    }

    /**
     * Schließt eine Upload-Session ab.
     * Prüft Ablaufzeit und ob noch offene oder fehlerhafte Batches existieren.
     */
    public void completeUpload(String uploadId) {

        // 1. Prüfen, ob es eine aktive Session zu der Batch gibt oder diese expired ist
        UploadSession session = sessionRepo.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        if (session.getExpiresAt() != null && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.ABORTED);
            sessionRepo.save(session);
            throw new ResponseStatusException(HttpStatus.GONE, "upload session expired");
        }

        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "upload session is not open");
        }

        // 2. Prüfen, ob es offene oder fehlerhafte Batches gab
        List<UploadBatch> all = batchRepo.findAll(uploadId);

        List<String> failed = all.stream()
                .filter(b -> b.getStatus() == UploadBatchStatus.ERROR)
                .map(UploadBatch::getBatchId)
                .toList();

        boolean hasPendingOrProcessing = all.stream()
                .anyMatch(b -> b.getStatus() == UploadBatchStatus.PENDING
                        || b.getStatus() == UploadBatchStatus.PROCESSING
                );

        // Bei ERROR: Session abbrechen (ABORTED) und 400 melden
        if (!failed.isEmpty()) {
            session.setStatus(UploadSessionStatus.ABORTED);
            sessionRepo.save(session);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "abort session: some batches failed: " + failed
            );
        }

        // Bei noch offenen Batches: (keinen Abort), aber 400 zurückgeben
        if (hasPendingOrProcessing) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cannot complete: pending or processing batches exist"
            );
        }

        // 3. Keine Errors, nichts offen → Session schließen
        session.setStatus(UploadSessionStatus.COMPLETED);
        sessionRepo.save(session);
    }
}
