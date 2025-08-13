package de.jwiegmann.upload.boundary.dto;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Data
@RequiredArgsConstructor
@Builder
public class UploadBatch {

    private final String uploadId;
    private final String batchId;
    private final String payload;
    private UploadBatchStatus status;
    private String errorMessage;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
