package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.*;
import de.jwiegmann.upload.control.repository.InMemoryUploadBatchRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
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
     * Dient gleichzeitig als Inbox-Table.
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

        // 2. Re-Upload-Logik
        var existingOpt = batchRepo.find(uploadId, batchId);
        if (existingOpt.isPresent()) {
            UploadBatch existing = existingOpt.get();
            switch (existing.getStatus()) {
                case ERROR -> {
                    // Erneuter Upload zulassen: Payload ersetzen, Status zurück auf PENDING
                    existing.setPayload(payload);
                    existing.setStatus(UploadBatchStatus.PENDING);
                    existing.setErrorMessage(null);
                    existing.setUpdatedAt(LocalDateTime.now());
                    return; // 200 OK
                }
                case PENDING, PROCESSING ->
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "upload batch is not finished yet");
                case DONE -> throw new ResponseStatusException(HttpStatus.CONFLICT, "upload batch already processed");
            }
        }

        // 3. Speichern der zu der Session gehörigen Batch
        LocalDateTime now = LocalDateTime.now();
        UploadBatch newBatch = UploadBatch.builder()
                .uploadId(uploadId)
                .batchId(batchId)
                .payload(payload)
                // PENDING da alle Batches dann von einem async Job abgearbeitet werden würden
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
     * Prüft Ablaufzeit und Status der Session
     */
    public void completeUpload(String uploadId) {

        UploadSession session = sessionRepo.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        if (session.getExpiresAt() != null && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(UploadSessionStatus.ABORTED);
            sessionRepo.save(session);
            throw new ResponseStatusException(HttpStatus.GONE, "upload session expired");
        }
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            // idempotent erlauben: wenn schon SEALED, 200 zurückgeben
            if (session.getStatus() == UploadSessionStatus.SEALED) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "upload session is not open");
        }

        // Session schließen für neue Batches, Verarbeitung läuft async weiter
        session.setStatus(UploadSessionStatus.SEALED);
        sessionRepo.save(session);
    }

    /**
     * Zeigt den Progress/Metriken für einen initiierten Upload an.
     *
     * @param uploadId Die UploadId zu einer aktiven UploadSession.
     * @return Die verschiedenen Status zu bereits hochgeladenen Batches.
     */
    public UploadStatusResponse getStatus(String uploadId) {

        UploadSession session = sessionRepo.find(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "uploadId not found"));

        var batches = batchRepo.findAll(uploadId);
        int total = batches.size();
        int done = (int) batches.stream().filter(b -> b.getStatus() == UploadBatchStatus.DONE).count();
        int pending = (int) batches.stream().filter(b -> b.getStatus() == UploadBatchStatus.PENDING).count();
        int processing = (int) batches.stream().filter(b -> b.getStatus() == UploadBatchStatus.PROCESSING).count();
        int error = (int) batches.stream().filter(b -> b.getStatus() == UploadBatchStatus.ERROR).count();

        var errors = batches.stream()
                .filter(b -> b.getStatus() == UploadBatchStatus.ERROR)
                .map(UploadBatch::getBatchId)
                .toList();

        return UploadStatusResponse.builder()
                .uploadId(uploadId)
                .sessionStatus(session.getStatus().name())
                .totalBatches(total)
                .done(done)
                .pending(pending)
                .processing(processing)
                .error(error)
                .errorBatchIds(errors)
                .build();
    }
}
