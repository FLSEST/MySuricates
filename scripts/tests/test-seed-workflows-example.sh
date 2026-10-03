#!/usr/bin/env bash
# Tests for scripts/seed-workflows.example.sh — the template for a private seed script, run through
# against a curl stub that answers like the API (no backend, no network). Needs jq, like the template.
# Run: bash scripts/tests/test-seed-workflows-example.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../seed-workflows.example.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

echo "1) syntax"
check "bash -n"                                'bash -n "$SCRIPT"'

echo "2) a seed run from start to end (cad7d821)"
# Die EXIT-Trap griff auf main()s lokales tmp zu: beim Beenden war es weg, und unter set -u endete das
# Skript mit „tmp: unbound variable“ (bash nannte dafuer lib/local-curl.sh:1) — Exit 1, die
# Temp-Datei blieb liegen. Der curl-Stub schreibt je Aufruf "METHODE URL" und, was ueber stdin kam.
if command -v jq >/dev/null 2>&1; then
  mkdir -p "$TMP/bin" "$TMP/tmp"
  cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
method=GET url="" stdin=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift ;;
    -H) [ "$2" = @- ] && stdin="$(cat)"; shift ;;
    -d) shift ;;
    http*) url="$1" ;;
  esac
  shift
done
printf '%s %s\n' "$method" "$url" >> "$CURL_LOG"
[ -z "$stdin" ] || printf '  stdin: %s\n' "$stdin" >> "$CURL_LOG"
case "$method $url" in
  "GET "*/system/health) echo '{"status":"UP"}' ;;
  "GET "*/projects)      echo '[]' ;;
  "POST "*/projects)     echo '{"id":"p-1","name":"Demo"}' ;;
  "POST "*/workflows)    echo '{"id":"w-1","name":"Hello World"}' ;;
  "POST "*/schedules)    echo '{"id":"s-1"}' ;;
  *) exit 22 ;;
esac
STUB
  chmod +x "$TMP/bin/curl"
  export CURL_LOG="$TMP/curl.log"; : > "$CURL_LOG"
  OUT="$(env PATH="$TMP/bin:$PATH" TMPDIR="$TMP/tmp" LC_ALL=C AUTOPILOT_URL=http://127.0.0.1:9 \
    AUTOPILOT_API_KEY=seed-key bash "$SCRIPT" 2>&1)"; RC=$?
  check "runs through: exit 0, Done."          '[ "$RC" = 0 ] && printf "%s" "$OUT" | grep "Done\." >/dev/null'
  check "no unbound variable at the end"       '! printf "%s" "$OUT" | grep "unbound variable" >/dev/null'
  check "the temporary workflow file is gone"  '[ -z "$(ls -A "$TMP/tmp")" ]'
  check "project, workflow and schedule created" 'grep -q "^POST .*/projects$" "$CURL_LOG" && grep -q "^POST .*/workflows$" "$CURL_LOG" && grep -q "^POST .*/schedules$" "$CURL_LOG"'
  check "the key via stdin only, never in curl's arguments" 'grep -q "stdin: X-API-Key: seed-key" "$CURL_LOG" && ! grep -v "stdin:" "$CURL_LOG" | grep seed-key >/dev/null'
else
  echo "  skip (no jq)"
fi

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
