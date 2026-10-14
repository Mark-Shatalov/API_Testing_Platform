package dev.apitestingplatform.domain.http;

import java.util.Objects;

public record RequestBody(Type type, String content) {
    public enum Type { NONE, JSON, TEXT }

    public RequestBody {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        if (type == Type.NONE && !content.isEmpty()) {
            throw new IllegalArgumentException("An absent body cannot contain content");
        }
    }

    public static RequestBody none() { return new RequestBody(Type.NONE, ""); }
    public static RequestBody json(String content) { return new RequestBody(Type.JSON, content); }
    public static RequestBody text(String content) { return new RequestBody(Type.TEXT, content); }
}
