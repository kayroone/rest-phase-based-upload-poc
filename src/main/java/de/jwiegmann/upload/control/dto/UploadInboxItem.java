package de.jwiegmann.upload.control.dto;

import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Ein einzelner Datensatz innerhalb einer UploadSession.
 * Identifiziert über (uploadId, sequenceNumber).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadInboxItem {

    private String uploadId;
    private int seqNo;                // laufende Nummer 1..expectedCount
    private String payload;           // JSON-Blob als String im PoC
    private UploadItemStatus status;  // PENDING, PROCESSING, DONE, ERROR
    private String errorMessage;      // optional, wenn ERROR
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
