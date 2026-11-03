package dev.apitestingplatform.domain.run;

import java.util.*;

/** Bounded, sanitized history payload. Epoch milliseconds keep the portable JSON independent of date adapters. */
public record HistoryRecord(UUID id, Kind kind, UUID workspaceId, UUID collectionId, String title,
        long startedMillis, long finishedMillis, State state, int planned, long revision, List<Step> steps) {
    public enum Kind { REQUEST, COLLECTION }
    public enum State { RUNNING, COMPLETED, COMPLETED_WITH_FAILURES, CANCELLED, INTERRUPTED, FAILED }
    public record Step(RunEntry entry, int passed, int failed, int notRun, int disabled,
            int extracted, int extractionFailed, int extractionNotRun, int extractionDisabled, boolean detailsOmitted) {
        public Step { Objects.requireNonNull(entry); }
    }
    public HistoryRecord {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(title); Objects.requireNonNull(state); steps = List.copyOf(steps);
        if (title.length() > 200 || startedMillis < 0 || finishedMillis < 0 || planned < 1 || planned > 1000 || revision < 0 || steps.size() > planned) throw new IllegalArgumentException("Invalid history metadata.");
    }
    public HistoryRecord withState(State value, long finished) { return new HistoryRecord(id, kind, workspaceId, collectionId, title, startedMillis, finished, value, planned, revision, steps); }
    public long requestFailures() { return steps.stream().filter(step -> switch (step.entry().outcome()) { case HTTP_ERROR, NETWORK_ERROR, INVALID_REQUEST -> true; default -> false; }).count(); }
    public long failedChecks() { return steps.stream().mapToLong(Step::failed).sum(); }
    public long extractionProblems() { return steps.stream().mapToLong(step -> step.extractionFailed() + step.extractionNotRun()).sum(); }
}
