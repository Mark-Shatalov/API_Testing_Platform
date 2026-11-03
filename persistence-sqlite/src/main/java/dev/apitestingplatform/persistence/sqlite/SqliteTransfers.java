package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.StorageException;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Serialized repository worker only. Imports create a new workspace and remap every hierarchy/environment reference. */
final class SqliteTransfers {
    private final Connection db; private final ProtectedDraftCodec codec;
    SqliteTransfers(Connection db, ProtectedDraftCodec codec) { this.db = db; this.codec = codec; }
    WorkspaceTransfer snapshot(UUID workspaceId) throws Exception {
        List<WorkspaceTransfer.Node> nodes = new ArrayList<>(); long budget = 48L * 1024 * 1024;
        String sql = "WITH RECURSIVE selected(id) AS (SELECT id FROM items WHERE id=? AND kind='WORKSPACE' UNION ALL SELECT items.id FROM items JOIN selected ON items.parent_id=selected.id) SELECT items.*,requests.payload_json FROM selected JOIN items ON items.id=selected.id LEFT JOIN requests ON requests.id=items.id LIMIT 10001";
        try (var query = db.prepareStatement(sql)) {
            query.setString(1, workspaceId.toString()); try (var rows = query.executeQuery()) { while (rows.next()) {
                String parent = rows.getString("parent_id"), payload = rows.getString("payload_json");
                if (payload != null) { budget -= payload.length(); if (budget < 0) throw new StorageException("Workspace exceeds the transfer size budget. Export a smaller workspace."); }
                var kind = LibraryItem.Kind.valueOf(rows.getString("kind"));
                nodes.add(new WorkspaceTransfer.Node(UUID.fromString(rows.getString("id")), kind, parent == null ? null : UUID.fromString(parent), rows.getString("name"), kind == LibraryItem.Kind.WORKSPACE ? 0 : rows.getInt("position"), payload == null ? null : codec.decode(payload)));
            } }
        }
        if (nodes.isEmpty()) throw new StorageException("Select an existing workspace to export.");
        List<Variable> workspace = List.of(); UUID selected = null;
        try (var query = db.prepareStatement("SELECT * FROM workspace_variable_settings WHERE workspace_id=?")) {
            query.setString(1, workspaceId.toString()); try (var row = query.executeQuery()) { if (row.next()) {
                budget -= row.getString("variables_json").length(); if (budget < 0) throw new StorageException("Workspace exceeds the transfer size budget.");
                workspace = codec.decodeValue(row.getString("variables_json"), VariableSet.class).variables(); String id = row.getString("selected_environment_id"); selected = id == null ? null : UUID.fromString(id);
            } }
        }
        List<WorkspaceTransfer.TransferEnvironment> environments = new ArrayList<>();
        try (var query = db.prepareStatement("SELECT * FROM environments WHERE workspace_id=? ORDER BY name COLLATE NOCASE,id")) {
            query.setString(1, workspaceId.toString()); try (var rows = query.executeQuery()) { while (rows.next()) {
                UUID id = UUID.fromString(rows.getString("id")); List<Variable> variables = new ArrayList<>();
                try (var values = db.prepareStatement("SELECT * FROM variables WHERE environment_id=? ORDER BY position,id")) {
                    values.setString(1, id.toString()); try (var entries = values.executeQuery()) { while (entries.next()) { budget -= entries.getString("value_json").length(); if (budget < 0) throw new StorageException("Workspace exceeds the transfer size budget."); variables.add(new Variable(entries.getString("name"), codec.decodeValue(entries.getString("value_json"), String.class), entries.getInt("secret") != 0)); } }
                }
                environments.add(new WorkspaceTransfer.TransferEnvironment(id, workspaceId, rows.getString("name"), variables));
            } }
        }
        return new WorkspaceTransfer(workspaceId, nodes, workspace, environments, selected);
    }
    UUID importCopy(WorkspaceTransfer input) throws Exception {
        // Validate and protect everything before opening a write transaction.
        WorkspaceTransfer transfer = new WorkspaceTransfer(input.workspaceId(), input.nodes(), input.variables(), input.environments(), input.selectedEnvironmentId());
        Map<UUID, UUID> remapped = new HashMap<>(); Map<UUID, String> requests = new HashMap<>(); Map<UUID, List<String>> variables = new HashMap<>();
        for (var node : transfer.nodes()) { remapped.put(node.id(), UUID.randomUUID()); if (node.request() != null) requests.put(node.id(), codec.encode(node.request())); }
        for (var environment : transfer.environments()) {
            remapped.put(environment.id(), UUID.randomUUID()); List<String> protectedValues = new ArrayList<>();
            for (var variable : environment.variables()) protectedValues.add(codec.encodeValue(variable.value())); variables.put(environment.id(), protectedValues);
        }
        String workspaceValues = codec.encodeValue(new VariableSet(transfer.variables())); String now = Instant.now().toString();
        db.setAutoCommit(false);
        try {
            int rootPosition; try (var query = db.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM items WHERE parent_id IS NULL")) { rows.next(); rootPosition = rows.getInt(1); }
            List<WorkspaceTransfer.Node> ordered = transfer.nodes().stream().sorted(Comparator.comparingInt(node -> node.kind().ordinal())).toList();
            Map<UUID, WorkspaceTransfer.Node> original = new HashMap<>(); transfer.nodes().forEach(node -> original.put(node.id(), node));
            for (var node : ordered) {
                UUID id = remapped.get(node.id()), parent = remapped.get(node.parentId());
                String name = node.kind() == LibraryItem.Kind.WORKSPACE ? importedName(node.name()) : node.name();
                execute("INSERT INTO items VALUES(?,?,?,?,?,?,?)", id, node.kind().name(), parent, name, node.kind() == LibraryItem.Kind.WORKSPACE ? rootPosition : node.position(), 0, now);
                switch (node.kind()) {
                    case WORKSPACE -> execute("INSERT INTO workspaces VALUES(?)", id);
                    case COLLECTION -> execute("INSERT INTO collections VALUES(?,?)", id, parent);
                    case FOLDER -> execute("INSERT INTO folders VALUES(?,?)", id, parent);
                    case REQUEST -> {
                        var ancestor = original.get(node.parentId()); boolean folder = ancestor.kind() == LibraryItem.Kind.FOLDER;
                        execute("INSERT INTO requests VALUES(?,?,?,?)", id, folder ? remapped.get(ancestor.parentId()) : parent, folder ? parent : null, requests.get(node.id()));
                    }
                }
            }
            for (var environment : transfer.environments()) {
                UUID id = remapped.get(environment.id()); execute("INSERT INTO environments(id,workspace_id,name,modified_at,version) VALUES(?,?,?,?,0)", id, remapped.get(transfer.workspaceId()), environment.name(), now);
                for (int index = 0; index < environment.variables().size(); index++) {
                    var variable = environment.variables().get(index);
                    execute("INSERT INTO variables(id,environment_id,name,value_json,secret,modified_at,position) VALUES(?,?,?,?,?,?,?)", UUID.randomUUID(), id, variable.name(), variables.get(environment.id()).get(index), variable.secret() ? 1 : 0, now, index);
                }
            }
            UUID workspace = remapped.get(transfer.workspaceId());
            execute("INSERT INTO workspace_variable_settings VALUES(?,0,?,?)", workspace, workspaceValues, remapped.get(transfer.selectedEnvironmentId()));
            db.commit(); return workspace;
        } catch (Exception failure) { db.rollback(); throw failure; }
        finally { db.setAutoCommit(true); }
    }
    private static String importedName(String name) { String suffix = " (imported)"; return name.substring(0, Math.min(name.length(), 200 - suffix.length())) + suffix; }
    private void execute(String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index] instanceof UUID id ? id.toString() : values[index]); statement.executeUpdate();
        }
    }
}
