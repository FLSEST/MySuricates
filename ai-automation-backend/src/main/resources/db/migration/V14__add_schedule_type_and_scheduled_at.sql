ALTER TABLE scheduled_workflows ADD COLUMN schedule_type TEXT NOT NULL DEFAULT 'CRON';
ALTER TABLE scheduled_workflows ADD COLUMN scheduled_at TEXT;
