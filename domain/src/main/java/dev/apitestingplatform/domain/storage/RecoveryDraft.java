package dev.apitestingplatform.domain.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryDraft(UUID tabId, UUID requestId, UUID workspaceId, String title,
                            RequestDraft draft, Instant modifiedAt) {
    public RecoveryDraft {
        Objects.requireNonNull(tabId); Objects.requireNonNull(workspaceId); Objects.requireNonNull(title);
        Objects.requireNonNull(draft); Objects.requireNonNull(modifiedAt);
        if (title.isBlank() || title.length() > 200) throw new IllegalArgumentException("Invalid draft title");
    }
}
