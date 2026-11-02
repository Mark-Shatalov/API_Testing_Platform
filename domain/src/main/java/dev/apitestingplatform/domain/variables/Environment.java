package dev.apitestingplatform.domain.variables;

import java.time.Instant;
import java.util.*;

public record Environment(UUID id, UUID workspaceId, String name, long version, Instant modifiedAt, List<Variable> variables) {
    public Environment {
        Objects.requireNonNull(id); Objects.requireNonNull(workspaceId); Objects.requireNonNull(modifiedAt);
        name = Objects.requireNonNull(name).trim(); variables = List.copyOf(variables); Variable.validate(variables);
        if (name.isEmpty() || name.length() > 200 || version < 0) throw new IllegalArgumentException("Use an environment name of 1 to 200 characters.");
    }
    @Override public String toString() { return name; }
}
