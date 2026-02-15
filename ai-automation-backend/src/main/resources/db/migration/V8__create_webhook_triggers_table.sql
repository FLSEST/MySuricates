CREATE TABLE IF NOT EXISTS webhook_triggers (
    id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL,
    secret_token TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1,
    description TEXT,
    default_context TEXT DEFAULT '{}',
    last_triggered_at TEXT,
    trigger_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_webhook_triggers_token ON webhook_triggers(secret_token);
