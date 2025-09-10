package de.jwiegmann.upload.boundary.dto.init;

import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadInitResponse {
    private String uploadId;
    private UploadSessionStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private String vslNummer;
    private int expected;
}