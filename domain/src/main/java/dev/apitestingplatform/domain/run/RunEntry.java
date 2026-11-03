package dev.apitestingplatform.domain.run;

import dev.apitestingplatform.domain.http.*;
import java.util.*;

/** Current-run display data only: no request/response bodies, headers or runtime variable values. */
public record RunEntry(int position, int iteration, UUID requestId, String name, Outcome outcome, int statusCode,
        long durationMillis, long sizeBytes, List<AssertionResult> assertions, List<ExtractionResult> extractions, String details) {
    public enum Outcome { SUCCESS, HTTP_ERROR, NETWORK_ERROR, INVALID_REQUEST, CANCELLED, NOT_RUN }
    public RunEntry { assertions = List.copyOf(assertions); extractions = List.copyOf(extractions); }
    public long failedChecks() { return assertions.stream().filter(result -> result.outcome() == AssertionResult.Outcome.FAIL).count(); }
    public long extractionProblems() { return extractions.stream().filter(result -> result.outcome() == ExtractionResult.Outcome.FAILED || result.outcome() == ExtractionResult.Outcome.NOT_RUN).count(); }
    public boolean hasProblems() { return outcome != Outcome.SUCCESS || failedChecks() > 0 || extractionProblems() > 0
            || assertions.stream().anyMatch(result -> result.outcome() == AssertionResult.Outcome.NOT_RUN); }
}
