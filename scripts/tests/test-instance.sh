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
case "$*" in
  *"compose"*"config"*) exit 0 ;;
  *"com.docker.compose.project"*) [ -n "${OWNER+x}" ] || exit 1; echo "$OWNER"; exit 0 ;;  # Container da? wem gehoert er?
  *"inspect"*) echo healthy ;;
esac
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
check "default: login, switches named"        'grep -q "MYSURICATES_AI_FROM_HOST=login" "$TMP/inst/demo.override.yml" && grep -q "MYSURICATES_SWITCH_PREFIX=MYSURICATES_" "$TMP/inst/demo.override.yml" && grep -q "MYSURICATES_SWITCH_SOURCE=$TMP/inst/demo.env" "$TMP/inst/demo.override.yml"'
printf 'MYSURICATES_PORT=19403\nMYSURICATES_AI_FROM_HOST=programs\nMYSURICATES_AI_PROVIDERS=codex\n' > "$TMP/inst/prog.env"
INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name prog --no-wait >/dev/null 2>&1
check "switches come from the instance file"  'grep -q "MYSURICATES_AI_FROM_HOST=programs" "$TMP/inst/prog.override.yml" && grep -q "MYSURICATES_AI_PROVIDERS=codex" "$TMP/inst/prog.override.yml"'
printf 'MYSURICATES_PORT=19404\nMYSURICATES_AI_FROM_HOST=everything\n' > "$TMP/inst/bad.env"
: > "$DOCKER_LOG"
OUT="$(bash "$SCRIPT" up --name bad --no-wait 2>&1)"; RC=$?
check "invalid mode: up refuses, no compose"  '[ "$RC" = 2 ] && ! grep -q "^compose " "$DOCKER_LOG" && printf "%s" "$OUT" | grep -q "MYSURICATES_AI_FROM_HOST"'
rm -f "$TMP/inst/prog.env" "$TMP/inst/prog.override.yml" "$TMP/inst/bad.env" "$TMP/inst/bad.override.yml"
check "list shows the instance and its port"  'bash "$SCRIPT" list | grep "demo.*19400" >/dev/null'
echo "1b) a container of the same name from another setup is never touched"
: > "$DOCKER_LOG"
OUT="$(OWNER=ai-automation-backend bash "$SCRIPT" up --name demo --no-wait 2>&1)"; RC=$?
check "foreign container: up refuses (exit 2)"  '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep "ai-automation-backend" >/dev/null'
check "foreign container: no compose call"      '! grep -q "^compose " "$DOCKER_LOG"'
OUT="$(OWNER= bash "$SCRIPT" key --name demo 2>&1)"; RC=$?
check "container without compose label counts as foreign" '[ "$RC" = 2 ]'
check "own container is fine"                   'OWNER=mysuricates-demo INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name demo --no-wait >/dev/null 2>&1'
check "down stays inside its compose project"   'OWNER=ai-automation-backend bash "$SCRIPT" down --name demo >/dev/null 2>&1; tail -1 "$DOCKER_LOG" | grep -- "-p mysuricates-demo" >/dev/null'

echo "1c) bound to the network: the address of this machine is named (b8aa3866)"
printf '#!/bin/sh\n[ "$1" = -I ] && echo "192.168.7.7 127.0.0.1"\n' > "$TMP/bin/hostname"; chmod +x "$TMP/bin/hostname"
printf 'MYSURICATES_PORT=19402\nMYSURICATES_BIND=0.0.0.0\nMYSURICATES_LANG=en\n' > "$TMP/inst/lan.env"
OUT="$(OWNER=mysuricates-lan INSTANCE_HEALTH_TIMEOUT=5 bash "$SCRIPT" up --name lan 2>&1)"
check "0.0.0.0: localhost and network address"  'printf "%s" "$OUT" | grep -F "http://localhost:19402" >/dev/null && printf "%s" "$OUT" | grep -F "http://192.168.7.7:19402" >/dev/null'
check "0.0.0.0: VPN hint"                       'printf "%s" "$OUT" | grep "VPN in front" >/dev/null'
OUT="$(OWNER=mysuricates-demo INSTANCE_HEALTH_TIMEOUT=5 bash "$SCRIPT" up --name demo 2>&1)"
check "127.0.0.1: one line, no network address" 'printf "%s" "$OUT" | tail -n 1 | grep -x "mysuricates-demo läuft: http://localhost:19400" >/dev/null && ! printf "%s" "$OUT" | grep VPN >/dev/null'
rm -f "$TMP/bin/hostname"

echo "2) AUTOMATION_* lines reach the instance"
check "compose passes the instance file as env_file" 'grep -q "env_file:" "$HERE/../../deploy/compose.yml" && grep -q "MYSURICATES_ENV_FILE" "$HERE/../../deploy/compose.yml"'

echo "3) host runtime (java stub, no real build)"
STATE="$TMP/state/mysuricates/hostdemo"
mkdir -p "$STATE/frontend" "$TMP/hostbin"
echo jar > "$STATE/app.jar"
cat > "$TMP/hostbin/java" <<'STUB'
#!/bin/sh
env > "$JAVA_ENV_OUT"
echo "$*" >> "$JAVA_ENV_OUT.args"
exec sleep 300
STUB
cat > "$TMP/hostbin/curl" <<'STUB'
#!/bin/sh
case "$*" in *system/health*) echo '{"status":"UP"}'; exit 0 ;; esac
exit 7
STUB
chmod +x "$TMP/hostbin/java" "$TMP/hostbin/curl"
cat > "$TMP/inst/hostdemo.env" <<'ENV'
MYSURICATES_RUNTIME=host
MYSURICATES_PORT=19401
MYSURICATES_PRESET=research
AUTOMATION_MODULES_WEBHOOKS=true
AUTOMATION_AI_DEFAULT_PROVIDER=PI
ENV
export JAVA_ENV_OUT="$TMP/java.env" XDG_STATE_HOME="$TMP/state"
PATH="$TMP/hostbin:$PATH" INSTANCE_HEALTH_TIMEOUT=10 bash "$SCRIPT" up --name hostdemo >"$TMP/host-up.out" 2>&1
# Der java-Stub schreibt seine Umgebung erst, wenn er laeuft; `up` kehrt vorher zurueck, weil
# der curl-Stub sofort "UP" meldet. Die .args-Datei entsteht nach der env-Datei und belegt, dass
# beide vollstaendig sind. Ohne dieses Warten verlor am 2026-09-22 auf dem CI-Runner die erste
# Pruefung das Rennen ("java started with the port"), die Millisekunden spaeteren nicht mehr.
for _ in $(seq 1 100); do [ -s "$JAVA_ENV_OUT.args" ] && break; sleep 0.05; done
check "host up reports the address"           'grep "localhost:19401" "$TMP/host-up.out" >/dev/null'
check "java started with the port"            'grep -x "AUTOMATION_PORT=19401" "$JAVA_ENV_OUT" >/dev/null'
check "backend serves the built frontend"     'grep -x "AUTOMATION_SERVEFRONTEND=true" "$JAVA_ENV_OUT" >/dev/null && grep -x "AUTOMATION_FRONTENDPATH=$STATE/frontend" "$JAVA_ENV_OUT" >/dev/null'
check "bound to 127.0.0.1 by default"         'grep -x "SERVER_ADDRESS=127.0.0.1" "$JAVA_ENV_OUT" >/dev/null'
check "data stays in the instance state dir"  'grep -x "AUTOMATION_DB_PATH=$STATE/data/automation.db" "$JAVA_ENV_OUT" >/dev/null'
check "AUTOMATION_* from the file reach java"  'grep -x "AUTOMATION_MODULES_WEBHOOKS=true" "$JAVA_ENV_OUT" >/dev/null && grep -x "AUTOMATION_AI_DEFAULT_PROVIDER=PI" "$JAVA_ENV_OUT" >/dev/null'
check "preset from MYSURICATES_PRESET"        'grep -x "AUTOMATION_MODULES_PRESET=research" "$JAVA_ENV_OUT" >/dev/null'
check "own user.home per instance"             'grep -- "-Duser.home=$STATE/home" "$JAVA_ENV_OUT.args" >/dev/null'
check "CLI lookups still use the real home"   'grep -x "AUTOMATION_HOST_HOME=$HOME" "$JAVA_ENV_OUT" >/dev/null && grep -x "AUTOMATION_HOSTPI_CONFIGDIR=$HOME/.pi/agent" "$JAVA_ENV_OUT" >/dev/null'
check "no docker call for the host runtime"   '! grep "hostdemo" "$DOCKER_LOG" >/dev/null'
check "pid file written"                      '[ -s "$STATE/instance.pid" ]'
PID="$(cat "$STATE/instance.pid" 2>/dev/null)"
PATH="$TMP/hostbin:$PATH" bash "$SCRIPT" down --name hostdemo >/dev/null 2>&1
check "down stops the process"                '! kill -0 "$PID" 2>/dev/null'
check "list shows the runtime"                'bash "$SCRIPT" list | grep "hostdemo.*19401.*host" >/dev/null'

check "no bash-4-only syntax"                 '! grep -nE "declare -g?A|mapfile|readarray" "$SCRIPT"'
echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
