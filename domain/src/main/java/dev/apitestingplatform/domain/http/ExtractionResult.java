package dev.apitestingplatform.domain.http;

import java.util.UUID;

/** Display-only result. Values are deliberately excluded. */
public record ExtractionResult(UUID id, String variable, Outcome outcome, String details) {
    public enum Outcome { EXTRACTED, FAILED, NOT_RUN, DISABLED }
}
