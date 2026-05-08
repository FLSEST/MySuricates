#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Documentation readiness check"
for f in README.md LICENSE SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md docs/architecture.md docs/agentic-development.md docs/public-readiness.md docs/security.md docs/use-cases.md; do
  [ -e "$f" ] && echo "present: $f" || echo "missing: $f"
done
section "Existing docs inventory"
find docs -maxdepth 2 -type f 2>/dev/null | sort | head -220 | redact || true
section "Public narrative keywords in docs (counts)"
for pat in 'agentic|agentisch' 'human[- ]in[- ]the[- ]loop|human review|mensch' 'security|secret|redact' 'test|quality|lint|build' 'living lab|exploration|research'; do
  count=$(pgrep_count_total -i "$pat" README.md docs AGENTS.md CLAUDE.md)
  printf '%s: %s\n' "$pat" "$count"
done
section "Gate status"
missing=0
for f in LICENSE SECURITY.md CONTRIBUTING.md docs/public-readiness.md docs/security.md; do [ -e "$f" ] || missing=$((missing+1)); done
if [ "$missing" -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: documentation-gaps"
  echo "gate_description: Public-facing documentation gaps exist; create reviewed docs before launch."
  echo "todo_item: Create reviewed public-readiness documentation skeleton | MEDIUM | documentation,public-readiness | Missing public-facing docs such as LICENSE/SECURITY/CONTRIBUTING or public-readiness notes."
else
  echo "gate_status: GREEN"
  echo "gate_category: documentation-gaps"
  echo "gate_description: Core public-facing documentation markers exist."
fi
