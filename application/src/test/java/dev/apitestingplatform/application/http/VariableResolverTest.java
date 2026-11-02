package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VariableResolverTest {
    private static Variable value(String name, String value) { return new Variable(name, value, false); }
    private static VariableResolver.Context context(List<Variable> variables) { return new VariableResolver.Context(List.of(), variables); }

    @Test void requestOverridesEnvironmentWhichOverridesWorkspaceAndTemplateRemainsIntact() {
        var template = RequestConfiguration.get("{{baseUrl}}/{{id}}/{{id}}");
        var scopes = new VariableResolver.Context(List.of(value("baseUrl", "https://example.com"), value("id", "workspace")), List.of(value("id", "environment")));
        var local = List.of(value("id", "request"));
        var resolved = VariableResolver.resolve(template, scopes, local);
        assertThat(resolved.issues()).isEmpty(); assertThat(resolved.request().url()).isEqualTo("https://example.com/request/request");
        assertThat(VariableResolver.resolve(template, scopes, List.of()).request().url()).endsWith("/environment/environment");
        assertThat(template.url()).isEqualTo("{{baseUrl}}/{{id}}/{{id}}");
    }
    @Test void nestedSecretsAreTaintedAndQueryPreviewIsMasked() {
        var template = new RequestConfiguration(HttpMethod.GET, "{{base}}/path", List.of(new RequestField("token", "{{token}}")),
                List.of(new RequestField("Authorization", "Bearer {{token}}")), List.of(), RequestBody.none(), java.time.Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        var result = VariableResolver.resolve(template, context(List.of(value("base", "https://{{host}}"), new Variable("host", "private.example.com", true),
                new Variable("token", "private-token-marker", true))), List.of());
        assertThat(result.issues()).isEmpty(); assertThat(result.request().url()).isEqualTo("https://private.example.com/path");
        assertThat(result.request().headers().getFirst().value()).isEqualTo("Bearer private-token-marker");
        assertThat(result.preview()).contains("[secret]", "token=%5Bsecret%5D").doesNotContain("private.example.com", "private-token-marker");
        assertThat(result.toString()).doesNotContain("private-token-marker");
    }
    @Test void missingVariablesAndCyclesHaveFieldErrorsWithoutValues() {
        var missing = VariableResolver.resolve(RequestConfiguration.get("{{unknown}}/path"), VariableResolver.Context.empty(), List.of());
        assertThat(missing.issues().getFirst().field()).isEqualTo("url"); assertThat(missing.issues().getFirst().message()).contains("unknown");
        var cycle = VariableResolver.resolve(RequestConfiguration.get("{{a}}"), context(List.of(value("a", "{{b}}"), value("b", "{{a}}"))), List.of());
        assertThat(cycle.issues().getFirst().message()).contains("cycle");
    }
    @Test void escapedPlaceholdersStayLiteralIncludingInsideVariables() {
        var request = new RequestConfiguration(HttpMethod.POST, "https://example.com", List.of(), List.of(), List.of(), RequestBody.text("\\{{missing}} {{text}}"),
                java.time.Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        var result = VariableResolver.resolve(request, context(List.of(value("text", "\\{{alsoMissing}}"))), List.of());
        assertThat(result.issues()).isEmpty(); assertThat(result.request().body().content()).isEqualTo("{{missing}} {{alsoMissing}}");
    }
    @Test void disabledRowsAreNotResolvedAndUnicodeFormValuesArePreserved() {
        var request = new RequestConfiguration(HttpMethod.POST, "https://example.com", List.of(new RequestField("{{missing}}", "{{missing}}", false)),
                List.of(new RequestField("X-Name", "{{name}}")), List.of(), RequestBody.form(List.of(new RequestField("label", "{{name}}"))),
                java.time.Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        var result = VariableResolver.resolve(request, context(List.of(value("name", "café 東京"))), List.of());
        assertThat(result.issues()).isEmpty(); assertThat(result.request().body().fields().getFirst().value()).isEqualTo("café 東京");
        assertThat(result.request().queryParameters().getFirst().name()).isEqualTo("{{missing}}");
    }
    @Test void invalidResolvedUrlsAndJsonAreRejectedBeforeExecution() {
        var invalid = VariableResolver.resolve(RequestConfiguration.get("{{base}}"), context(List.of(value("base", "file:///private"))), List.of());
        assertThat(RequestValidation.validate(invalid.request())).anyMatch(issue -> issue.field().equals("url"));
        var request = new RequestConfiguration(HttpMethod.POST, "https://example.com", List.of(), List.of(), List.of(), RequestBody.json("{\"value\":{{json}}}"),
                java.time.Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        var resolved = VariableResolver.resolve(request, context(List.of(value("json", "broken"))), List.of());
        assertThat(RequestValidation.validate(resolved.request())).anyMatch(issue -> issue.field().equals("body"));
    }
    @Test void rejectsDuplicatesMalformedPlaceholdersAndExcessiveExpansion() {
        assertThat(VariableResolver.resolve(RequestConfiguration.get("{{a}}"), context(List.of(value("a", "one"), value("a", "two"))), List.of()).issues())
                .anyMatch(issue -> issue.field().equals("variables"));
        for (String malformed : List.of("{{", "{{bad name}}"))
            assertThat(VariableResolver.resolve(RequestConfiguration.get(malformed), VariableResolver.Context.empty(), List.of()).issues()).isNotEmpty();
        List<Variable> deep = new ArrayList<>();
        for (int i = 0; i < 40; i++) deep.add(value("v" + i, i == 39 ? "https://example.com" : "{{v" + (i + 1) + "}}"));
        assertThat(VariableResolver.resolve(RequestConfiguration.get("{{v0}}"), context(deep), List.of()).issues().getFirst().message()).contains("32");
        List<Variable> large = List.of(value("a", "x".repeat(65536)), value("b", "{{a}}".repeat(200)));
        assertThat(VariableResolver.resolve(RequestConfiguration.get("{{b}}"), context(large), List.of()).issues().getFirst().message()).contains("size limit");
    }
    @Test void multipartTextUsesVariablesWithoutExpandingExternalFilePaths() {
        var request = new RequestConfiguration(HttpMethod.POST, "https://example.com", List.of(), List.of(), List.of(),
                RequestBody.multipart(List.of(MultipartPart.text("{{field}}", "{{name}}"))), java.time.Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        var result = VariableResolver.resolve(request, context(List.of(value("field", "title"), value("name", "hello"))), List.of());
        assertThat(result.request().body().parts().getFirst().name()).isEqualTo("title");
        assertThat(result.request().body().parts().getFirst().value()).isEqualTo("hello");
    }
}
