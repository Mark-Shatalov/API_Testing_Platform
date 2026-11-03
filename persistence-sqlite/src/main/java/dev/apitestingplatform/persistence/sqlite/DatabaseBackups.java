package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.operations.ManagedFiles;
import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.run.HistoryRecord;
import dev.apitestingplatform.domain.storage.RequestDraft;
import dev.apitestingplatform.domain.variables.VariableSet;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Worker/startup only, with the repository's data-directory lock held. Restores commit only before opening SQLite. */
final class DatabaseBackups {
    private static final long LIMIT = 1024L * 1024 * 1024;
    private static final List<String> SUFFIXES = List.of("", "-wal", "-shm");
    private final Path database, directory, pending, manifest, journal;
    private final ProtectedDraftCodec codec;
    DatabaseBackups(Path database, ProtectedDraftCodec codec) {
        this.database = database.toAbsolutePath().normalize(); this.directory = this.database.getParent(); this.codec = codec;
        pending = directory.resolve("restore-pending.sqlite"); manifest = directory.resolve("restore-pending.properties"); journal = directory.resolve("restore-in-progress.properties");
    }
    Path backup(Connection connection, Path destination) throws Exception {
        Path target = ManagedFiles.output(database, destination, true); Path temporary = Files.createTempFile(target.getParent(), ".api-backup-", ".sqlite");
        try {
            Files.delete(temporary);
            try (var sql = connection.createStatement()) { sql.execute("VACUUM INTO '" + temporary.toString().replace("'", "''") + "'"); }
            validate(temporary); force(temporary); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return target;
        } finally { Files.deleteIfExists(temporary); }
    }
    void stage(Path source) throws Exception {
        Path input = source.toAbsolutePath().normalize();
        if (SUFFIXES.stream().anyMatch(suffix -> input.equals(Path.of(database + suffix))) || input.equals(database.resolveSibling(database.getFileName() + ".lock")) || Files.exists(database) && Files.isSameFile(input, database)) throw new StorageException("Choose a completed backup file, not the active database or its sidecars.");
        if (Files.exists(journal)) throw new StorageException("An interrupted restore must be recovered before staging another backup.");
        Path temporary = Files.createTempFile(directory, ".restore-stage-", ".sqlite");
        try {
            copyBounded(input, temporary); int version = validate(temporary); String hash = hash(temporary); force(temporary);
            Files.move(temporary, pending, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Properties properties = new Properties(); properties.setProperty("format", "1"); properties.setProperty("state", "STAGED"); properties.setProperty("sha256", hash); properties.setProperty("schemaVersion", Integer.toString(version)); write(manifest, properties);
        } finally { Files.deleteIfExists(temporary); }
    }
    boolean pending() throws IOException { return Files.exists(manifest) && "STAGED".equals(read(manifest).getProperty("state")); }
    void cancel() throws Exception {
        if (Files.exists(journal)) throw new StorageException("Recovery is incomplete. Keep the automatic recovery backup and restart to retry recovery.");
        Files.deleteIfExists(manifest); Files.deleteIfExists(pending);
    }
    String applyPending() throws Exception {
        if (Files.exists(manifest) && "APPLIED".equals(read(manifest).getProperty("state"))) {
            try { Files.deleteIfExists(journal); cleanup(); }
            catch (IOException incomplete) { return "The database restore was applied; pending-file cleanup will retry on next launch. Automatic pre-restore copies are in recovery-backups."; }
            return "The database restore was applied. Automatic pre-restore copies are in recovery-backups.";
        }
        if (Files.exists(journal)) {
            try { rollback(safetyFromJournal()); Files.delete(journal); }
            catch (Exception failure) { throw new StorageException("An interrupted restore could not be recovered. Files and automatic recovery copies were kept. Review the data directory before retrying."); }
        }
        if (!Files.exists(manifest)) return "";
        boolean committed = false; Path safety = null;
        try {
            Properties request = read(manifest);
            if (!"1".equals(request.getProperty("format")) || !"STAGED".equals(request.getProperty("state")) || !hash(pending).equals(request.getProperty("sha256"))) throw new IOException();
            validate(pending); safety = safetyCopy();
            Properties activation = new Properties(); activation.setProperty("format", "1"); activation.setProperty("backup", safety.getFileName().toString()); write(journal, activation);
            // Old WAL/SHM must never be replayed into the restored standalone snapshot.
            Files.deleteIfExists(Path.of(database + "-wal")); Files.deleteIfExists(Path.of(database + "-shm"));
            replaceFrom(pending, database);
            request.setProperty("state", "APPLIED"); write(manifest, request); committed = true;
            Files.deleteIfExists(journal); cleanup();
            return "Database restored. The previous database and sidecars were preserved in recovery-backups.";
        } catch (Exception failure) {
            if (committed) return "Database restored. Pending-file cleanup is incomplete; use Cancel pending restore in Settings. Pre-restore data was preserved.";
            if (Files.exists(journal)) {
                try { rollback(safety == null ? safetyFromJournal() : safety); Files.delete(journal); }
                catch (Exception rollbackFailure) { throw new StorageException("Restore failed and automatic rollback could not finish. Current files and recovery backups were kept; storage remains closed for recovery."); }
            }
            return "Pending restore was not applied; the current database was kept or recovered. Review or cancel the pending restore in Settings.";
        }
    }
    private void cleanup() throws IOException { Files.deleteIfExists(pending); Files.deleteIfExists(manifest); }
    private Path safetyCopy() throws Exception {
        Path safety = directory.resolve("recovery-backups").resolve("before-restore-" + UUID.randomUUID()); Files.createDirectories(safety); Properties metadata = new Properties();
        for (String suffix : SUFFIXES) {
            Path source = Path.of(database + suffix); String key = suffix.isEmpty() ? "database" : suffix.substring(1); boolean present = Files.exists(source);
            metadata.setProperty(key + ".present", Boolean.toString(present));
            if (present) { Path target = safety.resolve("database.sqlite" + suffix); copyBounded(source, target); force(target); metadata.setProperty(key + ".sha256", hash(target)); }
        }
        write(safety.resolve("backup.properties"), metadata); return safety;
    }
    private Path safetyFromJournal() throws IOException {
        String folder = read(journal).getProperty("backup", "");
        if (!folder.matches("before-restore-[a-f0-9-]{36}")) throw new IOException();
        return directory.resolve("recovery-backups").resolve(folder);
    }
    private void rollback(Path safety) throws Exception {
        Properties metadata = read(safety.resolve("backup.properties"));
        for (String suffix : SUFFIXES) { String key = suffix.isEmpty() ? "database" : suffix.substring(1);
            if (!Set.of("true", "false").contains(metadata.getProperty(key + ".present"))) throw new IOException();
            if (Boolean.parseBoolean(metadata.getProperty(key + ".present")) && !hash(safety.resolve("database.sqlite" + suffix)).equals(metadata.getProperty(key + ".sha256"))) throw new IOException();
        }
        for (String suffix : SUFFIXES) { String key = suffix.isEmpty() ? "database" : suffix.substring(1); Path target = Path.of(database + suffix);
            if (Boolean.parseBoolean(metadata.getProperty(key + ".present"))) replaceFrom(safety.resolve("database.sqlite" + suffix), target); else Files.deleteIfExists(target);
        }
    }
    private void replaceFrom(Path source, Path target) throws Exception {
        Path temporary = Files.createTempFile(directory, ".restore-replace-", ".sqlite");
        try { copyBounded(source, temporary); force(temporary); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private int validate(Path source) throws Exception {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > LIMIT) throw new StorageException("Choose a completed SQLite backup of at most 1 GiB.");
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(1000);
        try (Connection candidate = config.createConnection("jdbc:sqlite:" + source.toAbsolutePath())) {
            try (var query = candidate.createStatement()) { query.execute("PRAGMA trusted_schema=OFF"); }
            List<Migration> available = Migrations.defaults(); int version = 0;
            try (var query = candidate.createStatement(); var rows = query.executeQuery("SELECT version,checksum FROM schema_version ORDER BY version")) {
                while (rows.next()) { if (rows.getInt(1) != ++version || version > available.size() || !rows.getString(2).equals(available.get(version - 1).checksum())) throw new StorageException("Backup schema is incompatible with this application."); }
            }
            if (version == 0) throw new StorageException("Backup schema is missing.");
            try (Connection expected = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                Migrations.apply(expected, source, available.subList(0, version).stream().map(migration -> new Migration(migration.version(), migration.sql(), false)).toList());
                if (!schema(candidate).equals(schema(expected))) throw new StorageException("Backup schema contains unsupported changes, triggers or views.");
            }
            try (var query = candidate.createStatement(); var rows = query.executeQuery("PRAGMA integrity_check(1)")) { if (!rows.next() || !"ok".equals(rows.getString(1))) throw new StorageException("Backup integrity check failed."); }
            try (var query = candidate.createStatement(); var rows = query.executeQuery("PRAGMA foreign_key_check")) { if (rows.next()) throw new StorageException("Backup references are invalid."); }
            checkPayloads(candidate, "requests", "payload_json", RequestDraft.class); checkPayloads(candidate, "drafts", "payload_json", RequestDraft.class);
            checkPayloads(candidate, "variables", "value_json", String.class);
            if (version >= 2) checkPayloads(candidate, "workspace_variable_settings", "variables_json", VariableSet.class);
            if (version >= 3) checkPayloads(candidate, "execution_history", "payload_json", HistoryRecord.class);
            return version;
        } catch (StorageException safe) { throw safe; }
        catch (Exception invalid) { throw new StorageException("Backup could not be verified for this Windows profile. Check its format, integrity and ownership; current data was kept."); }
    }
    private void checkPayloads(Connection source, String table, String column, Class<?> type) throws Exception {
        // Reject huge TEXT/BLOB values in SQLite before materializing a Java String.
        try (var query = source.createStatement(); var rows = query.executeQuery("SELECT 1 FROM " + table + " WHERE length(" + column + ") > 67108864 OR typeof(" + column + ") != 'text' LIMIT 1")) {
            if (rows.next()) throw new StorageException("Backup contains oversized or invalid protected data.");
        }
        try (var query = source.createStatement(); var rows = query.executeQuery("SELECT " + column + " FROM " + table)) { while (rows.next()) codec.decodeValue(rows.getString(1), type); }
    }
    private static List<String> schema(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type,name")) {
            while (rows.next()) result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3) + "|" + Objects.toString(rows.getString(4), "").replaceAll("\\s+", " ").strip());
        }
        return result;
    }
    private static void copyBounded(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > LIMIT) throw new IOException("Backup file exceeds the 1 GiB limit or is not a regular file.");
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] bytes = new byte[64 * 1024]; long total = 0; int count;
            while ((count = input.read(bytes)) != -1) { if ((total += count) > LIMIT) throw new IOException("Backup exceeds its size limit."); output.write(bytes, 0, count); }
        }
    }
    private static String hash(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[64 * 1024]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Properties read(Path path) throws IOException { try (var input = Files.newInputStream(path)) { byte[] bytes = input.readNBytes(8193); if (bytes.length > 8192) throw new IOException(); Properties value = new Properties(); value.load(new ByteArrayInputStream(bytes)); return value; } }
    private static void write(Path path, Properties value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".backup-metadata-", ".tmp");
        try { try (var output = Files.newOutputStream(temporary)) { value.store(output, "API Testing Platform restore metadata"); } force(temporary); Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void force(Path path) throws IOException { try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); } }
}
