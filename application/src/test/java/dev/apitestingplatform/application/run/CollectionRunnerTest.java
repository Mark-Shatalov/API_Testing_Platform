package dev.apitestingplatform.application.run;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CollectionRunnerTest {
    private static final UUID WORKSPACE = UUID.randomUUID(), COLLECTION = UUID.randomUUID();
    private static RequestDraft draft(String path, Authentication auth, List<AssertionDefinition> checks, List<ExtractionDefinition> extractions) {
        var empty = RequestDraft.empty();
        return new RequestDraft(empty.method(), "http://localhost/" + path, List.of(), List.of(), List.of(), empty.bodyType(), "", List.of(), List.of(),
                "30", empty.redirects(), empty.protocol(), List.of(), auth, checks, extractions);
    }
    private static SavedRequest saved(String name, RequestDraft draft) { return new SavedRequest(new LibraryItem(UUID.randomUUID(), LibraryItem.Kind.REQUEST, COLLECTION, name, 0, 0, Instant.now()), draft); }
    private static RequestDraft plain(String path) { return draft(path, Authentication.none(), List.of(), List.of()); }
    private static Authentication bearer() { return new Authentication(Authentication.Mode.BEARER, "{{token}}", "", "", "", "", Authentication.Placement.HEADER); }
    private static ExtractionDefinition token() { return new ExtractionDefinition(UUID.randomUUID(), "token", "$.token", true); }
    private static AssertionDefinition status(int expected) { return new AssertionDefinition(UUID.randomUUID(), "HTTP status", AssertionDefinition.Type.STATUS_EQUALS, "", Integer.toString(expected), true); }
    private static CollectionSnapshot snapshot(SavedRequest... requests) { return new CollectionSnapshot(COLLECTION, WORKSPACE, "Collection", null, "No environment", List.of(), List.of(), List.of(requests)); }
    private static ExecutionResult response(int status, String body, NetworkError.Type error) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ExecutionResult(status, Map.of(), bytes, 2, bytes.length, error == null ? Optional.empty() : Optional.of(new NetworkError(error, "private-error-message")), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
    }
    private static RequestExecution execution(CompletableFuture<ExecutionResult> future, AtomicBoolean cancelled) {
        return new RequestExecution() {
            public CompletionStage<ExecutionResult> result() { return future; }
            public boolean cancel() { cancelled.set(true); return future.complete(response(0, "", NetworkError.Type.CANCELLED)); }
        };
    }
    private static final class Engine implements HttpExecutionEngine {
        final BlockingQueue<RequestConfiguration> received = new LinkedBlockingQueue<>();
        final AtomicInteger count = new AtomicInteger();
        final Function<Integer, RequestExecution> responder;
        Engine(Function<Integer, RequestExecution> responder) { this.responder = responder; }
        public RequestExecution execute(RequestConfiguration request) { received.add(request); return responder.apply(count.getAndIncrement()); }
        public void close() { }
    }
    private static RequestExecution immediate(ExecutionResult response) { return execution(CompletableFuture.completedFuture(response), new AtomicBoolean()); }
    private static RunReport await(CollectionRunner.Execution execution) throws Exception { return execution.result().toCompletableFuture().get(5, TimeUnit.SECONDS); }

    @Test void runsInOrderExtractsAuthAndKeepsAssertionFailureSeparateFromRequestSuccessAcrossIterations() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, index % 2 == 0 ? "{\"token\":\"private-token-" + index + "\"}" : "{}", null)));
        var login = saved("Login", draft("login", Authentication.none(), List.of(status(200)), List.of(token())));
        var consumer = saved("Consumer", draft("consumer", bearer(), List.of(status(201)), List.of()));
        List<RunEntry> completed = new CopyOnWriteArrayList<>();
        try (var runner = new CollectionRunner(engine)) {
            var report = await(runner.start(snapshot(login, consumer), new RunOptions(2, 0), progress -> { }, completed::add));
            assertThat(report.status()).isEqualTo(RunReport.Status.COMPLETED_WITH_FAILURES);
            assertThat(report.requestFailures()).isZero(); assertThat(report.failedChecks()).isEqualTo(2);
            assertThat(report.entries()).extracting(RunEntry::requestId).containsExactly(login.item().id(), consumer.item().id(), login.item().id(), consumer.item().id());
            assertThat(report.entries()).extracting(RunEntry::iteration).containsExactly(1, 1, 2, 2);
            assertThat(report.entries()).allMatch(entry -> entry.outcome() == RunEntry.Outcome.SUCCESS);
            var sent = List.copyOf(engine.received);
            assertThat(sent.get(1).headers()).anyMatch(header -> header.name().equals("Authorization") && header.value().equals("Bearer private-token-0"));
            assertThat(sent.get(3).headers()).anyMatch(header -> header.value().equals("Bearer private-token-2"));
            assertThat(report.toString()).doesNotContain("private-token-0", "private-token-2"); assertThat(completed).hasSize(4);
        }
    }
    @Test void activeCancellationCancelsHttpSkipsLaterRequestsAndAllowsAnotherRun() throws Exception {
        CompletableFuture<ExecutionResult> waiting = new CompletableFuture<>(); AtomicBoolean cancelled = new AtomicBoolean();
        Engine engine = new Engine(index -> index == 0 ? execution(waiting, cancelled) : immediate(response(200, "{}", null)));
        try (var runner = new CollectionRunner(engine)) {
            var run = runner.start(snapshot(saved("First", plain("first")), saved("Second", plain("second"))), new RunOptions(1, 0), progress -> { }, row -> { });
            assertThat(engine.received.poll(2, TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(() -> runner.start(snapshot(saved("Busy", plain("busy"))), new RunOptions(1, 0), progress -> { }, row -> { })).isInstanceOf(IllegalStateException.class);
            assertThat(run.cancel()).isTrue(); var report = await(run);
            assertThat(cancelled).isTrue(); assertThat(engine.count).hasValue(1);
            assertThat(report.status()).isEqualTo(RunReport.Status.CANCELLED);
            assertThat(report.entries()).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.CANCELLED, RunEntry.Outcome.NOT_RUN);
            assertThat(run.cancel()).isFalse();
            assertThat(await(runner.start(snapshot(saved("Retry", plain("retry"))), new RunOptions(1, 0), progress -> { }, row -> { })).status()).isEqualTo(RunReport.Status.COMPLETED);
        }
    }
    @Test void cancellationDuringDelayPreventsAdmissionOfTheNextRequest() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, "{}", null))); CountDownLatch delaying = new CountDownLatch(1);
        try (var runner = new CollectionRunner(engine)) {
            var run = runner.start(snapshot(saved("First", plain("one")), saved("Second", plain("two"))), new RunOptions(1, 60000),
                    update -> { if (update.phase().startsWith("Waiting")) delaying.countDown(); }, row -> { });
            assertThat(delaying.await(2, TimeUnit.SECONDS)).isTrue(); run.cancel();
            assertThat(await(run).entries()).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.SUCCESS, RunEntry.Outcome.NOT_RUN);
            assertThat(engine.count).hasValue(1);
        }
    }
    @Test void delayAppliesBetweenRequestsAndNotAfterTheFinalRequest() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, "{}", null))); List<Long> times = new CopyOnWriteArrayList<>();
        try (var runner = new CollectionRunner(engine)) {
            await(runner.start(snapshot(saved("One", plain("one")), saved("Two", plain("two"))), new RunOptions(1, 80), progress -> { }, row -> times.add(System.nanoTime())));
            assertThat(TimeUnit.NANOSECONDS.toMillis(times.get(1) - times.get(0))).isGreaterThanOrEqualTo(65);
            // A single-request run with a large configured delay must still complete immediately.
            assertThat(await(runner.start(snapshot(saved("Only", plain("only"))), new RunOptions(1, 60000), progress -> { }, row -> { })).status()).isEqualTo(RunReport.Status.COMPLETED);
        }
    }
    @Test void missingPathsInvalidateOldTokenAndDependentRequestIsNotSent() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, index == 0 ? "{\"token\":\"old-private-token\"}" : "{}", null)));
        try (var runner = new CollectionRunner(engine)) {
            var report = await(runner.start(snapshot(saved("Login", draft("login", Authentication.none(), List.of(), List.of(token()))),
                    saved("Refresh", draft("refresh", Authentication.none(), List.of(), List.of(token()))), saved("Consumer", draft("protected", bearer(), List.of(), List.of()))),
                    new RunOptions(1, 0), progress -> { }, row -> { }));
            assertThat(engine.count).hasValue(2); assertThat(report.entries().get(1).extractionProblems()).isEqualTo(1);
            assertThat(report.entries().get(2).outcome()).isEqualTo(RunEntry.Outcome.INVALID_REQUEST);
            assertThat(report.entries().get(2).details()).contains("Unknown variable");
        }
    }
    @Test void everyIterationAndEveryNewRunStartsWithFreshVariables() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, "{\"token\":\"private-from-prior-iteration\"}", null)));
        var consumer = saved("First", draft("protected", bearer(), List.of(), List.of()));
        var login = saved("Last", draft("login", Authentication.none(), List.of(), List.of(token())));
        try (var runner = new CollectionRunner(engine)) {
            var report = await(runner.start(snapshot(consumer, login), new RunOptions(2, 0), progress -> { }, row -> { }));
            assertThat(report.entries()).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.INVALID_REQUEST, RunEntry.Outcome.SUCCESS, RunEntry.Outcome.INVALID_REQUEST, RunEntry.Outcome.SUCCESS);
            assertThat(await(runner.start(snapshot(consumer), new RunOptions(1, 0), progress -> { }, row -> { })).entries().getFirst().outcome()).isEqualTo(RunEntry.Outcome.INVALID_REQUEST);
            assertThat(engine.count).hasValue(2);
        }
    }
    @Test void reportsNetworkHttpAndValidationFailuresSeparatelyAndContinues() throws Exception {
        Engine engine = new Engine(index -> switch (index) {
            case 0 -> immediate(response(500, "{}", null));
            case 1 -> immediate(response(0, "", NetworkError.Type.TIMEOUT));
            case 2 -> throw new IllegalStateException("private-engine-message");
            default -> immediate(response(200, "{}", null));
        });
        try (var runner = new CollectionRunner(engine)) {
            var report = await(runner.start(snapshot(saved("Bad URL", plain("{{missing}}")), saved("Server error", draft("error", Authentication.none(), List.of(status(500)), List.of())),
                    saved("Timeout", draft("timeout", Authentication.none(), List.of(status(200)), List.of())), saved("Engine error", plain("engine")), saved("Okay", plain("ok"))),
                    new RunOptions(1, 0), progress -> { }, row -> { }));
            assertThat(report.entries()).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.INVALID_REQUEST, RunEntry.Outcome.HTTP_ERROR, RunEntry.Outcome.NETWORK_ERROR, RunEntry.Outcome.NETWORK_ERROR, RunEntry.Outcome.SUCCESS);
            assertThat(report.entries().get(1).assertions().getFirst().outcome()).isEqualTo(AssertionResult.Outcome.PASS);
            assertThat(report.entries().get(2).assertions().getFirst().outcome()).isEqualTo(AssertionResult.Outcome.NOT_RUN);
            assertThat(report.toString()).doesNotContain("private-error-message", "private-engine-message");
        }
    }
    @Test void boundedOptionsEmptyCollectionAndClosedRunnerFailBeforeTrafficAndListenerErrorsAreIsolated() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, "{}", null)));
        assertThatThrownBy(() -> new RunOptions(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOptions(1, 60001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOptions(1000, 0).total(2)).isInstanceOf(IllegalArgumentException.class);
        var runner = new CollectionRunner(engine);
        try {
            assertThatThrownBy(() -> runner.start(snapshot(), new RunOptions(1, 0), progress -> { }, row -> { })).isInstanceOf(IllegalArgumentException.class);
            assertThat(await(runner.start(snapshot(saved("One", plain("one"))), new RunOptions(1, 0), progress -> { throw new IllegalStateException(); }, row -> { throw new IllegalStateException(); })).status()).isEqualTo(RunReport.Status.COMPLETED);
        } finally { runner.close(); }
        assertThatThrownBy(() -> runner.start(snapshot(saved("One", plain("one"))), new RunOptions(1, 0), progress -> { }, row -> { })).isInstanceOf(IllegalStateException.class);
    }
    @Test void masksNewlyExtractedSecretsInChecksOfTheProducingResponse() throws Exception {
        Engine engine = new Engine(index -> immediate(response(200, "{\"token\":\"private-new-token\"}", null)));
        var check = new AssertionDefinition(UUID.randomUUID(), "Token", AssertionDefinition.Type.JSON_PATH_EQUALS, "$.token", "\"different\"", true);
        try (var runner = new CollectionRunner(engine)) {
            var report = await(runner.start(snapshot(saved("Token", draft("token", Authentication.none(), List.of(check), List.of(token())))), new RunOptions(1, 0), progress -> { }, row -> { }));
            assertThat(report.entries().getFirst().assertions().getFirst().actual()).contains("[secret]");
            assertThat(report.toString()).doesNotContain("private-new-token");
        }
    }
}
