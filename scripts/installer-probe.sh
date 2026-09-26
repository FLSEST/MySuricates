#!/usr/bin/env bash
#
# installer-probe.sh — prüft scripts/setup.sh so, wie ein neuer Nutzer ihn erlebt: auf einer frischen
# Linux-Maschine (Container; apt- oder dnf-Image, Default ubuntu:24.04), auf der nur git, sudo und
# tzdata liegen.
#
#   scripts/installer-probe.sh                   # HEAD dieses Checkouts prüfen, Report schreiben
#   scripts/installer-probe.sh --ref main        # anderen Stand prüfen (Branch, Tag oder SHA)
#   scripts/installer-probe.sh --interactive     # dieselbe frische Maschine als Shell — selbst durchklicken;
#                                                # teilt das Netz des Rechners: die Adresse, die setup.sh am
#                                                # Ende nennt, geht direkt im Browser
#   scripts/installer-probe.sh --keep            # Container nach dem Lauf stehen lassen, Instanz ansehen
#
#   Weitere Optionen: --repo <pfad>   Repo, aus dem der Stand kommt (Default: dieses)
#                     --image <image> apt- oder dnf-Image (Default ubuntu:24.04; z. B. rockylinux:9)
#                     --out <dir>     Report-Verzeichnis (Default: ~/.local/state/mysuricates/installer-probe/<zeit>-<sha>)
#                     --port <n>      Port auf 127.0.0.1 (Default: automatisch; nur automatischer Lauf)
#                     --setup-flags "<flags>"  zusaetzliche Flags fuer Phase 2, z. B. "--with-pi" (c3a4cbf6)
#                     --no-screenshots
#
# Automatischer Lauf (Weg „eine Instanz auf dem Rechner“): 1-pruefen `setup.sh --check`, 2-einrichten
# `setup.sh --yes --runtime host --bind 0.0.0.0` — ein Durchgang bis zur laufenden Instanz. Jede Phase
# läuft unter einem Pseudo-Terminal, der Mitschnitt zeigt also Farben und Kopf wie am Bildschirm.
# Harte Prüfungen: Phase 1 nennt die fehlenden Werkzeuge, Phase 2 endet mit Exit 0, Health UP (ohne
# Docker ist das kein Fehler), Oberfläche 200 auf demselben Port, Login mit dem erzeugten Key klappt
# (falls Playwright da ist), das Backend wird genau einmal gebaut, der Key steht in keinem Log und
# keinem Mitschnitt, und der Klon ist danach unverändert. Ergebnis: <out>/report.html — eine Datei,
# Screenshots eingebettet — und am Ende gate_status: GREEN|RED für den MySuricates-Workflow.
#
# Geprüft wird immer der committete Stand; uncommittete Änderungen sind nicht dabei (wie bei einem
# echten Klon). Ports nur auf 127.0.0.1. Greift nie auf DB oder REST-API der Dev-Instanz zu.
# Exit: 0 grün, 1 rot, 2 Aufruf-/Umgebungsfehler.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Die Probe-Instanz nie ueber einen Proxy aus der Umgebung fragen (bb4f60a5)
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
PHASE_TIMEOUT="${INSTALLER_PROBE_PHASE_TIMEOUT:-1800}"

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
    --keep)           KEEP=1 ;;
    --interactive)    INTERACTIVE=1 ;;
    --no-screenshots) SCREENSHOTS=0 ;;
    -h|--help)        sed -n '3,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *)                die "Unbekannte Option: $1 (siehe --help)" ;;
  esac
  shift
done

[ -n "$REF" ] || die "--ref braucht einen Wert"
[ -n "$IMAGE" ] || die "--image braucht einen Wert"
if [ -n "$FRONTEND_PORT" ] && ! [[ "$FRONTEND_PORT" =~ ^[0-9]+$ && "$FRONTEND_PORT" -ge 1024 && "$FRONTEND_PORT" -le 65000 ]]; then
  die "--port muss eine Zahl zwischen 1024 und 65000 sein"
fi
if [ "$INTERACTIVE" = 1 ] && [ -n "$FRONTEND_PORT" ]; then
  die "--port gilt nur für den automatischen Lauf — interaktiv nennt setup.sh den Port selbst"
fi
# Die Flags landen in bash -c '…' im Container: nur Flag-Zeichen, kein Quote, keine Shell-Syntax
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
if [ "$REF" = "HEAD" ] && [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
  echo "Hinweis: uncommittete Änderungen in $REPO werden nicht geprüft — nur Commit $SHORT." >&2
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="mysuricates-installer-probe-${STAMP}"
WORK="$(mktemp -d)"
USER_HOME="/home/probe"
CLONE="${USER_HOME}/mysuricates"

cleanup() {
  rm -rf "$WORK"
  if [ "$KEEP" = 0 ]; then docker rm -f "$NAME" >/dev/null 2>&1; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# --- Frische Maschine ------------------------------------------------------------------------------
# Ein Bundle trägt den Stand in den Container, unabhängig davon, ob $REPO ein Worktree, ein Klon mit
# Alternates oder ein normales Repo ist. Eine nackte SHA lässt sich nicht bündeln (kein Ref) → --all.
if ! git -C "$REPO" bundle create -q "$WORK/repo.bundle" "$REF" 2>/dev/null; then
  git -C "$REPO" bundle create -q "$WORK/repo.bundle" --all 2>/dev/null || die "git bundle fehlgeschlagen"
fi

# Ein Port: die Instanz liefert die Oberfläche selbst aus (setup.sh, Weg „auf dem Rechner“).
# Automatisch: Container-Port 9090 auf einen freien Port des Rechners. Interaktiv: das Netz des
# Rechners teilen — der Port, den setup.sh wählt (oder den man eintippt), ist dann direkt
# localhost:<port>, belegte Ports des Rechners sieht setup.sh selbst. Ein fest veröffentlichter Port
# ließ jede andere Wahl ins Leere laufen (6abc7fca). Die Instanz bleibt auf 127.0.0.1.
if [ "$INTERACTIVE" = 1 ]; then PORT_ARGS=(--network host)
elif [ -n "$FRONTEND_PORT" ]; then PORT_ARGS=(-p "127.0.0.1:${FRONTEND_PORT}:9090")
else PORT_ARGS=(-p "127.0.0.1::9090"); fi

echo "Installer-Probe: ${REF} (${SHORT}) auf ${IMAGE}"
docker run -d --name "$NAME" "${PORT_ARGS[@]}" "$IMAGE" sleep infinity >/dev/null \
  || die "Container konnte nicht starten (Image ${IMAGE}? Port belegt?)"

# Was ein Mensch nach `git clone` mindestens hat — und sonst nichts. tzdata gehört auf jedem echten
# Rechner dazu (sonst fragt apt beim JDK nach der Zeitzone); der animierte apt-Fortschrittsbalken ist
# abgeschaltet, weil er im Mitschnitt nur Steuerzeichen-Salat hinterlässt.
PREP='if command -v apt-get >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq git sudo tzdata >/dev/null 2>&1 || exit 1
  echo "Dpkg::Progress-Fancy \"0\";" > /etc/apt/apt.conf.d/99probe-no-fancy-progress
elif command -v dnf >/dev/null; then
  dnf install -y -q git sudo tzdata util-linux >/dev/null 2>&1 || exit 1
else
  echo "nur apt- oder dnf-Images werden unterstützt" >&2; exit 3
fi
id -u probe >/dev/null 2>&1 || useradd -m -s /bin/bash probe
echo "probe ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/probe && chmod 440 /etc/sudoers.d/probe'
docker exec "$NAME" bash -c "$PREP" || die "Grundausstattung im Container fehlgeschlagen"
docker cp -q "$WORK/repo.bundle" "$NAME:/tmp/repo.bundle" >/dev/null && docker exec "$NAME" chmod 644 /tmp/repo.bundle \
  || die "Bundle ließ sich nicht in den Container kopieren"
docker exec -u probe -w "$USER_HOME" -e PROBE_SHA="$SHA" -e PROBE_BRANCH="$BRANCH" "$NAME" bash -c \
  'git clone -q --no-checkout /tmp/repo.bundle mysuricates 2>/dev/null && cd mysuricates && git checkout -q -B "$PROBE_BRANCH" "$PROBE_SHA"' \
  || die "Klon im Container fehlgeschlagen"

host_port() { docker port "$NAME" "$1/tcp" 2>/dev/null | head -n1 | sed 's/.*://'; }
BPORT="$(host_port 9090)"
FPORT="$BPORT"

# --- Interaktiv: Shell in der frischen Maschine ----------------------------------------------------
if [ "$INTERACTIVE" = 1 ]; then
  # Die Maschine hat nur C.UTF-8; die Sprache der Modultexte wählt setup.sh dann über --lang.
  L=""; case "${LANG:-}" in de*) L=" --lang de" ;; esac
  HINTS="
  Frische Maschine: ${IMAGE}, Stand ${REF} (${SHORT}), Benutzer probe mit sudo.
  So geht ein neuer Nutzer vor:

    ./scripts/setup.sh${L}

  Im Modus-Schritt „auf diesem Rechner“ wählen — in diesem Wegwerf-Container gibt es kein Docker,
  „im Container“ und „Dev · Test · Prod“ scheitern hier (Probe dafür: TODO 2ed907a1).
  Nur ansehen, nichts installieren:  ./scripts/setup.sh --dry-run${L}

  Die Maschine teilt das Netz mit deinem Rechner: die Adresse, die der Installer am Ende nennt
  (http://localhost:<port>), öffnest du direkt im Browser. Belegte Ports umgeht er selbst.
  API-Key für den Login:  scripts/instance.sh key --name main
  exit beendet die Probe und räumt den Container weg.
"
  # Eigene Eingabeaufforderung: mit dem Netz des Rechners hiesse die Maschine wie der Rechner selbst
  docker exec -i -u probe "$NAME" bash -c 'cat > ~/.probe-hints && printf "%s\n" "cat ~/.probe-hints" "PS1='"'"'probe@frische-maschine:\\w\\$ '"'"'" >> ~/.bashrc' <<<"$HINTS"
  docker exec -it -u probe -w "$CLONE" -e TERM="${TERM:-xterm-256color}" -e LANG=C.UTF-8 "$NAME" bash
  exit 0
fi

# --- Automatischer Lauf ----------------------------------------------------------------------------
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

# Eine Phase unter einem Pseudo-Terminal (script), 120 Spalten, Eingabe per Pipe; -echo, damit die
# vorab gepufferte Antwort nicht als Zeile am Anfang des Mitschnitts steht.
run_phase() {  # name, command, input
  local name="$1" cmd="$2" input="$3" t0=$SECONDS rc
  printf '%b' "$input" | timeout "$PHASE_TIMEOUT" docker exec -i -u probe -w "$CLONE" \
    -e TERM=xterm-256color -e LANG=C.UTF-8 "$NAME" \
    script -qefc "stty cols 120 rows 40 -echo 2>/dev/null; rm -f /tmp/phase.rc; bash -c '$cmd'; echo \$? > /tmp/phase.rc" /dev/null \
    > "$OUT/logs/${name}.log" 2>&1
  rc="${PIPESTATUS[1]}"
  # Der Exit-Code der Phase selbst — über script/docker exec ging ein Abbruch schon als 0 durch
  local inner
  inner="$(docker exec "$NAME" cat /tmp/phase.rc 2>/dev/null)" && [ -n "$inner" ] && rc="$inner"
  printf '%s\t%s\t%s\t%s\n' "$name" "$cmd" "$rc" "$((SECONDS - t0))" >> "$OUT/phases.tsv"
  printf '  Phase %-10s exit=%s  %ss\n' "$name" "$rc" "$((SECONDS - t0))"
  return "$rc"
}

run_phase 1-pruefen    "./scripts/setup.sh --check --runtime host --lang de" ""; RC1=$?
run_phase 2-einrichten "./scripts/setup.sh --yes --runtime host --bind 0.0.0.0 --lang de${SETUP_FLAGS:+ $SETUP_FLAGS}" ""; RC2=$?

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

HEALTH="$(local_curl -s --max-time 10 "http://127.0.0.1:${BPORT}/api/v1/system/health" 2>/dev/null)"
if grep '"status":"UP"' <<<"$HEALTH" >/dev/null; then check "Health UP — ohne Docker kein Fehler" ok "$(grep -o '"docker":"[A-Z_]*"' <<<"$HEALTH")"
else check "Health UP — ohne Docker kein Fehler" fail "${HEALTH:-keine Antwort}"; fi
HTTP="$(local_curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:${FPORT}/" 2>/dev/null)"
if [ "$HTTP" = 200 ]; then check "Oberfläche auf demselben Port (HTTP 200)" ok ""; else check "Oberfläche auf demselben Port (HTTP 200)" fail "HTTP ${HTTP:-—}"; fi

SETUP_LOGS="${USER_HOME}/.local/state/mysuricates/setup-logs"
BUILDS="$(docker exec -u probe "$NAME" sh -c "cat $SETUP_LOGS/*.log 2>/dev/null" | grep -c 'Baue Backend')"
if [ "$BUILDS" = 1 ]; then check "Backend genau einmal gebaut" ok ""; else check "Backend genau einmal gebaut" fail "${BUILDS}× gebaut"; fi

DIRTY="$(docker exec -u probe -w "$CLONE" "$NAME" git status --porcelain 2>&1)"
if [ -z "$DIRTY" ]; then
  check "Klon nach dem Setup unverändert (git status leer)" ok ""
else
  check "Klon nach dem Setup unverändert (git status leer)" fail "$(tr '\n' ' ' <<<"$DIRTY")"
fi

KEY="$(docker exec -u probe -w "$CLONE" "$NAME" bash scripts/instance.sh key --name main 2>/dev/null)"
if [ -z "$KEY" ]; then
  check "API-Key steht in keinem Log und keinem Mitschnitt" fail "kein Key gefunden"
else
  LEAK="$( { cat "$OUT"/logs/*.log; docker exec -u probe "$NAME" sh -c "cat ${USER_HOME}/.local/state/mysuricates/setup-logs/*.log ${USER_HOME}/.local/state/mysuricates/main/data/logs/*.log 2>/dev/null"; } | grep -cF "$KEY")"
  if [ "$LEAK" = 0 ]; then check "API-Key steht in keinem Log und keinem Mitschnitt" ok ""
  else check "API-Key steht in keinem Log und keinem Mitschnitt" fail "${LEAK} Fundstelle(n)"; fi
fi

PLAYWRIGHT=""
for dir in "$REPO/ai-automation-frontend" "$SCRIPT_DIR/../ai-automation-frontend"; do
  if [ -f "$dir/node_modules/playwright/index.mjs" ]; then PLAYWRIGHT="$(cd "$dir" && pwd)/node_modules/playwright/index.mjs"; break; fi
done
if [ "$SCREENSHOTS" = 0 ]; then
  check "Login im Browser mit dem erzeugten API-Key" skip "--no-screenshots"
elif [ -z "$PLAYWRIGHT" ] || ! command -v node >/dev/null 2>&1; then
  check "Login im Browser mit dem erzeugten API-Key" skip "kein Playwright unter ai-automation-frontend/node_modules"
elif [ "$HTTP" != 200 ]; then
  check "Login im Browser mit dem erzeugten API-Key" skip "Frontend nicht erreichbar"
else
  SHOTS="$(PLAYWRIGHT_MODULE="$PLAYWRIGHT" PROBE_API_KEY="$KEY" \
    timeout 180 node "$SCRIPT_DIR/lib/installer_probe_screens.mjs" "http://127.0.0.1:${FPORT}/" "$OUT/screens" 2>&1)"
  SHOTS_RC=$?
  unset KEY
  if [ "$SHOTS_RC" = 3 ]; then
    # Browser zur installierten Playwright-Version fehlt (nach einem Update, 65164c57). Rot statt
    # skip, weil der Login dann ungeprueft ist und GREEN "bis Login durch" behaupten wuerde — aber mit
    # der Abhilfe statt eines Stacktraces. Nachladen ist Flos Entscheidung, die Probe laedt nichts.
    check "Login im Browser mit dem erzeugten API-Key" fail "Browser fehlt — im Frontend \`npm run pw:install\` ausführen ($(grep -o '^browser missing: [^ ]*' <<<"$SHOTS" | head -n1 | sed 's/^browser missing: //'))"
  elif [ "$SHOTS_RC" = 0 ]; then
    check "Login im Browser mit dem erzeugten API-Key" ok "$(grep -o 'console-errors [0-9]*' <<<"$SHOTS" | sed 's/console-errors /Konsolenfehler: /')$(grep '^console-error ' <<<"$SHOTS" | sed 's/^console-error / · /' | tr -d '\n')"
  else
    check "Login im Browser mit dem erzeugten API-Key" fail "$(grep -E 'failed' <<<"$SHOTS" | head -n1)"
  fi
fi

TOTAL=$((SECONDS - T_START))
python3 "$SCRIPT_DIR/lib/installer_probe_report.py" --dir "$OUT" --ref "$REF" --sha "$SHA" --short "$SHORT" \
  --image "$IMAGE" --started "$STARTED" --duration "$((TOTAL / 60)) min $((TOTAL % 60)) s" \
  || echo "Report konnte nicht geschrieben werden" >&2
ln -sfn "$OUT" "$(dirname "$OUT")/latest" 2>/dev/null || true

FAILED="$(awk -F'\t' '$2=="fail"' "$OUT/checks.tsv" | wc -l)"
echo
echo "Report: $OUT/report.html"
if [ "$KEEP" = 1 ]; then
  echo "Instanz läuft weiter: http://localhost:${FPORT}  (Backend :${BPORT})"
  echo "  API-Key:  docker exec -u probe $NAME cat ${USER_HOME}/.autotrigger/api-key"
  echo "  Aufräumen: docker rm -f $NAME"
fi
if [ "$FAILED" = 0 ]; then
  echo "gate_status: GREEN"
  echo "gate_category: installer"
  echo "gate_description: setup.sh läuft auf ${IMAGE} von nackt bis Login durch (${REF} ${SHORT})"
  exit 0
fi
echo "gate_status: RED"
echo "gate_category: installer"
echo "gate_description: Installer-Probe auf ${IMAGE}: ${FAILED} Prüfung(en) rot (${REF} ${SHORT}) — siehe Report"
exit 1
