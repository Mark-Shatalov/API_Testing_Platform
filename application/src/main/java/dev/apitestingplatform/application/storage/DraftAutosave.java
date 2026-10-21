package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.storage.RecoveryDraft;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Debounces immutable snapshots. Synchronization orders deletion after any in-flight enqueue. */
public final class DraftAutosave implements AutoCloseable {
    private record Pending(RecoveryDraft draft, ScheduledFuture<?> timer) { }
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("draft-debounce-", 0).factory());
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final LibraryRepository repository;
    private final long delayMillis;
    private final Consumer<Throwable> onFailure;
    private final Consumer<RecoveryDraft> onSaved;
    private boolean closed;
    private boolean paused;
    public DraftAutosave(LibraryRepository repository, Duration delay, Consumer<Throwable> onFailure) {
        this(repository, delay, onFailure, ignored -> { });
    }
    public DraftAutosave(LibraryRepository repository, Duration delay, Consumer<Throwable> onFailure, Consumer<RecoveryDraft> onSaved) {
        this.repository = Objects.requireNonNull(repository); this.onFailure = Objects.requireNonNull(onFailure);
        this.onSaved = Objects.requireNonNull(onSaved);
        delayMillis = delay.toMillis();
        if (delayMillis < 1) throw new IllegalArgumentException("Debounce must be positive");
        scheduler.setRemoveOnCancelPolicy(true);
    }
    public synchronized void changed(RecoveryDraft draft) {
        if (closed || paused) return;
        var previous = pending.remove(draft.tabId());
        if (previous != null) previous.timer().cancel(false);
        if (pending.size() >= 128) { onFailure.accept(new StorageException("Too many open drafts.")); return; }
        var timer = scheduler.schedule(() -> persist(draft), delayMillis, TimeUnit.MILLISECONDS);
        pending.put(draft.tabId(), new Pending(draft, timer));
    }
    private synchronized void persist(RecoveryDraft draft) {
        if (closed || paused) return;
        var entry = pending.get(draft.tabId());
        if (entry == null || entry.draft() != draft) return;
        pending.remove(draft.tabId());
        repository.putDraft(draft).whenComplete((ignored, failure) -> { if (failure != null) onFailure.accept(failure); else onSaved.accept(draft); });
    }
    public synchronized CompletionStage<Void> clear(UUID tabId) {
        var entry = pending.remove(tabId); if (entry != null) entry.timer().cancel(false);
        return repository.deleteDraft(tabId);
    }
    @Override public synchronized void close() {
        closed = true; pending.values().forEach(entry -> entry.timer().cancel(false)); pending.clear(); scheduler.shutdownNow();
    }
    public synchronized void pause() {
        paused = true; pending.values().forEach(entry -> entry.timer().cancel(false)); pending.clear();
    }
    public synchronized void resume() { if (!closed) paused = false; }
}
