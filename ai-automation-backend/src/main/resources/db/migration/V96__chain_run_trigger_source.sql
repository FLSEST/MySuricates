-- What started a chain run (TODO 6db47bc2). Until now only its entry runs knew it, through the context
-- key __parent_trigger_source; chain run lists had no starter. Set by ChainExecutor when the chain starts.
ALTER TABLE chain_runs ADD COLUMN trigger_source TEXT;

-- Existing chain runs: only a schedule start is certain (ScheduledWorkflowExecutor is the only caller
-- that passes a schedule id, and the chain executor then starts it as SCHEDULE). Every other old row
-- stays NULL — manual or wish starts are not distinguishable afterwards, nothing invented.
UPDATE chain_runs SET trigger_source = 'SCHEDULE'
 WHERE trigger_source IS NULL AND triggered_by_schedule_id IS NOT NULL;
