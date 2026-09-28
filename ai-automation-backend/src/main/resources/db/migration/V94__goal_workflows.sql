-- Ziele Stufe 3: Workflows am Ziel oder Arbeitspaket
-- (TODO 52bc1e8c Ziele Stufe 3: Workflows am Ziel oder Teilziel anzeigen, starten und als Stand nutzen).
--
-- Ein Workflow haengt am ganzen Ziel (work_package_id NULL) oder an einem seiner Arbeitspakete, mit
-- einer Rolle: WORKER („arbeitet daran“) oder STATUS („liefert den Stand“). Dieselbe Zuordnung gibt es
-- nur einmal – der Index behandelt „am Ziel“ (NULL) wie einen eigenen Wert. Workflows werden weich
-- geloescht (deleted_at); die Zuordnung bleibt dann stehen und wird beim Lesen ausgeblendet. Der
-- Ziel-Waechter steht hier nicht: er haengt ueber goals.guard_workflow_id am Ziel.
CREATE TABLE goal_workflows (
    id              TEXT PRIMARY KEY,
    goal_id         TEXT NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    work_package_id TEXT REFERENCES goal_work_packages(id) ON DELETE CASCADE,
    workflow_id     TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    role            TEXT NOT NULL DEFAULT 'WORKER',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_goal_workflows_unique ON goal_workflows(goal_id, IFNULL(work_package_id, ''), workflow_id);
CREATE INDEX idx_goal_workflows_workflow ON goal_workflows(workflow_id);
CREATE INDEX idx_goal_workflows_work_package ON goal_workflows(work_package_id);
