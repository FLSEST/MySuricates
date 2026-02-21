CREATE TABLE activity_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL,
    level TEXT NOT NULL,
    category TEXT NOT NULL,
    message TEXT NOT NULL,
    source TEXT,
    reference_id TEXT,
    metadata TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_activity_log_timestamp ON activity_log(timestamp DESC);
CREATE INDEX idx_activity_log_level ON activity_log(level);
CREATE INDEX idx_activity_log_category ON activity_log(category);
CREATE INDEX idx_activity_log_level_created ON activity_log(level, created_at);
