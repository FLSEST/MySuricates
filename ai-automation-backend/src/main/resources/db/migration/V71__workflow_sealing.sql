-- Versiegelte Workflows (automation.hardening.sealing): sealed_hash hält den Definitions-Hash zum
-- Zeitpunkt des Versiegelns. Änderungen an der Definition werden abgelehnt, und vor jedem Run wird
-- der aktuelle Hash gegen sealed_hash geprüft — auch Änderungen direkt in der Datenbank fallen auf.
ALTER TABLE workflows ADD COLUMN sealed BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE workflows ADD COLUMN sealed_hash TEXT;
ALTER TABLE workflows ADD COLUMN sealed_at TEXT;
