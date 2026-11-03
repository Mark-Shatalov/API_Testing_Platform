package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class DatabaseBackupsTest {
    @TempDir Path directory;
    final PayloadProtector protector = SqliteLibraryRepositoryTest.encryptedTestProtector();
    final List<SqliteLibraryRepository> repositories = new ArrayList<>();
    Path file() { return directory.resolve("platform.sqlite"); }
    Path backup() { return directory.resolve("snapshot.sqlite"); }
    SqliteLibraryRepository create() { var repository = new SqliteLibraryRepository(file(), protector); repositories.add(repository); return repository; }
    SqliteLibraryRepository open() throws Exception { var repository = create(); await(repository.ready()); return repository; }
    static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    @AfterEach void close() throws Exception { for (var repository : repositories) await(repository.shutdown()); }
    LibraryItem seed(SqliteLibraryRepository repository) throws Exception {
        var workspace = await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Saved workspace"));
        var collection = await(repository.create(LibraryItem.Kind.COLLECTION, workspace.id(), "Collection"));
        var request = await(repository.createRequest(collection.id(), "Saved request", RequestDraft.empty()));
        await(repository.createEnvironment(workspace.id(), "Private", List.of(new dev.apitestingplatform.domain.variables.Variable("token", "secret-backup-marker", true))));
        return request.item();
    }
    @Test void consistentEncryptedSnapshotRestoresOnlyAtRestartAndKeepsPreviousDatabase() throws Exception {
        var repository = open(); var request = seed(repository);
        await(repository.backupDatabase(backup()));
        assertThat(new String(Files.readAllBytes(backup()), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("secret-backup-marker");
        await(repository.rename(request.id(), "Edited after backup"));
        await(repository.stageDatabaseRestore(backup())); assertThat(await(repository.pendingDatabaseRestore())).isTrue();
        assertThat(await(repository.load(request.id())).item().name()).isEqualTo("Edited after backup");
        await(repository.shutdown());
        var restored = open(); assertThat(await(restored.load(request.id())).item().name()).isEqualTo("Saved request");
        assertThat(restored.startupNotice()).contains("restored"); assertThat(await(restored.pendingDatabaseRestore())).isFalse();
        try (var copies = Files.list(directory.resolve("recovery-backups"))) {
            Path previous = copies.findFirst().orElseThrow().resolve("database.sqlite");
            assertThat(Files.size(previous)).isPositive();
        }
    }
    @Test void cancelPendingRestoreKeepsNewerDataAndSourceBackup() throws Exception {
        var repository = open(); var request = seed(repository); await(repository.backupDatabase(backup()));
        await(repository.stageDatabaseRestore(backup())); await(repository.cancelDatabaseRestore());
        await(repository.rename(request.id(), "Keep newer")); await(repository.shutdown());
        assertThat(await(open().load(request.id())).item().name()).isEqualTo("Keep newer"); assertThat(backup()).exists();
    }
    @Test void modifiedStagedCopyIsRejectedAtStartupAndOriginalIsKept() throws Exception {
        var repository = open(); var request = seed(repository); await(repository.backupDatabase(backup())); await(repository.stageDatabaseRestore(backup()));
        await(repository.rename(request.id(), "Keep current")); await(repository.shutdown());
        Files.writeString(directory.resolve("restore-pending.sqlite"), "damaged");
        var restarted = open(); assertThat(await(restarted.load(request.id())).item().name()).isEqualTo("Keep current");
        assertThat(restarted.startupNotice()).contains("not applied"); assertThat(await(restarted.pendingDatabaseRestore())).isTrue();
    }
    @Test void invalidOrForeignProfileCopiesCannotReplaceAnAlreadyStagedValidBackup() throws Exception {
        var repository = open(); seed(repository); await(repository.backupDatabase(backup())); await(repository.stageDatabaseRestore(backup()));
        byte[] staged = Files.readAllBytes(directory.resolve("restore-pending.sqlite"));
        Path invalid = directory.resolve("invalid.sqlite"); Files.writeString(invalid, "not SQLite");
        assertThatThrownBy(() -> await(repository.stageDatabaseRestore(invalid))).hasCauseInstanceOf(StorageException.class);
        var foreign = new SqliteLibraryRepository(directory.resolve("foreign.sqlite"), SqliteLibraryRepositoryTest.encryptedTestProtector()); repositories.add(foreign); await(foreign.ready()); seed(foreign);
        Path foreignBackup = directory.resolve("foreign-backup.sqlite"); await(foreign.backupDatabase(foreignBackup));
        assertThatThrownBy(() -> await(repository.stageDatabaseRestore(foreignBackup))).hasCauseInstanceOf(StorageException.class);
        assertThat(Files.readAllBytes(directory.resolve("restore-pending.sqlite"))).isEqualTo(staged);
    }
    @Test void schemaChangesAndBrokenReferencesAreRejectedWithoutChangingLiveData() throws Exception {
        var repository = open(); var request = seed(repository); await(repository.backupDatabase(backup()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup()); var sql = connection.createStatement()) { sql.execute("CREATE TRIGGER extra AFTER INSERT ON items BEGIN SELECT 1; END"); }
        assertThatThrownBy(() -> await(repository.stageDatabaseRestore(backup()))).hasCauseInstanceOf(StorageException.class);
        await(repository.backupDatabase(backup()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup()); var sql = connection.createStatement()) { sql.execute("UPDATE requests SET collection_id='missing'"); }
        assertThatThrownBy(() -> await(repository.stageDatabaseRestore(backup()))).hasCauseInstanceOf(StorageException.class);
        assertThat(await(repository.load(request.id())).item().name()).isEqualTo("Saved request");
    }
    @Test void databaseCorruptionStillAllowsStagingVerifiedRestoreWhileHoldingInstanceLock() throws Exception {
        var repository = open(); var request = seed(repository); await(repository.backupDatabase(backup())); await(repository.shutdown());
        Files.writeString(file(), "corrupted original");
        var failed = create(); assertThatThrownBy(() -> await(failed.ready())).hasCauseInstanceOf(StorageException.class);
        var duplicate = create(); assertThatThrownBy(() -> await(duplicate.ready())).hasCauseInstanceOf(StorageException.class);
        assertThatThrownBy(() -> await(duplicate.stageDatabaseRestore(backup()))).hasCauseInstanceOf(StorageException.class);
        await(duplicate.shutdown()); await(failed.stageDatabaseRestore(backup())); await(failed.shutdown());
        assertThat(await(open().load(request.id())).item().name()).isEqualTo("Saved request");
        try (var copies = Files.list(directory.resolve("recovery-backups"))) { assertThat(Files.readString(copies.findFirst().orElseThrow().resolve("database.sqlite"))).isEqualTo("corrupted original"); }
    }
    @Test void backupAndExportTargetsCannotReplaceManagedFilesAndFailedOutputKeepsOldFile() throws Exception {
        var repository = open(); seed(repository);
        for (String name : List.of("platform.sqlite", "platform.sqlite-wal", "platform.sqlite.lock", "operations.properties", "restore-pending.sqlite", "logs/test.sqlite", "recovery-backups/test.sqlite"))
            assertThatThrownBy(() -> await(repository.backupDatabase(directory.resolve(name)))).hasCauseInstanceOf(StorageException.class);
        Path destination = directory.resolve("existing-directory"); Files.createDirectory(destination); Files.writeString(destination.resolve("keep.txt"), "keep");
        assertThatThrownBy(() -> await(repository.backupDatabase(destination))).hasCauseInstanceOf(StorageException.class);
        assertThat(Files.readString(destination.resolve("keep.txt"))).isEqualTo("keep"); assertThat(await(repository.list())).hasSize(3);
    }
    @Test void interruptedActivationRollsBackSafetyCopyBeforeRetryAndAppliedReceiptCannotReplayOldBackup() throws Exception {
        var repository = open(); var request = seed(repository); await(repository.backupDatabase(backup()));
        await(repository.rename(request.id(), "Current before interruption")); await(repository.shutdown());
        Path safety = directory.resolve("recovery-backups/before-restore-" + UUID.randomUUID()); Files.createDirectories(safety);
        Files.copy(file(), safety.resolve("database.sqlite")); Properties metadata = new Properties();
        for (String key : List.of("database", "wal", "shm")) metadata.setProperty(key + ".present", Boolean.toString(key.equals("database")));
        metadata.setProperty("database.sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file()))));
        write(safety.resolve("backup.properties"), metadata); Properties journal = new Properties(); journal.setProperty("backup", safety.getFileName().toString()); write(directory.resolve("restore-in-progress.properties"), journal);
        Files.writeString(file(), "partial replacement");
        var recovered = open(); assertThat(await(recovered.load(request.id())).item().name()).isEqualTo("Current before interruption"); await(recovered.shutdown());
        // Committed replacement with cleanup interrupted: a stale pending copy must never be replayed.
        Files.copy(backup(), directory.resolve("restore-pending.sqlite")); Properties applied = new Properties(); applied.setProperty("state", "APPLIED"); write(directory.resolve("restore-pending.properties"), applied);
        assertThat(await(open().load(request.id())).item().name()).isEqualTo("Current before interruption"); assertThat(directory.resolve("restore-pending.sqlite")).doesNotExist();
    }
    @Test void externalSqliteWriteLockReportsBusyAndRetrySucceedsWithOriginalDataIntact() throws Exception {
        var repository = open(); var request = seed(repository);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var sql = connection.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            Throwable failure = catchThrowable(() -> await(repository.rename(request.id(), "Attempt while locked")));
            assertThat(failure.getCause()).isInstanceOf(StorageException.class); assertThat(((StorageException) failure.getCause()).reason()).isEqualTo(StorageException.Reason.BUSY);
            assertThat(await(repository.load(request.id())).item().name()).isEqualTo("Saved request"); sql.execute("ROLLBACK");
        }
        assertThat(await(repository.rename(request.id(), "Retried"))).extracting(LibraryItem::name).isEqualTo("Retried");
    }
    static void write(Path path, Properties value) throws Exception { try (var output = Files.newOutputStream(path)) { value.store(output, "test interruption"); } }
    @Test void sqliteFailureClassificationNeverExposesDriverSqlOrSecrets() {
        Map<Integer, StorageException.Reason> cases = Map.of(5, StorageException.Reason.BUSY, 6, StorageException.Reason.BUSY, 13, StorageException.Reason.FULL, 8, StorageException.Reason.ACCESS, 14, StorageException.Reason.ACCESS, 11, StorageException.Reason.CORRUPT, 26, StorageException.Reason.CORRUPT);
        cases.forEach((code, reason) -> { var failure = SqliteLibraryRepository.storageFailure(new SQLException("SQL with secret-marker", "", code)); assertThat(failure.reason()).isEqualTo(reason); assertThat(failure.getMessage()).doesNotContain("secret-marker", "SQL with"); });
        assertThat(SqliteLibraryRepository.storageFailure(new SQLException("secret-marker", "", 19)).getMessage()).doesNotContain("secret-marker");
    }
}
