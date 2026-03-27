ALTER TABLE scheduled_workflows ADD COLUMN conflict_behavior TEXT DEFAULT 'SKIP';
