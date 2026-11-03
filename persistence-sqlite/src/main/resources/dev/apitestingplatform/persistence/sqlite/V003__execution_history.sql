CREATE TABLE execution_history(id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, started_ms INTEGER NOT NULL, finished_ms INTEGER NOT NULL, planned INTEGER NOT NULL, completed INTEGER NOT NULL, request_failures INTEGER NOT NULL, failed_checks INTEGER NOT NULL, revision INTEGER NOT NULL, payload_json TEXT NOT NULL);
CREATE INDEX execution_history_order ON execution_history(started_ms DESC,id DESC);
CREATE TABLE history_settings(id INTEGER PRIMARY KEY CHECK(id=1), max_entries INTEGER NOT NULL CHECK(max_entries BETWEEN 1 AND 1000), max_age_days INTEGER NOT NULL CHECK(max_age_days BETWEEN 1 AND 365));
INSERT INTO history_settings VALUES(1,100,30);
