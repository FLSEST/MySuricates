#!/usr/bin/env bash
# Tests for scripts/installer-probe.sh — argument handling and the checks that run before Docker.
# The probe itself (container, setup.sh, report) is exercised by running it; see docs/entwicklung.md.
# Run: bash scripts/tests/test-installer-probe.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="$HERE/../installer-probe.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Fake docker: logs every call; `docker info` fails, so a probe that gets that far stops with exit 2
# instead of starting containers.
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
exit 1
STUB
chmod +x "$TMP/bin/docker"
export DOCKER_CALLS="$TMP/docker-calls.log"
probe() { PATH="$TMP/bin:$PATH" bash "$PROBE" "$@" </dev/null >"$TMP/out" 2>&1; echo $?; }

echo "1) syntax and help"
check "bash -n passes"                                  'bash -n "$PROBE"'
check "--help names the interactive mode"               'bash "$PROBE" --help | grep -- "--interactive" >/dev/null'
check "--help names --setup-flags (c3a4cbf6)"          'bash "$PROBE" --help | grep -- "--setup-flags" >/dev/null'
check "--help names the container ways and --cleanup"   'bash "$PROBE" --help | grep -- "--way container" >/dev/null && bash "$PROBE" --help | grep -- "--way staged" >/dev/null && bash "$PROBE" --help | grep -- "--cleanup" >/dev/null'
check "--help names --ref and the gate lines"           'bash "$PROBE" --help | grep -- "--ref main" >/dev/null && bash "$PROBE" --help | grep "gate_status" >/dev/null'
check "the report renderer parses"                      'python3 -m py_compile "$HERE/../lib/installer_probe_report.py"'
check "the screenshot helper parses"                    '! command -v node >/dev/null || node --check "$HERE/../lib/installer_probe_screens.mjs"'

echo "2) argument errors exit 2 before any docker call"
: > "$DOCKER_CALLS"
check "unknown option"                                  '[ "$(probe --wat)" = 2 ] && grep -q "Unbekannte Option" "$TMP/out"'
check "unknown ref"                                     '[ "$(probe --ref does-not-exist-anywhere)" = 2 ] && grep -q "Unbekannter Stand" "$TMP/out"'
check "port must be numeric"                            '[ "$(probe --port abc)" = 2 ]'
check "port below 1024 is refused"                      '[ "$(probe --port 80)" = 2 ]'
check "--repo must be a git repo"                       '[ "$(probe --repo "$TMP")" = 2 ] && grep -q "Kein Git-Repo" "$TMP/out"'
check "--interactive without a terminal is refused"     '[ "$(probe --interactive)" = 2 ] && grep -q "braucht ein Terminal" "$TMP/out"'
check "--port with --interactive is refused"            '[ "$(probe --interactive --port 23000)" = 2 ] && grep -q "nur für den automatischen Lauf" "$TMP/out"'
check "--setup-flags: quotes and shell syntax refused"  '[ "$(probe --setup-flags "--with-pi'"'"'x")" = 2 ] && grep -q -- "--setup-flags: nur Flags" "$TMP/out" && [ "$(probe --setup-flags "--x;id")" = 2 ] && [ "$(probe --setup-flags "\$(id)")" = 2 ]'
check "--setup-flags with --interactive is refused"     '[ "$(probe --interactive --setup-flags --with-pi)" = 2 ] && grep -q "nur für den automatischen Lauf" "$TMP/out"'
check "unknown --way"                                   '[ "$(probe --way vm)" = 2 ] && grep -q -- "--way host|container|staged" "$TMP/out"'
check "--interactive only for --way host"               '[ "$(probe --interactive --way container)" = 2 ] && grep -q "nur für --way host" "$TMP/out"'
check "--cleanup refuses names that are not a probe run" '[ "$(probe --cleanup mysuricates-stable)" = 2 ] && [ "$(probe --cleanup mysuricates-installer-probe-x)" = 2 ] && [ "$(probe --cleanup "mysuricates-installer-probe-20260927-120000-main")" = 2 ] && grep -q "nur den Namen eines Probe-Laufs" "$TMP/out"'
check "none of these touched docker"                    '[ ! -s "$DOCKER_CALLS" ]'

echo "3) docker availability"
check "unreachable daemon exits 2 with a reason"        '[ "$(probe --ref HEAD)" = 2 ] && grep -q "Docker-Daemon nicht erreichbar" "$TMP/out"'
check "it asked docker info, nothing else"              '[ "$(cat "$DOCKER_CALLS")" = info ]'

echo "4) interactive: the machine shares the host network (6abc7fca)"
# Der Port, den setup.sh in der Probe waehlt, muss draussen unter localhost:<port> antworten — ein
# fest veroeffentlichter Port (frueher 9090 → 23000) laesst jede andere Wahl ins Leere laufen.
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1; then
  mkdir -p "$TMP/bin2"
  printf '#!/usr/bin/env bash\necho "$*" >> "$DOCKER_CALLS"\ncase "$1" in info) exit 0 ;; *) exit 1 ;; esac\n' > "$TMP/bin2/docker"
  chmod +x "$TMP/bin2/docker"; : > "$DOCKER_CALLS"
  script -qec "PATH='$TMP/bin2:$PATH' bash '$PROBE' --interactive" /dev/null </dev/null >/dev/null 2>&1
  check "interactive container uses the host network"   'grep -E "^run .*--network host" "$DOCKER_CALLS" >/dev/null'
  check "interactive container publishes no port"       '! grep -E "^run .* -p " "$DOCKER_CALLS" >/dev/null'
else
  echo "  skip (no usable script command)"
fi

echo "5) screenshot helper: a missing browser is named, not a stack trace (65164c57)"
if command -v node >/dev/null 2>&1; then
  SCREENS="$HERE/../lib/installer_probe_screens.mjs"
  # Fake-Playwright: launch() wirft, wie Playwright es nach einem Update ohne Browser-Download tut
  cat > "$TMP/pw-missing.mjs" <<'MJS'
export const chromium = { launch: async () => { throw new Error("browserType.launch: Executable doesn't exist at /pw/chromium_headless_shell-9999/chrome-headless-shell\n╔══╗\n║ Looks like Playwright was just installed or updated. ║\n║ Please run the following command to download new browsers: npx playwright install ║"); } };
MJS
  cat > "$TMP/pw-broken.mjs" <<'MJS'
export const chromium = { launch: async () => { throw new Error("browserType.launch: Target page, context or browser has been closed\nmore"); } };
MJS
  PLAYWRIGHT_MODULE="$TMP/pw-missing.mjs" node "$SCREENS" http://127.0.0.1:1/ "$TMP" >"$TMP/shots" 2>&1; RC=$?
  check "missing browser exits 3"                       '[ "$RC" = 3 ]'
  check "names the path and npm run pw:install"         'grep -F "browser missing: /pw/chromium_headless_shell-9999/chrome-headless-shell" "$TMP/shots" >/dev/null && grep -F "npm run pw:install" "$TMP/shots" >/dev/null'
  check "one line, no stack trace"                      '[ "$(wc -l < "$TMP/shots")" -eq 1 ]'
  PLAYWRIGHT_MODULE="$TMP/pw-broken.mjs" node "$SCREENS" http://127.0.0.1:1/ "$TMP" >"$TMP/shots" 2>&1; RC=$?
  check "other launch errors stay a failure (exit 1)"   '[ "$RC" = 1 ] && grep -F "login failed: browser did not start" "$TMP/shots" >/dev/null'
  check "the probe turns exit 3 into the hint"          'grep -F "Browser fehlt — im Frontend" "$PROBE" >/dev/null && grep -F "SHOTS_RC\" = 3" "$PROBE" >/dev/null'
else
  echo "  skip (no node)"
fi

echo "6) extra setup flags reach phase 2 only (c3a4cbf6)"
# Etwa --with-pi: die Probe soll auch den Weg mit pi fahren koennen. docker nimmt hier alles an und tut
# nichts — die Probe laeuft durch (rot, es gibt keine Instanz), die Phasen stehen in phases.tsv.
mkdir -p "$TMP/bin3"
printf '#!/usr/bin/env bash\necho "$*" >> "$DOCKER_CALLS"\nexit 0\n' > "$TMP/bin3/docker"; chmod +x "$TMP/bin3/docker"
: > "$DOCKER_CALLS"
PATH="$TMP/bin3:$PATH" INSTALLER_PROBE_PHASE_TIMEOUT=20 timeout 120 bash "$PROBE" --no-screenshots --out "$TMP/p6" \
  --setup-flags "--with-pi --preset research" </dev/null >"$TMP/out" 2>&1
phase_cmd() { awk -F'\t' -v n="$1" '$1 == n { print $2 }' "$TMP/p6/phases.tsv" 2>/dev/null; }
check "phase 2 carries the flags"                       'phase_cmd 2-einrichten | grep -F -- "--lang de --with-pi --preset research" >/dev/null'
check "phase 1 (check only) does not"                   'phase_cmd 1-pruefen | grep -F -- "--lang de" >/dev/null && ! phase_cmd 1-pruefen | grep -F -- "--with-pi" >/dev/null'

echo "7) container ways: host socket, same path, own names — and only those cleaned up"
# docker nimmt alles an; ps/volume/network ls listen Namen dieses Laufs (Vorsilbe aus `run --name`)
# neben fremden. Aufgeraeumt werden duerfen nur die mit der Vorsilbe.
if [ "$(id -u)" = 0 ]; then
  echo "  skip (running as root — the container ways refuse root)"
else
  mkdir -p "$TMP/bin4"
  cat > "$TMP/bin4/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
n="$(cat "$PROBE_NAME_FILE" 2>/dev/null)"
case "$1" in
  run) prev=""; for a in "$@"; do [ "$prev" = --name ] && echo "$a" > "$PROBE_NAME_FILE"; prev="$a"; done ;;
  ps) printf '%s\n' "$n" "$n-main" mysuricates-stable mysuricates-main "${n}x-main" ;;
  volume) [ "$2" = ls ] && printf '%s\n' "$n-main-data" "$n-main-secrets" mysuricates-main-data ai-automation-backend_stable-data ;;
  network) [ "$2" = ls ] && printf '%s\n' "$n-main_default" bridge ;;
esac
exit 0
STUB
  chmod +x "$TMP/bin4/docker"
  python3 -c "import socket,sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])" "$TMP/d.sock"
  export PROBE_NAME_FILE="$TMP/probe-name"
  way() { PATH="$TMP/bin4:$PATH" DOCKER_HOST="unix://$TMP/d.sock" INSTALLER_PROBE_PHASE_TIMEOUT=20 timeout 120 bash "$PROBE" --no-screenshots "$@" </dev/null >"$TMP/out" 2>&1; echo $?; }
  : > "$DOCKER_CALLS"
  check "a missing socket exits 2"                      '[ "$(PATH="$TMP/bin4:$PATH" DOCKER_HOST="unix://$TMP/none.sock" bash "$PROBE" --way container </dev/null >"$TMP/out" 2>&1; echo $?)" = 2 ] && grep -q "Docker-Socket .* nicht gefunden" "$TMP/out"'
  check "a remote DOCKER_HOST exits 2"                  '[ "$(PATH="$TMP/bin4:$PATH" DOCKER_HOST=tcp://10.0.0.1:2375 bash "$PROBE" --way staged </dev/null >"$TMP/out" 2>&1; echo $?)" = 2 ] && grep -q "lokalen Docker-Socket" "$TMP/out"'
  check "ports of Dev/Test/Stable/Tryout are refused"   '[ "$(way --way container --port 9090)" = 2 ] && grep -q "vergeben" "$TMP/out" && [ "$(way --way staged --port 9089)" = 2 ]'
  check "no machine was started for these"              '! grep -q "^run " "$DOCKER_CALLS"'
  : > "$DOCKER_CALLS"
  way --way container --port 19871 --out "$TMP/p7" >/dev/null
  N="$(cat "$PROBE_NAME_FILE")"
  check "the machine: host network, host socket"        'grep -E "^run -d --name $N --network host -v $TMP/d.sock:/var/run/docker.sock " "$DOCKER_CALLS" >/dev/null'
  check "the clone folder under the same path"          'grep -E "^run .* -v ([^ :]+/machine):\1 " "$DOCKER_CALLS" >/dev/null'
  check "the probe user gets this machine's IDs"        'grep -F -- "-e PROBE_UID=$(id -u) -e PROBE_SOCK_GID=$(stat -c %g "$TMP/d.sock" 2>/dev/null || stat -f %g "$TMP/d.sock") $N bash -c" "$DOCKER_CALLS" >/dev/null'
  check "setup.sh with the defaults, only the port set" 'awk -F"\t" "\$1==\"2-einrichten\" {print \$2}" "$TMP/p7/phases.tsv" | grep -x -- "./scripts/setup.sh --yes --port 19871 --lang de" >/dev/null'
  check "check phase names the way (no mode question)" 'awk -F"\t" "\$1==\"1-pruefen\" {print \$2}" "$TMP/p7/phases.tsv" | grep -x -- "./scripts/setup.sh --check --runtime container --lang de" >/dev/null'
  check "setup.sh sees the run's prefix"                'grep -E "^exec -i -u probe .* -e MYSURICATES_PREFIX=$N " "$DOCKER_CALLS" >/dev/null'
  check "cleanup: the machine and its instance"         'grep -qx "rm -f $N" "$DOCKER_CALLS" && grep -qx "rm -f $N-main" "$DOCKER_CALLS"'
  check "cleanup: its volumes, network and image"       'grep -qx "volume rm $N-main-data" "$DOCKER_CALLS" && grep -qx "volume rm $N-main-secrets" "$DOCKER_CALLS" && grep -qx "network rm $N-main_default" "$DOCKER_CALLS" && grep -qx "image rm $N:local" "$DOCKER_CALLS"'
  check "cleanup: nothing else"                         '! grep -E "^(rm|volume rm|network rm|image rm) " "$DOCKER_CALLS" | grep -vE " $N(-main(-data|-secrets|_default)?|:local)?$" | grep . >/dev/null'
  : > "$DOCKER_CALLS"
  way --way staged --port 19874 --out "$TMP/p8" >/dev/null
  check "staged: Dev · Test · Prod from the port on"     'awk -F"\t" "\$1==\"2-einrichten\" {print \$2}" "$TMP/p8/phases.tsv" | grep -x -- "./scripts/setup.sh --yes --mode staged --port 19874 --lang de" >/dev/null && awk -F"\t" "\$1==\"1-pruefen\" {print \$2}" "$TMP/p8/phases.tsv" | grep -- "--mode staged" >/dev/null'
  check "staged: its own checks are in the report"       'grep -q "^Drei Instanzen gesund" "$TMP/p8/checks.tsv" && grep -q "^Prod mit Siegelpflicht" "$TMP/p8/checks.tsv"'
  check "staged: red without instances"                  'grep -q "gate_status: RED" "$TMP/out"'
  : > "$DOCKER_CALLS"
  N="mysuricates-installer-probe-20260927-120000"; echo "$N" > "$PROBE_NAME_FILE"
  check "--cleanup removes a kept run"                   '[ "$(PATH="$TMP/bin4:$PATH" bash "$PROBE" --cleanup "$N" </dev/null >"$TMP/out" 2>&1; echo $?)" = 0 ] && grep -qx "rm -f $N" "$DOCKER_CALLS" && grep -qx "volume rm $N-main-data" "$DOCKER_CALLS" && ! grep -E "^(rm|volume rm) .*(stable|mysuricates-main)" "$DOCKER_CALLS" >/dev/null'
fi

echo "8) TERM during a phase: cleanup right away, not after the phase"
# docker exec der Phase haengt (wie setup.sh an einer Rueckfrage); ein TERM an die Probe muss trotzdem
# sofort aufraeumen — auf einen Vordergrund-Prozess wartete bash bis zum Ende der Phase
mkdir -p "$TMP/bin5"
cat > "$TMP/bin5/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
case "$1 $2" in "exec -i") sleep 20 ;; esac   # endet von selbst, der Test beendet nichts per Name
exit 0
STUB
chmod +x "$TMP/bin5/docker"; : > "$DOCKER_CALLS"
PATH="$TMP/bin5:$PATH" bash "$PROBE" --no-screenshots --out "$TMP/p9" </dev/null >"$TMP/out" 2>&1 &
PPID9=$!
for _ in $(seq 1 100); do grep -q "^exec -i " "$DOCKER_CALLS" 2>/dev/null && break; sleep 0.1; done
T0=$SECONDS; kill -TERM "$PPID9"; wait "$PPID9"; RC9=$?
check "TERM ends the probe within seconds (exit 130)"  '[ "$RC9" = 130 ] && [ $((SECONDS - T0)) -lt 15 ]'
check "... and removes the machine"                    'grep -qE "^rm -f mysuricates-installer-probe-[0-9]{8}-[0-9]{6}$" "$DOCKER_CALLS"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
