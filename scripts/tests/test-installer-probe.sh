#!/usr/bin/env bash
# Tests for scripts/installer-probe.sh — argument handling and the checks that run before Docker.
# The probe itself (container, setup.sh, report) is exercised by running it; see docs/entwicklung.md.
# Run: bash scripts/tests/test-installer-probe.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="$HERE/../installer-probe.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Fake docker: logs every call; `docker info` fails, so a probe that gets that far stops with exit 2
# instead of starting containers.
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
exit 1
STUB
chmod +x "$TMP/bin/docker"
export DOCKER_CALLS="$TMP/docker-calls.log"
probe() { PATH="$TMP/bin:$PATH" bash "$PROBE" "$@" </dev/null >"$TMP/out" 2>&1; echo $?; }

echo "1) syntax and help"
check "bash -n passes"                                  'bash -n "$PROBE"'
check "--help names the interactive mode"               'bash "$PROBE" --help | grep -q -- "--interactive"'
check "--help names --ref and the gate lines"           'bash "$PROBE" --help | grep -q -- "--ref main" && bash "$PROBE" --help | grep -q "gate_status"'
check "the report renderer parses"                      'python3 -m py_compile "$HERE/../lib/installer_probe_report.py"'
check "the screenshot helper parses"                    '! command -v node >/dev/null || node --check "$HERE/../lib/installer_probe_screens.mjs"'

echo "2) argument errors exit 2 before any docker call"
: > "$DOCKER_CALLS"
check "unknown option"                                  '[ "$(probe --wat)" = 2 ] && grep -q "Unbekannte Option" "$TMP/out"'
check "unknown ref"                                     '[ "$(probe --ref does-not-exist-anywhere)" = 2 ] && grep -q "Unbekannter Stand" "$TMP/out"'
check "port must be numeric"                            '[ "$(probe --port abc)" = 2 ]'
check "port below 1024 is refused"                      '[ "$(probe --port 80)" = 2 ]'
check "--repo must be a git repo"                       '[ "$(probe --repo "$TMP")" = 2 ] && grep -q "Kein Git-Repo" "$TMP/out"'
check "--interactive without a terminal is refused"     '[ "$(probe --interactive)" = 2 ] && grep -q "braucht ein Terminal" "$TMP/out"'
check "none of these touched docker"                    '[ ! -s "$DOCKER_CALLS" ]'

echo "3) docker availability"
check "unreachable daemon exits 2 with a reason"        '[ "$(probe --ref HEAD)" = 2 ] && grep -q "Docker-Daemon nicht erreichbar" "$TMP/out"'
check "it asked docker info, nothing else"              '[ "$(cat "$DOCKER_CALLS")" = info ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
