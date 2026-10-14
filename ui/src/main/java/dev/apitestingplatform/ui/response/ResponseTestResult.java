package dev.apitestingplatform.ui.response;

import java.util.Objects;

/** Supplied by an assertion runner; viewing results does not execute scripts or assertions. */
public record ResponseTestResult(String name, boolean passed, String details) {
    public ResponseTestResult {
        Objects.requireNonNull(name);
        Objects.requireNonNull(details);
    }
}
