package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RequestValidationTest {
    private RequestConfiguration request(HttpMethod method, String url, RequestBody body, List<RequestField> headers) {
        return new RequestConfiguration(method, url, List.of(), headers, List.of(), body,
                Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1);
    }

    @Test void validatesUrlsWithoutEchoingCredentials() {
        for (String url : List.of("relative", "ftp://localhost/a", "http://name:secret@localhost", "http://localhost:70000", "http://localhost:0")) {
            var issues = RequestValidation.validate(request(HttpMethod.GET, url, RequestBody.none(), List.of()));
            assertThat(issues).extracting(RequestValidation.Issue::field).contains("url");
            assertThat(issues.toString()).doesNotContain("secret");
        }
        assertThat(RequestValidation.validate(RequestConfiguration.get("https://example.com/a?q=x#fragment"))).isEmpty();
    }

    @Test void reportsJsonSyntaxLineAndRejectsMultipleRootsAndExcessiveDepth() {
        for (String json : List.of("{\n\"x\": }", "", "{} []", "[".repeat(101) + "0" + "]".repeat(101))) {
            assertThatThrownBy(() -> RequestValidation.checkJson(json)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RequestValidation.checkJson("{\n\"x\": }"))
                .hasMessageContaining("line 2").hasMessageContaining("column");
        assertThat(RequestValidation.formatJson("{\"x\":\"é\",\"n\":1}"))
                .contains("\n", "\"x\" : \"é\"", "\"n\" : 1");
    }

    @Test void skipsDisabledInvalidRowsAndReportsManagedHeaders() {
        var headers = List.of(new RequestField("Bad\nName", "secret", false), new RequestField("Host", "wrong"),
                new RequestField("X-Test", "bad\r\nsecret"));
        var issues = RequestValidation.validate(request(HttpMethod.POST, "http://localhost", RequestBody.text("hello"), headers));
        assertThat(issues).extracting(RequestValidation.Issue::field).containsExactly("headers.1", "headers.2");
        assertThat(issues.toString()).doesNotContain("secret");
    }

    @Test void rejectsHeadBodiesAndUnsafeMultipartMetadata() {
        assertThat(RequestValidation.validate(request(HttpMethod.HEAD, "http://localhost", RequestBody.text("x"), List.of())))
                .extracting(RequestValidation.Issue::field).contains("body");
        var body = RequestBody.multipart(List.of(MultipartPart.text("evil\r\nheader", "x"),
                MultipartPart.file("file", Path.of("C:/"), "application/octet-stream")));
        assertThat(RequestValidation.validate(request(HttpMethod.POST, "http://localhost", body,
                List.of(new RequestField("Content-Type", "multipart/form-data")))))
                .extracting(RequestValidation.Issue::field).contains("multipart.0", "multipart.1", "body");
    }
}
