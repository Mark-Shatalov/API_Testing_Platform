package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure, bounded template resolution. Request > environment > workspace; inputs are never mutated. */
public final class VariableResolver {
    public record Context(List<Variable> workspace, List<Variable> environment) {
        public Context { workspace = List.copyOf(workspace); environment = List.copyOf(environment); }
        public static Context empty() { return new Context(List.of(), List.of()); }
    }
    public record Resolved(RequestConfiguration request, String preview, List<RequestValidation.Issue> issues) {
        public Resolved { issues = List.copyOf(issues); }
        @Override public String toString() { return preview; }
    }
    private record Value(String text, String masked, boolean secret) { }
    private final Map<String, Variable> variables = new HashMap<>();
    private final Map<String, Value> cache = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final List<RequestValidation.Issue> issues = new ArrayList<>();
    private int budget = 32 * 1024 * 1024;

    private VariableResolver(Context context, List<Variable> local) {
        for (var scope : List.of(context.workspace(), context.environment(), local)) {
            Variable.validate(scope); scope.forEach(variable -> variables.put(variable.name(), variable));
        }
    }
    public static Resolved resolve(RequestConfiguration template, Context context, List<Variable> local) {
        VariableResolver resolver;
        try { resolver = new VariableResolver(context, local); }
        catch (IllegalArgumentException invalid) { return new Resolved(template, "", List.of(new RequestValidation.Issue("variables", invalid.getMessage()))); }
        Value url = resolver.field(template.url(), "url");
        List<RequestField> params = resolver.fields(template.queryParameters(), "params", false);
        List<RequestField> maskedParams = resolver.fields(template.queryParameters(), "params", true);
        RequestBody body = template.body();
        RequestBody resolvedBody = switch (body.type()) {
            case NONE -> body;
            case JSON -> RequestBody.json(resolver.field(body.content(), "body").text());
            case TEXT -> RequestBody.text(resolver.field(body.content(), "body").text());
            case FORM_URLENCODED -> RequestBody.form(resolver.fields(body.fields(), "form", false));
            case MULTIPART -> RequestBody.multipart(java.util.stream.IntStream.range(0, body.parts().size()).mapToObj(index -> {
                MultipartPart part = body.parts().get(index); if (!part.enabled()) return part;
                String field = "multipart." + index;
                return new MultipartPart(resolver.field(part.name(), field).text(), resolver.field(part.value(), field).text(),
                        part.file(), resolver.field(part.contentType(), field).text(), true);
            }).toList());
        };
        RequestConfiguration request = new RequestConfiguration(template.method(), url.text(), params,
                resolver.fields(template.headers(), "headers", false), resolver.fields(template.cookies(), "cookies", false),
                resolvedBody, template.timeout(), template.redirects(), template.protocol());
        String base = url.masked().split("#", 2)[0];
        String query = String.join("&", maskedParams.stream().filter(RequestField::enabled)
                .map(value -> encode(value.name()) + "=" + encode(value.value())).toList());
        if (!query.isEmpty()) base += (base.contains("?") ? base.endsWith("?") ? "" : "&" : "?") + query;
        return new Resolved(request, base, resolver.issues.stream().distinct().toList());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private List<RequestField> fields(List<RequestField> fields, String prefix, boolean masked) {
        return java.util.stream.IntStream.range(0, fields.size()).mapToObj(index -> {
            RequestField field = fields.get(index); if (!field.enabled()) return field;
            Value name = field(field.name(), prefix + "." + index), value = field(field.value(), prefix + "." + index);
            return new RequestField(masked ? name.masked() : name.text(), masked ? value.masked() : value.text(), true);
        }).toList();
    }
    private Value field(String input, String field) {
        try { return expand(input, 0); }
        catch (IllegalArgumentException invalid) { issues.add(new RequestValidation.Issue(field, invalid.getMessage())); return new Value("", "", false); }
    }
    private Value variable(String name, int depth) {
        if (cache.containsKey(name)) return cache.get(name);
        Variable variable = variables.get(name);
        if (variable == null) throw new IllegalArgumentException("Unknown variable {{" + name + "}}. Define it in request variables, the environment or workspace.");
        if (!visiting.add(name)) throw new IllegalArgumentException("Variable cycle involving {{" + name + "}}. Remove the circular reference.");
        try {
            Value expanded = expand(variable.value(), depth + 1);
            boolean secret = variable.secret() || expanded.secret();
            Value value = new Value(expanded.text(), secret ? "[secret]" : expanded.masked(), secret);
            cache.put(name, value); return value;
        } finally { visiting.remove(name); }
    }
    private Value expand(String input, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Variable nesting exceeds 32 levels.");
        StringBuilder text = new StringBuilder(), masked = new StringBuilder(); boolean secret = false;
        int cursor = 0;
        while (cursor < input.length()) {
            int start = input.indexOf("{{", cursor);
            if (start < 0) { append(text, input.substring(cursor)); append(masked, input.substring(cursor)); break; }
            boolean escaped = start > cursor && input.charAt(start - 1) == '\\';
            String prefix = input.substring(cursor, escaped ? start - 1 : start);
            append(text, prefix); append(masked, prefix);
            int end = input.indexOf("}}", start + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed variable placeholder. Use {{name}} or escape it with a backslash.");
            if (escaped) { String literal = input.substring(start, end + 2); append(text, literal); append(masked, literal); }
            else {
                String name = input.substring(start + 2, end);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Invalid variable placeholder. Use {{name}} with a valid variable name.");
                Value value = variable(name, depth); append(text, value.text()); append(masked, value.masked()); secret |= value.secret();
            }
            cursor = end + 2;
        }
        return new Value(text.toString(), masked.toString(), secret);
    }
    private void append(StringBuilder builder, String value) {
        if (value.length() > budget || builder.length() + (long) value.length() > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Expanded variables exceed the request size limit.");
        budget -= value.length(); builder.append(value);
    }
}
