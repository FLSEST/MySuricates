-- Workflow Templates table
CREATE TABLE workflow_templates (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon TEXT,
    category TEXT,
    blocks TEXT NOT NULL,
    instructions TEXT,
    model TEXT,
    built_in INTEGER DEFAULT 0,
    sort_order INTEGER DEFAULT 0,
    version TEXT DEFAULT '1.0.0',
    created_at TEXT,
    updated_at TEXT
);

-- Back-reference: Workflow → Template
ALTER TABLE workflows ADD COLUMN template_id TEXT REFERENCES workflow_templates(id) ON DELETE SET NULL;
