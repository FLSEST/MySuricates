-- Track which schedule triggered a workflow or chain run.
-- Allows "show all runs triggered by schedule X" without a join through workflow/chain.
ALTER TABLE workflow_runs ADD COLUMN triggered_by_schedule_id TEXT;
ALTER TABLE chain_runs ADD COLUMN triggered_by_schedule_id TEXT;
