package de.jwiegmann.upload.boundary.dto.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadError {
    private String code;
    private String message;
    private Object details;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    public UploadError(String code, String message, Object details) {
        this.code = code;
        this.message = message;
        this.details = details;
        this.timestamp = LocalDateTime.now();
    }
}
