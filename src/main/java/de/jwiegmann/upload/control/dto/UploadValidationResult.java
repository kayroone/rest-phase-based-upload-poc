package de.jwiegmann.upload.control.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UploadValidationResult {

    private boolean valid;
    private String errorMessage;

    public static UploadValidationResult valid() {
        return new UploadValidationResult(true, null);
    }

    public static UploadValidationResult invalid(String message) {
        return new UploadValidationResult(false, message);
    }
}
