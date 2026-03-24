CREATE TABLE assistant_sessions (
    id            TEXT PRIMARY KEY,
    page          TEXT NOT NULL,
    entity_id     TEXT,
    messages      TEXT NOT NULL DEFAULT '[]',
    message_count INTEGER NOT NULL DEFAULT 0,
    started_at    REAL NOT NULL,
    ended_at      REAL
);

CREATE INDEX idx_assistant_sessions_page ON assistant_sessions(page);
CREATE INDEX idx_assistant_sessions_started_at ON assistant_sessions(started_at);
