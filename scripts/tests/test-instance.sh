#!/usr/bin/env bash
# Tests for scripts/instance.sh — without Docker (stub logs the calls).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; SCRIPT="$HERE/../instance.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
mkdir -p "$TMP/bin" "$TMP/inst" "$TMP/home"
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_LOG"
case "$*" in *"compose"*"config"*) exit 0 ;; *"inspect"*) echo healthy ;; esac
exit 0
STUB
chmod +x "$TMP/bin/docker"
export DOCKER_LOG="$TMP/docker.log" PATH="$TMP/bin:$PATH" HOME="$TMP/home" MYSURICATES_INSTANCES_DIR="$TMP/inst"
cat > "$TMP/inst/demo.env" <<'ENV'
MYSURICATES_PORT=19400
MYSURICATES_PRESET=research
ENV
check "bash -n"                               'bash -n "$SCRIPT"'
check "--help lists the commands"             'bash "$SCRIPT" --help | grep "up|down|status|logs|key|list" >/dev/null'
check "unknown instance exits 2"              'bash "$SCRIPT" up --name nope >/dev/null 2>&1; [ $? -eq 2 ]'
INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name demo --no-wait >/dev/null 2>&1
check "compose project named after instance"  'grep -q -- "-p mysuricates-demo" "$DOCKER_LOG"'
check "uses deploy/compose.yml + override"    'grep -q "deploy/compose.yml" "$DOCKER_LOG" && grep -q "demo.override.yml" "$DOCKER_LOG"'
check "override was written"                  '[ -f "$TMP/inst/demo.override.yml" ] && grep -q "^  app:" "$TMP/inst/demo.override.yml"'
check "list shows the instance and its port"  'bash "$SCRIPT" list | grep "demo.*19400" >/dev/null'
check "no bash-4-only syntax"                 '! grep -nE "declare -g?A|mapfile|readarray" "$SCRIPT"'
echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
