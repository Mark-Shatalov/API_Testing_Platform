package dev.apitestingplatform.domain.run;

import java.util.UUID;

public record HistorySummary(UUID id, HistoryRecord.Kind kind, long startedMillis, long finishedMillis,
        HistoryRecord.State state, int planned, int completed, long requestFailures, long failedChecks) { }
