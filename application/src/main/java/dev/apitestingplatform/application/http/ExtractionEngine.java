package dev.apitestingplatform.application.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.PathNotFoundException;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.util.*;
import static dev.apitestingplatform.domain.http.ExtractionResult.Outcome.*;

/** Pure extraction against one immutable response. No saved environment is mutated. */
public final class ExtractionEngine {
    public record Evaluation(List<ExtractionResult> results, List<Variable> values, Set<String> targets) {
        public Evaluation { results = List.copyOf(results); values = List.copyOf(values); targets = Set.copyOf(targets); }
        @Override public String toString() { return "Extraction evaluation: " + results.size() + " rules"; }
    }
    private ExtractionEngine() { }

    public static List<RequestValidation.Issue> validate(List<ExtractionDefinition> rules) {
        if (rules.size() > 100) return List.of(new RequestValidation.Issue("extractions", "At most 100 extractions are allowed."));
        List<RequestValidation.Issue> issues = new ArrayList<>();
        Map<String, Long> names = rules.stream().filter(ExtractionDefinition::enabled).collect(java.util.stream.Collectors.groupingBy(ExtractionDefinition::variable, java.util.stream.Collectors.counting()));
        Map<UUID, Long> ids = rules.stream().filter(ExtractionDefinition::enabled).collect(java.util.stream.Collectors.groupingBy(ExtractionDefinition::id, java.util.stream.Collectors.counting()));
        for (int index = 0; index < rules.size(); index++) {
            var rule = rules.get(index); if (!rule.enabled()) continue;
            String problem = problem(rule);
            if (names.get(rule.variable()) > 1) problem = "Enabled extraction targets must be unique.";
            if (ids.get(rule.id()) > 1) problem = "Extraction identifiers must be unique.";
            if (problem != null) issues.add(new RequestValidation.Issue("extractions." + index, problem));
        }
        return List.copyOf(issues);
    }
    private static String problem(ExtractionDefinition rule) {
        try { Variable.validate(List.of(new Variable(rule.variable(), "", true))); }
        catch (IllegalArgumentException invalid) { return "Enter a variable name: start with a letter or underscore; use letters, digits, dots, underscores or hyphens."; }
        try { ResponseJson.path(rule.path(), true); }
        catch (RuntimeException invalid) { return "Use a JSONPath such as $.access_token or $.items[*].id. Filters, recursive descent and scripts are not supported."; }
        return null;
    }

    public static Evaluation evaluate(ExecutionResult response, List<ExtractionDefinition> rules, SecretRedactor redactor) {
        if (rules.size() > 100) throw new IllegalArgumentException("Too many extractions");
        List<ExtractionResult> results = new ArrayList<>(); List<Variable> values = new ArrayList<>(); Set<String> targets = new LinkedHashSet<>();
        var issues = validate(rules); JsonNode document = null; boolean parsed = false; String unavailable = null;
        for (int index = 0; index < rules.size(); index++) {
            var rule = rules.get(index);
            if (!rule.enabled()) { results.add(result(rule, DISABLED, "Extraction is disabled.", redactor)); continue; }
            targets.add(rule.variable());
            final int row = index;
            var invalid = issues.stream().filter(issue -> issue.field().equals("extractions." + row)).findFirst();
            if (invalid.isPresent()) { results.add(result(rule, NOT_RUN, invalid.get().message(), redactor)); continue; }
            if (Thread.currentThread().isInterrupted()) { results.add(result(rule, NOT_RUN, "Extraction was cancelled.", redactor)); continue; }
            if (!parsed) {
                parsed = true;
                if (response.error().isPresent() || response.statusCode() == 0) unavailable = "No complete response: " + response.error().map(error -> error.type().name()).orElse("network failure") + ".";
                else if (response.sizeBytes() > ResponseJson.JSON_LIMIT) unavailable = "Response exceeds the 2 MiB JSON extraction limit.";
                else try { document = ResponseJson.JSON.readTree(response.responseBody()); if (document == null || document.isMissingNode()) unavailable = "Response has no JSON body."; }
                catch (Exception invalidJson) { unavailable = "Response is not valid JSON or exceeds parsing limits."; }
            }
            if (unavailable != null) { results.add(result(rule, NOT_RUN, unavailable, redactor)); continue; }
            try {
                var path = ResponseJson.path(rule.path(), true);
                Object selected = path.read(document, ResponseJson.PATHS);
                JsonNode value = selected instanceof JsonNode node ? node : ResponseJson.JSON.valueToTree(selected);
                if (!path.isDefinite()) {
                    int count = value.size();
                    if (count != 1) { results.add(result(rule, FAILED, count == 0 ? "JSONPath matched no values." : "JSONPath matched " + count + " values; exactly one is required.", redactor)); continue; }
                    value = value.get(0);
                }
                String text = value.isTextual() ? value.textValue() : value.toString();
                if (text.length() > 65536) { results.add(result(rule, FAILED, "Extracted value exceeds the 65,536-character variable limit.", redactor)); continue; }
                // Every extracted value is secret, including values derived from a secret response.
                values.add(new Variable(rule.variable(), text, true));
                results.add(result(rule, EXTRACTED, "One value extracted; value is masked.", redactor));
            } catch (PathNotFoundException missing) { results.add(result(rule, FAILED, "JSONPath does not exist.", redactor)); }
            catch (RuntimeException failure) { results.add(result(rule, NOT_RUN, "The extraction could not be evaluated safely.", redactor)); }
        }
        return new Evaluation(results, values, targets);
    }
    public static Evaluation notRun(List<ExtractionDefinition> rules, SecretRedactor redactor, String reason) {
        return new Evaluation(rules.stream().map(rule -> result(rule, rule.enabled() ? NOT_RUN : DISABLED, rule.enabled() ? reason : "Extraction is disabled.", redactor)).toList(),
                List.of(), rules.stream().filter(ExtractionDefinition::enabled).map(ExtractionDefinition::variable).collect(java.util.stream.Collectors.toSet()));
    }
    private static ExtractionResult result(ExtractionDefinition rule, ExtractionResult.Outcome outcome, String reason, SecretRedactor redactor) {
        String name = redactor.text(rule.variable()); if (name.length() > 100) name = name.substring(0, 100) + "…";
        return new ExtractionResult(rule.id(), name, outcome, reason);
    }
}
