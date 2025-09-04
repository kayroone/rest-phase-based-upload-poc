package de.jwiegmann.upload.boundary.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request-DTO für ein einzelnes Item innerhalb eines Batch-Uploads.
 * Jedes Item hat eine eindeutige seqNo innerhalb der UploadSession.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemUploadRequest {
    private int seqNo;
    private JsonNode payload;
}

