package dev.apitestingplatform.domain.http;

import java.util.Objects;

/** Safe error classification; deliberately excludes URLs, headers, bodies, and raw exception messages. */
public record NetworkError(Type type, String message) {
    public enum Type {
        INVALID_REQUEST, DNS, CONNECTION_REFUSED, TLS, TIMEOUT, CANCELLED,
        REDIRECT_LIMIT, RESPONSE_TOO_LARGE, RESOURCE_LIMIT, ENGINE_CLOSED, IO
    }

    public NetworkError {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
    }
}
