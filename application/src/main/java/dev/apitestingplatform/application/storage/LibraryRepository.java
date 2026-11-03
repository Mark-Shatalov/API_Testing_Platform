package dev.apitestingplatform.application.storage;

import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Asynchronous application port. Implementations must never run database work on the caller thread. */
public interface LibraryRepository extends AutoCloseable {
    java.nio.file.Path databasePath();
    CompletionStage<java.nio.file.Path> backupDatabase(java.nio.file.Path destination);
    CompletionStage<Void> stageDatabaseRestore(java.nio.file.Path source);
    CompletionStage<Boolean> pendingDatabaseRestore();
    CompletionStage<Void> cancelDatabaseRestore();
    String startupNotice();
    CompletionStage<List<LibraryItem>> list();
    CompletionStage<dev.apitestingplatform.domain.run.CollectionSnapshot> snapshotCollection(UUID collectionId);
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
    CompletionStage<WorkspaceVariables> loadVariables(UUID workspaceId);
    CompletionStage<Void> saveWorkspaceVariables(UUID workspaceId, long expectedVersion, List<Variable> variables);
    CompletionStage<Environment> createEnvironment(UUID workspaceId, String name, List<Variable> variables);
    CompletionStage<Environment> saveEnvironment(Environment environment);
    CompletionStage<Void> deleteEnvironment(UUID environmentId, long expectedVersion);
    CompletionStage<Void> selectEnvironment(UUID workspaceId, UUID environmentId);
    CompletionStage<WorkspaceTransfer> exportWorkspace(UUID workspaceId);
    CompletionStage<UUID> importWorkspace(WorkspaceTransfer workspace);
    CompletionStage<Void> saveHistory(dev.apitestingplatform.domain.run.HistoryRecord record);
    CompletionStage<List<dev.apitestingplatform.domain.run.HistorySummary>> listHistory(int limit, int offset);
    CompletionStage<dev.apitestingplatform.domain.run.HistoryRecord> loadHistory(UUID id);
    CompletionStage<dev.apitestingplatform.domain.run.HistorySettings> historySettings();
    CompletionStage<Void> saveHistorySettings(dev.apitestingplatform.domain.run.HistorySettings settings);
    CompletionStage<Void> clearHistory();
    @Override void close();
}
