package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadSession;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryUploadSessionRepository {

    private final Map<String, UploadSession> store = new ConcurrentHashMap<>();

    public UploadSession save(UploadSession session) {
        store.put(session.getSessionId(), session);
        return session;
    }

    public Optional<UploadSession> find(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }
}
