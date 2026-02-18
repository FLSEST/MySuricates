ALTER TABLE containers ADD COLUMN last_activity_at TEXT;
UPDATE containers SET last_activity_at = started_at;
