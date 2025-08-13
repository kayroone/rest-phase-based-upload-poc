package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadBatch;
import de.jwiegmann.upload.boundary.dto.UploadBatchStatus;

import java.util.List;
import java.util.Optional;

public interface UploadBatchRepository {
    /**
     * Idempotent save: returns true if the batch did not exist and was saved; false if it already existed.
     */
    boolean saveIfAbsent(UploadBatch batch);

    Optional<UploadBatch> find(String uploadId, String vslId);

    List<UploadBatch> findByStatus(String uploadId, UploadBatchStatus status);

    List<UploadBatch> findAll(String uploadId);
}