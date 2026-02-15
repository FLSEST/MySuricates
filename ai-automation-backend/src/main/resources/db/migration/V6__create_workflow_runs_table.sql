CREATE TABLE workflow_runs (
    id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    started_at TEXT,
    completed_at TEXT,
    duration_ms INTEGER,
    user_rating INTEGER,
    error TEXT,
    metadata TEXT,
    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);

CREATE TABLE step_results (
    id TEXT PRIMARY KEY,
    workflow_run_id TEXT NOT NULL,
    step_index INTEGER NOT NULL,
    input TEXT,
    output TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    started_at TEXT,
    completed_at TEXT,
    duration_ms INTEGER,
    metadata TEXT,
    FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE
);
