package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import java.util.*;

/** Persistence/export boundary: no response values, raw network messages or request payloads. */
public final class HistorySanitizer {
    private HistorySanitizer() { }
    public static HistoryRecord create(UUID id, HistoryRecord.Kind kind, UUID workspace, UUID collection, String title,
            long started, long finished, HistoryRecord.State state, int planned, long revision, List<RunEntry> entries) {
        var steps = entries.stream().map(entry -> new HistoryRecord.Step(entry,
                count(entry.assertions(), AssertionResult.Outcome.PASS), count(entry.assertions(), AssertionResult.Outcome.FAIL), count(entry.assertions(), AssertionResult.Outcome.NOT_RUN), count(entry.assertions(), AssertionResult.Outcome.DISABLED),
                extracted(entry, ExtractionResult.Outcome.EXTRACTED), extracted(entry, ExtractionResult.Outcome.FAILED), extracted(entry, ExtractionResult.Outcome.NOT_RUN), extracted(entry, ExtractionResult.Outcome.DISABLED), false)).toList();
        return clean(new HistoryRecord(id, kind, workspace, collection, clip(title, 200), started, finished, state, planned, revision, steps));
    }
    public static HistoryRecord clean(HistoryRecord source) {
        int budget = 2 * 1024 * 1024; List<HistoryRecord.Step> steps = new ArrayList<>();
        for (var step : source.steps()) {
            var entry = step.entry(); List<AssertionResult> checks = new ArrayList<>(); List<ExtractionResult> extractions = new ArrayList<>();
            for (var check : entry.assertions()) {
                int cost = 1200;
                if (budget < cost) break; budget -= cost;
                checks.add(new AssertionResult(check.assertionId(), clip(check.name(), 80), check.outcome(), "[omitted]", "[omitted]", checkReason(check)));
            }
            for (var extraction : entry.extractions()) {
                int cost = 1000;
                if (budget < cost) break; budget -= cost;
                String reason = switch (extraction.outcome()) {
                    case EXTRACTED -> "One value extracted; value omitted from history.";
                    case DISABLED -> "Extraction is disabled.";
                    case FAILED -> extraction.details().matches("JSONPath matched [0-9]+ values; exactly one is required\\.") ? extraction.details() : "No single value could be extracted.";
                    case NOT_RUN -> "Extraction could not run against a complete valid response.";
                };
                extractions.add(new ExtractionResult(extraction.id(), clip(extraction.variable(), 100), extraction.outcome(), reason));
            }
            String detail = switch (entry.outcome()) {
                case SUCCESS -> "HTTP response received. Response data is omitted from history.";
                case HTTP_ERROR -> "HTTP error response; inspect the recorded HTTP status and check outcomes.";
                case INVALID_REQUEST -> "Request was not sent because validation failed. Inspect the saved request configuration.";
                case CANCELLED -> "Request step was cancelled.";
                case NOT_RUN -> "Request step was not started.";
                case NETWORK_ERROR -> networkReason(entry.details());
            };
            var sanitized = new RunEntry(entry.position(), entry.iteration(), entry.requestId(), clip(entry.name(), 200), entry.outcome(), entry.statusCode(),
                    entry.durationMillis(), entry.sizeBytes(), checks, extractions, detail);
            steps.add(new HistoryRecord.Step(sanitized, step.passed(), step.failed(), step.notRun(), step.disabled(), step.extracted(), step.extractionFailed(),
                    step.extractionNotRun(), step.extractionDisabled(), step.detailsOmitted() || checks.size() != entry.assertions().size() || extractions.size() != entry.extractions().size()));
        }
        return new HistoryRecord(source.id(), source.kind(), source.workspaceId(), source.collectionId(), clip(source.title(), 200), source.startedMillis(), source.finishedMillis(), source.state(), source.planned(), source.revision(), steps);
    }
    private static String networkReason(String source) {
        for (var type : NetworkError.Type.values()) if (source.equals("Network error: " + type.name())) return source;
        return "Network request failed; raw error details are omitted.";
    }
    private static String checkReason(AssertionResult check) {
        Set<String> safe = Set.of("JSONPath does not exist.", "Response has no JSON body.", "Response is not valid JSON or exceeds parsing limits.",
                "Response body is truncated; JSON checks require the complete body.", "Exact HTTP status comparison.", "Inclusive upper bound.", "Header name lookup is case-insensitive.",
                "At least one header value must contain the text (case-sensitive).", "Download stopped early; the full response measurement is unknown.");
        return safe.contains(check.reason()) ? check.reason() : "Check " + check.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "; compared values omitted.";
    }
    private static int count(List<AssertionResult> checks, AssertionResult.Outcome outcome) { return (int) checks.stream().filter(check -> check.outcome() == outcome).count(); }
    private static int extracted(RunEntry entry, ExtractionResult.Outcome outcome) { return (int) entry.extractions().stream().filter(result -> result.outcome() == outcome).count(); }
    private static String clip(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
}
