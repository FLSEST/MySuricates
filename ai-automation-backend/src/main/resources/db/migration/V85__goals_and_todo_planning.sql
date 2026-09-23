-- Ziele: TODOs auf ein Ergebnis hin sammeln, ordnen und einschaetzen (TODO dc0566f8).
--
-- Ein Ziel buendelt TODOs projektuebergreifend – der Open-Source-Launch laeuft ueber zwei
-- Projekte, ein Projekt allein kann ihn nicht abbilden. Die Einordnung haengt am TODO selbst:
-- Zugehoerigkeit und Rang im Ziel, Komplexitaet (S/M/L/XL), empfohlener Reasoning-Effort
-- (low/medium/high/xhigh wie workflows.effort), eine kurze Begruendung, wer sie gesetzt hat
-- (USER/AI) und wann, und die offene Frage, die vor der Arbeit geklaert sein muss.
CREATE TABLE goals (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    target_date TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX idx_goals_status ON goals(status);

ALTER TABLE todos ADD COLUMN goal_id TEXT REFERENCES goals(id) ON DELETE SET NULL;
ALTER TABLE todos ADD COLUMN goal_rank INTEGER;
ALTER TABLE todos ADD COLUMN complexity TEXT;
ALTER TABLE todos ADD COLUMN suggested_effort TEXT;
ALTER TABLE todos ADD COLUMN rating_note TEXT;
ALTER TABLE todos ADD COLUMN rated_by TEXT;
ALTER TABLE todos ADD COLUMN rated_at TEXT;
ALTER TABLE todos ADD COLUMN open_question TEXT;

CREATE INDEX idx_todos_goal ON todos(goal_id);
