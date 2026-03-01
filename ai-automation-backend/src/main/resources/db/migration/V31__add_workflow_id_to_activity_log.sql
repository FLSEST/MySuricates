ALTER TABLE activity_log ADD COLUMN workflow_id TEXT;
CREATE INDEX IF NOT EXISTS idx_activity_log_workflow_id ON activity_log(workflow_id, timestamp DESC);
