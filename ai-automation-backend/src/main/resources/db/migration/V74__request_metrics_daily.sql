-- Tagesaggregat der Request-Kosten je Route (TODO 5d1c3b2a).
-- Eine Zeile je Tag und Route; die Messung selbst laeuft im Speicher (RequestMetricsCollector),
-- hier landet nur der Tagesabschluss. Grundlage fuer den Drift-Watchdog und das Dashboard-Widget.
CREATE TABLE IF NOT EXISTS request_metrics_daily (
    id                  TEXT PRIMARY KEY,
    day                 TEXT    NOT NULL,
    route               TEXT    NOT NULL,
    calls               INTEGER NOT NULL,
    p50_ms              INTEGER NOT NULL,
    p95_ms              INTEGER NOT NULL,
    max_ms              INTEGER NOT NULL,
    p95_wait_ms         INTEGER NOT NULL,
    wait_share          REAL    NOT NULL,
    max_sql_statements  INTEGER NOT NULL,
    avg_sql_statements  REAL    NOT NULL,
    max_response_bytes  INTEGER NOT NULL,
    avg_response_bytes  INTEGER NOT NULL,
    created_at          TEXT    NOT NULL
);

-- Ein Tagesabschluss je Route: der Snapshot-Job ist damit wiederholbar, ohne Dubletten zu erzeugen.
CREATE UNIQUE INDEX IF NOT EXISTS idx_request_metrics_daily_day_route
    ON request_metrics_daily(day, route);

-- Fuer die Baseline-Abfrage des Watchdogs (letzte N Tage einer Route) und die Retention.
CREATE INDEX IF NOT EXISTS idx_request_metrics_daily_day ON request_metrics_daily(day);
