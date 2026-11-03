package dev.apitestingplatform.importexport;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.apitestingplatform.application.http.SecretRedactor;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.io.*;
import java.util.*;

/** Versioned portable format. No SQL column names, DPAPI envelopes, runtime values or execution history. */
public final class PortableWorkspaceFormat {
    public static final int LIMIT = 32 * 1024 * 1024;
    private static final String OMITTED = "{{REDACTED}}";
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxNameLength(256).maxStringLength(8 * 1024 * 1024).maxNumberLength(30).build()).build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static {
        var text = JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    public record Document(String format, int version, boolean secretsIncluded, WorkspaceTransfer workspace) {
        public Document { if (!"api-testing-platform-workspace".equals(format) || version != 1 || workspace == null) throw new IllegalArgumentException("Unsupported workspace format/version."); }
        @Override public String toString() { return "Portable workspace document; values hidden"; }
    }
    private PortableWorkspaceFormat() { }
    public static byte[] write(WorkspaceTransfer workspace, boolean includeSecrets) throws IOException {
        WorkspaceTransfer output;
        try { output = includeSecrets ? workspace : withoutSecrets(workspace); } catch (IllegalArgumentException large) { throw new IOException("Workspace exceeds the safe redaction/transfer limits. Split it into smaller workspaces."); }
        return boundedWrite(new Document("api-testing-platform-workspace", 1, includeSecrets, output), LIMIT);
    }
    public static Document read(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("Workspace file exceeds the 32 MiB limit.");
        try { return JSON.readValue(bytes, Document.class); }
        catch (UnrecognizedPropertyException unsupported) { throw new IOException("Unsupported fields found. Only API Testing Platform workspace format version 1 is supported; nothing was imported."); }
        catch (Exception invalid) { throw new IOException("Invalid or unsupported workspace file. Check version, structure, IDs, parent/environment references, order and size limits. Nothing was imported."); }
    }
    static byte[] boundedWrite(Object value, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(bytes) {
            int size;
            @Override public void write(int value) throws IOException { if (++size > limit) throw new IOException("Export exceeds its size limit."); out.write(value); }
            @Override public void write(byte[] data, int offset, int length) throws IOException { if (length > limit - size) throw new IOException("Export exceeds its size limit."); size += length; out.write(data, offset, length); }
        };
        JSON.writerWithDefaultPrettyPrinter().writeValue(bounded, value); return bytes.toByteArray();
    }
    public static WorkspaceTransfer withoutSecrets(WorkspaceTransfer workspace) {
        List<String> secrets = new ArrayList<>();
        java.util.stream.Stream.concat(workspace.variables().stream(), workspace.environments().stream().flatMap(environment -> environment.variables().stream()))
                .filter(value -> value.secret() || sensitiveName(value.name())).forEach(value -> secrets.add(value.value()));
        for (var node : workspace.nodes()) if (node.request() != null) {
            var request = node.request(); request.variables().stream().filter(value -> value.secret() || sensitiveName(value.name())).forEach(value -> secrets.add(value.value()));
            var auth = request.authentication(); secrets.addAll(List.of(auth.bearerToken(), auth.username(), auth.password(), auth.keyValue()));
            request.cookies().forEach(value -> secrets.add(value.value()));
            request.headers().stream().filter(header -> sensitiveName(header.name())).forEach(header -> { secrets.add(header.value()); authorizationSecrets(header.value(), secrets); });
            request.params().stream().filter(field -> sensitiveName(field.name())).forEach(field -> secrets.add(field.value()));
        }
        java.util.stream.Stream.concat(workspace.variables().stream(), workspace.environments().stream().flatMap(environment -> environment.variables().stream())).forEach(value -> urlSecrets(value.value(), secrets));
        for (var node : workspace.nodes()) if (node.request() != null) { urlSecrets(node.request().url(), secrets); node.request().variables().forEach(value -> urlSecrets(value.value(), secrets)); }
        Set<String> uniqueSecrets = new HashSet<>(secrets); uniqueSecrets.remove("");
        if (uniqueSecrets.size() > 4096 || uniqueSecrets.stream().mapToLong(String::length).sum() > 2 * 1024 * 1024) throw new IllegalArgumentException("Secret redaction budget exceeded.");
        SecretRedactor redactor = new SecretRedactor(uniqueSecrets, List.of(), List.of());
        List<WorkspaceTransfer.Node> nodes = workspace.nodes().stream().map(node -> new WorkspaceTransfer.Node(node.id(), node.kind(), node.parentId(), node.name(), node.position(), node.request() == null ? null : request(node.request(), redactor))).toList();
        return new WorkspaceTransfer(workspace.workspaceId(), nodes, variables(workspace.variables(), redactor), workspace.environments().stream().map(environment ->
                new WorkspaceTransfer.TransferEnvironment(environment.id(), environment.workspaceId(), environment.name(), variables(environment.variables(), redactor))).toList(), workspace.selectedEnvironmentId());
    }
    private static RequestDraft request(RequestDraft draft, SecretRedactor redactor) {
        var auth = draft.authentication();
        Authentication safe = new Authentication(auth.mode(), value(auth.bearerToken(), redactor), value(auth.username(), redactor), value(auth.password(), redactor), mask(auth.keyName(), redactor), value(auth.keyValue(), redactor), auth.placement());
        var checks = draft.assertions().stream().map(check -> new AssertionDefinition(check.id(), check.name(), check.type(), mask(check.target(), redactor),
                switch (check.type()) { case STATUS_EQUALS, MAX_BODY_SIZE, MAX_RESPONSE_TIME -> mask(check.expected(), redactor); case JSON_PATH_EQUALS -> "\"[omitted]\""; default -> check.expected().isEmpty() ? "" : OMITTED; }, check.enabled())).toList();
        var extractions = draft.extractions().stream().map(rule -> new ExtractionDefinition(rule.id(), rule.variable(), mask(rule.path(), redactor), rule.enabled())).toList();
        return new RequestDraft(draft.method(), url(draft.url(), redactor), fields(draft.params(), redactor), fields(draft.headers(), redactor), fields(draft.cookies(), redactor), draft.bodyType(), draft.bodyText().isEmpty() ? "" : OMITTED,
                fields(draft.form(), redactor), draft.multipart().stream().map(part -> new RequestDraft.Part(mask(part.name(), redactor), part.file() ? "" : value(part.value(), redactor), part.file(), mask(part.contentType(), redactor), part.enabled())).toList(),
                draft.timeout(), draft.redirects(), draft.protocol(), variables(draft.variables(), redactor), safe, checks, extractions);
    }
    private static List<RequestField> fields(List<RequestField> fields, SecretRedactor redactor) { return fields.stream().map(field -> new RequestField(mask(field.name(), redactor), value(field.value(), redactor), field.enabled())).toList(); }
    private static List<Variable> variables(List<Variable> variables, SecretRedactor redactor) {
        return variables.stream().map(variable -> new Variable(variable.name(), variable.secret() || sensitiveName(variable.name()) ? OMITTED :
                variable.value().matches("(?is)https?://.*") ? url(variable.value(), redactor) : mask(variable.value(), redactor), variable.secret() || sensitiveName(variable.name()))).toList();
    }
    private static void authorizationSecrets(String value, List<String> secrets) {
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) secrets.add(value.substring(7).strip());
        if (value.regionMatches(true, 0, "Basic ", 0, 6) && value.length() <= 65536) {
            try { for (String part : new String(Base64.getDecoder().decode(value.substring(6).strip()), java.nio.charset.StandardCharsets.UTF_8).split(":", 2)) secrets.add(part); } catch (IllegalArgumentException ignored) { }
        }
    }
    private static void urlSecrets(String text, List<String> secrets) {
        var user = java.util.regex.Pattern.compile("://([^/?#]*)@").matcher(text);
        if (user.find()) for (String value : user.group(1).split(":", 2)) { try { secrets.add(java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8)); } catch (IllegalArgumentException ignored) { } }
        int query = text.indexOf('?'); if (query < 0) return;
        for (String part : text.substring(query + 1).split("#", 2)[0].split("&")) {
            String[] pair = part.split("=", 2); if (pair.length != 2) continue;
            try { if (sensitiveName(java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8))) secrets.add(java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8)); } catch (IllegalArgumentException ignored) { }
        }
    }
    private static boolean sensitiveName(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.contains("token") || key.contains("password") || key.contains("secret") || key.contains("apikey") || key.contains("authorization") || key.contains("cookie");
    }
    private static String value(String text, SecretRedactor redactor) { return text.isEmpty() ? "" : text.matches("(?:\\{\\{[A-Za-z_][A-Za-z0-9_.-]{0,99}\\}\\})+") ? text : OMITTED; }
    private static String mask(String text, SecretRedactor redactor) { return redactor.text(text).replace("[secret]", OMITTED).replaceAll("(?i)%5Bsecret%5D", OMITTED); }
    private static String url(String text, SecretRedactor redactor) {
        String safe = mask(redactor.url(text), redactor); int fragment = safe.indexOf('#'); if (fragment >= 0) safe = safe.substring(0, fragment);
        int query = safe.indexOf('?'); if (query < 0) return safe;
        List<String> fields = new ArrayList<>();
        for (String pair : safe.substring(query + 1).split("&", -1)) { String[] parts = pair.split("=", 2); fields.add(parts[0] + "=" + value(parts.length == 2 ? parts[1] : "", redactor)); }
        return safe.substring(0, query + 1) + String.join("&", fields);
    }
}
