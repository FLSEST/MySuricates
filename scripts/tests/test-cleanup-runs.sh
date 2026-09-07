#!/usr/bin/env bash
# Tests for scripts/cleanup-runs.sh — the SQLite integrity gate (TODO b423c59d).
# Run: bash scripts/tests/test-cleanup-runs.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../cleanup-runs.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

make_db() {  # $1 = path — minimal schema the script's queries need, 400 FAILED runs
python3 - "$1" <<'PY'
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
conn.execute("PRAGMA page_size = 4096")
conn.executescript("""
CREATE TABLE workflows (id TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE workflow_runs (id TEXT PRIMARY KEY, workflow_id TEXT NOT NULL REFERENCES workflows(id),
                            status TEXT NOT NULL, started_at TEXT);
CREATE TABLE step_results (id TEXT PRIMARY KEY,
                           workflow_run_id TEXT NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
                           output TEXT);
INSERT INTO workflows VALUES ('wf-1', 'Demo Workflow');
""")
for i in range(400):
    conn.execute("INSERT INTO workflow_runs VALUES (?, 'wf-1', 'FAILED', '2026-01-01T00:00:00')", (f"run-{i}",))
    conn.execute("INSERT INTO step_results VALUES (?, ?, ?)", (f"step-{i}", f"run-{i}", "x" * 500))
conn.commit(); conn.close()
PY
}

echo "1) intact database"
make_db "$TMP/ok.db"
OUT=$("$SCRIPT" --dry-run --db "$TMP/ok.db" 2>&1); RC=$?
check "exit code 0"                      '[ "$RC" -eq 0 ]'
check "integrity check reported ok"     'grep -q "Integritätsprüfung: ok" <<<"$OUT"'
check "preview shows the 400 runs"       'grep -q "400" <<<"$OUT"'

echo "2) corrupt database (page 2 overwritten)"
make_db "$TMP/bad.db"
python3 - "$TMP/bad.db" <<'PY'
import sys
with open(sys.argv[1], "r+b") as f:
    f.seek(4096); f.write(b"\xff" * 4096)   # smash the first table's root page
PY
OUT=$("$SCRIPT" --dry-run --db "$TMP/bad.db" 2>&1); RC=$?
check "exit code 1"                      '[ "$RC" -eq 1 ]'
check "quick_check named in the message" 'grep -q "quick_check" <<<"$OUT"'
check "no preview on a corrupt db"       '! grep -q "=== Preview ===" <<<"$OUT"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
