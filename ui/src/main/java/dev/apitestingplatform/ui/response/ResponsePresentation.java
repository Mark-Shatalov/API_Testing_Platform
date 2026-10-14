package dev.apitestingplatform.ui.response;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import dev.apitestingplatform.domain.http.ExecutionResult;

import java.io.IOException;
import java.io.Writer;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Background-only decoding and bounded streaming JSON formatting; never builds a JSON tree. */
public record ResponsePresentation(String raw, String pretty, String contentType, boolean json,
        boolean truncated, String note, List<HeaderRow> headers, List<CookieRow> cookies) {
    public static final int BODY_LIMIT = 10 * 1024 * 1024;
    public static final int PRETTY_LIMIT = 512 * 1024;
    public static final int PAGE_SIZE = 64 * 1024;
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE);
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100).maxStringLength(PRETTY_LIMIT).maxNumberLength(1000).build()).build();

    public record HeaderRow(String name, String value) { }
    public record CookieRow(String name, String value, String domain, String path,
                            String expires, String maxAge, String secure, String httpOnly,
                            String sameSite, String raw) { }

    public static ResponsePresentation prepare(ExecutionResult result) {
        String contentType = header(result.responseHeaders(), "Content-Type");
        ByteBuffer buffer = result.bodyBuffer();
        boolean truncated = result.bodyTruncated() || buffer.remaining() > BODY_LIMIT;
        buffer.limit(Math.min(buffer.remaining(), BODY_LIMIT));
        Charset charset = StandardCharsets.UTF_8;
        String note = "";
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try { charset = Charset.forName(matcher.group(1)); }
            catch (IllegalArgumentException invalid) { note = "Unsupported charset; showing UTF-8 replacement text."; }
        }
        String raw;
        try {
            raw = charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE).decode(buffer).toString();
        } catch (java.nio.charset.CharacterCodingException impossible) { throw new IllegalStateException(impossible); }
        String pretty = null;
        boolean json = false;
        if (!truncated && !raw.isBlank() && raw.length() <= PRETTY_LIMIT) {
            String input = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            LimitedWriter writer = new LimitedWriter(PRETTY_LIMIT);
            try (JsonParser parser = JSON.createParser(input); JsonGenerator generator = JSON.createGenerator(writer)) {
                generator.useDefaultPrettyPrinter();
                if (parser.nextToken() != null) {
                    generator.copyCurrentStructure(parser);
                    if (parser.nextToken() != null) throw new IOException("Multiple JSON documents");
                    generator.flush();
                    json = true;
                    pretty = writer.text();
                }
            } catch (IOException | RuntimeException invalid) {
                if (contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
                    note = "JSON could not be formatted within safety limits; displaying the original text.";
                }
            }
        } else if (raw.length() > PRETTY_LIMIT) {
            note = "Large body: paged raw view. Pretty formatting is limited to 512 KiB.";
        }
        if (truncated) note = "Body truncated after " + Math.min(result.sizeBytes(), BODY_LIMIT)
                + " bytes. Search and Save cover the captured prefix only.";
        List<HeaderRow> headers = new ArrayList<>();
        result.responseHeaders().entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> entry.getValue().forEach(value -> headers.add(new HeaderRow(entry.getKey(), value))));
        List<CookieRow> cookies = new ArrayList<>();
        result.responseHeaders().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Set-Cookie")) values.forEach(value -> cookies.add(cookie(value)));
        });
        return new ResponsePresentation(raw, pretty, contentType.isBlank() ? "Not provided" : contentType,
                json, truncated, note, List.copyOf(headers), List.copyOf(cookies));
    }

    public static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
    }

    private static CookieRow cookie(String raw) {
        try {
            HttpCookie cookie = HttpCookie.parse(raw).getFirst();
            return new CookieRow(cookie.getName(), cookie.getValue(), value(cookie.getDomain()), value(cookie.getPath()),
                    attribute(raw, "Expires"), attribute(raw, "Max-Age"), cookie.getSecure() ? "Yes" : "No",
                    cookie.isHttpOnly() ? "Yes" : "No", attribute(raw, "SameSite"), raw);
        } catch (IllegalArgumentException invalid) {
            return new CookieRow("Unparsed cookie", "", "", "", "", "", "", "", "", raw);
        }
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static String attribute(String raw, String name) {
        for (String part : raw.split(";")) {
            int equals = part.indexOf('=');
            if (equals >= 0 && part.substring(0, equals).strip().equalsIgnoreCase(name)) return part.substring(equals + 1).strip();
        }
        return "";
    }

    private static final class LimitedWriter extends Writer {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted() || length > limit - text.length()) throw new IOException("Formatting limit");
            text.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        String text() { return text.toString(); }
    }
}
