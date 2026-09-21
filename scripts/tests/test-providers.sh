#!/usr/bin/env bash
# Tests for scripts/lib/providers.sh — which AI CLIs are there and logged in, provider-neutral.
# Run: bash scripts/tests/test-providers.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
mkdir -p "$TMP/home" "$TMP/bin" "$TMP/tools"
# Eigener PATH ohne echte KI-CLIs: nur die Werkzeuge, die providers.sh braucht (auf dem Host liegen
# codex/pi evtl. in /usr/bin und wuerden "present" vortaeuschen).
for t in dirname tr grep cat; do ln -s "$(command -v "$t")" "$TMP/tools/$t"; done
run() { ( HOME="$TMP/home" PATH="$TMP/bin:$TMP/tools"; export HOME PATH; . "$HERE/../lib/providers.sh"; eval "$1" ); }
# grep ohne -q: -q beendet sich beim ersten Treffer, der Schreiber bekommt SIGPIPE, und
# pipefail wertet die Pipeline dann als Fehler.
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

echo "4) bash 3.2 hygiene"
check "no bash-4-only syntax"                   '! grep -nE "declare -g?A|mapfile|readarray" "$HERE/../lib/providers.sh" "$HERE/../lib/provider-catalog.generated.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
