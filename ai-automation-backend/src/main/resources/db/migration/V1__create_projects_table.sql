CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    path TEXT NOT NULL,
    config TEXT,
    status TEXT NOT NULL DEFAULT 'STOPPED',
    created_at TEXT,
    updated_at TEXT
);
