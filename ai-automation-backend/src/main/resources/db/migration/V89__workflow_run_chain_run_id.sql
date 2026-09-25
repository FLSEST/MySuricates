-- The chain run a workflow run belongs to (TODO a3b89355). Set when a chain starts an entry run;
-- null for every other run and for runs from before this migration (no backfill: nothing invented).
-- No foreign key on purpose: deleting a chain deletes its chain runs, the workflow runs stay.
ALTER TABLE workflow_runs ADD COLUMN chain_run_id TEXT;

-- The runs of one chain run (chain run detail, audit report origin)
CREATE INDEX IF NOT EXISTS idx_workflow_runs_chain_run_id ON workflow_runs(chain_run_id);
