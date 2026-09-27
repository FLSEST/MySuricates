#!/usr/bin/env bash
# start-autotrigger-dev.sh — startet/stoppt das Dev-Backend (:9090) und den Vite-Dev-Server (:3000).
#
#   scripts/start-autotrigger-dev.sh [start] [--rebuild] [--show-key]   # idempotent, Default
#   scripts/start-autotrigger-dev.sh stop                                # nur Backend
#   scripts/start-autotrigger-dev.sh restart [--force]                   # Backend: stop -> JAR bauen -> start;
#                                                                        # Frontend nur, falls es nicht laeuft
#   scripts/start-autotrigger-dev.sh build                               # nur Runtime-JAR bauen (setup.sh), startet nichts
#   scripts/start-autotrigger-dev.sh [start|stop|restart|build] --container  # Backend im Docker-Container
#
# --container: Dockerfile.stable kompiliert nichts, es kopiert das JAR aus target/. restart baut es
# deshalb vorher immer (mvn clean package), start nur mit --rebuild oder wenn keins liegt, build nur das.
#
# Das Backend laeuft NICHT aus target/classes (mvn spring-boot:run), sondern aus einer JAR-Kopie unter
# ${STATE_DIR}/runtime/current.jar. Grund: Eine JVM mit -cp target/classes liest Klassen lebenslang lazy
# von Platte; jeder Maven-Lauf im Haupt-Checkout und jeder IDE-Build (VS Code Java / Eclipse-Compiler)
# schreibt das Verzeichnis um und toetet die laufende JVM spaeter mit NoClassDefFoundError
# (Vorfaelle 2026-07-27 und 2026-09-13). Aus der Kopie kann kein Build mehr etwas wegziehen.
# `restart` prueft vorher ueber die API, ob Workflow-Runs laufen (CLAUDE.md-Regel), und bricht dann ab.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# Mounts fuer den Dev-Container: nur, was es auf diesem Rechner gibt (gemeinsam mit deploy/).
# shellcheck source=lib/host-mounts.sh
. "${SCRIPT_DIR}/lib/host-mounts.sh"
# Backend und Vite fragen: nie ueber einen Proxy aus der Umgebung, der Key nur ueber stdin (bb4f60a5).
# shellcheck source=lib/local-curl.sh
. "${SCRIPT_DIR}/lib/local-curl.sh"
# api_key(): Env -> Key-Datei, nie die DB (ausgelagert fuer setup-audit-workflows.sh, fff23a6c).
# shellcheck source=lib/api-key.sh
. "${SCRIPT_DIR}/lib/api-key.sh"

COMMAND="start"
# Betriebsart des Backends: host (Default, java -jar) oder container (docker compose).
# Der Host-Weg bleibt unveraendert; der Container-Weg ist additiv (TODO a1236a64).
RUNTIME_MODE="${AUTOTRIGGER_DEV_RUNTIME:-host}"
SHOW_KEY=0
FORCE=0
REBUILD=0
for arg in "$@"; do
  case "$arg" in
    start|stop|restart|build) COMMAND="$arg" ;;
    --show-key) SHOW_KEY=1 ;;   # API-Key voll anzeigen (Default: maskiert, nur letzte 4 Zeichen)
    --force) FORCE=1 ;;         # restart trotz laufender Workflow-Runs
    --rebuild) REBUILD=1 ;;     # start: JAR neu bauen, auch wenn schon eins liegt
    --container) RUNTIME_MODE=container ;;  # Backend im Container statt als java -jar (TODO a1236a64)
    --host) RUNTIME_MODE=host ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) ;; # andere Argumente ignorieren
  esac
done

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/autotrigger-dev"
RUNTIME_DIR="${STATE_DIR}/runtime"
KEEP_JARS="${AUTOTRIGGER_DEV_KEEP_JARS:-2}"
mkdir -p "${STATE_DIR}"

BACKEND_DIR="${REPO_ROOT}/ai-automation-backend"
FRONTEND_DIR="${REPO_ROOT}/ai-automation-frontend"

# Maven-Wrapper bevorzugen (lädt Maven selbst), sonst System-Maven.
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

# Kommandozeilen-Muster, an denen eine PID-Datei als "gehoert wirklich uns" erkannt
# wird — Backend ist `java -jar .../current.jar` (aeltere PID-Dateien zeigen noch auf
# einen Maven-Launcher), Frontend `npm run dev` (spaeter node/vite).
BACKEND_PROCESS_PATTERN='(mvn|maven|spring-boot|java)'
FRONTEND_PROCESS_PATTERN='(npm|node|vite)'

BACKEND_PORT=9090
# Im Container antwortet das Backend auf dem Port, den Compose veroeffentlicht: laeuft-schon-Pruefung,
# Bereitschaft und Run-Check vor restart folgen ihm — eine Probe neben dem Host-Backend (3bfa4e10).
if [[ "${RUNTIME_MODE}" == "container" ]]; then BACKEND_PORT="${AUTOTRIGGER_DEV_CONTAINER_PORT:-9090}"; fi
API_BASE="${MYSURICATES_API_BASE:-http://localhost:${BACKEND_PORT}/api/v1}"
BACKEND_READY_URL="${API_BASE}/system/info"
# Vite serves HTTPS only when certs/dev.crt exists, otherwise plain HTTP.
# Probe both schemes so a fresh clone (no certs -> HTTP) is detected correctly.
FRONTEND_HTTPS_URL="https://localhost:3000"
FRONTEND_HTTP_URL="http://localhost:3000"

# JVM-Flags wie frueher im spring-boot-maven-plugin (pom.xml), plus Tiered-Stop fuer schnellen Start.
BACKEND_JVM_OPTS=(
  -XX:TieredStopAtLevel=1
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=./data/logs/
  -XX:ErrorFile=./data/logs/hs_err_pid%p.log
)

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

# Kommandozeile eines Prozesses lesen: /proc auf Linux, sonst ps.
process_cmdline() {
  local pid="$1"

  if [[ -r "/proc/${pid}/cmdline" ]]; then
    tr '\0' ' ' < "/proc/${pid}/cmdline"
    return 0
  fi

  ps -p "${pid}" -o args= 2>/dev/null || true
}

# `kill -0` allein reicht nicht: nach einem Crash bleibt die PID-Datei liegen und
# das OS vergibt die PID irgendwann neu (2026-08-04 an einen snapd-Helper). Der
# Starter hielt das Backend dann fuer laufend und startete es nie — Frontend lief,
# alle /api-Proxy-Requests liefen in ECONNREFUSED. Deshalb zusaetzlich pruefen, ob
# die Kommandozeile ueberhaupt zum erwarteten Dienst passt.
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
    return 1   # Kommandozeile nicht lesbar -> PID nicht vertrauenswuerdig
  fi

  [[ "${cmdline}" =~ ${pattern} ]]
}

# PID-Datei verwerfen, die auf einen toten oder fremden Prozess zeigt. Damit
# entscheidet danach allein der Readiness-Endpoint, ob neu gestartet wird.
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
# Run-Waechter: laufende Workflow-Runs vor einem Neustart erkennen.
# ---------------------------------------------------------------------------

# Anzahl laufender Runs; leer, wenn nicht ermittelbar (Backend down, kein Key).
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

# Exit 3, wenn Runs laufen und --force fehlt. Unbekannt (Backend down) => weiter.
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
# JAR-Runtime: bauen, in den State-Ordner kopieren, rotieren.
# ---------------------------------------------------------------------------

# "<YYYYmmdd-HHMMSS>-<shortsha>[-dirty]" — dirty = uncommittete Aenderungen an getrackten Dateien.
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

# Kopiert ein gebautes JAR nach RUNTIME_DIR, setzt current.jar und behaelt KEEP_JARS Stueck.
install_runtime_jar() {
  local jar="$1" target
  [[ -f "${jar}" ]] || { echo "JAR nicht gefunden: ${jar}" >&2; return 1; }
  mkdir -p "${RUNTIME_DIR}"
  target="${RUNTIME_DIR}/backend-$(runtime_jar_label).jar"
  cp -f "${jar}" "${target}"
  ln -sfn "${target}" "${CURRENT_JAR}"
  # Rotation: neueste KEEP_JARS behalten (current.jar zeigt auf die neueste).
  ls -1t "${RUNTIME_DIR}"/backend-*.jar 2>/dev/null | tail -n +"$((KEEP_JARS + 1))" | xargs -r rm -f --
  echo "${target}"
}

# Fat-JAR in target/ — leer, wenn keins liegt.
target_jar() {
  find "${BACKEND_DIR}/target" -maxdepth 1 -name 'ai-automation-backend-*.jar' ! -name '*.original' 2>/dev/null | head -1 || true
}

# Fat-JAR im Backend-Verzeichnis bauen (clean: heilt auch ein von IDE-Builds korrumpiertes target/).
# Setzt BUILT_JAR. Der Host-Weg kopiert es danach nach runtime/, der Container-Weg ins Image.
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

# ── Container-Betrieb (--container) ──────────────────────────────────────────────
# Additiv zum Host-Weg: dieselben Kommandos, nur startet statt `java -jar` ein
# `docker compose`. Der Vite-Dev-Server bleibt davon unberuehrt — er proxied nach
# :9090, unabhaengig davon, wer dort antwortet (TODO a1236a64).

COMPOSE_FILE="${BACKEND_DIR}/docker-compose.dev-container.yml"
COMPOSE_OVERRIDE="${STATE_DIR}/dev-container.override.yml"
COMPOSE_PROJECT="${AUTOTRIGGER_DEV_COMPOSE_PROJECT:-autotrigger-dev}"

compose_cmd() {
  # Das Image legt einen Benutzer mit den Host-IDs an (Dockerfile.stable), damit die
  # gemounteten Anmeldungen lesbar bleiben. $UID/$GID sind in Bash nicht exportiert,
  # deshalb hier ausdruecklich aus id(1) — ohne sie scheitert der Build im useradd.
  AUTOTRIGGER_DEV_HOST_USER="${AUTOTRIGGER_DEV_HOST_USER:-$(id -un)}" \
  AUTOTRIGGER_DEV_HOST_UID="${AUTOTRIGGER_DEV_HOST_UID:-$(id -u)}" \
  AUTOTRIGGER_DEV_HOST_GID="${AUTOTRIGGER_DEV_HOST_GID:-$(id -g)}" \
  docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" -f "${COMPOSE_OVERRIDE}" "$@"
}

# Override mit dem, was vom Host in den Container kommt (scripts/lib/host-mounts.sh, 262948a4):
#   AUTOTRIGGER_DEV_AI_FROM_HOST  programs | login (Default) | all
#   AUTOTRIGGER_DEV_AI_PROVIDERS  Auswahl aus claude,codex,pi (Default: alle)
#   AUTOTRIGGER_DEV_DOCKER_SOCKET 0 (Default) | 1 = volle Kontrolle ueber Docker auf dem Rechner
#                                 (Projekt-Container, Terminal, container_image) — bewusst setzen
#   AUTOTRIGGER_DEV_CONTAINER_BIND Adresse fuer den Port, Default 127.0.0.1; 0.0.0.0 = auch lokales Netz
#                                 (liest docker-compose.dev-container.yml selbst, hier nur geprueft)
# Die Projektwurzel bindet docker-compose.dev-container.yml selbst ein.
write_compose_override() {
  local HOST_MOUNTS_NODE_MODULES="${AUTOTRIGGER_DEV_NODE_MODULES:-}"   # leer: npm root -g
  local missing
  case "${AUTOTRIGGER_DEV_CONTAINER_BIND:-127.0.0.1}" in
    *[!0-9a-fA-F.:]*) echo "Ungueltiges AUTOTRIGGER_DEV_CONTAINER_BIND '${AUTOTRIGGER_DEV_CONTAINER_BIND}' (eine IP-Adresse, z. B. 127.0.0.1 oder 0.0.0.0)." >&2
                      return 2 ;;
  esac
  host_mounts_yaml backend "" "${AUTOTRIGGER_DEV_DOCKER_SOCKET:-0}" "${AUTOTRIGGER_DEV_AI_FROM_HOST:-login}" \
    "${AUTOTRIGGER_DEV_AI_PROVIDERS:-}" "scripts/start-autotrigger-dev.sh --container" AUTOTRIGGER_DEV_ \
    > "${COMPOSE_OVERRIDE}" || { echo "Ungueltige AUTOTRIGGER_DEV_AI_*-Werte (siehe oben)." >&2; return 2; }
  echo "KI-Anbieter im Container: ${HOST_MOUNTS_READY:-keiner} (${AUTOTRIGGER_DEV_AI_FROM_HOST:-login}, ${COMPOSE_OVERRIDE})"
  missing="$(host_mounts_missing_text)"
  if [[ -n "${missing}" ]]; then
    printf 'Nicht nutzbar im Container:\n%s\n' "${missing}" >&2
  fi
}

# Das JAR, das Dockerfile.stable ins Image kopiert (COPY target/…jar). Ohne diesen Schritt lief nach
# einer Code-Aenderung still das zuletzt auf dem Host gebaute JAR, ohne JAR scheiterte der Build (3bfa4e10).
#   $1 = always (restart, build) | missing (start: nur mit --rebuild oder ohne JAR) | built (eben gebaut)
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

start_backend_container() {  # $1 = always|missing|built, siehe ensure_container_jar
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
    cd "${BACKEND_DIR}"   # cwd bleibt das Backend-Verzeichnis: ./data/automation.db, user.dir-Nutzer
    nohup java "${BACKEND_JVM_OPTS[@]}" ${AUTOTRIGGER_DEV_JVM_OPTS:-} -jar "${CURRENT_JAR}" \
      > "${BACKEND_LOG}" 2>&1 &
    echo "$!" > "${BACKEND_PID_FILE}"
  )
}

# Stoppt das Backend aus der PID-Datei (inkl. Kind-JVM eines alten Maven-Launchers).
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
    # Eigene Sitzung statt nur nohup: Node setzt beim Start alle Signal-Dispositionen zurueck und hebt
    # damit das SIG_IGN von nohup auf — schloss man das Terminal, beendete SIGHUP den Vite-Server,
    # waehrend das Backend (Java respektiert SIG_IGN) weiterlief (454f8ac5, gefunden von
    # scripts/installer-probe.sh). python3 statt setsid, weil setsid nur unter Linux dabei ist;
    # execvp behaelt die PID, die PID-Datei zeigt also weiter auf npm.
    nohup python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' npm run dev \
      > "${FRONTEND_LOG}" 2>&1 &
    echo "$!" > "${FRONTEND_PID_FILE}"
  )
}

# Nach einem restart: Vite nachstarten, falls er fehlt (etwa weil das Terminal abgestuerzt ist und
# ihn mitgenommen hat). start_frontend laesst einen laufenden Server in Ruhe. Scheitert der Start,
# bleibt es bei einer Warnung — restart gilt dem Backend, und das laeuft da schon.
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

# Zeigt den API-Key, den der neue User im Browser-Login eingeben muss.
# Drei Fälle: Security aus / eigener Key via Env / auto-generierter Key (Datei).
# Default maskiert (nur letzte 4 Zeichen) — mit --show-key voll. Sicher für CI/Screen-Sharing.
print_api_key() {
  local key_file="${HOME}/.autotrigger/api-key"
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
  echo "  Backend:  http://localhost:${BACKEND_PORT}/api/v1"
  if [[ "${backend_ok}" -eq 1 ]]; then
    print_api_key
  fi
  print_logs_hint

  if [[ "${backend_ok}" -eq 1 && "${frontend_ok}" -eq 1 ]]; then
    echo
    echo "Autotrigger Dev ist gestartet."
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
  echo "Autotrigger Dev Starter (${COMMAND})"
  echo "Repo: ${REPO_ROOT}"

  echo "Betriebsart: ${RUNTIME_MODE}"

  case "${COMMAND}" in
    build)
      # Baut das JAR, das ein spaeteres `start` ohne weiteren Build verwendet — so kompiliert
      # scripts/setup.sh das Backend nur einmal statt test-compile + clean package (0ab376d3).
      # Im Container-Betrieb dasselbe JAR in target/, aus dem start/restart das Image bauen (3bfa4e10).
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
        # Erst bauen, dann anhalten: scheitert Maven, laeuft der alte Container weiter
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
