package dev.apitestingplatform.domain.variables;

import java.util.List;

/** Versioned serialization value for encrypted workspace variables. */
public record VariableSet(List<Variable> variables) {
    public VariableSet { variables = List.copyOf(variables); Variable.validate(variables); }
}
