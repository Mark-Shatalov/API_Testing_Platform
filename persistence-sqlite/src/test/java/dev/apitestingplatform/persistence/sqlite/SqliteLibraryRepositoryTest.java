package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class SqliteLibraryRepositoryTest {
    @TempDir Path directory;
    private final List<SqliteLibraryRepository> repositories = new ArrayList<>();
    private final PayloadProtector protector = encryptedTestProtector();
    private Path file() { return directory.resolve("platform.sqlite"); }
    private SqliteLibraryRepository open() throws Exception {
        var repository = new SqliteLibraryRepository(file(), protector); repositories.add(repository); await(repository.ready()); return repository;
    }
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    @AfterEach void shutdown() throws Exception { for (var repository : repositories) await(repository.shutdown()); }
    private LibraryItem workspace(SqliteLibraryRepository repository) throws Exception { return await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Workspace")); }
    private LibraryItem collection(SqliteLibraryRepository repository) throws Exception { return await(repository.create(LibraryItem.Kind.COLLECTION, workspace(repository).id(), "Collection")); }
    private static RequestDraft draft(String body) {
        return new RequestDraft(HttpMethod.PATCH, "http://localhost/test?x=1", List.of(new RequestField("q", "é", false)),
                List.of(new RequestField("Authorization", "Bearer private-secret-marker")), List.of(new RequestField("sid", "private-cookie")),
                RequestBody.Type.JSON, body, List.of(new RequestField("form", "saved")),
                List.of(new RequestDraft.Part("upload", "C:\\data\\missing.bin", true, "application/octet-stream", false)),
                "0.125", RedirectPolicy.NEVER, HttpProtocol.HTTP_1_1);
    }

    @Test void createsFullSchemaWalAndStableOrderedHierarchyAndRejectsNestedFolders() throws Exception {
        var repository = open(); var collection = collection(repository);
        var folder = await(repository.create(LibraryItem.Kind.FOLDER, collection.id(), "Folder"));
        var request = await(repository.createRequest(folder.id(), "Request", draft("{broken}")));
        assertThat(await(repository.load(request.item().id()))).isEqualTo(request);
        assertThatThrownBy(() -> await(repository.create(LibraryItem.Kind.FOLDER, folder.id(), "Nested"))).hasCauseInstanceOf(StorageException.class);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var query = db.createStatement()) {
            try (var result = query.executeQuery("PRAGMA journal_mode")) { assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("wal"); }
            try (var result = query.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                var tables = new ArrayList<String>(); while (result.next()) tables.add(result.getString(1));
                assertThat(tables).contains("schema_version", "workspaces", "collections", "folders", "requests", "environments", "variables", "assertions", "runs", "request_results", "assertion_results", "drafts");
            }
        }
        assertThat(await(repository.list())).hasSize(4);
    }

    @Test void savesEverySettingAndAllBodyDraftsAcrossRestartWithoutPlaintextOnDisk() throws Exception {
        var repository = open(); var parent = collection(repository);
        var created = await(repository.createRequest(parent.id(), "Unicode café", draft("{\"secret\":\"private-body-marker\"}")));
        var changed = await(repository.save(created.item().id(), created.item().version(), draft("edited incomplete JSON")));
        assertThat(changed.item().version()).isEqualTo(1);
        await(repository.shutdown());
        var reopened = open(); assertThat(await(reopened.load(created.item().id()))).isEqualTo(changed);
        await(reopened.shutdown());
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.ISO_8859_1))
                .doesNotContain("private-secret-marker", "private-cookie", "edited incomplete JSON", "private-body-marker");
    }

    @Test void renameReorderDeleteCompactPositionsAndCascadeWithoutOrphans() throws Exception {
        var repository = open(); var parent = collection(repository);
        var a = await(repository.createRequest(parent.id(), "A", RequestDraft.empty()));
        var b = await(repository.createRequest(parent.id(), "B", RequestDraft.empty()));
        var c = await(repository.createRequest(parent.id(), "C", RequestDraft.empty()));
        await(repository.reorder(c.item().id(), 0));
        assertThat(await(repository.list()).stream().filter(item -> parent.id().equals(item.parentId())))
                .extracting(LibraryItem::name).containsExactly("C", "A", "B");
        await(repository.rename(a.item().id(), "Renamed"));
        assertThat(await(repository.load(a.item().id())).item().name()).isEqualTo("Renamed");
        await(repository.delete(c.item().id()));
        assertThat(await(repository.list()).stream().filter(item -> parent.id().equals(item.parentId())))
                .extracting(LibraryItem::position).containsExactly(0, 1);
        await(repository.delete(parent.id()));
        assertThat(await(repository.list())).hasSize(1);
        assertThatThrownBy(() -> await(repository.load(b.item().id()))).hasCauseInstanceOf(StorageException.class);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var query = db.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM requests")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test void serializesConcurrentWritesAndUsesOptimisticRequestVersions() throws Exception {
        var repository = open(); var parent = collection(repository);
        List<CompletionStage<LibraryItem>> operations = new ArrayList<>();
        for (int i = 0; i < 40; i++) operations.add(repository.create(LibraryItem.Kind.FOLDER, parent.id(), "Folder " + i));
        for (var operation : operations) await(operation);
        assertThat(await(repository.list()).stream().filter(item -> parent.id().equals(item.parentId())))
                .extracting(LibraryItem::position).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 40).boxed().toList());
        var saved = await(repository.createRequest(parent.id(), "Request", RequestDraft.empty()));
        await(repository.save(saved.item().id(), 0, draft("first")));
        assertThatThrownBy(() -> await(repository.save(saved.item().id(), 0, draft("stale")))).hasCauseInstanceOf(StorageException.class);
        assertThat(await(repository.load(saved.item().id())).draft().bodyText()).isEqualTo("first");
    }

    @Test void crashRecoveryIsIdempotentAndNeverOverwritesSavedRequests() throws Exception {
        var repository = open(); var parent = collection(repository);
        var saved = await(repository.createRequest(parent.id(), "Request", draft("last saved")));
        var tab = new RecoveryDraft(UUID.randomUUID(), saved.item().id(), parent.parentId(), "Request", draft("unfinished edits"), Instant.now());
        await(repository.putDraft(tab)); await(repository.shutdown()); // Simulated crash: no finishSession.
        var restarted = open(); assertThat(await(restarted.recoverableDrafts())).containsExactly(tab);
        assertThat(await(restarted.claimDraft(tab.tabId()))).isEqualTo(tab);
        assertThat(await(restarted.claimDraft(tab.tabId()))).isEqualTo(tab);
        assertThat(await(restarted.recoverableDrafts())).isEmpty();
        assertThat(await(restarted.load(saved.item().id())).draft().bodyText()).isEqualTo("last saved");
        await(restarted.finishSession()); await(restarted.shutdown());
        assertThat(await(open().recoverableDrafts())).isEmpty();
    }

    @Test void failedDraftWritesAreSafeAndRetryableAndDoNotChangeSavedPayloads() throws Exception {
        var repository = open(); var parent = collection(repository);
        var saved = await(repository.createRequest(parent.id(), "Request", draft("saved")));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var query = db.createStatement()) {
            query.execute("CREATE TRIGGER reject_draft BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'private-error-marker'); END");
        }
        var snapshot = new RecoveryDraft(UUID.randomUUID(), saved.item().id(), parent.parentId(), "Request", draft("unsaved"), Instant.now());
        Throwable failedWrite = catchThrowable(() -> await(repository.putDraft(snapshot)));
        assertThat(failedWrite).hasCauseInstanceOf(StorageException.class).hasStackTraceContaining("editor changes");
        assertThat(failedWrite.getCause().getMessage()).doesNotContain("private-error-marker");
        assertThat(await(repository.load(saved.item().id())).draft().bodyText()).isEqualTo("saved");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var query = db.createStatement()) { query.execute("DROP TRIGGER reject_draft"); }
        await(repository.putDraft(snapshot)); await(repository.shutdown());
        assertThat(await(open().recoverableDrafts())).containsExactly(snapshot);
    }

    @Test void migrationSuccessCreatesConsistentBackupAndFailureRollsBackSchemaAndData() throws Exception {
        var repository = open(); var parent = collection(repository); await(repository.shutdown());
        List<Migration> migrations = new ArrayList<>(Migrations.defaults());
        migrations.add(new Migration(2, "CREATE TABLE extra(id TEXT); INSERT INTO extra VALUES('existing');", true));
        var upgraded = new SqliteLibraryRepository(file(), protector, migrations); repositories.add(upgraded); await(upgraded.ready()); await(upgraded.shutdown());
        try (var backups = Files.list(directory.resolve("migration-backups"))) {
            var files = backups.toList(); assertThat(files).hasSize(1);
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + files.getFirst()); var query = db.createStatement(); var row = query.executeQuery("SELECT count(*) FROM collections")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1);
            }
        }
        var failureMigrations = new ArrayList<>(migrations); failureMigrations.add(new Migration(3, "CREATE TABLE should_rollback(id TEXT); THIS IS INVALID SQL;", true));
        var failed = new SqliteLibraryRepository(file(), protector, failureMigrations); repositories.add(failed);
        assertThatThrownBy(() -> await(failed.ready())).hasCauseInstanceOf(StorageException.class); await(failed.shutdown());
        var restored = new SqliteLibraryRepository(file(), protector, migrations); repositories.add(restored); await(restored.ready());
        assertThat(await(restored.list())).extracting(LibraryItem::id).contains(parent.id());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var query = db.createStatement()) {
            try (var row = query.executeQuery("SELECT max(version) FROM schema_version")) { assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(2); }
            try (var row = query.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='should_rollback'")) { assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero(); }
        }
    }

    @Test void checksumDriftAndConcurrentApplicationInstancesAreRejected() throws Exception {
        var repository = open();
        var duplicate = new SqliteLibraryRepository(file(), protector); repositories.add(duplicate);
        assertThatThrownBy(() -> await(duplicate.ready())).hasCauseInstanceOf(StorageException.class); await(duplicate.shutdown());
        await(repository.shutdown());
        var changed = new SqliteLibraryRepository(file(), protector, List.of(new Migration(1, "CREATE TABLE wrong(id TEXT)", false))); repositories.add(changed);
        assertThatThrownBy(() -> await(changed.ready())).hasCauseInstanceOf(StorageException.class); await(changed.shutdown());
        await(open().ready());
    }

    private static PayloadProtector encryptedTestProtector() {
        try {
            var key = KeyGenerator.getInstance("AES").generateKey();
            return new PayloadProtector() {
                @Override public byte[] protect(byte[] plain) {
                    try {
                        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
                        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                        byte[] encrypted = cipher.doFinal(plain); return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
                @Override public byte[] unprotect(byte[] encrypted) {
                    try {
                        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
                        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
            };
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
