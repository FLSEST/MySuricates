-- Mehrere benannte Verbindungen je Integrations-Anbieter (0b3cbcc3).
-- V55 legte provider_id als UNIQUE an; SQLite kann eine UNIQUE-Bedingung nicht entfernen,
-- deshalb wird die Tabelle neu gebaut. Bestandszeilen werden zur Verbindung 'default'.
-- Keine Fremdschlüssel verweisen auf integration_connections.

CREATE TABLE integration_connections_new (
    id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL,
    connection_name TEXT NOT NULL DEFAULT 'default',
    status TEXT NOT NULL DEFAULT 'DISCONNECTED',
    access_token_encrypted TEXT,
    refresh_token_encrypted TEXT,
    token_expiry TEXT,
    scopes TEXT,
    account_info TEXT,
    created_at TEXT,
    updated_at TEXT,
    client_id_encrypted TEXT,
    client_secret_encrypted TEXT,
    UNIQUE (provider_id, connection_name)
);

INSERT INTO integration_connections_new
    (id, provider_id, connection_name, status, access_token_encrypted, refresh_token_encrypted,
     token_expiry, scopes, account_info, created_at, updated_at, client_id_encrypted, client_secret_encrypted)
SELECT
    id, provider_id, 'default', status, access_token_encrypted, refresh_token_encrypted,
    token_expiry, scopes, account_info, created_at, updated_at, client_id_encrypted, client_secret_encrypted
FROM integration_connections;

DROP TABLE integration_connections;
ALTER TABLE integration_connections_new RENAME TO integration_connections;

CREATE INDEX idx_integration_connections_provider ON integration_connections(provider_id);
