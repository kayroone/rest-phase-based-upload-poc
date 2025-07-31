package de.jwiegmann.upload.boundary;

import de.jwiegmann.upload.control.BatchUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        String uploadId = service.initUpload();
        return ResponseEntity.ok(Map.of("uploadId", uploadId));
    }

    @PutMapping("/{uploadId}/batches/{vslId}")
    public ResponseEntity<?> addBatch(
            @PathVariable String uploadId,
            @PathVariable String vslId,
            @RequestBody String payload) {
        service.addBatch(uploadId, vslId, payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<?> complete(@PathVariable String uploadId) {
        service.completeUpload(uploadId);
        return ResponseEntity.ok().build();
    }
}
