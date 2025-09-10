package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.error.UploadError;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UploadErrorFactory {

    public static UploadError validationFailed(String details) {
        return UploadError.builder()
                .code("VALIDATION_FAILED")
                .message("Request validation failed: " + details)
                .details(Map.of("details", details))
                .build();
    }

    public static UploadError itemAlreadyExists(int seqNo) {
        return UploadError.builder()
                .code("ITEM_ALREADY_EXISTS")
                .message("item already exists")
                .details(Map.of("seqNo", seqNo))
                .build();
    }

    public static UploadError duplicateSeqNoInRequest(int seqNo) {
        return UploadError.builder()
                .code("DUPLICATE_SEQ_NO")
                .message("duplicate seqNo in request")
                .details(Map.of("seqNo", seqNo))
                .build();
    }

    public static UploadError invalidSeqNo(int seqNo, int maxExpected) {
        return UploadError.builder()
                .code("INVALID_SEQ_NO")
                .message("seqNo out of range (1.." + maxExpected + ")")
                .details(Map.of("seqNo", seqNo, "maxExpected", maxExpected))
                .build();
    }

    public static UploadError uploadSessionNotOpen(String uploadId) {
        return UploadError.builder()
                .code("UPLOAD_SESSION_NOT_OPEN")
                .message("upload session is not open")
                .details(Map.of("uploadId", uploadId))
                .build();
    }

    public static UploadError reUploadedFromError(int seqNo) {
        return UploadError.builder()
                .code("RE_UPLOADED_FROM_ERROR")
                .message("re-uploaded from ERROR")
                .details(Map.of("seqNo", seqNo))
                .build();
    }

    public static UploadError itemNotFinishedYet(int seqNo) {
        return UploadError.builder()
                .code("ITEM_NOT_FINISHED")
                .message("item not finished yet")
                .details(Map.of("seqNo", seqNo))
                .build();
    }

    public static UploadError itemAlreadyProcessed(int seqNo) {
        return UploadError.builder()
                .code("ITEM_ALREADY_PROCESSED")
                .message("item already processed")
                .details(Map.of("seqNo", seqNo))
                .build();
    }

    public static UploadError sealedNewItemsNotAllowed(String uploadId) {
        return UploadError.builder()
                .code("SEALED_NEW_ITEMS_NOT_ALLOWED")
                .message("upload session sealed: new items not allowed")
                .details(Map.of("uploadId", uploadId))
                .build();
    }

    public static UploadError sealedOnlyErrorItemsAllowed(String uploadId) {
        return UploadError.builder()
                .code("SEALED_ONLY_ERROR_ITEMS_ALLOWED")
                .message("upload session sealed: only ERROR items can be re-uploaded")
                .details(Map.of("uploadId", uploadId))
                .build();
    }
}