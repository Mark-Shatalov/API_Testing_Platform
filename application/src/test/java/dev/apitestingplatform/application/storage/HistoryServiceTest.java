package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.application.run.CollectionRunner;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryServiceTest {
    static final class Store {
        final Map<UUID, HistoryRecord> records = new ConcurrentHashMap<>(); final AtomicBoolean fails = new AtomicBoolean();
        final LibraryRepository repository = (LibraryRepository) Proxy.newProxyInstance(LibraryRepository.class.getClassLoader(), new Class<?>[]{LibraryRepository.class}, (proxy, method, args) -> {
            if (method.getName().equals("saveHistory")) {
                if (fails.get()) return CompletableFuture.failedFuture(new StorageException("Fixture write failure"));
                HistoryRecord record = (HistoryRecord) args[0]; records.compute(record.id(), (id, previous) -> previous == null || previous.revision() <= record.revision() ? record : previous); return CompletableFuture.completedFuture(null);
            }
            if (method.getName().equals("close")) return null;
            throw new UnsupportedOperationException(method.getName());
        });
    }
    static RunEntry entry() { return new RunEntry(1, 1, UUID.randomUUID(), "Request", RunEntry.Outcome.NETWORK_ERROR, 0, 25, 50,
            List.of(new AssertionResult(UUID.randomUUID(), "Check", AssertionResult.Outcome.NOT_RUN, "private-expected", "private-actual", "private-reason")),
            List.of(new ExtractionResult(UUID.randomUUID(), "token", ExtractionResult.Outcome.NOT_RUN, "private-extraction-message")), "private-network-error"); }
    static HistoryRecord record(UUID id, long revision) { return HistorySanitizer.create(id, HistoryRecord.Kind.REQUEST, null, null, "Report", System.currentTimeMillis(), System.currentTimeMillis(), HistoryRecord.State.COMPLETED_WITH_FAILURES, 1, revision, List.of(entry())); }

    @Test void sanitizedReportKeepsCountsAndMetricsButDropsComparedValuesAndRawErrors() {
        var record = record(UUID.randomUUID(), 1); var step = record.steps().getFirst();
        assertThat(step.notRun()).isEqualTo(1); assertThat(step.extractionNotRun()).isEqualTo(1); assertThat(step.entry().durationMillis()).isEqualTo(25); assertThat(step.entry().sizeBytes()).isEqualTo(50);
        assertThat(record.toString()).doesNotContain("private-expected", "private-actual", "private-reason", "private-network-error", "private-extraction-message");
        assertThat(HistorySanitizer.clean(record)).isEqualTo(record);
    }
    @Test void globalDetailBudgetKeepsCompleteCountersForLargeReports() {
        var checks = java.util.stream.IntStream.range(0, 100).mapToObj(index -> new AssertionResult(UUID.randomUUID(), "Check " + index, AssertionResult.Outcome.FAIL, "private", "private", "private")).toList();
        var entries = java.util.stream.IntStream.range(0, 1000).mapToObj(index -> new RunEntry(index + 1, 1, UUID.randomUUID(), "Request", RunEntry.Outcome.SUCCESS, 200, 1, 1, checks, List.of(), "")).toList();
        var record = HistorySanitizer.create(UUID.randomUUID(), HistoryRecord.Kind.COLLECTION, null, null, "Large report", System.currentTimeMillis(), System.currentTimeMillis(), HistoryRecord.State.COMPLETED_WITH_FAILURES, 1000, 1, entries);
        assertThat(record.failedChecks()).isEqualTo(100000); assertThat(record.steps()).hasSize(1000); assertThat(record.steps().getLast().detailsOmitted()).isTrue();
        assertThat(record.steps().stream().mapToInt(step -> step.entry().assertions().size()).sum()).isLessThan(2000);
    }
    @Test void boundedRetryBufferKeepsLatestRevisionAndRetriesFinalState() throws Exception {
        Store store = new Store(); store.fails.set(true); HistoryService service = new HistoryService(store.repository); UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.save(record(id, 0)).toCompletableFuture().get()).hasCauseInstanceOf(StorageException.class);
        assertThatThrownBy(() -> service.save(record(id, 1)).toCompletableFuture().get()).hasCauseInstanceOf(StorageException.class);
        assertThat(service.pendingCount()).isEqualTo(1); store.fails.set(false); service.retry().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(service.pendingCount()).isZero(); assertThat(store.records.get(id).revision()).isEqualTo(1);
        store.fails.set(true); for (int i = 0; i < 20; i++) service.save(record(UUID.randomUUID(), 1)); assertThat(service.pendingCount()).isEqualTo(16);
    }
    @Test void manualCancellationFinishesOnceAndLateResponseCannotOverwriteIt() throws Exception {
        Store store = new Store(); var service = new HistoryService(store.repository); var capture = new RequestHistory(service, new HistoryService.Context(UUID.randomUUID(), null, null, "Manual request"));
        capture.begin(); capture.finish(RunEntry.Outcome.CANCELLED, 0, 0, 0, List.of(), List.of(), "cancelled", SecretRedactor.empty()).toCompletableFuture().get();
        capture.finish(RunEntry.Outcome.SUCCESS, 200, 1, 1, List.of(), List.of(), "late", SecretRedactor.empty()).toCompletableFuture().get();
        assertThat(store.records).hasSize(1); assertThat(store.records.values().iterator().next().state()).isEqualTo(HistoryRecord.State.CANCELLED);
    }
    @Test void runnerHistoryFailureDoesNotStopHttpAndRetryPersistsFinalReport() throws Exception {
        Store store = new Store(); store.fails.set(true); var service = new HistoryService(store.repository);
        HttpExecutionEngine engine = new HttpExecutionEngine() {
            public RequestExecution execute(RequestConfiguration request) { return new RequestExecution() {
                public CompletionStage<ExecutionResult> result() { return CompletableFuture.completedFuture(new ExecutionResult(200, Map.of(), new byte[0], 1, 0, Optional.empty(), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1))); }
                public boolean cancel() { return false; }
            }; }
            public void close() { }
        };
        UUID workspace = UUID.randomUUID(), collection = UUID.randomUUID(); var empty = RequestDraft.empty();
        var draft = new RequestDraft(empty.method(), "https://example.test/", empty.params(), empty.headers(), empty.cookies(), empty.bodyType(), empty.bodyText(), empty.form(), empty.multipart(), empty.timeout(), empty.redirects(), empty.protocol());
        var saved = new SavedRequest(new LibraryItem(UUID.randomUUID(), LibraryItem.Kind.REQUEST, collection, "Request", 0, 0, Instant.now()), draft);
        var snapshot = new CollectionSnapshot(collection, workspace, "Collection", null, "None", List.of(), List.of(), List.of(saved));
        try (var runner = new CollectionRunner(engine, service)) {
            var report = runner.start(snapshot, new RunOptions(1, 0), progress -> { }, row -> { }).result().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(report.status()).isEqualTo(RunReport.Status.COMPLETED); assertThat(report.historyWarning()).isNotEmpty(); assertThat(service.pendingCount()).isEqualTo(1);
            store.fails.set(false); service.retry().toCompletableFuture().get(); assertThat(store.records.get(report.id()).state()).isEqualTo(HistoryRecord.State.COMPLETED);
        }
    }
}
