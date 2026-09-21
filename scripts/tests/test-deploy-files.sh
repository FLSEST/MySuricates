#!/usr/bin/env bash
# Static checks for deploy/ (image for one instance, only Docker needed). The real build runs in
# the installer probe; this catches the regressions that would make it slow, leaky or root-owned.
# Run: bash scripts/tests/test-deploy-files.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
IGNORE="$R/.dockerignore"; DF="$R/deploy/Dockerfile"; EP="$R/deploy/entrypoint.sh"

echo "1) build context"
check "dockerignore starts with deny-all"      '[ "$(grep -v "^#" "$IGNORE" | grep -v "^$" | head -1)" = "*" ]'
check "worktrees never in the context"         '! grep -q "^!\.claude" "$IGNORE"'
check "local instance config never in image"   '! grep -q "^!ai-automation-backend/config" "$IGNORE" && ! grep -q "^!ai-automation-backend$" "$IGNORE"'
check "frontend node_modules excluded"         'grep -q "^ai-automation-frontend/node_modules$" "$IGNORE"'

echo "2) image"
check "user id at runtime, not at build"       '! grep -q "ARG HOST_UID" "$DF"'
check "backend serves the frontend"            'grep -q "AUTOMATION_SERVEFRONTEND=true" "$DF"'
check "integration key on its own volume path" 'grep -q "AUTOMATION_INTEGRATION_KEYFILE=/app/secrets/" "$DF"'
check "state paths all under /app/data"        'grep -q "AUTOMATION_DB_PATH=/app/data/" "$DF" && grep -q "AUTOMATION_BACKUP_DIR=/app/data/" "$DF"'
check "no host-built jar is copied"            '! grep -q "COPY target/" "$DF"'

echo "3) entrypoint"
check "entrypoint parses"                      'bash -n "$EP"'
check "drops root before starting java"        'grep -q "^exec gosu" "$EP"'
check "claude.json seeded writable"            'grep -q "/seed/.claude.json" "$EP"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
