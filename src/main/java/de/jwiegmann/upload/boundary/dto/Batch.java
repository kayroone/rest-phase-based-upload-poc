package de.jwiegmann.upload.boundary.dto;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
@Builder
public class Batch {

    private final String uploadId;
    private final String batchId;
    private final String payload;
}
