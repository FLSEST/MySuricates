-- Wishes: autonomous iteration flag
-- When true, the wish loop skips AWAITING_DECOMPOSE_APPROVAL and AWAITING_APPROVAL
-- and runs until satisfied, FAILED, or max_iterations is reached.
ALTER TABLE wishes ADD COLUMN auto_refine BOOLEAN NOT NULL DEFAULT FALSE;
