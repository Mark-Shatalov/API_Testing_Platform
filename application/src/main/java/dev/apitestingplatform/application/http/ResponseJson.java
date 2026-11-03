package dev.apitestingplatform.application.http;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/** Shared bounded parser; only properties, indices and optional array wildcards. */
final class ResponseJson {
    static final int JSON_LIMIT = 2 * 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100).maxStringLength(JSON_LIMIT).maxNumberLength(1000).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    static final Configuration PATHS = Configuration.builder().jsonProvider(new JacksonJsonNodeJsonProvider(JSON)).mappingProvider(new JacksonMappingProvider(JSON)).build();
    static JsonPath path(String text, boolean wildcard) {
        if (text.isEmpty() || text.charAt(0) != '$') throw new IllegalArgumentException();
        int cursor = 1, steps = 0;
        while (cursor < text.length()) {
            if (++steps > 64) throw new IllegalArgumentException();
            char next = text.charAt(cursor++);
            if (next == '.') {
                int start = cursor;
                while (cursor < text.length() && (Character.isLetterOrDigit(text.charAt(cursor)) || text.charAt(cursor) == '_' || text.charAt(cursor) == '-')) cursor++;
                if (cursor == start) throw new IllegalArgumentException();
            } else if (next == '[' && cursor < text.length()) {
                char first = text.charAt(cursor);
                if (first == '\'' || first == '"') {
                    cursor++; boolean closed = false;
                    while (cursor < text.length()) {
                        char character = text.charAt(cursor++);
                        if (character < 32) throw new IllegalArgumentException();
                        if (character == '\\') { if (cursor >= text.length()) throw new IllegalArgumentException(); cursor++; }
                        else if (character == first) { closed = true; break; }
                    }
                    if (!closed) throw new IllegalArgumentException();
                } else if (first == '*' && wildcard) { cursor++;
                } else {
                    int start = cursor; while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) cursor++;
                    if (cursor == start) throw new IllegalArgumentException(); Integer.parseInt(text.substring(start, cursor));
                }
                if (cursor >= text.length() || text.charAt(cursor++) != ']') throw new IllegalArgumentException();
            } else throw new IllegalArgumentException();
        }
        JsonPath path = JsonPath.compile(text); if (!wildcard && !path.isDefinite()) throw new IllegalArgumentException(); return path;
    }

    private ResponseJson() { }
}
