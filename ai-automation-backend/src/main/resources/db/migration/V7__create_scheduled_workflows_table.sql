CREATE TABLE IF NOT EXISTS scheduled_workflows (
    id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL,
    cron_expression TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    description TEXT,
    input_context TEXT DEFAULT '{}',
    last_run_at TEXT,
    next_run_at TEXT,
    created_at TEXT,
    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);
