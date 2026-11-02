package dev.apitestingplatform.domain.http;

import java.util.Objects;
import java.util.UUID;

/** Editable declarative check. Incomplete drafts are saved; enabled checks are validated before sending. */
public record AssertionDefinition(UUID id, String name, Type type, String target, String expected, boolean enabled) {
    public enum Type {
        STATUS_EQUALS("Status equals"), HEADER_EXISTS("Header exists"), HEADER_CONTAINS("Header contains"),
        JSON_PATH_EXISTS("JSONPath exists"), JSON_PATH_EQUALS("JSONPath value equals"),
        MAX_RESPONSE_TIME("Maximum time (ms)"), MAX_BODY_SIZE("Maximum size (bytes)");
        private final String label;
        Type(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public AssertionDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(type); Objects.requireNonNull(target); Objects.requireNonNull(expected);
        if (name.length() > 200 || target.length() > 1024 || expected.length() > 65536) throw new IllegalArgumentException("Assertion exceeds editor limits.");
    }
    @Override public String toString() { return "Assertion[" + id + ", " + type + "]"; }
}
