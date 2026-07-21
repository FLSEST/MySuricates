CREATE TABLE audit_reports (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    workflow_id TEXT NOT NULL,
    workflow_run_id TEXT NOT NULL UNIQUE,
    audit_key TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    markdown_content TEXT,
    gate_status TEXT,
    gate_description TEXT,
    provider TEXT,
    model TEXT,
    workflow_version TEXT,
    ingestion_status TEXT NOT NULL,
    ingestion_error TEXT,
    generated_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_reports_lookup
    ON audit_reports(project_id, audit_key, generated_at DESC);
CREATE INDEX idx_audit_reports_status
    ON audit_reports(ingestion_status, gate_status);

CREATE TABLE audit_findings (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    audit_key TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    rule_key TEXT NOT NULL,
    subject TEXT NOT NULL,
    severity TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    evidence TEXT,
    tags TEXT,
    status TEXT NOT NULL,
    first_seen_report_id TEXT NOT NULL,
    last_seen_report_id TEXT NOT NULL,
    first_seen_run_id TEXT NOT NULL,
    last_seen_run_id TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    regression_count INTEGER NOT NULL DEFAULT 0,
    resolved_at TEXT,
    disposition_note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_audit_finding_fingerprint UNIQUE(project_id, audit_key, fingerprint),
    FOREIGN KEY(first_seen_report_id) REFERENCES audit_reports(id),
    FOREIGN KEY(last_seen_report_id) REFERENCES audit_reports(id)
);

CREATE INDEX idx_audit_findings_current
    ON audit_findings(project_id, audit_key, status, severity);

CREATE TABLE audit_finding_occurrences (
    id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL,
    report_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    evidence TEXT,
    tags TEXT,
    observed_at TEXT NOT NULL,
    CONSTRAINT uq_audit_occurrence UNIQUE(finding_id, report_id),
    FOREIGN KEY(finding_id) REFERENCES audit_findings(id) ON DELETE CASCADE,
    FOREIGN KEY(report_id) REFERENCES audit_reports(id) ON DELETE CASCADE
);

CREATE INDEX idx_audit_occurrences_report ON audit_finding_occurrences(report_id);

CREATE TABLE audit_finding_todos (
    id TEXT PRIMARY KEY,
    finding_id TEXT NOT NULL,
    todo_id TEXT NOT NULL,
    managed INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_audit_finding_todo UNIQUE(finding_id, todo_id),
    FOREIGN KEY(finding_id) REFERENCES audit_findings(id) ON DELETE CASCADE,
    FOREIGN KEY(todo_id) REFERENCES todos(id) ON DELETE CASCADE
);

CREATE INDEX idx_audit_finding_todos_todo ON audit_finding_todos(todo_id);
