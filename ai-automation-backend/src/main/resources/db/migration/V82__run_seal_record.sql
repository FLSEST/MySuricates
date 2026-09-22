-- Siegelstand am Run festhalten (TODO 31ad45b7).
--
-- Der Siegelzustand eines Workflows aendert sich; ob ein Run versiegelt lief, muss daher beim Start
-- festgehalten werden, nicht aus dem heutigen Zustand abgeleitet. sealed: RunAdmission hat das
-- Siegel geprueft und bestaetigt (1) bzw. der Workflow war nicht versiegelt (0); NULL = Run von vor
-- dieser Migration. sealed_skill_pins: die dabei bestaetigten Skill-Inhalts-Hashes (JSON), NULL bei
-- unversiegelten Runs und bei Siegeln, die noch keine Skills pinnen.
ALTER TABLE workflow_runs ADD COLUMN sealed BOOLEAN;
ALTER TABLE workflow_runs ADD COLUMN sealed_skill_pins TEXT;
