package dev.apitestingplatform.domain.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LibraryItem(UUID id, Kind kind, UUID parentId, String name, int position,
                          long version, Instant modifiedAt) {
    public enum Kind { WORKSPACE, COLLECTION, FOLDER, REQUEST }
    public LibraryItem {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(name); Objects.requireNonNull(modifiedAt);
        name = name.strip();
        if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(c -> c < 32)
                || position < 0 || version < 0 || (kind == Kind.WORKSPACE) != (parentId == null))
            throw new IllegalArgumentException("Invalid library item");
    }
    @Override public String toString() { return name; }
}
