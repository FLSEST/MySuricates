-- Run-Provenienz: welche Definition (SHA-256 über den kanonischen Workflow-Snapshot) ein Run
-- ausgeführt hat, welche Versionszeile dazu passt und was ihn gestartet hat.
-- Alte Runs bleiben ohne Hash (nicht rekonstruierbar); Versionszeilen werden beim Start
-- nachträglich gehasht (WorkflowVersionHashBackfillService).
ALTER TABLE workflow_versions ADD COLUMN definition_hash TEXT;
ALTER TABLE workflow_runs ADD COLUMN definition_hash TEXT;
ALTER TABLE workflow_runs ADD COLUMN definition_version_id TEXT;
ALTER TABLE workflow_runs ADD COLUMN trigger_source TEXT;

CREATE INDEX idx_workflow_versions_hash ON workflow_versions (workflow_id, definition_hash);
