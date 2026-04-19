ALTER TABLE todos ADD COLUMN linked_workflow_id TEXT REFERENCES workflows(id) ON DELETE SET NULL;
ALTER TABLE todos ADD COLUMN resolution_state TEXT;

CREATE INDEX idx_todos_linked_workflow_id ON todos(linked_workflow_id);
