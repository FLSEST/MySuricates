#!/usr/bin/env bash
# Tests for scripts/lib/providers.sh — which AI CLIs are there and logged in, provider-neutral.
# Run: bash scripts/tests/test-providers.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
mkdir -p "$TMP/home" "$TMP/bin" "$TMP/tools"
# Own PATH without real AI CLIs: only the tools providers.sh needs (on the host
# codex/pi may lie in /usr/bin and would feign "present").
for t in dirname tr grep cat; do ln -s "$(command -v "$t")" "$TMP/tools/$t"; done
# Login variables of the host (e.g. ANTHROPIC_API_KEY) must not influence the tests.
run() { ( HOME="$TMP/home" PATH="$TMP/bin:$TMP/tools"; export HOME PATH; . "$HERE/../lib/providers.sh"
  for _id in "${PROVIDER_IDS[@]}"; do for _v in $(provider_get "$_id" login_env); do unset "$_v"; done; done
  eval "$1" ); }
# grep without -q: -q exits at the first hit, the writer gets SIGPIPE, and
# pipefail then counts the pipeline as an error.
has() { run "providers_status" | grep -x -- "$1" >/dev/null; }

echo "1) catalog"
check "lists every provider from the backend"   '[ "$(run "providers_ids" | tr "\n" " ")" = "claude codex pi " ]'
check "reads fields"                            '[ "$(run "provider_get codex login")" = "codex login" ]'
check "unknown provider/field is empty"         '[ -z "$(run "provider_get nope cli")" ]'

echo "2) nothing installed"
check "all missing"                             '[ "$(run "providers_status")" = "claude missing logged-out
codex missing logged-out
pi missing logged-out" ]'

echo "3) installed and logged in"
for c in claude codex pi; do printf '#!/bin/sh\n' > "$TMP/bin/$c"; chmod +x "$TMP/bin/$c"; done
mkdir -p "$TMP/home/.codex" "$TMP/home/.pi/agent" "$TMP/home/.claude"
echo '{"tokens":1}' > "$TMP/home/.codex/auth.json"
echo '{}' > "$TMP/home/.pi/agent/auth.json"
echo '{"oauthAccount":{"x":1}}' > "$TMP/home/.claude.json"
check "codex logged in via auth.json"           'has "codex present logged-in"'
check "empty pi auth.json is not a login"       'has "pi present logged-out"'
check "claude via .claude.json oauthAccount"    'has "claude present logged-in"'
echo '{"anthropic":{"key":"x"}}' > "$TMP/home/.pi/agent/auth.json"
check "pi with one backend is logged in"        'has "pi present logged-in"'
echo '{}' > "$TMP/home/.claude.json"
check "claude.json without account is not"      'has "claude present logged-out"'
: > "$TMP/home/.claude/.credentials.json"
check "an empty credentials file is not"        'has "claude present logged-out"'
echo '{"claudeAiOauth":{}}' > "$TMP/home/.claude/.credentials.json"
check "claude via .credentials.json"            'has "claude present logged-in"'

echo "4) login via environment variable (0b1a3ae7)"
rm -f "$TMP/home/.claude/.credentials.json" "$TMP/home/.codex/auth.json"; echo '{}' > "$TMP/home/.pi/agent/auth.json"
check "catalog names login variables"           '[ "$(run "provider_get claude login_env")" = "CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY" ]'
check "without files and variables: logged out" 'has "claude present logged-out" && has "codex present logged-out" && has "pi present logged-out"'
check "CLAUDE_CODE_OAUTH_TOKEN logs claude in"  '[ "$(run "CLAUDE_CODE_OAUTH_TOKEN=sk-secret-123; export CLAUDE_CODE_OAUTH_TOKEN; providers_status")" = "claude present logged-in
codex present logged-out
pi present logged-out" ]'
check "ANTHROPIC_API_KEY: claude and pi"        '[ "$(run "export ANTHROPIC_API_KEY=x; providers_status" | grep -c " logged-in$")" = 2 ]'
check "OPENAI_API_KEY logs codex in"            'run "export OPENAI_API_KEY=x; providers_status" | grep -x "codex present logged-in" >/dev/null'
check "an empty variable is not a login"        'run "export CLAUDE_CODE_OAUTH_TOKEN=; providers_status" | grep -x "claude present logged-out" >/dev/null'
check "the value is never printed"              '! run "export CLAUDE_CODE_OAUTH_TOKEN=sk-secret-123; providers_status; provider_logged_in claude" 2>&1 | grep sk-secret >/dev/null'

echo "4b) file login vs. variable login — only files reach a container (90b4422c)"
check "names of the set variables, not values"  '[ "$(run "export ANTHROPIC_API_KEY=sk-secret-1 GEMINI_API_KEY=g-secret-2; provider_login_env_names pi")" = "ANTHROPIC_API_KEY GEMINI_API_KEY" ]'
check "no variable set: no names"               '[ -z "$(run "provider_login_env_names codex")" ]'
check "a variable is no file login"             '! run "export OPENAI_API_KEY=x; provider_login_file codex"'
echo '{"tokens":1}' > "$TMP/home/.codex/auth.json"
check "auth.json is a file login"               'run "provider_login_file codex"'
rm -f "$TMP/home/.codex/auth.json"

echo "5) bash 3.2 hygiene"
check "no bash-4-only syntax"                   '! grep -nE "declare -g?A|mapfile|readarray" "$HERE/../lib/providers.sh" "$HERE/../lib/provider-catalog.generated.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
