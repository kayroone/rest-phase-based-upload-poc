package de.jwiegmann.upload.control;

import de.jwiegmann.upload.control.repository.InMemoryBatchRepository;
import de.jwiegmann.upload.entity.Batch;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class BatchUploadService {

    private final InMemoryBatchRepository repo;

    public BatchUploadService(InMemoryBatchRepository repo) {
        this.repo = repo;
    }

    public String initUpload() {
        return UUID.randomUUID().toString();
    }

    public void addBatch(String uploadId, String batchId, String payload) {
        repo.find(uploadId, batchId).ifPresent(b -> {
            // idempotent: ignore duplicate
            return;
        });
        repo.save(Batch.builder()
                .uploadId(uploadId)
                .batchId(batchId)
                .payload(payload)
                .build()
        );
    }

    public void completeUpload(String uploadId) {
        List<Batch> all = repo.findAll(uploadId);
        List<Batch> errors = all.stream().filter(b -> b.getStatus() == Batch.Status.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Some batches failed: " + errors.stream().map(Batch::getBatchId).toList());
        }
        // trigger processing or return summary
    }
}
