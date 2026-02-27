ALTER TABLE workflows ADD COLUMN test_status TEXT DEFAULT 'UNTESTED';
ALTER TABLE workflows ADD COLUMN last_tested_at TEXT;
