-- Optional HMAC signing secret per webhook (automation.hardening.webhook-signatures).
-- Stored encrypted (AES-GCM, key outside the database); null = no signature required.
ALTER TABLE webhook_triggers ADD COLUMN signing_secret_enc TEXT;
