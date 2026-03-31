-- Onboarding profiles for AI project integration
CREATE TABLE onboarding_profiles (
    id TEXT PRIMARY KEY,
    key TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT,
    content TEXT NOT NULL,
    type TEXT NOT NULL,
    built_in INTEGER NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE onboarding_profile_history (
    id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    changed_by TEXT NOT NULL,
    changed_at TEXT NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES onboarding_profiles(id) ON DELETE CASCADE
);

CREATE INDEX idx_onboarding_profile_history_profile_id ON onboarding_profile_history(profile_id);
