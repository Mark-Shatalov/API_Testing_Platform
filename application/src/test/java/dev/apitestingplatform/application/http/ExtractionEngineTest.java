package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.apitestingplatform.domain.http.ExtractionResult.Outcome.*;
import static org.assertj.core.api.Assertions.*;

class ExtractionEngineTest {
    private static ExtractionDefinition rule(String variable, String path) { return new ExtractionDefinition(UUID.randomUUID(), variable, path, true); }
    private static ExecutionResult response(String body) { return response(body, null); }
    private static ExecutionResult response(String body, NetworkError.Type error) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ExecutionResult(error == null || error == NetworkError.Type.RESPONSE_TOO_LARGE ? 200 : 0, Map.of(), bytes, 1, bytes.length,
                error == null ? Optional.empty() : Optional.of(new NetworkError(error, "private-network-error")), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
    }
    private static ExtractionEngine.Evaluation evaluate(String body, ExtractionDefinition... rules) { return ExtractionEngine.evaluate(response(body), List.of(rules), SecretRedactor.empty()); }

    @Test void extractsTypedSingleValuesInOrderWithoutExposingValuesInResults() {
        var rules = List.of(rule("token", "$.token"), rule("number", "$.n"), rule("flag", "$.flag"), rule("nothing", "$.nothing"), rule("object", "$.object"), rule("list", "$.list"));
        var evaluated = ExtractionEngine.evaluate(response("{\"token\":\"private-Привет\",\"n\":9007199254740993.12345,\"flag\":true,\"nothing\":null,\"object\":{\"a\":1},\"list\":[1,2]}"), rules, SecretRedactor.empty());
        assertThat(evaluated.results()).extracting(ExtractionResult::id).containsExactlyElementsOf(rules.stream().map(ExtractionDefinition::id).toList());
        assertThat(evaluated.results()).allMatch(result -> result.outcome() == EXTRACTED);
        assertThat(evaluated.values()).extracting(Variable::value).containsExactly("private-Привет", "9007199254740993.12345", "true", "null", "{\"a\":1}", "[1,2]");
        assertThat(evaluated.values()).allMatch(Variable::secret);
        assertThat(evaluated.results().toString() + evaluated + evaluated.values()).doesNotContain("private-Привет");
    }
    @Test void distinguishesMissingMultipleEmptyAndSingleWildcardMatches() {
        var evaluated = evaluate("{\"items\":[{\"id\":1},{\"id\":2}],\"one\":[{\"id\":3}],\"empty\":[],\"blank\":\"\"}",
                rule("missing", "$.missing"), rule("multiple", "$.items[*].id"), rule("empty", "$.empty[*]"), rule("one", "$.one[*].id"), rule("blank", "$.blank"));
        assertThat(evaluated.results()).extracting(ExtractionResult::outcome).containsExactly(FAILED, FAILED, FAILED, EXTRACTED, EXTRACTED);
        assertThat(evaluated.results().get(1).details()).contains("2 values");
        assertThat(evaluated.results().get(2).details()).contains("no values");
        assertThat(evaluated.values()).extracting(Variable::value).containsExactly("3", "");
    }
    @ParameterizedTest @ValueSource(strings = {"", "<html>failed</html>", "{", "{\"id\":1,\"id\":2}", "{\"id\":1} true"})
    void rejectsEmptyMalformedDuplicateAndTrailingJson(String body) {
        assertThat(evaluate(body, rule("id", "$.id")).results().getFirst().outcome()).isEqualTo(NOT_RUN);
    }
    @ParameterizedTest @ValueSource(strings = {"$.items[?(@.id)]", "$..token", "$.items.length()", "$.items[0:2]", "$.items[-1]", "$.items[0,1]", "$.*"})
    void blocksUnsafeOrUnboundedPathOperatorsBeforeTraffic(String path) {
        assertThat(ExtractionEngine.validate(List.of(rule("token", path)))).singleElement().satisfies(issue -> assertThat(issue.field()).isEqualTo("extractions.0"));
    }
    @Test void validatesNamesDuplicateTargetsAndDisabledDrafts() {
        var disabled = new ExtractionDefinition(UUID.randomUUID(), "bad name", "$..secret", false);
        assertThat(ExtractionEngine.validate(List.of(disabled))).isEmpty();
        assertThat(evaluate("not json", disabled).results().getFirst().outcome()).isEqualTo(DISABLED);
        assertThat(ExtractionEngine.validate(List.of(rule("bad name", "$.id")))).hasSize(1);
        assertThat(ExtractionEngine.validate(List.of(rule("token", "$.a"), rule("token", "$.b")))).hasSize(2);
    }
    @Test void enforcesBodyValueDepthAndPathLimits() {
        assertThat(evaluate("{\"id\":\"" + "x".repeat(65537) + "\"}", rule("id", "$.id")).results().getFirst().outcome()).isEqualTo(FAILED);
        assertThat(evaluate(" ".repeat(2 * 1024 * 1024) + "{}", rule("id", "$.id")).results().getFirst().details()).contains("2 MiB");
        assertThat(evaluate("[".repeat(101) + "0" + "]".repeat(101), rule("id", "$[0]")).results().getFirst().outcome()).isEqualTo(NOT_RUN);
        assertThat(ExtractionEngine.validate(List.of(rule("id", "$" + ".a".repeat(65))))).isNotEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"TIMEOUT", "CANCELLED", "DNS", "TLS", "IO", "RESPONSE_TOO_LARGE"})
    void incompleteOrFailedResponsesNeverProduceValues(String type) {
        var evaluated = ExtractionEngine.evaluate(response("{\"token\":\"private\"}", NetworkError.Type.valueOf(type)), List.of(rule("token", "$.token")), SecretRedactor.empty());
        assertThat(evaluated.values()).isEmpty(); assertThat(evaluated.results().getFirst().outcome()).isEqualTo(NOT_RUN);
        assertThat(evaluated.results().toString()).doesNotContain("private-network-error");
    }
    @Test void failedRefreshRemovesOldTargetAndPreservesUnrelatedAndDisabledValues() {
        var session = new RunVariables();
        session.publish(session.snapshot(), evaluate("{\"token\":\"old\",\"other\":1}", rule("token", "$.token"), rule("other", "$.other")));
        var snapshot = session.snapshot();
        session.publish(snapshot, evaluate("{}", rule("token", "$.missing"), new ExtractionDefinition(UUID.randomUUID(), "other", "$", false)));
        assertThat(session.snapshot().values()).extracting(Variable::name).containsExactly("other");
        assertThat(snapshot.values()).hasSize(2); // immutable previous request snapshot
    }
    @Test void clearAndConcurrentPublicationRejectLateCompletion() {
        var session = new RunVariables(); var before = session.snapshot(); var evaluated = evaluate("{\"token\":\"new\"}", rule("token", "$.token"));
        session.clear();
        assertThat(session.publish(before, evaluated).getFirst().outcome()).isEqualTo(NOT_RUN); assertThat(session.snapshot().values()).isEmpty();
        var same = session.snapshot(); session.publish(same, evaluated);
        assertThat(session.publish(same, evaluated).getFirst().outcome()).isEqualTo(NOT_RUN);
        assertThat(session.snapshot().values()).extracting(Variable::value).containsExactly("new");
    }
    @Test void runValuesOverrideEnvironmentButRequestWinsAndResponseBracesRemainLiteral() {
        var context = new VariableResolver.Context(List.of(new Variable("id", "workspace", false)), List.of(new Variable("id", "environment", false)),
                List.of(new Variable("id", "{{server_text}}", true)));
        var request = new RequestConfiguration(HttpMethod.GET, "https://example.test/{{id}}", List.of(), List.of(), List.of(), RequestBody.none(), Duration.ofSeconds(1), RedirectPolicy.NEVER, HttpProtocol.HTTP_1_1);
        var resolved = VariableResolver.resolve(request, context, List.of());
        assertThat(resolved.issues()).isEmpty(); assertThat(resolved.request().url()).endsWith("{{server_text}}"); assertThat(resolved.preview()).doesNotContain("server_text");
        assertThat(VariableResolver.resolve(request, context, List.of(new Variable("id", "local", false))).request().url()).endsWith("local");
        assertThat(context.environment().getFirst().value()).isEqualTo("environment");
        assertThat(context.toString()).doesNotContain("server_text");
    }
    @Test void sessionCapacityFailureDoesNotPublishPartialOutputs() {
        var session = new RunVariables(); var definitions = new ArrayList<ExtractionDefinition>();
        for (int i = 0; i < 100; i++) definitions.add(rule("v" + i, "$.v"));
        var evaluation = evaluate("{\"v\":\"" + "x".repeat(12000) + "\"}", definitions.toArray(ExtractionDefinition[]::new));
        assertThat(session.publish(session.snapshot(), evaluation)).noneMatch(result -> result.outcome() == EXTRACTED);
        assertThat(session.snapshot().values()).isEmpty();
    }
}
