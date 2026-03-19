-- Add delay_expression column for DELAYED schedule type
-- DELAYED schedules compute scheduledAt = created_at + delay_expression at creation time
-- and behave like ONCE (run once, then disable).
ALTER TABLE scheduled_workflows ADD COLUMN delay_expression TEXT;
