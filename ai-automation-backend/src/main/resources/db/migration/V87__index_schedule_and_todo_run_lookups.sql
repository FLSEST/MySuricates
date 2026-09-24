-- Lookups that scanned whole tables (audit finding d4b62bf3):
-- runs of a schedule, newest first (schedule run list, calendar, last-run badges)
CREATE INDEX IF NOT EXISTS idx_workflow_runs_schedule_started ON workflow_runs(triggered_by_schedule_id, started_at);
CREATE INDEX IF NOT EXISTS idx_chain_runs_schedule ON chain_runs(triggered_by_schedule_id);

-- TODO lookups by the run that fixed or created them (TodoTrigger loop, run detail)
CREATE INDEX IF NOT EXISTS idx_todos_last_fix_run_id ON todos(last_fix_run_id);
CREATE INDEX IF NOT EXISTS idx_todos_workflow_run_id ON todos(workflow_run_id);
