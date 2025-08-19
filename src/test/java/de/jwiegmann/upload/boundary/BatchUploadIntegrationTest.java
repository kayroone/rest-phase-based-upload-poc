package de.jwiegmann.upload.boundary;

import de.jwiegmann.upload.boundary.dto.UploadBatch;
import de.jwiegmann.upload.boundary.dto.UploadBatchStatus;
import de.jwiegmann.upload.control.repository.InMemoryUploadBatchRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchUploadIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private InMemoryUploadBatchRepository batchRepo;

    @Autowired
    private InMemoryUploadSessionRepository sessionRepo;

    @Test
    void init_addBatch_status_complete_flow() {

        // 1. INIT
        ResponseEntity<Map> initResp = rest.postForEntity("/upload", null, Map.class);
        assertThat(initResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(initResp.getHeaders().getLocation()).isNotNull();
        assertThat(initResp.getBody()).containsKeys("uploadId", "status", "createdAt", "expiresAt");
        String uploadId = (String) initResp.getBody().get("uploadId");

        // 2. addBatch
        String batchId = "vsl00001";
        String payload = """
                    [{"betrag":10,"zeitstempelWertstellung":"2025-07-01T00:00:00Z","verwendungszweck":"Testzahlung 1"}]
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var putResp = rest.exchange(
                "/upload/{uploadId}/batches/{batchId}",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Void.class,
                uploadId, batchId
        );
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. status: pending=1, done=0, error=0, errorBatchIds=[]
        ResponseEntity<Map> status1 = rest.getForEntity("/upload/{id}/status", Map.class, uploadId);
        assertThat(status1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) status1.getBody().get("totalBatches")).intValue()).isEqualTo(1);
        assertThat(((Number) status1.getBody().get("pending")).intValue()).isEqualTo(1);
        assertThat(((Number) status1.getBody().get("done")).intValue()).isEqualTo(0);
        assertThat(((Number) status1.getBody().get("error")).intValue()).isEqualTo(0);
        assertThat((List<?>) status1.getBody().getOrDefault("errorBatchIds", List.of())).isEmpty();

        // 4. complete bei pending -> 200 (Session wird SEALED)
        ResponseEntity<Void> completeAccepted =
                rest.postForEntity("/upload/{id}/complete", null, Void.class, uploadId);
        assertThat(completeAccepted.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 5. Verarbeitet simulieren: Batch -> DONE
        List<UploadBatch> batches = batchRepo.findAll(uploadId);
        assertThat(batches).hasSize(1);
        batches.get(0).setStatus(UploadBatchStatus.DONE);

        // 6. complete -> 200
        ResponseEntity<Void> completeOk = rest.postForEntity("/upload/{id}/complete", null, Void.class, uploadId);
        assertThat(completeOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 7. status nach complete: sessionStatus=COMPLETED, done=1
        ResponseEntity<Map> status2 = rest.getForEntity("/upload/{id}/status", Map.class, uploadId);
        assertThat(status2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status2.getBody().get("sessionStatus")).isEqualTo("SEALED");
        assertThat(((Number) status2.getBody().get("done")).intValue()).isEqualTo(1);
    }

    @Test
    void error_and_reupload_flow() {
        // INIT
        String uploadId = (String) rest.postForEntity("/upload", null, Map.class)
                .getBody().get("uploadId");

        // addBatch
        String batchId = "vsl00002";
        String payload = "[{\"betrag\":12}]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var first = rest.exchange(
                "/upload/{uploadId}/batches/{batchId}",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Void.class,
                uploadId, batchId
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // künstlich auf ERROR setzen
        var list = batchRepo.findAll(uploadId);
        assertThat(list).hasSize(1);
        UploadBatch b = list.get(0);
        b.setStatus(UploadBatchStatus.ERROR);

        // status zeigt error=1 und errorBatchIds enthält batchId
        ResponseEntity<Map> statusErr = rest.getForEntity("/upload/{id}/status", Map.class, uploadId);
        assertThat(((Number) statusErr.getBody().get("error")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<String> errorIds = (List<String>) statusErr.getBody().get("errorBatchIds");
        assertThat(errorIds).containsExactly(batchId);

        // RE-UPLOAD derselben batchId -> erlaubt, setzt zurück auf PENDING
        String correctedPayload = "[{\"betrag\":13}]";
        var reupload = rest.exchange(
                "/upload/{uploadId}/batches/{batchId}",
                HttpMethod.PUT,
                new HttpEntity<>(correctedPayload, headers),
                Void.class,
                uploadId, batchId
        );
        assertThat(reupload.getStatusCode()).isEqualTo(HttpStatus.OK);

        // status: pending=1, error=0
        ResponseEntity<Map> statusAfterReupload = rest.getForEntity("/upload/{id}/status", Map.class, uploadId);
        assertThat(((Number) statusAfterReupload.getBody().get("pending")).intValue()).isEqualTo(1);
        assertThat(((Number) statusAfterReupload.getBody().get("error")).intValue()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> errorIdsAfter = (List<String>) statusAfterReupload.getBody().getOrDefault("errorBatchIds", List.of());
        assertThat(errorIdsAfter).isEmpty();
    }

    @Test
    void duplicate_on_pending_returns_conflict() {
        // INIT
        String uploadId = (String) rest.postForEntity("/upload", null, Map.class)
                .getBody().get("uploadId");

        String batchId = "vsl00003";
        String payload = "[{\"betrag\":1}]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // first
        var ok = rest.exchange(
                "/upload/{uploadId}/batches/{batchId}",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Void.class,
                uploadId, batchId
        );
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        // duplicate while PENDING -> 409
        var dup = rest.exchange(
                "/upload/{uploadId}/batches/{batchId}",
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                String.class,
                uploadId, batchId
        );
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
