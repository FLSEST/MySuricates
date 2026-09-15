-- Versioned snapshots of the dashboard layout (all views), written when a user finishes editing.
-- created_at has no SQL default: every row is written by Hibernate, so the stored format stays uniform
-- and ordering by created_at is reliable.
CREATE TABLE dashboard_layout_versions (
    id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    device_label TEXT NOT NULL,
    name TEXT,
    format_version INTEGER NOT NULL,
    view_count INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    content TEXT NOT NULL
);

CREATE INDEX idx_dashboard_layout_versions_created_at ON dashboard_layout_versions (created_at);
