ALTER TABLE environments ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE variables ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX environments_name ON environments(workspace_id, name COLLATE NOCASE);
CREATE TABLE workspace_variable_settings(workspace_id TEXT PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE, version INTEGER NOT NULL DEFAULT 0, variables_json TEXT NOT NULL, selected_environment_id TEXT REFERENCES environments(id) ON DELETE SET NULL);
