package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.storage.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded serialized queue for all database work, including reads and startup migrations. */
public final class SqliteLibraryRepository implements LibraryRepository {
    private final Path file;
    private final UUID session = UUID.randomUUID();
    private final ProtectedDraftCodec codec;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("sqlite-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Connection db;
    private FileChannel lockFile;
    private FileLock lock;

    public SqliteLibraryRepository(Path file, PayloadProtector protector) { this(file, protector, null); }
    SqliteLibraryRepository(Path file, PayloadProtector protector, List<Migration> migrations) {
        this.file = file.toAbsolutePath().normalize(); codec = new ProtectedDraftCodec(Objects.requireNonNull(protector));
        worker.execute(() -> {
            try {
                Files.createDirectories(this.file.getParent());
                lockFile = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = lockFile.tryLock();
                if (lock == null) throw new StorageException("The data directory is already in use by another application instance.");
                db = DriverManager.getConnection("jdbc:sqlite:" + this.file);
                try (var statement = db.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=3000");
                    statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA synchronous=FULL");
                }
                Migrations.apply(db, this.file, migrations == null ? Migrations.defaults() : List.copyOf(migrations));
                ready.complete(null);
            } catch (Exception | LinkageError failure) {
                release(); ready.completeExceptionally(failure instanceof StorageException ? failure
                        : new StorageException("Local storage could not start. Data was not replaced; check access, disk space and schema compatibility."));
            }
        });
    }
    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
    public Path databasePath() { return file; }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> CompletionStage<T> submit(Work<T> action) {
        var result = new CompletableFuture<T>();
        if (closed.get()) { result.completeExceptionally(new StorageException("Local storage is closed.")); return result; }
        try {
            worker.execute(() -> {
                try { ready.join(); result.complete(action.run()); }
                catch (Exception failure) {
                    Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                    result.completeExceptionally(cause instanceof StorageException ? cause
                            : new StorageException("Local storage operation failed. Your editor changes are still available. Check disk space and permissions."));
                }
            });
        } catch (RejectedExecutionException busy) { result.completeExceptionally(new StorageException("Local storage is busy or closed. Try again.")); }
        return result.minimalCompletionStage();
    }
    private <T> T transaction(Work<T> action) throws Exception {
        db.setAutoCommit(false);
        try { T result = action.run(); db.commit(); return result; }
        catch (Exception failure) { db.rollback(); throw failure; }
        finally { db.setAutoCommit(true); }
    }
    @Override public CompletionStage<List<LibraryItem>> list() { return submit(() -> {
        List<LibraryItem> items = new ArrayList<>();
        try (var query = db.createStatement(); var rows = query.executeQuery("SELECT * FROM items ORDER BY position,id")) {
            while (rows.next()) items.add(item(rows));
        } return List.copyOf(items);
    }); }
    private LibraryItem get(UUID id) throws SQLException {
        try (var query = db.prepareStatement("SELECT * FROM items WHERE id=?")) {
            query.setString(1, id.toString()); try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new StorageException("The selected item no longer exists."); return item(rows);
            }
        }
    }
    private static LibraryItem item(ResultSet row) throws SQLException {
        String parent = row.getString("parent_id");
        return new LibraryItem(UUID.fromString(row.getString("id")), LibraryItem.Kind.valueOf(row.getString("kind")),
                parent == null ? null : UUID.fromString(parent), row.getString("name"), row.getInt("position"), row.getLong("version"), Instant.parse(row.getString("modified_at")));
    }
    @Override public CompletionStage<LibraryItem> create(LibraryItem.Kind kind, UUID parent, String name) {
        return create(kind, parent, name, RequestDraft.empty());
    }
    @Override public CompletionStage<SavedRequest> createRequest(UUID parent, String name, RequestDraft draft) {
        return create(LibraryItem.Kind.REQUEST, parent, name, draft).thenApply(item -> new SavedRequest(item, draft));
    }
    private CompletionStage<LibraryItem> create(LibraryItem.Kind kind, UUID parent, String name, RequestDraft initial) {
        return submit(() -> {
            // Encoding/DPAPI happens outside the write transaction.
            String payload = kind == LibraryItem.Kind.REQUEST ? codec.encode(initial) : null;
            return transaction(() -> {
                LibraryItem ancestor = parent == null ? null : get(parent);
                boolean valid = switch (kind) {
                    case WORKSPACE -> ancestor == null;
                    case COLLECTION -> ancestor != null && ancestor.kind() == LibraryItem.Kind.WORKSPACE;
                    case FOLDER -> ancestor != null && ancestor.kind() == LibraryItem.Kind.COLLECTION;
                    case REQUEST -> ancestor != null && (ancestor.kind() == LibraryItem.Kind.COLLECTION || ancestor.kind() == LibraryItem.Kind.FOLDER);
                };
                if (!valid) throw new StorageException("Choose a valid parent. Folders may only be one level deep inside a collection.");
                var item = new LibraryItem(UUID.randomUUID(), kind, parent, name, siblings(parent).size(), 0, Instant.now());
                try (var insert = db.prepareStatement("INSERT INTO items VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, item.id().toString()); insert.setString(2, kind.name()); insert.setString(3, parent == null ? null : parent.toString());
                    insert.setString(4, item.name()); insert.setInt(5, item.position()); insert.setLong(6, 0); insert.setString(7, item.modifiedAt().toString()); insert.executeUpdate();
                }
                switch (kind) {
                    case WORKSPACE -> execute("INSERT INTO workspaces VALUES(?)", item.id().toString());
                    case COLLECTION -> execute("INSERT INTO collections VALUES(?,?)", item.id().toString(), parent.toString());
                    case FOLDER -> execute("INSERT INTO folders VALUES(?,?)", item.id().toString(), parent.toString());
                    case REQUEST -> execute("INSERT INTO requests VALUES(?,?,?,?)", item.id().toString(),
                            (ancestor.kind() == LibraryItem.Kind.FOLDER ? ancestor.parentId() : ancestor.id()).toString(),
                            ancestor.kind() == LibraryItem.Kind.FOLDER ? parent.toString() : null, payload);
                }
                return item;
            });
        });
    }
    private int execute(String sql, String... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]); return statement.executeUpdate();
        }
    }
    @Override public CompletionStage<LibraryItem> rename(UUID id, String name) { return submit(() -> transaction(() -> {
        LibraryItem previous = get(id);
        LibraryItem changed = new LibraryItem(id, previous.kind(), previous.parentId(), name, previous.position(), previous.version() + 1, Instant.now());
        execute("UPDATE items SET name=?,version=version+1,modified_at=? WHERE id=?", changed.name(), changed.modifiedAt().toString(), id.toString()); return changed;
    })); }
    private List<UUID> siblings(UUID parent) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (var query = db.prepareStatement("SELECT id FROM items WHERE parent_id IS ? ORDER BY position,id")) {
            query.setString(1, parent == null ? null : parent.toString());
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(UUID.fromString(rows.getString(1))); }
        } return result;
    }
    private void positions(List<UUID> ordered) throws SQLException {
        try (var update = db.prepareStatement("UPDATE items SET position=?,modified_at=? WHERE id=?")) {
            for (int i = 0; i < ordered.size(); i++) {
                update.setInt(1, i); update.setString(2, Instant.now().toString()); update.setString(3, ordered.get(i).toString()); update.addBatch();
            } update.executeBatch();
        }
    }
    @Override public CompletionStage<Void> reorder(UUID id, int position) { return submit(() -> transaction(() -> {
        var item = get(id); var ordered = siblings(item.parentId());
        if (position < 0 || position >= ordered.size()) throw new StorageException("Invalid item position.");
        ordered.remove(id); ordered.add(position, id); positions(ordered); return null;
    })); }
    @Override public CompletionStage<Void> delete(UUID id) { return submit(() -> transaction(() -> {
        var item = get(id); execute("DELETE FROM items WHERE id=?", id.toString()); positions(siblings(item.parentId())); return null;
    })); }
    private SavedRequest readRequest(UUID id) throws Exception {
        var item = get(id);
        if (item.kind() != LibraryItem.Kind.REQUEST) throw new StorageException("Select a saved request.");
        try (var query = db.prepareStatement("SELECT payload_json FROM requests WHERE id=?")) {
            query.setString(1, id.toString()); try (var row = query.executeQuery()) {
                if (!row.next()) throw new StorageException("Request data is missing."); return new SavedRequest(item, codec.decode(row.getString(1)));
            }
        }
    }
    @Override public CompletionStage<SavedRequest> load(UUID id) { return submit(() -> readRequest(id)); }
    @Override public CompletionStage<SavedRequest> save(UUID id, long expectedVersion, RequestDraft draft) { return submit(() -> {
        String payload = codec.encode(draft);
        return transaction(() -> {
            var item = get(id);
            if (item.kind() != LibraryItem.Kind.REQUEST || item.version() != expectedVersion)
                throw new StorageException("This request changed since it was opened. Reopen it or save your edits as a new request.");
            execute("UPDATE requests SET payload_json=? WHERE id=?", payload, id.toString());
            execute("UPDATE items SET version=version+1,modified_at=? WHERE id=?", Instant.now().toString(), id.toString());
            return new SavedRequest(get(id), draft);
        });
    }); }
    @Override public CompletionStage<Void> putDraft(RecoveryDraft draft) { return submit(() -> {
        String payload = codec.encode(draft.draft());
        execute("INSERT INTO drafts VALUES(?,?,?,?,?,?,?) ON CONFLICT(tab_id) DO UPDATE SET request_id=excluded.request_id,workspace_id=excluded.workspace_id,title=excluded.title,payload_json=excluded.payload_json,session_id=excluded.session_id,modified_at=excluded.modified_at",
                draft.tabId().toString(), draft.requestId() == null ? null : draft.requestId().toString(), draft.workspaceId().toString(), draft.title(), payload, session.toString(), draft.modifiedAt().toString()); return null;
    }); }
    @Override public CompletionStage<Void> deleteDraft(UUID id) { return submit(() -> { execute("DELETE FROM drafts WHERE tab_id=?", id.toString()); return null; }); }
    private RecoveryDraft draft(ResultSet row) throws Exception {
        String request = row.getString("request_id");
        return new RecoveryDraft(UUID.fromString(row.getString("tab_id")), request == null ? null : UUID.fromString(request),
                UUID.fromString(row.getString("workspace_id")), row.getString("title"), codec.decode(row.getString("payload_json")), Instant.parse(row.getString("modified_at")));
    }
    @Override public CompletionStage<List<RecoveryDraft>> recoverableDrafts() { return submit(() -> {
        List<RecoveryDraft> result = new ArrayList<>();
        try (var query = db.prepareStatement("SELECT * FROM drafts WHERE session_id<>? ORDER BY modified_at,tab_id")) {
            query.setString(1, session.toString()); try (var rows = query.executeQuery()) { while (rows.next()) result.add(draft(rows)); }
        } return List.copyOf(result);
    }); }
    @Override public CompletionStage<RecoveryDraft> claimDraft(UUID id) { return submit(() -> transaction(() -> {
        RecoveryDraft draft;
        try (var query = db.prepareStatement("SELECT * FROM drafts WHERE tab_id=?")) {
            query.setString(1, id.toString()); try (var row = query.executeQuery()) {
                if (!row.next()) throw new StorageException("This recovery draft is no longer available."); draft = draft(row);
            }
        }
        execute("UPDATE drafts SET session_id=? WHERE tab_id=?", session.toString(), id.toString()); return draft;
    })); }
    @Override public CompletionStage<Void> finishSession() { return submit(() -> {
        execute("DELETE FROM drafts WHERE session_id=?", session.toString()); return null;
    }); }
    private void release() {
        try { if (db != null) db.close(); } catch (SQLException ignored) { }
        try { if (lock != null) lock.release(); } catch (java.io.IOException ignored) { }
        try { if (lockFile != null) lockFile.close(); } catch (java.io.IOException ignored) { }
    }
    private final CompletableFuture<Void> shutdown = new CompletableFuture<>();
    public CompletionStage<Void> shutdown() {
        if (!closed.compareAndSet(false, true)) return shutdown.minimalCompletionStage();
        // Shutdown drains accepted tasks, then releases handles, without blocking JavaFX.
        CompletableFuture.runAsync(() -> {
            worker.shutdown();
            try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) worker.shutdownNow(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
            release();
            shutdown.complete(null);
        });
        return shutdown.minimalCompletionStage();
    }
    @Override public void close() { shutdown(); }
}
