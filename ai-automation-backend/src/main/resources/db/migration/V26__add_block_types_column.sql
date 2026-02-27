-- Denormalized block_types column for lightweight similarity queries
-- Stores sorted, comma-separated unique block types (e.g. "agentic,retry,simple")
ALTER TABLE workflows ADD COLUMN block_types TEXT DEFAULT '';
