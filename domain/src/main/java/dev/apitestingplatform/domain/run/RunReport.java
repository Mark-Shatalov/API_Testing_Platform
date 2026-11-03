package dev.apitestingplatform.domain.run;

import java.time.Instant;
import java.util.*;

/** Ephemeral result; persistent history/report export belongs to a later increment. */
public record RunReport(UUID id, UUID collectionId, Instant startedAt, Instant finishedAt, Status status, List<RunEntry> entries, String historyWarning) {
    public RunReport(UUID id, UUID collectionId, Instant startedAt, Instant finishedAt, Status status, List<RunEntry> entries) {
        this(id, collectionId, startedAt, finishedAt, status, entries, "");
    }
    public enum Status { COMPLETED, COMPLETED_WITH_FAILURES, CANCELLED, FAILED }
    public RunReport { entries = List.copyOf(entries); }
    public long requestFailures() { return entries.stream().filter(entry -> switch (entry.outcome()) {
        case HTTP_ERROR, NETWORK_ERROR, INVALID_REQUEST -> true; default -> false;
    }).count(); }
    public long failedChecks() { return entries.stream().mapToLong(RunEntry::failedChecks).sum(); }
    public long extractionProblems() { return entries.stream().mapToLong(RunEntry::extractionProblems).sum(); }
}
