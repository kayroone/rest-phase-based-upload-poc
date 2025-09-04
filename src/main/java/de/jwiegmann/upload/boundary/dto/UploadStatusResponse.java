package de.jwiegmann.upload.boundary.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response für den Status-Endpunkt.
 * Liefert Fortschritt, Zähler und fehlerhafte/fehlende Sequenzen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadStatusResponse {

    private String sessionId;
    private String sessionStatus; // ACTIVE | SEALED | COMPLETED | ABORTED
    private int expected;
    private int received;
    private int pending;
    private int processing;
    private int done;
    private int error;

    @JsonProperty("missingSeq")
    private List<Integer> missingSequence;

    @JsonProperty("errorSeq")
    private List<Integer> errorSequence;
}
