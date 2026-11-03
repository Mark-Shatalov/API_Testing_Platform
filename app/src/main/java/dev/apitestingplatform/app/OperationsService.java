package dev.apitestingplatform.app;

import dev.apitestingplatform.application.operations.*;
import dev.apitestingplatform.application.storage.LibraryRepository;
import dev.apitestingplatform.domain.operations.OperationalSettings;
import dev.apitestingplatform.domain.run.HistorySettings;
import dev.apitestingplatform.httpclient.NetworkConfiguration;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Application-owned network preferences, safe diagnostics and repository backup boundary. */
final class OperationsService implements AppOperations, AutoCloseable {
    private final LibraryRepository repository; private final OperationalPreferences preferences; private final OperationalLog log; private final String startupNotice;
    private final ExecutorService work = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("app-operations-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    OperationsService(LibraryRepository repository, OperationalPreferences preferences, OperationalLog log, String startupNotice) { this.repository = repository; this.preferences = preferences; this.log = log; this.startupNotice = startupNotice; }
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    private <T> CompletionStage<T> submit(Work<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try { work.execute(() -> { try { future.complete(action.run()); } catch (Exception failure) { future.completeExceptionally(failure); } }); }
        catch (RejectedExecutionException busy) { future.completeExceptionally(new IOException("Operational worker is busy or closed. Try again.")); }
        return future.minimalCompletionStage();
    }
    public CompletionStage<Info> info() {
        return submit(preferences::load).thenCompose(loaded -> repository.pendingDatabaseRestore().handle((pending, failure) -> new Info(loaded.settings(), repository.databasePath().getParent().toString(), repository.databasePath().toString(), log.directory().toString(),
                failure == null && pending, String.join(" ", List.of(loaded.notice(), startupNotice, repository.startupNotice())).strip(), log.notice())));
    }
    public CompletionStage<Void> saveSettings(OperationalSettings settings) {
        return submit(() -> {
            NetworkConfiguration.trust(settings.additionalCaFile());
            var normalized = new OperationalSettings(settings.proxyMode(), settings.proxyHost(), settings.proxyPort(), settings.additionalCaFile().isEmpty() ? "" : Path.of(settings.additionalCaFile()).toAbsolutePath().normalize().toString(), settings.responseLimitKiB());
            preferences.save(normalized); log.record(EventLog.Level.INFO, EventLog.Component.SETTINGS, EventLog.Code.SETTINGS_SAVED, UUID.randomUUID()); return null;
        });
    }
    public CompletionStage<Path> backup(Path destination) { UUID id = UUID.randomUUID(); return repository.backupDatabase(destination).whenComplete((path, failure) -> log.record(failure == null ? EventLog.Level.INFO : EventLog.Level.ERROR, EventLog.Component.BACKUP, failure == null ? EventLog.Code.BACKUP_CREATED : EventLog.Code.STORAGE_FAILED, id)); }
    public CompletionStage<Void> stageRestore(Path source) { UUID id = UUID.randomUUID(); return repository.stageDatabaseRestore(source).whenComplete((ignored, failure) -> log.record(failure == null ? EventLog.Level.INFO : EventLog.Level.ERROR, EventLog.Component.BACKUP, failure == null ? EventLog.Code.RESTORE_STAGED : EventLog.Code.RESTORE_REJECTED, id)); }
    public CompletionStage<Void> cancelRestore() { return repository.cancelDatabaseRestore().thenRun(() -> log.record(EventLog.Level.INFO, EventLog.Component.BACKUP, EventLog.Code.RESTORE_CANCELLED, UUID.randomUUID())); }
    public CompletionStage<Void> openLogs() { return submit(() -> {
        Files.createDirectories(log.directory());
        if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) throw new IOException("Open the displayed log directory manually in File Explorer.");
        java.awt.Desktop.getDesktop().open(log.directory().toFile()); return null;
    }); }
    public CompletionStage<Void> diagnostics(Path destination) {
        return info().thenCompose(info -> repository.historySettings().exceptionally(failure -> HistorySettings.defaults()).thenCompose(retention -> log.snapshot().thenCompose(events -> submit(() -> {
            Path target = ManagedFiles.output(repository.databasePath(), destination, false), temporary = Files.createTempFile(target.getParent(), ".api-diagnostics-", ".tmp");
            Properties summary = new Properties(); summary.setProperty("format", "api-testing-platform-diagnostics"); summary.setProperty("version", "1"); summary.setProperty("applicationVersion", "0.1.0-SNAPSHOT");
            summary.setProperty("java", safeProperty("java.version")); summary.setProperty("os", safeProperty("os.name")); summary.setProperty("architecture", safeProperty("os.arch"));
            summary.setProperty("proxyMode", info.settings().proxyMode().name()); summary.setProperty("additionalCaConfigured", Boolean.toString(!info.settings().additionalCaFile().isEmpty())); summary.setProperty("responseLimitKiB", Integer.toString(info.settings().responseLimitKiB()));
            summary.setProperty("historyMaxEntries", Integer.toString(retention.maxEntries())); summary.setProperty("historyMaxAgeDays", Integer.toString(retention.maxAgeDays())); summary.setProperty("droppedLogEvents", Long.toString(log.dropped()));
            try {
                try (var output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    output.putNextEntry(new ZipEntry("diagnostics.properties")); summary.store(output, "No database, paths, proxy addresses, certificates, settings files or secret values are included"); output.closeEntry();
                    output.putNextEntry(new ZipEntry("events.log")); output.write(events); output.closeEntry();
                }
                if (Files.size(temporary) > 5 * 1024 * 1024) throw new IOException("Diagnostic archive exceeds the 5 MiB limit.");
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.record(EventLog.Level.INFO, EventLog.Component.SETTINGS, EventLog.Code.DIAGNOSTICS_CREATED, UUID.randomUUID()); return (Void) null;
            } finally { Files.deleteIfExists(temporary); }
        }))));
    }
    private static String safeProperty(String key) { String value = System.getProperty(key, "unknown"); return value.matches("[A-Za-z0-9 ._()+-]{1,100}") ? value : "omitted"; }
    public void close() { work.shutdown(); }
}
