package de.jwiegmann.upload.boundary;

import de.jwiegmann.upload.boundary.dto.*;
import de.jwiegmann.upload.control.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/upload")
public class UploadRestController {

    private final UploadService service;

    public UploadRestController(UploadService service) {
        this.service = service;
    }

    /**
     * InitUpload: legt eine neue Upload-Session (genau eine VSL je Session) an.
     * Body enthält Metadaten inkl. anzahlDatensaetzeInsgesamt.
     */
    @PostMapping
    public ResponseEntity<?> init(@RequestBody UploadInitRequest req) {

        UploadSession session = service.initUpload(req);

        return ResponseEntity
                .created(URI.create("/upload/" + session.getSessionId()))
                .body(Map.of(
                        "sessionId", session.getSessionId(),
                        "status", session.getStatus().name(),
                        "createdAt", session.getCreatedAt().toString(),
                        "expiresAt", session.getExpiresAt().toString(),
                        "vslNummer", session.getVslNummer(),
                        "expected", session.getExpectedCount()
                ));
    }


    /**
     * Batch-Upload: mehrere Items (mit eigener seqNo) in einem Request hochladen.
     * Jedes Item wird anhand (sessionId, seqNo) idempotent verarbeitet.
     * <p>
     * Hinweis: Der Service validiert u.a. maxItemsPerRequest, Duplikate im Batch,
     * Sequenzbereich (1..expected) sowie Re-Upload-Policy (ERROR-only).
     */
    @PutMapping("/{sessionId}")
    public ResponseEntity<BatchUploadResponse> putBatch(
            @PathVariable String sessionId,
            @RequestBody List<ItemUploadRequest> batch
    ) {
        BatchUploadResponse result = service.uploadBatch(sessionId, batch);
        return ResponseEntity.ok(result);
    }

    /**
     * Status der Session abrufen (Fortschritt, fehlende/fehlerhafte Sequenzen).
     */
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<UploadStatusResponse> status(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getStatus(sessionId));
    }
}
