package de.jwiegmann.upload.boundary;

import de.jwiegmann.upload.boundary.dto.UploadSession;
import de.jwiegmann.upload.control.BatchUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/upload")
public class BatchRestController {

    private final BatchUploadService service;

    public BatchRestController(BatchUploadService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> init() {
        UploadSession session = service.initUpload();
        return ResponseEntity
                .created(URI.create("/uploads/" + session.getUploadId()))
                .body(Map.of(
                        "uploadId", session.getUploadId(),
                        "status", session.getStatus().name(),
                        "createdAt", session.getCreatedAt().toString(),
                        "expiresAt", session.getExpiresAt().toString()
                ));
    }

    @PutMapping("/{uploadId}/batches/{batchId}")
    public ResponseEntity<?> addBatch(
            @PathVariable String uploadId,
            @PathVariable String batchId,
            @RequestBody String payload) {
        service.addBatch(uploadId, batchId, payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<?> complete(@PathVariable String uploadId) {
        service.completeUpload(uploadId);
        return ResponseEntity.ok().build();
    }
}
