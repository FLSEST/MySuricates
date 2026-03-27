ALTER TABLE scheduled_workflows ADD COLUMN end_date TEXT;
ALTER TABLE scheduled_workflows ADD COLUMN max_runs INTEGER DEFAULT 0;
ALTER TABLE scheduled_workflows ADD COLUMN run_count INTEGER DEFAULT 0;
