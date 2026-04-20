-- Add tags + category to workflows for intent-based discovery.
-- Tags are a JSON-encoded string array (mirrors workflow_pool schema).
-- Category is a closed taxonomy (Productivity | Dev | Content | Analysis | Ops | Data | Integration | Other).
-- tags_generated_at tracks when the tagging service last wrote; tags_user_edited locks tags from auto-regeneration.
ALTER TABLE workflows ADD COLUMN tags TEXT;
ALTER TABLE workflows ADD COLUMN category VARCHAR(32);
ALTER TABLE workflows ADD COLUMN tags_generated_at TIMESTAMP;
ALTER TABLE workflows ADD COLUMN tags_user_edited BOOLEAN NOT NULL DEFAULT 0;
