-- Snapshot the workflow name at run creation so the run history stays stable
-- even when a workflow is soft-deleted or the run is queried without a JPA
-- session (OSIV disabled → lazy workflow load would throw).
ALTER TABLE workflow_runs ADD COLUMN workflow_name TEXT;

UPDATE workflow_runs
SET workflow_name = (SELECT name FROM workflows WHERE workflows.id = workflow_runs.workflow_id)
WHERE workflow_name IS NULL;
