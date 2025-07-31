package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.entity.Batch;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryBatchRepository {
    private final Map<String, Map<String, Batch>> store = new ConcurrentHashMap<>();

    public void save(Batch batch) {
        store.computeIfAbsent(batch.getUploadId(), k -> new ConcurrentHashMap<>())
                .put(batch.getBatchId(), batch);
    }

    public Optional<Batch> find(String uploadId, String vslId) {
        return Optional.ofNullable(store.getOrDefault(uploadId, Map.of()).get(vslId));
    }

    public List<Batch> findPending(String uploadId) {
        return store.getOrDefault(uploadId, Map.of()).values().stream()
                .filter(b -> b.getStatus() == Batch.Status.PENDING)
                .collect(Collectors.toList());
    }

    public List<Batch> findAll(String uploadId) {
        return new ArrayList<>(store.getOrDefault(uploadId, Map.of()).values());
    }
}
