package dev.apitestingplatform.domain.http;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Status 0 and absent protocol indicate no completed HTTP response. Body contains raw received bytes. */
public record ExecutionResult(int statusCode, Map<String, List<String>> responseHeaders,
        byte[] responseBody, long durationMillis, long sizeBytes, Optional<NetworkError> error,
        Instant startedAt, Optional<HttpProtocol> protocol) {
    public ExecutionResult {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        responseHeaders.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        responseHeaders = Map.copyOf(copy);
        responseBody = responseBody.clone();
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(protocol, "protocol");
        if (statusCode < 0 || statusCode > 599 || durationMillis < 0 || sizeBytes != responseBody.length) {
            throw new IllegalArgumentException("Invalid execution result metadata");
        }
    }

    @Override public byte[] responseBody() { return responseBody.clone(); }
    /** Read-only view avoids copying the body for decoding and file saving. */
    public java.nio.ByteBuffer bodyBuffer() { return java.nio.ByteBuffer.wrap(responseBody).asReadOnlyBuffer(); }
    public boolean bodyTruncated() {
        return error.map(value -> value.type() == NetworkError.Type.RESPONSE_TOO_LARGE).orElse(false);
    }
    public boolean succeeded() { return error.isEmpty(); }
}
