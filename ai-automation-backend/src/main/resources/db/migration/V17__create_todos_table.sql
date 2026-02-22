CREATE TABLE todos (
    id              TEXT PRIMARY KEY,
    title           TEXT NOT NULL,
    description     TEXT,
    status          TEXT NOT NULL DEFAULT 'OPEN',
    priority        TEXT NOT NULL DEFAULT 'MEDIUM',
    tags            TEXT,
    project_id      TEXT,
    source          TEXT NOT NULL DEFAULT 'USER',
    source_agent    TEXT,
    workflow_run_id TEXT,
    reference_type  TEXT,
    reference_id    TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL
);
CREATE INDEX idx_todos_status   ON todos(status);
CREATE INDEX idx_todos_project  ON todos(project_id);
CREATE INDEX idx_todos_source   ON todos(source);
CREATE INDEX idx_todos_created  ON todos(created_at DESC);
