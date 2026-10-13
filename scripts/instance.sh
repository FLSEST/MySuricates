#!/usr/bin/env bash
#
# instance.sh — start, stop, inspect MySuricates instances: in a container or on this machine.
#
#   scripts/instance.sh up     --name NAME [--no-wait] [--rebuild]   # (build and) start
#   scripts/instance.sh down   --name NAME                           # stop; data stays, running runs abort
#   scripts/instance.sh status --name NAME
#   scripts/instance.sh logs   --name NAME
#   scripts/instance.sh key    --name NAME                           # API key for the login
#   scripts/instance.sh built  --name NAME                           # host: state of the build vs. the clone
#   scripts/instance.sh list                                          # all instances with port
#   scripts/instance.sh login  --name NAME [claude|codex|pi]         # own login (only AI_FROM_HOST=programs)
#   scripts/instance.sh sync-keys --name NAME                        # fetch the keys of the sync targets (staged: dev)
#
# Each instance is a file deploy/instances/NAME.env (git-ignored; the installer writes it).
# Commands: up|down|status|logs|key|list|login|built|sync-keys
#
# Control (MYSURICATES_*): RUNTIME container|host, PORT (required), BIND (127.0.0.1), PRESET
# (core|research|full), LANG (de|en, language of the address lines; default de), PROJECTS (container: folder that gets mounted; default folder above the
# clone, never all of $HOME), DOCKER_SOCKET 0|1 (full control over Docker!), AI_FROM_HOST
# programs|login|all (what of the machine's AI providers goes into the container; default login =
# programs + only the login per provider), AI_PROVIDERS (selection from claude,codex,pi), HARDENING_SEALING,
# HARDENING_REQUIRE_SEALED (true|false), ENVIRONMENT (display name), APT_REFRESH (container: a new
# value, e.g. the month, fetches the image's NodeSource and Docker repo fresh), PREFIX (container: prefix
# of the Docker names PREFIX-NAME and of the image PREFIX:local, default mysuricates), NETWORK (container: shared
# Docker network PREFIX-NETWORK in which the instance is reachable under its name — staged: staged), SYNC_TARGETS
# (instances whose API key sync-keys fetches into deploy/instances/NAME.sync-keys/; read-only in the container under
# /app/sync-keys). Every AUTOMATION_* line goes to the instance unchanged (single modules, hardening,
# AUTOMATION_AI_DEFAULT_PROVIDER, AUTOMATION_SYNC_* …).
#
# Host: built into a copy under ${XDG_STATE_HOME:-~/.local/state}/mysuricates/NAME/ (JAR +
# frontend), the backend serves the frontend itself — one process, one port. `up` rebuilds if
# the clone is on a different commit than at the last build (built-from alongside), with --rebuild or
# if no JAR exists yet. `up` does not rebuild a running instance: it says that it shows an old
# state — it is picked up with down and up (1c016162). Container: `up` builds the image every
# time (unchanged layers from the cache), and only from the tracked files of the clone
# (890e8e9c), and replaces a running container if the image changed. `down` names
# beforehand the workflow runs that abort — the instance is only queried read-only (29820f58).
# bash-3.2 compatible (macOS).
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
# shellcheck source=lib/data-home.sh
. "${SCRIPT_DIR}/lib/data-home.sh"
INSTANCES_DIR="${MYSURICATES_INSTANCES_DIR:-${REPO_ROOT}/deploy/instances}"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose.yml"
# Tools from the installer's download (Java/Node without a package manager), if present
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
case "$CMD" in -h|--help|"") sed -n '3,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

env_value() { grep "^$2=" "$1" 2>/dev/null | tail -n 1 | cut -d= -f2-; }

if [ "$CMD" = list ]; then
  for f in "$INSTANCES_DIR"/*.env; do
    [ -f "$f" ] || continue
    printf '%-12s port %-6s %s\n' "$(basename "$f" .env)" "$(env_value "$f" MYSURICATES_PORT)" "$(env_value "$f" MYSURICATES_RUNTIME | sed 's/^$/container/')"
  done
  exit 0
fi

[ -n "$NAME" ] || die "--name fehlt"
# Not with - at the start: the name becomes Docker names and arguments (mysuricates--x, -x.env)
case "$NAME" in *[!a-z0-9-]*|-*) die "Name nur aus a-z, 0-9 und - (nicht am Anfang)" ;; esac
# mysuricates-tryout and its volumes belong to scripts/tryout.sh — never put an instance on top of it
[ "$NAME" != tryout ] || die "Der Name 'tryout' ist fuer scripts/tryout.sh reserviert"
ENV_FILE="$INSTANCES_DIR/$NAME.env"
[ -f "$ENV_FILE" ] || die "Keine Instanz '$NAME' ($ENV_FILE fehlt)"

# Load values — only KEY=VALUE, nothing is executed. MYSURICATES_* control this script,
# AUTOMATION_* go to the instance.
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
# Names in Docker: container and compose project PREFIX-NAME, volumes PREFIX-NAME-data/-secrets, image
# PREFIX:local. The prefix (default mysuricates) keeps e.g. the installer probe with its Docker in the
# container away from real instances on the same machine — same names would mean same volumes.
PREFIX="${MYSURICATES_PREFIX:-mysuricates}"
case "$PREFIX" in *[!a-z0-9-]*|-*) die "MYSURICATES_PREFIX nur aus a-z, 0-9 und - (nicht am Anfang)" ;; esac
CONTAINER="$PREFIX-$NAME"; IMAGE="$PREFIX:local"
case "${MYSURICATES_NETWORK:-}" in *[!a-z0-9-]*|-*) die "MYSURICATES_NETWORK nur aus a-z, 0-9 und - (nicht am Anfang)" ;; esac
case "${MYSURICATES_SYNC_TARGETS:-}" in *[!a-z0-9,-]*) die "MYSURICATES_SYNC_TARGETS: Instanznamen, durch Komma getrennt" ;; esac
export MYSURICATES_NAME="$NAME" MYSURICATES_ENV_FILE="$ENV_FILE" MYSURICATES_CONTAINER="$CONTAINER" MYSURICATES_IMAGE="$IMAGE"
HOST_UID="$(id -u)"; HOST_GID="$(id -g)"; HOST_USER="$(id -un)"; HOST_HOME="$HOME"
export HOST_UID HOST_GID HOST_USER HOST_HOME

# "running" message: locally one line as before; if the instance binds to the network (--bind 0.0.0.0), in addition
# the network address(es) of this machine and the VPN hint (b8aa3866, scripts/lib/netaddr.sh)
say_running() {  # WORD
  if netaddr_is_local "$BIND"; then echo "$CONTAINER $1: $(netaddr_local_url "$BIND" "$PORT")"; return 0; fi
  i18n_load "${MYSURICATES_LANG:-de}"
  echo "$CONTAINER $1:"
  netaddr_lines "$BIND" "$PORT" | sed 's/^/  /'
}

# Where the instance listens — with --bind to one address only there, not on 127.0.0.1 (3d002903) —,
# and without a proxy from the environment (bb4f60a5): the check failed on both, up waited 300 s
health_ok() { local_curl -fsS --max-time 3 "$(instance_api_base "$BIND" "$PORT")/api/v1/system/health" >/dev/null 2>&1; }
# Running workflow runs abort on stopping (29820f58): say beforehand how many. Only a warning,
# down is an explicit command; if the instance does not answer or the key is missing, carry on silently.
warn_running_runs() {  # KEY
  local n
  n="$(instance_running_runs "$(instance_api_base "$BIND" "$PORT")" "${1:-}")"
  [ -n "$n" ] && [ "$n" -gt 0 ] || return 0
  echo "$CONTAINER: $n Workflow-Run(s) laufen noch — sie brechen jetzt ab." >&2
}
# Taken = something accepts connections on the instance's address (not: answers like MySuricates)
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
# Staged operation: definition sync Dev → Test / Prod (84a78bb7)
# ---------------------------------------------------------------------------------------------------
# Dev reaches Test and Prod over a shared Docker network under their instance names
# (http://test:9090) — not via host.docker.internal: the ports are only on 127.0.0.1 of the
# machine, and a container on Linux does not reach those that way. sync-keys fetches the targets' API keys
# via `key` (data folder of the target instance, never from its DB) into one file per target; Dev
# reads them read-only under /app/sync-keys (AUTOMATION_SYNC_*_KEY_FILE).
staged_network() { [ -z "${MYSURICATES_NETWORK:-}" ] || printf '%s-%s' "$PREFIX" "$MYSURICATES_NETWORK"; }
sync_keys_dir() { printf '%s/%s.sync-keys' "$INSTANCES_DIR" "$NAME"; }
sync_targets() { printf '%s' "${MYSURICATES_SYNC_TARGETS:-}" | tr ',' ' '; }

# Extra file for docker compose: network (with the instance name as alias) and key folder. Return 1 =
# none of it configured, no file needed.
staged_yaml() {
  local net
  net="$(staged_network)"
  [ -n "$net" ] || [ -n "${MYSURICATES_SYNC_TARGETS:-}" ] || return 1
  printf '# Geschrieben von scripts/instance.sh up aus %s — nicht von Hand aendern.\nservices:\n  app:\n' "$ENV_FILE"
  [ -z "$net" ] || printf '    networks:\n      default: {}\n      staged:\n        aliases: ["%s"]\n' "$NAME"
  [ -z "${MYSURICATES_SYNC_TARGETS:-}" ] || printf '    volumes:\n      - "%s:/app/sync-keys:ro"\n' "$(sync_keys_dir)"
  [ -z "$net" ] || printf 'networks:\n  staged:\n    name: "%s"\n    external: true\n' "$net"
  return 0
}

if [ "$CMD" = sync-keys ]; then
  [ -n "${MYSURICATES_SYNC_TARGETS:-}" ] || die "$NAME hat keine Sync-Ziele (MYSURICATES_SYNC_TARGETS in $ENV_FILE)"
  dir="$(sync_keys_dir)"
  mkdir -p "$dir" && chmod 700 "$dir" || die "$dir lässt sich nicht anlegen"
  rc=0
  for t in $(sync_targets); do
    [ "$t" != "$NAME" ] || continue
    # Without this instance's MYSURICATES_* values — the target reads its own file
    key="$(for v in $(compgen -e); do case "$v" in MYSURICATES_PREFIX|MYSURICATES_INSTANCES_DIR) ;; MYSURICATES_*) unset "$v" ;; esac; done
           bash "$SCRIPT_DIR/instance.sh" key --name "$t" 2>/dev/null | tr -d '\r\n')"
    if [ -n "$key" ] && (umask 077 && printf '%s\n' "$key" > "$dir/$t.key.tmp") && mv -f "$dir/$t.key.tmp" "$dir/$t.key"; then
      echo "$t: Key übernommen ($dir/$t.key)"
    else
      rm -f "$dir/$t.key.tmp"
      echo "$t: kein Key — läuft die Instanz? scripts/instance.sh up --name $t, dann noch einmal sync-keys" >&2
      rc=1
    fi
  done
  exit "$rc"
fi

# ---------------------------------------------------------------------------------------------------
# Container
# ---------------------------------------------------------------------------------------------------
# Build context of the image: the tracked files of the clone as they are in the working tree (git
# ls-files), never the whole folder. Pool definitions excluded only via .git/info/exclude or
# other private files would otherwise end up in the image and in the pool browser (890e8e9c); the allowlist
# in .dockerignore applies in addition afterwards. Changed tracked files count, as with the
# host build; deleted ones are simply missing. scripts/tryout.sh likewise builds only tracked files (git archive).
tracked_files_tar() {
  ( cd "$REPO_ROOT" || exit 1
    git ls-files -z | while IFS= read -r -d '' f; do
      if [ -e "$f" ] || [ -L "$f" ]; then printf '%s\0' "$f"; fi
    done | tar --null -T - -cf - )
}
is_git_clone() { [ "$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null)" = "$(cd "$REPO_ROOT" && pwd -P)" ]; }

container_cmd() {
  local override="$INSTANCES_DIR/$NAME.override.yml" staged="$INSTANCES_DIR/$NAME.staged.yml" owner missing login_command net
  compose() {
    if [ -f "$staged" ]; then docker compose -p "$CONTAINER" -f "$COMPOSE_FILE" -f "$override" -f "$staged" "$@"
    else docker compose -p "$CONTAINER" -f "$COMPOSE_FILE" -f "$override" "$@"; fi
  }
  build_image() {
    if is_git_clone; then
      echo "Baue Image $IMAGE aus den getrackten Dateien des Klons ..."
      # APT_REFRESH as in deploy/compose.yml: a new value fetches the third-party repos fresh (8b7a39b9)
      tracked_files_tar | docker build -f deploy/Dockerfile --target runtime \
        --build-arg "APT_REFRESH=${MYSURICATES_APT_REFRESH:-}" -t "$IMAGE" -
    else
      # Without git (e.g. an unpacked release archive, which contains only tracked files anyway): the folder
      compose build
    fi
  }
  # A container of the same name from another setup (e.g. docker-compose.test.yml →
  # mysuricates-test) is never touched; down/logs stay in their own compose project anyway.
  case "$CMD" in down|logs) ;; *)
    if owner="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$CONTAINER" 2>/dev/null)" \
       && [ "$owner" != "$CONTAINER" ]; then
      die "Es gibt schon einen Container $CONTAINER aus einer anderen Einrichtung (Compose-Projekt: ${owner:-keins}) — bitte einen anderen Namen wählen (--name)"
    fi ;;
  esac
  case "$CMD" in
    up)
      # What comes from the host into the container is decided by the switches of the instance file (262948a4)
      # Only in programs mode does the container log in itself — the status card then shows the
      # command that does it (fixed text, never executed).
      login_command=""
      [ "${MYSURICATES_AI_FROM_HOST:-login}" = programs ] && login_command="scripts/instance.sh login"
      HOST_MOUNTS_PUBLISHED_BIND="$BIND" HOST_MOUNTS_PUBLISHED_PORT="$PORT" HOST_MOUNTS_BIND_SWITCH=MYSURICATES_BIND \
      host_mounts_yaml app "${MYSURICATES_PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}" "${MYSURICATES_DOCKER_SOCKET:-0}" \
        "${MYSURICATES_AI_FROM_HOST:-login}" "${MYSURICATES_AI_PROVIDERS:-}" "$ENV_FILE" MYSURICATES_ "$login_command" "instance-$CONTAINER" > "$override" \
        || die "Ungültige KI-Schalter in $ENV_FILE (MYSURICATES_AI_FROM_HOST=programs|login|all, MYSURICATES_AI_PROVIDERS=claude,codex,pi)"
      missing="$(host_mounts_missing_text)"
      [ -z "$missing" ] || printf 'KI-Anbieter, die dieser Container nicht nutzen kann:\n%s\n' "$missing" >&2
      # Staged operation (84a78bb7): shared network and key folder — both must exist before compose up,
      # otherwise Docker creates the folder as root
      net="$(staged_network)"
      if [ -n "$net" ] && ! docker network inspect "$net" >/dev/null 2>&1; then
        docker network create "$net" >/dev/null || die "Docker-Netz $net lässt sich nicht anlegen"
      fi
      if [ -n "${MYSURICATES_SYNC_TARGETS:-}" ]; then
        mkdir -p "$(sync_keys_dir)" && chmod 700 "$(sync_keys_dir)" || die "$(sync_keys_dir) lässt sich nicht anlegen"
      fi
      if staged_yaml > "$staged.tmp"; then mv -f "$staged.tmp" "$staged"; else rm -f "$staged.tmp" "$staged"; fi
      build_image || exit 1
      compose up -d --no-build || exit 1
      [ "$WAIT" = 1 ] || exit 0
      if wait_health container_healthy; then say_running läuft; exit 0; fi
      echo "$CONTAINER wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2; exit 1 ;;
    down)
      # Key only from the own container — down does not touch one of the same name from another setup
      if [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$CONTAINER" 2>/dev/null)" = "$CONTAINER" ]; then
        warn_running_runs "$(docker exec -u "$HOST_UID" "$CONTAINER" sh -c "$DATA_HOME_KEY_SH" sh "$HOST_HOME" 2>/dev/null)"
      fi
      compose down ;;
    status) docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "nicht gestartet" ;;
    logs)   compose logs --tail 200 ;;
    key)    docker exec -u "$HOST_UID" "$CONTAINER" sh -c "$DATA_HOME_KEY_SH" sh "$HOST_HOME" ;;
    login)
      # In login mode the login comes from the machine; a second one in the container would overwrite the
      # mounted auth.json (Codex, Pi) or bypass the token (Claude).
      [ "${MYSURICATES_AI_FROM_HOST:-login}" = programs ] \
        || die "$CONTAINER nutzt die Anmeldung dieses Rechners (MYSURICATES_AI_FROM_HOST=${MYSURICATES_AI_FROM_HOST:-login}). Eigene Anmeldung nur mit MYSURICATES_AI_FROM_HOST=programs in $ENV_FILE"
      provider_login_argv "${TOOL:-claude}" || die "login kennt claude, codex, pi"
      docker exec -it -u "$HOST_UID" "$CONTAINER" "${PROVIDER_LOGIN_ARGV[@]}" ;;
    built)  die "built gibt es nur für Host-Instanzen — im Container baut docker compose das Image bei jedem up" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list|login|built|sync-keys)" ;;
  esac
}
container_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" = healthy ]; }

# ---------------------------------------------------------------------------------------------------
# Host — one java process, built frontend, state per instance
# ---------------------------------------------------------------------------------------------------
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/$NAME"
PID_FILE="$STATE/instance.pid"
BUILT_FROM="$STATE/built-from"   # Commit of the clone at the last build (1c016162)

host_running() { [ -s "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

# Commit the clone is on — empty without git (then the existing build stays)
repo_head() { git -C "$REPO_ROOT" rev-parse --verify -q HEAD 2>/dev/null; }
short_sha() { if [ "${1:--}" = - ]; then printf 'unbekannt'; else printf '%s' "${1:0:8}"; fi; }

# State of the host build vs. the clone, one line (also for scripts/setup.sh):
#   none                 no build yet
#   current SHA          built from the commit the clone is on
#   outdated BUILT HEAD  built from another commit; BUILT is "-" if it is not recorded what from
#                        (build before this bookkeeping or aborted) — then rather build once too often
#   unknown BUILT        commit of the clone cannot be determined (no git) — no comparison, no rebuild
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
  head="$(repo_head)"   # before the build: a git pull during the minutes after does not count
  echo "Baue Backend (Maven) ..."
  # clean: otherwise the JAR of the previous version stays in target/ and the find below takes
  # any of them. When the demo VM jumped from 0.7.6 to 0.7.7 (2026-09-23) it thus copied
  # the old JAR back — the instance kept running on 0.7.6 unnoticed after the "upgrade".
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
  # Delete the marker first: if copying aborts, the next up rebuilds instead of attributing the old commit
  # to the half-copied state.
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
        # Never silently keep running on the old state (1c016162) — but do not stop it unasked either:
        # that ended running workflow runs. The new state is picked up with down and up.
        if [ "$st" = outdated ]; then
          echo "$CONTAINER läuft mit Stand $(short_sha "$built"), der Klon steht auf $(short_sha "$head") — nicht übernommen." >&2
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
      # Order: first the values from MYSURICATES_*, then the AUTOMATION_* lines of the file —
      # what is there wins.
      (
        cd "$STATE" || exit 1
        # Own user.home per instance: otherwise it would share the API key file and integration keys
        # (~/.mysuricates/) with every other instance of this user — e.g. a dev instance. Java
        # reads user.home from the account, not from $HOME. The AI CLIs keep inheriting the real $HOME;
        # where the backend itself looks for them, the properties point to them.
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
          MYSURICATES_BIND_SWITCH=MYSURICATES_BIND MYSURICATES_SWITCH_SOURCE="$ENV_FILE" \
          AUTOMATION_HARDENING_SEALING="${MYSURICATES_HARDENING_SEALING:-false}" \
          AUTOMATION_HARDENING_REQUIRESEALEDFORUNATTENDED="${MYSURICATES_HARDENING_REQUIRE_SEALED:-false}"
        for kv in ${AUTOMATION_ENV[@]+"${AUTOMATION_ENV[@]}"}; do export "$kv"; done
        nohup java -Duser.home="$STATE/home" -XX:+UseZGC -XX:+ZGenerational -Xms256m -Xmx1g -Djava.awt.headless=true \
          -XX:+ExitOnOutOfMemoryError -jar "$STATE/app.jar" > "$STATE/data/logs/stdout.log" 2>&1 < /dev/null &
        echo "$!" > "$PID_FILE"
      ) || exit 1
      [ "$WAIT" = 1 ] || exit 0
      if wait_health health_ok; then say_running läuft; exit 0; fi
      echo "$CONTAINER wurde nicht rechtzeitig gesund — scripts/instance.sh logs --name $NAME" >&2; exit 1 ;;
    down)
      if ! host_running; then echo "$CONTAINER läuft nicht"; rm -f "$PID_FILE"; exit 0; fi
      warn_running_runs "$(cat "$(data_home "$STATE/home")/api-key" 2>/dev/null)"
      pid="$(cat "$PID_FILE")"
      kill "$pid" 2>/dev/null
      i=0; while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
      rm -f "$PID_FILE"; echo "$CONTAINER gestoppt" ;;
    status)
      if host_running; then
        if health_ok; then echo "running healthy"; else echo "running starting"; fi
      else echo "nicht gestartet"; fi ;;
    logs)   tail -n 200 "$STATE/data/logs/mysuricates.log" 2>/dev/null || tail -n 200 "$STATE/data/logs/stdout.log" ;;
    key)    cat "$(data_home "$STATE/home")/api-key" ;;
    built)  host_build_state ;;
    login)  die "login gibt es nur fuer Container-Instanzen — im Host-Betrieb nutzen die Programme die Anmeldungen dieses Rechners direkt" ;;
    *)      die "Unbekannter Befehl: $CMD (up|down|status|logs|key|list|login|built|sync-keys)" ;;
  esac
}

case "$RUNTIME" in
  container) container_cmd ;;
  host)      host_cmd ;;
  *)         die "MYSURICATES_RUNTIME muss container oder host sein, nicht '$RUNTIME'" ;;
esac
