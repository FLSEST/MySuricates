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

echo "2b) tryout stage (40c31211)"
check "compose builds the runtime stage only"  'grep -q "target: runtime" "$R/deploy/compose.yml"'
check "runtime stage comes before tryout"      '[ "$(grep -n "AS runtime$" "$DF" | cut -d: -f1)" -lt "$(grep -n "AS tryout$" "$DF" | cut -d: -f1)" ]'
check "tryout builds on runtime"               'grep -q "^FROM runtime AS tryout$" "$DF"'
check "tryout brings the AI CLIs"              'grep -q "@anthropic-ai/claude-code" "$DF" && grep -q "@openai/codex" "$DF" && grep -q "@earendil-works/pi-coding-agent" "$DF"'
check "tryout brings pip/venv, JDK, Maven"     'grep -q "python3-venv" "$DF" && grep -q "JAVA_HOME=/opt/java/jdk" "$DF" && grep -q "/usr/local/bin/mvn" "$DF"'
check "tryout home lives on the data volume"   'grep -q "HOST_HOME=/app/data/home" "$DF" && grep -q "TRYOUT_HOME=\"/app/data/home\"" "$R/scripts/tryout.sh"'
check "tryout names itself in the UI"          'grep -q "AUTOMATION_ENVIRONMENT=Tryout" "$DF"'
check "no host mounts in the tryout"           '! grep -q "docker.sock\|/seed/\|\$HOME:" "$R/scripts/tryout.sh"'

echo "3) entrypoint"
check "entrypoint parses"                      'bash -n "$EP"'
check "drops root before starting java"        'grep -q "^exec gosu" "$EP"'
check "claude.json seeded writable"            'grep -q "/seed/.claude.json" "$EP"'
check "own provider homes for the user"        'grep -q "CLAUDE_CONFIG_DIR" "$EP" && grep -q "CODEX_HOME" "$EP" && grep -q "PI_CODING_AGENT_DIR" "$EP"'
check "host login files are never chowned"     'grep -q -- "-path /app/data/providers -prune" "$EP" && ! grep -qE "chown -R .*/app/data" "$EP"'

echo "3b) Stable/Test entrypoint (ai-automation-backend/docker/entrypoint.sh)"
BEP="$R/ai-automation-backend/docker/entrypoint.sh"
check "entrypoint parses"                      'bash -n "$BEP"'
check "own provider homes for the user"        'grep -q "CLAUDE_CONFIG_DIR" "$BEP" && grep -q "CODEX_HOME" "$BEP" && grep -q "PI_CODING_AGENT_DIR" "$BEP"'
check "drops root before starting java"        'grep -q "^exec gosu" "$BEP"'

echo "4) keys never in the container layer (3fb2242c)"
check "deploy: seal key on the secrets volume" 'grep -q "AUTOMATION_SEAL_KEY_FILE=/app/secrets/" "$DF"'
for f in docker-compose.stable.yml docker-compose.test.yml docker-compose.dev-container.yml; do
  C="$R/ai-automation-backend/$f"
  check "$f: both key files on /app/secrets" 'grep -q "AUTOMATION_INTEGRATION_KEY_FILE=/app/secrets/" "$C" && grep -q "AUTOMATION_SEAL_KEY_FILE=/app/secrets/" "$C"'
  check "$f: own secrets volume, declared"   'grep -qE "^      - [a-z-]+-secrets:/app/secrets$" "$C" && grep -qE "^  [a-z-]+-secrets:$" "$C"'
done
check "Dockerfile.stable prepares /app/secrets"  'grep -q "/app/secrets" "$R/ai-automation-backend/docker/Dockerfile.stable"'
check "backend entrypoint hands /app/secrets to the app user" 'grep -q "chown .*/app/secrets" "$BEP" && grep -q "chmod 700 /app/secrets" "$BEP"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
