CREATE INDEX IF NOT EXISTS idx_activity_log_level_category_ts
ON activity_log(level, category, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_activity_log_category_ts
ON activity_log(category, timestamp DESC);
