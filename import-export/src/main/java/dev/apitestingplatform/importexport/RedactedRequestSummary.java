package dev.apitestingplatform.importexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apitestingplatform.application.http.SecretRedactor;
import dev.apitestingplatform.application.http.VariableResolver;
import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.RequestConfiguration;
import java.util.*;

/** Non-portable diagnostic summary. Bodies, cookie values and authentication settings are omitted. */
public final class RedactedRequestSummary {
    private RedactedRequestSummary() { }
    public static String write(VariableResolver.Resolved prepared) throws java.io.IOException {
        if (!prepared.issues().isEmpty() || !RequestValidation.validate(prepared.request()).isEmpty()) throw new java.io.IOException("Prepare a valid request before exporting a summary");
        RequestConfiguration request = prepared.request(); SecretRedactor redactor = prepared.redactor();
        SecretRedactor transport = new SecretRedactor(List.of(), request.sensitiveHeaderNames(), request.sensitiveQueryNames());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("format", "redacted-request-summary"); summary.put("version", 1);
        summary.put("method", request.method().name()); summary.put("url", transport.url(redactor.url(request.url())));
        summary.put("headers", transport.headers(redactor.headers(request.headers())));
        summary.put("queryParameters", request.queryParameters().stream().map(field -> Map.of("name", redactor.text(field.name()),
                "value", "[omitted]", "enabled", field.enabled())).toList());
        summary.put("body", "[omitted]"); summary.put("cookies", "[omitted]");
        summary.put("timeoutMillis", request.timeout().toMillis()); summary.put("redirectPolicy", request.redirects().name());
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        if (json.length() > 1024 * 1024) throw new java.io.IOException("Summary exceeds the size limit");
        return json;
    }
}
