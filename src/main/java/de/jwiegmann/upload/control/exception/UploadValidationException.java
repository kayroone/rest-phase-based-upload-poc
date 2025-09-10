package de.jwiegmann.upload.control.exception;

import lombok.Getter;

@Getter
public class UploadValidationException extends RuntimeException {

    private final String errorCode;

    public UploadValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}