package dev.apitestingplatform.application.run;

import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.RequestDraft;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Converts a saved editable draft without creating JavaFX properties or controls. */
public final class DraftRequestCompiler {
    public record Compiled(RequestConfiguration request, List<RequestValidation.Issue> issues) { public Compiled { issues = List.copyOf(issues); } }
    private DraftRequestCompiler() { }
    public static Compiled compile(RequestDraft draft) {
        Duration timeout;
        try {
            timeout = Duration.ofNanos(new java.math.BigDecimal(draft.timeout()).movePointRight(9).longValueExact());
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { return new Compiled(null, List.of(new RequestValidation.Issue("timeout", "Timeout must be a positive number of seconds, at most 86400 (up to 9 decimal places)."))); }
        try {
            RequestBody body = switch (draft.bodyType()) {
                case NONE -> RequestBody.none();
                case JSON -> RequestBody.json(draft.bodyText());
                case TEXT -> RequestBody.text(draft.bodyText());
                case FORM_URLENCODED -> RequestBody.form(draft.form());
                case MULTIPART -> RequestBody.multipart(draft.multipart().stream().map(part -> new MultipartPart(part.name(), part.file() ? "" : part.value(),
                        part.file() && part.enabled() ? Optional.of(uploadPath(part.value())) : Optional.empty(),
                        part.file() ? part.contentType() : "text/plain; charset=UTF-8", part.enabled())).toList());
            };
            return new Compiled(new RequestConfiguration(draft.method(), draft.url(), draft.params(), draft.headers(), draft.cookies(), body,
                    timeout, draft.redirects(), draft.protocol()), List.of());
        } catch (IllegalArgumentException invalid) { return new Compiled(null, List.of(new RequestValidation.Issue("body", "Check the saved body and enabled upload file paths."))); }
    }
    private static Path uploadPath(String value) { if (value.isBlank()) throw new IllegalArgumentException(); return Path.of(value); }
}
