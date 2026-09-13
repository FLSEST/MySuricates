-- Beweis-Snapshot und Zeitpunkt einer Risiko-Akzeptanz (ACCEPTED_RISK).
-- disposition_evidence: Evidence-Text des Findings zum Zeitpunkt der Akzeptanz (Vergleichsbasis
-- fuer die Verschlechterungsregel), disposition_at: Zeitpunkt der Akzeptanz (Basis fuer den
-- 180-Tage-Verfall). Bestehende Akzeptanzen werden mit ihrem aktuellen Stand vorbelegt.
ALTER TABLE audit_findings ADD COLUMN disposition_evidence TEXT;
ALTER TABLE audit_findings ADD COLUMN disposition_at TEXT;

UPDATE audit_findings
SET disposition_evidence = evidence,
    disposition_at = resolved_at
WHERE status = 'ACCEPTED_RISK' AND disposition_at IS NULL;
