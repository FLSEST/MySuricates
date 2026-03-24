CREATE TABLE page_suggestions (
    id                  TEXT PRIMARY KEY,
    page                TEXT NOT NULL UNIQUE,
    suggestions         TEXT NOT NULL DEFAULT '[]',
    generated_by        TEXT NOT NULL DEFAULT 'CLAUDE',
    generated_at        REAL NOT NULL,
    based_on_sessions   INTEGER NOT NULL DEFAULT 0
);
