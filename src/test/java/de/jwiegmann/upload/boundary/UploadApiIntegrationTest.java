package de.jwiegmann.upload.boundary;

import com.jayway.jsonpath.JsonPath;
import de.jwiegmann.upload.boundary.dto.UploadSession;
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

        // 3) Einzel-Status prüfen (ACTIVE, received=2, missingSequence enthält 3)
        mockMvc.perform(get("/zahlungsdaten-api/v1/upload/{uploadId}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId))
                .andExpect(jsonPath("$.uploadStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.missingSeq[0]").value(3));

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
                .andExpect(jsonPath("$.missingSeq.length()").value(0));

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

        // Assert
        List<Integer> seqAll = JsonPath.read(resp, "$.results[*].seqNo");
        assertThat(seqAll).containsExactlyInAnyOrder(1, 2, 2);

        List<String> statusFor1 = JsonPath.read(resp, "$.results[?(@.seqNo == 1)].status");
        assertThat(statusFor1).hasSize(1).allMatch("ACCEPTED"::equals);

        List<String> statusFor2 = JsonPath.read(resp, "$.results[?(@.seqNo == 2)].status");
        assertThat(statusFor2).hasSize(2).allMatch("INVALID"::equals);

        List<String> msgFor2 = JsonPath.read(resp, "$.results[?(@.seqNo == 2)].message");
        assertThat(msgFor2).allMatch("duplicate seqNo in request"::equals);
    }

}
