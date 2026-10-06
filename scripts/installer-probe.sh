#!/usr/bin/env bash
#
# installer-probe.sh — checks scripts/setup.sh the way a new user experiences it: on a fresh
# Linux machine (container; apt or dnf image, default ubuntu:24.04) that has only git, sudo and
# tzdata.
#
#   scripts/installer-probe.sh                   # check HEAD of this checkout, write report
#   scripts/installer-probe.sh --ref main        # check another state (branch, tag or SHA)
#   scripts/installer-probe.sh --interactive     # the same fresh machine as a shell — click through yourself;
#                                                # shares the machine's network: the address setup.sh names at the
#                                                # end works directly in the browser
#   scripts/installer-probe.sh --keep            # leave the container standing after the run, look at the instance
#   scripts/installer-probe.sh --way container   # default way: the instance in a container (the machine's Docker)
#   scripts/installer-probe.sh --way staged      # Dev · Test · Prod, three containers
#   scripts/installer-probe.sh --cleanup <name>  # clear away leftovers of a run with --keep (name from the output)
#
#   More options: --repo <path>   repo the state comes from (default: this one)
#                     --image <image> apt or dnf image (default ubuntu:24.04; e.g. rockylinux:9)
#                     --out <dir>     report directory (default: ~/.local/state/mysuricates/installer-probe/<time>-<sha>)
#                     --port <n>      port on 127.0.0.1 (default: automatic; automatic run only;
#                                     with --way staged the first of three)
#                     --setup-flags "<flags>"  additional flags for phase 2, e.g. "--with-pi" (c3a4cbf6)
#                     --no-screenshots
#
# Automatic run in two phases: 1-check `setup.sh --check`, 2-set up `setup.sh --yes …` —
# one pass up to the running instance. Each phase runs under a pseudo-terminal, so the recording
# shows colours and header as on the screen. The ways (--way):
#   host       "eine Instanz auf diesem Rechner" (default): `--runtime host --bind 0.0.0.0`
#   container  "eine Instanz im Container", the default way: `setup.sh --yes --port <p>`
#   staged     "Dev · Test · Prod": `setup.sh --yes --mode staged --port <p>`
# Hard checks: phase 1 names the missing tools, phase 2 ends with exit 0, health UP (on a
# machine without Docker no error), UI 200 on the same port, login with the generated key works
# (if Playwright is present), the key appears in no log and no recording, the clone is unchanged
# afterwards. On the machine additionally: the backend is built exactly once. In the container: each
# instance published only on 127.0.0.1 and without a Docker socket (the defaults). Staged: three
# healthy instances on different ports, Prod with seal requirement. Result: <out>/report.html —
# one file, screenshots embedded — and at the end gate_status: GREEN|RED for the MySuricates workflow.
#
# Container ways: the probe machine gets the machine's Docker socket (group docker with its
# group ID, setup.sh installs Docker itself) and the machine's network — ports that are taken
# there, setup.sh sees for itself, and the instances stay on 127.0.0.1 as for any user. The
# clone lies in a folder of the machine under the same path (the machine's Docker service resolves
# the bind mounts of the inner compose). All Docker names carry the run's prefix
# (mysuricates-installer-probe-<time>-…, via MYSURICATES_PREFIX to instance.sh); at the end exactly that
# is cleaned up — also after an abort, each name checked against the prefix beforehand. Running
# instances of the machine stay untouched; the automatic ports avoid 8090, 9090, 9091 and 9095.
#
# What is checked is always the committed state; uncommitted changes are not included (as with a
# real clone). Ports only on 127.0.0.1. Never accesses the DB or REST API of the dev instance.
# Exit: 0 green, 1 red, 2 usage/environment error.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Never query the probe instance through a proxy from the environment (bb4f60a5)
# shellcheck source=lib/local-curl.sh
. "${SCRIPT_DIR}/lib/local-curl.sh"
REPO="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
REF="HEAD"
IMAGE="ubuntu:24.04"
OUT=""
KEEP=0
INTERACTIVE=0
SCREENSHOTS=1
FRONTEND_PORT=""
SETUP_FLAGS=""
WAY="host"
CLEANUP=""

die() { echo "installer-probe: $*" >&2; exit 2; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ref)            REF="${2:-}"; shift ;;
    --ref=*)          REF="${1#--ref=}" ;;
    --repo)           REPO="${2:-}"; shift ;;
    --repo=*)         REPO="${1#--repo=}" ;;
    --image)          IMAGE="${2:-}"; shift ;;
    --image=*)        IMAGE="${1#--image=}" ;;
    --out)            OUT="${2:-}"; shift ;;
    --out=*)          OUT="${1#--out=}" ;;
    --port)           FRONTEND_PORT="${2:-}"; shift ;;
    --port=*)         FRONTEND_PORT="${1#--port=}" ;;
    --setup-flags)    SETUP_FLAGS="${2:-}"; shift ;;
    --setup-flags=*)  SETUP_FLAGS="${1#--setup-flags=}" ;;
    --way)            WAY="${2:-}"; shift ;;
    --way=*)          WAY="${1#--way=}" ;;
    --cleanup)        CLEANUP="${2:-}"; shift ;;
    --cleanup=*)      CLEANUP="${1#--cleanup=}" ;;
    --keep)           KEEP=1 ;;
    --interactive)    INTERACTIVE=1 ;;
    --no-screenshots) SCREENSHOTS=0 ;;
    -h|--help)        sed -n '3,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *)                die "Unbekannte Option: $1 (siehe --help)" ;;
  esac
  shift
done

# Everything a container way creates in the machine's Docker is named <prefix>-… or <prefix>:local.
# Only names of exactly this form does the probe clear away — never anything else.
PREFIX_RE='^mysuricates-installer-probe-[0-9]{8}-[0-9]{6}$'
probe_owned() {  # NAME — does NAME belong to this run?
  [[ "$PREFIX" =~ $PREFIX_RE ]] || return 1
  case "$1" in "$PREFIX"-?*|"$PREFIX:local") return 0 ;; esac
  return 1
}
# The instances (containers, volumes, compose network, image) — not the probe machine itself
remove_probe_docker() {
  local n removed=""
  for n in $(docker ps -a --format '{{.Names}}' 2>/dev/null); do
    probe_owned "$n" && docker rm -f "$n" >/dev/null 2>&1 && removed="$removed $n"
  done
  for n in $(docker volume ls -q 2>/dev/null); do
    probe_owned "$n" && docker volume rm "$n" >/dev/null 2>&1 && removed="$removed $n"
  done
  for n in $(docker network ls --format '{{.Name}}' 2>/dev/null); do
    probe_owned "$n" && docker network rm "$n" >/dev/null 2>&1 && removed="$removed $n"
  done
  probe_owned "$PREFIX:local" && docker image rm "$PREFIX:local" >/dev/null 2>&1 && removed="$removed $PREFIX:local"
  [ -z "$removed" ] || echo "Aufgeräumt:$removed" >&2
  return 0
}

if [ -n "$CLEANUP" ]; then
  PREFIX="$CLEANUP"
  [[ "$PREFIX" =~ $PREFIX_RE ]] || die "--cleanup nimmt nur den Namen eines Probe-Laufs (mysuricates-installer-probe-<datum>-<zeit>)"
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || die "Docker-Daemon nicht erreichbar (läuft er? Rechte?)"
  docker rm -f "$PREFIX" >/dev/null 2>&1
  remove_probe_docker
  exit 0
fi

[ -n "$REF" ] || die "--ref braucht einen Wert"
case "$WAY" in host|container|staged) ;; *) die "--way host|container|staged, nicht '$WAY'" ;; esac
[ -n "$IMAGE" ] || die "--image braucht einen Wert"
if [ -n "$FRONTEND_PORT" ] && ! [[ "$FRONTEND_PORT" =~ ^[0-9]+$ && "$FRONTEND_PORT" -ge 1024 && "$FRONTEND_PORT" -le 65000 ]]; then
  die "--port muss eine Zahl zwischen 1024 und 65000 sein"
fi
if [ "$INTERACTIVE" = 1 ] && [ "$WAY" != host ]; then
  die "--interactive gibt es nur für --way host — die Container-Wege bekämen den Docker des Rechners in die Hand"
fi
if [ "$INTERACTIVE" = 1 ] && [ -n "$FRONTEND_PORT" ]; then
  die "--port gilt nur für den automatischen Lauf — interaktiv nennt setup.sh den Port selbst"
fi
# The flags end up in bash -c '…' in the container: only flag characters, no quote, no shell syntax
case "$SETUP_FLAGS" in *[!A-Za-z0-9\ =,._/:-]*) die "--setup-flags: nur Flags wie --with-pi oder --preset research, ohne Anführungszeichen und Shell-Zeichen" ;; esac
if [ "$INTERACTIVE" = 1 ] && [ -n "$SETUP_FLAGS" ]; then
  die "--setup-flags gilt nur für den automatischen Lauf — interaktiv tippt man sie selbst"
fi
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || die "Kein Git-Repo: $REPO"
SHA="$(git -C "$REPO" rev-parse --verify --quiet "${REF}^{commit}")" || die "Unbekannter Stand: $REF"
SHORT="${SHA:0:8}"
if git -C "$REPO" show-ref --verify --quiet "refs/heads/${REF}"; then
  BRANCH="$REF"
elif [ "$REF" = "HEAD" ]; then
  BRANCH="$(git -C "$REPO" symbolic-ref --short -q HEAD || echo probe)"
else
  BRANCH="probe"
fi
if [ "$INTERACTIVE" = 1 ] && ! [[ -t 0 && -t 1 ]]; then
  die "--interactive braucht ein Terminal (im eigenen Terminal starten, nicht über eine Pipe)"
fi
command -v docker >/dev/null 2>&1 || die "docker fehlt — die Probe braucht einen Docker-Daemon"
docker info >/dev/null 2>&1 || die "Docker-Daemon nicht erreichbar (läuft er? Rechte?)"

# Container ways: socket and user ID of the machine, free ports
if [ "$WAY" != host ]; then
  case "${DOCKER_HOST:-}" in ""|unix://*) ;; *) die "--way $WAY braucht einen lokalen Docker-Socket (DOCKER_HOST=${DOCKER_HOST})" ;; esac
  SOCK="${DOCKER_HOST:-unix:///var/run/docker.sock}"; SOCK="${SOCK#unix://}"
  [ -S "$SOCK" ] || die "Docker-Socket $SOCK nicht gefunden"
  SOCK_GID="$(stat -c %g "$SOCK" 2>/dev/null || stat -f %g "$SOCK" 2>/dev/null)"
  HOST_UID="$(id -u)"
  [ "$HOST_UID" != 0 ] || die "--way $WAY nicht als root starten: der Klon liegt in einem Ordner des Rechners und gehört dem Probe-Benutzer mit deiner Benutzer-ID"
  [ -n "$SOCK_GID" ] && [ "$SOCK_GID" != 0 ] || die "Der Docker-Socket $SOCK gehört keiner eigenen Gruppe (GID ${SOCK_GID:-?}) — die Probe braucht eine Gruppe wie docker"
  # Taken or fixed (Dev 9090, Test 8090, Stable 9091, Tryout 9095 — even if they are not currently
  # running): the probe never puts an instance there
  RESERVED=" 8090 9090 9091 9095 "
  port_ok() { case "$RESERVED" in *" $1 "*) return 1 ;; esac; ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
  N_PORTS=1; [ "$WAY" = staged ] && N_PORTS=3
  ports_ok() { local i=0; while [ "$i" -lt "$N_PORTS" ]; do port_ok $(($1 + i)) || return 1; i=$((i + 1)); done; }
  if [ -n "$FRONTEND_PORT" ]; then
    ports_ok "$FRONTEND_PORT" || die "Port ${FRONTEND_PORT} (bzw. die ${N_PORTS} ab dort) belegt oder für Dev/Test/Stable/Tryout vergeben"
    BASE_PORT="$FRONTEND_PORT"
  else
    BASE_PORT=19190
    while ! ports_ok "$BASE_PORT"; do
      BASE_PORT=$((BASE_PORT + 10)); [ "$BASE_PORT" -lt 19990 ] || die "Kein freier Port zwischen 19190 und 19990"
    done
  fi
fi

if [ "$REF" = "HEAD" ] && [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
  echo "Hinweis: uncommittete Änderungen in $REPO werden nicht geprüft — nur Commit $SHORT." >&2
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="mysuricates-installer-probe-${STAMP}"
PREFIX="$NAME"   # Docker names of the instances of a container way: <PREFIX>-main, <PREFIX>-dev …
WORK="$(mktemp -d)"
USER_HOME="/home/probe"
# Container ways: the clone in a folder of the machine, in the container under the same path
if [ "$WAY" = host ]; then CLONE_PARENT="$USER_HOME"; else CLONE_PARENT="$WORK/machine"; mkdir -p "$CLONE_PARENT"; fi
CLONE="${CLONE_PARENT}/mysuricates"
EXEC_ENV=()
[ "$WAY" = host ] || EXEC_ENV=(-e "MYSURICATES_PREFIX=$PREFIX")
case "$WAY" in staged) INSTS="dev test prod" ;; *) INSTS="main" ;; esac
PHASE_TIMEOUT="${INSTALLER_PROBE_PHASE_TIMEOUT:-$([ "$WAY" = staged ] && echo 3600 || echo 1800)}"

cleanup() {
  if [ "$KEEP" = 1 ]; then
    [ "$WAY" = host ] && rm -rf "$WORK"   # in the container way the clone of the standing instance lies there
    return 0
  fi
  # The machine first: that ends setup.sh and the docker calls in it, after that nobody creates anything any more
  docker rm -f "$NAME" >/dev/null 2>&1
  [ "$WAY" = host ] || remove_probe_docker
  rm -rf "$WORK" 2>/dev/null || echo "installer-probe: $WORK ließ sich nicht ganz löschen" >&2
}
PHASE_PID=""
trap cleanup EXIT
# A phase runs in the background, the probe waits for it with wait: only that way does a TERM take effect immediately
# (a foreground process bash waited for until its end, up to PHASE_TIMEOUT) and the
# cleanup comes before a half set-up container stays standing
trap '[ -z "$PHASE_PID" ] || kill "$PHASE_PID" 2>/dev/null; exit 130' INT TERM

# --- Fresh machine ------------------------------------------------------------------------------
# A bundle carries the state into the container, regardless of whether $REPO is a worktree, a clone with
# alternates or a normal repo. A bare SHA cannot be bundled (no ref) → --all.
if ! git -C "$REPO" bundle create -q "$WORK/repo.bundle" "$REF" 2>/dev/null; then
  git -C "$REPO" bundle create -q "$WORK/repo.bundle" --all 2>/dev/null || die "git bundle fehlgeschlagen"
fi

# One port: the instance serves the UI itself (setup.sh, way "auf dem Rechner").
# Automatic: container port 9090 to a free port of the machine. Interactive: share the network of the
# machine — the port setup.sh chooses (or one types in) is then directly
# localhost:<port>, taken ports of the machine setup.sh sees itself. A fixed published port
# sent every other choice into the void (6abc7fca). The instance stays on 127.0.0.1.
#
# Container ways: the machine's network (rationale at the top of the header) and the Docker socket. Whoever has the socket
# controls the machine's Docker — the machine is a throwaway container with the committed
# state; only what carries this run's prefix is cleaned up.
if [ "$WAY" != host ]; then PORT_ARGS=(--network host -v "$SOCK:/var/run/docker.sock" -v "$CLONE_PARENT:$CLONE_PARENT")
elif [ "$INTERACTIVE" = 1 ]; then PORT_ARGS=(--network host)
elif [ -n "$FRONTEND_PORT" ]; then PORT_ARGS=(-p "127.0.0.1:${FRONTEND_PORT}:9090")
else PORT_ARGS=(-p "127.0.0.1::9090"); fi

case "$WAY" in
  host)      WAY_LABEL="eine Instanz auf dem Rechner" ;;
  container) WAY_LABEL="eine Instanz im Container" ;;
  staged)    WAY_LABEL="Dev · Test · Prod im Container" ;;
esac
echo "Installer-Probe: ${REF} (${SHORT}) auf ${IMAGE}, Weg: ${WAY_LABEL}"
docker run -d --name "$NAME" "${PORT_ARGS[@]}" "$IMAGE" sleep infinity >/dev/null \
  || die "Container konnte nicht starten (Image ${IMAGE}? Port belegt?)"

# What a human has at least after `git clone` — and nothing else. tzdata belongs on every real
# machine (otherwise apt asks for the time zone at the JDK); the animated apt progress bar is
# switched off because in the recording it leaves only control-character salad.
PREP='if command -v apt-get >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq git sudo tzdata >/dev/null 2>&1 || exit 1
  echo "Dpkg::Progress-Fancy \"0\";" > /etc/apt/apt.conf.d/99probe-no-fancy-progress
elif command -v dnf >/dev/null; then
  dnf install -y -q git sudo tzdata util-linux >/dev/null 2>&1 || exit 1
else
  echo "nur apt- oder dnf-Images werden unterstützt" >&2; exit 3
fi
if [ -n "${PROBE_UID:-}" ]; then
  # Container way: the user with the ID of the machine (the clone lies in its folder), the group
  # docker with the ID of the socket — as on a machine whose Docker service creates it. Docker
  # itself is installed by setup.sh; it adds the user to the group in the process.
  u="$(getent passwd "$PROBE_UID" | cut -d: -f1)"
  if [ -n "$u" ] && [ "$u" != probe ]; then userdel -r "$u" >/dev/null 2>&1 || exit 1; fi
  g="$(getent group "$PROBE_SOCK_GID" | cut -d: -f1)"
  if [ -z "$g" ]; then groupadd -g "$PROBE_SOCK_GID" docker || exit 1
  elif [ "$g" != docker ]; then echo "Gruppen-ID $PROBE_SOCK_GID des Docker-Sockets ist im Image schon $g" >&2; exit 1; fi
  id -u probe >/dev/null 2>&1 || useradd -m -s /bin/bash -u "$PROBE_UID" probe || exit 1
fi
id -u probe >/dev/null 2>&1 || useradd -m -s /bin/bash probe
echo "probe ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/probe && chmod 440 /etc/sudoers.d/probe'
PREP_ENV=()
[ "$WAY" = host ] || PREP_ENV=(-e "PROBE_UID=$HOST_UID" -e "PROBE_SOCK_GID=$SOCK_GID")
docker exec ${PREP_ENV[@]+"${PREP_ENV[@]}"} "$NAME" bash -c "$PREP" || die "Grundausstattung im Container fehlgeschlagen"
docker cp -q "$WORK/repo.bundle" "$NAME:/tmp/repo.bundle" >/dev/null && docker exec "$NAME" chmod 644 /tmp/repo.bundle \
  || die "Bundle ließ sich nicht in den Container kopieren"
docker exec -u probe -w "$CLONE_PARENT" -e PROBE_SHA="$SHA" -e PROBE_BRANCH="$BRANCH" "$NAME" bash -c \
  'git clone -q --no-checkout /tmp/repo.bundle mysuricates 2>/dev/null && cd mysuricates && git checkout -q -B "$PROBE_BRANCH" "$PROBE_SHA"' \
  || die "Klon im Container fehlgeschlagen"

host_port() { docker port "$NAME" "$1/tcp" 2>/dev/null | head -n1 | sed 's/.*://'; }
BPORT="$(host_port 9090)"
FPORT="$BPORT"

# --- Interactive: shell in the fresh machine ----------------------------------------------------
if [ "$INTERACTIVE" = 1 ]; then
  # The machine has only C.UTF-8; setup.sh then chooses the language of the module texts via --lang.
  L=""; case "${LANG:-}" in de*) L=" --lang de" ;; esac
  HINTS="
  Frische Maschine: ${IMAGE}, Stand ${REF} (${SHORT}), Benutzer probe mit sudo.
  So geht ein neuer Nutzer vor:

    ./scripts/setup.sh${L}

  Im Modus-Schritt „auf diesem Rechner“ wählen — in diesem Wegwerf-Container gibt es kein Docker,
  „im Container“ und „Dev · Test · Prod“ scheitern hier (automatisch geprüft mit --way container
  bzw. --way staged).
  Nur ansehen, nichts installieren:  ./scripts/setup.sh --dry-run${L}

  Die Maschine teilt das Netz mit deinem Rechner: die Adresse, die der Installer am Ende nennt
  (http://localhost:<port>), öffnest du direkt im Browser. Belegte Ports umgeht er selbst.
  API-Key für den Login:  scripts/instance.sh key --name main
  exit beendet die Probe und räumt den Container weg.
"
  # Own prompt: with the machine's network the machine would be named like the machine itself
  docker exec -i -u probe "$NAME" bash -c 'cat > ~/.probe-hints && printf "%s\n" "cat ~/.probe-hints" "PS1='"'"'probe@frische-maschine:\\w\\$ '"'"'" >> ~/.bashrc' <<<"$HINTS"
  docker exec -it -u probe -w "$CLONE" -e TERM="${TERM:-xterm-256color}" -e LANG=C.UTF-8 "$NAME" bash
  exit 0
fi

# --- Automatic run ----------------------------------------------------------------------------
OUT="${OUT:-${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/installer-probe/${STAMP}-${SHORT}}"
mkdir -p "$OUT/logs" "$OUT/screens" || die "Report-Verzeichnis nicht anlegbar: $OUT"
: > "$OUT/phases.tsv"
: > "$OUT/checks.tsv"
STARTED="$(date '+%Y-%m-%d %H:%M')"
T_START=$SECONDS

check() {  # label, ok|fail|skip, detail
  printf '%s\t%s\t%s\n' "$1" "$2" "${3//$'\t'/ }" >> "$OUT/checks.tsv"
  printf '  %-4s %s%s\n' "$2" "$1" "${3:+ — $3}"
}

# A phase under a pseudo-terminal (script), 120 columns, input via pipe; -echo so that the
# previously buffered answer does not appear as a line at the start of the recording.
run_phase() {  # name, command, input
  local name="$1" cmd="$2" input="$3" t0=$SECONDS rc
  (
    printf '%b' "$input" | timeout "$PHASE_TIMEOUT" docker exec -i -u probe -w "$CLONE" \
      -e TERM=xterm-256color -e LANG=C.UTF-8 ${EXEC_ENV[@]+"${EXEC_ENV[@]}"} "$NAME" \
      script -qefc "stty cols 120 rows 40 -echo 2>/dev/null; rm -f /tmp/phase.rc; bash -c '$cmd'; echo \$? > /tmp/phase.rc" /dev/null \
      > "$OUT/logs/${name}.log" 2>&1
    exit "${PIPESTATUS[1]}"
  ) &
  PHASE_PID=$!
  wait "$PHASE_PID"; rc=$?
  PHASE_PID=""
  # The phase's own exit code — through script/docker exec an abort already passed as 0
  local inner
  inner="$(docker exec "$NAME" cat /tmp/phase.rc 2>/dev/null)" && [ -n "$inner" ] && rc="$inner"
  printf '%s\t%s\t%s\t%s\n' "$name" "$cmd" "$rc" "$((SECONDS - t0))" >> "$OUT/phases.tsv"
  printf '  Phase %-10s exit=%s  %ss\n' "$name" "$rc" "$((SECONDS - t0))"
  return "$rc"
}

case "$WAY" in
  host)
    CMD1="./scripts/setup.sh --check --runtime host --lang de"
    CMD2="./scripts/setup.sh --yes --runtime host --bind 0.0.0.0 --lang de" ;;
  container)
    # A user's defaults (in the container, 127.0.0.1, no socket); only the port is set —
    # 9090 and the following often already belong to real instances on this machine
    # --runtime container also in the check run: without a way setup.sh asks for it in the terminal
    CMD1="./scripts/setup.sh --check --runtime container --lang de"
    CMD2="./scripts/setup.sh --yes --port ${BASE_PORT} --lang de" ;;
  staged)
    CMD1="./scripts/setup.sh --check --mode staged --lang de"
    CMD2="./scripts/setup.sh --yes --mode staged --port ${BASE_PORT} --lang de" ;;
esac
run_phase 1-pruefen    "$CMD1" ""; RC1=$?
run_phase 2-einrichten "${CMD2}${SETUP_FLAGS:+ $SETUP_FLAGS}" ""; RC2=$?

echo "Prüfungen:"
P1="$(sed 's/\x1b\[[0-9;]*m//g' "$OUT/logs/1-pruefen.log" | tr -d '\r')"
if [ "$RC1" = 1 ] && grep "Fehlt:" <<<"$P1" >/dev/null; then
  check "Phase 1 nennt die fehlenden Werkzeuge" ok "$(grep -o 'Fehlt: [a-z0-9 ]*' <<<"$P1" | head -n1)"
elif [ "$RC1" = 0 ]; then
  check "Phase 1 nennt die fehlenden Werkzeuge" skip "Image bringt schon alles mit"
else
  check "Phase 1 nennt die fehlenden Werkzeuge" fail "Exit ${RC1}"
fi
if [ "$RC2" = 0 ]; then check "Phase 2: ein Durchgang bis zur laufenden Instanz (Exit 0)" ok ""
else check "Phase 2: ein Durchgang bis zur laufenden Instanz (Exit 0)" fail "Exit ${RC2}"; fi

SETUP_LOGS="${USER_HOME}/.local/state/mysuricates/setup-logs"
# A command in the probe machine as the user, in the clone — with the container ways' prefix
in_machine() { docker exec -u probe -w "$CLONE" ${EXEC_ENV[@]+"${EXEC_ENV[@]}"} "$NAME" "$@"; }
instance_key() { in_machine bash scripts/instance.sh key --name "$1" 2>/dev/null | tr -d '[:space:]'; }
# Port of an instance from its file — the clone of a container way is also on the machine
inst_port() { grep '^MYSURICATES_PORT=' "$CLONE/deploy/instances/$1.env" 2>/dev/null | tail -n 1 | cut -d= -f2; }

if [ "$WAY" = host ]; then
  HEALTH="$(local_curl -s --max-time 10 "http://127.0.0.1:${BPORT}/api/v1/system/health" 2>/dev/null)"
  if grep '"status":"UP"' <<<"$HEALTH" >/dev/null; then check "Health UP — ohne Docker kein Fehler" ok "$(grep -o '"docker":"[A-Z_]*"' <<<"$HEALTH")"
  else check "Health UP — ohne Docker kein Fehler" fail "${HEALTH:-keine Antwort}"; fi
  HTTP="$(local_curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:${FPORT}/" 2>/dev/null)"
  if [ "$HTTP" = 200 ]; then check "Oberfläche auf demselben Port (HTTP 200)" ok ""; else check "Oberfläche auf demselben Port (HTTP 200)" fail "HTTP ${HTTP:-—}"; fi

  BUILDS="$(docker exec -u probe "$NAME" sh -c "cat $SETUP_LOGS/*.log 2>/dev/null" | grep -c 'Baue Backend')"
  if [ "$BUILDS" = 1 ]; then check "Backend genau einmal gebaut" ok ""; else check "Backend genau einmal gebaut" fail "${BUILDS}× gebaut"; fi
  LOGIN_HTTP="$HTTP"
else
  # Per instance: health, UI, and that it runs with the defaults — only published on 127.0.0.1,
  # without Docker socket (since the default is that way, no switch of the probe)
  FPORT=""; LOGIN_HTTP=""
  for n in $INSTS; do
    p="$(inst_port "$n")"; c="$PREFIX-$n"; sfx=""; [ "$WAY" = staged ] && sfx=" ($n)"
    [ -n "$FPORT" ] || FPORT="$p"
    HEALTH="$(local_curl -s --max-time 10 "http://127.0.0.1:${p:-0}/api/v1/system/health" 2>/dev/null)"
    if grep '"status":"UP"' <<<"$HEALTH" >/dev/null; then check "Health UP${sfx}" ok "Port ${p}"
    else check "Health UP${sfx}" fail "${HEALTH:-keine Antwort auf Port ${p:-?}}"; fi
    HTTP="$(local_curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:${p:-0}/" 2>/dev/null)"
    [ -n "$LOGIN_HTTP" ] || LOGIN_HTTP="$HTTP"
    if [ "$HTTP" = 200 ]; then check "Oberfläche auf demselben Port${sfx} (HTTP 200)" ok ""
    else check "Oberfläche auf demselben Port${sfx} (HTTP 200)" fail "HTTP ${HTTP:-—}"; fi
    PUB="$(docker port "$c" 9090/tcp 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')"
    SOCKETS="$(docker inspect -f '{{range .Mounts}}{{.Destination}}{{"\n"}}{{end}}' "$c" 2>/dev/null | grep -c 'docker\.sock$')"
    if [ -n "$p" ] && [ "$PUB" = "127.0.0.1:$p" ] && [ "$SOCKETS" = 0 ]; then
      check "Nur auf 127.0.0.1, ohne Docker-Socket${sfx}" ok "$c → $PUB"
    else
      check "Nur auf 127.0.0.1, ohne Docker-Socket${sfx}" fail "$c: veröffentlicht ${PUB:-nichts}, Socket-Mounts ${SOCKETS:-?}"
    fi
  done
fi

if [ "$WAY" = staged ]; then
  DETAIL=""; BAD=""; PORTS=""
  for n in dev test prod; do
    p="$(inst_port "$n")"; st="$(docker inspect -f '{{.State.Health.Status}}' "$PREFIX-$n" 2>/dev/null)"
    DETAIL="${DETAIL}${DETAIL:+ · }$n :${p:-?} ${st:-fehlt}"
    { [ "$st" = healthy ] && [ -n "$p" ]; } || BAD=1
    PORTS="$PORTS $p"
  done
  if [ -z "$BAD" ] && [ "$(printf '%s\n' $PORTS | sort -u | grep -c .)" = 3 ]; then
    check "Drei Instanzen gesund, jede auf eigenem Port" ok "$DETAIL"
  else
    check "Drei Instanzen gesund, jede auf eigenem Port" fail "$DETAIL"
  fi
  # Prod from its own view: environment Prod, seal and seal requirement for unattended runs on
  CAPS="$(local_curl_key "$(instance_key prod)" -s --max-time 10 "http://127.0.0.1:$(inst_port prod)/api/v1/system/capabilities" 2>/dev/null)"
  SEAL="$(python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except ValueError:
    print("keine Antwort"); sys.exit(1)
m = {x.get("name"): x.get("enabled") for x in d.get("modules") or []}
env, s, r = d.get("environment"), m.get("sealing"), m.get("require-sealed-for-unattended")
print(f"environment={env} sealing={str(s).lower()} require-sealed-for-unattended={str(r).lower()}")
sys.exit(0 if env == "Prod" and s is True and r is True else 1)' <<<"$CAPS" 2>/dev/null)"
  if [ "$?" = 0 ]; then check "Prod mit Siegelpflicht" ok "$SEAL"; else check "Prod mit Siegelpflicht" fail "${SEAL:-keine Antwort}"; fi
fi

DIRTY="$(docker exec -u probe -w "$CLONE" "$NAME" git status --porcelain 2>&1)"
if [ -z "$DIRTY" ]; then
  check "Klon nach dem Setup unverändert (git status leer)" ok ""
else
  check "Klon nach dem Setup unverändert (git status leer)" fail "$(tr '\n' ' ' <<<"$DIRTY")"
fi

# Everything a user or support gets to see: recordings, setup logs, in the container in addition
# the output and the log files of each instance
all_logs() {
  local n
  cat "$OUT"/logs/*.log
  docker exec -u probe "$NAME" sh -c "cat $SETUP_LOGS/*.log ${USER_HOME}/.local/state/mysuricates/main/data/logs/*.log 2>/dev/null"
  [ "$WAY" = host ] && return 0
  for n in $INSTS; do
    docker logs "$PREFIX-$n" 2>&1
    docker exec "$PREFIX-$n" sh -c 'cat /app/data/logs/*.log 2>/dev/null'
  done
  return 0
}
KEYS=(); NOKEY=""
for n in $INSTS; do
  k="$(instance_key "$n")"
  if [ -n "$k" ]; then KEYS[${#KEYS[@]}]="$k"; else NOKEY="$NOKEY $n"; fi
done
KEY="${KEYS[0]:-}"
if [ -n "$NOKEY" ]; then
  check "API-Key steht in keinem Log und keinem Mitschnitt" fail "kein Key gefunden:$NOKEY"
else
  LOGS="$(all_logs)"; LEAK=0
  for k in "${KEYS[@]}"; do LEAK=$((LEAK + $(grep -cF "$k" <<<"$LOGS"))); done
  unset LOGS
  if [ "$LEAK" = 0 ]; then check "API-Key steht in keinem Log und keinem Mitschnitt" ok ""
  else check "API-Key steht in keinem Log und keinem Mitschnitt" fail "${LEAK} Fundstelle(n)"; fi
fi
unset KEYS

PLAYWRIGHT=""
for dir in "$REPO/ai-automation-frontend" "$SCRIPT_DIR/../ai-automation-frontend"; do
  if [ -f "$dir/node_modules/playwright/index.mjs" ]; then PLAYWRIGHT="$(cd "$dir" && pwd)/node_modules/playwright/index.mjs"; break; fi
done
LOGIN_LABEL="Login im Browser mit dem erzeugten API-Key"
[ "$WAY" = staged ] && LOGIN_LABEL="$LOGIN_LABEL (dev)"
if [ "$SCREENSHOTS" = 0 ]; then
  check "$LOGIN_LABEL" skip "--no-screenshots"
elif [ -z "$PLAYWRIGHT" ] || ! command -v node >/dev/null 2>&1; then
  check "$LOGIN_LABEL" skip "kein Playwright unter ai-automation-frontend/node_modules"
elif [ "$LOGIN_HTTP" != 200 ]; then
  check "$LOGIN_LABEL" skip "Frontend nicht erreichbar"
else
  SHOTS="$(PLAYWRIGHT_MODULE="$PLAYWRIGHT" PROBE_API_KEY="$KEY" \
    timeout 180 node "$SCRIPT_DIR/lib/installer_probe_screens.mjs" "http://127.0.0.1:${FPORT}/" "$OUT/screens" 2>&1)"
  SHOTS_RC=$?
  if [ "$SHOTS_RC" = 3 ]; then
    # Browser for the installed Playwright version is missing (after an update, 65164c57). Red instead of
    # skip, because the login is then unchecked and GREEN would claim "bis Login durch" — but with
    # the remedy instead of a stack trace. Reloading is the maintainer's decision, the probe loads nothing.
    check "$LOGIN_LABEL" fail "Browser fehlt — im Frontend \`npm run pw:install\` ausführen ($(grep -o '^browser missing: [^ ]*' <<<"$SHOTS" | head -n1 | sed 's/^browser missing: //'))"
  elif [ "$SHOTS_RC" = 0 ]; then
    check "$LOGIN_LABEL" ok "$(grep -o 'console-errors [0-9]*' <<<"$SHOTS" | sed 's/console-errors /Konsolenfehler: /')$(grep '^console-error ' <<<"$SHOTS" | sed 's/^console-error / · /' | tr -d '\n')"
  else
    check "$LOGIN_LABEL" fail "$(grep -E 'failed' <<<"$SHOTS" | head -n1)"
  fi
fi
unset KEY

TOTAL=$((SECONDS - T_START))
python3 "$SCRIPT_DIR/lib/installer_probe_report.py" --dir "$OUT" --ref "$REF" --sha "$SHA" --short "$SHORT" \
  --image "$IMAGE" --way "$WAY" --started "$STARTED" --duration "$((TOTAL / 60)) min $((TOTAL % 60)) s" \
  || echo "Report konnte nicht geschrieben werden" >&2
ln -sfn "$OUT" "$(dirname "$OUT")/latest" 2>/dev/null || true

FAILED="$(awk -F'\t' '$2=="fail"' "$OUT/checks.tsv" | wc -l)"
echo
echo "Report: $OUT/report.html"
if [ "$KEEP" = 1 ]; then
  if [ "$WAY" = host ]; then
    echo "Instanz läuft weiter: http://localhost:${FPORT}  (Backend :${BPORT})"
    echo "  API-Key:  docker exec -u probe $NAME cat ${USER_HOME}/.mysuricates/api-key"
    echo "  Aufräumen: docker rm -f $NAME"
  else
    echo "Instanzen laufen weiter (Docker des Rechners, Vorsilbe $PREFIX):"
    for n in $INSTS; do echo "  $n: http://localhost:$(inst_port "$n")"; done
    echo "  API-Key:  docker exec -u probe -w $CLONE -e MYSURICATES_PREFIX=$PREFIX $NAME bash scripts/instance.sh key --name <name>"
    echo "  Aufräumen: $0 --cleanup $PREFIX && rm -rf $WORK"
  fi
fi
if [ "$FAILED" = 0 ]; then
  echo "gate_status: GREEN"
  echo "gate_category: installer"
  echo "gate_description: setup.sh läuft auf ${IMAGE} von nackt bis Login durch (${WAY_LABEL}, ${REF} ${SHORT})"
  exit 0
fi
echo "gate_status: RED"
echo "gate_category: installer"
echo "gate_description: Installer-Probe auf ${IMAGE} (${WAY_LABEL}): ${FAILED} Prüfung(en) rot (${REF} ${SHORT}) — siehe Report"
exit 1
