CREATE TABLE skills (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon TEXT,
    category TEXT NOT NULL,
    ai_context TEXT,
    config_schema TEXT,
    builtin INTEGER NOT NULL DEFAULT 0,
    version TEXT DEFAULT '1.0.0',
    created_at TEXT
);
