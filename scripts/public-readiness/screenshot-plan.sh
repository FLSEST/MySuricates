#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

# Produces a Demo-Tour Storyboard, not just a page inventory.
# A storyboard step contains: route, purpose, demo-data path, redaction targets, capture order.

section "Screenshot storyboard"

inventory_section "Detected frontend routes"
ROUTES_FILE=""
if [ -f ai-automation-frontend/src/App.tsx ]; then
  ROUTES_FILE=ai-automation-frontend/src/App.tsx
  grep -hE "path:[[:space:]]*['\"][^'\"]+['\"]" "$ROUTES_FILE" 2>/dev/null \
    | sed -nE "s/.*path:[[:space:]]*['\"]([^'\"]+)['\"].*/\1/p" \
    | sort -u | head -100 | redact
fi

inventory_section "Demo-data fixtures present"
fixture_dirs=$(find . -maxdepth 5 -type d \( -iname '*fixture*' -o -iname '*seed*' -o -iname '*mock*' -o -iname '*demo*' \) ! -path '*/node_modules/*' ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/.git/*' 2>/dev/null | sed 's#^./##' | head -40)
if [ -n "$fixture_dirs" ]; then
  echo "$fixture_dirs"
  fixture_count=$(echo "$fixture_dirs" | wc -l | tr -d ' ')
else
  fixture_count=0
fi
echo "fixture_directory_count: $fixture_count"

# Storyboard sequence — curated demo tour. Order matters: build a coherent story.
section "Demo-Tour Sequence"
cat <<'STORYBOARD'
01. Dashboard / overview
    route: /
    purpose: Establish what the app is at a glance — projects, recent runs, gate-status banner
    redaction: workflow-run-ids, project names if internal, user email
    demo-data: seed two projects with one healthy run each, ensure gate banner shows GREEN

02. Workflows list
    route: /workflows
    purpose: Show breadth of available workflows (count, naming, tags)
    redaction: any PRD-internal naming, customer-specific workflow titles
    demo-data: keep 5–8 representative workflows; hide drafts

03. Workflow detail (canvas)
    route: /workflows/:id
    purpose: Demonstrate block composition (shell + skill + agentic + approval)
    redaction: api-keys in block config, internal URLs, secrets in env-var lists
    demo-data: pick a workflow with mixed block types (e.g. a Public-Readiness PRD-01)

04. Workflow run / execution detail
    route: /workflows/:id/run/:runId
    purpose: Show live block execution, gate-status banner, output panels
    redaction: full output text (use redact() helper or seed clean run), process IDs, stack traces
    demo-data: seed a completed run with deterministic output (no live mvn/npm noise)

05. Chains list
    route: /chains
    purpose: Show that chains compose workflows
    redaction: chain names referencing internal customers
    demo-data: keep 2–3 chains, ideally including a Public-Readiness chain (A/B/C)

06. Chain run
    route: /chains/:id/run/:runId
    purpose: Show approval-gate UX (human-in-the-loop)
    redaction: approver email, free-text approval reason
    demo-data: seed a run paused at an approval step

07. Wishes
    route: /wishes
    purpose: Show self-improvement loop entry point (intent → decompose → execute)
    redaction: any wish text containing PII or product names
    demo-data: 1–2 wishes in different states

08. Factory
    route: /factory
    purpose: Show generated/promoted workflow templates
    redaction: internal template names
    demo-data: empty or 1–2 promoted templates

09. Projects list
    route: /projects
    purpose: Multi-project overview
    redaction: project names if internal
    demo-data: 2–3 named projects, archived ones hidden

10. Settings (subset)
    route: /settings (or wherever wired)
    purpose: Show configurability without exposing secrets
    redaction: api-key, model credentials, integration tokens — all MUST be masked
    demo-data: show shape only, never real values
STORYBOARD

inventory_section "Storyboard checks"
if [ "$fixture_count" -eq 0 ]; then
  echo "demo_data_status: missing — no fixture/seed directories detected"
  STORYBOARD_GAP=1
else
  echo "demo_data_status: candidates_present"
  STORYBOARD_GAP=0
fi

if [ -z "$ROUTES_FILE" ]; then
  echo "routes_source: missing"
  STORYBOARD_GAP=1
fi

inventory_section "Existing screenshots (filename inventory)"
shot_count=$(find . -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.webp' -o -iname '*.gif' \) ! -path '*/node_modules/*' ! -path '*/data/*' ! -path '*/.git/*' 2>/dev/null | wc -l | tr -d ' ')
echo "screenshot_file_count: $shot_count"
find . -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.webp' -o -iname '*.gif' \) ! -path '*/node_modules/*' ! -path '*/data/*' ! -path '*/.git/*' 2>/dev/null | sed 's#^./##' | sort | head -40 | redact || true

section "Gate status"
if [ "$STORYBOARD_GAP" -eq 1 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: screenshot-storyboard"
  echo "gate_description: Storyboard sequence defined, but demo-data or routes source missing — capture is not yet reproducible."
  echo "todo_item: Provide demo-data seed for screenshot tour | MEDIUM | documentation,ux,public-readiness | Create fixture/seed directory with deterministic projects, workflows, runs to back the 10-step demo tour."
else
  echo "gate_status: GREEN"
  echo "gate_category: screenshot-storyboard"
  echo "gate_description: Storyboard sequence defined and demo-data candidates present; verify each step against the redaction list before capture."
  echo "todo_item: Walk through 10-step storyboard, capture and redact | MEDIUM | documentation,ux,public-readiness | Execute capture per step, apply redaction list, store under screenshots/raw and promote curated subset."
fi
