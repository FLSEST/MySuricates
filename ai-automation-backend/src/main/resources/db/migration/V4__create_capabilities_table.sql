CREATE TABLE capabilities (
    id TEXT PRIMARY KEY,
    skill_id TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    handler_class TEXT,
    input_schema TEXT,
    output_schema TEXT,
    async INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);
