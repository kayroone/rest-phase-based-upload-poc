package de.jwiegmann.upload.boundary.dto.status;

/**
 * Status eines Items nach einem Batch-Upload.
 */
public enum BatchUploadResultStatus {
    ACCEPTED,   // neu angenommen (PENDING)
    REUPLOADED,   // Re-Upload eines ERROR-Items, Payload ersetzt
    CONFLICT,   // nicht erlaubt (bereits PENDING/PROCESSING/DONE)
    INVALID     // ungültige seqNo oder Regel verletzt
}
