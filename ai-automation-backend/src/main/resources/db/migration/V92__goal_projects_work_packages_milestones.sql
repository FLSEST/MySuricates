-- Ziele Stufe 1: Ziel -> Projekte -> Arbeitspakete -> TODOs, dazu Meilensteine
-- (TODO c60bf914 Ziele Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs, Meilensteine, Gliederung und Zeitstrahl).
--
-- 1. goal_projects: Ein Ziel wird ausdruecklich einem oder mehreren Projekten zugeordnet, mit Reihenfolge.
--    Befuellt aus den TODOs der Ziele (auch archivierten); position 0 = das Projekt mit den meisten
--    nicht archivierten TODOs, Gleichstand nach project_id.
-- 2. Die bisherigen Meilensteine (V88) werden zu ARBEITSPAKETEN: goal_work_packages, je genau einem
--    Projekt (nullable, SET NULL), mit position und optionalem Zeitraum start_date/end_date
--    (Altbestand: end_date = altes due_date, start_date = end_date – ein Ein-Tages-Paket, nichts
--    erfunden), completed_at = altes reached_at. todos.milestone_id heisst jetzt work_package_id.
--    Ein Paket mit TODOs aus mehreren Projekten wird aufgeteilt: je Minderheitsprojekt eine Kopie
--    (gleicher Titel, gleicher Zeitraum, completed_at NULL), die TODOs dieses Projekts haengen danach an ihr.
-- 3. NEU goal_milestones: ein Termin fuers ganze Ziel (kein Projekt), optional mit Arbeitspaketen
--    verknuepft (goal_milestone_work_packages) – erreicht, wenn die verknuepften Pakete fertig sind.
--
-- FALLE 1: Flyway laeuft hier in einer Transaktion mit foreign_keys=ON (FlywayConfig). PRAGMA
-- foreign_keys=OFF wirkt innerhalb einer Transaktion nicht (V38 verlaesst sich darauf – nicht
-- kopieren). DROP TABLE goal_milestones loest deshalb ein implizites DELETE aus, und
-- todos.milestone_id (ON DELETE SET NULL) wuerde still fuer alle TODOs geleert. Die Zuordnungen
-- werden vorher in _todo_ms gesichert und am Ende zurueckgeschrieben.
-- FALLE 2: Der Fremdschluessel in todos nennt die Tabelle beim NAMEN. Der Neubau heisst deshalb erst
-- wieder goal_milestones (dann zeigt todos auf ihn) und wird danach zu goal_work_packages umbenannt –
-- SQLite >= 3.26 schreibt dabei die REFERENCES-Klausel in todos mit um (legacy_alter_table ist aus).
-- Erst danach entsteht die NEUE goal_milestones; todos zeigt dann nicht auf sie.

-- ── 1. Ziel ↔ Projekte ─────────────────────────────────────────────────────────────
CREATE TABLE goal_projects (
    goal_id    TEXT NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    position   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (goal_id, project_id)
);

CREATE INDEX idx_goal_projects_project ON goal_projects(project_id);

INSERT INTO goal_projects (goal_id, project_id, position)
SELECT goal_id, project_id,
       ROW_NUMBER() OVER (PARTITION BY goal_id ORDER BY active DESC, project_id) - 1
FROM (
    SELECT t.goal_id, t.project_id,
           SUM(CASE WHEN t.status <> 'ARCHIVED' THEN 1 ELSE 0 END) AS active
    FROM todos t
    WHERE t.goal_id IS NOT NULL AND t.project_id IS NOT NULL
      AND EXISTS (SELECT 1 FROM goals g WHERE g.id = t.goal_id)
      AND EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id)
    GROUP BY t.goal_id, t.project_id
);

-- ── 2. Arbeitspakete: Zuordnungen sichern, Projekt bestimmen, Aufteilung vorbereiten ─
CREATE TEMP TABLE _todo_ms AS
SELECT id AS todo_id, milestone_id FROM todos WHERE milestone_id IS NOT NULL;

-- Mehrheitsprojekt je Paket: meiste TODOs, Gleichstand nach Zielprojekt-Position, dann project_id.
CREATE TEMP TABLE _ms_project AS
SELECT milestone_id, project_id FROM (
    SELECT t.milestone_id, t.project_id,
           ROW_NUMBER() OVER (PARTITION BY t.milestone_id
                              ORDER BY COUNT(*) DESC, MIN(COALESCE(gp.position, 2147483647)), t.project_id) AS rn
    FROM todos t
    JOIN goal_milestones m ON m.id = t.milestone_id
    LEFT JOIN goal_projects gp ON gp.goal_id = m.goal_id AND gp.project_id = t.project_id
    WHERE t.project_id IS NOT NULL
      AND EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id)
    GROUP BY t.milestone_id, t.project_id
)
WHERE rn = 1;

-- Je Minderheitsprojekt eines Pakets eine neue ID. Die UUID entsteht AUSSERHALB des DISTINCT –
-- in einer eigenen Tabelle, damit auch kein Query-Flattening sie hineinzieht –, sonst wuerde jede
-- Zeile durch ihre Zufalls-ID „verschieden“ und das Paar je TODO einmal kopiert.
CREATE TEMP TABLE _ms_minority AS
SELECT DISTINCT t.milestone_id, t.project_id
FROM todos t
JOIN _ms_project mp ON mp.milestone_id = t.milestone_id
WHERE t.project_id IS NOT NULL AND t.project_id <> mp.project_id
  AND EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id);

-- Format wie Hibernates UUID-Generator: 8-4-4-4-12, klein, Version 4, Variante 8/9/a/b.
CREATE TEMP TABLE _ms_split AS
SELECT milestone_id, project_id,
       lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4'
           || substr(lower(hex(randomblob(2))), 2) || '-'
           || substr('89ab', 1 + (random() & 3), 1) || substr(lower(hex(randomblob(2))), 2) || '-'
           || lower(hex(randomblob(6))) AS new_id
FROM _ms_minority;

-- ── 3. Neubau mit den Spalten der Arbeitspakete befuellen ──────────────────────────
CREATE TABLE goal_milestones_new (
    id           TEXT PRIMARY KEY,
    goal_id      TEXT NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    project_id   TEXT REFERENCES projects(id) ON DELETE SET NULL,
    title        TEXT NOT NULL,
    start_date   TEXT,
    end_date     TEXT,
    position     INTEGER NOT NULL DEFAULT 0,
    completed_at TEXT,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

-- Projekt: Mehrheit der TODOs, sonst das erste Zielprojekt (position 0), sonst keins.
-- Zeitraum: das alte Datum als Ende und als Start.
INSERT INTO goal_milestones_new (id, goal_id, project_id, title, start_date, end_date, position, completed_at,
                                 created_at, updated_at)
SELECT m.id, m.goal_id,
       COALESCE((SELECT mp.project_id FROM _ms_project mp WHERE mp.milestone_id = m.id),
                (SELECT gp.project_id FROM goal_projects gp WHERE gp.goal_id = m.goal_id
                 ORDER BY gp.position, gp.project_id LIMIT 1)),
       m.title, m.due_date, m.due_date, 0, m.reached_at, m.created_at, m.updated_at
FROM goal_milestones m;

-- Kopien fuer die Minderheitsprojekte: noch nie abgeschlossen.
INSERT INTO goal_milestones_new (id, goal_id, project_id, title, start_date, end_date, position, completed_at,
                                 created_at, updated_at)
SELECT s.new_id, m.goal_id, s.project_id, m.title, m.due_date, m.due_date, 0, NULL, m.created_at, m.updated_at
FROM _ms_split s
JOIN goal_milestones m ON m.id = s.milestone_id;

-- Reihenfolge je (Ziel, Projekt): nach Ende (ohne Zeitraum zuletzt), dann Anlage.
CREATE TEMP TABLE _ms_position AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY goal_id, project_id
                              ORDER BY end_date IS NULL, end_date, created_at, id) - 1 AS position
FROM goal_milestones_new;

UPDATE goal_milestones_new
SET position = (SELECT p.position FROM _ms_position p WHERE p.id = goal_milestones_new.id);

-- ── 4. Tauschen, umbenennen, Zuordnungen zurueckschreiben ──────────────────────────
-- Implizites DELETE: setzt todos.milestone_id auf NULL (deshalb _todo_ms).
DROP TABLE goal_milestones;
-- Erst unter dem alten Namen: der Fremdschluessel in todos zeigt so auf den Neubau ...
ALTER TABLE goal_milestones_new RENAME TO goal_milestones;
-- ... und wandert beim Umbenennen mit (REFERENCES goal_work_packages).
ALTER TABLE goal_milestones RENAME TO goal_work_packages;
ALTER TABLE todos RENAME COLUMN milestone_id TO work_package_id;

DROP INDEX idx_todos_milestone;
CREATE INDEX idx_todos_work_package ON todos(work_package_id);
CREATE INDEX idx_goal_work_packages_goal ON goal_work_packages(goal_id);
CREATE INDEX idx_goal_work_packages_goal_project ON goal_work_packages(goal_id, project_id, position);

-- Zurueck an das alte Paket – oder an die Kopie, wenn das TODO zu einem Minderheitsprojekt gehoert.
-- TODOs ohne Projekt bleiben beim Original.
UPDATE todos
SET work_package_id = COALESCE(
        (SELECT s.new_id FROM _ms_split s JOIN _todo_ms b ON b.milestone_id = s.milestone_id
         WHERE b.todo_id = todos.id AND s.project_id = todos.project_id),
        (SELECT b.milestone_id FROM _todo_ms b WHERE b.todo_id = todos.id))
WHERE id IN (SELECT todo_id FROM _todo_ms);

-- ── 5. NEU: Meilensteine des Ziels und ihre Arbeitspakete ─────────────────────────
-- Ein Meilenstein ist ein Termin fuers ganze Ziel. Mit verknuepften Paketen ist er erreicht, wenn
-- alle verknuepften Pakete abgeschlossen sind (reached_at haelt den ersten Zeitpunkt fest); ohne
-- Pakete wird er von Hand als erreicht markiert. Der Zustand wird beim Lesen berechnet.
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

CREATE TABLE goal_milestone_work_packages (
    milestone_id    TEXT NOT NULL REFERENCES goal_milestones(id) ON DELETE CASCADE,
    work_package_id TEXT NOT NULL REFERENCES goal_work_packages(id) ON DELETE CASCADE,
    PRIMARY KEY (milestone_id, work_package_id)
);

CREATE INDEX idx_goal_milestone_work_packages_package ON goal_milestone_work_packages(work_package_id);

DROP TABLE _ms_position;
DROP TABLE _ms_split;
DROP TABLE _ms_minority;
DROP TABLE _ms_project;
DROP TABLE _todo_ms;
