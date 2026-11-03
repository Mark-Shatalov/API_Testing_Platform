package dev.apitestingplatform.application.run;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.SavedRequest;
import dev.apitestingplatform.domain.variables.Variable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One bounded background worker, sequential HTTP admission and cooperative cancellation. Does not own the HTTP engine. */
public final class CollectionRunner implements AutoCloseable {
    public record Progress(int completed, int total, int iteration, String phase) { }
    public interface Execution {
        CompletionStage<RunReport> result();
        boolean cancel();
    }
    private final HttpExecutionEngine engine;
    private final HistoryService history;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("collection-run-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private Job current;
    private boolean closed;
    private dev.apitestingplatform.application.operations.EventLog log = dev.apitestingplatform.application.operations.EventLog.none();
    public void setEventLog(dev.apitestingplatform.application.operations.EventLog value) { log = Objects.requireNonNull(value); }
    public CollectionRunner(HttpExecutionEngine engine) { this(engine, null); }
    public CollectionRunner(HttpExecutionEngine engine, HistoryService history) { this.engine = Objects.requireNonNull(engine); this.history = history; }
    public synchronized CompletionStage<Void> cancelAndWait() {
        if (current == null) return CompletableFuture.completedFuture(null);
        Job stopping = current; stopping.cancel();
        return stopping.result().thenApply(report -> (Void) null).toCompletableFuture().orTimeout(30, TimeUnit.SECONDS);
    }
    public CompletionStage<Void> stopped() {
        return CompletableFuture.runAsync(() -> { try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new CompletionException(new IllegalStateException("Runner is still stopping.")); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CompletionException(interrupted); } });
    }

    public synchronized Execution start(CollectionSnapshot snapshot, RunOptions options, Consumer<Progress> progress, Consumer<RunEntry> completed) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(options); Objects.requireNonNull(progress); Objects.requireNonNull(completed);
        int total = options.total(snapshot.requests().size());
        if (closed) throw new IllegalStateException("Collection runner is closed.");
        if (current != null) throw new IllegalStateException("A collection is already running. Cancel it or wait for it to finish.");
        Job job = new Job(snapshot, options, total, progress, completed); current = job;
        try { worker.execute(job); }
        catch (RejectedExecutionException busy) { current = null; throw new IllegalStateException("Collection runner is unavailable. Try again."); }
        return job;
    }

    private final class Job implements Runnable, Execution {
        private final CollectionSnapshot snapshot;
        private final RunOptions options;
        private final int total;
        private final Consumer<Progress> progress;
        private final Consumer<RunEntry> completed;
        private final CompletableFuture<RunReport> result = new CompletableFuture<>();
        private final List<RunEntry> entries = new ArrayList<>();
        private final UUID historyId = UUID.randomUUID();
        private long historyRevision, lastCheckpoint;
        private String historyWarning = "";
        private boolean cancelled, done;
        private RequestExecution active;
        private Thread thread;
        Job(CollectionSnapshot snapshot, RunOptions options, int total, Consumer<Progress> progress, Consumer<RunEntry> completed) {
            this.snapshot = snapshot; this.options = options; this.total = total; this.progress = progress; this.completed = completed;
        }
        @Override public CompletionStage<RunReport> result() { return result.minimalCompletionStage(); }
        @Override public synchronized boolean cancel() {
            if (done || cancelled) return false;
            cancelled = true;
            if (active != null) try { active.cancel(); } catch (RuntimeException ignored) { /* Still interrupt the run worker. */ }
            if (thread != null) thread.interrupt();
            return true;
        }
        private synchronized void checkCancelled() throws InterruptedException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
        private void progress(int iteration, String phase) { emit(progress, new Progress(entries.size(), total, iteration, phase)); }
        private SavedRequest request(int position) { return snapshot.requests().get(position % snapshot.requests().size()); }
        private int iteration(int position) { return position / snapshot.requests().size() + 1; }

        @Override public void run() {
            log.record(dev.apitestingplatform.application.operations.EventLog.Level.INFO, dev.apitestingplatform.application.operations.EventLog.Component.RUNNER, dev.apitestingplatform.application.operations.EventLog.Code.RUN_STARTED, historyId);
            Instant started = Instant.now(); RunReport.Status status = RunReport.Status.COMPLETED;
            RunVariables variables = new RunVariables(); int position = 0; boolean stepStarted = false;
            synchronized (this) { thread = Thread.currentThread(); }
            try {
                journal(started, HistoryRecord.State.RUNNING);
                for (; position < total; position++) {
                    stepStarted = false; checkCancelled();
                    if (position > 0 && options.delayMillis() > 0) { progress(iteration(position), "Waiting between requests"); Thread.sleep(options.delayMillis()); checkCancelled(); }
                    if (position % snapshot.requests().size() == 0) variables.clear();
                    stepStarted = true; progress(iteration(position), "Preparing saved request " + (position + 1));
                    RunEntry entry = execute(position, variables);
                    checkCancelled(); entries.add(entry); emit(completed, entry); progress(iteration(position), "Request finished");
                    if (entries.size() == 1 || System.nanoTime() - lastCheckpoint >= TimeUnit.SECONDS.toNanos(1)) journal(started, HistoryRecord.State.RUNNING);
                }
                if (entries.stream().anyMatch(RunEntry::hasProblems)) status = RunReport.Status.COMPLETED_WITH_FAILURES;
            } catch (InterruptedException cancellation) {
                synchronized (this) { cancelled = true; }
                status = RunReport.Status.CANCELLED;
                if (stepStarted && position < total && entries.size() == position) entries.add(unstarted(position, RunEntry.Outcome.CANCELLED, "This request step was cancelled."));
            } catch (RuntimeException unexpected) {
                status = RunReport.Status.FAILED;
                if (position < total && entries.size() == position) entries.add(unstarted(position, RunEntry.Outcome.NETWORK_ERROR, "The run could not continue safely."));
            } finally {
                variables.clear();
                synchronized (this) { if (active != null) { try { active.cancel(); } catch (RuntimeException ignored) { } active = null; } }
                if (position < total && status == RunReport.Status.COMPLETED) status = RunReport.Status.FAILED;
                String reason = status == RunReport.Status.CANCELLED ? "Not started: collection run was cancelled." : "Not started: collection run stopped.";
                while (entries.size() < total) entries.add(unstarted(entries.size(), RunEntry.Outcome.NOT_RUN, reason));
                synchronized (this) { if (cancelled) status = RunReport.Status.CANCELLED; done = true; thread = null; }
                Thread.interrupted(); // Cancellation belongs to this job, not to its final history write.
                try { journal(started, HistoryRecord.State.valueOf(status.name())); } catch (InterruptedException interrupted) { Thread.interrupted(); historyWarning = "History save is pending. Open History and retry pending saves."; }
                synchronized (CollectionRunner.this) { if (current == this) current = null; }
                log.record(status == RunReport.Status.COMPLETED ? dev.apitestingplatform.application.operations.EventLog.Level.INFO : dev.apitestingplatform.application.operations.EventLog.Level.WARN,
                        dev.apitestingplatform.application.operations.EventLog.Component.RUNNER, dev.apitestingplatform.application.operations.EventLog.Code.RUN_FINISHED, historyId,
                        Map.of(dev.apitestingplatform.application.operations.EventLog.Metric.REQUEST_COUNT, (long) entries.size()));
                result.complete(new RunReport(historyId, snapshot.collectionId(), started, Instant.now(), status, entries, historyWarning));
            }
        }

        private void journal(Instant started, HistoryRecord.State state) throws InterruptedException {
            if (history == null) return;
            var record = HistorySanitizer.create(historyId, HistoryRecord.Kind.COLLECTION, snapshot.workspaceId(), snapshot.collectionId(), snapshot.name(), started.toEpochMilli(),
                    state == HistoryRecord.State.RUNNING ? 0 : Math.max(started.toEpochMilli(), System.currentTimeMillis()), state, total, ++historyRevision, entries);
            try { history.save(record).toCompletableFuture().get(10, TimeUnit.SECONDS); historyWarning = ""; }
            catch (ExecutionException | TimeoutException failure) { historyWarning = "History save failed or is pending. Open History and retry pending saves."; }
            lastCheckpoint = System.nanoTime();
        }
        private RunEntry execute(int position, RunVariables variables) throws InterruptedException {
            SavedRequest saved = request(position); var draft = saved.draft(); var run = variables.snapshot();
            SecretRedactor redactor = new SecretRedactor(java.util.stream.Stream.of(snapshot.workspaceVariables(), snapshot.environmentVariables(), run.values(), draft.variables())
                    .flatMap(Collection::stream).filter(Variable::secret).map(Variable::value).toList(), List.of(), List.of());
            List<RequestValidation.Issue> issues = new ArrayList<>();
            RequestConfiguration configured = null;
            try {
                var compiled = DraftRequestCompiler.compile(draft); issues.addAll(compiled.issues());
                if (issues.isEmpty()) {
                    var resolved = VariableResolver.resolve(compiled.request(), new VariableResolver.Context(snapshot.workspaceVariables(), snapshot.environmentVariables(), run.values()), draft.variables(), draft.authentication());
                    redactor = resolved.redactor(); configured = resolved.request(); issues.addAll(resolved.issues());
                    if (issues.isEmpty()) issues.addAll(RequestValidation.validate(configured));
                }
                issues.addAll(AssertionEngine.validate(draft.assertions())); issues.addAll(ExtractionEngine.validate(draft.extractions()));
            } catch (RuntimeException invalid) { issues.add(new RequestValidation.Issue("request", "Saved request configuration could not be prepared.")); }
            checkCancelled();
            if (!issues.isEmpty()) {
                var extraction = ExtractionEngine.notRun(draft.extractions(), redactor, "Request was not sent: validation failed.");
                variables.publish(run, extraction);
                return new RunEntry(position + 1, iteration(position), saved.item().id(), clip(redactor.text(saved.item().name())), RunEntry.Outcome.INVALID_REQUEST, 0, 0, 0,
                        notRun(draft.assertions(), redactor, "Request was not sent: validation failed."), extraction.results(),
                        clip(redactor.text(String.join("\n", issues.stream().map(issue -> issue.field() + ": " + issue.message()).toList()))));
            }
            progress(iteration(position), "Sending saved request " + (position + 1));
            ExecutionResult response;
            try {
                RequestExecution admitted;
                synchronized (this) { checkCancelled(); admitted = engine.execute(configured); active = admitted; }
                response = admitted.result().toCompletableFuture().get();
                synchronized (this) { active = null; }
            } catch (ExecutionException | RuntimeException failed) {
                response = new ExecutionResult(0, Map.of(), new byte[0], 0, 0, Optional.of(new NetworkError(NetworkError.Type.IO, "Request execution failed.")), Instant.now(), Optional.empty());
            }
            checkCancelled(); progress(iteration(position), "Checking and extracting response data");
            var extracted = ExtractionEngine.evaluate(response, draft.extractions(), redactor);
            redactor = redactor.withSecrets(extracted.values().stream().map(Variable::value).toList());
            var checks = AssertionEngine.evaluate(response, draft.assertions(), redactor);
            checkCancelled();
            var published = draft.extractions().isEmpty() ? extracted.results() : variables.publish(run, extracted);
            RunEntry.Outcome outcome = response.error().isPresent() ? response.error().get().type() == NetworkError.Type.CANCELLED ? RunEntry.Outcome.CANCELLED : RunEntry.Outcome.NETWORK_ERROR
                    : response.statusCode() >= 400 ? RunEntry.Outcome.HTTP_ERROR : RunEntry.Outcome.SUCCESS;
            String details = response.error().map(error -> "Network error: " + error.type().name()).orElse("HTTP response received. Checks and extraction have separate outcomes.");
            return new RunEntry(position + 1, iteration(position), saved.item().id(), clip(redactor.text(saved.item().name())), outcome,
                    response.statusCode(), response.durationMillis(), response.sizeBytes(), checks, published, details);
        }
        private RunEntry unstarted(int position, RunEntry.Outcome outcome, String reason) {
            // Names can contain arbitrary user text; do not expose raw draft values on interrupted paths.
            return new RunEntry(position + 1, iteration(position), request(position).item().id(), "Request " + (position % snapshot.requests().size() + 1), outcome,
                    0, 0, 0, List.of(), List.of(), reason);
        }
    }
    private static List<AssertionResult> notRun(List<AssertionDefinition> rules, SecretRedactor redactor, String reason) {
        return rules.stream().map(rule -> new AssertionResult(rule.id(), clip(redactor.text(rule.name())), rule.enabled() ? AssertionResult.Outcome.NOT_RUN : AssertionResult.Outcome.DISABLED,
                "", "", rule.enabled() ? reason : "Check is disabled.")).toList();
    }
    private static String clip(String value) { return value.length() <= 512 ? value : value.substring(0, 512) + "…"; }
    private static <T> void emit(Consumer<T> consumer, T value) { try { consumer.accept(value); } catch (RuntimeException ignored) { /* A display callback cannot abort a run. */ } }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (current != null) current.cancel(); worker.shutdown();
    }
}
