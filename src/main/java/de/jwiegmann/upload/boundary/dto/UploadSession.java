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
public class UploadSession {

    private String uploadId;

    @Builder.Default
    private UploadSessionStatus status = UploadSessionStatus.ACTIVE;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

}
