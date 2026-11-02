package dev.apitestingplatform.domain.http;

import java.util.Objects;
import java.util.UUID;

/** Safe display strings are prepared by the application engine before leaving its worker. */
public record AssertionResult(UUID assertionId, String name, Outcome outcome, String expected, String actual, String reason) {
    public enum Outcome { PASS, FAIL, NOT_RUN, DISABLED }
    public AssertionResult {
        Objects.requireNonNull(assertionId); Objects.requireNonNull(name); Objects.requireNonNull(outcome);
        Objects.requireNonNull(expected); Objects.requireNonNull(actual); Objects.requireNonNull(reason);
    }
}
