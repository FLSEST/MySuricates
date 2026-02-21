CREATE TABLE workflow_memory (
    id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    tags TEXT,
    source_run_ids TEXT,
    provider TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);
CREATE INDEX idx_workflow_memory_workflow ON workflow_memory(workflow_id);
CREATE INDEX idx_workflow_memory_active ON workflow_memory(workflow_id, active);
