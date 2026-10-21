package dev.apitestingplatform.ui.response;

import dev.apitestingplatform.domain.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class ResponsePresentationTest {
    @TempDir Path directory;

    static ExecutionResult result(int status, byte[] bytes, Map<String, List<String>> headers, NetworkError.Type error) {
        return new ExecutionResult(status, headers, bytes, 42, bytes.length,
                error == null ? Optional.empty() : Optional.of(new NetworkError(error, "Safe error message")),
                Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
    }
    static ExecutionResult json(String body) {
        return result(200, body.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", List.of("application/json")), null);
    }

    @Test void detectsJsonWithoutContentTypeAndPreservesRawText() {
        String raw = "{\"name\":\"é\",\"number\":123,\"active\":true}";
        ResponsePresentation view = ResponsePresentation.prepare(result(200, raw.getBytes(StandardCharsets.UTF_8), Map.of(), null));
        assertThat(view.json()).isTrue();
        assertThat(view.raw()).isEqualTo(raw);
        assertThat(view.pretty()).contains("\n", "\"name\" : \"é\"");
        assertThat(view.contentType()).isEqualTo("Not provided");
    }

    @Test void preservesPlainMalformedAndMultipleJsonDocuments() {
        for (String raw : List.of("Hello world", "{broken}", "{} {}", "[1,2", "\"unterminated")) {
            ResponsePresentation view = ResponsePresentation.prepare(json(raw));
            assertThat(view.pretty()).isNull();
            assertThat(view.raw()).isEqualTo(raw);
        }
    }

    @Test void rejectsExcessiveJsonNestingAndPrettyOutputExpansion() {
        ResponsePresentation nested = ResponsePresentation.prepare(json("[".repeat(150) + "0" + "]".repeat(150)));
        assertThat(nested.pretty()).isNull();
        String compact = "[" + "0,".repeat(180_000) + "0]";
        ResponsePresentation expanded = ResponsePresentation.prepare(json(compact));
        assertThat(expanded.pretty() == null).as("Expanded pretty output exceeds its bounded writer").isTrue();
        assertThat(expanded.raw()).isEqualTo(compact);
    }

    @Test void decodesDeclaredCharsetAndHandlesUnsupportedCharsets() {
        ResponsePresentation latin = ResponsePresentation.prepare(result(200, "café".getBytes(StandardCharsets.ISO_8859_1),
                Map.of("CONTENT-TYPE", List.of("text/plain; charset=\"ISO-8859-1\"")), null));
        assertThat(latin.raw()).isEqualTo("café");
        ResponsePresentation unsupported = ResponsePresentation.prepare(result(200, "text".getBytes(StandardCharsets.UTF_8),
                Map.of("content-type", List.of("text/plain; charset=not-real")), null));
        assertThat(unsupported.raw()).isEqualTo("text");
        assertThat(unsupported.note()).contains("Unsupported charset");
        assertThat(ResponsePresentation.prepare(json("\uFEFF{\"ok\":true}")).json()).isTrue();
    }

    @Test void retainsDuplicateHeadersAndDetailedCookiesIncludingInvalidOnes() {
        String cookie = "session=abc; Path=/api; Domain=example.com; Max-Age=60; Secure; HttpOnly; SameSite=Strict";
        ResponsePresentation view = ResponsePresentation.prepare(result(200, new byte[0], Map.of(
                "X-Item", List.of("first", "second"), "Set-Cookie", List.of(cookie, "invalid")), null));
        assertThat(view.headers().stream().filter(row -> row.name().equals("X-Item"))).hasSize(2);
        assertThat(view.cookies()).hasSize(2);
        ResponsePresentation.CookieRow parsed = view.cookies().getFirst();
        assertThat(parsed.name()).isEqualTo("session");
        assertThat(parsed.value()).isEqualTo("abc");
        assertThat(parsed.path()).isEqualTo("/api");
        assertThat(parsed.sameSite()).isEqualTo("Strict");
        assertThat(parsed.secure()).isEqualTo("Yes");
        assertThat(parsed.httpOnly()).isEqualTo("Yes");
        assertThat(view.cookies().get(1).raw()).isEqualTo("invalid");
    }

    @Test void mapsEveryResponseState() {
        assertThat(ResponseState.of(json("{}"))).isEqualTo(ResponseState.SUCCESS);
        assertThat(ResponseState.of(result(404, new byte[0], Map.of(), null))).isEqualTo(ResponseState.HTTP_ERROR);
        Map<NetworkError.Type, ResponseState> states = Map.of(NetworkError.Type.TLS, ResponseState.TLS_ERROR,
                NetworkError.Type.DNS, ResponseState.DNS_ERROR, NetworkError.Type.CONNECTION_REFUSED, ResponseState.CONNECTION_ERROR,
                NetworkError.Type.REDIRECT_LIMIT, ResponseState.REDIRECT_ERROR,
                NetworkError.Type.TIMEOUT, ResponseState.TIMEOUT, NetworkError.Type.CANCELLED, ResponseState.CANCELLED,
                NetworkError.Type.RESPONSE_TOO_LARGE, ResponseState.RESPONSE_TOO_LARGE);
        states.forEach((error, state) -> assertThat(ResponseState.of(result(0, new byte[0], Map.of(), error))).isEqualTo(state));
    }

    @Test void pagesAndSearchesLargeBodyAndMarksTruncation() {
        String raw = "x".repeat(ResponsePresentation.PAGE_SIZE * 2) + "needle";
        ResponsePresentation view = ResponsePresentation.prepare(json(raw));
        assertThat(view.pretty()).isNull();
        assertThat(BodySearch.find(view.raw(), "needle", 0, false)).isEqualTo(ResponsePresentation.PAGE_SIZE * 2);
        assertThat(BodySearch.find("one two one", "one", 1, false)).isEqualTo(8);
        assertThat(BodySearch.find("one two one", "one", -1, true)).isEqualTo(8);
        ResponsePresentation truncated = ResponsePresentation.prepare(result(200, "{\"partial\":1".getBytes(StandardCharsets.UTF_8),
                Map.of(), NetworkError.Type.RESPONSE_TOO_LARGE));
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.note()).contains("captured prefix");
        assertThat(truncated.pretty()).isNull();
    }

    @Test void capsBodiesAtTenMiBAndSavesOnlyCapturedBytes() throws Exception {
        byte[] bytes = new byte[ResponsePresentation.BODY_LIMIT + 1];
        java.util.Arrays.fill(bytes, (byte) 'x');
        ExecutionResult oversized = result(200, bytes, Map.of(), null);
        ResponsePresentation view = ResponsePresentation.prepare(oversized);
        assertThat(view.raw()).hasSize(ResponsePresentation.BODY_LIMIT);
        assertThat(view.truncated()).isTrue();
        Path file = directory.resolve("prefix.bin");
        ResponseBodyFiles.save(oversized, file);
        assertThat(Files.size(file)).isEqualTo(ResponsePresentation.BODY_LIMIT);
    }

    @Test void savesOriginalBinaryBytesAndReplacesExistingFileWithoutLeavingTemporaryFiles() throws Exception {
        byte[] bytes = {0, (byte) 0xff, 1, 2};
        ExecutionResult result = result(200, bytes, Map.of(), null);
        Path file = directory.resolve("response.bin");
        Files.writeString(file, "old data");
        ResponseBodyFiles.save(result, file);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).containsExactly(file); }
    }

    @Test void syntaxHighlighterHasLinearCoverageForLongEscapedStrings() {
        String text = "{\"key\":\"" + "\\\"".repeat(20_000) + "\",\"n\":12,\"b\":false}";
        var spans = JsonHighlighting.styles(text);
        assertThat(spans.length()).isEqualTo(text.length());
        assertThat(spans.stream().flatMap(span -> span.getStyle().stream()).toList())
                .contains("json-key", "json-string", "json-number", "json-literal");
    }
}
