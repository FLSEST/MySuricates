#!/usr/bin/env bash
# MySuricates / AutoPilot seed script — template
#
# Copy this file to `seed-workflows.sh` and adapt it to your own projects.
# The real seed-workflows.sh is gitignored so personal project paths don't leak.
#
# Usage:
#   cp scripts/seed-workflows.example.sh scripts/seed-workflows.sh
#   chmod +x scripts/seed-workflows.sh
#   AUTOPILOT_API_KEY=... ./scripts/seed-workflows.sh
#
# Environment variables:
#   AUTOPILOT_URL      — base URL (default: http://localhost:9090)
#   AUTOPILOT_API_KEY  — API key from Settings UI (required)

set -euo pipefail

BASE_URL="${AUTOPILOT_URL:-http://localhost:9090}/api/v1"
API_KEY="${AUTOPILOT_API_KEY:-}"

info()  { echo -e "\033[0;34m[INFO]\033[0m  $*" >&2; }
ok()    { echo -e "\033[0;32m[OK]\033[0m    $*" >&2; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*" >&2; }
error() { echo -e "\033[0;31m[ERROR]\033[0m $*" >&2; }

acurl() {
  if [[ -n "$API_KEY" ]]; then
    curl -sf -H "X-API-Key: ${API_KEY}" "$@"
  else
    curl -sf "$@"
  fi
}

check_api() {
  if ! curl -sf "${BASE_URL}/system/health" > /dev/null 2>&1; then
    error "AutoPilot API not reachable at ${BASE_URL}"
    error "Start the backend first: cd ai-automation-backend && mvn spring-boot:run"
    exit 1
  fi
  if [[ -z "$API_KEY" ]]; then
    error "AUTOPILOT_API_KEY not set. Read it from Settings → API Key."
    exit 1
  fi
  ok "API reachable, key present"
}

# Create or reuse a project. Echoes the project id to stdout.
create_project() {
  local name="$1" type="$2" path="$3"
  local existing_id
  existing_id=$(acurl "${BASE_URL}/projects" | jq -r --arg n "$name" '.[] | select(.name == $n) | .id' 2>/dev/null || true)
  if [[ -n "$existing_id" ]]; then
    warn "Project '${name}' already exists (${existing_id})"
    echo "$existing_id"
    return
  fi
  local response
  response=$(acurl -X POST "${BASE_URL}/projects" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg n "$name" --arg t "$type" --arg p "$path" '{name: $n, type: $t, path: $p}')")
  local id
  id=$(echo "$response" | jq -r '.id')
  ok "Created project '${name}' (${id})"
  echo "$id"
}

# Create a workflow from a JSON file. The projectId is injected automatically.
create_workflow() {
  local project_id="$1" json_file="$2"
  local payload
  payload=$(jq --arg pid "$project_id" '. + {projectId: $pid}' "$json_file")
  local response
  response=$(acurl -X POST "${BASE_URL}/workflows" \
    -H "Content-Type: application/json" \
    -d "$payload")
  local id name
  id=$(echo "$response" | jq -r '.id')
  name=$(echo "$response" | jq -r '.name')
  ok "Created workflow '${name}' (${id})"
  echo "$id"
}

create_schedule() {
  local workflow_id="$1" cron="$2" description="$3"
  acurl -X POST "${BASE_URL}/schedules" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg wid "$workflow_id" --arg c "$cron" --arg d "$description" \
      '{workflowId: $wid, cronExpression: $c, description: $d, scheduleType: "CRON"}')" > /dev/null
  ok "Created schedule '${description}'"
}

# ---------------------------------------------------------------------------
# Example seed
# ---------------------------------------------------------------------------

main() {
  check_api

  local PROJ_DEMO
  PROJ_DEMO=$(create_project "Demo" "CODE" "/path/to/your/project")

  local tmp
  tmp=$(mktemp)
  trap 'rm -f "$tmp"' EXIT

  cat > "$tmp" <<'WORKFLOW'
{
  "name": "Hello World",
  "description": "Minimal seed workflow — prints a greeting.",
  "icon": "Sparkles",
  "blocks": [
    {
      "type": "shell",
      "config": { "command": "echo 'Hello from AutoPilot'" }
    }
  ]
}
WORKFLOW

  local WF_ID
  WF_ID=$(create_workflow "$PROJ_DEMO" "$tmp")
  create_schedule "$WF_ID" "0 9 * * *" "Daily 09:00 hello"

  ok "Done."
}

main "$@"
