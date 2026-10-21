package dev.apitestingplatform.application.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import dev.apitestingplatform.domain.http.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure validation and JSON formatting; independent of JavaFX, files and network traffic. */
public final class RequestValidation {
    private static final int BODY_LIMIT = 8 * 1024 * 1024;
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100).maxStringLength(BODY_LIMIT).maxNumberLength(1000).build()).build();
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

    private RequestValidation() { }

    public record Issue(String field, String message) { }

    public static List<Issue> validate(RequestConfiguration request) {
        List<Issue> issues = new ArrayList<>();
        try {
            URI uri = URI.create(request.url());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            issues.add(new Issue("url", "Enter an absolute HTTP or HTTPS URL without embedded credentials."));
        }
        validateFields("params", request.queryParameters(), issues, false);
        validateFields("headers", request.headers(), issues, true);
        for (int i = 0; i < request.cookies().size(); i++) {
            RequestField cookie = request.cookies().get(i);
            if (cookie.enabled() && (!cookie.name().matches(TOKEN)
                    || !cookie.value().matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*"))) {
                issues.add(new Issue("cookies." + i, "Use a valid cookie name and an ASCII value without separators."));
            }
        }
        RequestBody body = request.body();
        if (request.method() == HttpMethod.HEAD && body.type() != RequestBody.Type.NONE) {
            issues.add(new Issue("body", "HEAD requests must use the None body mode."));
        }
        if (body.type() == RequestBody.Type.JSON) {
            try { checkJson(body.content()); }
            catch (IllegalArgumentException exception) { issues.add(new Issue("body", exception.getMessage())); }
        }
        validateFields("form", body.fields(), issues, false);
        for (int i = 0; i < body.parts().size(); i++) {
            MultipartPart part = body.parts().get(i);
            if (!part.enabled()) continue;
            if (part.name().isBlank() || unsafeDisposition(part.name())
                    || part.file().isPresent() && (part.file().get().getFileName() == null
                        || unsafeDisposition(part.file().get().getFileName().toString()))
                    || !part.contentType().matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[\\x20-\\x7e]*)?")) {
                issues.add(new Issue("multipart." + i, "Use a field name, a safe file name and a valid content type (no quotes or control characters)."));
            }
        }
        if (body.type() == RequestBody.Type.MULTIPART && request.headers().stream()
                .anyMatch(h -> h.enabled() && (h.name().equalsIgnoreCase("Content-Type")
                        || h.name().equalsIgnoreCase("Content-Encoding")))) {
            issues.add(new Issue("body", "Multipart Content-Type and boundary are generated automatically; disable custom Content-Type/Content-Encoding headers."));
        }
        return List.copyOf(issues);
    }

    private static boolean unsafeDisposition(String text) {
        return text.indexOf('"') >= 0 || text.indexOf('\\') >= 0 || text.chars().anyMatch(c -> c < 32 || c == 127);
    }

    private static void validateFields(String field, List<RequestField> fields, List<Issue> issues, boolean headers) {
        for (int i = 0; i < fields.size(); i++) {
            RequestField row = fields.get(i);
            if (!row.enabled()) continue;
            if (row.name().isBlank()) issues.add(new Issue(field + "." + i, "Name is required; disable unused rows."));
            else if (headers && (!row.name().matches(TOKEN) || RESTRICTED.contains(row.name().toLowerCase(Locale.ROOT))
                    || row.value().chars().anyMatch(c -> c != '\t' && (c < 32 || c > 255) || c == 127))) {
                issues.add(new Issue(field + "." + i, "Invalid or transport-managed header. Host, Content-Length, Connection, Expect and Upgrade are automatic."));
            }
        }
    }

    public static void checkJson(String content) {
        if (content.length() > BODY_LIMIT) throw new IllegalArgumentException("JSON exceeds the 8 MiB request limit.");
        try (var parser = JSON.createParser(content)) {
            if (parser.nextToken() == null) throw new IllegalArgumentException("JSON is empty (line 1, column 1).");
            parser.skipChildren();
            if (parser.nextToken() != null) throw new IllegalArgumentException("JSON must contain one root value (line "
                    + parser.currentTokenLocation().getLineNr() + ").");
        } catch (JsonProcessingException exception) {
            throw jsonError(exception);
        } catch (IOException exception) { throw new IllegalArgumentException("JSON could not be read."); }
    }

    public static String formatJson(String content) {
        checkJson(content);
        try (var parser = JSON.createParser(content); var output = new BoundedWriter(); var generator = JSON.createGenerator(output)) {
            generator.useDefaultPrettyPrinter();
            JsonToken token = parser.nextToken();
            if (token != null) generator.copyCurrentStructure(parser);
            generator.flush();
            return output.toString();
        } catch (JsonProcessingException exception) { throw jsonError(exception); }
        catch (IOException exception) { throw new IllegalArgumentException("JSON could not be formatted."); }
    }

    private static IllegalArgumentException jsonError(JsonProcessingException exception) {
        if (exception.getLocation() == null)
            return new IllegalArgumentException("JSON exceeds parsing safety limits (maximum nesting depth: 100).");
        return new IllegalArgumentException("Invalid JSON at line " + exception.getLocation().getLineNr()
                + ", column " + exception.getLocation().getColumnNr() + ". Check JSON syntax.");
    }

    private static final class BoundedWriter extends java.io.Writer {
        private final StringBuilder output = new StringBuilder();
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted() || length > BODY_LIMIT - output.length())
                throw new IOException("Formatting limit");
            output.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return output.toString(); }
    }
}
