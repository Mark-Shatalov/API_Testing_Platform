package dev.apitestingplatform.domain.storage;

import java.util.Objects;

public record SavedRequest(LibraryItem item, RequestDraft draft) {
    public SavedRequest {
        Objects.requireNonNull(item); Objects.requireNonNull(draft);
        if (item.kind() != LibraryItem.Kind.REQUEST) throw new IllegalArgumentException("Not a request");
    }
}
