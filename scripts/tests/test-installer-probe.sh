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

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
