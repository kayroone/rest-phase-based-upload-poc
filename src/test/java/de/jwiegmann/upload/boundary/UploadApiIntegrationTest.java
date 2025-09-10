package de.jwiegmann.upload.boundary;

import com.jayway.jsonpath.JsonPath;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UploadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryUploadSessionRepository sessionRepo;

    @Test
    void fullFlow_singleAndAllStatus() throws Exception {

        // 1) InitUpload
        String initBody = """
                {"bewNr":"123456789","vslNummer":"vsl00001","anzahlDatensaetzeInsgesamt":3}
                """;

        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.uploadId").exists())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expected").value(3))
                .andReturn()
                .getResponse().getContentAsString();

        String uploadId = JsonPath.read(initResp, "$.uploadId");
        assertThat(uploadId).isNotBlank();

        // 2) BatchUpload (zwei Items)
        String batch2 = """
                [
                  {"seqNo":1,"payload":{"betrag":100}},
                  {"seqNo":2,"payload":{"betrag":200}}
                ]
                """;
        mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[?(@.seqNo==1)].status", hasItem("ACCEPTED")))
                .andExpect(jsonPath("$.results[?(@.seqNo==2)].status", hasItem("ACCEPTED")));

        // 3) Einzel-Status prüfen (ACTIVE, received=2, missingSeq enthält 3)
        mockMvc.perform(get("/zahlungsdaten-api/v1/upload/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId))
                .andExpect(jsonPath("$.uploadStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.missingSeq[0]").value(3)); // KORRIGIERT: missingSeq statt missingSeq

        // 4) Letztes Item → Auto-SEALED
        String batch3 = """
                [
                  {"seqNo":3,"payload":{"betrag":300}}
                ]
                """;
        mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));

        UploadSession s = sessionRepo.find(uploadId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(UploadSessionStatus.SEALED);

        // 5) Einzel-Status jetzt SEALED & vollständig
        mockMvc.perform(get("/zahlungsdaten-api/v1/upload/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("SEALED"))
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.received").value(3))
                .andExpect(jsonPath("$.missingSeq.length()").value(0)); // KORRIGIERT: missingSeq

        // 6) Alle Uploads – Liste enthält mindestens unseren Upload
        mockMvc.perform(get("/zahlungsdaten-api/v1/upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[?(@.uploadId=='" + uploadId + "')]").exists());
    }

    @Test
    void batch_with_duplicate_seq_in_request_marks_invalid() throws Exception {

        // Init
        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bewNr\":\"A1\",\"vslNummer\":\"VSL-1\",\"anzahlDatensaetzeInsgesamt\":3}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(initResp, "$.uploadId");

        // Batch mit Duplikat (seqNo=2 doppelt)
        String batch = """
                [
                  {"seqNo":1,"payload":{}},
                  {"seqNo":2,"payload":{}},
                  {"seqNo":2,"payload":{}}
                ]
                """;

        String resp = mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // Assert: Alle 3 Items vorhanden (1x seqNo=1, 2x seqNo=2)
        List<Integer> seqAll = JsonPath.read(resp, "$.results[*].seqNo");
        assertThat(seqAll).containsExactlyInAnyOrder(1, 2, 2);

        // Assert: seqNo=1 sollte ACCEPTED sein
        List<String> statusFor1 = JsonPath.read(resp, "$.results[?(@.seqNo == 1)].status");
        assertThat(statusFor1).hasSize(1).allMatch("ACCEPTED"::equals);

        // Assert: Von den zwei seqNo=2 Items sollte eine ACCEPTED und eine INVALID sein
        List<String> statusFor2 = JsonPath.read(resp, "$.results[?(@.seqNo == 2)].status");
        assertThat(statusFor2).hasSize(2);
        assertThat(statusFor2).containsExactlyInAnyOrder("ACCEPTED", "INVALID");

        // Assert: Nur das Duplikat (zweite seqNo=2) sollte Error haben
        List<String> errorCodesFor2 = JsonPath.read(resp, "$.results[?(@.seqNo == 2 && @.status == 'INVALID')].error.code");
        assertThat(errorCodesFor2).hasSize(1).allMatch("DUPLICATE_SEQ_NO"::equals);

        List<String> errorMessagesFor2 = JsonPath.read(resp, "$.results[?(@.seqNo == 2 && @.status == 'INVALID')].error.message");
        assertThat(errorMessagesFor2).hasSize(1).allMatch("duplicate seqNo in request"::equals);
    }

    @Test
    void batch_with_invalid_sequence_range_marks_invalid() throws Exception {
        // Init mit expectedCount=3
        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bewNr\":\"A1\",\"vslNummer\":\"VSL-1\",\"anzahlDatensaetzeInsgesamt\":3}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(initResp, "$.uploadId");

        // Batch mit ungültigen seqNos (0, 4, 5 sind außerhalb 1-3)
        String batch = """
                [
                  {"seqNo":0,"payload":{}},
                  {"seqNo":1,"payload":{}},
                  {"seqNo":4,"payload":{}},
                  {"seqNo":5,"payload":{}}
                ]
                """;

        String resp = mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // seqNo=1 sollte ACCEPTED sein
        List<String> statusFor1 = JsonPath.read(resp, "$.results[?(@.seqNo == 1)].status");
        assertThat(statusFor1).hasSize(1).allMatch("ACCEPTED"::equals);

        // seqNo=0,4,5 sollten INVALID sein
        List<String> statusFor0 = JsonPath.read(resp, "$.results[?(@.seqNo == 0)].status");
        List<String> statusFor4 = JsonPath.read(resp, "$.results[?(@.seqNo == 4)].status");
        List<String> statusFor5 = JsonPath.read(resp, "$.results[?(@.seqNo == 5)].status");

        assertThat(statusFor0).hasSize(1).allMatch("INVALID"::equals);
        assertThat(statusFor4).hasSize(1).allMatch("INVALID"::equals);
        assertThat(statusFor5).hasSize(1).allMatch("INVALID"::equals);

        // Error codes prüfen
        List<String> errorCodes = JsonPath.read(resp, "$.results[?(@.status == 'INVALID')].error.code");
        assertThat(errorCodes).allMatch("INVALID_SEQ_NO"::equals);
    }

    @Test
    void batch_upload_to_sealed_session_allows_only_error_reuploads() throws Exception {
        // Init und Session zum SEALED Status bringen
        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bewNr\":\"A1\",\"vslNummer\":\"VSL-1\",\"anzahlDatensaetzeInsgesamt\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(initResp, "$.uploadId");

        // Alle Items uploaden → SEALED
        mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}},{\"seqNo\":2,\"payload\":{}}]"))
                .andExpect(status().isOk());

        // Verify SEALED
        UploadSession session = sessionRepo.find(uploadId).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.SEALED);

        // Versuche bestehendes Item zu re-uploaden → sollte CONFLICT sein
        String resp = mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{\"new\":\"data\"}}]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> statuses = JsonPath.read(resp, "$.results[*].status");

        // Item sollte CONFLICT sein (da nicht im ERROR Status)
        assertThat(statuses).containsExactly("CONFLICT");

        // Vollständige Error-Struktur validieren
        String errorCode = JsonPath.read(resp, "$.results[0].error.code");
        String errorMessage = JsonPath.read(resp, "$.results[0].error.message");
        String errorTimestamp = JsonPath.read(resp, "$.results[0].error.timestamp");

        assertThat(errorCode).isEqualTo("SEALED_ONLY_ERROR_ITEMS_ALLOWED");
        assertThat(errorMessage).isEqualTo("upload session sealed: only ERROR items can be re-uploaded");
        assertThat(errorTimestamp).isNotNull();
    }

    @Test
    void batch_upload_empty_batch_validation() throws Exception {
        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bewNr\":\"A1\",\"vslNummer\":\"VSL-1\",\"anzahlDatensaetzeInsgesamt\":3}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(initResp, "$.uploadId");

        // Leerer Batch sollte alle Items als INVALID markieren
        String resp = mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Sollte leere results haben oder Validation Error
        List<Object> results = JsonPath.read(resp, "$.results");
        assertThat(results).isEmpty(); // Leerer Batch = leere Results
    }

    @Test
    void batch_upload_nonexistent_uploadId_returns_404() throws Exception {
        String fakeUploadId = "00000000-0000-0000-0000-000000000000";

        mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", fakeUploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}}]"))
                .andExpect(status().isNotFound());
    }

    @Test
    void upload_status_includes_correct_statistics() throws Exception {
        // Init
        String initResp = mockMvc.perform(post("/zahlungsdaten-api/v1/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bewNr\":\"A1\",\"vslNummer\":\"VSL-1\",\"anzahlDatensaetzeInsgesamt\":5}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(initResp, "$.uploadId");

        // Upload nur 2 von 5 Items
        mockMvc.perform(put("/zahlungsdaten-api/v1/upload/{uploadId}/items", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}},{\"seqNo\":3,\"payload\":{}}]"))
                .andExpect(status().isOk());

        // Status prüfen
        mockMvc.perform(get("/zahlungsdaten-api/v1/upload/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.expected").value(5))
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.missingSeq").isArray())
                .andExpect(jsonPath("$.missingSeq.length()").value(3))
                .andExpect(jsonPath("$.missingSeq", hasItem(2)))
                .andExpect(jsonPath("$.missingSeq", hasItem(4)))
                .andExpect(jsonPath("$.missingSeq", hasItem(5)));
    }
}