package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.run.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared, bounded retry buffer. SQL/encryption stay on the repository worker. */
public final class HistoryService {
    public record Context(UUID workspaceId, UUID collectionId, UUID requestId, String title) { }
    private final LibraryRepository repository;
    private final Map<UUID, HistoryRecord> pending = new LinkedHashMap<>();
    public HistoryService(LibraryRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public CompletionStage<Void> save(HistoryRecord record) {
        HistoryRecord safe = HistorySanitizer.clean(record);
        synchronized (pending) {
            var previous = pending.get(safe.id()); if (previous == null || previous.revision() <= safe.revision()) pending.put(safe.id(), safe);
            while (pending.size() > 16) pending.remove(pending.keySet().iterator().next());
        }
        return repository.saveHistory(safe).whenComplete((ignored, failure) -> {
            synchronized (pending) {
                var previous = pending.get(safe.id());
                if (failure == null) { if (previous == null || previous.revision() <= safe.revision()) pending.remove(safe.id()); }
                else {
                    if (previous == null || previous.revision() <= safe.revision()) pending.put(safe.id(), safe);
                    while (pending.size() > 16) pending.remove(pending.keySet().iterator().next());
                }
            }
        });
    }
    public CompletionStage<Void> retry() {
        List<HistoryRecord> records; synchronized (pending) { records = List.copyOf(pending.values()); }
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (var record : records) chain = chain.thenCompose(ignored -> save(record));
        return chain;
    }
    public int pendingCount() { synchronized (pending) { return pending.size(); } }
}
