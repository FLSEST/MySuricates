#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

WITH_TOOLS=0
for a in "$@"; do
  case "$a" in
    --with-tools|-t) WITH_TOOLS=1 ;;
  esac
done

section "Dead code and unused surface scan"

# "TODO" is also the domain entity in this repository (TodoService, "TODO anlegen"),
# and comments refer to a tracked TODO via "TODO <8-hex-id>". Therefore only comment
# markers are counted ("// TODO:", "# FIXME", "<!-- HACK"), split into "with TODO id"
# (tracked) and "bare" (untracked). The own directory is excluded: the scanners
# name the markers they search for (TODO 16e172a2).
MARKER_PREFIX='(//|#|/\*|\*|<!--)[[:space:]]*'
TODO_ID='TODO[[:space:]:(]*[0-9a-f]{8}([^0-9a-zA-Z]|$)'
marker_hits() {  # $1 = ERE after the comment prefix
  pgrep -n --exclude-dir=public-readiness --exclude='*.md' "${MARKER_PREFIX}$1" .
}

inventory_section "Comment marker counts (TODO/FIXME/HACK/XXX)"
echo "note: 'TODO' is also a domain entity here; only comment markers are counted, TODO-ID references are tracked in the TODO system"
todo_all=$(marker_hits "TODO([[:space:]]*[:(]|[[:space:]]+[0-9a-f]{8})")
todo_with_id=$(printf '%s\n' "$todo_all" | grep -cE "$TODO_ID" || true)
todo_bare=$(printf '%s\n' "$todo_all" | grep -vE "$TODO_ID" | grep -c . || true)
printf 'TODO_marker_with_todo_id: %s\n' "$todo_with_id"
printf 'TODO_marker_bare: %s\n' "$todo_bare"
for pat in FIXME HACK XXX; do
  printf '%s_marker_bare: %s\n' "$pat" "$(marker_hits "${pat}([^A-Za-z0-9_]|$)" | grep -vcE "$TODO_ID" || true)"
done
printf 'deprecated_marker_count: %s\n' "$(pgrep_count_total deprecated .)"
printf 'TODO_id_references_total: %s\n' "$(pgrep -o --exclude-dir=public-readiness "$TODO_ID" . | wc -l | tr -d ' ')"

inventory_section "Bare comment markers without TODO-ID (top 50)"
bare_list=$( { printf '%s\n' "$todo_all"; marker_hits "(FIXME|HACK|XXX)([^A-Za-z0-9_]|$)"; } \
  | grep -vE "$TODO_ID" | grep . )
if [ -n "$bare_list" ]; then printf '%s\n' "$bare_list" | redact | head -50; else echo "(none)"; fi

inventory_section "Backup/old/generated-looking files"
candidates=$(find . -type f \( -name '*.bak' -o -name '*.old' -o -name '*~' -o -name '*.orig' -o -name '*.tmp' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | wc -l | tr -d ' ')
echo "backup_file_count: $candidates"
find . -type f \( -name '*.bak' -o -name '*.old' -o -name '*~' -o -name '*.orig' -o -name '*.tmp' \) ! -path '*/node_modules/*' ! -path '*/.git/*' | sed 's#^./##' | head -160 | redact

if [ "$WITH_TOOLS" -eq 1 ]; then
  if [ -f ai-automation-frontend/package.json ] && command -v npx >/dev/null; then
    tool_section "depcheck (frontend)" npx
    (cd ai-automation-frontend && npx --yes depcheck --skip-missing=true 2>&1 | redact | head -180) || true
  fi
  if [ -f ai-automation-backend/pom.xml ]; then
    tool_section "Maven dependency:analyze" mvn
    (cd ai-automation-backend && mvn -q dependency:analyze -DskipTests 2>&1 | redact | head -180) || true
  fi
fi

section "Gate status"
if [ "$WITH_TOOLS" -eq 1 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: dead-code-candidates"
  echo "gate_description: Dead-code scan ran with tools (non-deterministic); manual verification required before cleanup."
  echo "todo_item: Review dead-code and unused-surface candidates | LOW | refactoring,cleanup,review | Validate depcheck/Maven/TODO findings and decide safe cleanup scope."
else
  echo "gate_status: GREEN"
  echo "gate_category: dead-code-inventory"
  echo "gate_description: Dead-code marker inventory completed deterministically. Run with --with-tools for depcheck / mvn dependency:analyze."
fi
