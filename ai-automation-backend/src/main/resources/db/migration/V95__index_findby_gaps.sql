-- Derived-query lookups without an index (TODO 2d233599, performance audit unindexed_findby_column).
-- Only columns on a hot path or in a growing table; configuration-sized tables (skills, projects,
-- onboarding_profiles, containers, wishes) and two-valued flags like enabled stay unindexed on purpose.

-- Workflows of a project: project list counts them per project, project page lists them,
-- deleting a project cascades through them (rows carry the whole block JSON)
CREATE INDEX IF NOT EXISTS idx_workflows_project_id ON workflows(project_id);

-- Per-workflow / per-chain lists on the detail pages, goal guard lookup (workflow_id IN ...)
-- and the ON DELETE CASCADE from workflows and workflow_chains. V38 rebuilt the table without any index.
CREATE INDEX IF NOT EXISTS idx_scheduled_workflows_workflow_id ON scheduled_workflows(workflow_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_workflows_chain_id ON scheduled_workflows(chain_id);

-- Per-workflow trigger lists and the ON DELETE CASCADE from workflows
CREATE INDEX IF NOT EXISTS idx_webhook_triggers_workflow_id ON webhook_triggers(workflow_id);
CREATE INDEX IF NOT EXISTS idx_todo_triggers_target_workflow_id ON todo_triggers(target_workflow_id);

-- Capabilities of a skill: skill detail, JOIN FETCH on skill loads, ON DELETE CASCADE from skills
CREATE INDEX IF NOT EXISTS idx_capabilities_skill_id ON capabilities(skill_id);

-- Run deletion (retention, one run per short transaction) removes the run's dead-letter entries;
-- the table grows with every failed run
CREATE INDEX IF NOT EXISTS idx_dead_letter_entries_workflow_run_id ON dead_letter_entries(workflow_run_id);

-- Newest active memories across all workflows (assistant context on every prompt, page suggestions):
-- idx_workflow_memory_active is (workflow_id, active) and cannot serve a lookup by active alone.
-- With created_at in the index SQLite walks it in order and stops at the LIMIT.
CREATE INDEX IF NOT EXISTS idx_workflow_memory_active_created_at ON workflow_memory(active, created_at);
