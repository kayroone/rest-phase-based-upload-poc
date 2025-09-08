package de.jwiegmann.upload.boundary;

import de.jwiegmann.upload.boundary.dto.*;
import de.jwiegmann.upload.control.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/zahlungsdaten-api/v1")
public class UploadRestController {

    private final UploadService service;

    public UploadRestController(UploadService service) {
        this.service = service;
    }

    /**
     * POST /zahlungsdaten-api/v1/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<?> init(@RequestBody UploadInitRequest req) {

        UploadSession s = service.initUpload(req);

        return ResponseEntity
                .created(URI.create("/zahlungsdaten-api/v1/upload/" + s.getUploadId()))
                .body(Map.of(
                        "uploadId", s.getUploadId(),
                        "status", s.getStatus().name(),
                        "createdAt", s.getCreatedAt().toString(),
                        "expiresAt", s.getExpiresAt().toString(),
                        "vslNummer", s.getVslNummer(),
                        "expected", s.getExpectedCount()
                ));
    }

    /**
     * PUT /zahlungsdaten-api/v1/upload/{uploadId}/items
     */
    @PutMapping("/upload/{uploadId}/items")
    public ResponseEntity<BatchUploadResponse> uploadBatch(
            @PathVariable String uploadId,
            @RequestBody java.util.List<ItemUploadRequest> items
    ) {
        BatchUploadResponse result = service.uploadBatch(uploadId, items);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /zahlungsdaten-api/v1/upload/{uploadId} — Status eines Uploads
     */
    @GetMapping("/upload/{uploadId}")
    public ResponseEntity<UploadStatusResponse> getStatus(@PathVariable String uploadId) {
        return ResponseEntity.ok(service.getStatus(uploadId));
    }

    /**
     * GET /zahlungsdaten-api/v1/upload — Status aller Uploads
     */
    @GetMapping("/upload")
    public ResponseEntity<UploadStatusListResponse> getAllStatus() {
        return ResponseEntity.ok(service.getAllStatus());
    }
}
