package dev.apitestingplatform.persistence.sqlite;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Migrations {
    static List<Migration> defaults() throws java.io.IOException {
        try (var source = Migrations.class.getResourceAsStream("V001__local_storage.sql")) {
            if (source == null) throw new java.io.IOException("Missing schema resource");
            return List.of(new Migration(1, new String(source.readAllBytes(), StandardCharsets.UTF_8), false));
        }
    }

    static void apply(Connection db, Path file, List<Migration> migrations) throws Exception {
        try (var statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)");
        }
        Map<Integer, String> applied = new TreeMap<>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT version,checksum FROM schema_version ORDER BY version")) {
            while (rows.next()) applied.put(rows.getInt(1), rows.getString(2));
        }
        for (int i = 0; i < migrations.size(); i++) {
            Migration migration = migrations.get(i);
            if (migration.version() != i + 1) throw new SQLException("Migrations must be contiguous and ordered");
            if (applied.containsKey(migration.version()) && !applied.get(migration.version()).equals(migration.checksum()))
                throw new SQLException("Migration checksum mismatch");
        }
        if (applied.size() > migrations.size() || !applied.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, applied.size()).boxed().toList())))
            throw new SQLException("Unsupported schema version");
        for (Migration migration : migrations) {
            if (applied.containsKey(migration.version())) continue;
            if (migration.incompatible() && !applied.isEmpty()) {
                Path backups = file.getParent().resolve("migration-backups"); Files.createDirectories(backups);
                Path backup = backups.resolve("before-v" + migration.version() + "-" + UUID.randomUUID() + ".sqlite");
                try (var statement = db.createStatement()) { statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'"); }
            }
            db.setAutoCommit(false);
            try {
                for (String sql : migration.sql().split(";")) if (!sql.isBlank()) try (var statement = db.createStatement()) { statement.execute(sql); }
                try (var insert = db.prepareStatement("INSERT INTO schema_version VALUES(?,?,?)")) {
                    insert.setInt(1, migration.version()); insert.setString(2, migration.checksum()); insert.setString(3, Instant.now().toString()); insert.executeUpdate();
                }
                db.commit(); applied.put(migration.version(), migration.checksum());
            } catch (Exception failure) { db.rollback(); throw failure; }
            finally { db.setAutoCommit(true); }
        }
    }
}
