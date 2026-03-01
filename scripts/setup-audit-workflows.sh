#!/usr/bin/env bash
set -euo pipefail

# Setup script for 6 analysis/audit workflows with weekly schedules.
# Creates workflows from built-in templates and assigns staggered Monday schedules.
# Idempotent: skips workflows/schedules that already exist.

BASE_URL="${AUTOPILOT_URL:-http://localhost:9090}/api/v1"
API_KEY="${AUTOPILOT_API_KEY:-}"
PROJECT_ID="ebf4f50c-20c2-4c35-a84a-a0b350124523"  # AutoPilot project

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output (all logs go to stderr)
info()  { echo -e "\033[0;34m[INFO]\033[0m  $*" >&2; }
ok()    { echo -e "\033[0;32m[OK]\033[0m    $*" >&2; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*" >&2; }
error() { echo -e "\033[0;31m[ERROR]\033[0m $*" >&2; }

# Authenticated curl wrapper
acurl() {
  if [[ -n "$API_KEY" ]]; then
    curl -sf -H "X-API-Key: ${API_KEY}" "$@"
  else
    curl -sf "$@"
  fi
}

# Check API is reachable and resolve API key
check_api() {
  if ! curl -sf "${BASE_URL}/system/health" > /dev/null 2>&1; then
    error "AutoPilot API not reachable at ${BASE_URL}"
    error "Start the backend first: cd ai-automation-backend && mvn spring-boot:run"
    exit 1
  fi
  ok "API reachable at ${BASE_URL}"

  # Auto-detect API key from database if not provided
  if [[ -z "$API_KEY" ]]; then
    for db in \
      "${SCRIPT_DIR}/../ai-automation-backend/data/automation.db" \
      "./ai-automation-backend/data/automation.db" \
      "./data/automation.db"; do
      if [[ -f "$db" ]]; then
        API_KEY=$(python3 -c "
import sqlite3
conn = sqlite3.connect('$db')
c = conn.execute(\"SELECT value FROM app_settings WHERE key='api-key'\")
r = c.fetchone()
if r: print(r[0])
conn.close()
" 2>/dev/null || true)
        if [[ -n "$API_KEY" ]]; then
          ok "Auto-detected API key from ${db}"
          break
        fi
      fi
    done
  fi

  # Verify API key works
  if [[ -n "$API_KEY" ]]; then
    if ! acurl "${BASE_URL}/projects" > /dev/null 2>&1; then
      error "API key authentication failed"
      exit 1
    fi
    ok "API key authenticated"
  else
    warn "No API key found — requests may fail if security is enabled"
  fi
}

# Check if a workflow with the given name already exists in the project
workflow_exists() {
  local name="$1"
  local existing
  existing=$(acurl "${BASE_URL}/workflows?projectId=${PROJECT_ID}" \
    | jq -r --arg n "$name" '.[] | select(.name == $n) | .id' 2>/dev/null || true)
  if [[ -n "$existing" ]]; then
    echo "$existing"
    return 0
  fi
  return 1
}

# Create a workflow from a template, returns the workflow ID
create_workflow_from_template() {
  local template_id="$1"
  local name="$2"
  local description="$3"
  local icon="$4"

  # Check if workflow already exists
  local existing_id
  if existing_id=$(workflow_exists "$name"); then
    warn "Workflow '${name}' already exists (${existing_id})"
    echo "$existing_id"
    return
  fi

  # Fetch template to get blocks
  local template
  template=$(acurl "${BASE_URL}/workflow-templates/${template_id}")
  if [[ -z "$template" || "$template" == "null" ]]; then
    error "Template '${template_id}' not found"
    return 1
  fi

  local blocks
  blocks=$(echo "$template" | jq '.blocks')
  local model
  model=$(echo "$template" | jq -r '.model // empty')
  local instructions
  instructions=$(echo "$template" | jq -r '.instructions // empty')

  # Build workflow payload
  local payload
  payload=$(jq -n \
    --arg name "$name" \
    --arg desc "$description" \
    --arg icon "$icon" \
    --arg pid "$PROJECT_ID" \
    --arg tid "$template_id" \
    --arg model "$model" \
    --arg instructions "$instructions" \
    --argjson blocks "$blocks" \
    '{
      name: $name,
      description: $desc,
      icon: $icon,
      projectId: $pid,
      templateId: $tid,
      blocks: $blocks
    }
    | if $model != "" then . + {model: $model} else . end
    | if $instructions != "" then . + {instructions: $instructions} else . end')

  local response
  response=$(acurl -X POST "${BASE_URL}/workflows" \
    -H "Content-Type: application/json" \
    -d "$payload")

  local id
  id=$(echo "$response" | jq -r '.id')
  if [[ -z "$id" || "$id" == "null" ]]; then
    error "Failed to create workflow '${name}'"
    return 1
  fi
  ok "Created workflow '${name}' (${id})"
  echo "$id"
}

# Create schedule for a workflow (idempotent — checks description for duplicate)
create_schedule() {
  local workflow_id="$1" cron="$2" description="$3"

  # Check if schedule already exists
  local existing
  existing=$(acurl "${BASE_URL}/schedules" \
    | jq -r --arg wid "$workflow_id" '.[] | select(.workflowId == $wid) | .id' 2>/dev/null || true)
  if [[ -n "$existing" ]]; then
    warn "Schedule for workflow ${workflow_id} already exists (${existing})"
    return
  fi

  local response
  response=$(acurl -X POST "${BASE_URL}/schedules" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg wid "$workflow_id" --arg c "$cron" --arg d "$description" \
      '{workflowId: $wid, cronExpression: $c, description: $d, scheduleType: "CRON"}')")

  local id
  id=$(echo "$response" | jq -r '.id')
  ok "Created schedule '${description}' (${id})"
}

# ============================================================================
# Template → Workflow mapping
# ============================================================================

declare -A TEMPLATES=(
  ["api-consistency-audit"]="API-Konsistenz-Audit|REST-APIs auf Namenskonventionen, Pagination, DTOs und Fehlerbehandlung pruefen|Search"
  ["dead-code-detector"]="Dead-Code-Detektor|Ungenutzten Code finden: verwaiste Exports, tote Dateien, ungenutzte Dependencies|Skull"
  ["doc-accuracy-check"]="Dokumentations-Genauigkeits-Check|Dokumentation gegen Code verifizieren: Build-Befehle, Struktur, APIs, Versionen|FileCheck"
  ["codebase-health-scorecard"]="Codebase-Gesundheits-Scorecard|Ganzheitliche Bewertung: Tests, Komplexitaet, Fehlerbehandlung, Security, Tech Debt|HeartPulse"
  ["architecture-debt-radar"]="Architektur-Schulden-Radar|God Classes, zirkulaere Abhaengigkeiten, Schichtverletzungen aufdecken|Building2"
  ["test-coverage-gaps"]="Test-Abdeckungs-Luecken|Ungetestete Klassen identifizieren, Coverage-Ratio berechnen, Prioritaeten setzen|TestTube"
)

# Staggered Monday schedule (Spring 6-field cron)
declare -A SCHEDULES=(
  ["api-consistency-audit"]="0 0 9 ? * MON|API-Konsistenz-Audit - Montags 09:00"
  ["dead-code-detector"]="0 20 9 ? * MON|Dead-Code-Detektor - Montags 09:20"
  ["doc-accuracy-check"]="0 40 9 ? * MON|Doku-Genauigkeits-Check - Montags 09:40"
  ["codebase-health-scorecard"]="0 0 10 ? * MON|Codebase-Health-Scorecard - Montags 10:00"
  ["architecture-debt-radar"]="0 20 10 ? * MON|Architektur-Schulden-Radar - Montags 10:20"
  ["test-coverage-gaps"]="0 40 10 ? * MON|Test-Coverage-Gaps - Montags 10:40"
)

# Ordered list for deterministic execution
TEMPLATE_ORDER=(
  "api-consistency-audit"
  "dead-code-detector"
  "doc-accuracy-check"
  "codebase-health-scorecard"
  "architecture-debt-radar"
  "test-coverage-gaps"
)

# ============================================================================
# Main execution
# ============================================================================
main() {
  info "=== Audit Workflow Setup ==="
  info "Creating 6 audit workflows + weekly schedules in AutoPilot project"
  echo >&2

  check_api

  # Verify project exists
  local project_name
  project_name=$(acurl "${BASE_URL}/projects/${PROJECT_ID}" | jq -r '.name' 2>/dev/null || true)
  if [[ -z "$project_name" || "$project_name" == "null" ]]; then
    error "AutoPilot project not found (${PROJECT_ID})"
    error "Run scripts/seed-workflows.sh first to create the project"
    exit 1
  fi
  ok "Target project: ${project_name} (${PROJECT_ID})"
  echo >&2

  # --- Create Workflows ---
  info "--- Creating Workflows from Templates ---"
  declare -A WORKFLOW_IDS

  for tid in "${TEMPLATE_ORDER[@]}"; do
    IFS='|' read -r name desc icon <<< "${TEMPLATES[$tid]}"
    WORKFLOW_IDS[$tid]=$(create_workflow_from_template "$tid" "$name" "$desc" "$icon")
  done
  echo >&2

  # --- Create Schedules ---
  info "--- Creating Weekly Schedules (Montags, gestaffelt) ---"

  for tid in "${TEMPLATE_ORDER[@]}"; do
    IFS='|' read -r cron desc <<< "${SCHEDULES[$tid]}"
    wid="${WORKFLOW_IDS[$tid]}"
    if [[ -n "$wid" ]]; then
      create_schedule "$wid" "$cron" "$desc"
    fi
  done
  echo >&2

  # --- Summary ---
  info "=== Summary ==="
  ok "6 Audit Workflows created (or already existed)"
  ok "6 Weekly Schedules created (Mondays 09:00-10:40, staggered)"
  echo >&2
  info "Schedule overview:"
  info "  09:00  API-Konsistenz-Audit"
  info "  09:20  Dead-Code-Detektor"
  info "  09:40  Dokumentations-Genauigkeits-Check"
  info "  10:00  Codebase-Gesundheits-Scorecard"
  info "  10:20  Architektur-Schulden-Radar"
  info "  10:40  Test-Abdeckungs-Luecken"
}

main "$@"
