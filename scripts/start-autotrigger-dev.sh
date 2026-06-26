#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/autotrigger-dev"
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

BACKEND_READY_URL="http://localhost:9090/api/v1/system/info"
# Vite serves HTTPS only when certs/dev.crt exists, otherwise plain HTTP.
# Probe both schemes so a fresh clone (no certs -> HTTP) is detected correctly.
FRONTEND_HTTPS_URL="https://localhost:3000"
FRONTEND_HTTP_URL="http://localhost:3000"

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

pid_is_running() {
  local pid_file="$1"
  local pid

  pid="$(read_pid "${pid_file}")"
  if [[ -z "${pid}" || ! "${pid}" =~ ^[0-9]+$ ]]; then
    return 1
  fi

  kill -0 "${pid}" >/dev/null 2>&1
}

endpoint_is_ready() {
  local url="$1"
  curl -fsSk --max-time 2 "${url}" >/dev/null 2>&1
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

start_backend() {
  if pid_is_running "${BACKEND_PID_FILE}" || endpoint_is_ready "${BACKEND_READY_URL}"; then
    echo "Backend laeuft bereits auf http://localhost:9090"
    return 0
  fi

  rm -f "${BACKEND_PID_FILE}"
  echo "Starte Backend ..."
  (
    cd "${BACKEND_DIR}"
    nohup "${MVN_CMD}" spring-boot:run > "${BACKEND_LOG}" 2>&1 &
    echo "$!" > "${BACKEND_PID_FILE}"
  )
}

start_frontend() {
  if pid_is_running "${FRONTEND_PID_FILE}" || frontend_is_ready; then
    echo "Frontend laeuft bereits auf $(resolved_frontend_url)"
    return 0
  fi

  rm -f "${FRONTEND_PID_FILE}"
  echo "Starte Frontend ..."
  (
    cd "${FRONTEND_DIR}"
    nohup npm run dev > "${FRONTEND_LOG}" 2>&1 &
    echo "$!" > "${FRONTEND_PID_FILE}"
  )
}

print_logs_hint() {
  echo
  echo "Logs:"
  echo "  Backend:  ${BACKEND_LOG}"
  echo "  Frontend: ${FRONTEND_LOG}"
}

# Zeigt den API-Key, den der neue User im Browser-Login eingeben muss.
# Drei Fälle: Security aus / eigener Key via Env / auto-generierter Key (Datei).
print_api_key() {
  local key_file="${HOME}/.autotrigger/api-key"
  echo
  if [[ "${AUTOMATION_SECURITY_ENABLED:-true}" == "false" ]]; then
    echo "API-Key: nicht nötig (AUTOMATION_SECURITY_ENABLED=false)"
  elif [[ -n "${AUTOMATION_API_KEY:-}" ]]; then
    echo "API-Key (eigener, via AUTOMATION_API_KEY):"
    echo "  ${AUTOMATION_API_KEY}"
  elif [[ -f "${key_file}" ]]; then
    echo "API-Key (beim ersten Öffnen im Browser-Login einfügen):"
    echo "  $(cat "${key_file}")"
    echo "  Quelle: ${key_file}"
  else
    echo "API-Key: noch nicht gefunden — siehe Backend-Log (${BACKEND_LOG})"
  fi
}

if [[ "${MVN_CMD}" == "mvn" ]]; then
  require_command mvn   # kein Wrapper vorhanden -> System-Maven nötig
fi
require_command npm
require_command curl

echo "Autotrigger Dev Starter"
echo "Repo: ${REPO_ROOT}"

start_backend
start_frontend

backend_ok=0
frontend_ok=0

if wait_for_endpoint "Backend" "${BACKEND_READY_URL}" 90; then
  backend_ok=1
fi

if wait_for_frontend 90; then
  frontend_ok=1
fi

echo
echo "URLs:"
echo "  Frontend: $(resolved_frontend_url)"
echo "  Backend:  http://localhost:9090/api/v1"
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
