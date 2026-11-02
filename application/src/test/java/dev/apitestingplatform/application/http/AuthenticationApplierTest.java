package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuthenticationApplierTest {
    private static Authentication auth(Authentication.Mode mode, String token, String user, String password, String key, String value, Authentication.Placement placement) {
        return new Authentication(mode, token, user, password, key, value, placement);
    }
    private static VariableResolver.Resolved prepare(Authentication auth) { return VariableResolver.resolve(RequestConfiguration.get("https://example.com"), VariableResolver.Context.empty(), List.of(), auth); }
    @Test void bearerResolvesVariablesAndRetainsTemplates() {
        var auth = auth(Authentication.Mode.BEARER, "{{token}}", "", "", "", "", Authentication.Placement.HEADER);
        var result = VariableResolver.resolve(RequestConfiguration.get("https://example.com"), new VariableResolver.Context(List.of(), List.of(new Variable("token", "private-token-marker", true))), List.of(), auth);
        assertThat(result.issues()).isEmpty(); assertThat(result.request().headers().getFirst().value()).isEqualTo("Bearer private-token-marker");
        assertThat(result.request().sensitiveHeaderNames()).contains("authorization"); assertThat(auth.bearerToken()).isEqualTo("{{token}}");
        assertThat(auth.toString()).doesNotContain("{{token}}");
    }
    @Test void basicUsesUtf8AndClearsTemporaryBytesWithoutChangingPassword() {
        var result = prepare(auth(Authentication.Mode.BASIC, "", "café", "пароль", "", "", Authentication.Placement.HEADER));
        assertThat(result.issues()).isEmpty();
        assertThat(result.request().headers().getFirst().value()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("café:пароль".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void apiKeySupportsHeaderOrQueryAndAlwaysMasksLiteralKeysInPreview() {
        var header = prepare(auth(Authentication.Mode.API_KEY, "", "", "", "X-Access", "private-api-marker", Authentication.Placement.HEADER));
        assertThat(header.issues()).isEmpty(); assertThat(header.request().sensitiveHeaderNames()).contains("x-access");
        var query = prepare(auth(Authentication.Mode.API_KEY, "", "", "", "custom_key", "private api marker", Authentication.Placement.QUERY));
        assertThat(query.issues()).isEmpty(); assertThat(query.request().queryParameters().getFirst().value()).isEqualTo("private api marker");
        assertThat(query.request().sensitiveQueryNames()).contains("custom_key");
        assertThat(query.preview()).contains("custom_key=%5Bsecret%5D").doesNotContain("private", "marker");
    }
    @Test void rejectsInvalidCredentialsWithoutEchoingThem() {
        for (Authentication auth : List.of(auth(Authentication.Mode.BEARER, "private\r\nmarker", "", "", "", "", Authentication.Placement.HEADER),
                auth(Authentication.Mode.BASIC, "", "bad:name", "private-password-marker", "", "", Authentication.Placement.HEADER),
                auth(Authentication.Mode.API_KEY, "", "", "", "Host", "private-key-marker", Authentication.Placement.HEADER),
                auth(Authentication.Mode.API_KEY, "", "", "", "X-Key", "private\nmarker", Authentication.Placement.HEADER))) {
            var result = prepare(auth); assertThat(result.issues()).isNotEmpty();
            assertThat(result.issues()).allMatch(issue -> issue.field().equals("auth"));
            assertThat(result.issues().toString()).doesNotContain("private-password-marker", "private-key-marker", "private\nmarker");
        }
    }
    @Test void conflictingManualFieldsAreRejectedButDisabledRowsAreAllowed() {
        var auth = auth(Authentication.Mode.BEARER, "token-marker", "", "", "", "", Authentication.Placement.HEADER);
        var base = RequestConfiguration.get("https://example.com");
        for (boolean enabled : List.of(true, false)) {
            var request = new RequestConfiguration(base.method(), base.url(), List.of(), List.of(new RequestField("authorization", "manual", enabled)), List.of(), base.body(), base.timeout(), base.redirects(), base.protocol());
            assertThat(AuthenticationApplier.apply(request, auth).issues().isEmpty()).isEqualTo(!enabled);
        }
        var key = auth(Authentication.Mode.API_KEY, "", "", "", "custom_key", "private-marker", Authentication.Placement.QUERY);
        assertThat(AuthenticationApplier.apply(RequestConfiguration.get("https://example.com?custom%5Fkey=manual"), key).issues()).isNotEmpty();
    }
    @Test void unknownAuthVariablesFailInAuthAndInactiveFieldsAreIgnored() {
        var missing = prepare(auth(Authentication.Mode.BEARER, "{{missing}}", "", "", "", "", Authentication.Placement.HEADER));
        assertThat(missing.issues()).anyMatch(issue -> issue.field().equals("auth"));
        var inactive = prepare(auth(Authentication.Mode.NONE, "{{missing}}", "", "", "", "", Authentication.Placement.HEADER));
        assertThat(inactive.issues()).isEmpty(); assertThat(inactive.request().headers()).isEmpty();
    }
}
