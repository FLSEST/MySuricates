#!/usr/bin/env bash
# Tests for scripts/start-mysuricates-dev.sh — JAR runtime handling and the restart run guard.
# Run: bash scripts/tests/test-start-mysuricates-dev.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../start-mysuricates-dev.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Source the script's functions in a subshell with an isolated state dir and a fake repo.
# $1 = extra shell code to run after sourcing (evaluated inside the subshell).
run_in_script() {
  (
    export HOME="$TMP/home" XDG_STATE_HOME="$TMP/state" XDG_CONFIG_HOME="$TMP/config"
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
  FE_PID="$(cat "$TMP/state/mysuricates-dev/frontend.pid" 2>/dev/null)"
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
OVR='export HOST_MOUNTS_ASSUME_SOCKET=1 MYSURICATES_DEV_NODE_MODULES="'"$TMP"'/nm"; mkdir -p "$HOME/.claude"; COMPOSE_OVERRIDE="'"$TMP"'/ov.yml"'
OUT="$(run_in_script "$OVR"'; write_compose_override >/dev/null 2>&1; cat "$COMPOSE_OVERRIDE"')"
check "override names the backend service"  'grep -q "^  backend:" <<<"$OUT"'
check "default login: no login directory"   '! grep -q "/.claude:" <<<"$OUT" && grep -q "MYSURICATES_AI_FROM_HOST=login" <<<"$OUT"'
check "socket off by default"               '! grep -q "docker.sock" <<<"$OUT" && grep -q "MYSURICATES_DOCKER_SOCKET=0" <<<"$OUT"'
check "switches named for the settings"     'grep -q "MYSURICATES_SWITCH_PREFIX=MYSURICATES_DEV_" <<<"$OUT"'
OUT="$(run_in_script "$OVR"'; MYSURICATES_DEV_DOCKER_SOCKET=1 MYSURICATES_DEV_AI_FROM_HOST=all write_compose_override >/dev/null 2>&1; cat "$COMPOSE_OVERRIDE"')"
check "all + socket on purpose are honored" 'grep -q "/.claude:" <<<"$OUT" && grep -q "/var/run/docker.sock:/var/run/docker.sock" <<<"$OUT"'
mkdir -p "$TMP/config/mysuricates" "$TMP/nm/@openai/codex"; printf 'OPENAI_API_KEY=test-openai\n' > "$TMP/config/mysuricates/ai.env"
OUT="$(run_in_script "$OVR"'; MYSURICATES_DEV_AI_PROVIDERS=codex write_compose_override >/dev/null 2>&1; cat "$COMPOSE_OVERRIDE"')"
check "login variable: the dev container's own copy" 'grep -qF "\"$TMP/config/mysuricates/container-env/dev-mysuricates-dev.env\"" <<<"$OUT" && ! grep -q 4711 <<<"$OUT"'
rm -rf "$TMP/config" "$TMP/nm/@openai"
RC="$(run_in_script "$OVR"'; MYSURICATES_DEV_CONTAINER_BIND="0.0.0.0 x" write_compose_override >/dev/null 2>&1; echo $?')"
check "a bind that is no address refuses the start" '[ "$RC" = 2 ]'
DCY="$HERE/../../ai-automation-backend/docker-compose.dev-container.yml"
check "compose file publishes on 127.0.0.1 unless switched" 'grep -qF "host_ip: \"\${MYSURICATES_DEV_CONTAINER_BIND:-127.0.0.1}\"" "$DCY" && [ "$(grep -c "host_ip:" "$DCY")" = 1 ]'
RC="$(run_in_script "$OVR"'; MYSURICATES_DEV_AI_FROM_HOST=everything write_compose_override >/dev/null 2>&1; echo $?')"
check "invalid mode refuses the start"      '[ "$RC" = 2 ]'
check "compose file no longer hard-wires the socket" '! grep -q "docker.sock:/var/run/docker.sock" "$HERE/../../ai-automation-backend/docker-compose.dev-container.yml"'

echo "9) --container builds the JAR the image copies (3bfa4e10)"
# Dockerfile.stable copies target/ai-automation-backend-*.jar and compiles nothing. Without a Maven run
# after a code change the JAR last built on the host silently ran.
mkdir -p "$TMP/cbin" "$TMP/cnm"
cat > "$TMP/cbin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
case "$*" in *" up "*) touch "$UP_MARKER" ;; *" down"*) rm -f "$UP_MARKER" ;; esac
exit 0
STUB
cat > "$TMP/cbin/curl" <<'STUB'
#!/usr/bin/env bash
# Answers only once the container is "running" (the docker stub creates the marker)
[[ "$*" == *"/system/info"* && -e "$UP_MARKER" ]] && exit 0
# Vite on :3000 is "running" as soon as the npm stub has created the marker
[[ "$*" == *":3000"* && -e "$FE_MARKER" ]] && exit 0
exit 7
STUB
# restart also starts a missing Vite — never the real one from the repo (3c75d433)
cat > "$TMP/cbin/npm" <<'STUB'
#!/usr/bin/env bash
echo "npm $*" >> "$CALLS"; touch "$FE_MARKER"
STUB
mkdir -p "$TMP/frontend"
cat > "$TMP/backend/mvnw" <<'STUB'
#!/usr/bin/env bash
echo "mvn $*" >> "$CALLS"
mkdir -p "$(dirname "$0")/target" && echo "fat" > "$(dirname "$0")/target/ai-automation-backend-9.9.9.jar"
STUB
chmod +x "$TMP/cbin/docker" "$TMP/cbin/curl" "$TMP/cbin/npm" "$TMP/backend/mvnw"
CTX='export PATH="'"$TMP"'/cbin:$PATH" CALLS="'"$TMP"'/calls.log" UP_MARKER="'"$TMP"'/up.marker" FE_MARKER="'"$TMP"'/fe.marker" MYSURICATES_DEV_NODE_MODULES="'"$TMP"'/cnm"
  BACKEND_DIR="'"$TMP"'/backend"; FRONTEND_DIR="'"$TMP"'/frontend"; MVN_CMD="$BACKEND_DIR/mvnw"; COMPOSE_OVERRIDE="'"$TMP"'/cov.yml"; RUNTIME_MODE=container'
fresh() { rm -rf "$TMP/backend/target" "$TMP/up.marker" "$TMP/fe.marker" "$TMP/state/mysuricates-dev/runtime" "$TMP/state/mysuricates-dev/frontend.pid"; : > "$TMP/calls.log"; }
fresh
RC="$(run_in_script "$CTX; COMMAND=build; ( main >/dev/null 2>&1 ); echo \$?")"
check "build --container: exit 0, maven once"            '[ "$RC" = 0 ] && [ "$(grep -c "^mvn .*clean package -DskipTests" "$TMP/calls.log")" = 1 ]'
check "build --container: no image, nothing started"     '! grep -q "^docker" "$TMP/calls.log" && [ ! -e "$TMP/state/mysuricates-dev/runtime/current.jar" ]'
fresh
RC="$(run_in_script "$CTX; COMMAND=restart; ( main >/dev/null 2>&1 ); echo \$?")"
check "restart --container: exit 0"                      '[ "$RC" = 0 ]'
check "restart --container: build, then down, then up"   '[ "$(awk "/^mvn .*clean package/{print \"mvn\"} /^docker compose .* down\$/{print \"down\"} /^docker compose .* up -d --build\$/{print \"up\"}" "$TMP/calls.log" | tr "\n" " ")" = "mvn down up " ]'
check "restart: a missing Vite is started once, after the backend" '[ "$(grep -c "^npm run dev" "$TMP/calls.log")" = 1 ] && [ "$(tail -1 "$TMP/calls.log")" = "npm run dev" ]'
fresh; touch "$TMP/fe.marker"
RC="$(run_in_script "$CTX; COMMAND=restart; ( main >/dev/null 2>&1 ); echo \$?")"
check "restart: a running Vite is left alone"            '[ "$RC" = 0 ] && ! grep -q "^npm" "$TMP/calls.log"'
fresh
OUT="$(run_in_script "$CTX; wait_for_frontend() { return 1; }; COMMAND=restart; ( main 2>&1 ); echo \"rc=\$?\"")"
check "restart: Vite not coming up only warns"           'grep -q "^rc=0$" <<<"$OUT" && grep -q "Warnung: Frontend laeuft nicht" <<<"$OUT"'
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
check "maven fails: restart stops before touching the container" '[ "$RC" != 0 ] && ! grep -q "^docker" "$TMP/calls.log" && ! grep -q "^npm" "$TMP/calls.log"'

echo "10) --container: the checks follow MYSURICATES_DEV_CONTAINER_PORT (3bfa4e10)"
URL="$(MYSURICATES_DEV_RUNTIME=container MYSURICATES_DEV_CONTAINER_PORT=9190 run_in_script 'echo "$BACKEND_READY_URL"')"
check "container port 9190 → readiness on :9190"         '[ "$URL" = "http://localhost:9190/api/v1/system/info" ]'
URL="$(MYSURICATES_DEV_CONTAINER_PORT=9190 run_in_script 'echo "$BACKEND_READY_URL"')"
check "host mode keeps :9090"                            '[ "$URL" = "http://localhost:9090/api/v1/system/info" ]'
URL="$(MYSURICATES_DEV_RUNTIME=container MYSURICATES_DEV_CONTAINER_PORT=9190 MYSURICATES_API_BASE=http://x:1/api/v1 run_in_script 'echo "$BACKEND_READY_URL"')"
check "MYSURICATES_API_BASE still wins"                  '[ "$URL" = "http://x:1/api/v1/system/info" ]'

echo "11) a proxy from the environment never gets a request meant for the dev backend (bb4f60a5)"
# curl 8.5 also sends localhost via http_proxy/https_proxy: the readiness check ran via the
# proxy, and the run guard before restart sent it the API key — which also stood in the curl arguments,
# visible in the process list. Real HTTP answers for the real curl: tests/fake_instance.py;
# the fake proxy writes down everything that reaches it and answers 502 — it must receive nothing.
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
  FAKE="$HERE/fake_instance.py"; PXLOG="$TMP/proxy.log"; RKEY="key-bb4f60a5-$$"
  freeport() { local p="$1"; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }
  serve() {  # MODE PORT … — start fake_instance.py detached; prints the PID as soon as the port answers
    local pid
    pid="$( (python3 "$FAKE" "$@" > /dev/null 2>&1 & echo $!) )"
    for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$2") 2>/dev/null && break; sleep 0.05; done
    echo "$pid"
  }
  APORT="$(freeport 19480)"; API_PID="$(serve api "$APORT" "$RKEY" 2)"
  PXPORT="$(freeport $((APORT + 1)))"; PX_PID="$(serve proxy "$PXPORT" "$PXLOG")"
  trap 'kill "$API_PID" "$PX_PID" 2>/dev/null; rm -rf "$TMP"' EXIT
  PX="http://127.0.0.1:$PXPORT"
  # proxied CODE — CODE in the starter (run_in_script) with the fake proxy in every variable curl reads
  proxied() {
    : > "$PXLOG"
    run_in_script "export http_proxy=$PX https_proxy=$PX all_proxy=$PX HTTP_PROXY=$PX HTTPS_PROXY=$PX ALL_PROXY=$PX; unset no_proxy NO_PROXY; $1"
  }
  untouched() { [ ! -s "$PXLOG" ]; }
  proxied "curl -s --max-time 3 http://127.0.0.1:$APORT/api/v1/system/info" >/dev/null
  check "control: a plain curl goes through the fake proxy" '! untouched'
  RC="$(proxied "endpoint_is_ready http://127.0.0.1:$APORT/api/v1/system/info; echo \$?")"
  check "readiness: answered directly, the proxy gets nothing" '[ "$RC" = 0 ] && untouched'
  CLOSED="$(freeport $((PXPORT + 1)))"
  proxied "endpoint_is_ready https://127.0.0.1:$CLOSED/" >/dev/null
  check "https probe (frontend): no CONNECT through the proxy" 'untouched'
  mkdir -p "$TMP/curlspy"; : > "$TMP/curlspy.log"
  printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> "%s"\nexec "%s" "$@"\n' "$TMP/curlspy.log" "$(command -v curl)" > "$TMP/curlspy/curl"; chmod +x "$TMP/curlspy/curl"
  GUARD="export PATH=\"$TMP/curlspy:\$PATH\" MYSURICATES_API_KEY=$RKEY; API_BASE=http://127.0.0.1:$APORT/api/v1"
  N="$(proxied "$GUARD; running_runs_count")"
  check "run guard: counts the runs, asked directly"      '[ "$N" = 2 ] && untouched'
  check "run guard: the key never shows in curl's arguments" '[ -s "$TMP/curlspy.log" ] && ! grep -qF "$RKEY" "$TMP/curlspy.log"'
  RC="$(proxied "$GUARD; FORCE=0; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?")"
  check "restart refused (exit 3) although a proxy is set" '[ "$RC" = 3 ] && untouched'
  kill "$API_PID" "$PX_PID" 2>/dev/null
else
  echo "  skip (no python3 or curl)"
fi

echo "12) rename: the old state directory moves along, the data directory keeps working"
MIG="$TMP/mig"; mkdir -p "$MIG/state/autotrigger-dev/runtime" "$MIG/home"
echo jar > "$MIG/state/autotrigger-dev/runtime/backend-1.jar"; echo 4711 > "$MIG/state/autotrigger-dev/backend.pid"
ln -s "$MIG/state/autotrigger-dev/runtime/backend-1.jar" "$MIG/state/autotrigger-dev/runtime/current.jar"
OUT="$(HOME="$MIG/home" XDG_STATE_HOME="$MIG/state" bash -c 'source "$1" >/dev/null 2>&1; set +e; echo "$STATE_DIR"; readlink "$CURRENT_JAR"; cat "$BACKEND_PID_FILE"' _ "$SCRIPT")"
check "old state dir moved to mysuricates-dev"           '[ "$(sed -n 1p <<<"$OUT")" = "$MIG/state/mysuricates-dev" ] && [ ! -e "$MIG/state/autotrigger-dev" ]'
check "current.jar points into the new dir"              '[ "$(sed -n 2p <<<"$OUT")" = "$MIG/state/mysuricates-dev/runtime/backend-1.jar" ] && [ -f "$MIG/state/mysuricates-dev/runtime/current.jar" ]'
check "the PID file survives the move"                   '[ "$(sed -n 3p <<<"$OUT")" = 4711 ]'
mkdir -p "$MIG/state/autotrigger-dev"
HOME="$MIG/home" XDG_STATE_HOME="$MIG/state" bash -c 'source "$1" >/dev/null 2>&1' _ "$SCRIPT"
check "an existing new dir is never overwritten"         '[ -d "$MIG/state/autotrigger-dev" ] && [ "$(cat "$MIG/state/mysuricates-dev/backend.pid")" = 4711 ]'
dh() { HOME="$MIG/home" bash -c '. "$1"; unset AUTOMATION_DATA_HOME; data_home' _ "$HERE/../lib/data-home.sh"; }
check "fresh home: data in ~/.mysuricates"               '[ "$(dh)" = "$MIG/home/.mysuricates" ]'
mkdir -p "$MIG/home/.mysuricates" "$MIG/home/.autotrigger"; echo old-key > "$MIG/home/.autotrigger/api-key"
check "older install: ~/.autotrigger as long as it exists" '[ "$(dh)" = "$MIG/home/.autotrigger" ]'
check "api_key reads the key from there"                 '[ "$(HOME="$MIG/home" bash -c ". \"\$1\"; unset MYSURICATES_API_KEY AUTOMATION_DATA_HOME; api_key" _ "$HERE/../lib/api-key.sh")" = old-key ]'
check "DATA_HOME_KEY_SH reads the same file"             '[ "$(bash -c ". \"\$1\"; sh -c \"\$DATA_HOME_KEY_SH\" sh \"\$2\"" _ "$HERE/../lib/data-home.sh" "$MIG/home")" = old-key ]'
rm -rf "$MIG/home/.autotrigger"; echo new-key > "$MIG/home/.mysuricates/api-key"
check "after the move: ~/.mysuricates"                   '[ "$(dh)" = "$MIG/home/.mysuricates" ] && [ "$(bash -c ". \"\$1\"; sh -c \"\$DATA_HOME_KEY_SH\" sh \"\$2\"" _ "$HERE/../lib/data-home.sh" "$MIG/home")" = new-key ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
