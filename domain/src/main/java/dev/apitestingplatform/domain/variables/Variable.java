package dev.apitestingplatform.domain.variables;

import java.util.*;

/** Values may be incomplete in request drafts. Validate before saving an environment or executing. */
public record Variable(String name, String value, boolean secret) {
    public Variable {
        Objects.requireNonNull(name); Objects.requireNonNull(value);
        if (name.length() > 100 || value.length() > 65536) throw new IllegalArgumentException("Variable exceeds size limits.");
    }
    public static void validate(List<Variable> variables) {
        if (variables.size() > 256) throw new IllegalArgumentException("At most 256 variables are allowed per scope.");
        Set<String> names = new HashSet<>();
        long total = 0;
        for (Variable variable : variables) {
            total += variable.name().length() + variable.value().length();
            if (total > 1024 * 1024) throw new IllegalArgumentException("Variable values exceed the 1,048,576-character limit for this scope.");
            if (!variable.name().matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}"))
                throw new IllegalArgumentException("Variable names must start with a letter or underscore and use letters, digits, dots, underscores or hyphens.");
            if (!names.add(variable.name())) throw new IllegalArgumentException("Variable names must be unique within each scope.");
        }
    }
    @Override public String toString() { return name + "=" + (secret ? "[secret]" : value); }
}
