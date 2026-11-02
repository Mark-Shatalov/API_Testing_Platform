package dev.apitestingplatform.importexport;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RedactedRequestSummaryTest {
    @Test void summaryContainsNoCredentialOrSecretBodyPlaintext() throws Exception {
        var request = new RequestConfiguration(HttpMethod.POST, "https://example.com/{{secretPath}}?access_token=literal-token-marker",
                List.of(new RequestField("ordinary", "query-private-marker")), List.of(new RequestField("Cookie", "cookie-private-marker"), new RequestField("Set-Cookie", "response-cookie-marker")),
                List.of(new RequestField("sid", "explicit-cookie-marker")), RequestBody.text("body-private-marker"), java.time.Duration.ofSeconds(5), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        for (Authentication.Mode mode : List.of(Authentication.Mode.BEARER, Authentication.Mode.BASIC, Authentication.Mode.API_KEY)) {
            var auth = new Authentication(mode, "bearer-private-marker", "user-private-marker", "password-private-marker", "X-Custom-Key", "api-private-marker", Authentication.Placement.HEADER);
            var prepared = VariableResolver.resolve(request, VariableResolver.Context.empty(), List.of(new Variable("secretPath", "path-private-marker", true)), auth);
            String json = RedactedRequestSummary.write(prepared);
            assertThat(json).doesNotContain("literal-token-marker", "query-private-marker", "cookie-private-marker", "response-cookie-marker", "explicit-cookie-marker", "body-private-marker",
                    "bearer-private-marker", "user-private-marker", "password-private-marker", "api-private-marker", "path-private-marker");
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("format").asText()).isEqualTo("redacted-request-summary");
        }
    }
    @Test void invalidPreparationCannotBeExported() {
        var invalid = VariableResolver.resolve(RequestConfiguration.get("{{missing}}"), VariableResolver.Context.empty(), List.of());
        assertThatThrownBy(() -> RedactedRequestSummary.write(invalid)).isInstanceOf(java.io.IOException.class);
    }
}
