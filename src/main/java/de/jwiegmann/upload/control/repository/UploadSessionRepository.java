package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadSession;

import java.util.Optional;

public interface UploadSessionRepository {

    UploadSession save(UploadSession session);
    Optional<UploadSession> find(String uploadId);
}
