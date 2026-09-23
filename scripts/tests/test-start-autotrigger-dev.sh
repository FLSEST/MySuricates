#!/usr/bin/env bash
# Tests for scripts/start-autotrigger-dev.sh — JAR runtime handling and the restart run guard.
# Run: bash scripts/tests/test-start-autotrigger-dev.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../start-autotrigger-dev.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Source the script's functions in a subshell with an isolated state dir and a fake repo.
# $1 = extra shell code to run after sourcing (evaluated inside the subshell).
run_in_script() {
  (
    export HOME="$TMP/home" XDG_STATE_HOME="$TMP/state"
    mkdir -p "$HOME" "$XDG_STATE_HOME"
    # shellcheck disable=SC1090
    source "$SCRIPT" >/dev/null 2>&1
    set +e
    REPO_ROOT="$TMP/repo"
    eval "$1"
  )
}

# Fake git repo so runtime_jar_label() has a sha and a clean/dirty state.
mkdir -p "$TMP/repo"
git -C "$TMP/repo" init -q
git -C "$TMP/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init
SHA="$(git -C "$TMP/repo" rev-parse --short HEAD)"

echo "1) runtime jar label"
LABEL="$(run_in_script 'runtime_jar_label')"
check "label carries the short sha ($SHA)"        '[[ "$LABEL" == *"-$SHA" ]]'
check "clean tree has no -dirty suffix"          '[[ "$LABEL" != *-dirty ]]'
echo "x" > "$TMP/repo/tracked.txt"; git -C "$TMP/repo" add tracked.txt; git -C "$TMP/repo" -c user.name=t -c user.email=t@t commit -q -m t
echo "y" > "$TMP/repo/tracked.txt"
LABEL_DIRTY="$(run_in_script 'runtime_jar_label')"
check "modified tracked file yields -dirty"      '[[ "$LABEL_DIRTY" == *-dirty ]]'
git -C "$TMP/repo" checkout -q -- tracked.txt

echo "2) install_runtime_jar: copy, symlink, rotation"
for i in 1 2 3 4; do echo "jar$i" > "$TMP/fake$i.jar"; done
OUT="$(run_in_script '
  for i in 1 2 3 4; do install_runtime_jar "'"$TMP"'/fake$i.jar" >/dev/null; sleep 1.1; done
  ls -1 "$RUNTIME_DIR"/backend-*.jar | wc -l
  cat "$RUNTIME_DIR/current.jar"
  readlink -f "$RUNTIME_DIR/current.jar"
')"
COUNT="$(sed -n 1p <<<"$OUT")"; CURRENT_CONTENT="$(sed -n 2p <<<"$OUT")"; CURRENT_TARGET="$(sed -n 3p <<<"$OUT")"
check "keeps only 2 jars after 4 installs (got $COUNT)"  '[ "$COUNT" -eq 2 ]'
check "current.jar points at the newest install"        '[ "$CURRENT_CONTENT" = "jar4" ]'
check "current.jar is a symlink into runtime dir"       '[[ "$CURRENT_TARGET" == *"/runtime/backend-"*".jar" ]]'
check "missing jar is rejected"                         '! run_in_script "install_runtime_jar /nonexistent.jar" >/dev/null 2>&1'

echo "3) restart guard against running workflow runs"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Fake curl: answer the executions query with the JSON from $FAKE_RUNS_JSON, fail otherwise.
if [[ "$*" == *"/executions?status=RUNNING"* ]]; then printf '%s' "$FAKE_RUNS_JSON"; exit 0; fi
exit 22
STUB
chmod +x "$TMP/bin/curl"
guard() {  # $1 = json, $2 = FORCE
  run_in_script "export PATH=\"$TMP/bin:\$PATH\" MYSURICATES_API_KEY=k FAKE_RUNS_JSON='$1'; FORCE=$2; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?"
}
check "blocks (exit 3) when one run is active (page)"    '[ "$(guard "{\"content\":[{\"id\":\"r\"}],\"totalElements\":1}" 0)" = 3 ]'
check "blocks when runs come as a bare array"            '[ "$(guard "[{\"id\":\"r\"}]" 0)" = 3 ]'
check "passes when no run is active"                     '[ "$(guard "{\"content\":[],\"totalElements\":0}" 0)" = 0 ]'
check "--force overrides an active run"                  '[ "$(guard "{\"content\":[{\"id\":\"r\"}],\"totalElements\":1}" 1)" = 0 ]'
check "passes when the API is unreachable"               '[ "$(run_in_script "export PATH=\"$TMP/bin:\$PATH\" MYSURICATES_API_KEY=k MYSURICATES_API_BASE=http://x/api/v1 FAKE_RUNS_JSON=; API_BASE=http://x/api/v1; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?")" = 0 ]'
check "passes without any API key"                       '[ "$(run_in_script "unset MYSURICATES_API_KEY; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?")" = 0 ]'

echo "4) stale pid file handling"
OUT="$(run_in_script '
  echo 999999 > "$BACKEND_PID_FILE"
  discard_stale_pid_file "$BACKEND_PID_FILE" "$BACKEND_PROCESS_PATTERN" Backend >/dev/null
  [ -f "$BACKEND_PID_FILE" ] && echo kept || echo removed
')"
check "pid file of a dead process is removed"            '[ "$OUT" = removed ]'
OUT="$(run_in_script '
  sleep 30 & echo $! > "$BACKEND_PID_FILE"
  discard_stale_pid_file "$BACKEND_PID_FILE" "$BACKEND_PROCESS_PATTERN" Backend >/dev/null
  [ -f "$BACKEND_PID_FILE" ] && echo kept || echo removed
  kill %1 2>/dev/null
')"
check "pid file of a foreign process (sleep) is removed" '[ "$OUT" = removed ]'

echo "5) help and syntax"
check "bash -n passes"                                   'bash -n "$SCRIPT"'
check "--help prints usage without side effects"         '"$SCRIPT" --help | grep "restart" >/dev/null'
check "--help documents build"                           '"$SCRIPT" --help | grep " build " >/dev/null'

echo "6) build: only the runtime jar, nothing started (setup.sh builds once)"
mkdir -p "$TMP/backend"
cat > "$TMP/backend/mvnw" <<'STUB'
#!/usr/bin/env bash
# Fake Maven wrapper: log the call, drop a fat jar where build_backend_jar looks for it.
echo "$*" >> "$(dirname "$0")/mvn-calls.log"
mkdir -p "$(dirname "$0")/target" && echo "fat" > "$(dirname "$0")/target/ai-automation-backend-9.9.9.jar"
STUB
chmod +x "$TMP/backend/mvnw"
OUT="$(run_in_script '
  BACKEND_DIR="'"$TMP"'/backend"; MVN_CMD="$BACKEND_DIR/mvnw"; COMMAND=build; RUNTIME_MODE=host
  ( main >/dev/null 2>&1 ); echo "rc=$?"
  cat "$RUNTIME_DIR/current.jar" 2>/dev/null || echo no-jar
  [ -e "$BACKEND_PID_FILE" ] || [ -e "$FRONTEND_PID_FILE" ] && echo started || echo nothing-started
')"
check "build exits 0"                                    '[ "$(sed -n 1p <<<"$OUT")" = rc=0 ]'
check "build installs current.jar from target/"          '[ "$(sed -n 2p <<<"$OUT")" = fat ]'
check "build starts neither backend nor frontend"        '[ "$(sed -n 3p <<<"$OUT")" = nothing-started ]'
check "build runs clean package once, tests skipped"     '[ "$(grep -c "clean package -DskipTests" "$TMP/backend/mvn-calls.log")" -eq 1 ]'

echo "7) the frontend outlives the terminal it was started from (454f8ac5)"
# Node resets signal dispositions at startup, so `nohup` alone did not protect Vite: closing the
# terminal sent SIGHUP and the frontend died while the backend lived on. `script` plays the
# terminal that closes; the fake npm is a plain node process, like `npm run dev` → vite.
if command -v node >/dev/null 2>&1 && command -v script >/dev/null 2>&1; then
  mkdir -p "$TMP/npm-bin" "$TMP/fe"
  printf '#!/usr/bin/env bash\nexec node -e "setInterval(() => {}, 1000)"\n' > "$TMP/npm-bin/npm"
  chmod +x "$TMP/npm-bin/npm"
  cat > "$TMP/frontend-case.sh" <<CASE
export HOME="$TMP/home" XDG_STATE_HOME="$TMP/state" PATH="$TMP/npm-bin:\$PATH"
source "$SCRIPT" >/dev/null 2>&1
FRONTEND_DIR="$TMP/fe"
frontend_is_ready() { return 1; }
start_frontend >/dev/null 2>&1
sleep 1
CASE
  script -qec "bash $TMP/frontend-case.sh" /dev/null >/dev/null 2>&1
  sleep 1
  FE_PID="$(cat "$TMP/state/autotrigger-dev/frontend.pid" 2>/dev/null)"
  check "frontend still runs after the terminal closed"  '[ -n "$FE_PID" ] && kill -0 "$FE_PID" 2>/dev/null'
  check "it leads its own session (no controlling tty)"  '[ "$(ps -o sid= -p "$FE_PID" 2>/dev/null | tr -d " ")" = "$FE_PID" ]'
  check "the pid file names the node process itself"     '[[ "$(ps -o args= -p "$FE_PID" 2>/dev/null)" == node* ]]'
  [ -n "$FE_PID" ] && kill "$FE_PID" 2>/dev/null
else
  echo "  skip (node or script missing)"
fi

echo "8) dev container override comes from scripts/lib/host-mounts.sh (262948a4)"
check "starter sources the mount library"   'grep -q "lib/host-mounts.sh" "$SCRIPT"'
mkdir -p "$TMP/nm"
OVR='export HOST_MOUNTS_ASSUME_SOCKET=1 AUTOTRIGGER_DEV_NODE_MODULES="'"$TMP"'/nm"; mkdir -p "$HOME/.claude"; COMPOSE_OVERRIDE="'"$TMP"'/ov.yml"'
OUT="$(run_in_script "$OVR"'; write_compose_override >/dev/null 2>&1; cat "$COMPOSE_OVERRIDE"')"
check "override names the backend service"  'grep -q "^  backend:" <<<"$OUT"'
check "default login: no login directory"   '! grep -q "/.claude:" <<<"$OUT" && grep -q "MYSURICATES_AI_FROM_HOST=login" <<<"$OUT"'
check "socket on by default, as before"     'grep -q "/var/run/docker.sock:/var/run/docker.sock" <<<"$OUT"'
check "switches named for the settings"     'grep -q "MYSURICATES_SWITCH_PREFIX=AUTOTRIGGER_DEV_" <<<"$OUT"'
OUT="$(run_in_script "$OVR"'; AUTOTRIGGER_DEV_DOCKER_SOCKET=0 AUTOTRIGGER_DEV_AI_FROM_HOST=all write_compose_override >/dev/null 2>&1; cat "$COMPOSE_OVERRIDE"')"
check "all + socket off are honored"        'grep -q "/.claude:" <<<"$OUT" && ! grep -q "docker.sock" <<<"$OUT"'
RC="$(run_in_script "$OVR"'; AUTOTRIGGER_DEV_AI_FROM_HOST=everything write_compose_override >/dev/null 2>&1; echo $?')"
check "invalid mode refuses the start"      '[ "$RC" = 2 ]'
check "compose file no longer hard-wires the socket" '! grep -q "docker.sock:/var/run/docker.sock" "$HERE/../../ai-automation-backend/docker-compose.dev-container.yml"'

echo "9) --container builds the JAR the image copies (3bfa4e10)"
# Dockerfile.stable kopiert target/ai-automation-backend-*.jar und kompiliert nichts. Ohne Maven-Lauf
# lief nach einer Code-Aenderung still das zuletzt auf dem Host gebaute JAR.
mkdir -p "$TMP/cbin" "$TMP/cnm"
cat > "$TMP/cbin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
case "$*" in *" up "*) touch "$UP_MARKER" ;; *" down"*) rm -f "$UP_MARKER" ;; esac
exit 0
STUB
cat > "$TMP/cbin/curl" <<'STUB'
#!/usr/bin/env bash
# Antwortet erst, wenn der Container "laeuft" (der docker-Stub legt die Marke an)
[[ "$*" == *"/system/info"* && -e "$UP_MARKER" ]] && exit 0
exit 7
STUB
cat > "$TMP/backend/mvnw" <<'STUB'
#!/usr/bin/env bash
echo "mvn $*" >> "$CALLS"
mkdir -p "$(dirname "$0")/target" && echo "fat" > "$(dirname "$0")/target/ai-automation-backend-9.9.9.jar"
STUB
chmod +x "$TMP/cbin/docker" "$TMP/cbin/curl" "$TMP/backend/mvnw"
CTX='export PATH="'"$TMP"'/cbin:$PATH" CALLS="'"$TMP"'/calls.log" UP_MARKER="'"$TMP"'/up.marker" AUTOTRIGGER_DEV_NODE_MODULES="'"$TMP"'/cnm"
  BACKEND_DIR="'"$TMP"'/backend"; MVN_CMD="$BACKEND_DIR/mvnw"; COMPOSE_OVERRIDE="'"$TMP"'/cov.yml"; RUNTIME_MODE=container'
fresh() { rm -rf "$TMP/backend/target" "$TMP/up.marker" "$TMP/state/autotrigger-dev/runtime"; : > "$TMP/calls.log"; }
fresh
RC="$(run_in_script "$CTX; COMMAND=build; ( main >/dev/null 2>&1 ); echo \$?")"
check "build --container: exit 0, maven once"            '[ "$RC" = 0 ] && [ "$(grep -c "^mvn .*clean package -DskipTests" "$TMP/calls.log")" = 1 ]'
check "build --container: no image, nothing started"     '! grep -q "^docker" "$TMP/calls.log" && [ ! -e "$TMP/state/autotrigger-dev/runtime/current.jar" ]'
fresh
RC="$(run_in_script "$CTX; COMMAND=restart; ( main >/dev/null 2>&1 ); echo \$?")"
check "restart --container: exit 0"                      '[ "$RC" = 0 ]'
check "restart --container: build, then down, then up"   '[ "$(awk "/^mvn .*clean package/{print \"mvn\"} /^docker compose .* down\$/{print \"down\"} /^docker compose .* up -d --build\$/{print \"up\"}" "$TMP/calls.log" | tr "\n" " ")" = "mvn down up " ]'
fresh; mkdir -p "$TMP/backend/target"; echo alt > "$TMP/backend/target/ai-automation-backend-9.9.9.jar"
OUT="$(run_in_script "$CTX; start_backend_container missing 2>&1")"
check "start --container with a JAR: no build, names it" '! grep -q "^mvn" "$TMP/calls.log" && grep -q "Verwende vorhandenes JAR" <<<"$OUT" && grep -q "up -d --build" "$TMP/calls.log"'
fresh
OUT="$(run_in_script "$CTX; start_backend_container missing 2>&1")"
check "start --container without a JAR: builds first"   '[ "$(head -1 "$TMP/calls.log" | cut -c1-3)" = mvn ] && grep -q "up -d --build" "$TMP/calls.log"'
fresh; mkdir -p "$TMP/backend/target"; echo alt > "$TMP/backend/target/ai-automation-backend-9.9.9.jar"
OUT="$(run_in_script "$CTX; REBUILD=1; start_backend_container missing 2>&1")"
check "start --container --rebuild: builds"              'grep -q "^mvn" "$TMP/calls.log"'
fresh
cat > "$TMP/backend/mvnw" <<'STUB'
#!/usr/bin/env bash
echo "mvn $*" >> "$CALLS"; exit 1
STUB
RC="$(run_in_script "$CTX; COMMAND=restart; ( main >/dev/null 2>&1 ); echo \$?")"
check "maven fails: restart stops before touching the container" '[ "$RC" != 0 ] && ! grep -q "^docker" "$TMP/calls.log"'

echo "10) --container: the checks follow AUTOTRIGGER_DEV_CONTAINER_PORT (3bfa4e10)"
URL="$(AUTOTRIGGER_DEV_RUNTIME=container AUTOTRIGGER_DEV_CONTAINER_PORT=9190 run_in_script 'echo "$BACKEND_READY_URL"')"
check "container port 9190 → readiness on :9190"         '[ "$URL" = "http://localhost:9190/api/v1/system/info" ]'
URL="$(AUTOTRIGGER_DEV_CONTAINER_PORT=9190 run_in_script 'echo "$BACKEND_READY_URL"')"
check "host mode keeps :9090"                            '[ "$URL" = "http://localhost:9090/api/v1/system/info" ]'
URL="$(AUTOTRIGGER_DEV_RUNTIME=container AUTOTRIGGER_DEV_CONTAINER_PORT=9190 MYSURICATES_API_BASE=http://x:1/api/v1 run_in_script 'echo "$BACKEND_READY_URL"')"
check "MYSURICATES_API_BASE still wins"                  '[ "$URL" = "http://x:1/api/v1/system/info" ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
