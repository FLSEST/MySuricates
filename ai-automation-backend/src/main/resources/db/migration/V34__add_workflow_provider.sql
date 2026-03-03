-- Add optional AI provider override per workflow (CLAUDE, CODEX, etc.)
ALTER TABLE workflows ADD COLUMN provider TEXT;
