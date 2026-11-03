package dev.apitestingplatform.application.operations;

import dev.apitestingplatform.domain.operations.OperationalSettings;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/** Asynchronous operational boundary, implemented and owned by app. */
public interface AppOperations {
    record Info(OperationalSettings settings, String dataDirectory, String databaseFile, String logsDirectory,
            boolean pendingRestore, String startupNotice, String logNotice) { }
    CompletionStage<Info> info();
    CompletionStage<Void> saveSettings(OperationalSettings settings);
    CompletionStage<Path> backup(Path destination);
    CompletionStage<Void> stageRestore(Path source);
    CompletionStage<Void> cancelRestore();
    CompletionStage<Void> diagnostics(Path destination);
    CompletionStage<Void> openLogs();
}
