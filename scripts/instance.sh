#!/usr/bin/env bash
#
# instance.sh — MySuricates-Instanzen starten, stoppen, ansehen: im Container oder auf diesem Rechner.
#
#   scripts/instance.sh up     --name NAME [--no-wait] [--rebuild]   # (bauen und) starten
#   scripts/instance.sh down   --name NAME                           # stoppen; Daten bleiben
#   scripts/instance.sh status --name NAME
#   scripts/instance.sh logs   --name NAME
#   scripts/instance.sh key    --name NAME                           # API-Key fuer den Login
#   scripts/instance.sh list                                          # alle Instanzen mit Port
#
# Jede Instanz ist eine Datei deploy/instances/NAME.env (git-ignoriert; der Installer schreibt sie).
# Commands: up|down|status|logs|key|list
#
# Steuerung (MYSURICATES_*): RUNTIME container|host, PORT (Pflicht), BIND (127.0.0.1), PRESET
# (core|research|full), LANG (de|en, Sprache der Adresszeilen; Default de), PROJECTS (Container: Ordner, der eingebunden wird; Default Ordner ueber dem
# Klon, nie ganz $HOME), DOCKER_SOCKET 0|1 (volle Kontrolle ueber Docker!), HARDENING_SEALING,
# HARDENING_REQUIRE_SEALED (true|false), ENVIRONMENT (Anzeigename). Jede AUTOMATION_*-Zeile geht
# unveraendert an die Instanz (Einzelmodule, Haertung, AUTOMATION_AI_DEFAULT_PROVIDER …).
#
# Host: gebaut wird in eine Kopie unter ${XDG_STATE_HOME:-~/.local/state}/mysuricates/NAME/ (JAR +
# Frontend), das Backend liefert das Frontend selbst aus — ein Prozess, ein Port. Neu gebaut wird mit
# --rebuild (oder wenn noch kein JAR liegt). bash-3.2-tauglich (macOS).
set -uo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/host-mounts.sh
. "${SCRIPT_DIR}/lib/host-mounts.sh"
# shellcheck source=lib/i18n.sh
. "${SCRIPT_DIR}/lib/i18n.sh"
# shellcheck source=lib/netaddr.sh
. "${SCRIPT_DIR}/lib/netaddr.sh"
INSTANCES_DIR="${MYSURICATES_INSTANCES_DIR:-${REPO_ROOT}/deploy/instances}"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose.yml"
# Werkzeuge aus dem Download des Installers (Java/Node ohne Paketmanager), falls vorhanden
[ -r "${XDG_DATA_HOME:-$HOME/.local/share}/mysuricates/env.sh" ] && . "${XDG_DATA_HOME:-$HOME/.local/share}/mysuricates/env.sh"

die() { echo "instance.sh: $*" >&2; exit 2; }
CMD="${1:-}"; [ "$#" -gt 0 ] && shift
NAME=""; WAIT=1; REBUILD=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift ;;
    --name=*) NAME="${1#--name=}" ;;
    --no-wait) WAIT=0 ;;
    --rebuild) REBUILD=1 ;;
    *) die "Unbekannte Option: $1" ;;
  esac
  shift
done
case "$CMD" in -h|--help|"") sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

env_value() { grep "^$2=" "$1" 2>/dev/null | tail -n 1 | cut -d= -f2-; }

if [ "$CMD" = list ]; then
  for f in "$INSTANCES_DIR"/*.env; do
    [ -f "$f" ] || continue
    printf '%-12s port %-6s %s\n' "$(basename "$f" .env)" "$(env_value "$f" MYSURICATES_PORT)" "$(env_value "$f" MYSURICATES_RUNTIME | sed 's/^$/container/')"
  done
  exit 0
fi

[ -n "$NAME" ] || die "--name fehlt"
case "$NAME" in *[!a-z0-9-]*) die "Name nur aus a-z, 0-9 und -" ;; esac
ENV_FILE="$INSTANCES_DIR/$NAME.env"
[ -f "$ENV_FILE" ] || die "Keine Instanz '$NAME' ($ENV_FILE fehlt)"

# Werte laden — nur KEY=VALUE, nichts wird ausgefuehrt. MYSURICATES_* steuern dieses Skript,
# AUTOMATION_* gehen an die Instanz.
AUTOMATION_ENV=()
while IFS='=' read -r k v || [ -n "$k" ]; do
  case "$k" in
    MYSURICATES_[A-Z_]*) export "$k=$v" ;;
    AUTOMATION_[A-Z0-9_]*) AUTOMATION_ENV[${#AUTOMATION_ENV[@]}]="$k=$v" ;;
  esac
done < "$ENV_FILE"
[ -n "${MYSURICATES_PORT:-}" ] || die "MYSURICATES_PORT fehlt in $ENV_FILE"
RUNTIME="${MYSURICATES_RUNTIME:-container}"
PORT="$MYSURICATES_PORT"
BIND="${MYSURICATES_BIND:-127.0.0.1}"
export MYSURICATES_NAME="$NAME" MYSURICATES_ENV_FILE="$ENV_FILE"
HOST_UID="$(id -u)"; HOST_GID="$(id -g)"; HOST_USER="$(id -un)"; HOST_HOME="$HOME"
export HOST_UID HOST_GID HOST_USER HOST_HOME

# "läuft"-Meldung: lokal eine Zeile wie bisher; bindet die Instanz ins Netz (--bind 0.0.0.0), dazu
# die Netzadresse(n) dieses Rechners und der VPN-Hinweis (b8aa3866, scripts/lib/netaddr.sh)
say_running() {  # WORT
  if netaddr_is_local "$BIND"; then echo "mysuricates-$NAME $1: http://localhost:$PORT"; return 0; fi
  i18n_load "${MYSURICATES_LANG:-de}"
  echo "mysuricates-$NAME $1:"
  netaddr_lines "$BIND" "$PORT" | sed 's/^/  /'
}

health_ok() { curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/v1/system/health" >/dev/null 2>&1; }
# Belegt = irgendetwas nimmt Verbindungen an (nicht: antwortet wie MySuricates)
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }
wait_health() {
  local i=0
  while [ "$i" -lt "${INSTANCE_HEALTH_TIMEOUT:-300}" ]; do
    "$@" && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

# ---------------------------------------------------------------------------------------------------
# Container
# ---------------------------------------------------------------------------------------------------
container_cmd() {
  local override="$INSTANCES_DIR/$NAME.override.yml" owner
  compose() { docker compose -p "mysuricates-$NAME" -f "$COMPOSE_FILE" -f "$override" "$@"; }
  # Ein gleichnamiger Container aus einer anderen Einrichtung (z. B. docker-compose.test.yml →
  # mysuricates-test) wird nie angefasst; down/logs bleiben ohnehin im eigenen Compose-Projekt.
  case "$CMD" in down|logs) ;; *)
    if owner="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "mysuricates-$NAME" 2>/dev/null)" \
       && [ "$owner" != "mysuricates-$NAME" ]; then
      die "Es gibt schon einen Container mysuricates-$NAME aus einer anderen Einrichtung (Compose-Projekt: ${owner:-keins}) — bitte einen anderen Namen wählen (--name)"
    fi ;;
  esac
  case "$CMD" in
    up)
      host_mounts_yaml app "${MYSURICATES_PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}" "${MYSURICATES_DOCKER_SOCKET:-0}" > "$override"
      compose up -d --build || exit 1
      [ "$WAIT" = 1 ] || exit 0
      if wait_health container_healthy; then say_running läuft; exit 0; fi
      echo "mysuricates-$NAME wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2; exit 1 ;;
    down)   compose down ;;
    status) docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null || echo "nicht gestartet" ;;
    logs)   compose logs --tail 200 ;;
    key)    docker exec -u "$HOST_UID" "mysuricates-$NAME" cat "$HOST_HOME/.autotrigger/api-key" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list)" ;;
  esac
}
container_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null)" = healthy ]; }

# ---------------------------------------------------------------------------------------------------
# Host — ein java-Prozess, gebautes Frontend, Zustand je Instanz
# ---------------------------------------------------------------------------------------------------
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/$NAME"
PID_FILE="$STATE/instance.pid"

host_running() { [ -s "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

host_build() {
  local jar
  echo "Baue Backend (Maven) ..."
  (cd "$REPO_ROOT/ai-automation-backend" && ./mvnw -q -B -DskipTests package) || return 1
  jar="$(find "$REPO_ROOT/ai-automation-backend/target" -maxdepth 1 -name 'ai-automation-backend-*.jar' ! -name '*.original' | head -n 1)"
  [ -n "$jar" ] || { echo "Kein JAR unter ai-automation-backend/target" >&2; return 1; }
  echo "Baue Frontend (npm ci, vite build) ..."
  (cd "$REPO_ROOT/ai-automation-frontend" && npm ci --no-audit --no-fund && npm run build) || return 1
  mkdir -p "$STATE"
  cp -f "$jar" "$STATE/app.jar"
  rm -rf "$STATE/frontend" && cp -R "$REPO_ROOT/ai-automation-frontend/dist" "$STATE/frontend"
}

host_cmd() {
  local pid
  case "$CMD" in
    up)
      if host_running; then say_running "läuft bereits"; exit 0; fi
      if [ "$REBUILD" = 1 ] || [ ! -s "$STATE/app.jar" ]; then host_build || exit 1; fi
      if port_busy; then die "Port $PORT ist schon belegt — anderen Port in $ENV_FILE eintragen"; fi
      mkdir -p "$STATE/data/logs" "$STATE/home"
      # Reihenfolge: erst die Werte aus MYSURICATES_*, dann die AUTOMATION_*-Zeilen der Datei —
      # was dort steht, gewinnt.
      (
        cd "$STATE" || exit 1
        # Eigenes user.home je Instanz: sonst teilte sie API-Key-Datei und Integrations-Schluessel
        # (~/.autotrigger/) mit jeder anderen Instanz dieses Benutzers — etwa einer Dev-Instanz. Java
        # liest user.home aus dem Konto, nicht aus $HOME. Die KI-CLIs erben weiter das echte $HOME;
        # wo das Backend selbst nach ihnen sucht, zeigen die Properties darauf.
        export AUTOMATION_HOST_HOME="$HOME" AUTOMATION_HOSTPI_CONFIGDIR="$HOME/.pi/agent" \
          AUTOMATION_MODELCATALOG_CODEXHOME="$HOME/.codex"
        export AUTOMATION_PORT="$PORT" SERVER_ADDRESS="$BIND" \
          AUTOMATION_SERVEFRONTEND=true AUTOMATION_FRONTENDPATH="$STATE/frontend" \
          AUTOMATION_DB_PATH="$STATE/data/automation.db" LOG_DIR="$STATE/data/logs" \
          AUTOMATION_BACKUP_DIR="$STATE/data/backups" AUTOMATION_REPORTS_DIR="$STATE/data/reports" \
          AUTOMATION_POOL_DIR="$REPO_ROOT/workflow-pool" AUTOMATION_DOCS_DIR="$REPO_ROOT/docs" \
          AUTOMATION_MODULES_PRESET="${MYSURICATES_PRESET:-core}" \
          AUTOMATION_ENVIRONMENT="${MYSURICATES_ENVIRONMENT:-}" \
          AUTOMATION_CORS_ORIGINS="http://localhost:$PORT,http://127.0.0.1:$PORT" \
          AUTOMATION_HARDENING_SEALING="${MYSURICATES_HARDENING_SEALING:-false}" \
          AUTOMATION_HARDENING_REQUIRESEALEDFORUNATTENDED="${MYSURICATES_HARDENING_REQUIRE_SEALED:-false}"
        for kv in ${AUTOMATION_ENV[@]+"${AUTOMATION_ENV[@]}"}; do export "$kv"; done
        nohup java -Duser.home="$STATE/home" -XX:+UseZGC -XX:+ZGenerational -Xms256m -Xmx1g -Djava.awt.headless=true \
          -XX:+ExitOnOutOfMemoryError -jar "$STATE/app.jar" > "$STATE/data/logs/stdout.log" 2>&1 < /dev/null &
        echo "$!" > "$PID_FILE"
      ) || exit 1
      [ "$WAIT" = 1 ] || exit 0
      if wait_health health_ok; then say_running läuft; exit 0; fi
      echo "mysuricates-$NAME wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2; exit 1 ;;
    down)
      if ! host_running; then echo "mysuricates-$NAME läuft nicht"; rm -f "$PID_FILE"; exit 0; fi
      pid="$(cat "$PID_FILE")"
      kill "$pid" 2>/dev/null
      i=0; while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
      rm -f "$PID_FILE"; echo "mysuricates-$NAME gestoppt" ;;
    status)
      if host_running; then
        if health_ok; then echo "running healthy"; else echo "running starting"; fi
      else echo "nicht gestartet"; fi ;;
    logs)   tail -n 200 "$STATE/data/logs/mysuricates.log" 2>/dev/null || tail -n 200 "$STATE/data/logs/stdout.log" ;;
    key)    cat "$STATE/home/.autotrigger/api-key" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list)" ;;
  esac
}

case "$RUNTIME" in
  container) container_cmd ;;
  host)      host_cmd ;;
  *)         die "MYSURICATES_RUNTIME muss container oder host sein, nicht '$RUNTIME'" ;;
esac
