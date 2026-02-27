ALTER TABLE scheduled_workflows ADD COLUMN chain_id TEXT REFERENCES workflow_chains(id) ON DELETE CASCADE;
ALTER TABLE scheduled_workflows ADD COLUMN target_type TEXT NOT NULL DEFAULT 'WORKFLOW';
