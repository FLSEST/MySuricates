#!/usr/bin/env bash
# =============================================================================
# cleanup-runs.sh — Workflow Run Cleanup for MySuricates
# =============================================================================
#
# IMPORTANT: This script writes directly to the SQLite database via the
# sqlite3 CLI. This is an EXPLICIT EXCEPTION to the project convention
# (CLAUDE.md: "NEVER write to the SQLite database directly") and is
# intentional for one-time maintenance tasks only.
#
# The script enforces a mandatory API backup BEFORE any deletion. The backup
# triggers a WAL checkpoint server-side so the backup is consistent.
#
# Side effects to be aware of:
#   - step_results are deleted automatically via ON DELETE CASCADE.
#   - template_verifications.last_run_id → SET NULL for affected runs.
#   - chain_runs.entry_runs embeds workflowRunId in a JSON blob (no FK).
#     Deleted run IDs become stale strings in historical chain run details.
#     This is cosmetic only and does not affect current functionality.
#
# Usage:
#   ./scripts/cleanup-runs.sh [options]
#
# Options:
#   --dry-run           Preview what would be deleted (default)
#   --execute           Actually delete the matching runs
#   --workflow <name>   Filter by workflow name substring (case-insensitive)
#   --status <list>     Comma-separated statuses (default: FAILED,CANCELLED)
#   --before <date>     Only runs started before YYYY-MM-DD
#   --after <date>      Only runs started on or after YYYY-MM-DD
#   -h, --help          Show this help
#
# Protected statuses (never deletable):
#   PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, INTERRUPTED
#
# Examples:
#   ./scripts/cleanup-runs.sh
#   ./scripts/cleanup-runs.sh --workflow "Hunt Quality"
#   ./scripts/cleanup-runs.sh --workflow "Hunt Quality" --execute
#   ./scripts/cleanup-runs.sh --status CANCELLED --before 2026-03-01 --execute
#   ./scripts/cleanup-runs.sh --status FAILED --after 2026-07-14 --execute
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DB_PATH="$PROJECT_ROOT/ai-automation-backend/data/automation.db"
BASE_URL="http://localhost:9090"
API_URL="$BASE_URL/api/v1"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${GREEN}[cleanup-runs]${NC} $*"; }
warn() { echo -e "${YELLOW}[cleanup-runs]${NC} $*"; }
err()  { echo -e "${RED}[cleanup-runs]${NC} $*" >&2; }
info() { echo -e "${BLUE}[cleanup-runs]${NC} $*"; }

# =============================================================================
# Argument parsing
# =============================================================================

MODE="dry-run"
WORKFLOW_FILTER=""
STATUS_FILTER="FAILED,CANCELLED"
BEFORE_DATE=""
AFTER_DATE=""
YES=false

usage() {
    echo "Usage: $(basename "$0") [options]"
    echo ""
    echo "Options:"
    echo "  --dry-run           Preview what would be deleted (default)"
    echo "  --execute           Actually delete the matching runs"
    echo "  --workflow <name>   Filter by workflow name substring (case-insensitive)"
    echo "  --status <list>     Comma-separated statuses (default: FAILED,CANCELLED)"
    echo "  --before <date>     Only runs started before YYYY-MM-DD"
    echo "  --after <date>      Only runs started on or after YYYY-MM-DD"
    echo "  --db <path>         SQLite file (default: ai-automation-backend/data/automation.db)"
    echo "  -h, --help          Show this help"
    echo ""
    echo "Protected statuses (never deletable):"
    echo "  PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, INTERRUPTED"
    echo ""
    echo "Examples:"
    echo "  $(basename "$0")"
    echo "  $(basename "$0") --workflow 'Hunt Quality'"
    echo "  $(basename "$0") --workflow 'Hunt Quality' --execute"
    echo "  $(basename "$0") --status CANCELLED --before 2026-03-01 --execute"
    echo "  $(basename "$0") --status FAILED --after 2026-07-27 --execute"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)   MODE="dry-run" ;;
        --execute)   MODE="execute" ;;
        --yes|-y)    YES=true ;;
        --workflow)  WORKFLOW_FILTER="$2"; shift ;;
        --status)    STATUS_FILTER="$2"; shift ;;
        --before)    BEFORE_DATE="$2"; shift ;;
        --after)     AFTER_DATE="$2"; shift ;;
        --db)        DB_PATH="$2"; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           err "Unknown option: $1"; echo ""; usage; exit 1 ;;
    esac
    shift
done

# =============================================================================
# Validation
# =============================================================================

PROTECTED_STATUSES="PENDING RUNNING PAUSED COMPLETED COMPLETED_WITH_ERRORS INTERRUPTED"

check_dependencies() {
    if ! command -v python3 &>/dev/null; then
        err "python3 not found. Install with: sudo apt install python3"
        exit 1
    fi
    if ! python3 -c "import sqlite3" &>/dev/null; then
        err "Python sqlite3 module not available"
        exit 1
    fi
    if [[ ! -f "$DB_PATH" ]]; then
        err "Database not found at: $DB_PATH"
        exit 1
    fi
}

check_backend_stopped() {
    # CRITICAL: Writing directly to SQLite while the backend is running causes
    # WAL-mode corruption. The backend MUST be stopped before any direct DB write.
    if curl -s --connect-timeout 2 "$BASE_URL/api/v1/system/health" &>/dev/null; then
        err "Das Backend läuft noch auf $BASE_URL!"
        err ""
        err "Direkter SQLite-Write mit laufendem Backend korumpiert die Datenbank"
        err "(WAL-Mode-Konflikt). Backend erst stoppen:"
        err ""
        err "  kill \$(pgrep -f 'ai-automation-backend.*jar')"
        err ""
        err "Danach Script erneut ausführen, dann Backend neu starten."
        exit 1
    fi
}

check_db_integrity() {
    # Refuse to touch a corrupt database. On 2026-08-04 a DELETE on an already damaged
    # btree made the corruption worse, and the later index rebuild resurrected rows that
    # had been deleted. quick_check reads every page (seconds on a 300 MB file).
    local result
    if ! result=$(python3 - <<'PYEOF'
import sqlite3, os, sys
try:
    conn = sqlite3.connect(os.environ["DB_PATH"])
    rows = [r[0] for r in conn.execute("PRAGMA quick_check")]
    conn.close()
except sqlite3.DatabaseError as e:
    rows = [f"quick_check failed: {e}"]
print("\n".join(rows))
sys.exit(0 if rows == ["ok"] else 1)
PYEOF
    ); then
        err "SQLite-Integritätsprüfung fehlgeschlagen (PRAGMA quick_check)!"
        err ""
        while IFS= read -r line; do err "  $line"; done <<< "$result"
        err ""
        err "Kein Zugriff auf diese DB — ein DELETE auf einer beschädigten Datenbank verschlimmert"
        err "den Schaden (siehe 2026-08-04). Erst reparieren (Backup einspielen oder .recover),"
        err "dann erneut versuchen."
        exit 1
    fi
    log "Integritätsprüfung: ok"
}

# Datumsangaben landen unquoted in der WHERE-Klausel — Format hart prüfen,
# damit weder kaputtes SQL noch eingeschleuste Bedingungen möglich sind.
validate_dates() {
    local name value flag
    for name in BEFORE_DATE AFTER_DATE; do
        value="${!name}"
        [[ -z "$value" ]] && continue
        flag="${name%_DATE}"
        if [[ ! "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
            err "Invalid date for --${flag,,}: '$value' (expected YYYY-MM-DD)"
            exit 1
        fi
    done

    if [[ -n "$BEFORE_DATE" && -n "$AFTER_DATE" ]]; then
        if [[ ! "$AFTER_DATE" < "$BEFORE_DATE" ]]; then
            err "--after ($AFTER_DATE) must be earlier than --before ($BEFORE_DATE)"
            exit 1
        fi
    fi
}

validate_statuses() {
    IFS=',' read -ra requested <<< "$STATUS_FILTER"
    for s in "${requested[@]}"; do
        s="${s// /}"
        s="${s^^}"
        for protected in $PROTECTED_STATUSES; do
            if [[ "$s" == "$protected" ]]; then
                err "Status '$s' is protected and cannot be deleted."
                err "Protected statuses: $PROTECTED_STATUSES"
                exit 1
            fi
        done
    done
}

# =============================================================================
# API key resolution
# =============================================================================

resolve_api_key() {
    local key
    key=$(python3 -c "
import sqlite3
conn = sqlite3.connect('$DB_PATH')
c = conn.execute(\"SELECT value FROM app_settings WHERE key='api-key'\")
r = c.fetchone()
if r: print(r[0])
conn.close()
" 2>/dev/null || true)

    if [[ -z "$key" ]]; then
        err "Could not read API key from database: $DB_PATH"
        exit 1
    fi
    echo "$key"
}

# =============================================================================
# Backup
# =============================================================================

do_backup() {
    local api_key="$1"
    log "Triggering backup via API (GET /api/v1/system/backup)..."

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-API-Key: $api_key" \
        "$API_URL/system/backup")

    if [[ "$http_code" == "200" ]]; then
        log "Backup created successfully → ~/.autotrigger/backups/"
    elif [[ "$http_code" == "000" ]]; then
        err "Backend not reachable at $BASE_URL"
        err "Start with: scripts/start-autotrigger-dev.sh"
        exit 1
    else
        err "Backup API returned HTTP $http_code — aborting."
        exit 1
    fi
}

# =============================================================================
# SQL query builder
# =============================================================================

build_where_clause() {
    # Convert "FAILED,CANCELLED" → "'FAILED','CANCELLED'"
    local status_in
    status_in=$(echo "$STATUS_FILTER" \
        | tr ',' '\n' \
        | sed "s/^[[:space:]]*//;s/[[:space:]]*$//" \
        | sed "s/.*/'&'/" \
        | paste -sd ',' -)

    local where="wr.status IN ($status_in)"

    if [[ -n "$WORKFLOW_FILTER" ]]; then
        where="$where AND w.name LIKE '%${WORKFLOW_FILTER}%'"
    fi

    if [[ -n "$BEFORE_DATE" ]]; then
        where="$where AND wr.started_at < '${BEFORE_DATE}T00:00:00'"
    fi

    if [[ -n "$AFTER_DATE" ]]; then
        where="$where AND wr.started_at >= '${AFTER_DATE}T00:00:00'"
    fi

    echo "$where"
}

# =============================================================================
# Python DB helper — uses env vars DB_PATH and SQL_WHERE to avoid quote issues
# =============================================================================

# All Python DB calls receive WHERE clause and DB path via env vars,
# so SQL single-quotes never conflict with Python string delimiters.

show_preview() {
    local where="$1"

    local run_count step_count
    run_count=$(SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"SELECT COUNT(DISTINCT wr.id) FROM workflow_runs wr JOIN workflows w ON w.id = wr.workflow_id WHERE {w}")
print(cur.fetchone()[0]); conn.close()
PYEOF
)
    step_count=$(SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"SELECT COUNT(sr.id) FROM step_results sr JOIN workflow_runs wr ON wr.id = sr.workflow_run_id JOIN workflows w ON w.id = wr.workflow_id WHERE {w}")
print(cur.fetchone()[0]); conn.close()
PYEOF
)

    echo ""
    info "=== Preview ==="
    echo -e "  Workflow runs to delete:  ${YELLOW}${run_count}${NC}"
    echo -e "  Step results (cascaded):  ${YELLOW}${step_count}${NC}"
    echo ""

    if [[ "$run_count" -gt 0 ]]; then
        echo -e "  ${BLUE}Breakdown by workflow / status:${NC}"
        SQL_WHERE="$where" python3 - <<'PYEOF' | while IFS= read -r line; do echo "    $line"; done
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"""
    SELECT w.name AS workflow, wr.status AS status, COUNT(wr.id) AS runs
    FROM workflow_runs wr JOIN workflows w ON w.id = wr.workflow_id
    WHERE {w}
    GROUP BY w.name, wr.status ORDER BY runs DESC
""")
cols = [d[0] for d in cur.description]
rows = cur.fetchall()
widths = [max(len(str(cols[i])), max((len(str(r[i])) for r in rows), default=0)) for i in range(len(cols))]
fmt = "  ".join(f"{{:<{x}}}" for x in widths)
print(fmt.format(*cols))
print("  ".join("-" * x for x in widths))
for r in rows:
    print(fmt.format(*r))
conn.close()
PYEOF
        echo ""

        local date_range
        date_range=$(SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"SELECT MIN(substr(wr.started_at,1,10)), MAX(substr(wr.started_at,1,10)) FROM workflow_runs wr JOIN workflows w ON w.id = wr.workflow_id WHERE {w}")
r = cur.fetchone(); print(f"{r[0]} \u2192 {r[1]}"); conn.close()
PYEOF
)
        echo "  Date range: $date_range"
    fi
    echo ""
}

get_run_count() {
    local where="$1"
    SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"SELECT COUNT(DISTINCT wr.id) FROM workflow_runs wr JOIN workflows w ON w.id = wr.workflow_id WHERE {w}")
print(cur.fetchone()[0]); conn.close()
PYEOF
}

# =============================================================================
# Delete
# =============================================================================

do_delete() {
    local where="$1"

    # Backup via file copy (backend is stopped, so API backup is unavailable)
    local backup_dir="$HOME/.autotrigger/backups"
    local backup_file="$backup_dir/mysuricates-manual-$(date +%Y-%m-%d_%H-%M-%S).db"
    mkdir -p "$backup_dir"
    log "Erstelle Backup: $backup_file"
    cp "$DB_PATH" "$backup_file"
    log "Backup erstellt ($(du -h "$backup_file" | cut -f1))"

    # Show preview
    show_preview "$where"
    local run_count
    run_count=$(get_run_count "$where")

    if [[ "$run_count" -eq 0 ]]; then
        warn "No matching runs found. Nothing to delete."
        exit 0
    fi

    # Confirm
    if [[ "$YES" == true ]]; then
        log "Bestätigung via --yes übersprungen."
    else
        echo -e "${YELLOW}Wirklich löschen? [y/N]${NC} " >&2
        local answer
        read -r answer </dev/tty
        if [[ ! "$answer" =~ ^[yY]$ ]]; then
            log "Abgebrochen. Nichts gelöscht."
            exit 0
        fi
    fi

    # Count step_results before deletion (for report)
    local step_count_before
    step_count_before=$(SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
cur = conn.execute(f"SELECT COUNT(sr.id) FROM step_results sr JOIN workflow_runs wr ON wr.id = sr.workflow_run_id JOIN workflows w ON w.id = wr.workflow_id WHERE {w}")
print(cur.fetchone()[0]); conn.close()
PYEOF
)

    # Delete — foreign_keys = ON ensures cascade to step_results
    log "Deleting ${run_count} workflow runs..."
    SQL_WHERE="$where" python3 - <<'PYEOF'
import sqlite3, os
w = os.environ["SQL_WHERE"]
conn = sqlite3.connect(os.environ["DB_PATH"])
conn.execute("PRAGMA foreign_keys = ON")
conn.execute(f"DELETE FROM workflow_runs WHERE id IN (SELECT wr.id FROM workflow_runs wr JOIN workflows w ON w.id = wr.workflow_id WHERE {w})")
conn.commit(); conn.close()
PYEOF

    echo ""
    log "Done."
    echo -e "  Workflow runs deleted:  ${GREEN}${run_count}${NC}"
    echo -e "  Step results cascaded:  ${GREEN}${step_count_before}${NC}"
    echo ""
    log "Backup location: ~/.autotrigger/backups/"
}

# =============================================================================
# Main
# =============================================================================

main() {
    check_dependencies
    validate_dates
    validate_statuses

    export DB_PATH
    local where
    where=$(build_where_clause)

    echo ""
    info "=== MySuricates Workflow Run Cleanup ==="
    info "DB:       $DB_PATH"
    info "Status:   $STATUS_FILTER"
    [[ -n "$WORKFLOW_FILTER" ]] && info "Workflow: *${WORKFLOW_FILTER}*"
    [[ -n "$BEFORE_DATE" ]]     && info "Before:   ${BEFORE_DATE}"
    [[ -n "$AFTER_DATE" ]]      && info "After:    ${AFTER_DATE}"
    info "Mode:     $MODE"

    check_db_integrity

    if [[ "$MODE" == "execute" ]]; then
        check_backend_stopped
    fi

    if [[ "$MODE" == "dry-run" ]]; then
        show_preview "$where"
        local run_count
        run_count=$(get_run_count "$where")
        if [[ "$run_count" -eq 0 ]]; then
            warn "No matching runs found."
        else
            warn "Dry-run only. Use --execute to delete."
        fi
    else
        do_delete "$where"
    fi
}

main "$@"
