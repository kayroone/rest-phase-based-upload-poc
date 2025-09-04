package de.jwiegmann.upload.control.repository;

import de.jwiegmann.upload.boundary.dto.status.UploadItemStatus;
import de.jwiegmann.upload.control.dto.InboxItem;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Einfaches In-Memory Repository für InboxItems.
 * Map Struktur: Map<sessionId, Map<sequenceNumber, InboxItem>>.
 */
@Repository
public class InMemoryInboxItemRepository {

    private final Map<String, Map<Integer, InboxItem>> store = new ConcurrentHashMap<>();

    public Optional<InboxItem> find(String sessionId, int sequenceNumber) {
        return Optional.ofNullable(store.getOrDefault(sessionId, Map.of()).get(sequenceNumber));
    }

    /**
     * Speichert das Item, falls für diese (sessionId, sequenceNumber) noch keines existiert.
     * Gibt true zurück, wenn gespeichert wurde, false wenn bereits vorhanden.
     */
    public boolean saveIfAbsent(InboxItem item) {
        return store.computeIfAbsent(item.getSessionId(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(item.getSeqNo(), item) == null;
    }

    public List<InboxItem> findAll(String sessionId) {
        return new ArrayList<>(store.getOrDefault(sessionId, Map.of()).values());
    }

    public List<InboxItem> findByStatus(String sessionId, UploadItemStatus status) {
        return store.getOrDefault(sessionId, Map.of()).values().stream()
                .filter(i -> i.getStatus() == status)
                .collect(Collectors.toList());
    }
}
