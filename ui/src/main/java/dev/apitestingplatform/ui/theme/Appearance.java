package dev.apitestingplatform.ui.theme;

import java.util.Locale;
import java.util.Objects;

/** Portable appearance values, independent of request data. */
public record Appearance(Theme theme, String accent) {
    public enum Theme {
        GRAPHITE("Dark graphite"), LIGHT("Light");
        private final String label;
        Theme(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public Appearance {
        Objects.requireNonNull(theme);
        if (accent == null || !accent.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Accent must be a six-digit RGB color");
        accent = accent.toLowerCase(Locale.ROOT);
    }
    public static Appearance defaults() { return new Appearance(Theme.GRAPHITE, "#818cf8"); }
}
