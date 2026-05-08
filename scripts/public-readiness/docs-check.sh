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
  count=$(rg -I -i "$pat" README.md docs AGENTS.md CLAUDE.md 2>/dev/null | wc -l | tr -d ' ')
  printf '%s: %s\n' "$pat" "$count"
done
