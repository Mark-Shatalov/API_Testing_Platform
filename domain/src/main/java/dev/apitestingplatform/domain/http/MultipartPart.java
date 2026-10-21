package dev.apitestingplatform.domain.http;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Immutable upload description. File content is streamed by infrastructure, never read by the UI. */
public record MultipartPart(String name, String value, Optional<Path> file,
                            String contentType, boolean enabled) {
    public MultipartPart {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(contentType, "contentType");
        file = file.map(path -> path.toAbsolutePath().normalize());
    }

    public static MultipartPart text(String name, String value) {
        return new MultipartPart(name, value, Optional.empty(), "text/plain; charset=UTF-8", true);
    }

    public static MultipartPart file(String name, Path path, String contentType) {
        return new MultipartPart(name, "", Optional.of(path), contentType, true);
    }
}
