-- Global quality status on templates
ALTER TABLE workflow_templates ADD COLUMN quality_status TEXT NOT NULL DEFAULT 'UNTESTED';

-- Per-project verification tracking
CREATE TABLE template_verifications (
    id TEXT PRIMARY KEY,
    template_id TEXT NOT NULL REFERENCES workflow_templates(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    last_run_id TEXT REFERENCES workflow_runs(id) ON DELETE SET NULL,
    verified_at TIMESTAMP NOT NULL,
    UNIQUE(template_id, project_id)
);
