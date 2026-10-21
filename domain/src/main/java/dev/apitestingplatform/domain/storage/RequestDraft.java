package dev.apitestingplatform.domain.storage;

import dev.apitestingplatform.domain.http.*;
import java.util.List;
import java.util.Objects;

/** Editable, possibly incomplete state; all body-mode drafts survive saving and crash recovery. */
public record RequestDraft(HttpMethod method, String url, List<RequestField> params, List<RequestField> headers,
        List<RequestField> cookies, RequestBody.Type bodyType, String bodyText, List<RequestField> form,
        List<Part> multipart, String timeout, RedirectPolicy redirects, HttpProtocol protocol) {
    public record Part(String name, String value, boolean file, String contentType, boolean enabled) {
        public Part { Objects.requireNonNull(name); Objects.requireNonNull(value); Objects.requireNonNull(contentType); }
    }
    public RequestDraft {
        Objects.requireNonNull(method); Objects.requireNonNull(url); Objects.requireNonNull(bodyType);
        Objects.requireNonNull(bodyText); Objects.requireNonNull(timeout); Objects.requireNonNull(redirects); Objects.requireNonNull(protocol);
        params = List.copyOf(params); headers = List.copyOf(headers); cookies = List.copyOf(cookies);
        form = List.copyOf(form); multipart = List.copyOf(multipart);
        if (params.size() > 1000 || headers.size() > 1000 || cookies.size() > 1000 || form.size() > 1000 || multipart.size() > 1000
                || bodyText.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Draft exceeds editor limits");
    }
    public static RequestDraft empty() {
        return new RequestDraft(HttpMethod.GET, "", List.of(), List.of(), List.of(), RequestBody.Type.NONE,
                "", List.of(), List.of(), "30", RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
    }
}
