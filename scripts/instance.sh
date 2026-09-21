#!/usr/bin/env bash
#
# instance.sh — MySuricates-Instanzen als Container verwalten (deploy/compose.yml).
#
#   scripts/instance.sh up     --name NAME [--no-wait]   # bauen (falls noetig) und starten
#   scripts/instance.sh down   --name NAME               # stoppen; Daten bleiben in den Volumes
#   scripts/instance.sh status --name NAME
#   scripts/instance.sh logs   --name NAME
#   scripts/instance.sh key    --name NAME               # API-Key fuer den Login
#   scripts/instance.sh list                              # alle Instanzen mit Port
#
# Jede Instanz ist eine Datei deploy/instances/NAME.env (git-ignoriert; der Installer schreibt sie).
# Commands: up|down|status|logs|key|list
#
# Werte in NAME.env: MYSURICATES_PORT (Pflicht), MYSURICATES_BIND (127.0.0.1), MYSURICATES_PRESET
# (core|research|full), MYSURICATES_PROJECTS (Default: Ordner ueber dem Klon, nie ganz $HOME),
# MYSURICATES_DOCKER_SOCKET (0|1 — gibt der Instanz volle Kontrolle ueber Docker),
# MYSURICATES_HARDENING_SEALING, MYSURICATES_HARDENING_REQUIRE_SEALED (true|false),
# MYSURICATES_ENVIRONMENT (Anzeigename, z. B. Prod). bash-3.2-tauglich (macOS).
set -uo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/host-mounts.sh
. "${SCRIPT_DIR}/lib/host-mounts.sh"
INSTANCES_DIR="${MYSURICATES_INSTANCES_DIR:-${REPO_ROOT}/deploy/instances}"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose.yml"

die() { echo "instance.sh: $*" >&2; exit 2; }
CMD="${1:-}"; [ "$#" -gt 0 ] && shift
NAME=""; WAIT=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift ;;
    --name=*) NAME="${1#--name=}" ;;
    --no-wait) WAIT=0 ;;
    *) die "Unbekannte Option: $1" ;;
  esac
  shift
done
case "$CMD" in -h|--help|"") sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

if [ "$CMD" = list ]; then
  for f in "$INSTANCES_DIR"/*.env; do
    [ -f "$f" ] || continue
    printf '%-12s port %s\n' "$(basename "$f" .env)" "$(grep '^MYSURICATES_PORT=' "$f" | cut -d= -f2)"
  done
  exit 0
fi

[ -n "$NAME" ] || die "--name fehlt"
case "$NAME" in *[!a-z0-9-]*) die "Name nur aus a-z, 0-9 und -" ;; esac
ENV_FILE="$INSTANCES_DIR/$NAME.env"
[ -f "$ENV_FILE" ] || die "Keine Instanz '$NAME' ($ENV_FILE fehlt)"
OVERRIDE="$INSTANCES_DIR/$NAME.override.yml"

# Werte der Instanz laden — nur KEY=VALUE mit MYSURICATES_-Praefix, nichts wird ausgefuehrt
while IFS='=' read -r k v || [ -n "$k" ]; do
  case "$k" in MYSURICATES_[A-Z_]*) export "$k=$v" ;; esac
done < "$ENV_FILE"
[ -n "${MYSURICATES_PORT:-}" ] || die "MYSURICATES_PORT fehlt in $ENV_FILE"
export MYSURICATES_NAME="$NAME"
HOST_UID="$(id -u)"; HOST_GID="$(id -g)"; HOST_USER="$(id -un)"; HOST_HOME="$HOME"
export HOST_UID HOST_GID HOST_USER HOST_HOME
PROJECTS="${MYSURICATES_PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}"

compose() { docker compose -p "mysuricates-$NAME" -f "$COMPOSE_FILE" -f "$OVERRIDE" "$@"; }

case "$CMD" in
  up)
    host_mounts_yaml app "$PROJECTS" "${MYSURICATES_DOCKER_SOCKET:-0}" > "$OVERRIDE"
    compose up -d --build || exit 1
    [ "$WAIT" = 1 ] || exit 0
    i=0
    while [ "$i" -lt "${INSTANCE_HEALTH_TIMEOUT:-300}" ]; do
      if [ "$(docker inspect -f '{{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null)" = healthy ]; then
        echo "mysuricates-$NAME läuft: http://localhost:${MYSURICATES_PORT}"
        exit 0
      fi
      sleep 2; i=$((i + 2))
    done
    echo "mysuricates-$NAME wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2
    exit 1 ;;
  down)   compose down ;;
  status) docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null || echo "nicht gestartet" ;;
  logs)   compose logs --tail 200 ;;
  key)    docker exec -u "$HOST_UID" "mysuricates-$NAME" cat "$HOST_HOME/.autotrigger/api-key" ;;
  *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list)" ;;
esac
