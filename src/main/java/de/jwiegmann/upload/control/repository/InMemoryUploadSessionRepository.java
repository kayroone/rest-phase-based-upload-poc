package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadSession;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryUploadSessionRepository {

    private final Map<String, UploadSession> store = new ConcurrentHashMap<>();

    public UploadSession save(UploadSession uploadSession) {
        store.put(uploadSession.getUploadId(), uploadSession);
        return uploadSession;
    }

    public Optional<UploadSession> find(String uploadId) {
        return Optional.ofNullable(store.get(uploadId));
    }

    public List<UploadSession> findAll() {
        return new ArrayList<>(store.values());
    }
}
