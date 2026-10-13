#!/usr/bin/env bash
# start-mysuricates-dev.sh — starts/stops the dev backend (:9090) and the Vite dev server (:3000).
#
#   scripts/start-mysuricates-dev.sh [start] [--rebuild] [--show-key]   # idempotent, default
#   scripts/start-mysuricates-dev.sh stop                                # backend only
#   scripts/start-mysuricates-dev.sh restart [--force]                   # backend: stop -> build JAR -> start;
#                                                                        # frontend only if it is not running
#   scripts/start-mysuricates-dev.sh build                               # only build the runtime JAR (setup.sh), starts nothing
#   scripts/start-mysuricates-dev.sh [start|stop|restart|build] --container  # backend in the Docker container
#
# Backend and Vite listen on 127.0.0.1 only; MYSURICATES_DEV_BIND=0.0.0.0 (or one IP address,
# e.g. a Tailscale IP) opens both — set it deliberately, the instance is then reachable from outside.
# With --container the backend's port follows MYSURICATES_DEV_CONTAINER_BIND instead.
#
# --container: Dockerfile.stable compiles nothing, it copies the JAR from target/. restart therefore
# always builds it beforehand (mvn clean package), start only with --rebuild or if none exists, build only that.
#
# The backend does NOT run from target/classes (mvn spring-boot:run), but from a JAR copy under
# ${STATE_DIR}/runtime/current.jar. Reason: a JVM with -cp target/classes reads classes lazily
# from disk for its whole life; every Maven run in the main checkout and every IDE build (VS Code Java / Eclipse compiler)
# rewrites the directory and later kills the running JVM with NoClassDefFoundError
# (incidents 2026-07-27 and 2026-09-13). From the copy no build can pull anything away anymore.
# `restart` first checks via the API whether workflow runs are running (CLAUDE.md rule), and aborts then.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# Mounts for the dev container: only what exists on this machine (shared with deploy/).
# shellcheck source=lib/host-mounts.sh
. "${SCRIPT_DIR}/lib/host-mounts.sh"
# Query backend and Vite: never via a proxy from the environment, the key only via stdin (bb4f60a5).
# shellcheck source=lib/local-curl.sh
. "${SCRIPT_DIR}/lib/local-curl.sh"
# api_key(): env -> key file, never the DB (fff23a6c).
# shellcheck source=lib/api-key.sh
. "${SCRIPT_DIR}/lib/api-key.sh"
# netaddr_local_url(): where this machine reaches a server bound to an address.
# shellcheck source=lib/netaddr.sh
. "${SCRIPT_DIR}/lib/netaddr.sh"

COMMAND="start"
# Operating mode of the backend: host (default, java -jar) or container (docker compose).
# The host path stays unchanged; the container path is additive (TODO a1236a64).
RUNTIME_MODE="${MYSURICATES_DEV_RUNTIME:-host}"
SHOW_KEY=0
FORCE=0
REBUILD=0
for arg in "$@"; do
  case "$arg" in
    start|stop|restart|build) COMMAND="$arg" ;;
    --show-key) SHOW_KEY=1 ;;   # show the API key in full (default: masked, last 4 characters only)
    --force) FORCE=1 ;;         # restart despite running workflow runs
    --rebuild) REBUILD=1 ;;     # start: rebuild the JAR even if one already exists
    --container) RUNTIME_MODE=container ;;  # backend in the container instead of as java -jar (TODO a1236a64)
    --host) RUNTIME_MODE=host ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) ;; # ignore other arguments
  esac
done

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates-dev"
# One-time move from the name before the rename: PID files and runtime JARs stay valid
# (a running backend keeps its JAR open, renaming the folder does not disturb it);
LEGACY_STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/autotrigger-dev"
# current.jar is an absolute link and is repointed to the new folder.
if [[ ! -e "${STATE_DIR}" && -d "${LEGACY_STATE_DIR}" ]]; then
  mv "${LEGACY_STATE_DIR}" "${STATE_DIR}"
  if [[ -L "${STATE_DIR}/runtime/current.jar" ]]; then
    ln -sfn "${STATE_DIR}/runtime/$(basename "$(readlink "${STATE_DIR}/runtime/current.jar")")" \
      "${STATE_DIR}/runtime/current.jar"
  fi
fi
RUNTIME_DIR="${STATE_DIR}/runtime"
KEEP_JARS="${MYSURICATES_DEV_KEEP_JARS:-2}"
mkdir -p "${STATE_DIR}"

BACKEND_DIR="${REPO_ROOT}/ai-automation-backend"
FRONTEND_DIR="${REPO_ROOT}/ai-automation-frontend"

# Prefer the Maven wrapper (downloads Maven itself), otherwise system Maven.
if [[ -x "${BACKEND_DIR}/mvnw" ]]; then
  MVN_CMD="${BACKEND_DIR}/mvnw"
else
  MVN_CMD="mvn"
fi

BACKEND_PID_FILE="${STATE_DIR}/backend.pid"
FRONTEND_PID_FILE="${STATE_DIR}/frontend.pid"
BACKEND_LOG="${STATE_DIR}/backend.log"
FRONTEND_LOG="${STATE_DIR}/frontend.log"
CURRENT_JAR="${RUNTIME_DIR}/current.jar"

# Command-line patterns by which a PID file is recognized as "really ours"
# — backend is `java -jar .../current.jar` (older PID files still point to
# a Maven launcher), frontend `npm run dev` (later node/vite).
BACKEND_PROCESS_PATTERN='(mvn|maven|spring-boot|java)'
FRONTEND_PROCESS_PATTERN='(npm|node|vite)'

# Address the dev start from source listens on: the backend in host mode (--server.address) and Vite
# (vite.config.ts reads the same variable). Loopback by default, like every Docker way (cd7c53f9);
# MYSURICATES_DEV_BIND opens both, e.g. 0.0.0.0 or one Tailscale IP. Not in application.yml: the
# images must listen on 0.0.0.0 inside their container. Checked by check_dev_bind before any start.
DEV_BIND="${MYSURICATES_DEV_BIND:-127.0.0.1}"

BACKEND_PORT=9090
# In the container the backend answers on the port that Compose publishes: already-running check,
# readiness and run check before restart follow it — a probe next to the host backend (3bfa4e10).
if [[ "${RUNTIME_MODE}" == "container" ]]; then BACKEND_PORT="${MYSURICATES_DEV_CONTAINER_PORT:-9090}"; fi
# Bound to a single non-loopback address the backend does not answer on localhost — probe that address.
if [[ "${RUNTIME_MODE}" == "container" ]]; then
  BACKEND_LOCAL_URL="http://localhost:${BACKEND_PORT}"
else
  BACKEND_LOCAL_URL="$(netaddr_local_url "${DEV_BIND}" "${BACKEND_PORT}")"
fi
API_BASE="${MYSURICATES_API_BASE:-${BACKEND_LOCAL_URL}/api/v1}"
BACKEND_READY_URL="${API_BASE}/system/info"
# Vite serves HTTPS only when certs/dev.crt exists, otherwise plain HTTP.
# Probe both schemes so a fresh clone (no certs -> HTTP) is detected correctly.
FRONTEND_HTTP_URL="$(netaddr_local_url "${DEV_BIND}" 3000)"
FRONTEND_HTTPS_URL="https://${FRONTEND_HTTP_URL#http://}"

# JVM flags as before in the spring-boot-maven-plugin (pom.xml), plus tiered stop for fast start.
BACKEND_JVM_OPTS=(
  -XX:TieredStopAtLevel=1
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=./data/logs/
  -XX:ErrorFile=./data/logs/hs_err_pid%p.log
)

# Refuses a MYSURICATES_DEV_BIND that is no IP address (same rule as MYSURICATES_DEV_CONTAINER_BIND):
# the value ends up in the java and Vite command lines.
check_dev_bind() {
  case "${DEV_BIND}" in
    *[!0-9a-fA-F.:]*) echo "Ungueltiges MYSURICATES_DEV_BIND '${MYSURICATES_DEV_BIND}' (eine IP-Adresse, z. B. 127.0.0.1 oder 0.0.0.0)." >&2
                      return 2 ;;
  esac
}

require_command() {
  local command_name="$1"
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Fehlender Befehl: ${command_name}" >&2
    exit 1
  fi
}

read_pid() {
  local pid_file="$1"
  if [[ -f "${pid_file}" ]]; then
    tr -d '[:space:]' < "${pid_file}"
  fi
}

# Read a process's command line: /proc on Linux, otherwise ps.
process_cmdline() {
  local pid="$1"

  if [[ -r "/proc/${pid}/cmdline" ]]; then
    tr '\0' ' ' < "/proc/${pid}/cmdline"
    return 0
  fi

  ps -p "${pid}" -o args= 2>/dev/null || true
}

# `kill -0` alone is not enough: after a crash the PID file stays and
# the OS reassigns the PID at some point (2026-08-04 to a snapd helper). The
# starter then thought the backend was running and never started it — frontend ran,
# all /api proxy requests ran into ECONNREFUSED. Therefore additionally check whether
# the command line fits the expected service at all.
pid_belongs_to_service() {
  local pid_file="$1"
  local pattern="$2"
  local pid cmdline

  pid="$(read_pid "${pid_file}")"
  if [[ -z "${pid}" || ! "${pid}" =~ ^[0-9]+$ ]]; then
    return 1
  fi

  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  fi

  cmdline="$(process_cmdline "${pid}")"
  if [[ -z "${cmdline}" ]]; then
    return 1   # command line unreadable -> PID not trustworthy
  fi

  [[ "${cmdline}" =~ ${pattern} ]]
}

# Discard a PID file that points to a dead or foreign process. Thereby
# the readiness endpoint alone decides afterwards whether to restart.
discard_stale_pid_file() {
  local pid_file="$1"
  local pattern="$2"
  local name="$3"
  local pid

  [[ -f "${pid_file}" ]] || return 0

  if pid_belongs_to_service "${pid_file}" "${pattern}"; then
    return 0
  fi

  pid="$(read_pid "${pid_file}")"
  echo "Verwaiste PID-Datei fuer ${name} entfernt (PID ${pid:-leer}): ${pid_file}"
  rm -f "${pid_file}"
}

endpoint_is_ready() {
  local url="$1"
  local_curl -fsSk --max-time 2 "${url}" >/dev/null 2>&1
}

wait_for_endpoint() {
  local name="$1"
  local url="$2"
  local attempts="$3"

  for ((i = 1; i <= attempts; i += 1)); do
    if endpoint_is_ready "${url}"; then
      echo "${name} ist erreichbar: ${url}"
      return 0
    fi
    sleep 1
  done

  echo "${name} wurde nicht rechtzeitig erreichbar: ${url}" >&2
  return 1
}

frontend_is_ready() {
  endpoint_is_ready "${FRONTEND_HTTPS_URL}" || endpoint_is_ready "${FRONTEND_HTTP_URL}"
}

resolved_frontend_url() {
  if endpoint_is_ready "${FRONTEND_HTTPS_URL}"; then
    echo "${FRONTEND_HTTPS_URL}"
  else
    echo "${FRONTEND_HTTP_URL}"
  fi
}

wait_for_frontend() {
  local attempts="$1"

  for ((i = 1; i <= attempts; i += 1)); do
    if frontend_is_ready; then
      echo "Frontend ist erreichbar: $(resolved_frontend_url)"
      return 0
    fi
    sleep 1
  done

  echo "Frontend wurde nicht rechtzeitig erreichbar: ${FRONTEND_HTTP_URL} / ${FRONTEND_HTTPS_URL}" >&2
  return 1
}

# ---------------------------------------------------------------------------
# Run guard: detect running workflow runs before a restart.
# ---------------------------------------------------------------------------

# Number of running runs; empty if not determinable (backend down, no key).
running_runs_count() {
  local key body
  key="$(api_key)"
  [[ -n "${key}" ]] || return 0
  body="$(local_curl_key "${key}" -fsS --max-time 5 \
    "${API_BASE}/executions?status=RUNNING&size=1" 2>/dev/null)" || return 0
  python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if isinstance(d, dict):
    n = d.get("totalElements")
    if n is None:
        c = d.get("content", [])
        n = len(c) if isinstance(c, list) else 0
else:
    n = len(d)
print(int(n))' <<< "${body}"
}

# Exit 3 if runs are running and --force is missing. Unknown (backend down) => continue.
refuse_restart_if_runs_active() {
  local count
  count="$(running_runs_count)"
  if [[ -z "${count}" ]]; then
    echo "Laufende Runs nicht ermittelbar (Backend down oder kein API-Key) — fahre fort."
    return 0
  fi
  if [[ "${count}" -gt 0 && "${FORCE}" -ne 1 ]]; then
    echo "Es laufen noch ${count} Workflow-Run(s). Neustart abgebrochen — warten oder --force." >&2
    return 3
  fi
  if [[ "${count}" -gt 0 ]]; then
    echo "WARNUNG: ${count} laufende Run(s) werden durch --force abgebrochen."
  else
    echo "Keine laufenden Runs."
  fi
}

# ---------------------------------------------------------------------------
# JAR runtime: build, copy into the state folder, rotate.
# ---------------------------------------------------------------------------

# "<YYYYmmdd-HHMMSS>-<shortsha>[-dirty]" — dirty = uncommitted changes to tracked files.
runtime_jar_label() {
  local sha="nogit" dirty=""
  if git -C "${REPO_ROOT}" rev-parse --short HEAD >/dev/null 2>&1; then
    sha="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
    if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
      dirty="-dirty"
    fi
  fi
  echo "$(date +%Y%m%d-%H%M%S)-${sha}${dirty}"
}

# Copies a built JAR to RUNTIME_DIR, sets current.jar and keeps KEEP_JARS pieces.
install_runtime_jar() {
  local jar="$1" target
  [[ -f "${jar}" ]] || { echo "JAR nicht gefunden: ${jar}" >&2; return 1; }
  mkdir -p "${RUNTIME_DIR}"
  target="${RUNTIME_DIR}/backend-$(runtime_jar_label).jar"
  cp -f "${jar}" "${target}"
  ln -sfn "${target}" "${CURRENT_JAR}"
  # Rotation: keep the newest KEEP_JARS (current.jar points to the newest).
  ls -1t "${RUNTIME_DIR}"/backend-*.jar 2>/dev/null | tail -n +"$((KEEP_JARS + 1))" | xargs -r rm -f --
  echo "${target}"
}

# Fat JAR in target/ — empty if none exists.
target_jar() {
  find "${BACKEND_DIR}/target" -maxdepth 1 -name 'ai-automation-backend-*.jar' ! -name '*.original' 2>/dev/null | head -1 || true
}

# Build the fat JAR in the backend directory (clean: also heals a target/ corrupted by IDE builds).
# Sets BUILT_JAR. The host path then copies it to runtime/, the container path into the image.
compile_backend_jar() {
  echo "Baue Backend-JAR (mvn clean package -DskipTests) ..."
  (cd "${BACKEND_DIR}" && "${MVN_CMD}" -q clean package -DskipTests) || return 1
  BUILT_JAR="$(target_jar)"
  [[ -n "${BUILT_JAR}" ]] || { echo "Kein Fat-JAR unter ${BACKEND_DIR}/target gefunden." >&2; return 1; }
}

build_backend_jar() {
  compile_backend_jar || return 1
  echo "Runtime-JAR: $(install_runtime_jar "${BUILT_JAR}")"
}

# ---------------------------------------------------------------------------
# Backend / Frontend
# ---------------------------------------------------------------------------

# ── Container operation (--container) ──────────────────────────────────────────────
# Additive to the host path: same commands, only `docker compose` starts instead of
# `java -jar`. The Vite dev server is unaffected by it — it proxies to
# :9090, regardless of who answers there (TODO a1236a64).

COMPOSE_FILE="${BACKEND_DIR}/docker-compose.dev-container.yml"
COMPOSE_OVERRIDE="${STATE_DIR}/dev-container.override.yml"
COMPOSE_PROJECT="${MYSURICATES_DEV_COMPOSE_PROJECT:-mysuricates-dev}"

compose_cmd() {
  # The image creates a user with the host IDs (Dockerfile.stable) so that the
  # mounted logins stay readable. $UID/$GID are not exported in Bash,
  # therefore explicitly from id(1) here — without them the build fails in useradd.
  MYSURICATES_DEV_HOST_USER="${MYSURICATES_DEV_HOST_USER:-$(id -un)}" \
  MYSURICATES_DEV_HOST_UID="${MYSURICATES_DEV_HOST_UID:-$(id -u)}" \
  MYSURICATES_DEV_HOST_GID="${MYSURICATES_DEV_HOST_GID:-$(id -g)}" \
  docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" -f "${COMPOSE_OVERRIDE}" "$@"
}

# Override with what comes from the host into the container (scripts/lib/host-mounts.sh, 262948a4):
#   MYSURICATES_DEV_AI_FROM_HOST  programs | login (default) | all
#   MYSURICATES_DEV_AI_PROVIDERS  selection from claude,codex,pi (default: all)
#   MYSURICATES_DEV_DOCKER_SOCKET 0 (default) | 1 = full control over Docker on the machine
#                                 (project container, terminal, container_image) — set deliberately
#   MYSURICATES_DEV_CONTAINER_BIND address for the port, default 127.0.0.1; 0.0.0.0 = also local network
#                                 (docker-compose.dev-container.yml reads it itself, only checked here)
# The project root includes docker-compose.dev-container.yml itself.
write_compose_override() {
  local HOST_MOUNTS_NODE_MODULES="${MYSURICATES_DEV_NODE_MODULES:-}"   # empty: npm root -g
  local missing
  case "${MYSURICATES_DEV_CONTAINER_BIND:-127.0.0.1}" in
    *[!0-9a-fA-F.:]*) echo "Ungueltiges MYSURICATES_DEV_CONTAINER_BIND '${MYSURICATES_DEV_CONTAINER_BIND}' (eine IP-Adresse, z. B. 127.0.0.1 oder 0.0.0.0)." >&2
                      return 2 ;;
  esac
  HOST_MOUNTS_PUBLISHED_BIND="${MYSURICATES_DEV_CONTAINER_BIND:-127.0.0.1}" HOST_MOUNTS_PUBLISHED_PORT="${BACKEND_PORT}" \
  HOST_MOUNTS_BIND_SWITCH=MYSURICATES_DEV_CONTAINER_BIND \
  host_mounts_yaml backend "" "${MYSURICATES_DEV_DOCKER_SOCKET:-0}" "${MYSURICATES_DEV_AI_FROM_HOST:-login}" \
    "${MYSURICATES_DEV_AI_PROVIDERS:-}" "scripts/start-mysuricates-dev.sh --container" MYSURICATES_DEV_ "" \
    "dev-${COMPOSE_PROJECT}" \
    > "${COMPOSE_OVERRIDE}" || { echo "Ungueltige MYSURICATES_DEV_AI_*-Werte (siehe oben)." >&2; return 2; }
  echo "KI-Anbieter im Container: ${HOST_MOUNTS_READY:-keiner} (${MYSURICATES_DEV_AI_FROM_HOST:-login}, ${COMPOSE_OVERRIDE})"
  missing="$(host_mounts_missing_text)"
  if [[ -n "${missing}" ]]; then
    printf 'Nicht nutzbar im Container:\n%s\n' "${missing}" >&2
  fi
}

# The JAR that Dockerfile.stable copies into the image (COPY target/…jar). Without this step, after
# a code change the JAR last built on the host silently ran, without a JAR the build failed (3bfa4e10).
#   $1 = always (restart, build) | missing (start: only with --rebuild or without JAR) | built (just built)
ensure_container_jar() {
  local jar
  [[ "${1:-missing}" == "built" ]] && return 0
  jar="$(target_jar)"
  if [[ "${1:-missing}" == "always" || "${REBUILD}" -eq 1 || -z "${jar}" ]]; then
    if [[ "${MVN_CMD}" == "mvn" ]]; then require_command mvn; fi
    compile_backend_jar || return 1
    echo "JAR fuer das Image: ${BUILT_JAR}"
  else
    echo "Verwende vorhandenes JAR fuer das Image: ${jar} (neu bauen: restart oder --rebuild)"
  fi
}

start_backend_container() {  # $1 = always|missing|built, see ensure_container_jar
  require_command docker
  if endpoint_is_ready "${BACKEND_READY_URL}"; then
    echo "Auf :${BACKEND_PORT} antwortet bereits ein Backend — erst stoppen." >&2
    return 1
  fi
  ensure_container_jar "${1:-missing}" || return 1
  write_compose_override || return 1
  echo "Starte Backend im Container (docker compose up -d) ..."
  compose_cmd up -d --build
}

stop_backend_container() {
  require_command docker
  [[ -f "${COMPOSE_OVERRIDE}" ]] || write_compose_override || return 1
  echo "Stoppe Backend-Container (docker compose down) ..."
  compose_cmd down
}

start_backend() {
  check_dev_bind || return 2
  discard_stale_pid_file "${BACKEND_PID_FILE}" "${BACKEND_PROCESS_PATTERN}" "Backend"

  if pid_belongs_to_service "${BACKEND_PID_FILE}" "${BACKEND_PROCESS_PATTERN}" \
    || endpoint_is_ready "${BACKEND_READY_URL}"; then
    echo "Backend laeuft bereits auf http://localhost:${BACKEND_PORT}"
    return 0
  fi

  if [[ "${REBUILD}" -eq 1 || ! -f "${CURRENT_JAR}" ]]; then
    build_backend_jar
  elif [[ "${COMMAND}" != "restart" ]]; then
    echo "Verwende vorhandenes Runtime-JAR: $(readlink -f "${CURRENT_JAR}") (neu bauen: restart oder --rebuild)"
  fi

  rm -f "${BACKEND_PID_FILE}"
  echo "Starte Backend (java -jar) ..."
  (
    cd "${BACKEND_DIR}"   # cwd stays the backend directory: ./data/automation.db, user.dir users
    # The switch is only shown on the settings page (Erreichbarkeit, 1af11dcc), never applied by the backend
    MYSURICATES_BIND_SWITCH=MYSURICATES_DEV_BIND MYSURICATES_SWITCH_SOURCE="scripts/start-mysuricates-dev.sh" \
      nohup java "${BACKEND_JVM_OPTS[@]}" ${MYSURICATES_DEV_JVM_OPTS:-} -jar "${CURRENT_JAR}" \
      --server.address="${DEV_BIND}" > "${BACKEND_LOG}" 2>&1 &
    echo "$!" > "${BACKEND_PID_FILE}"
  )
}

# Stops the backend from the PID file (incl. child JVM of an old Maven launcher).
stop_backend() {
  local pid kids
  discard_stale_pid_file "${BACKEND_PID_FILE}" "${BACKEND_PROCESS_PATTERN}" "Backend"

  if ! pid_belongs_to_service "${BACKEND_PID_FILE}" "${BACKEND_PROCESS_PATTERN}"; then
    if endpoint_is_ready "${BACKEND_READY_URL}"; then
      echo "Backend antwortet auf :${BACKEND_PORT}, aber keine gueltige PID-Datei (${BACKEND_PID_FILE})." >&2
      echo "Fremd gestartet? PID(s): $(lsof -ti "tcp:${BACKEND_PORT}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')— bitte manuell beenden." >&2
      return 1
    fi
    echo "Backend laeuft nicht."
    return 0
  fi

  pid="$(read_pid "${BACKEND_PID_FILE}")"
  kids="$(pgrep -P "${pid}" 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -f "${BACKEND_LOG}" ]]; then
    mv -f "${BACKEND_LOG}" "${BACKEND_LOG}.before-restart-$(date +%Y%m%d-%H%M)"
  fi
  echo "Stoppe Backend (PID ${pid}${kids:+, Kinder ${kids}}) ..."
  # shellcheck disable=SC2086
  kill -TERM "${pid}" ${kids} 2>/dev/null || true
  for ((i = 1; i <= 30; i += 1)); do
    kill -0 "${pid}" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "${pid}" 2>/dev/null; then
    echo "Backend reagiert nicht auf SIGTERM — SIGKILL."
    # shellcheck disable=SC2086
    kill -KILL "${pid}" ${kids} 2>/dev/null || true
    sleep 2
  fi
  rm -f "${BACKEND_PID_FILE}"
  for ((i = 1; i <= 10; i += 1)); do
    endpoint_is_ready "${BACKEND_READY_URL}" || break
    sleep 1
  done
  echo "Backend gestoppt."
}

start_frontend() {
  check_dev_bind || return 2
  discard_stale_pid_file "${FRONTEND_PID_FILE}" "${FRONTEND_PROCESS_PATTERN}" "Frontend"

  if pid_belongs_to_service "${FRONTEND_PID_FILE}" "${FRONTEND_PROCESS_PATTERN}" \
    || frontend_is_ready; then
    echo "Frontend laeuft bereits auf $(resolved_frontend_url)"
    return 0
  fi

  rm -f "${FRONTEND_PID_FILE}"
  echo "Starte Frontend ..."
  (
    cd "${FRONTEND_DIR}"
    # Own session instead of just nohup: Node resets all signal dispositions at start and thereby lifts
    # the SIG_IGN of nohup — when the terminal was closed, SIGHUP ended the Vite server,
    # while the backend (Java respects SIG_IGN) kept running (454f8ac5, found by
    # scripts/installer-probe.sh). python3 instead of setsid, because setsid exists only on Linux;
    # execvp keeps the PID, so the PID file still points to npm.
    MYSURICATES_DEV_BIND="${DEV_BIND}" \
      nohup python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' npm run dev \
      > "${FRONTEND_LOG}" 2>&1 &
    echo "$!" > "${FRONTEND_PID_FILE}"
  )
}

# After a restart: start Vite afterwards if it is missing (e.g. because the terminal crashed and
# took it along). start_frontend leaves a running server alone. If the start fails,
# it stays with a warning — restart is about the backend, and that is already running.
ensure_frontend_after_restart() {
  if ! command -v npm >/dev/null 2>&1; then
    echo "Hinweis: npm nicht gefunden, Frontend nicht gestartet." >&2
    return 0
  fi
  start_frontend
  wait_for_frontend 90 || echo "Warnung: Frontend laeuft nicht, siehe ${FRONTEND_LOG}" >&2
  return 0
}

print_logs_hint() {
  echo
  echo "Logs:"
  echo "  Backend:  ${BACKEND_LOG}"
  echo "  Frontend: ${FRONTEND_LOG}"
}

# Shows the API key the new user has to enter in the browser login.
# Three cases: security off / own key via env / auto-generated key (file).
# Masked by default (last 4 characters only) — with --show-key in full. Safe for CI/screen sharing.
print_api_key() {
  local key_file
  key_file="$(data_home)/api-key"
  local key="" src=""
  echo
  if [[ "${AUTOMATION_SECURITY_ENABLED:-true}" == "false" ]]; then
    echo "API-Key: nicht nötig (AUTOMATION_SECURITY_ENABLED=false)"
    return 0
  elif [[ -n "${AUTOMATION_API_KEY:-}" ]]; then
    key="${AUTOMATION_API_KEY}"; src="AUTOMATION_API_KEY (env)"
    echo "API-Key (eigener, via AUTOMATION_API_KEY):"
  elif [[ -f "${key_file}" ]]; then
    key="$(cat "${key_file}")"; src="${key_file}"
    echo "API-Key (beim ersten Öffnen im Browser-Login einfügen):"
  else
    echo "API-Key: noch nicht angelegt — das Backend legt ihn beim ersten Start in ${key_file} ab"
    return 0
  fi
  if [[ "${SHOW_KEY}" -eq 1 ]]; then
    echo "  ${key}"
  else
    echo "  …${key: -4}   (maskiert — voll anzeigen mit: --show-key)"
  fi
  echo "  Quelle: ${src}"
}

report_and_exit() {
  local backend_ok="$1" frontend_ok="$2"
  echo
  echo "URLs:"
  echo "  Frontend: $(resolved_frontend_url)"
  echo "  Backend:  ${BACKEND_LOCAL_URL}/api/v1"
  if [[ "${backend_ok}" -eq 1 ]]; then
    print_api_key
  fi
  print_logs_hint

  if [[ "${backend_ok}" -eq 1 && "${frontend_ok}" -eq 1 ]]; then
    echo
    echo "MySuricates Dev ist gestartet."
    exit 0
  fi

  echo
  echo "Mindestens ein Dienst ist nicht sauber gestartet." >&2
  print_logs_hint >&2
  exit 1
}

main() {
  require_command curl
  require_command python3
  echo "MySuricates Dev Starter (${COMMAND})"
  echo "Repo: ${REPO_ROOT}"

  echo "Betriebsart: ${RUNTIME_MODE}"

  if [[ "${COMMAND}" == "start" || "${COMMAND}" == "restart" ]]; then
    # Before anything is stopped or built: a bad value must not leave the backend down.
    check_dev_bind || exit 2
    if ! netaddr_is_local "${DEV_BIND}"; then
      echo "Lauscht auf ${DEV_BIND} (MYSURICATES_DEV_BIND) — von außerhalb dieses Rechners erreichbar."
    fi
  fi

  case "${COMMAND}" in
    build)
      # Builds the JAR that a later `start` uses without a further build — so
      # scripts/setup.sh compiles the backend only once instead of test-compile + clean package (0ab376d3).
      # In container operation the same JAR in target/, from which start/restart build the image (3bfa4e10).
      if [[ "${RUNTIME_MODE}" == "container" ]]; then
        ensure_container_jar always || exit 1
      else
        if [[ "${MVN_CMD}" == "mvn" ]]; then require_command mvn; fi
        build_backend_jar
      fi
      ;;
    stop)
      if [[ "${RUNTIME_MODE}" == "container" ]]; then stop_backend_container; else stop_backend; fi
      ;;
    restart)
      refuse_restart_if_runs_active
      if [[ "${RUNTIME_MODE}" == "container" ]]; then
        # First build, then stop: if Maven fails, the old container keeps running
        ensure_container_jar always || exit 1
        stop_backend_container
        start_backend_container built || exit 1
      else
        if [[ "${MVN_CMD}" == "mvn" ]]; then require_command mvn; fi
        stop_backend
        build_backend_jar
        start_backend
      fi
      local ok=0
      if wait_for_endpoint "Backend" "${BACKEND_READY_URL}" 120; then ok=1; fi
      if [[ "${ok}" -eq 1 ]]; then ensure_frontend_after_restart; fi
      print_logs_hint
      [[ "${ok}" -eq 1 ]] || exit 1
      if [[ "${RUNTIME_MODE}" == "container" ]]; then
        echo "Backend neu gestartet im Container (${COMPOSE_PROJECT})."
      else
        echo "Backend neu gestartet aus $(readlink -f "${CURRENT_JAR}")."
      fi
      ;;
    start)
      require_command npm
      if [[ "${RUNTIME_MODE}" == "container" ]]; then
        start_backend_container missing || exit 1
      else
        if [[ "${MVN_CMD}" == "mvn" ]]; then require_command mvn; fi
        start_backend
      fi
      start_frontend
      local backend_ok=0 frontend_ok=0
      if wait_for_endpoint "Backend" "${BACKEND_READY_URL}" 120; then backend_ok=1; fi
      if wait_for_frontend 90; then frontend_ok=1; fi
      report_and_exit "${backend_ok}" "${frontend_ok}"
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
