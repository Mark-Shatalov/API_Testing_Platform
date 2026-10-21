package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.storage.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Asynchronous application port. Implementations must never run database work on the caller thread. */
public interface LibraryRepository extends AutoCloseable {
    CompletionStage<List<LibraryItem>> list();
    CompletionStage<LibraryItem> create(LibraryItem.Kind kind, UUID parentId, String name);
    CompletionStage<SavedRequest> createRequest(UUID parentId, String name, RequestDraft draft);
    CompletionStage<LibraryItem> rename(UUID id, String name);
    CompletionStage<Void> reorder(UUID id, int position);
    CompletionStage<Void> delete(UUID id);
    CompletionStage<SavedRequest> load(UUID id);
    CompletionStage<SavedRequest> save(UUID id, long expectedVersion, RequestDraft draft);
    CompletionStage<Void> putDraft(RecoveryDraft draft);
    CompletionStage<Void> deleteDraft(UUID tabId);
    CompletionStage<List<RecoveryDraft>> recoverableDrafts();
    CompletionStage<RecoveryDraft> claimDraft(UUID tabId);
    CompletionStage<Void> finishSession();
    @Override void close();
}
