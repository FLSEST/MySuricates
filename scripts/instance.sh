#!/usr/bin/env bash
#
# instance.sh — MySuricates-Instanzen starten, stoppen, ansehen: im Container oder auf diesem Rechner.
#
#   scripts/instance.sh up     --name NAME [--no-wait] [--rebuild]   # (bauen und) starten
#   scripts/instance.sh down   --name NAME                           # stoppen; Daten bleiben, laufende Runs brechen ab
#   scripts/instance.sh status --name NAME
#   scripts/instance.sh logs   --name NAME
#   scripts/instance.sh key    --name NAME                           # API-Key fuer den Login
#   scripts/instance.sh built  --name NAME                           # Host: Stand des Builds gegen den Klon
#   scripts/instance.sh list                                          # alle Instanzen mit Port
#   scripts/instance.sh login  --name NAME [claude|codex|pi]         # eigene Anmeldung (nur AI_FROM_HOST=programs)
#
# Jede Instanz ist eine Datei deploy/instances/NAME.env (git-ignoriert; der Installer schreibt sie).
# Commands: up|down|status|logs|key|list|login|built
#
# Steuerung (MYSURICATES_*): RUNTIME container|host, PORT (Pflicht), BIND (127.0.0.1), PRESET
# (core|research|full), LANG (de|en, Sprache der Adresszeilen; Default de), PROJECTS (Container: Ordner, der eingebunden wird; Default Ordner ueber dem
# Klon, nie ganz $HOME), DOCKER_SOCKET 0|1 (volle Kontrolle ueber Docker!), AI_FROM_HOST
# programs|login|all (was von den KI-Anbietern des Rechners in den Container kommt; Default login =
# Programme + je Anbieter nur die Anmeldung), AI_PROVIDERS (Auswahl aus claude,codex,pi), HARDENING_SEALING,
# HARDENING_REQUIRE_SEALED (true|false), ENVIRONMENT (Anzeigename). Jede AUTOMATION_*-Zeile geht
# unveraendert an die Instanz (Einzelmodule, Haertung, AUTOMATION_AI_DEFAULT_PROVIDER …).
#
# Host: gebaut wird in eine Kopie unter ${XDG_STATE_HOME:-~/.local/state}/mysuricates/NAME/ (JAR +
# Frontend), das Backend liefert das Frontend selbst aus — ein Prozess, ein Port. `up` baut neu, wenn
# der Klon auf einem anderen Commit steht als beim letzten Bau (built-from daneben), mit --rebuild oder
# wenn noch kein JAR liegt. Eine laufende Instanz baut `up` nicht um: es sagt, dass sie einen alten
# Stand zeigt — uebernommen wird er mit down und up (1c016162). Container: `up` baut das Image jedes
# Mal (unveraenderte Schichten aus dem Cache), und zwar nur aus den getrackten Dateien des Klons
# (890e8e9c), und ersetzt einen laufenden Container, wenn sich das Image geaendert hat. `down` nennt
# vorher die Workflow-Runs, die dabei abbrechen — gefragt wird die Instanz nur lesend (29820f58).
# bash-3.2-tauglich (macOS).
set -uo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/host-mounts.sh
. "${SCRIPT_DIR}/lib/host-mounts.sh"
# shellcheck source=lib/provider-login.sh
. "${SCRIPT_DIR}/lib/provider-login.sh"
# shellcheck source=lib/i18n.sh
. "${SCRIPT_DIR}/lib/i18n.sh"
# shellcheck source=lib/netaddr.sh
. "${SCRIPT_DIR}/lib/netaddr.sh"
# shellcheck source=lib/local-curl.sh
. "${SCRIPT_DIR}/lib/local-curl.sh"
# shellcheck source=lib/instance-runs.sh
. "${SCRIPT_DIR}/lib/instance-runs.sh"
INSTANCES_DIR="${MYSURICATES_INSTANCES_DIR:-${REPO_ROOT}/deploy/instances}"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose.yml"
# Werkzeuge aus dem Download des Installers (Java/Node ohne Paketmanager), falls vorhanden
[ -r "${XDG_DATA_HOME:-$HOME/.local/share}/mysuricates/env.sh" ] && . "${XDG_DATA_HOME:-$HOME/.local/share}/mysuricates/env.sh"

die() { echo "instance.sh: $*" >&2; exit 2; }
CMD="${1:-}"; [ "$#" -gt 0 ] && shift
NAME=""; WAIT=1; REBUILD=0; TOOL=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift ;;
    --name=*) NAME="${1#--name=}" ;;
    --no-wait) WAIT=0 ;;
    --rebuild) REBUILD=1 ;;
    claude|codex|pi) TOOL="$1" ;;
    *) die "Unbekannte Option: $1" ;;
  esac
  shift
done
case "$CMD" in -h|--help|"") sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

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
# mysuricates-tryout und seine Volumes gehoeren scripts/tryout.sh — nie eine Instanz darueberlegen
[ "$NAME" != tryout ] || die "Der Name 'tryout' ist fuer scripts/tryout.sh reserviert"
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
  if netaddr_is_local "$BIND"; then echo "mysuricates-$NAME $1: $(netaddr_local_url "$BIND" "$PORT")"; return 0; fi
  i18n_load "${MYSURICATES_LANG:-de}"
  echo "mysuricates-$NAME $1:"
  netaddr_lines "$BIND" "$PORT" | sed 's/^/  /'
}

# Dort, wo die Instanz lauscht — mit --bind auf eine Adresse nur dort, nicht auf 127.0.0.1 (3d002903) —,
# und ohne Proxy aus der Umgebung (bb4f60a5): an beidem scheiterte die Pruefung, up wartete 300 s
health_ok() { local_curl -fsS --max-time 3 "$(instance_api_base "$BIND" "$PORT")/api/v1/system/health" >/dev/null 2>&1; }
# Laufende Workflow-Runs brechen beim Stoppen ab (29820f58): vorher sagen, wie viele. Nur eine Warnung,
# down ist ein ausdruecklicher Befehl; antwortet die Instanz nicht oder fehlt der Key, still weiter.
warn_running_runs() {  # KEY
  local n
  n="$(instance_running_runs "$(instance_api_base "$BIND" "$PORT")" "${1:-}")"
  [ -n "$n" ] && [ "$n" -gt 0 ] || return 0
  echo "mysuricates-$NAME: $n Workflow-Run(s) laufen noch — sie brechen jetzt ab." >&2
}
# Belegt = irgendetwas nimmt auf der Adresse der Instanz Verbindungen an (nicht: antwortet wie MySuricates)
port_busy() { (exec 3<>"/dev/tcp/$(instance_connect_addr "$BIND")/$PORT") 2>/dev/null; }
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
# Build-Kontext des Images: die getrackten Dateien des Klons, wie sie im Arbeitsbaum liegen (git
# ls-files), nie der ganze Ordner. Nur per .git/info/exclude ausgeschlossene Pool-Definitionen oder
# andere private Dateien kaemen sonst ins Image und in den Pool-Browser (890e8e9c); die Positivliste
# in .dockerignore greift danach zusaetzlich. Geaenderte getrackte Dateien zaehlen mit, wie beim
# Host-Build; geloeschte fehlen einfach. scripts/tryout.sh baut ebenso nur Getracktes (git archive).
tracked_files_tar() {
  ( cd "$REPO_ROOT" || exit 1
    git ls-files -z | while IFS= read -r -d '' f; do
      if [ -e "$f" ] || [ -L "$f" ]; then printf '%s\0' "$f"; fi
    done | tar --null -T - -cf - )
}
is_git_clone() { [ "$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null)" = "$(cd "$REPO_ROOT" && pwd -P)" ]; }

container_cmd() {
  local override="$INSTANCES_DIR/$NAME.override.yml" owner missing
  compose() { docker compose -p "mysuricates-$NAME" -f "$COMPOSE_FILE" -f "$override" "$@"; }
  build_image() {
    if is_git_clone; then
      echo "Baue Image mysuricates:local aus den getrackten Dateien des Klons ..."
      tracked_files_tar | docker build -f deploy/Dockerfile --target runtime -t mysuricates:local -
    else
      # Ohne git (etwa ein entpacktes Release-Archiv, das ohnehin nur Getracktes enthaelt): der Ordner
      compose build
    fi
  }
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
      # Was vom Host in den Container kommt, entscheiden die Schalter der Instanzdatei (262948a4)
      host_mounts_yaml app "${MYSURICATES_PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}" "${MYSURICATES_DOCKER_SOCKET:-0}" \
        "${MYSURICATES_AI_FROM_HOST:-login}" "${MYSURICATES_AI_PROVIDERS:-}" "$ENV_FILE" MYSURICATES_ > "$override" \
        || die "Ungültige KI-Schalter in $ENV_FILE (MYSURICATES_AI_FROM_HOST=programs|login|all, MYSURICATES_AI_PROVIDERS=claude,codex,pi)"
      missing="$(host_mounts_missing_text)"
      [ -z "$missing" ] || printf 'KI-Anbieter, die dieser Container nicht nutzen kann:\n%s\n' "$missing" >&2
      build_image || exit 1
      compose up -d --no-build || exit 1
      [ "$WAIT" = 1 ] || exit 0
      if wait_health container_healthy; then say_running läuft; exit 0; fi
      echo "mysuricates-$NAME wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2; exit 1 ;;
    down)
      # Key nur aus dem eigenen Container — einen gleichnamigen aus einer anderen Einrichtung fasst down nicht an
      if [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "mysuricates-$NAME" 2>/dev/null)" = "mysuricates-$NAME" ]; then
        warn_running_runs "$(docker exec -u "$HOST_UID" "mysuricates-$NAME" cat "$HOST_HOME/.autotrigger/api-key" 2>/dev/null)"
      fi
      compose down ;;
    status) docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null || echo "nicht gestartet" ;;
    logs)   compose logs --tail 200 ;;
    key)    docker exec -u "$HOST_UID" "mysuricates-$NAME" cat "$HOST_HOME/.autotrigger/api-key" ;;
    login)
      # Im Modus login kommt die Anmeldung vom Rechner; eine zweite im Container ueberschriebe dort die
      # eingebundene auth.json (Codex, Pi) oder liefe am Token vorbei (Claude).
      [ "${MYSURICATES_AI_FROM_HOST:-login}" = programs ] \
        || die "mysuricates-$NAME nutzt die Anmeldung dieses Rechners (MYSURICATES_AI_FROM_HOST=${MYSURICATES_AI_FROM_HOST:-login}). Eigene Anmeldung nur mit MYSURICATES_AI_FROM_HOST=programs in $ENV_FILE"
      provider_login_argv "${TOOL:-claude}" || die "login kennt claude, codex, pi"
      docker exec -it -u "$HOST_UID" "mysuricates-$NAME" "${PROVIDER_LOGIN_ARGV[@]}" ;;
    built)  die "built gibt es nur für Host-Instanzen — im Container baut docker compose das Image bei jedem up" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list|login|built)" ;;
  esac
}
container_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "mysuricates-$NAME" 2>/dev/null)" = healthy ]; }

# ---------------------------------------------------------------------------------------------------
# Host — ein java-Prozess, gebautes Frontend, Zustand je Instanz
# ---------------------------------------------------------------------------------------------------
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/$NAME"
PID_FILE="$STATE/instance.pid"
BUILT_FROM="$STATE/built-from"   # Commit des Klons beim letzten Bau (1c016162)

host_running() { [ -s "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

# Commit, auf dem der Klon steht — leer ohne git (dann bleibt es beim vorhandenen Build)
repo_head() { git -C "$REPO_ROOT" rev-parse --verify -q HEAD 2>/dev/null; }
short_sha() { if [ "${1:--}" = - ]; then printf 'unbekannt'; else printf '%s' "${1:0:8}"; fi; }

# Stand des Host-Builds gegen den Klon, eine Zeile (auch fuer scripts/setup.sh):
#   none                 noch kein Build
#   current SHA          gebaut aus dem Commit, auf dem der Klon steht
#   outdated BUILT HEAD  aus einem anderen Commit gebaut; BUILT ist "-", wenn nicht vermerkt ist, woraus
#                        (Bau vor dieser Buchfuehrung oder abgebrochen) — dann lieber einmal zu oft bauen
#   unknown BUILT        Commit des Klons nicht ermittelbar (kein git) — kein Vergleich, kein Neubau
host_build_state() {
  local built head
  [ -s "$STATE/app.jar" ] || { echo none; return 0; }
  built="$(cat "$BUILT_FROM" 2>/dev/null)"; head="$(repo_head)"
  if [ -z "$head" ]; then echo "unknown ${built:--}"
  elif [ "$built" = "$head" ]; then echo "current $head"
  else echo "outdated ${built:--} $head"; fi
}

host_build() {
  local jar count head
  head="$(repo_head)"   # vor dem Bau: ein git pull waehrend der Minuten danach zaehlt nicht mit
  echo "Baue Backend (Maven) ..."
  # clean: sonst bleibt das JAR der vorigen Version im target/ liegen und der find unten nimmt
  # irgendeines davon. Beim Sprung der Demo-VM von 0.7.6 auf 0.7.7 (2026-09-23) kopierte er so
  # das alte JAR zurueck — die Instanz lief nach dem „Upgrade" unbemerkt weiter auf 0.7.6.
  (cd "$REPO_ROOT/ai-automation-backend" && ./mvnw -q -B -DskipTests clean package) || return 1
  jar="$(find "$REPO_ROOT/ai-automation-backend/target" -maxdepth 1 -name 'ai-automation-backend-*.jar' ! -name '*.original')"
  count="$(printf '%s' "$jar" | grep -c .)"
  if [ "$count" -gt 1 ]; then
    echo "Mehrere JARs unter ai-automation-backend/target — welches gemeint ist, ist nicht entscheidbar:" >&2
    printf '%s\n' "$jar" | sed 's/^/  /' >&2
    echo "  target/ leeren (mvnw clean) und neu bauen." >&2
    return 1
  fi
  [ -n "$jar" ] || { echo "Kein JAR unter ai-automation-backend/target" >&2; return 1; }
  echo "Baue Frontend (npm ci, vite build) ..."
  (cd "$REPO_ROOT/ai-automation-frontend" && npm ci --no-audit --no-fund && npm run build) || return 1
  mkdir -p "$STATE"
  # Erst den Vermerk loeschen: bricht das Kopieren ab, baut das naechste up neu, statt dem halben
  # Stand den alten Commit zuzuschreiben.
  rm -f "$BUILT_FROM"
  cp -f "$jar" "$STATE/app.jar" || return 1
  rm -rf "$STATE/frontend" && cp -R "$REPO_ROOT/ai-automation-frontend/dist" "$STATE/frontend" || return 1
  [ -z "$head" ] || printf '%s\n' "$head" > "$BUILT_FROM"
}

host_cmd() {
  local pid st built head
  case "$CMD" in
    up)
      read -r st built head <<EOF
$(host_build_state)
EOF
      if host_running; then
        say_running "läuft bereits"
        # Nie still auf dem alten Stand weiterlaufen (1c016162) — aber auch nicht ungefragt anhalten:
        # das beendete laufende Workflow-Runs. Uebernommen wird der neue Stand mit down und up.
        if [ "$st" = outdated ]; then
          echo "mysuricates-$NAME läuft mit Stand $(short_sha "$built"), der Klon steht auf $(short_sha "$head") — nicht übernommen." >&2
          echo "  Übernehmen (baut neu): scripts/instance.sh down --name $NAME && scripts/instance.sh up --name $NAME" >&2
        elif [ "$REBUILD" = 1 ]; then
          echo "--rebuild wirkt erst auf eine gestoppte Instanz: scripts/instance.sh down --name $NAME, dann up --rebuild" >&2
        fi
        exit 0
      fi
      if port_busy; then die "Port $PORT ist schon belegt — anderen Port in $ENV_FILE eintragen"; fi
      case "$st" in
        none) host_build || exit 1 ;;
        outdated)
          echo "Der Klon steht auf $(short_sha "$head"), gebaut ist Stand $(short_sha "$built") — baue neu"
          host_build || exit 1 ;;
        *)
          if [ "$REBUILD" = 1 ]; then host_build || exit 1
          else echo "Build vorhanden, nicht neu gebaut (Stand $(short_sha "$built"))"; fi ;;
      esac
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
          AUTOMATION_INSTANCE_URL="http://localhost:$PORT" \
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
      warn_running_runs "$(cat "$STATE/home/.autotrigger/api-key" 2>/dev/null)"
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
    built)  host_build_state ;;
    login)  die "login gibt es nur fuer Container-Instanzen — im Host-Betrieb nutzen die Programme die Anmeldungen dieses Rechners direkt" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list|login|built)" ;;
  esac
}

case "$RUNTIME" in
  container) container_cmd ;;
  host)      host_cmd ;;
  *)         die "MYSURICATES_RUNTIME muss container oder host sein, nicht '$RUNTIME'" ;;
esac
