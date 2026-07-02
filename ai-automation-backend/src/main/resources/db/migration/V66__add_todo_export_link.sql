-- Single-TODO export link, decoupled from reference_type (which stays for internal/origin
-- correlation like workflow_run / health_check / imported issues).
ALTER TABLE todos ADD COLUMN exported_provider TEXT;
ALTER TABLE todos ADD COLUMN exported_ref TEXT;
ALTER TABLE todos ADD COLUMN exported_url TEXT;
