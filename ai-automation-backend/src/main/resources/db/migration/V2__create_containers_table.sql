CREATE TABLE containers (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL UNIQUE,
    docker_container_id TEXT NOT NULL,
    image_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CREATED',
    mounts TEXT,
    started_at TEXT,
    stopped_at TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);
