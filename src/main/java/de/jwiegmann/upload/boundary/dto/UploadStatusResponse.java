package de.jwiegmann.upload.boundary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UploadStatusResponse {
    private String uploadId;
    private String sessionStatus; // ACTIVE | COMPLETED | ABORTED
    private int totalBatches;
    private int done;
    private int pending;
    private int processing;
    private int error;
    private List<String> errorBatchIds;
}
