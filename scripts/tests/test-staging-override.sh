#!/usr/bin/env bash
# Tests for scripts/staging.sh — Stable/Test get what comes from the host through the same generator as
# every other container way (scripts/lib/host-mounts.sh, 262948a4). Without Docker: stubs log calls.
# Run: bash scripts/tests/test-staging-override.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

# A copy of the layout staging.sh derives its paths from, so the real checkout is never touched
# (stable-frontend/, target/, git tags, images).
REPO="$TMP/repo"
mkdir -p "$REPO/scripts/lib" "$REPO/ai-automation-backend/stable-frontend" "$REPO/ai-automation-frontend/dist" \
         "$TMP/bin" "$TMP/home" "$TMP/config/mysuricates" "$TMP/state" "$TMP/nm"
cp "$R/scripts/staging.sh" "$REPO/scripts/"
cp "$R/scripts/lib/host-mounts.sh" "$R/scripts/lib/provider-login.sh" "$REPO/scripts/lib/"
cp "$R/ai-automation-backend/docker-compose.stable.yml" "$R/ai-automation-backend/docker-compose.test.yml" "$REPO/ai-automation-backend/"
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_LOG"
case "$*" in
  ps\ *) [ -n "${RUNNING:-}" ] && echo abc123 ;;
  *" printenv MYSURICATES_AI_FROM_HOST") echo "${MODE:-login}" ;;
  *" printenv APP_USER") echo flo ;;
esac
exit 0
STUB
for t in curl mvn npm git; do printf '#!/bin/sh\nexit 0\n' > "$TMP/bin/$t"; done
chmod +x "$TMP/bin/"*
export HOME="$TMP/home" XDG_CONFIG_HOME="$TMP/config" XDG_STATE_HOME="$TMP/state" PATH="$TMP/bin:$PATH" \
       DOCKER_LOG="$TMP/docker.log" HOST_MOUNTS_ASSUME_SOCKET=1 HOST_MOUNTS_NODE_MODULES="$TMP/nm"
unset STABLE_AI_FROM_HOST STABLE_AI_PROVIDERS STABLE_DOCKER_SOCKET TEST_AI_FROM_HOST TEST_AI_PROVIDERS TEST_DOCKER_SOCKET MYSURICATES_STAGING_ENV
S="$REPO/scripts/staging.sh"; OV="$TMP/state/mysuricates/staging"; ENVF="$TMP/config/mysuricates/staging.env"
STABLE_YML="$REPO/ai-automation-backend/docker-compose.stable.yml"; TEST_YML="$REPO/ai-automation-backend/docker-compose.test.yml"

echo "1) write-override"
OUT="$(bash "$S" write-override stable 2>/dev/null)"
check "prints only the path (the backend reads it)" '[ "$OUT" = "$OV/stable.override.yml" ]'
check "service stable, default login"               'grep -q "^  stable:" "$OUT" && grep -q "MYSURICATES_AI_FROM_HOST=login" "$OUT"'
check "socket on by default, as before"             'grep -q "/var/run/docker.sock:/var/run/docker.sock" "$OUT"'
check "switches named for the settings page"        'grep -q "MYSURICATES_SWITCH_PREFIX=STABLE_" "$OUT" && grep -q "MYSURICATES_SWITCH_SOURCE=$ENVF" "$OUT"'
OUT="$(bash "$S" write-override test 2>/dev/null)"
check "service test, prefix TEST_"                  'grep -q "^  test:" "$OUT" && grep -q "MYSURICATES_SWITCH_PREFIX=TEST_" "$OUT"'
check "unknown environment is refused"              'bash "$S" write-override prod >/dev/null 2>&1; [ $? -eq 2 ]'

echo "2) switches from staging.env, environment wins"
printf 'STABLE_AI_FROM_HOST=programs\nSTABLE_DOCKER_SOCKET=0\nTEST_AI_PROVIDERS=claude\n' > "$ENVF"
bash "$S" write-override stable >/dev/null 2>&1; bash "$S" write-override test >/dev/null 2>&1
check "stable: programs, no socket"                 'grep -q "MYSURICATES_AI_FROM_HOST=programs" "$OV/stable.override.yml" && ! grep -q docker.sock "$OV/stable.override.yml"'
check "test keeps its own values"                   'grep -q "MYSURICATES_AI_FROM_HOST=login" "$OV/test.override.yml" && grep -q "MYSURICATES_AI_PROVIDERS=claude" "$OV/test.override.yml" && grep -q docker.sock "$OV/test.override.yml"'
STABLE_DOCKER_SOCKET=1 bash "$S" write-override stable >/dev/null 2>&1
check "an environment variable wins over the file"  'grep -q docker.sock "$OV/stable.override.yml"'
printf 'STABLE_AI_FROM_HOST=$(touch %s/pwned)\n' "$TMP" > "$ENVF"
bash "$S" write-override stable >/dev/null 2>&1; RC=$?
check "the file is never executed"                  '[ ! -e "$TMP/pwned" ] && [ "$RC" = 2 ]'
rm -f "$ENVF"

echo "3) every compose call carries the override"
: > "$DOCKER_LOG"
bash "$S" start >/dev/null 2>&1
check "start"          'grep -qF -- "compose -f $STABLE_YML -f $OV/stable.override.yml up -d" "$DOCKER_LOG"'
bash "$S" stop >/dev/null 2>&1
check "stop"           'grep -qF -- "compose -f $STABLE_YML -f $OV/stable.override.yml down" "$DOCKER_LOG"'
bash "$S" promote >/dev/null 2>&1
check "promote builds with it" 'grep -qF -- "compose -f $STABLE_YML -f $OV/stable.override.yml build" "$DOCKER_LOG"'
bash "$S" test-start >/dev/null 2>&1; bash "$S" test-stop >/dev/null 2>&1
check "test-start / test-stop" 'grep -qF -- "compose -f $TEST_YML -f $OV/test.override.yml up -d" "$DOCKER_LOG" && grep -qF -- "compose -f $TEST_YML -f $OV/test.override.yml down" "$DOCKER_LOG"'
check "no call without it"     '! grep -E "^compose " "$DOCKER_LOG" | grep -v -- "override.yml" | grep -q .'
printf 'STABLE_AI_FROM_HOST=everything\n' > "$ENVF"; : > "$DOCKER_LOG"
bash "$S" start >/dev/null 2>&1; RC=$?
check "invalid switch: start refuses before compose" '[ "$RC" = 2 ] && ! grep -q "^compose " "$DOCKER_LOG"'
rm -f "$ENVF"

echo "4) own login only with programs"
: > "$DOCKER_LOG"
RUNNING=1 MODE=login bash "$S" login stable codex >/dev/null 2>&1; RC=$?
check "mode login: refused"            '[ "$RC" = 2 ] && ! grep -q "exec -it" "$DOCKER_LOG"'
RUNNING=1 MODE=programs bash "$S" login stable codex >/dev/null 2>&1
check "mode programs: as the app user" 'grep -q -- "exec -it -u flo mysuricates-stable codex login --device-auth" "$DOCKER_LOG"'
check "not running: refused"           'bash "$S" login test codex >/dev/null 2>&1; [ $? -eq 2 ]'
check "unknown provider: refused"      'RUNNING=1 MODE=programs bash "$S" login stable gemini >/dev/null 2>&1; [ $? -eq 2 ]'

echo "5) compose files no longer hard-wire the host"
for f in "$R/ai-automation-backend/docker-compose.stable.yml" "$R/ai-automation-backend/docker-compose.test.yml"; do
  check "$(basename "$f"): no provider mounts, no socket" '! grep -E "^\s+- " "$f" | grep -qE "/seed/|\.claude|\.codex|\.pi[:/]|\.local/(bin|share)[^ ]*:|docker\.sock:"'
done
check "staging.sh parses"               'bash -n "$R/scripts/staging.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
