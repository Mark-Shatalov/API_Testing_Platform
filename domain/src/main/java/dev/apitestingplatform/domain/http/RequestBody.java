package dev.apitestingplatform.domain.http;

import java.util.Objects;
import java.util.List;

public record RequestBody(Type type, String content, List<RequestField> fields, List<MultipartPart> parts) {
    public enum Type { NONE, JSON, TEXT, FORM_URLENCODED, MULTIPART }

    public RequestBody(Type type, String content) { this(type, content, List.of(), List.of()); }

    public RequestBody {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        fields = List.copyOf(fields);
        parts = List.copyOf(parts);
        if (type == Type.NONE && !content.isEmpty()) {
            throw new IllegalArgumentException("An absent body cannot contain content");
        }
        if (type != Type.FORM_URLENCODED && !fields.isEmpty()
                || type != Type.MULTIPART && !parts.isEmpty()
                || (type == Type.FORM_URLENCODED || type == Type.MULTIPART) && !content.isEmpty()) {
            throw new IllegalArgumentException("Body content does not match its mode");
        }
    }

    public static RequestBody none() { return new RequestBody(Type.NONE, ""); }
    public static RequestBody json(String content) { return new RequestBody(Type.JSON, content); }
    public static RequestBody text(String content) { return new RequestBody(Type.TEXT, content); }
    public static RequestBody form(List<RequestField> fields) {
        return new RequestBody(Type.FORM_URLENCODED, "", fields, List.of());
    }
    public static RequestBody multipart(List<MultipartPart> parts) {
        return new RequestBody(Type.MULTIPART, "", List.of(), parts);
    }
}
