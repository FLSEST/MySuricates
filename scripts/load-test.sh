#!/usr/bin/env bash
#
# load-test.sh — load test of a revision on a throwaway instance, before every release (TODO 1a6058dc).
#
#   scripts/load-test.sh [--ref REF] [--repo DIR] [--jar PATH] [--scenario FILE] [--keep]
#
# 1. Builds the backend JAR from exactly the commit REF (default HEAD, via git archive — uncommitted changes do
#    not count) or takes --jar. Built JARs are cached per commit and reused.
# 2. Starts a throwaway instance: own port, own -Duser.home/HOME/XDG_CONFIG_HOME, own DB,
#    own API key, no Docker (socket path pointing nowhere), no backup, no tray, no rate limit,
#    no auto-tagging. claude/codex/pi are dummies on the PATH that log every call: an
#    AI call costs no tokens here, but turns the run RED.
# 3. driver.py: synthetic seed from load-test/scenario.json, then load like real users (readers,
#    run starters, TODO writers), every request with client time and server timing of the instance.
# 4. report.py: key figures per route, verdict (GREEN/YELLOW/RED), comparison with the last run on an
#    earlier revision (tagged releases first).
#
# Result: ${MYSURICATES_LOAD_TEST_DIR:-${XDG_STATE_HOME:-~/.local/state}/mysuricates/load-test}/
#         <timestamp>-<sha12>/ with facts.txt, result.json, summary.txt, raw.json, instance.log.
# release.sh reads summary.txt of the tagged revision. At the end facts.txt and the
# gate_status lines go to stdout — which is also how the pool workflow "Lasttest vor dem Release" reads them.
#
# Exit: 0 GREEN/YELLOW, 3 RED, 2 run not possible (build, start, seed).
# Prerequisites: git, java (21+), mvn (only without --jar and without cache hit), python3, curl.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/local-curl.sh
. "$SCRIPT_DIR/lib/local-curl.sh"
REF="HEAD"
JAR=""
SCENARIO="$SCRIPT_DIR/load-test/scenario.json"
KEEP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="$2"; shift 2 ;;
    --repo) REPO="$(cd "$2" && pwd)"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $1" >&2; exit 2 ;;
  esac
done

log() { echo "[load-test] $*"; }
fail() {  # run not possible: clear gate lines so a workflow does not silently stay green
  echo "[load-test] FEHLER: $*" >&2
  echo "gate_status: RED"
  echo "gate_category: load-test"
  echo "gate_description: Lasttest nicht moeglich — $*"
  exit 2
}

for tool in git java python3 curl; do
  command -v "$tool" >/dev/null || fail "$tool fehlt"
done
[ -f "$SCENARIO" ] || fail "Szenario fehlt: $SCENARIO"

COMMIT="$(git -C "$REPO" rev-parse --verify --quiet "${REF}^{commit}")" || fail "Unbekannter Stand: $REF"
STATE_ROOT="${MYSURICATES_LOAD_TEST_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/load-test}"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/mysuricates/load-test"
RESULT_DIR="$STATE_ROOT/$(date +%Y%m%d-%H%M%S)-${COMMIT:0:12}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mysuricates-load-test.XXXXXX")"
JVM_PID=""

cleanup() {
  if [ -n "$JVM_PID" ] && kill -0 "$JVM_PID" 2>/dev/null; then
    kill "$JVM_PID" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "$JVM_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$JVM_PID" 2>/dev/null
  fi
  if [ "$KEEP" = 1 ]; then log "Arbeitsverzeichnis bleibt: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

log "Stand: $COMMIT (${REF})"

# --- 1. JAR ------------------------------------------------------------------------------------------
if [ -z "$JAR" ]; then
  JAR="$CACHE_DIR/jars/$COMMIT.jar"
  if [ -f "$JAR" ]; then
    log "JAR aus dem Cache: $JAR"
  else
    command -v mvn >/dev/null || fail "mvn fehlt (oder --jar angeben)"
    log "Baue JAR aus $COMMIT (git archive, mvn package -DskipTests) ..."
    mkdir -p "$WORK/src" "$CACHE_DIR/jars"
    git -C "$REPO" archive "$COMMIT" | tar -x -C "$WORK/src" || fail "git archive fehlgeschlagen"
    (cd "$WORK/src/ai-automation-backend" && mvn -q -DskipTests package) > "$WORK/build.log" 2>&1 \
      || { tail -30 "$WORK/build.log" >&2; fail "Build fehlgeschlagen"; }
    BUILT="$(find "$WORK/src/ai-automation-backend/target" -maxdepth 1 -name 'ai-automation-backend-*.jar' ! -name '*.original' | head -1)"
    [ -n "$BUILT" ] || fail "Kein Fat-JAR im Build"
    cp "$BUILT" "$JAR.tmp" && mv "$JAR.tmp" "$JAR"
    rm -rf "$WORK/src"
    # Limit the cache: the five most recent JARs are enough for release and baseline.
    ls -1t "$CACHE_DIR/jars"/*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f --
  fi
fi
[ -f "$JAR" ] || fail "JAR fehlt: $JAR"

# --- 2. Throwaway instance ------------------------------------------------------------------------------
mkdir -p "$WORK/home/.config" "$WORK/data" "$WORK/backups" "$WORK/project" "$WORK/bin" "$RESULT_DIR"
: > "$WORK/ai-calls.log"
for cli in claude codex pi; do
  printf '#!/bin/sh\necho "%s $*" >> "%s"\nexit 1\n' "$cli" "$WORK/ai-calls.log" > "$WORK/bin/$cli"
  chmod +x "$WORK/bin/$cli"
done
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
KEY="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
read -r XMX MAX_RUNS < <(python3 -c 'import json,sys; i=json.load(open(sys.argv[1])).get("instance",{}); print(i.get("xmx","1g"), i.get("max_concurrent_runs",10))' "$SCENARIO")
JAVA="$(command -v java)"

log "Starte Instanz auf 127.0.0.1:$PORT (Heap $XMX, $MAX_RUNS parallele Laeufe) ..."
# env -i: nothing from the caller's shell (SPRING_PROFILES_ACTIVE, AUTOMATION_* of the dev instance …)
# reaches the throwaway instance. -Duser.home is mandatory — Java does not read user.home from $HOME.
(cd "$WORK" && exec env -i \
  PATH="$WORK/bin:/usr/local/bin:/usr/bin:/bin" HOME="$WORK/home" XDG_CONFIG_HOME="$WORK/home/.config" \
  LANG="${LANG:-C.UTF-8}" TMPDIR="$WORK" \
  DOCKER_HOST="unix://$WORK/kein-docker.sock" \
  AUTOMATION_PORT="$PORT" AUTOMATION_DB_PATH="$WORK/data/automation.db" AUTOMATION_API_KEY="$KEY" \
  AUTOMATION_BACKUP_DIR="$WORK/backups" AUTOMATION_BACKUP_ENABLED=false AUTOMATION_TRAY_ENABLED=false \
  AUTOMATION_MODULES_PRESET=full AUTOMATION_RATE_LIMIT_ENABLED=false \
  AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false AUTOMATION_DISCOVERY_SEMANTIC_ENABLED=false \
  AUTOMATION_MAX_CONCURRENT_RUNS="$MAX_RUNS" \
  "$JAVA" "-Xmx$XMX" -Duser.home="$WORK/home" -jar "$JAR" --server.address=127.0.0.1 \
  > "$WORK/instance.log" 2>&1) &
JVM_PID=$!

for _ in $(seq 1 180); do
  kill -0 "$JVM_PID" 2>/dev/null || { tail -30 "$WORK/instance.log" >&2; cp "$WORK/instance.log" "$RESULT_DIR/"; fail "Instanz beendete sich beim Start"; }
  local_curl -sf -m 2 "http://127.0.0.1:$PORT/actuator/health" >/dev/null && break
  sleep 1
done
local_curl -sf -m 2 "http://127.0.0.1:$PORT/actuator/health" >/dev/null || fail "Instanz nach 180 s nicht erreichbar"
log "Instanz bereit (PID $JVM_PID)"

# --- 3. Seed and load --------------------------------------------------------------------------------
python3 "$SCRIPT_DIR/load-test/driver.py" --base "http://127.0.0.1:$PORT/api/v1" --key "$KEY" \
  --scenario "$SCENARIO" --project-dir "$WORK/project" --commit "$COMMIT" \
  --out "$RESULT_DIR/raw.json" --ai-log "$WORK/ai-calls.log"
DRIVER_RC=$?
cp "$WORK/instance.log" "$RESULT_DIR/instance.log"
[ -s "$WORK/ai-calls.log" ] && cp "$WORK/ai-calls.log" "$RESULT_DIR/ai-calls.log"
[ "$DRIVER_RC" -eq 0 ] || fail "Treiber brach ab (Exit $DRIVER_RC, Log: $RESULT_DIR/instance.log)"

# --- 4. Evaluation -----------------------------------------------------------------------------------
python3 "$SCRIPT_DIR/load-test/report.py" "$RESULT_DIR/raw.json" "$RESULT_DIR" \
  --results-root "$STATE_ROOT" --repo "$REPO"
REPORT_RC=$?
[ "$REPORT_RC" -eq 2 ] && fail "Auswertung fehlgeschlagen"

cat "$RESULT_DIR/facts.txt"
VERDICT="$(sed -n 's/.* verdict=\([A-Z]*\).*/\1/p' "$RESULT_DIR/summary.txt")"
echo ""
echo "Ergebnis: $RESULT_DIR"
echo "gate_status: $VERDICT"
echo "gate_category: load-test"
case "$VERDICT" in
  GREEN)  echo "gate_description: Lasttest unauffaellig" ;;
  YELLOW) echo "gate_description: Lasttest auffaellig — $(sed -n 's/^FAKT load_reason_1: //p' "$RESULT_DIR/facts.txt")" ;;
  *)      echo "gate_description: Lasttest RED — $(sed -n 's/^FAKT load_reason_1: //p' "$RESULT_DIR/facts.txt")" ;;
esac
exit "$REPORT_RC"
