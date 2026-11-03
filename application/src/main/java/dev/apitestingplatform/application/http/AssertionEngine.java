package dev.apitestingplatform.application.http;

import com.fasterxml.jackson.databind.*;
import com.jayway.jsonpath.*;
import dev.apitestingplatform.domain.http.*;
import java.util.*;
import static dev.apitestingplatform.domain.http.AssertionResult.Outcome.*;

/** Bounded declarative checks over an immutable result. No scripts, filters, regexes or recursive JSONPath traversal. */
public final class AssertionEngine {
    private static final int JSON_LIMIT = ResponseJson.JSON_LIMIT;
    private static final ObjectMapper JSON = ResponseJson.JSON;
    private static final Configuration PATHS = ResponseJson.PATHS;
    private AssertionEngine() { }

    public static List<RequestValidation.Issue> validate(List<AssertionDefinition> assertions) {
        List<RequestValidation.Issue> issues = new ArrayList<>(); Set<UUID> ids = new HashSet<>();
        if (assertions.size() > 100) return List.of(new RequestValidation.Issue("tests", "At most 100 checks are allowed per request."));
        for (int index = 0; index < assertions.size(); index++) {
            var rule = assertions.get(index);
            if (!rule.enabled()) continue;
            String problem = problem(rule);
            if (!ids.add(rule.id())) problem = "Check identifiers must be unique within a request.";
            if (problem != null) issues.add(new RequestValidation.Issue("tests." + index, problem));
        }
        return List.copyOf(issues);
    }
    private static String problem(AssertionDefinition rule) {
        if (rule.name().isBlank()) return "Give this check a name.";
        try {
            switch (rule.type()) {
                case STATUS_EQUALS -> { long status = number(rule.expected()); if (status < 100 || status > 599) return "Expected HTTP status must be 100 to 599."; }
                case MAX_RESPONSE_TIME, MAX_BODY_SIZE -> number(rule.expected());
                case HEADER_EXISTS, HEADER_CONTAINS -> {
                    if (!rule.target().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) return "Enter a valid header name.";
                    if (rule.type() == AssertionDefinition.Type.HEADER_CONTAINS && rule.expected().isEmpty()) return "Enter the text the header must contain.";
                }
                case JSON_PATH_EXISTS, JSON_PATH_EQUALS -> {
                    path(rule.target());
                    if (rule.type() == AssertionDefinition.Type.JSON_PATH_EQUALS) JSON.readTree(rule.expected());
                    if (rule.type() == AssertionDefinition.Type.JSON_PATH_EQUALS && rule.expected().isBlank()) return "Enter a JSON value, for example 2, true or \"hello\".";
                }
            }
        } catch (Exception invalid) {
            return switch (rule.type()) {
                case STATUS_EQUALS, MAX_RESPONSE_TIME, MAX_BODY_SIZE -> "Enter a non-negative whole number within the supported integer range.";
                case JSON_PATH_EXISTS -> "Use a definite JSONPath such as $.id or $.users[0].name; filters, wildcards and scripts are not supported.";
                case JSON_PATH_EQUALS -> "Use a definite JSONPath and a valid JSON expected value, for example 2, true or \"hello\".";
                default -> "Invalid check configuration.";
            };
        }
        return null;
    }
    private static long number(String text) {
        if (!text.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
        return Long.parseLong(text);
    }
    private static JsonPath path(String text) { return ResponseJson.path(text, false); }

    public static List<AssertionResult> evaluate(ExecutionResult response, List<AssertionDefinition> definitions, SecretRedactor redactor) {
        if (definitions.size() > 100) throw new IllegalArgumentException("Too many checks");
        List<AssertionResult> results = new ArrayList<>(); JsonNode document = null; String jsonError = null; boolean parsed = false;
        for (var rule : definitions) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!rule.enabled()) { results.add(result(rule, DISABLED, "", "", "Check is disabled.", redactor)); continue; }
            String invalid = problem(rule);
            if (invalid != null) { results.add(result(rule, NOT_RUN, "", "", invalid, redactor)); continue; }
            if (response.statusCode() == 0) {
                results.add(result(rule, NOT_RUN, "", "", "No HTTP response: " + response.error().map(error -> error.type().name()).orElse("network failure") + ".", redactor)); continue;
            }
            if (rule.type() == AssertionDefinition.Type.JSON_PATH_EXISTS || rule.type() == AssertionDefinition.Type.JSON_PATH_EQUALS) {
                if (!parsed) {
                    parsed = true;
                    if (response.bodyTruncated()) jsonError = "Response body is truncated; JSON checks require the complete body.";
                    else if (response.sizeBytes() > JSON_LIMIT) jsonError = "Response exceeds the 2 MiB JSON assertion limit.";
                    else try { document = JSON.readTree(response.responseBody()); if (document == null || document.isMissingNode()) jsonError = "Response has no JSON body."; }
                    catch (Exception invalidJson) { jsonError = "Response is not valid JSON or exceeds parsing limits."; }
                }
                if (jsonError != null) { results.add(result(rule, NOT_RUN, "", "", jsonError, redactor)); continue; }
            }
            try {
                switch (rule.type()) {
                    case STATUS_EQUALS -> results.add(result(rule, response.statusCode() == number(rule.expected()) ? PASS : FAIL,
                            rule.expected(), Integer.toString(response.statusCode()), "Exact HTTP status comparison.", redactor));
                    case HEADER_EXISTS, HEADER_CONTAINS -> {
                        List<String> values = response.responseHeaders().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(rule.target()))
                                .flatMap(entry -> entry.getValue().stream()).toList();
                        boolean present = response.responseHeaders().keySet().stream().anyMatch(name -> name.equalsIgnoreCase(rule.target()));
                        boolean pass = rule.type() == AssertionDefinition.Type.HEADER_EXISTS ? present : values.stream().anyMatch(value -> value.contains(rule.expected()));
                        String expected = rule.type() == AssertionDefinition.Type.HEADER_EXISTS ? "present" : redactor.header(rule.target(), rule.expected());
                        String actual = !present ? "missing" : values.isEmpty() ? "present (empty)" : redactor.header(rule.target(), String.join("; ", values));
                        results.add(result(rule, pass ? PASS : FAIL, expected, actual, rule.type() == AssertionDefinition.Type.HEADER_EXISTS
                                ? "Header name lookup is case-insensitive." : "At least one header value must contain the text (case-sensitive).", redactor));
                    }
                    case MAX_RESPONSE_TIME, MAX_BODY_SIZE -> {
                        long actual = rule.type() == AssertionDefinition.Type.MAX_RESPONSE_TIME ? response.durationMillis() : response.sizeBytes();
                        boolean pass = actual <= number(rule.expected());
                        boolean unknown = response.bodyTruncated() && pass;
                        results.add(result(rule, unknown ? NOT_RUN : pass ? PASS : FAIL, "<= " + rule.expected(), Long.toString(actual),
                                unknown ? "Download stopped early; the full response measurement is unknown." : "Inclusive upper bound.", redactor));
                    }
                    case JSON_PATH_EXISTS, JSON_PATH_EQUALS -> {
                        Object selected;
                        try { selected = path(rule.target()).read(document, PATHS); }
                        catch (PathNotFoundException missing) { results.add(result(rule, FAIL, rule.type() == AssertionDefinition.Type.JSON_PATH_EXISTS ? "present" : rule.expected(), "missing", "JSONPath does not exist.", redactor)); continue; }
                        JsonNode actual = selected instanceof JsonNode node ? node : JSON.valueToTree(selected);
                        boolean pass = rule.type() == AssertionDefinition.Type.JSON_PATH_EXISTS || equalJson(actual, JSON.readTree(rule.expected()));
                        results.add(result(rule, pass ? PASS : FAIL, rule.type() == AssertionDefinition.Type.JSON_PATH_EXISTS ? "present" : rule.expected(),
                                actual.toString(), "JSON values are compared by type and structure; numeric values ignore scale.", redactor));
                    }
                }
            } catch (Exception failed) { results.add(result(rule, NOT_RUN, "", "", "The check could not be evaluated safely.", redactor)); }
        }
        return List.copyOf(results);
    }
    private static boolean equalJson(JsonNode first, JsonNode second) {
        if (first.isNumber() && second.isNumber()) return first.decimalValue().compareTo(second.decimalValue()) == 0;
        if (first.isArray() && second.isArray()) {
            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++) if (!equalJson(first.get(index), second.get(index))) return false;
            return true;
        }
        if (first.isObject() && second.isObject()) {
            if (first.size() != second.size()) return false;
            var fields = first.fields(); while (fields.hasNext()) {
                var field = fields.next(); if (!second.has(field.getKey()) || !equalJson(field.getValue(), second.get(field.getKey()))) return false;
            }
            return true;
        }
        return first.equals(second);
    }
    private static AssertionResult result(AssertionDefinition rule, AssertionResult.Outcome outcome, String expected, String actual, String reason, SecretRedactor redactor) {
        return new AssertionResult(rule.id(), clip(redactor.text(rule.name())), outcome, clip(redactor.text(expected)), clip(redactor.text(actual)), clip(redactor.text(reason)));
    }
    private static String clip(String value) { return value.length() <= 512 ? value : value.substring(0, 512) + "…"; }
}
