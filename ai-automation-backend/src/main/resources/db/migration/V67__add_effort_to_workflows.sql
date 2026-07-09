-- Unified reasoning-effort level per workflow (low/medium/high/xhigh), nullable.
-- Unset means provider default: no --effort (Claude) / --thinking (Pi) flag is emitted.
ALTER TABLE workflows ADD COLUMN effort VARCHAR(16);
