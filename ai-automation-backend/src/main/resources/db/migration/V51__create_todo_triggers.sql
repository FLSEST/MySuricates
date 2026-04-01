CREATE TABLE todo_triggers (
    id TEXT PRIMARY KEY,
    tag_pattern TEXT NOT NULL,
    project_id TEXT,
    source_filter TEXT,
    min_priority TEXT,
    target_workflow_id TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    context_template TEXT DEFAULT '{}',
    enabled INTEGER NOT NULL DEFAULT 1,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    auto_close INTEGER NOT NULL DEFAULT 1,
    description TEXT,
    last_triggered_at TEXT,
    trigger_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX idx_todo_triggers_enabled ON todo_triggers(enabled);
