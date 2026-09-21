#!/usr/bin/env bash
# Tests for scripts/lib/host-mounts.sh — compose override with only the mounts this machine has.
# Run: bash scripts/tests/test-host-mounts.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
run() {  # $1 = OS, rest = args
  local os="$1"; shift
  ( HOME="$TMP/home" HOST_MOUNTS_OS="$os" HOST_MOUNTS_NODE_MODULES="$TMP/nm"
    export HOME HOST_MOUNTS_OS HOST_MOUNTS_NODE_MODULES
    . "$HERE/../lib/host-mounts.sh"; host_mounts_yaml "$@"; echo "#count=$HOST_MOUNTS_PROVIDER_COUNT" )
}
mkdir -p "$TMP/home"

echo "1) nothing installed"
OUT="$(run Linux svc "" 0)"
check "valid empty override"            'grep -q "^services:" <<<"$OUT" && grep -q "^  svc:" <<<"$OUT" && grep -q "volumes: \[\]" <<<"$OUT"'
check "no provider counted"             'grep -q "#count=0" <<<"$OUT"'

echo "2) claude + codex + pi on Linux"
mkdir -p "$TMP/home/.local/bin" "$TMP/home/.local/share/claude" "$TMP/home/.claude" "$TMP/home/.codex" "$TMP/home/.pi" \
         "$TMP/nm/@openai/codex" "$TMP/nm/@earendil-works/pi-coding-agent"
echo '{}' > "$TMP/home/.claude.json"
OUT="$(run Linux svc "" 0)"
check "claude binary dir mounted ro"    'grep -q -- "- $TMP/home/.local/bin:$TMP/home/.local/bin:ro" <<<"$OUT"'
check "claude.json seeded read-only"    'grep -q -- "- $TMP/home/.claude.json:/seed/.claude.json:ro" <<<"$OUT"'
check "logins mounted rw"               'grep -q -- "- $TMP/home/.claude:$TMP/home/.claude$" <<<"$OUT" && grep -q -- "- $TMP/home/.codex:$TMP/home/.codex$" <<<"$OUT"'
check "npm packages mounted ro"         'grep -q -- "- $TMP/nm/@openai/codex:$TMP/nm/@openai/codex:ro" <<<"$OUT"'
check "providers counted (3)"           'grep -q "#count=3" <<<"$OUT"'

echo "3) macOS: logins only, never host binaries"
OUT="$(run Darwin svc "" 0)"
check "no binary dirs on macOS"         '! grep -q "\.local/bin" <<<"$OUT" && ! grep -q "/nm/" <<<"$OUT"'
check "logins still mounted"            'grep -q -- "- $TMP/home/.claude:$TMP/home/.claude$" <<<"$OUT"'

echo "4) projects dir and docker socket"
export HOST_MOUNTS_ASSUME_SOCKET=1   # CI/Probe haben evtl. keinen echten Socket
mkdir -p "$TMP/projects"
OUT="$(run Linux svc "$TMP/projects" 1)"
check "projects mounted at same path"   'grep -q -- "- $TMP/projects:$TMP/projects$" <<<"$OUT"'
check "socket only when asked"          'grep -q -- "- /var/run/docker.sock:/var/run/docker.sock" <<<"$OUT"'
OUT="$(run Linux svc "$TMP/projects" 0)"
check "no socket by default"            '! grep -q docker.sock <<<"$OUT"'
check "missing projects dir is skipped" '! grep -q "/nope" <<<"$(run Linux svc "$TMP/nope" 0)"'
check "whole HOME is refused"           '! grep -q -- "- $TMP/home:$TMP/home$" <<<"$(run Linux svc "$TMP/home" 0)"'

echo "5) bash 3.2 hygiene"
check "no bash-4-only syntax"           '! grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}" "$HERE/../lib/host-mounts.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
