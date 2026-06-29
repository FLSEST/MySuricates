-- Per-schedule policy for system resume after suspend/freeze.
-- Default 0 (false): missed CRON occurrences are skipped forward to the next future run on resume
-- instead of firing a backlog stampede. Set to 1 to catch up (run once) on resume.
ALTER TABLE scheduled_workflows ADD COLUMN catch_up_on_resume INTEGER NOT NULL DEFAULT 0;
