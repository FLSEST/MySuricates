#!/usr/bin/env bash
#
# tryout.sh — MySuricates ausprobieren, ohne es einzurichten: ein Container, der alles mitbringt.
#
#   scripts/tryout.sh build  [--ref REF]          # Image aus dem neuesten Release-Tag (oder REF) bauen
#   scripts/tryout.sh start  [--port N] [--image IMG]  # starten (Default-Port 9095), Adresse + Key zeigen
#   scripts/tryout.sh login  [claude|codex|pi]    # KI-Anmeldung im Container (Default claude)
#   scripts/tryout.sh key                          # API-Key fuer die Anmeldung in der Oberflaeche
#   scripts/tryout.sh shell                        # Shell im Container (als der Benutzer der Instanz)
#   scripts/tryout.sh status | logs | stop
#   scripts/tryout.sh remove [--data]              # Container weg; --data loescht auch Daten + Logins
#   scripts/tryout.sh export [DATEI]               # Image als Datei fuer Tester (docker load < DATEI)
# Commands: build|start|login|key|shell|status|logs|stop|remove|export
#
# Gebaut wird aus `git archive REF`, nie aus dem Arbeitsverzeichnis: das Image entspricht genau dem
# Tag, Uncommittetes und lokale Konfiguration kommen nicht hinein. Getaggt als mysuricates:<VERSION>-tryout
# und mysuricates:tryout (= zuletzt gebaut). Der Container bindet nichts vom Rechner ein — kein
# Projektordner, kein Docker-Socket, keine Logins; alles liegt auf den Volumes
# mysuricates-tryout-data/-secrets. Einrichten fuer die echte Arbeit: scripts/setup.sh.
# MYSURICATES_APT_REFRESH=<neuer Wert, etwa der Monat> beim build holt NodeSource- und Docker-Repo
# frisch statt aus dem Layer-Cache, und damit auch die KI-CLIs der Tryout-Stufe (8b7a39b9).
set -uo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/provider-login.sh
. "${SCRIPT_DIR}/lib/provider-login.sh"
# shellcheck source=lib/data-home.sh
. "${SCRIPT_DIR}/lib/data-home.sh"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CONTAINER="mysuricates-tryout"
DATA_VOLUME="mysuricates-tryout-data"
SECRETS_VOLUME="mysuricates-tryout-secrets"
IMAGE_REPO="mysuricates"
TRYOUT_HOME="/app/data/home"   # = HOST_HOME der Tryout-Stufe in deploy/Dockerfile
TRYOUT_UID=1000

die() { echo "tryout.sh: $*" >&2; exit 2; }
CMD="${1:-}"; [ "$#" -gt 0 ] && shift
case "$CMD" in -h|--help|"") sed -n '3,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

REF=""; PORT="${MYSURICATES_TRYOUT_PORT:-9095}"; IMAGE="$IMAGE_REPO:tryout"; PURGE=0; ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --ref) REF="${2:-}"; shift ;;
    --ref=*) REF="${1#--ref=}" ;;
    --port) PORT="${2:-}"; shift ;;
    --port=*) PORT="${1#--port=}" ;;
    --image) IMAGE="${2:-}"; shift ;;
    --image=*) IMAGE="${1#--image=}" ;;
    --data) PURGE=1 ;;
    -*) die "Unbekannte Option: $1" ;;
    *) ARGS[${#ARGS[@]}]="$1" ;;
  esac
  shift
done
case "$PORT" in ''|*[!0-9]*) die "--port braucht eine Zahl" ;; esac

# Neuestes Release = hoechstes vX.Y.Z-Tag (nach Version sortiert, nicht nach Datum)
newest_tag() { git -C "$REPO_ROOT" tag --list 'v[0-9]*' --sort=-v:refname | head -n 1; }

exists()  { docker container inspect "$CONTAINER" >/dev/null 2>&1; }
running() { [ "$(docker container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = true ]; }
healthy() { [ "$(docker container inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" = healthy ]; }
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }   # nimmt schon jemand Verbindungen an?
need_running() { running || die "$CONTAINER laeuft nicht — scripts/tryout.sh start"; }
as_user() { docker exec "$@"; }   # Aufrufe im Container immer als der Benutzer der Instanz, nie root
# Release-Images von vor der Umbenennung schreiben den Key nach ~/.autotrigger — DATA_HOME_KEY_SH liest beide
key() { as_user -u "$TRYOUT_UID" "$CONTAINER" sh -c "$DATA_HOME_KEY_SH" sh "$TRYOUT_HOME" 2>/dev/null; }

cmd_build() {
  [ -n "$REF" ] || REF="$(newest_tag)"
  [ -n "$REF" ] || die "Kein Release-Tag gefunden — --ref angeben (z. B. --ref HEAD)"
  local sha version
  sha="$(git -C "$REPO_ROOT" rev-parse --verify --quiet "$REF^{commit}")" || die "Unbekannte Referenz: $REF"
  # Das Dockerfile kommt aus REF selbst — aeltere Tags kennen die Tryout-Stufe noch nicht
  git -C "$REPO_ROOT" show "$sha:deploy/Dockerfile" 2>/dev/null | grep -q '^FROM .* AS tryout$' \
    || die "$REF kennt die Tryout-Stufe noch nicht (deploy/Dockerfile ohne 'AS tryout') — neueres Tag oder --ref HEAD"
  version="$(git -C "$REPO_ROOT" describe --tags --exact-match "$sha" 2>/dev/null || git -C "$REPO_ROOT" describe --tags --always "$sha")"
  version="${version#v}"
  echo "Baue $IMAGE_REPO:$version-tryout aus $REF (${sha:0:8}) …"
  git -C "$REPO_ROOT" archive --format=tar "$sha" \
    | docker build -f deploy/Dockerfile --target tryout \
        --label "org.opencontainers.image.title=MySuricates tryout" \
        --label "org.opencontainers.image.version=$version" \
        --label "org.opencontainers.image.revision=$sha" \
        --build-arg "APT_REFRESH=${MYSURICATES_APT_REFRESH:-}" \
        -t "$IMAGE_REPO:$version-tryout" -t "$IMAGE_REPO:tryout" - \
    || die "Build fehlgeschlagen"
  echo "Fertig: $IMAGE_REPO:$version-tryout (auch $IMAGE_REPO:tryout) — starten mit scripts/tryout.sh start"
}

cmd_start() {
  if exists; then
    local current
    current="$(docker container inspect -f '{{.Config.Image}}' "$CONTAINER")"
    [ "$current" = "$IMAGE" ] || echo "Hinweis: $CONTAINER laeuft mit $current — fuer ein neues Image erst 'remove' (Daten bleiben)."
    running || docker start "$CONTAINER" >/dev/null || die "Start fehlgeschlagen"
  else
    docker image inspect "$IMAGE" >/dev/null 2>&1 || die "Image $IMAGE fehlt — scripts/tryout.sh build (oder docker load < DATEI)"
    ! port_busy || die "Port $PORT ist belegt — anderen waehlen: scripts/tryout.sh start --port N"
    docker run -d --name "$CONTAINER" \
      -p "127.0.0.1:$PORT:9090" \
      -v "$DATA_VOLUME:/app/data" -v "$SECRETS_VOLUME:/app/secrets" \
      -e "AUTOMATION_CORS_ORIGINS=http://localhost:$PORT,http://127.0.0.1:$PORT" \
      -e "AUTOMATION_INSTANCE_URL=http://localhost:$PORT" -e "AUTOMATION_INSTANCE_CONTAINER=$CONTAINER" \
      -e "MYSURICATES_LOGIN_COMMAND=scripts/tryout.sh login [claude|codex|pi]" \
      "$IMAGE" >/dev/null || { docker rm -f "$CONTAINER" >/dev/null 2>&1; die "Start fehlgeschlagen"; }  # kein halb angelegter Container
  fi
  echo "Warte auf $CONTAINER …"
  local i=0
  until healthy; do
    [ "$i" -lt "${TRYOUT_HEALTH_TIMEOUT:-300}" ] || die "nicht rechtzeitig gesund — scripts/tryout.sh logs"
    sleep 2; i=$((i + 2))
  done
  local port
  port="$(docker port "$CONTAINER" 9090/tcp 2>/dev/null | head -n 1 | sed 's/.*://')"
  echo "MySuricates laeuft: http://localhost:${port:-$PORT}"
  echo "API-Key:  $(key || echo '(noch nicht geschrieben — scripts/tryout.sh key)')"
  echo "KI-Login: scripts/tryout.sh login   (ohne Login laufen nur Shell- und deterministische Bloecke)"
}

cmd_login() {
  need_running
  provider_login_argv "${ARGS[0]:-claude}" || die "login kennt claude, codex, pi"
  as_user -it -u "$TRYOUT_UID" -e "HOME=$TRYOUT_HOME" -w "$TRYOUT_HOME" "$CONTAINER" "${PROVIDER_LOGIN_ARGV[@]}"
}

cmd_export() {
  docker image inspect "$IMAGE" >/dev/null 2>&1 || die "Image $IMAGE fehlt — scripts/tryout.sh build"
  local version file
  version="$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.version"}}' "$IMAGE")"
  file="${ARGS[0]:-mysuricates-${version:-unknown}-tryout.tar.gz}"
  echo "Schreibe $file …"
  # Beide Namen mitgeben: nach `docker load` findet `start` ohne --image das Image unter :tryout
  local tags=("$IMAGE")
  [ -z "$version" ] || [ "$IMAGE" = "$IMAGE_REPO:$version-tryout" ] || tags[1]="$IMAGE_REPO:$version-tryout"
  docker save "${tags[@]}" | gzip > "$file" || die "Export fehlgeschlagen"
  echo "Fertig: $file — beim Empfaenger: docker load < $file, dann docker run (siehe docs/GETTING-STARTED.md → Ausprobieren)"
}

cmd_remove() {
  if exists; then docker rm -f "$CONTAINER" >/dev/null && echo "$CONTAINER entfernt."; fi
  if [ "$PURGE" = 1 ]; then
    docker volume rm "$DATA_VOLUME" "$SECRETS_VOLUME" >/dev/null 2>&1 || true
    echo "Daten, Logins und API-Key geloescht."
  else
    echo "Daten bleiben ($DATA_VOLUME) — ganz weg mit: scripts/tryout.sh remove --data"
  fi
}

command -v docker >/dev/null 2>&1 || die "Docker fehlt — das Tryout braucht nur Docker"
case "$CMD" in
  build)  cmd_build ;;
  start)  cmd_start ;;
  login)  cmd_login ;;
  key)    need_running; key || die "Noch kein API-Key — kurz warten, dann erneut" ;;
  shell)  need_running; as_user -it -u "$TRYOUT_UID" -e "HOME=$TRYOUT_HOME" -w "$TRYOUT_HOME" "$CONTAINER" bash ;;
  status) if exists; then docker container inspect -f '{{.State.Status}} {{.State.Health.Status}} {{.Config.Image}}' "$CONTAINER"; else echo "nicht gestartet"; fi ;;
  logs)   docker logs --tail 200 "$CONTAINER" ;;
  stop)   if exists; then docker stop "$CONTAINER" >/dev/null && echo "$CONTAINER gestoppt (Daten bleiben)."; else echo "nicht gestartet"; fi ;;
  remove) cmd_remove ;;
  export) cmd_export ;;
  *) die "Unbekannter Befehl: $CMD (build|start|login|key|shell|status|logs|stop|remove|export)" ;;
esac
