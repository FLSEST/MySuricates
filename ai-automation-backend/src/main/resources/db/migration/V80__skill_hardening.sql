-- Skills in die Workflow-Haertung einbeziehen (TODO 5861977c).
--
-- 1. sealed_skill_hashes: beim Siegeln festgehaltener Inhalts-Hash je Skill, den der Workflow nutzt
--    (JSON {skillId: sha256}). RunAdmission vergleicht ihn vor jedem Run; NULL heisst: vor dieser
--    Migration versiegelt, erneutes Siegeln traegt die Hashes nach.
-- 2. skill_versions: jede Aenderung an einem Skill (Anlegen, Import, Built-in-Update, Loeschen) mit
--    Snapshot, Inhalts-Hash und serverseitiger Herkunft. Ohne Fremdschluessel, damit die Historie
--    das Loeschen des Skills ueberlebt.
ALTER TABLE workflows ADD COLUMN sealed_skill_hashes TEXT;

CREATE TABLE skill_versions (
    id TEXT PRIMARY KEY,
    skill_id TEXT NOT NULL,
    version_number INTEGER NOT NULL,
    operation TEXT NOT NULL,
    snapshot TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    change_source TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(skill_id, version_number)
);

CREATE INDEX idx_skill_versions_skill_id ON skill_versions(skill_id);
