-- Keeps a run's live log after the run ends (TODO bbbfcbf8): one row per run, the lines as gzip-compressed
-- JSON. The foreign key cascades, so run retention and DELETE /executions/{id} remove the log with the run.
CREATE TABLE run_live_logs (
    run_id     TEXT PRIMARY KEY REFERENCES workflow_runs(id) ON DELETE CASCADE,
    buffer_id  TEXT NOT NULL,
    line_count INTEGER NOT NULL,
    skipped    INTEGER NOT NULL DEFAULT 0,
    content    BLOB NOT NULL,
    stored_at  TEXT NOT NULL
);

-- Per workflow: NULL = follow the system default (automation.live-log.persist-default), 'on' or 'off'.
-- Not part of the definition hash, so it changes no seal and writes no version row.
ALTER TABLE workflows ADD COLUMN live_log_persist TEXT;
