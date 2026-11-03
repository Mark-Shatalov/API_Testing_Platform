package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure, bounded template resolution. Request > run > environment > workspace; inputs are never mutated. */
public final class VariableResolver {
    public record Context(List<Variable> workspace, List<Variable> environment, List<Variable> run) {
        public Context(List<Variable> workspace, List<Variable> environment) { this(workspace, environment, List.of()); }
        public Context { workspace = List.copyOf(workspace); environment = List.copyOf(environment); run = List.copyOf(run); }
        public Context withRun(List<Variable> values) { return new Context(workspace, environment, values); }
        public static Context empty() { return new Context(List.of(), List.of()); }
    }
    public record Resolved(RequestConfiguration request, String preview, List<RequestValidation.Issue> issues, SecretRedactor redactor) {
        public Resolved(RequestConfiguration request, String preview, List<RequestValidation.Issue> issues) { this(request, preview, issues, SecretRedactor.empty()); }
        public Resolved { issues = List.copyOf(issues); }
        @Override public String toString() { return preview; }
    }
    private record Value(String text, String masked, boolean secret) { }
    private final Map<String, Variable> variables = new HashMap<>();
    private final Set<String> literalVariables = new HashSet<>();
    private final Map<String, Value> cache = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final List<RequestValidation.Issue> issues = new ArrayList<>();
    private final Set<String> secrets = new HashSet<>(), sensitiveHeaders = new HashSet<>(), sensitiveQuery = new HashSet<>();
    private int budget = 32 * 1024 * 1024;

    private VariableResolver(Context context, List<Variable> local) {
        for (var scope : List.of(context.workspace(), context.environment(), context.run(), local)) {
            Variable.validate(scope); scope.forEach(variable -> {
                variables.put(variable.name(), variable); if (variable.secret()) secrets.add(variable.value());
            });
        }
        context.run().forEach(value -> literalVariables.add(value.name()));
        local.forEach(value -> literalVariables.remove(value.name()));
    }
    public static Resolved resolve(RequestConfiguration template, Context context, List<Variable> local) {
        return resolve(template, context, local, Authentication.none());
    }
    public static Resolved resolve(RequestConfiguration template, Context context, List<Variable> local, Authentication authentication) {
        VariableResolver resolver;
        try { resolver = new VariableResolver(context, local); }
        catch (IllegalArgumentException invalid) { return new Resolved(template, "", List.of(new RequestValidation.Issue("variables", invalid.getMessage()))); }
        resolver.sensitiveHeaders.addAll(template.sensitiveHeaderNames()); resolver.sensitiveQuery.addAll(template.sensitiveQueryNames());
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
                resolvedBody, template.timeout(), template.redirects(), template.protocol(), resolver.sensitiveHeaders, resolver.sensitiveQuery);
        Authentication auth = resolver.authentication(authentication);
        resolver.secrets.addAll(List.of(authentication.bearerToken(), authentication.username(), authentication.password(), authentication.keyValue()));
        if (resolver.issues.isEmpty()) {
            var applied = AuthenticationApplier.apply(request, auth); resolver.issues.addAll(applied.issues()); resolver.secrets.addAll(applied.secrets()); request = applied.request();
            if (request.queryParameters().size() > maskedParams.size()) {
                maskedParams = new ArrayList<>(maskedParams); maskedParams.add(new RequestField(auth.keyName(), "[secret]"));
            }
        }
        // Literal credential headers/cookies must not escape into summaries either.
        for (RequestField header : request.headers()) if (Set.of("authorization", "proxy-authorization", "cookie", "set-cookie").contains(header.name().toLowerCase(Locale.ROOT)))
            resolver.secrets.add(header.value());
        request.cookies().forEach(cookie -> resolver.secrets.add(cookie.value()));
        SecretRedactor redactor = new SecretRedactor(resolver.secrets, request.sensitiveHeaderNames(), request.sensitiveQueryNames());
        String base = url.masked().split("#", 2)[0];
        String query = String.join("&", maskedParams.stream().filter(RequestField::enabled)
                .map(value -> encode(value.name()) + "=" + encode(value.value())).toList());
        if (!query.isEmpty()) base += (base.contains("?") ? base.endsWith("?") ? "" : "&" : "?") + query;
        return new Resolved(request, redactor.url(base), resolver.issues.stream().distinct().toList(), redactor);
    }
    private Authentication authentication(Authentication auth) {
        return switch (auth.mode()) {
            case NONE -> Authentication.none();
            case BEARER -> new Authentication(auth.mode(), authValue(auth.bearerToken()), "", "", "", "", auth.placement());
            case BASIC -> new Authentication(auth.mode(), "", authValue(auth.username()), authValue(auth.password()), "", "", auth.placement());
            case API_KEY -> new Authentication(auth.mode(), "", "", "", authValue(auth.keyName()), authValue(auth.keyValue()), auth.placement());
        };
    }
    private String authValue(String template) {
        String value = field(template, "auth").text();
        if (value.length() > 65536) { issues.add(new RequestValidation.Issue("auth", "Resolved authentication field exceeds the size limit.")); return ""; }
        return value;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private List<RequestField> fields(List<RequestField> fields, String prefix, boolean masked) {
        return java.util.stream.IntStream.range(0, fields.size()).mapToObj(index -> {
            RequestField field = fields.get(index); if (!field.enabled()) return field;
            Value name = field(field.name(), prefix + "." + index), value = field(field.value(), prefix + "." + index);
            if (!masked && (name.secret() || value.secret())) {
                if (prefix.equals("headers")) sensitiveHeaders.add(name.text());
                if (prefix.equals("params")) sensitiveQuery.add(name.text());
            }
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
        if (variable == null) throw new IllegalArgumentException("Unknown variable {{" + name + "}}. Define it in request variables, extract it from an earlier response in this session, or set it in the environment or workspace.");
        if (!visiting.add(name)) throw new IllegalArgumentException("Variable cycle involving {{" + name + "}}. Remove the circular reference.");
        try {
            Value expanded = literalVariables.contains(name) ? new Value(variable.value(), variable.value(), variable.secret()) : expand(variable.value(), depth + 1);
            boolean secret = variable.secret() || expanded.secret();
            if (secret) secrets.add(expanded.text());
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
