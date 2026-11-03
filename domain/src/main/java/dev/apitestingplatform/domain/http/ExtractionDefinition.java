package dev.apitestingplatform.domain.http;

import java.util.Objects;
import java.util.UUID;

/** Editable extraction configuration, never the extracted value. */
public record ExtractionDefinition(UUID id, String variable, String path, boolean enabled) {
    public ExtractionDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(variable); Objects.requireNonNull(path);
        if (variable.length() > 100 || path.length() > 1024) throw new IllegalArgumentException("Extraction exceeds editor limits.");
    }
    @Override public String toString() { return "ExtractionDefinition[" + id + "]"; }
}
