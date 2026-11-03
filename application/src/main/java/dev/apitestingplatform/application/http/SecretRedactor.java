package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Safe text/header/URL boundary for summaries, future history, diagnostics and logs. */
public final class SecretRedactor {
    private static final Set<String> STANDARD_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final Set<String> STANDARD_QUERY = Set.of("token", "access_token", "api_key", "apikey", "password", "secret");
    private final List<String> values;
    private final List<String> sourceValues;
    private final java.util.regex.Pattern replacements;
    private final Set<String> headers;
    private final Set<String> query;
    public SecretRedactor(Collection<String> values, Collection<String> headers, Collection<String> query) {
        sourceValues = values.stream().filter(Objects::nonNull).toList();
        Set<String> expanded = new HashSet<>();
        for (String value : values) if (value != null && !value.isEmpty()) {
            expanded.add(value);
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); expanded.add(encoded);
            expanded.add(java.util.regex.Pattern.compile("%[0-9A-F]{2}").matcher(encoded).replaceAll(match -> match.group().toLowerCase(Locale.ROOT)));
            expanded.add(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));
        }
        this.values = expanded.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        replacements = this.values.isEmpty() ? null : java.util.regex.Pattern.compile("\\[secret\\]|%5[Bb]secret%5[Dd]|" + String.join("|", this.values.stream().map(java.util.regex.Pattern::quote).toList()));
        Set<String> names = new HashSet<>(STANDARD_HEADERS); for (String name : headers) names.add(name.toLowerCase(Locale.ROOT)); this.headers = Set.copyOf(names);
        names = new HashSet<>(STANDARD_QUERY); for (String name : query) names.add(name.toLowerCase(Locale.ROOT)); this.query = Set.copyOf(names);
    }
    public SecretRedactor withSecrets(Collection<String> additional) {
        List<String> combined = new ArrayList<>(sourceValues); combined.addAll(additional);
        return new SecretRedactor(combined, headers, query);
    }
    public static SecretRedactor empty() { return new SecretRedactor(List.of(), List.of(), List.of()); }
    public String text(String input) {
        return replacements == null ? input : replacements.matcher(input).replaceAll(match ->
                match.group().equalsIgnoreCase("%5Bsecret%5D") ? match.group() : "[secret]");
    }
    public String header(String name, String value) { return headers.contains(name.toLowerCase(Locale.ROOT)) ? "[secret]" : text(value); }
    public String url(String input) {
        String url = input.replaceFirst("(?<=://)[^/?#]*@", "[secret]@");
        int fragment = url.indexOf('#'); if (fragment >= 0) url = url.substring(0, fragment);
        int question = url.indexOf('?'); if (question < 0) return text(url);
        List<String> parts = new ArrayList<>();
        for (String part : url.substring(question + 1).split("&", -1)) {
            String[] pair = part.split("=", 2);
            try {
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                parts.add(query.contains(name) ? pair[0] + "=%5Bsecret%5D" : part);
            } catch (IllegalArgumentException invalid) { parts.add("[secret]"); }
        }
        return text(url.substring(0, question + 1) + String.join("&", parts));
    }
    public List<RequestField> headers(List<RequestField> fields) {
        return fields.stream().map(field -> new RequestField(text(field.name()), header(field.name(), field.value()), field.enabled())).toList();
    }
    @Override public String toString() { return "SecretRedactor[values hidden]"; }
}
