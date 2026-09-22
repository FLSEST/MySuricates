-- Siegel signieren und seine Geschichte dauerhaft festhalten (TODO 1a5fbd73).
--
-- 1. sealed_signature: HMAC-SHA256 ueber Workflow-ID, Definitions-Hash und Skill-Pins mit einem
--    Schluessel ausserhalb der Datenbank. Ohne sie steht der Siegelwert in derselben Datei wie das,
--    was er schuetzt — wer die Datei schreibt, passt beides an und kommt sauber durch.
-- 2. workflow_seal_events: jede Versiegelung und Entsiegelung mit Zeitpunkt. Das Activity-Log wird
--    nach 30 Tagen (teils frueher) aufgeraeumt und sealed_at faellt beim Entsiegeln weg; diese
--    Tabelle bleibt. Ohne Fremdschluessel, damit die Historie das Loeschen des Workflows ueberlebt.
--    prev_event_hash/event_hash verketten die Eintraege: eine geloeschte oder geaenderte Zeile
--    bricht die Kette sichtbar.
ALTER TABLE workflows ADD COLUMN sealed_signature TEXT;

CREATE TABLE workflow_seal_events (
    id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL,
    workflow_name TEXT,
    action TEXT NOT NULL,
    occurred_at TEXT NOT NULL DEFAULT (datetime('now')),
    definition_hash TEXT,
    skill_pins TEXT,
    signature TEXT,
    prev_event_hash TEXT,
    event_hash TEXT NOT NULL
);

CREATE INDEX idx_workflow_seal_events_workflow_id ON workflow_seal_events(workflow_id);
