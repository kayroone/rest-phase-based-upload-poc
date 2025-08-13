package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadSession;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryUploadSessionRepository implements UploadSessionRepository {

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    @Override
    public UploadSession save(UploadSession session) {
        sessions.put(session.getUploadId(), session);
        return session;
    }

    @Override
    public Optional<UploadSession> find(String uploadId) {
        return Optional.ofNullable(sessions.get(uploadId));
    }
}
