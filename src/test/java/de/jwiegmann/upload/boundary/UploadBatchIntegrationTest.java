package de.jwiegmann.upload.boundary;

import com.jayway.jsonpath.JsonPath;
import de.jwiegmann.upload.boundary.dto.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import de.jwiegmann.upload.control.dto.InboxItem;
import de.jwiegmann.upload.control.repository.InMemoryInboxItemRepository;
import de.jwiegmann.upload.control.repository.InMemoryUploadSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UploadBatchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryUploadSessionRepository sessionRepo;

    @Autowired
    private InMemoryInboxItemRepository itemRepo;

    @Test
    void batchFlow_happyPath_autoSeal_and_status() throws Exception {
        // Init (expected=3)
        String initBody = """
                {"bewNr":"123456789","vslNummer":"vsl00001","anzahlDatensaetzeGesamt":3}
                """;
        String initResponse = mockMvc.perform(post("/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(initResponse, "$.sessionId");

        // Batch mit zwei Items (seq 1,2)
        String batch12 = """
                [
                  {"seqNo":1,"payload":{"betrag":10}},
                  {"seqNo":2,"payload":{"betrag":11}}
                ]
                """;
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("ACCEPTED"));

        // Status: ACTIVE, received=2, expected=3, missingSeq[0]=3
        mockMvc.perform(get("/upload/{id}/status", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.missingSeq[0]").value(3));

        // Batch mit letztem Item (seq 3) → Auto-SEALED
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":3,\"payload\":{\"betrag\":12}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));

        UploadSession session = sessionRepo.find(sessionId).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.SEALED);

        // Status: SEALED, received=3, keine Missing
        mockMvc.perform(get("/upload/{id}/status", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("SEALED"))
                .andExpect(jsonPath("$.received").value(3))
                .andExpect(jsonPath("$.missingSeq.length()").value(0));
    }

    @Test
    void batch_with_duplicate_seq_in_request_marks_invalid() throws Exception {
        // Init (expected=3)
        String sessionId = JsonPath.read(
                mockMvc.perform(post("/upload")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"bewNr\":\"1\",\"vslNummer\":\"A\",\"anzahlDatensaetzeGesamt\":3}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.sessionId"
        );

        // Duplikat der seqNo=2 im gleichen Request → INVALID
        String batch = """
                [
                  {"seqNo":1,"payload":{}},
                  {"seqNo":2,"payload":{}},
                  {"seqNo":2,"payload":{}}
                ]
                """;
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].seqNo", org.hamcrest.Matchers.hasItems(1, 2, 2)))
                .andExpect(jsonPath("$.results[?(@.seqNo==1)][*].status",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("ACCEPTED"))))
                .andExpect(jsonPath("$.results[?(@.seqNo==2)][*].status",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("INVALID"))));
    }

    @Test
    void sealed_policy_blocks_new_items_but_allows_error_reupload() throws Exception {
        // Init (expected=1)
        String sessionId = JsonPath.read(
                mockMvc.perform(post("/upload")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"bewNr\":\"1\",\"vslNummer\":\"B\",\"anzahlDatensaetzeGesamt\":1}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.sessionId"
        );

        // Item seq 1 → Auto-SEALED
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}}]"))
                .andExpect(status().isOk());

        // Neues Item (seq 2) wäre out-of-range → INVALID (oder CONFLICT je Policy)
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":2,\"payload\":{}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("INVALID"));

        // seq 1 auf ERROR setzen
        InboxItem it = itemRepo.find(sessionId, 1).orElseThrow();
        it.setStatus(UploadItemStatus.ERROR);
        it.setUpdatedAt(LocalDateTime.now());

        // Re-Upload von seq 1 ist in SEALED erlaubt → REUPLOADED
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REUPLOADED"));
    }

    @Test
    void duplicate_existing_in_active_conflict() throws Exception {
        // Init (expected=2)
        String sessionId = JsonPath.read(
                mockMvc.perform(post("/upload")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"bewNr\":\"1\",\"vslNummer\":\"C\",\"anzahlDatensaetzeGesamt\":2}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.sessionId"
        );

        // seq 1 akzeptieren
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));

        // seq 1 in ACTIVE erneut (ohne ERROR) → CONFLICT
        mockMvc.perform(put("/upload/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seqNo\":1,\"payload\":{}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("CONFLICT"));
    }
}

