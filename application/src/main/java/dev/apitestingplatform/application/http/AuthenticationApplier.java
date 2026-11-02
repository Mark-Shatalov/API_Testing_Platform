package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Builds transport credentials without mutating saved templates. No challenge negotiation or scripts. */
public final class AuthenticationApplier {
    public record Applied(RequestConfiguration request, List<RequestValidation.Issue> issues, Set<String> secrets) {
        public Applied { issues = List.copyOf(issues); secrets = Set.copyOf(secrets); }
        @Override public String toString() { return "Authentication result [credentials hidden]"; }
    }
    private AuthenticationApplier() { }
    public static Applied apply(RequestConfiguration input, Authentication auth) {
        if (auth.mode() == Authentication.Mode.NONE) return new Applied(input, List.of(), Set.of());
        List<RequestField> headers = new ArrayList<>(input.headers()), query = new ArrayList<>(input.queryParameters());
        Set<String> sensitiveHeaders = new HashSet<>(input.sensitiveHeaderNames()), sensitiveQuery = new HashSet<>(input.sensitiveQueryNames());
        Set<String> secrets = new HashSet<>();
        String name, value;
        try {
            switch (auth.mode()) {
                case BEARER -> {
                    if (!auth.bearerToken().matches("[A-Za-z0-9._~+/=-]+") || !auth.bearerToken().matches("[A-Za-z0-9._~+/-]+=*"))
                        throw new IllegalArgumentException("Enter a Bearer token without the Bearer prefix or whitespace.");
                    name = "Authorization"; value = "Bearer " + auth.bearerToken(); secrets.add(auth.bearerToken());
                }
                case BASIC -> {
                    if (auth.username().isEmpty() || auth.username().contains(":") || controls(auth.username()) || controls(auth.password()))
                        throw new IllegalArgumentException("Basic Auth needs a username without colons or control characters. Passwords cannot contain control characters.");
                    byte[] bytes = (auth.username() + ":" + auth.password()).getBytes(StandardCharsets.UTF_8);
                    try { value = "Basic " + Base64.getEncoder().encodeToString(bytes); }
                    finally { Arrays.fill(bytes, (byte) 0); }
                    name = "Authorization"; secrets.add(auth.username()); secrets.add(auth.password());
                }
                case API_KEY -> {
                    name = auth.keyName(); value = auth.keyValue();
                    if (name.isBlank() || value.isEmpty() || controls(name)) throw new IllegalArgumentException("API Key needs a name and a non-empty value.");
                    secrets.add(value);
                    if (auth.placement() == Authentication.Placement.QUERY) {
                        if (query.stream().anyMatch(row -> row.enabled() && row.name().equals(name)) || rawQueryHas(input.url(), name))
                            throw new IllegalArgumentException("Remove the matching manual query parameter before using API Key authentication.");
                        query.add(new RequestField(name, value)); sensitiveQuery.add(name);
                        return success(input, headers, query, sensitiveHeaders, sensitiveQuery, secrets);
                    }
                }
                default -> throw new IllegalArgumentException("Choose a supported authentication mode.");
            }
            if (headers.stream().anyMatch(row -> row.enabled() && row.name().equalsIgnoreCase(name)))
                throw new IllegalArgumentException("Disable the matching manual header before using authentication in the Auth tab.");
            RequestConfiguration probe = new RequestConfiguration(input.method(), "https://example.com", List.of(), List.of(new RequestField(name, value)),
                    List.of(), RequestBody.none(), input.timeout(), input.redirects(), input.protocol());
            if (RequestValidation.validate(probe).stream().anyMatch(issue -> issue.field().startsWith("headers")))
                throw new IllegalArgumentException("Authentication needs a valid, non-managed header name and a value without unsupported header characters.");
            headers.add(new RequestField(name, value)); sensitiveHeaders.add(name); secrets.add(value);
            return success(input, headers, query, sensitiveHeaders, sensitiveQuery, secrets);
        } catch (IllegalArgumentException invalid) { return new Applied(input, List.of(new RequestValidation.Issue("auth", invalid.getMessage())), Set.of()); }
    }
    private static Applied success(RequestConfiguration input, List<RequestField> headers, List<RequestField> query,
            Set<String> sensitiveHeaders, Set<String> sensitiveQuery, Set<String> secrets) {
        return new Applied(new RequestConfiguration(input.method(), input.url(), query, headers, input.cookies(), input.body(), input.timeout(),
                input.redirects(), input.protocol(), sensitiveHeaders, sensitiveQuery), List.of(), secrets);
    }
    private static boolean controls(String value) { return value.chars().anyMatch(character -> character < 32 || character == 127); }
    private static boolean rawQueryHas(String url, String name) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) for (String part : query.split("&"))
                if (URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8).equals(name)) return true;
        } catch (IllegalArgumentException invalidUrl) { /* URL validation will reject malformed URLs before execution. */ }
        return false;
    }
}
