package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static dev.apitestingplatform.domain.http.AssertionDefinition.Type.*;
import static dev.apitestingplatform.domain.http.AssertionResult.Outcome.*;
import static org.assertj.core.api.Assertions.*;

class AssertionEngineTest {
    private static AssertionDefinition rule(AssertionDefinition.Type type, String target, String expected) {
        return new AssertionDefinition(UUID.randomUUID(), type.toString(), type, target, expected, true);
    }
    private static ExecutionResult response(String body) { return response(200, body, 42, null); }
    private static ExecutionResult response(int status, String body, long time, NetworkError.Type error) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ExecutionResult(status, Map.of("Content-Type", List.of("application/json"), "X-Tag", List.of("one", "two")), bytes, time, bytes.length,
                error == null ? Optional.empty() : Optional.of(new NetworkError(error, "private-error-marker")), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
    }
    private static List<AssertionResult> evaluate(ExecutionResult response, AssertionDefinition... rules) {
        return AssertionEngine.evaluate(response, List.of(rules), SecretRedactor.empty());
    }
    @Test void evaluatesAllSevenChecksInSavedOrderWithExpectedAndActualValues() {
        var response = response("{\"id\":2,\"active\":true}");
        List<AssertionDefinition> rules = List.of(rule(STATUS_EQUALS, "", "200"), rule(HEADER_EXISTS, "content-type", ""), rule(HEADER_CONTAINS, "X-Tag", "two"),
                rule(JSON_PATH_EXISTS, "$.active", ""), rule(JSON_PATH_EQUALS, "$.id", "2"), rule(MAX_RESPONSE_TIME, "", "42"), rule(MAX_BODY_SIZE, "", Long.toString(response.sizeBytes())));
        assertThat(AssertionEngine.validate(rules)).isEmpty();
        var results = AssertionEngine.evaluate(response, rules, SecretRedactor.empty());
        assertThat(results).extracting(AssertionResult::assertionId).containsExactlyElementsOf(rules.stream().map(AssertionDefinition::id).toList());
        assertThat(results).allMatch(result -> result.outcome() == PASS && !result.expected().isEmpty() && !result.actual().isEmpty());
    }
    @Test void jsonEqualityUsesTypesStructureAndNumericValueRatherThanStringFormatting() {
        var result = evaluate(response("{\"id\":2,\"value\":{\"a\":1,\"b\":[2]}}"),
                rule(JSON_PATH_EQUALS, "$.id", "2.0"), rule(JSON_PATH_EQUALS, "$.id", "\"2\""),
                rule(JSON_PATH_EQUALS, "$.value", "{\"b\":[2.00],\"a\":1.0}"));
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(PASS, FAIL, PASS);
        assertThat(evaluate(response("{\"value\":1.234567890123456789}"), rule(JSON_PATH_EQUALS, "$.value", "1.234567890123456788")).getFirst().outcome()).isEqualTo(FAIL);
    }
    @Test void nullIsPresentButMissingPathsAndHeadersFail() {
        var result = evaluate(response("{\"nullable\":null}"), rule(JSON_PATH_EXISTS, "$.nullable", ""), rule(JSON_PATH_EQUALS, "$.nullable", "null"),
                rule(JSON_PATH_EXISTS, "$.missing", ""), rule(HEADER_EXISTS, "X-Missing", ""));
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(PASS, PASS, FAIL, FAIL);
        var emptyHeader = new ExecutionResult(200, Map.of("X-Empty", List.of()), new byte[0], 1, 0, Optional.empty(), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
        assertThat(evaluate(emptyHeader, rule(HEADER_EXISTS, "x-empty", "")).getFirst().outcome()).isEqualTo(PASS);
    }
    @Test void bracketKeysAndArrayIndicesWorkAndHttpErrorResponsesCanPassChecks() {
        var result = evaluate(response(500, "{\"a.b\":[{\"id\":3}]}", 42, null), rule(STATUS_EQUALS, "", "500"), rule(JSON_PATH_EQUALS, "$['a.b'][0].id", "3"));
        assertThat(result).allMatch(row -> row.outcome() == PASS);
    }
    @Test void malformedEmptyDuplicateOrDeepJsonGivesNotRunWhileStatusStillWorks() {
        for (String body : List.of("", "{broken}", "{} {}", "{\"id\":1,\"id\":2}", "[".repeat(110) + "0" + "]".repeat(110))) {
            var result = evaluate(response(body), rule(JSON_PATH_EXISTS, "$.id", ""), rule(STATUS_EQUALS, "", "200"));
            assertThat(result).extracting(AssertionResult::outcome).containsExactly(NOT_RUN, PASS);
            assertThat(result.getFirst().reason()).isNotEmpty().doesNotContain(body.isEmpty() ? "private-error-marker" : "{broken}");
        }
        assertThat(evaluate(response("x".repeat(2 * 1024 * 1024 + 1)), rule(JSON_PATH_EXISTS, "$.id", "")).getFirst().reason()).contains("2 MiB");
    }
    @ParameterizedTest @EnumSource(value = NetworkError.Type.class, names = {"DNS", "TLS", "TIMEOUT", "CANCELLED", "CONNECTION_REFUSED"})
    void networkFailuresNeverBecomePassingAssertions(NetworkError.Type error) {
        var disabled = new AssertionDefinition(UUID.randomUUID(), "Disabled", STATUS_EQUALS, "", "invalid", false);
        var result = evaluate(response(0, "", 42, error), rule(STATUS_EQUALS, "", "200"), rule(JSON_PATH_EXISTS, "$.id", ""), rule(MAX_RESPONSE_TIME, "", "1000"), disabled);
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(NOT_RUN, NOT_RUN, NOT_RUN, DISABLED);
        assertThat(result.getFirst().reason()).contains(error.name()).doesNotContain("private-error-marker");
    }
    @Test void limitsAreInclusiveAndSizeCountsBytesRatherThanCharacters() {
        var result = evaluate(response("é"), rule(MAX_RESPONSE_TIME, "", "41"), rule(MAX_RESPONSE_TIME, "", "42"), rule(MAX_BODY_SIZE, "", "1"), rule(MAX_BODY_SIZE, "", "2"));
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(FAIL, PASS, FAIL, PASS);
    }
    @Test void truncatedResponsesOnlyAllowConclusiveComparisons() {
        var result = evaluate(response(200, "123456", 42, NetworkError.Type.RESPONSE_TOO_LARGE), rule(STATUS_EQUALS, "", "200"), rule(JSON_PATH_EXISTS, "$.id", ""),
                rule(MAX_BODY_SIZE, "", "5"), rule(MAX_BODY_SIZE, "", "6"), rule(MAX_RESPONSE_TIME, "", "41"), rule(MAX_RESPONSE_TIME, "", "42"));
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(PASS, NOT_RUN, FAIL, NOT_RUN, FAIL, NOT_RUN);
    }
    @Test void invalidConfigurationsAreReportedBeforeExecutionAndDisabledDraftsAreAllowed() {
        var invalid = List.of(rule(STATUS_EQUALS, "", "99"), rule(MAX_RESPONSE_TIME, "", "-1"), rule(MAX_BODY_SIZE, "", "9223372036854775808"),
                rule(HEADER_EXISTS, "Bad Header", ""), rule(JSON_PATH_EQUALS, "$.id", "not JSON"));
        assertThat(AssertionEngine.validate(invalid)).hasSize(invalid.size());
        assertThat(AssertionEngine.evaluate(response("{}"), invalid, SecretRedactor.empty())).allMatch(row -> row.outcome() == NOT_RUN);
        assertThat(AssertionEngine.validate(List.of(new AssertionDefinition(UUID.randomUUID(), "", JSON_PATH_EQUALS, "bad", "bad", false)))).isEmpty();
    }
    @Test void filtersFunctionsWildcardsAndScriptsAreRejected() {
        for (String path : List.of("$..id", "$[*]", "$[?(@.id==1)]", "$.id.length()", "$[0:3]", "$['a','b']", "$[(java.lang.Runtime.getRuntime())]"))
            assertThat(AssertionEngine.validate(List.of(rule(JSON_PATH_EXISTS, path, "")))).as(path).isNotEmpty();
    }
    @Test void credentialHeadersAndKnownSecretValuesAreMaskedAfterComparison() {
        byte[] bytes = "{\"token\":\"private-token-marker\"}".getBytes(StandardCharsets.UTF_8);
        var response = new ExecutionResult(200, Map.of("Authorization", List.of("Bearer private-token-marker")), bytes, 1, bytes.length, Optional.empty(), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
        var result = AssertionEngine.evaluate(response, List.of(rule(HEADER_CONTAINS, "Authorization", "private-token-marker"), rule(JSON_PATH_EQUALS, "$.token", "\"private-token-marker\"")),
                new SecretRedactor(List.of("private-token-marker"), List.of(), List.of()));
        assertThat(result).allMatch(row -> row.outcome() == PASS); assertThat(result.toString()).doesNotContain("private-token-marker");
    }
}
