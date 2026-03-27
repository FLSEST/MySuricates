-- Add context_delta column to step_results for run resumption.
-- Stores the cumulative user-defined variables (excluding derivable step_N_output
-- and system-injected vars) at the end of each step, enabling reconstruction of
-- the execution context when resuming an INTERRUPTED workflow run.
ALTER TABLE step_results ADD COLUMN context_delta TEXT;
