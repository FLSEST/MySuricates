-- Ziele: Meilensteine, Faelligkeit im Ziel und Ziel-Waechter (TODO 259f4f66).
--
-- Ein Meilenstein ist ein eigener Eintrag mit Titel und Datum; TODOs des Ziels werden ihm
-- zugeordnet. Erreicht ist er, wenn alle zugeordneten TODOs erledigt sind – reached_at haelt den
-- ersten Zeitpunkt fest und bleibt, auch wenn spaeter wieder ein offenes TODO dazukommt.
-- Der Zustand (OPEN/DUE_SOON/OVERDUE/REACHED) wird beim Lesen berechnet, nicht gespeichert.
CREATE TABLE goal_milestones (
    id          TEXT PRIMARY KEY,
    goal_id     TEXT NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    due_date    TEXT NOT NULL,
    reached_at  TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX idx_goal_milestones_goal ON goal_milestones(goal_id);

ALTER TABLE todos ADD COLUMN milestone_id TEXT REFERENCES goal_milestones(id) ON DELETE SET NULL;

CREATE INDEX idx_todos_milestone ON todos(milestone_id);

-- Der Ziel-Waechter: ein Workflow aus der Vorlage goal-guard je Ziel, mit taeglichem Zeitplan.
ALTER TABLE goals ADD COLUMN guard_workflow_id TEXT REFERENCES workflows(id) ON DELETE SET NULL;
