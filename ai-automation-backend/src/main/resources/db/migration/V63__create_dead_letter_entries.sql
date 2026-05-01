CREATE TABLE dead_letter_entries (
    id TEXT PRIMARY KEY,
    workflow_run_id TEXT,
    workflow_id TEXT,
    workflow_name TEXT,
    error_message TEXT,
    failed_step_index INTEGER,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_failure_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    created_at TEXT NOT NULL,
    updated_at TEXT
);

CREATE INDEX idx_dlq_status ON dead_letter_entries(status);
CREATE INDEX idx_dlq_created_at ON dead_letter_entries(created_at DESC);
