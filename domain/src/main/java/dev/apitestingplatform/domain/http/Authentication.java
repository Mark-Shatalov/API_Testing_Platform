package dev.apitestingplatform.domain.http;

import java.util.Objects;

/** Editable authentication templates. Values are validated only after variable resolution. */
public record Authentication(Mode mode, String bearerToken, String username, String password,
        String keyName, String keyValue, Placement placement) {
    public enum Mode {
        NONE("No auth"), BEARER("Bearer Token"), BASIC("Basic Auth"), API_KEY("API Key");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public enum Placement { HEADER, QUERY }
    public Authentication {
        Objects.requireNonNull(mode); Objects.requireNonNull(placement);
        for (String value : new String[]{bearerToken, username, password, keyName, keyValue}) {
            Objects.requireNonNull(value);
            if (value.length() > 65536) throw new IllegalArgumentException("Authentication field exceeds the size limit.");
        }
    }
    public static Authentication none() { return new Authentication(Mode.NONE, "", "", "", "X-API-Key", "", Placement.HEADER); }
    @Override public String toString() { return "Authentication[" + mode + ", credentials hidden]"; }
}
