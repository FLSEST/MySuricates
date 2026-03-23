CREATE TABLE completion_triggers (
    id TEXT PRIMARY KEY,
    source_workflow_id TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    target_workflow_id TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    enabled INTEGER NOT NULL DEFAULT 1,
    trigger_on_success INTEGER NOT NULL DEFAULT 1,
    trigger_on_failure INTEGER NOT NULL DEFAULT 0,
    trigger_always INTEGER NOT NULL DEFAULT 0,
    description TEXT,
    context_variables TEXT DEFAULT '{}',
    last_triggered_at TEXT,
    trigger_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX idx_completion_triggers_source ON completion_triggers(source_workflow_id);
CREATE INDEX idx_completion_triggers_target ON completion_triggers(target_workflow_id);
