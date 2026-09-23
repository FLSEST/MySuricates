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
# docker build … - : der Build-Kontext kommt als tar ueber stdin — aufheben, damit der Test ihn prueft
[ "$1" = build ] && { cat > "${DOCKER_CTX:-/dev/null}"; exit 0; }
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
check "--help names built (1c016162)"         'bash "$SCRIPT" --help | grep -- "built  --name NAME" >/dev/null'
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
# Eine Kopie des Skripts in einem Wegwerf-Repo: `up` baut, wenn der vorhandene Build nicht zum Klon
# passt (1c016162) — gegen das echte Repo liefe dann ein echter Maven-Bau. Hier scheitert er laut.
HOSTREPO="$TMP/hostrepo"
mkdir -p "$HOSTREPO/scripts" "$HOSTREPO/ai-automation-backend"
cp "$SCRIPT" "$HOSTREPO/scripts/instance.sh"; ln -s "$HERE/../lib" "$HOSTREPO/scripts/lib"
printf '#!/bin/sh\necho "unerwarteter Bau im Test" >&2\nexit 1\n' > "$HOSTREPO/ai-automation-backend/mvnw"
chmod +x "$HOSTREPO/ai-automation-backend/mvnw"
HSCRIPT="$HOSTREPO/scripts/instance.sh"
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
PATH="$TMP/hostbin:$PATH" INSTANCE_HEALTH_TIMEOUT=10 GIT_CEILING_DIRECTORIES="$TMP" bash "$HSCRIPT" up --name hostdemo >"$TMP/host-up.out" 2>&1
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
PATH="$TMP/hostbin:$PATH" bash "$HSCRIPT" down --name hostdemo >/dev/null 2>&1
check "down stops the process"                '! kill -0 "$PID" 2>/dev/null'
check "list shows the runtime"                'bash "$HSCRIPT" list | grep "hostdemo.*19401.*host" >/dev/null'

echo "4) host build takes the JAR it just built, not one left over from an older version"
# Eigenes Wegwerf-Repo: instance.sh leitet REPO_ROOT aus seinem eigenen Ort ab, und gebaut wird
# mit Stubs (kein Maven, kein npm). Der Fall stammt aus der Demo-VM: nach dem Sprung auf 0.7.7 lag
# das 0.7.6-JAR noch im target/, und kopiert wurde das alte (2026-09-23).
REPO="$TMP/repo"
mkdir -p "$REPO/scripts" "$REPO/ai-automation-backend/target" "$REPO/ai-automation-frontend"
cp "$SCRIPT" "$REPO/scripts/instance.sh"
ln -s "$HERE/../lib" "$REPO/scripts/lib"
cat > "$REPO/ai-automation-backend/mvnw" <<'STUB'
#!/bin/sh
for a in "$@"; do [ "$a" = clean ] && rm -f target/*.jar; done
mkdir -p target && echo neu > target/ai-automation-backend-9.9.9.jar
STUB
cat > "$TMP/hostbin/npm" <<'STUB'
#!/bin/sh
case "$*" in *"run build"*) mkdir -p dist && echo html > dist/index.html ;; esac
exit 0
STUB
chmod +x "$REPO/ai-automation-backend/mvnw" "$TMP/hostbin/npm"
cat > "$TMP/inst/buildtest.env" <<'ENV'
MYSURICATES_RUNTIME=host
MYSURICATES_PORT=19403
ENV
STATE2="$TMP/state/mysuricates/buildtest"
echo alt > "$REPO/ai-automation-backend/target/ai-automation-backend-0.0.1-SNAPSHOT.jar"
PATH="$TMP/hostbin:$PATH" INSTANCE_HEALTH_TIMEOUT=10 bash "$REPO/scripts/instance.sh" up --name buildtest --rebuild >"$TMP/build.out" 2>&1
check "clean raeumt das alte JAR weg"         '[ ! -e "$REPO/ai-automation-backend/target/ai-automation-backend-0.0.1-SNAPSHOT.jar" ]'
check "app.jar is the fresh build"            '[ "$(cat "$STATE2/app.jar" 2>/dev/null)" = neu ]'
check "built frontend copied"                 '[ -s "$STATE2/frontend/index.html" ]'
PATH="$TMP/hostbin:$PATH" bash "$REPO/scripts/instance.sh" down --name buildtest >/dev/null 2>&1

# Zweiter Fall: ein Bau, der das target/ nicht raeumt, hinterlaesst zwei JARs — dann darf das
# Skript nicht raten, sondern muss abbrechen.
cat > "$REPO/ai-automation-backend/mvnw" <<'STUB'
#!/bin/sh
mkdir -p target && echo neu > target/ai-automation-backend-9.9.9.jar
STUB
chmod +x "$REPO/ai-automation-backend/mvnw"
echo alt > "$REPO/ai-automation-backend/target/ai-automation-backend-0.0.1-SNAPSHOT.jar"
PATH="$TMP/hostbin:$PATH" bash "$REPO/scripts/instance.sh" up --name buildtest --rebuild >"$TMP/build2.out" 2>&1
RC_BUILD=$?
check "two JARs: stops instead of guessing"   '[ "$RC_BUILD" -ne 0 ] && grep -q "Mehrere JARs" "$TMP/build2.out"'
check "names both files in the message"       'grep -q "0.0.1-SNAPSHOT.jar" "$TMP/build2.out" && grep -q "9.9.9.jar" "$TMP/build2.out"'

echo "5) after a git pull, up builds anew — and says what it did (1c016162)"
# Die Demo-VM lief nach `git pull` + setup.sh still mit dem alten Build weiter: up baute nur mit
# --rebuild oder ohne JAR. Eigenes Git-Repo mit Stubs; das JAR enthaelt den Commit, aus dem es stammt.
REPO2="$TMP/repo2"
mkdir -p "$REPO2/scripts" "$REPO2/ai-automation-backend" "$REPO2/ai-automation-frontend"
cp "$SCRIPT" "$REPO2/scripts/instance.sh"
ln -s "$HERE/../lib" "$REPO2/scripts/lib"
cat > "$REPO2/ai-automation-backend/mvnw" <<'STUB'
#!/bin/sh
echo build >> "$MVN_LOG"
rm -rf target && mkdir -p target && git rev-parse --short HEAD > target/ai-automation-backend-9.9.9.jar
STUB
chmod +x "$REPO2/ai-automation-backend/mvnw"
printf 'target/\n' > "$REPO2/.gitignore"
commit() { git -C "$REPO2" add -A && git -C "$REPO2" -c user.name=t -c user.email=t@t commit -qm "$1"; }
git -C "$REPO2" init -q && commit eins
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=19405\n' > "$TMP/inst/upd.env"
STATE3="$TMP/state/mysuricates/upd"
export MVN_LOG="$TMP/mvn.log"
inst() { PATH="$TMP/hostbin:$PATH" INSTANCE_HEALTH_TIMEOUT=10 bash "$REPO2/scripts/instance.sh" "$@" --name upd; }
builds() { grep -c build "$MVN_LOG" 2>/dev/null; }
head_of() { git -C "$REPO2" rev-parse "${1:-HEAD}"; }
: > "$MVN_LOG"; inst up >/dev/null 2>&1
check "first up builds once"                   '[ "$(builds)" = 1 ]'
check "the commit of the build is noted"       '[ "$(cat "$STATE3/built-from" 2>/dev/null)" = "$(head_of)" ]'
check "built: current + commit"                '[ "$(inst built)" = "current $(head_of)" ]'
: > "$MVN_LOG"; OUT="$(inst up 2>&1)"
check "running, current: nothing built, no warning" '[ "$(builds)" = 0 ] && printf "%s" "$OUT" | grep -q "läuft bereits" && ! printf "%s" "$OUT" | grep -q "nicht übernommen"'
OLD="$(head_of)"; echo neu > "$REPO2/neu.txt"; commit zwei; NEW="$(head_of)"
check "new commit: built says outdated OLD NEW" '[ "$(inst built)" = "outdated $OLD $NEW" ]'
PID_BEFORE="$(cat "$STATE3/instance.pid")"
: > "$MVN_LOG"; OUT="$(inst up 2>&1)"; RC=$?
check "running, outdated: says so, with both commits and the way" '[ "$RC" = 0 ] && printf "%s" "$OUT" | grep -q "nicht übernommen" && printf "%s" "$OUT" | grep -q "${OLD:0:8}" && printf "%s" "$OUT" | grep -q "${NEW:0:8}" && printf "%s" "$OUT" | grep -q "instance.sh down --name upd"'
check "running, outdated: neither built nor stopped" '[ "$(builds)" = 0 ] && [ "$(cat "$STATE3/instance.pid")" = "$PID_BEFORE" ] && kill -0 "$PID_BEFORE" 2>/dev/null'
OUT="$(inst up --rebuild 2>&1)"
check "running: --rebuild is not silently ignored" 'printf "%s" "$OUT" | grep -q -- "nicht übernommen" && [ "$(builds)" = 0 ]'
inst down >/dev/null 2>&1
: > "$MVN_LOG"; OUT="$(inst up 2>&1)"
check "stopped, outdated: up builds anew and says why" '[ "$(builds)" = 1 ] && printf "%s" "$OUT" | grep -q "baue neu"'
check "app.jar comes from the new commit"      '[ "$(cat "$STATE3/app.jar")" = "$(git -C "$REPO2" rev-parse --short HEAD)" ] && [ "$(inst built)" = "current $NEW" ]'
inst down >/dev/null 2>&1
: > "$MVN_LOG"; OUT="$(inst up 2>&1)"
check "stopped, current: starts without building, names the commit" '[ "$(builds)" = 0 ] && printf "%s" "$OUT" | grep -q "nicht neu gebaut (Stand ${NEW:0:8})"'
OUT="$(inst up --rebuild 2>&1)"
check "running, current: --rebuild names the way" 'printf "%s" "$OUT" | grep -q -- "--rebuild wirkt erst" && [ "$(builds)" = 0 ]'
inst down >/dev/null 2>&1
rm -f "$STATE3/built-from"   # Build aus der Zeit vor dem Vermerk
check "no note: outdated, build unknown"       '[ "$(inst built)" = "outdated - $NEW" ]'
: > "$MVN_LOG"; inst up >/dev/null 2>&1
check "no note: builds once, then notes"       '[ "$(builds)" = 1 ] && [ "$(cat "$STATE3/built-from")" = "$NEW" ]'
inst down >/dev/null 2>&1
mv "$REPO2/.git" "$REPO2/.git-weg"
: > "$MVN_LOG"
check "no git: unknown, the note stays"        '[ "$(GIT_CEILING_DIRECTORIES="$TMP" inst built)" = "unknown $NEW" ]'
GIT_CEILING_DIRECTORIES="$TMP" inst up >/dev/null 2>&1
check "no git: starts the build it has"        '[ "$(builds)" = 0 ] && [ -s "$STATE3/instance.pid" ]'
GIT_CEILING_DIRECTORIES="$TMP" inst down >/dev/null 2>&1
mv "$REPO2/.git-weg" "$REPO2/.git"
check "built is host-only (container: exit 2)" 'bash "$SCRIPT" built --name demo >/dev/null 2>&1; [ $? -eq 2 ]'
check "container up always builds the image"   'grep -q "^build -f deploy/Dockerfile --target runtime -t mysuricates:local -$" "$DOCKER_LOG" && grep -q "up -d --no-build" "$DOCKER_LOG"'

echo "6) the image gets only tracked files — nothing kept out by .git/info/exclude (890e8e9c)"
# Auf dem Rechner des Autors lagen private Pool-Definitionen, nur per .git/info/exclude
# ausgeschlossen; der Build aus dem Arbeitsbaum nahm sie mit ins Image und in den Pool-Browser.
CREPO="$TMP/crepo"
mkdir -p "$CREPO/scripts" "$CREPO/deploy" "$CREPO/workflow-pool" "$CREPO/docs"
cp "$SCRIPT" "$CREPO/scripts/instance.sh"; ln -s "$HERE/../lib" "$CREPO/scripts/lib"
cp "$HERE/../../deploy/compose.yml" "$CREPO/deploy/"; echo "FROM scratch" > "$CREPO/deploy/Dockerfile"
echo '{"id":"shared"}' > "$CREPO/workflow-pool/shared.json"; echo '{"id":"gone"}' > "$CREPO/workflow-pool/gone.json"
echo alt > "$CREPO/docs/page.md"
git -C "$CREPO" init -q && git -C "$CREPO" add -A && git -C "$CREPO" -c user.name=t -c user.email=t@t commit -qm eins
echo '{"id":"private"}' > "$CREPO/workflow-pool/private.json"; echo "/workflow-pool/private.json" >> "$CREPO/.git/info/exclude"
echo '{"id":"loose"}' > "$CREPO/workflow-pool/loose.json"   # neu, nie hinzugefuegt
echo neu > "$CREPO/docs/page.md"                             # geaendert, nicht committet
rm "$CREPO/workflow-pool/gone.json"                           # geloescht, nicht committet
printf 'MYSURICATES_PORT=19406\n' > "$TMP/inst/ctx.env"
export DOCKER_CTX="$TMP/ctx.tar"; : > "$DOCKER_LOG"
INSTANCE_HEALTH_TIMEOUT=0 bash "$CREPO/scripts/instance.sh" up --name ctx --no-wait > "$TMP/ctx.out" 2>&1; RC=$?
LIST="$(tar -tf "$DOCKER_CTX" 2>/dev/null)"
check "up builds and starts (exit 0)"          '[ "$RC" = 0 ]'
check "context: tracked pool file + Dockerfile" 'grep -qx "workflow-pool/shared.json" <<<"$LIST" && grep -qx "deploy/Dockerfile" <<<"$LIST"'
check "context: excluded private file stays out" '! grep -q "private.json" <<<"$LIST"'
check "context: untracked file stays out"       '! grep -q "loose.json" <<<"$LIST"'
check "context: a changed tracked file as it is" '[ "$(tar -xOf "$DOCKER_CTX" docs/page.md 2>/dev/null)" = neu ]'
check "context: a deleted tracked file is left out" '! grep -q "gone.json" <<<"$LIST"'
check "image first, then up without compose build" '[ "$(grep -n "^build " "$DOCKER_LOG" | cut -d: -f1)" -lt "$(grep -n "up -d --no-build" "$DOCKER_LOG" | cut -d: -f1)" ] && ! grep -q -e "--build$" -e "compose.* build$" "$DOCKER_LOG"'
mv "$CREPO/.git" "$CREPO/.git-weg"; : > "$DOCKER_LOG"
GIT_CEILING_DIRECTORIES="$TMP" INSTANCE_HEALTH_TIMEOUT=0 bash "$CREPO/scripts/instance.sh" up --name ctx --no-wait >/dev/null 2>&1
check "without git: compose builds from the folder" 'grep -q "compose .* build$" "$DOCKER_LOG" && grep -q "up -d --no-build" "$DOCKER_LOG"'
mv "$CREPO/.git-weg" "$CREPO/.git"

check "no bash-4-only syntax"                 '! grep -nE "declare -g?A|mapfile|readarray" "$SCRIPT"'
echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
