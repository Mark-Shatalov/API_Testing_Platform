package dev.apitestingplatform.domain.http;

import java.util.Objects;

/** Ordered name/value pair; lists preserve duplicate parameters and headers. */
public record RequestField(String name, String value) {
    public RequestField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
