package dev.apitestingplatform.importexport;

import dev.apitestingplatform.domain.run.HistoryRecord;
import dev.apitestingplatform.domain.storage.WorkspaceTransfer;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Bounded file/parser worker. Failed writes leave the previous destination intact and remove temporary files. */
public final class TransferFiles implements AutoCloseable {
    private Path protectedDatabase;
    public TransferFiles() { }
    public TransferFiles(Path database) { protectedDatabase = database.toAbsolutePath().normalize(); }
    private Path output(Path destination) throws IOException { return protectedDatabase == null ? destination : dev.apitestingplatform.application.operations.ManagedFiles.output(protectedDatabase, destination, false); }
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon().name("portable-files-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> CompletionStage<T> submit(Work<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try { worker.execute(() -> { try { result.complete(action.run()); } catch (Exception failure) { result.completeExceptionally(failure instanceof IOException ? failure : new IOException("File operation failed. Existing data was not replaced.")); } }); }
        catch (RejectedExecutionException busy) { result.completeExceptionally(new IOException("File worker is busy or closed. Try again.")); }
        return result.minimalCompletionStage();
    }
    public CompletionStage<PortableWorkspaceFormat.Document> read(Path path) { return submit(() -> { try (var input = Files.newInputStream(path)) { return PortableWorkspaceFormat.read(input.readNBytes(PortableWorkspaceFormat.LIMIT + 1)); } }); }
    public CompletionStage<Void> workspace(Path path, WorkspaceTransfer workspace, boolean includeSecrets) { return submit(() -> { atomic(output(path), PortableWorkspaceFormat.write(workspace, includeSecrets)); return null; }); }
    public CompletionStage<Void> report(Path path, HistoryRecord report, boolean html) { return submit(() -> { atomic(output(path), html ? ReportFormat.html(report) : ReportFormat.json(report)); return null; }); }
    private static void atomic(Path path, byte[] bytes) throws IOException {
        Path target = path.toAbsolutePath().normalize(), temporary = Files.createTempFile(target.getParent(), ".api-export-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() { worker.shutdown(); }
}
