package de.jwiegmann.upload.boundary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response-DTO für den Batch-Upload: fasst die Ergebnisse pro Item zusammen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUploadResponse {
    private String sessionId;
    private List<BatchUploadResult> results;
}

