package de.jwiegmann.upload.entity;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Data
@RequiredArgsConstructor
@Builder
public class Batch {

    private final String uploadId;
    private final String batchId;
    private final String payload;
    private Status status;
    private String errorMessage;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {PENDING, PROCESSING, DONE, ERROR}
}
