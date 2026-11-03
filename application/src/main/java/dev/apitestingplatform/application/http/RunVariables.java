package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.ExtractionResult;
import dev.apitestingplatform.domain.variables.Variable;
import java.util.*;

/** One temporary run/session, owned by its caller. Never serialize this object into a saved draft. */
public final class RunVariables {
    public record Snapshot(long revision, List<Variable> values) {
        public Snapshot { values = List.copyOf(values); }
        @Override public String toString() { return "Run variable snapshot: " + values.size() + " masked values"; }
    }
    private final Map<String, Variable> values = new LinkedHashMap<>();
    private long revision;
    public synchronized Snapshot snapshot() { return new Snapshot(revision, List.copyOf(values.values())); }
    public synchronized void clear() { values.clear(); revision++; }

    /** Atomic merge. A failed target is removed so an old token cannot silently survive its failed refresh. */
    public synchronized List<ExtractionResult> publish(Snapshot submitted, ExtractionEngine.Evaluation evaluation) {
        if (submitted.revision() != revision) return rejected(evaluation, "Session changed while this request was active; no extracted values were applied. Send again.");
        Map<String, Variable> next = new LinkedHashMap<>(values);
        evaluation.targets().forEach(next::remove);
        evaluation.values().forEach(value -> next.put(value.name(), value));
        try { Variable.validate(List.copyOf(next.values())); }
        catch (IllegalArgumentException limit) {
            evaluation.targets().forEach(values::remove); revision++;
            return rejected(evaluation, "Temporary variable capacity exceeded (256 values / 1,048,576 characters). Clear the session and try again.");
        }
        values.clear(); values.putAll(next); revision++;
        return evaluation.results();
    }
    private static List<ExtractionResult> rejected(ExtractionEngine.Evaluation evaluation, String reason) {
        return evaluation.results().stream().map(result -> result.outcome() == ExtractionResult.Outcome.EXTRACTED
                ? new ExtractionResult(result.id(), result.variable(), ExtractionResult.Outcome.NOT_RUN, reason) : result).toList();
    }
    @Override public String toString() { return "RunVariables[masked]"; }
}
