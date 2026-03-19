-- Make workflow_id nullable in scheduled_workflows to support CHAIN target type.
-- V27 added chain_id + target_type but forgot to remove the NOT NULL constraint
-- from workflow_id, causing INSERT to fail for CHAIN schedules.
-- SQLite does not support ALTER COLUMN, so the table must be recreated.

PRAGMA foreign_keys = OFF;

CREATE TABLE scheduled_workflows_new (
    id TEXT PRIMARY KEY,
    workflow_id TEXT REFERENCES workflows(id) ON DELETE CASCADE,
    chain_id TEXT REFERENCES workflow_chains(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL DEFAULT 'WORKFLOW',
    cron_expression TEXT NOT NULL DEFAULT '',
    schedule_type TEXT NOT NULL DEFAULT 'CRON',
    scheduled_at TEXT,
    delay_expression TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    description TEXT,
    input_context TEXT DEFAULT '{}',
    last_run_at TEXT,
    next_run_at TEXT,
    created_at TEXT,
    updated_at TEXT
);

INSERT INTO scheduled_workflows_new
    (id, workflow_id, chain_id, target_type, cron_expression, schedule_type,
     scheduled_at, delay_expression, enabled, description, input_context,
     last_run_at, next_run_at, created_at, updated_at)
SELECT
    id, workflow_id, chain_id, target_type, cron_expression,
    COALESCE(schedule_type, 'CRON'),
    scheduled_at,
    NULL AS delay_expression,
    enabled, description, input_context,
    last_run_at, next_run_at, created_at, updated_at
FROM scheduled_workflows;

DROP TABLE scheduled_workflows;
ALTER TABLE scheduled_workflows_new RENAME TO scheduled_workflows;

PRAGMA foreign_keys = ON;
