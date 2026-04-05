-- Composite index for the main list query: filter by status, sort by priority + created_at
CREATE INDEX IF NOT EXISTS idx_todos_status_priority_created ON todos(status, priority, created_at DESC);

-- Composite index for project-scoped queries with status filter
CREATE INDEX IF NOT EXISTS idx_todos_project_status ON todos(project_id, status);
