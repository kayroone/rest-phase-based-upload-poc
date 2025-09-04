package de.jwiegmann.upload.boundary.dto;

import de.jwiegmann.upload.boundary.dto.status.BatchUploadResultStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ergebnis für ein einzelnes Item innerhalb eines Batch-Uploads.
 * Gibt an, wie der Server mit diesem Item umgegangen ist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUploadResult {
    private int seqNo;
    private BatchUploadResultStatus status;
    private String message;
}
