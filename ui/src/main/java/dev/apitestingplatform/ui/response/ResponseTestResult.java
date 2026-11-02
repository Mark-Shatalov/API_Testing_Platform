package dev.apitestingplatform.ui.response;

import java.util.Objects;
import dev.apitestingplatform.domain.http.AssertionResult;

/** Supplied by an assertion runner; viewing results does not execute scripts or assertions. */
public record ResponseTestResult(String name, AssertionResult.Outcome outcome, String expected, String actual, String details) {
    public ResponseTestResult(String name, boolean passed, String details) { this(name, passed ? AssertionResult.Outcome.PASS : AssertionResult.Outcome.FAIL, "", "", details); }
    public boolean passed() { return outcome == AssertionResult.Outcome.PASS; }
    public static ResponseTestResult from(AssertionResult result) { return new ResponseTestResult(result.name(), result.outcome(), result.expected(), result.actual(), result.reason()); }
    public ResponseTestResult {
        Objects.requireNonNull(name);
        Objects.requireNonNull(details);
        Objects.requireNonNull(outcome); Objects.requireNonNull(expected); Objects.requireNonNull(actual);
    }
}
