package dev.apitestingplatform.domain.http;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/** Immutable user input. URL/header validation occurs asynchronously in the engine. */
public record RequestConfiguration(
        HttpMethod method, String url, List<RequestField> queryParameters,
        List<RequestField> headers, List<RequestField> cookies, RequestBody body,
        Duration timeout, RedirectPolicy redirects, HttpProtocol protocol,
        Set<String> sensitiveHeaderNames, Set<String> sensitiveQueryNames) {
    public RequestConfiguration(HttpMethod method, String url, List<RequestField> queryParameters,
            List<RequestField> headers, List<RequestField> cookies, RequestBody body,
            Duration timeout, RedirectPolicy redirects, HttpProtocol protocol) {
        this(method, url, queryParameters, headers, cookies, body, timeout, redirects, protocol, Set.of(), Set.of());
    }
    public RequestConfiguration {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        queryParameters = List.copyOf(queryParameters);
        headers = List.copyOf(headers);
        cookies = List.copyOf(cookies);
        sensitiveHeaderNames = sensitiveHeaderNames.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        sensitiveQueryNames = Set.copyOf(sensitiveQueryNames);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(redirects, "redirects");
        Objects.requireNonNull(protocol, "protocol");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Timeout must be positive and at most one day");
        }
    }

    public static RequestConfiguration get(String url) {
        return new RequestConfiguration(HttpMethod.GET, url, List.of(), List.of(), List.of(),
                RequestBody.none(), Duration.ofSeconds(30), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
    }
}
