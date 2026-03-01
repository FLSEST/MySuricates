CREATE TABLE wishes (
    id TEXT PRIMARY KEY,
    original_text TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'RECEIVED',
    project_id TEXT REFERENCES projects(id),
    decomposition TEXT,
    generated_workflow_ids TEXT,
    generated_chain_id TEXT,
    iteration_count INTEGER NOT NULL DEFAULT 0,
    max_iterations INTEGER NOT NULL DEFAULT 5,
    current_run_id TEXT,
    result_summary TEXT,
    error TEXT,
    metadata TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_wishes_status ON wishes(status);
CREATE INDEX idx_wishes_project_id ON wishes(project_id);
