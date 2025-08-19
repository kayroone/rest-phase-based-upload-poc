package de.jwiegmann.upload.boundary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadBatch {

    private String uploadId;
    private String batchId;
    private String payload;

    @Builder.Default
    private UploadBatchStatus status = UploadBatchStatus.PENDING;

    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
