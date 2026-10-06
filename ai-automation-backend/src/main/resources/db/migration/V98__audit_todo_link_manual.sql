-- Whether a managed audit TODO was created by hand (Audit Center button, POST /audit-findings/{id}/todos)
-- instead of by the ingest threshold (TODO f2ce6c50). Only automatic TODOs are released once their open
-- findings fall below HIGH; a TODO created by hand follows the findings like a HIGH one (DONE, back to OPEN).
-- Backfill: the ingest only creates TODOs for HIGH/CRITICAL findings and copies the severity into the TODO
-- priority, so a managed link whose TODO is LOW or MEDIUM came from the button.
ALTER TABLE audit_finding_todos ADD COLUMN manual INTEGER NOT NULL DEFAULT 0;
UPDATE audit_finding_todos SET manual = 1
 WHERE managed = 1 AND todo_id IN (SELECT id FROM todos WHERE priority IN ('LOW', 'MEDIUM'));
