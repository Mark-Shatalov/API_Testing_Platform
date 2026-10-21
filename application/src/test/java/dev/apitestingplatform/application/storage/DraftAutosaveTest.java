package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.storage.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class DraftAutosaveTest {
    private static RecoveryDraft draft(UUID id, String title) {
        return new RecoveryDraft(id, null, UUID.randomUUID(), title, RequestDraft.empty(), Instant.now());
    }
    @Test void rapidEditsWriteOnlyLatestSnapshot() throws Exception {
        List<RecoveryDraft> writes = new CopyOnWriteArrayList<>(); CountDownLatch persisted = new CountDownLatch(1);
        var repository = fake((method, args) -> { if (method.equals("putDraft")) { writes.add((RecoveryDraft) args[0]); persisted.countDown(); } return CompletableFuture.completedFuture(null); });
        try (var autosave = new DraftAutosave(repository, Duration.ofMillis(100), failure -> fail("Unexpected autosave failure"))) {
            UUID id = UUID.randomUUID();
            for (int i = 0; i < 50; i++) autosave.changed(draft(id, "Edit " + i));
            assertThat(persisted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(writes).hasSize(1); assertThat(writes.getFirst().title()).isEqualTo("Edit 49");
        }
    }
    @Test void clearAndCloseCancelPendingSnapshotsAndPauseCanResume() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var repository = fake((method, args) -> { calls.add(method); return CompletableFuture.completedFuture(null); });
        var autosave = new DraftAutosave(repository, Duration.ofMillis(60), ignored -> { });
        UUID id = UUID.randomUUID(); autosave.changed(draft(id, "Before clear")); autosave.clear(id);
        Thread.sleep(100); assertThat(calls).containsExactly("deleteDraft");
        autosave.changed(draft(id, "Before pause")); autosave.pause(); Thread.sleep(100);
        assertThat(calls).containsExactly("deleteDraft");
        autosave.resume(); autosave.changed(draft(id, "After resume")); Thread.sleep(150);
        assertThat(calls).containsExactly("deleteDraft", "putDraft");
        autosave.changed(draft(id, "Before close")); autosave.close(); Thread.sleep(100);
        assertThat(calls).containsExactly("deleteDraft", "putDraft");
    }
    @Test void failedWriteIsReportedAndLaterEditRetriesWithoutMutatingSnapshot() throws Exception {
        var failed = new CountDownLatch(1); var success = new CountDownLatch(1); AtomicInteger attempts = new AtomicInteger();
        var repository = fake((method, args) -> {
            if (attempts.incrementAndGet() == 1) return CompletableFuture.failedFuture(new StorageException("Simulated failure"));
            success.countDown(); return CompletableFuture.completedFuture(null);
        });
        try (var autosave = new DraftAutosave(repository, Duration.ofMillis(40), failure -> failed.countDown())) {
            UUID id = UUID.randomUUID(); autosave.changed(draft(id, "Still in editor"));
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            autosave.changed(draft(id, "Retry")); assertThat(success.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts).hasValue(2);
        }
    }
    private interface Call { Object run(String method, Object[] arguments); }
    private static LibraryRepository fake(Call call) {
        return (LibraryRepository) Proxy.newProxyInstance(LibraryRepository.class.getClassLoader(), new Class<?>[]{LibraryRepository.class},
                (proxy, method, args) -> call.run(method.getName(), args));
    }
}
