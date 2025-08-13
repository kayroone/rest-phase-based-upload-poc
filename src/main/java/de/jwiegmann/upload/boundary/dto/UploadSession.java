package de.jwiegmann.upload.boundary.dto;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Data
@RequiredArgsConstructor
@Builder
public class UploadSession {

    private final String uploadId;
    private UploadSessionStatus status;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;

}
