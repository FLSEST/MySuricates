-- Herkunft eines TODOs aus einer Agenten-Sitzung als eigenes Feld (TODO 3497533c).
--
-- Bisher haengt die Rueckverfolgbarkeit daran, dass der Agent den Befehl
-- `claude --resume <uuid>` freiwillig in die Beschreibung schreibt (Konvention aus CLAUDE.md).
-- Das Feld session_id traegt nur die Shell-Session, nicht die Konversation. Messung vom
-- 2026-09-16 ueber 1897 TODOs: 874 mit sourceAgent claude-code, davon 554 (63 %) mit --resume
-- in der Beschreibung. Ein gutes Drittel war nicht auf seine Sitzung zurueckfuehrbar --
-- prompt-enforced statt backend-enforced, genau das Muster, das die Projektregel verbietet.
ALTER TABLE todos ADD COLUMN agent_session_ref TEXT;

-- Einmalige Uebernahme aus dem Fliesstext: `claude --resume <uuid>` bzw. `codex --resume <uuid>`.
-- Bewusst in SQL und nicht in einem Skript, damit jede Instanz sie beim Start genau einmal
-- bekommt. Gesucht wird die UUID direkt hinter '--resume ' (36 Zeichen, 8-4-4-4-12).
--
-- Die Bedingung mit GLOB haelt den Treffer streng: nur wenn an der erwarteten Stelle
-- wirklich eine UUID-Form steht, wird uebernommen. SQLite kennt keine Regex, deshalb GLOB.
UPDATE todos
SET agent_session_ref = substr(description, instr(description, '--resume ') + 9, 36)
WHERE agent_session_ref IS NULL
  AND description IS NOT NULL
  AND instr(description, '--resume ') > 0
  AND substr(description, instr(description, '--resume ') + 9, 36)
      GLOB '[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]-[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]-[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]-[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]-[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]';

CREATE INDEX IF NOT EXISTS idx_todos_agent_session_ref ON todos(agent_session_ref);
