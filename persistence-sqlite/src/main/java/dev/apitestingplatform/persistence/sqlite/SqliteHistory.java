package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.run.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Called only by the owning repository's serialized worker. */
final class SqliteHistory {
    private final Connection db; private final ProtectedDraftCodec codec;
    SqliteHistory(Connection db, ProtectedDraftCodec codec) { this.db = db; this.codec = codec; }
    void recover() throws Exception {
        try (var update = db.prepareStatement("UPDATE execution_history SET state='INTERRUPTED',finished_ms=? WHERE state='RUNNING'")) { update.setLong(1, System.currentTimeMillis()); update.executeUpdate(); }
        prune();
    }
    void save(HistoryRecord record) throws Exception {
        HistoryRecord safe = HistorySanitizer.clean(record); String payload = codec.encodeValue(safe);
        try (var insert = db.prepareStatement("INSERT INTO execution_history VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,finished_ms=excluded.finished_ms,completed=excluded.completed,request_failures=excluded.request_failures,failed_checks=excluded.failed_checks,revision=excluded.revision,payload_json=excluded.payload_json WHERE excluded.revision>=execution_history.revision AND execution_history.state<>'INTERRUPTED'")) {
            insert.setString(1, safe.id().toString()); insert.setString(2, safe.kind().name()); insert.setString(3, safe.state().name()); insert.setLong(4, safe.startedMillis()); insert.setLong(5, safe.finishedMillis());
            insert.setInt(6, safe.planned()); insert.setInt(7, (int) safe.steps().stream().filter(step -> step.entry().outcome() != RunEntry.Outcome.NOT_RUN).count()); insert.setLong(8, safe.requestFailures()); insert.setLong(9, safe.failedChecks()); insert.setLong(10, safe.revision()); insert.setString(11, payload); insert.executeUpdate();
        }
        prune();
    }
    HistorySettings settings() throws SQLException {
        try (var query = db.createStatement(); var row = query.executeQuery("SELECT max_entries,max_age_days FROM history_settings WHERE id=1")) { if (!row.next()) throw new SQLException(); return new HistorySettings(row.getInt(1), row.getInt(2)); }
    }
    void settings(HistorySettings settings) throws Exception {
        try (var update = db.prepareStatement("UPDATE history_settings SET max_entries=?,max_age_days=? WHERE id=1")) { update.setInt(1, settings.maxEntries()); update.setInt(2, settings.maxAgeDays()); update.executeUpdate(); }
        prune();
    }
    private void prune() throws SQLException {
        var settings = settings(); long cutoff = Instant.now().minus(Duration.ofDays(settings.maxAgeDays())).toEpochMilli();
        try (var delete = db.prepareStatement("DELETE FROM execution_history WHERE state<>'RUNNING' AND (started_ms<? OR id NOT IN (SELECT id FROM execution_history WHERE state<>'RUNNING' ORDER BY started_ms DESC,id DESC LIMIT ?))")) { delete.setLong(1, cutoff); delete.setInt(2, settings.maxEntries()); delete.executeUpdate(); }
    }
    List<HistorySummary> list(int limit, int offset) throws SQLException {
        if (limit < 1 || limit > 1000 || offset < 0) throw new StorageException("Invalid history page.");
        List<HistorySummary> result = new ArrayList<>();
        try (var query = db.prepareStatement("SELECT * FROM execution_history ORDER BY started_ms DESC,id DESC LIMIT ? OFFSET ?")) {
            query.setInt(1, limit); query.setInt(2, offset);
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(new HistorySummary(UUID.fromString(rows.getString("id")), HistoryRecord.Kind.valueOf(rows.getString("kind")), rows.getLong("started_ms"), rows.getLong("finished_ms"),
                    HistoryRecord.State.valueOf(rows.getString("state")), rows.getInt("planned"), rows.getInt("completed"), rows.getLong("request_failures"), rows.getLong("failed_checks"))); }
        }
        return List.copyOf(result);
    }
    HistoryRecord load(UUID id) throws Exception {
        try (var query = db.prepareStatement("SELECT * FROM execution_history WHERE id=?")) {
            query.setString(1, id.toString()); try (var row = query.executeQuery()) {
                if (!row.next()) throw new StorageException("This history record was removed by retention or deletion. Refresh history.");
                return HistorySanitizer.clean(codec.decodeValue(row.getString("payload_json"), HistoryRecord.class)).withState(HistoryRecord.State.valueOf(row.getString("state")), row.getLong("finished_ms"));
            }
        }
    }
    void clear() throws SQLException { try (var statement = db.createStatement()) { statement.executeUpdate("DELETE FROM execution_history WHERE state<>'RUNNING'"); } }
}
