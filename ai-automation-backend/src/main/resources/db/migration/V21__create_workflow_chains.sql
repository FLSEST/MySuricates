CREATE TABLE workflow_chains (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon TEXT,
    entries TEXT,
    stop_on_failure INTEGER NOT NULL DEFAULT 1,
    metadata TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE chain_runs (
    id TEXT PRIMARY KEY,
    chain_id TEXT NOT NULL REFERENCES workflow_chains(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'PENDING',
    entry_runs TEXT,
    input_context TEXT,
    started_at TEXT,
    completed_at TEXT,
    duration_ms INTEGER,
    error TEXT,
    metadata TEXT
);

CREATE INDEX idx_chain_runs_chain_id ON chain_runs(chain_id);
CREATE INDEX idx_chain_runs_status ON chain_runs(status);
