package dev.apitestingplatform.domain.variables;

import java.util.*;

/** Immutable snapshot including the environment selected for this workspace. */
public record WorkspaceVariables(long version, List<Variable> variables, List<Environment> environments, UUID selectedEnvironmentId) {
    public WorkspaceVariables { variables = List.copyOf(variables); environments = List.copyOf(environments); }
    public List<Variable> selectedVariables() {
        return environments.stream().filter(value -> value.id().equals(selectedEnvironmentId)).findFirst()
                .map(Environment::variables).orElse(List.of());
    }
}
