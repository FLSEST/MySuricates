CREATE TABLE integration_connections (
    id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'DISCONNECTED',
    access_token_encrypted TEXT,
    refresh_token_encrypted TEXT,
    token_expiry TEXT,
    scopes TEXT,
    account_info TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE INDEX idx_integration_connections_provider ON integration_connections(provider_id);
