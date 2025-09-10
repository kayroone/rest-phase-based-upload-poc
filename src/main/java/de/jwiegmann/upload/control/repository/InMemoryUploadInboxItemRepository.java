package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import de.jwiegmann.upload.control.dto.UploadInboxItem;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Einfaches In-Memory Repository für InboxItems.
 * Map Struktur: Map<uploadId, Map<sequenceNumber, InboxItem>>.
 */
@Repository
public class InMemoryUploadInboxItemRepository {

    private final Map<String, Map<Integer, UploadInboxItem>> store = new ConcurrentHashMap<>();

    public Optional<UploadInboxItem> find(String uploadId, int sequenceNumber) {
        return Optional.ofNullable(store.getOrDefault(uploadId, Map.of()).get(sequenceNumber));
    }

    /**
     * Speichert das Item, falls für diese (uploadId, sequenceNumber) noch keines existiert.
     * Gibt true zurück, wenn gespeichert wurde, false wenn bereits vorhanden.
     */
    public boolean saveIfAbsent(UploadInboxItem item) {
        return store.computeIfAbsent(item.getUploadId(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(item.getSeqNo(), item) == null;
    }

    public List<UploadInboxItem> findAll(String uploadId) {
        return new ArrayList<>(store.getOrDefault(uploadId, Map.of()).values());
    }

    public List<UploadInboxItem> findByStatus(String uploadId, UploadItemStatus status) {
        return store.getOrDefault(uploadId, Map.of()).values().stream()
                .filter(i -> i.getStatus() == status)
                .collect(Collectors.toList());
    }
}
