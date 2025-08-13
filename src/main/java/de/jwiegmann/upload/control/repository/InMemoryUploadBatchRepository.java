package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.UploadBatch;
import de.jwiegmann.upload.boundary.dto.UploadBatchStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryUploadBatchRepository implements UploadBatchRepository {

    // Map<uploadId, Map<vslId, UploadBatch>>
    private final Map<String, Map<String, UploadBatch>> store = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(UploadBatch batch) {
        return store
                .computeIfAbsent(batch.getUploadId(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(batch.getBatchId(), batch) == null;
    }

    @Override
    public Optional<UploadBatch> find(String uploadId, String vslId) {
        return Optional.ofNullable(store.getOrDefault(uploadId, Map.of()).get(vslId));
    }

    @Override
    public List<UploadBatch> findByStatus(String uploadId, UploadBatchStatus status) {
        return store.getOrDefault(uploadId, Map.of()).values().stream()
                .filter(b -> b.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<UploadBatch> findAll(String uploadId) {
        return new ArrayList<>(store.getOrDefault(uploadId, Map.of()).values());
    }
}
