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
  "exec "*"api-key") [ -n "${CONTAINER_KEY:-}" ] || exit 1; echo "$CONTAINER_KEY" ;;     # instance.sh key
esac
exit 0
STUB
chmod +x "$TMP/bin/docker"
# XDG_CONFIG_HOME mit: host-mounts.sh liest ai.env von dort und legt daneben die Kopie der Instanz an
export DOCKER_LOG="$TMP/docker.log" PATH="$TMP/bin:$PATH" HOME="$TMP/home" MYSURICATES_INSTANCES_DIR="$TMP/inst" \
       XDG_CONFIG_HOME="$TMP/config" HOST_MOUNTS_NODE_MODULES="$TMP/nm"
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
check "login mode: no fixed login command"    '! grep -q "MYSURICATES_LOGIN_COMMAND" "$TMP/inst/demo.override.yml"'
printf 'MYSURICATES_PORT=19403\nMYSURICATES_AI_FROM_HOST=programs\nMYSURICATES_AI_PROVIDERS=codex\n' > "$TMP/inst/prog.env"
INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name prog --no-wait >/dev/null 2>&1
check "switches come from the instance file"  'grep -q "MYSURICATES_AI_FROM_HOST=programs" "$TMP/inst/prog.override.yml" && grep -q "MYSURICATES_AI_PROVIDERS=codex" "$TMP/inst/prog.override.yml"'
check "programs mode: login command for the card" 'grep -q "MYSURICATES_LOGIN_COMMAND=scripts/instance.sh login" "$TMP/inst/prog.override.yml"'
printf 'MYSURICATES_PORT=19404\nMYSURICATES_AI_FROM_HOST=everything\n' > "$TMP/inst/bad.env"
: > "$DOCKER_LOG"
OUT="$(bash "$SCRIPT" up --name bad --no-wait 2>&1)"; RC=$?
check "invalid mode: up refuses, no compose"  '[ "$RC" = 2 ] && ! grep -q "^compose " "$DOCKER_LOG" && printf "%s" "$OUT" | grep -q "MYSURICATES_AI_FROM_HOST"'
mkdir -p "$TMP/config/mysuricates" "$TMP/nm/@openai/codex"; printf 'OPENAI_API_KEY=sk-test-openai-4711\n' > "$TMP/config/mysuricates/ai.env"
printf 'MYSURICATES_PORT=19405\nMYSURICATES_AI_PROVIDERS=codex\n' > "$TMP/inst/keyed.env"
INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name keyed --no-wait >/dev/null 2>&1
check "login variable: a copy of its own per instance" 'grep -qF "\"$TMP/config/mysuricates/container-env/instance-mysuricates-keyed.env\"" "$TMP/inst/keyed.override.yml" && ! grep -q 4711 "$TMP/inst/keyed.override.yml"'
rm -rf "$TMP/config" "$TMP/nm" "$TMP/inst/keyed.env" "$TMP/inst/keyed.override.yml"
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

echo "1b2) MYSURICATES_PREFIX: own docker names, e.g. for the installer probe"
: > "$DOCKER_LOG"
check "prefix: up builds its own image and project" 'MYSURICATES_PREFIX=probe-x OWNER=probe-x-demo INSTANCE_HEALTH_TIMEOUT=0 bash "$SCRIPT" up --name demo --no-wait >/dev/null 2>&1 && grep -q -- "-t probe-x:local -$" "$DOCKER_LOG" && grep -q -- "-p probe-x-demo " "$DOCKER_LOG" && ! grep -q "mysuricates" "$DOCKER_LOG"'
check "prefix: key reads from its own container" 'MYSURICATES_PREFIX=probe-x OWNER=probe-x-demo CONTAINER_KEY=k bash "$SCRIPT" key --name demo >/dev/null 2>&1; tail -1 "$DOCKER_LOG" | grep -q " probe-x-demo cat "'
check "prefix: invalid characters refused (exit 2)" 'MYSURICATES_PREFIX="Probe x" bash "$SCRIPT" status --name demo >/dev/null 2>&1; [ $? -eq 2 ]'
check "compose derives every name from the prefix" '[ "$(grep -c "MYSURICATES_CONTAINER:?" "$HERE/../../deploy/compose.yml")" = 5 ] && grep -q "image: \${MYSURICATES_IMAGE:?}" "$HERE/../../deploy/compose.yml" && ! grep -q "mysuricates-\\\${MYSURICATES_NAME" "$HERE/../../deploy/compose.yml"'

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
check "container up always builds the image"   'grep -q "^build -f deploy/Dockerfile --target runtime --build-arg APT_REFRESH= -t mysuricates:local -$" "$DOCKER_LOG" && grep -q "up -d --no-build" "$DOCKER_LOG"'

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
# Ein neuer Wert holt die Fremd-Repos des Images frisch (8b7a39b9) — aus der Instanz-Datei oder der Umgebung
printf 'MYSURICATES_APT_REFRESH=2026-10\n' >> "$TMP/inst/ctx.env"; : > "$DOCKER_LOG"
INSTANCE_HEALTH_TIMEOUT=0 bash "$CREPO/scripts/instance.sh" up --name ctx --no-wait >/dev/null 2>&1
check "APT_REFRESH from the instance file goes to the build" 'grep -q "^build .*--build-arg APT_REFRESH=2026-10 -t mysuricates:local -$" "$DOCKER_LOG"'
printf 'MYSURICATES_PORT=19406\n' > "$TMP/inst/ctx.env"; : > "$DOCKER_LOG"
MYSURICATES_APT_REFRESH=2026-11 INSTANCE_HEALTH_TIMEOUT=0 bash "$CREPO/scripts/instance.sh" up --name ctx --no-wait >/dev/null 2>&1
check "... or from the environment"            'grep -q "^build .*--build-arg APT_REFRESH=2026-11 -t mysuricates:local -$" "$DOCKER_LOG"'
mv "$CREPO/.git" "$CREPO/.git-weg"; : > "$DOCKER_LOG"
GIT_CEILING_DIRECTORIES="$TMP" INSTANCE_HEALTH_TIMEOUT=0 bash "$CREPO/scripts/instance.sh" up --name ctx --no-wait >/dev/null 2>&1
check "without git: compose builds from the folder" 'grep -q "compose .* build$" "$DOCKER_LOG" && grep -q "up -d --no-build" "$DOCKER_LOG"'
mv "$CREPO/.git-weg" "$CREPO/.git"

echo "7) running workflow runs: asked read-only, named before down stops them (29820f58)"
# Die Instanz-API spielt ein kleiner Server aus der Python-Standardbibliothek (fake_instance.py) —
# echte HTTP-Antworten fuer das echte curl. Er zaehlt nur mit dem richtigen Key; ein Workflow-Name,
# der wie das Feld aussieht, darf die Zahl nicht verfaelschen.
LIBRUNS="$HERE/../lib/instance-runs.sh"
FAKE="$HERE/fake_instance.py"
check "lib: bash -n"                           'bash -n "$LIBRUNS"'
base() { ( . "$LIBRUNS"; instance_api_base "$1" 19999 ); }
check "address: loopback and wildcard → 127.0.0.1" '[ "$(base "")" = http://127.0.0.1:19999 ] && [ "$(base 127.0.0.1)" = http://127.0.0.1:19999 ] && [ "$(base 0.0.0.0)" = http://127.0.0.1:19999 ] && [ "$(base ::)" = http://127.0.0.1:19999 ]'
check "address: a bound address as it is"      '[ "$(base 100.64.1.2)" = http://100.64.1.2:19999 ] && [ "$(base fd00::1)" = "http://[fd00::1]:19999" ] && [ "$(base ::1)" = "http://[::1]:19999" ]'
printf 'MYSURICATES_PORT=1\nMYSURICATES_BIND=0.0.0.0\n' > "$TMP/a.env"; printf 'MYSURICATES_PORT=2\n' > "$TMP/b.env"
check "address before a rewrite: the first per name counts" '( . "$LIBRUNS"; instance_remember_addr x "$TMP/a.env"; instance_remember_addr x "$TMP/b.env"; instance_remember_addr y-1 "$TMP/b.env"; [ "$(instance_prev_addr x)" = "1,0.0.0.0" ] && [ "$(instance_prev_addr y-1)" = "2," ] && [ -z "$(instance_prev_addr z)" ] )'
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
  freeport() { local p="$1"; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }
  APID=""
  # Losgeloest gestartet (kein Kind dieser Shell): beendet ihn down, bleibt kein Zombie zurueck, den
  # kill -0 noch fuer lebendig hielte. stopapi wartet, bis er weg ist — der naechste nimmt denselben Port.
  fakeapi() {  # PORT KEY RUNS — startet die Fake-API und wartet, bis sie antwortet
    APID="$( (python3 "$FAKE" api "$1" "$2" "$3" > /dev/null 2>&1 & echo $!) )"
    for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0; sleep 0.05; done
    return 1
  }
  stopapi() {
    [ -n "$APID" ] || return 0
    kill "$APID" 2>/dev/null
    for _ in $(seq 1 100); do kill -0 "$APID" 2>/dev/null || break; sleep 0.05; done
    APID=""
  }
  trap 'stopapi; rm -rf "$TMP"' EXIT
  runs() { ( . "$LIBRUNS"; instance_running_runs "$@" ); }
  RKEY="key-29820f58-$$"; RPORT="$(freeport 19430)"
  fakeapi "$RPORT" "$RKEY" 2
  check "lib: counts the running runs"           '[ "$(runs "http://127.0.0.1:$RPORT" "$RKEY")" = 2 ]'
  check "lib: wrong key → unknown, not 0"        '[ -z "$(runs "http://127.0.0.1:$RPORT" wrong-key)" ]'
  check "lib: no key → unknown, nothing asked"   '[ -z "$(runs "http://127.0.0.1:$RPORT" "")" ]'
  mkdir -p "$TMP/curlspy"
  printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> "%s"\nexec "%s" "$@"\n' "$TMP/curlspy.log" "$(command -v curl)" > "$TMP/curlspy/curl"; chmod +x "$TMP/curlspy/curl"
  check "lib: the key never shows in curl's arguments" '[ "$(PATH="$TMP/curlspy:$PATH" runs "http://127.0.0.1:$RPORT" "$RKEY")" = 2 ] && [ -s "$TMP/curlspy.log" ] && ! grep -qF "$RKEY" "$TMP/curlspy.log"'
  check "lib: a proxy from the environment is not used" '[ "$(http_proxy=http://127.0.0.1:9 HTTP_PROXY=http://127.0.0.1:9 all_proxy=http://127.0.0.1:9 runs "http://127.0.0.1:$RPORT" "$RKEY")" = 2 ]'
  stopapi
  check "lib: instance not answering → unknown"  '[ -z "$(runs "http://127.0.0.1:$RPORT" "$RKEY")" ]'
  # Host: die Fake-API ist der Prozess der Instanz — down meldet die Runs und beendet ihn dann
  printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\n' "$RPORT" > "$TMP/inst/runs.env"
  RSTATE="$TMP/state/mysuricates/runs"; mkdir -p "$RSTATE/home/.autotrigger"
  printf '%s\n' "$RKEY" > "$RSTATE/home/.autotrigger/api-key"
  fakeapi "$RPORT" "$RKEY" 3; echo "$APID" > "$RSTATE/instance.pid"
  OUT="$(bash "$SCRIPT" down --name runs 2>&1)"
  check "host down: names the runs it aborts, then stops" 'printf "%s" "$OUT" | grep -qF "mysuricates-runs: 3 Workflow-Run(s) laufen noch" && printf "%s" "$OUT" | grep -q "gestoppt" && ! kill -0 "$APID" 2>/dev/null'
  stopapi
  fakeapi "$RPORT" "$RKEY" 0; echo "$APID" > "$RSTATE/instance.pid"
  OUT="$(bash "$SCRIPT" down --name runs 2>&1)"
  check "host down: no runs, no warning"         '! printf "%s" "$OUT" | grep -q "Workflow-Run" && printf "%s" "$OUT" | grep -q "gestoppt"'
  stopapi
  printf 'other-key\n' > "$RSTATE/home/.autotrigger/api-key"
  fakeapi "$RPORT" "$RKEY" 3; echo "$APID" > "$RSTATE/instance.pid"
  OUT="$(bash "$SCRIPT" down --name runs 2>&1)"
  check "host down: not checkable → stops anyway"  '! printf "%s" "$OUT" | grep -q "Workflow-Run" && printf "%s" "$OUT" | grep -q "gestoppt" && ! kill -0 "$APID" 2>/dev/null'
  stopapi
  # Container: Key aus dem Container (docker exec), Port aus der Instanz-Datei
  printf 'MYSURICATES_PORT=%s\n' "$RPORT" > "$TMP/inst/cruns.env"
  fakeapi "$RPORT" "$RKEY" 1; : > "$DOCKER_LOG"
  OUT="$(OWNER=mysuricates-cruns CONTAINER_KEY="$RKEY" bash "$SCRIPT" down --name cruns 2>&1)"
  check "container down: names the runs, then compose down" 'printf "%s" "$OUT" | grep -qF "mysuricates-cruns: 1 Workflow-Run(s) laufen noch" && tail -n 1 "$DOCKER_LOG" | grep -q "mysuricates-cruns.* down$"'
  OUT="$(OWNER=mysuricates-cruns bash "$SCRIPT" down --name cruns 2>&1)"
  check "container down: no key (not running) → no warning" '! printf "%s" "$OUT" | grep -q "Workflow-Run" && tail -n 1 "$DOCKER_LOG" | grep -q " down$"'
  : > "$DOCKER_LOG"; OUT="$(OWNER=ai-automation-backend CONTAINER_KEY="$RKEY" bash "$SCRIPT" down --name cruns 2>&1)"
  check "container down: a foreign container is not asked" '! grep -q "^exec " "$DOCKER_LOG" && ! printf "%s" "$OUT" | grep -q "Workflow-Run" && tail -n 1 "$DOCKER_LOG" | grep -q -- "-p mysuricates-cruns .* down$"'
  stopapi
  rm -f "$TMP/inst/runs.env" "$TMP/inst/cruns.env" "$TMP/inst/cruns.override.yml"
else
  echo "  skip (no python3 or curl)"
fi

echo "8) a proxy from the environment never gets a request meant for the own instance (bb4f60a5)"
# curl 8.5 schickt auch 127.0.0.1 ueber http_proxy/all_proxy: hinter einem Proxy scheiterte health_ok —
# up wartete 300 s und meldete „nicht gesund“ —, und ein Key ginge an ihn mit. Der Fake-Proxy schreibt
# alles auf, was bei ihm ankommt, und antwortet 502: er darf nichts bekommen.
LIBCURL="$HERE/../lib/local-curl.sh"
check "lib local-curl: bash -n"                'bash -n "$LIBCURL"'
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
  PXLOG="$TMP/proxy.log"; : > "$PXLOG"
  fakeapi "$RPORT" "$RKEY" 2
  PXPORT="$(freeport $((RPORT + 1)))"
  PXPID="$( (python3 "$FAKE" proxy "$PXPORT" "$PXLOG" > /dev/null 2>&1 & echo $!) )"
  for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$PXPORT") 2>/dev/null && break; sleep 0.05; done
  UPSTATE="$TMP/state/mysuricates/pxup"
  trap 'stopapi; kill "$PXPID" $(cat "$UPSTATE/instance.pid" 2>/dev/null) 2>/dev/null; rm -rf "$TMP"' EXIT
  PX="http://127.0.0.1:$PXPORT"
  # proxied BEFEHL… — mit dem Fake-Proxy in jeder Variablen, die curl liest; der Log beginnt leer
  proxied() { : > "$PXLOG"; env -u no_proxy -u NO_PROXY http_proxy="$PX" https_proxy="$PX" all_proxy="$PX" HTTP_PROXY="$PX" HTTPS_PROXY="$PX" ALL_PROXY="$PX" "$@"; }
  untouched() { [ ! -s "$PXLOG" ]; }   # der Proxy hat seit dem letzten proxied nichts bekommen
  proxied curl -s --max-time 3 "http://127.0.0.1:$RPORT/api/v1/system/health" >/dev/null
  check "control: a plain curl goes through the fake proxy" '! untouched'
  lib() { proxied env PATH="$TMP/curlspy:$PATH" bash -c ". '$LIBCURL'; $1"; }
  RUNS_URL="http://127.0.0.1:$RPORT/api/v1/executions?status=RUNNING&size=1"
  check "lib: local_curl reaches the instance directly"  'lib "local_curl -fsS --max-time 3 http://127.0.0.1:$RPORT/api/v1/system/health" | grep -q UP && untouched'
  : > "$TMP/curlspy.log"
  check "lib: local_curl_key — the key reaches the instance" 'lib "local_curl_key $RKEY -fsS --max-time 3 \"$RUNS_URL\"" | grep -q "\"totalElements\": 2" && untouched'
  check "... never curl's arguments"             '[ -s "$TMP/curlspy.log" ] && ! grep -qF "$RKEY" "$TMP/curlspy.log"'
  check "lib: empty key → no header (401)"       '[ "$(lib "local_curl_key \"\" -s -o /dev/null -w %{http_code} \"$RUNS_URL\"")" = 401 ] && untouched'
  # Host: die Fake-API ist der Prozess der Instanz
  printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\n' "$RPORT" > "$TMP/inst/px.env"
  PXSTATE="$TMP/state/mysuricates/px"; mkdir -p "$PXSTATE/home/.autotrigger"
  printf '%s\n' "$RKEY" > "$PXSTATE/home/.autotrigger/api-key"; echo "$APID" > "$PXSTATE/instance.pid"
  check "host status: healthy although a proxy is set" '[ "$(proxied bash "$SCRIPT" status --name px)" = "running healthy" ] && untouched'
  OUT="$(proxied bash "$SCRIPT" down --name px 2>&1)"
  check "host down: runs asked directly, then stopped" 'printf "%s" "$OUT" | grep -qF "mysuricates-px: 2 Workflow-Run(s) laufen noch" && printf "%s" "$OUT" | grep -q gestoppt && untouched'
  stopapi
  # up wartet auf Health — der java-Stub startet die Fake-API auf dem Port der Instanz
  mkdir -p "$TMP/pxbin" "$UPSTATE/frontend"; echo jar > "$UPSTATE/app.jar"
  printf '#!/bin/sh\nexec python3 "%s" api "$AUTOMATION_PORT" "%s" 0\n' "$FAKE" "$RKEY" > "$TMP/pxbin/java"; chmod +x "$TMP/pxbin/java"
  printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\n' "$RPORT" > "$TMP/inst/pxup.env"
  OUT="$(proxied env PATH="$TMP/pxbin:$PATH" INSTANCE_HEALTH_TIMEOUT=6 GIT_CEILING_DIRECTORIES="$TMP" bash "$HSCRIPT" up --name pxup 2>&1)"; RC=$?
  check "host up: healthy although a proxy is set" '[ "$RC" = 0 ] && printf "%s" "$OUT" | grep -qF "mysuricates-pxup läuft: http://localhost:$RPORT" && untouched'
  env PATH="$TMP/pxbin:$PATH" bash "$HSCRIPT" down --name pxup >/dev/null 2>&1
  kill "$PXPID" 2>/dev/null
  rm -f "$TMP/inst/px.env" "$TMP/inst/pxup.env"
else
  echo "  skip (no python3 or curl)"
fi
# Jeder Aufruf gegen die eigene Instanz geht ueber lib/local-curl.sh — ein neues nacktes curl faellt hier
# auf. Ausgenommen: Downloads aus dem Netz (lib/deps.sh, sie brauchen den Proxy), Installationshinweise
# als Text (lib/provider-catalog.generated.sh) und die Tests mit ihren Stubs. git grep --untracked: auch
# neue Dateien, aber keine ignorierten wie die private Kopie scripts/seed-workflows.sh. Der Aufruf im
# Helfer selbst muss gefunden werden — sonst hat die Suche nichts gesehen.
CURLS="$(cd "$HERE/.." && git grep --untracked -nIE "(^|[^[:alnum:]_-])curl[[:space:]]+(-|\"|'|\\\$|http|\\\\)" -- . 2>/dev/null)"
NAKED="$(printf '%s\n' "$CURLS" | grep -vE '^(tests/|lib/local-curl\.sh:|lib/deps\.sh:|lib/provider-catalog\.generated\.sh:)' \
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#')"
check "every curl to the own instance goes through lib/local-curl.sh" 'printf "%s" "$CURLS" | grep -q "^lib/local-curl.sh:" && { [ -z "$NAKED" ] || { printf "%s\n" "$NAKED" | sed "s/^/         /"; false; }; }'

echo "9) bound to one address: health, port and runs asked there, not on 127.0.0.1 (3d002903)"
# Mit --bind auf eine Adresse (LAN, Tailscale) lauscht die Instanz nur dort. health_ok fragte 127.0.0.1 —
# up wartete 300 s und meldete „nicht gesund“ —, port_busy sah einen belegten Port dort nicht, und
# instance_api_base nahm jede 127.*-Adresse fuer 127.0.0.1. Eine zweite Loopback-Adresse spielt die
# gebundene; der java-Stub startet die Fake-API wie Spring auf SERVER_ADDRESS. Auf 127.0.0.1 lauscht
# derweil jemand anderes auf demselben Port und antwortet 502: dort darf nichts als gesund gelten.
check "address: another loopback address as it is" '[ "$(base 127.0.0.2)" = http://127.0.0.2:19999 ] && [ "$(base localhost)" = http://127.0.0.1:19999 ] && [ "$(base "[::1]")" = "http://[::1]:19999" ] && [ "$(base "[::]")" = http://127.0.0.1:19999 ]'
BADDR=127.0.0.2
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1 \
   && python3 -c "import socket; socket.socket().bind(('$BADDR', 0))" 2>/dev/null; then
  # frei auf beiden Adressen
  bfree() { local p="$1"; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null || (exec 3<>"/dev/tcp/$BADDR/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }
  listen() {  # HOST MODUS PORT … — fake_instance.py auf HOST, losgeloest; gibt die PID aus, sobald er annimmt
    local host="$1" pid; shift
    pid="$( (FAKE_INSTANCE_HOST="$host" python3 "$FAKE" "$@" > /dev/null 2>&1 & echo $!) )"
    for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/$host/$2") 2>/dev/null && break; sleep 0.05; done
    echo "$pid"
  }
  gone() { kill "$1" 2>/dev/null; for _ in $(seq 1 100); do kill -0 "$1" 2>/dev/null || break; sleep 0.05; done; }
  BPORT="$(bfree 19470)"; OTHER=""; FOREIGN=""
  BSTATE="$TMP/state/mysuricates/bound"; mkdir -p "$BSTATE/frontend" "$BSTATE/home/.autotrigger" "$TMP/bbin"
  echo jar > "$BSTATE/app.jar"; printf 'bkey\n' > "$BSTATE/home/.autotrigger/api-key"
  trap 'stopapi; kill $(cat "$BSTATE/instance.pid" 2>/dev/null) $OTHER $FOREIGN 2>/dev/null; rm -rf "$TMP"' EXIT
  printf '#!/bin/sh\nexec env FAKE_INSTANCE_HOST="$SERVER_ADDRESS" python3 "%s" api "$AUTOMATION_PORT" bkey 1\n' "$FAKE" > "$TMP/bbin/java"
  chmod +x "$TMP/bbin/java"
  printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\nMYSURICATES_BIND=%s\n' "$BPORT" "$BADDR" > "$TMP/inst/bound.env"
  binst() { env PATH="$TMP/bbin:$PATH" INSTANCE_HEALTH_TIMEOUT=6 GIT_CEILING_DIRECTORIES="$TMP" bash "$HSCRIPT" "$@" --name bound 2>&1; }
  OTHER="$(listen 127.0.0.1 proxy "$BPORT" "$TMP/other.log")"
  OUT="$(binst up)"; RC=$?
  check "up: healthy on the bound address; 127.0.0.1 taken by someone else does not count" '[ "$RC" = 0 ] && printf "%s" "$OUT" | grep -qF "mysuricates-bound läuft: http://$BADDR:$BPORT"'
  check "status: running healthy"                 '[ "$(binst status)" = "running healthy" ]'
  OUT="$(binst down)"
  check "down: the runs asked at the bound address" 'printf "%s" "$OUT" | grep -qF "mysuricates-bound: 1 Workflow-Run(s) laufen noch" && printf "%s" "$OUT" | grep -q gestoppt'
  gone "$OTHER"; OTHER=""
  # Jemand anderes auf der gebundenen Adresse: up lehnt ab, bevor es etwas startet
  FOREIGN="$(listen "$BADDR" api "$BPORT" x 0)"
  OUT="$(binst up)"; RC=$?
  check "up: port taken on the bound address → refused, nothing started" '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep -qF "Port $BPORT ist schon belegt" && [ ! -s "$BSTATE/instance.pid" ]'
  gone "$FOREIGN"; FOREIGN=""
  rm -f "$TMP/inst/bound.env"
else
  echo "  skip (no python3 or curl, or $BADDR is not a loopback address here — macOS)"
fi

check "no bash-4-only syntax"                 '! grep -nE "declare -g?A|mapfile|readarray" "$SCRIPT"'
echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
