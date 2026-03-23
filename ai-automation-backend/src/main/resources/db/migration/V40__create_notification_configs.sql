CREATE TABLE notification_configs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    channel_type TEXT NOT NULL DEFAULT 'WEBHOOK',
    webhook_url TEXT,
    workflow_id TEXT REFERENCES workflows(id) ON DELETE CASCADE,
    trigger_on_success INTEGER NOT NULL DEFAULT 1,
    trigger_on_failure INTEGER NOT NULL DEFAULT 1,
    trigger_always INTEGER NOT NULL DEFAULT 0,
    message_template TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX idx_notification_configs_workflow ON notification_configs(workflow_id);
