package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.application.http.SecretRedactor;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import java.util.*;
import java.util.concurrent.*;

/** One manual Send attempt, including validation failures and explicit cancellation. */
public final class RequestHistory {
    private final HistoryService service;
    private final HistoryService.Context context;
    private final UUID id = UUID.randomUUID();
    private final long started = System.currentTimeMillis();
    private CompletionStage<Void> finished;
    public RequestHistory(HistoryService service, HistoryService.Context context) { this.service = service; this.context = context; }
    public void begin() { service.save(HistorySanitizer.create(id, HistoryRecord.Kind.REQUEST, context.workspaceId(), context.collectionId(), context.title(), started, 0, HistoryRecord.State.RUNNING, 1, 0, List.of())); }
    public synchronized CompletionStage<Void> finish(RunEntry.Outcome outcome, int status, long time, long bytes, List<AssertionResult> checks, List<ExtractionResult> extractions, String reason, SecretRedactor redactor) {
        if (finished != null) return finished;
        var entry = new RunEntry(1, 1, context.requestId(), redactor.text(context.title()), outcome, status, time, bytes, checks, extractions, reason);
        var state = outcome == RunEntry.Outcome.CANCELLED ? HistoryRecord.State.CANCELLED : entry.hasProblems() ? HistoryRecord.State.COMPLETED_WITH_FAILURES : HistoryRecord.State.COMPLETED;
        finished = service.save(HistorySanitizer.create(id, HistoryRecord.Kind.REQUEST, context.workspaceId(), context.collectionId(), redactor.text(context.title()), started,
                Math.max(started, System.currentTimeMillis()), state, 1, 1, List.of(entry)));
        return finished;
    }
}
