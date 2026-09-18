#!/usr/bin/env bash
# Tests fuer das Faktenblatt-Muster in workflow-pool/mysuricates-health-check.json und
# workflow-pool/mysuricates-performance-audit.json (TODO d79f9d54).
#
# Muster (wie im Architektur-Review, 29173b90): die Shell misst deterministisch und gibt ein
# Faktenblatt aus ("FAKT <name>: <wert>", Luecken als "NICHT GEMESSEN: <name> (<grund>)"),
# die KI interpretiert nur. Dieser Test pinnt drei Zusagen:
#   1. der Faktenblatt-Block misst die Groessen wirklich (Fixture mit bekannten Zahlen),
#   2. er markiert nicht Messbares als "NICHT GEMESSEN" statt zu raten,
#   3. der Gate-Block faellt auf RED/audit-invalid, sobald eine Pflicht-FAKT-Zeile fehlt —
#      ohne diese Pruefung waere ein gruener KI-Report ohne Messwerte moeglich.
#
# Run: bash scripts/tests/test-audit-facts.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
HEALTH="$REPO/workflow-pool/mysuricates-health-check.json"
PERF="$REPO/workflow-pool/mysuricates-performance-audit.json"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# ---------------------------------------------------------------------------
# Rendern wie TemplateResolver.resolveShellSafe: jeder Platzhalter wird single-quoted
# eingesetzt (die Bloecke fuehren "shell_escape_variables": true), damit sie lokal laufen.
# ---------------------------------------------------------------------------
render() {  # $1 = definition, $2 = block-selektor (name:<n> | label:<prefix>), $3 = werte als JSON
  python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
kind, _, wanted = sys.argv[2].partition(':')
values = json.loads(sys.argv[3])


def blocks(node):
    if isinstance(node, dict):
        if 'type' in node and isinstance(node.get('config'), dict):
            yield node
        for child in node.values():
            yield from blocks(child)
    elif isinstance(node, list):
        for child in node:
            yield from blocks(child)


if kind == 'name':
    found = [b for b in blocks(definition) if b.get('name') == wanted]
else:
    found = [b for b in blocks(definition) if b['config'].get('label', '').startswith(wanted)]
assert len(found) == 1, 'erwartet genau einen Block %s, gefunden: %d' % (sys.argv[2], len(found))
config = found[0]['config']
assert config.get('shell_escape_variables') is True, 'Block %s braucht shell_escape_variables' % sys.argv[2]


def substitute(match):
    key = match.group(1).strip()
    assert key in values, 'unerwarteter Platzhalter: %s' % key
    return "'" + str(values[key]).replace("'", "'\\''") + "'"


print(re.sub(r'\{\{\s*([^{}]+?)\s*}}', substitute, config['command']))
PY
}

report_prompt() {  # $1 = definition, $2 = zieldatei — Prompt des in metadata.audit.reportBlock genannten Blocks
  python3 - "$1" <<'PY' > "$2"
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
report = definition['metadata']['audit']['reportBlock']
print(next(b['config']['command'] for b in definition['blocks'] if b.get('name') == report))
PY
}

json_values() {  # liest key=wert-Paare (wert = datei) und baut das JSON fuer render()
  python3 - "$@" <<'PY'
import json, sys
out = {}
for pair in sys.argv[1:]:
    key, _, source = pair.partition('=')
    out[key] = open(source, encoding='utf-8').read().rstrip('\n') if source else ''
print(json.dumps(out))
PY
}

# ---------------------------------------------------------------------------
# Fixture: vier Java-Klassen mit bekannten Eigenschaften, Surefire-Reports, Migration,
# Frontend-Bundle, Logdatei und DB-Datei.
# ---------------------------------------------------------------------------
FIX="$TMP/fixture"
MAIN="$FIX/ai-automation-backend/src/main/java/com/automation"
mkdir -p "$MAIN/service" "$MAIN/repository" \
         "$FIX/ai-automation-backend/src/test/java/com/automation" \
         "$FIX/ai-automation-backend/src/main/resources/db/migration" \
         "$FIX/ai-automation-backend/target/surefire-reports" \
         "$FIX/ai-automation-backend/data/logs" \
         "$FIX/ai-automation-frontend/src" "$FIX/ai-automation-frontend/dist/assets" \
         "$FIX/docs"

cat > "$MAIN/service/SlowService.java" <<'JAVA'
package com.automation.service;

class SlowService {
    @Transactional
    public void syncAll() {
        restTemplate.getForObject("http://example.invalid", String.class);
    }
}
JAVA

cat > "$MAIN/service/LoopService.java" <<'JAVA'
package com.automation.service;

class LoopService {
    public void collect(java.util.List<String> ids) {
        for (String id : ids) {
            thingRepository.findById(id);
        }
    }
}
JAVA

cat > "$MAIN/repository/ThingRepository.java" <<'JAVA'
package com.automation.repository;

interface ThingRepository {
    java.util.List<Object> findByOwnerName(String ownerName);
    java.util.List<Object> findByThingIdAndStatus(String thingId, String status);
}
JAVA

{ echo "package com.automation;"; echo "class Big {"
  for i in $(seq 1 397); do echo "    // filler $i"; done
  echo "}"; } > "$MAIN/Big.java"

echo "class BigTest {}" > "$FIX/ai-automation-backend/src/test/java/com/automation/BigTest.java"
echo "CREATE INDEX idx_thing_thing_id ON thing(thing_id);" \
  > "$FIX/ai-automation-backend/src/main/resources/db/migration/V1__init.sql"

cat > "$FIX/ai-automation-backend/target/surefire-reports/com.automation.ATest.txt" <<'TXT'
-------------------------------------------------------------------------------
Test set: com.automation.ATest
-------------------------------------------------------------------------------
Tests run: 5, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 1.5 s - in com.automation.ATest
TXT
cat > "$FIX/ai-automation-backend/target/surefire-reports/com.automation.BTest.txt" <<'TXT'
-------------------------------------------------------------------------------
Test set: com.automation.BTest
-------------------------------------------------------------------------------
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.5 s - in com.automation.BTest
bTest  Time elapsed: 0.2 s  <<< FAILURE!
TXT

head -c 4096 /dev/zero | tr '\0' 'x' > "$FIX/ai-automation-frontend/dist/assets/index-abc.js"
echo "export const x = 1;" > "$FIX/ai-automation-frontend/src/x.ts"
head -c 2048 /dev/zero | tr '\0' 'y' > "$FIX/ai-automation-backend/data/automation.db"
TODAY="$(date +%F)"
{ echo "$TODAY 10:00:00 [main] INFO  com.automation.Boot - Starte Kontext"
  echo "$TODAY 10:00:01 [virtual-7] ERROR com.automation.Run - request timed out after 300s"
} > "$FIX/ai-automation-backend/data/logs/mysuricates.log"
echo "# Doku" > "$FIX/docs/x.md"
git init -q "$FIX" && ( cd "$FIX" && git -c user.name=t -c user.email=t@example.invalid add -A \
  && git -c user.name=t -c user.email=t@example.invalid commit -q -m init )

# Blockausgaben der Messbloecke, wie der Health-Check sie liefert
cat > "$TMP/tests-out.txt" <<TXT
mvn test: exit 0 (Log: $TMP/backend.log, 120 Zeilen)
Surefire: 7 Tests, 1 Failures, 0 Errors, 1 Skipped
TXT
cat > "$TMP/build-out.txt" <<TXT
npm run build: exit 0 (Log: $TMP/build.log)
dist/assets/index-abc.js   4.0 kB
TXT
printf 'vite v7 building for production...\n' > "$TMP/build.log"
cat > "$TMP/lint-out.txt" <<'TXT'
npm run lint: exit 1

/repo/src/a.tsx
  1:1  error    kaputt      no-unused-vars
  2:1  warning  unschoen    react-hooks/exhaustive-deps

✖ 4 problems (1 error, 3 warnings)
TXT
cat > "$TMP/metrics-out.txt" <<'TXT'
  CODE-METRIKEN
Backend: 4 Sources, 1 Tests (25.0%)
found 0 vulnerabilities
Backend: UP
TXT
: > "$TMP/empty.txt"

report_prompt "$HEALTH" "$TMP/health-prompt.txt"
report_prompt "$PERF" "$TMP/perf-prompt.txt"

echo "1) Health-Check: Faktenblatt misst die Groessen deterministisch"
printf '%s\n' "$FIX" > "$TMP/base.txt"

# Das Backend misst die Request-Kosten selbst und reicht sie als Kontextvariable request_metrics
# herein (RequestMetricsFactSheet). Hier steht stellvertretend, was es liefert — der Faktenblatt-
# Block darf sie nur durchreichen, nicht selbst messen.
cat > "$TMP/request-metrics.txt" <<'REQMETRICS'
FAKT request_metrics_routes_measured: 2
FAKT request_metrics_slowest_route: GET /api/v1/todos
FAKT request_metrics_slowest_p95_ms: 320
FAKT request_metrics_max_sql_route: GET /api/v1/workflows
FAKT request_metrics_max_sql_statements: 47
FAKT request_metrics_max_wait_share_percent: 41.2
FAKT request_metrics_max_response_bytes: 3061816
FAKT request_metrics_drifting_routes: 1
FAKT request_metrics_drift_detail: GET /api/v1/todos x7.5
REQMETRICS
VALUES="$(json_values execution_path="$TMP/base.txt" \
  block.backend_tests.output="$TMP/tests-out.txt" \
  block.frontend_build.output="$TMP/build-out.txt" \
  block.frontend_lint.output="$TMP/lint-out.txt" \
  block.metrics.output="$TMP/metrics-out.txt")"
render "$HEALTH" name:facts "$VALUES" > "$TMP/health-facts.sh" 2> "$TMP/health-facts-render.err"
check "Faktenblatt-Block 'facts' extrahierbar" '[ -s "$TMP/health-facts.sh" ]'
bash "$TMP/health-facts.sh" > "$TMP/health-facts.out" 2> "$TMP/health-facts.err"
check "laeuft ohne stderr" '[ ! -s "$TMP/health-facts.err" ]'
check "FAKT backend_tests_exit: 0"           'grep -qx "FAKT backend_tests_exit: 0" "$TMP/health-facts.out"'
check "FAKT maven_build_result: SUCCESS"     'grep -qx "FAKT maven_build_result: BUILD SUCCESS" "$TMP/health-facts.out"'
check "FAKT backend_tests_total: 7"          'grep -qx "FAKT backend_tests_total: 7" "$TMP/health-facts.out"'
check "FAKT backend_tests_failures: 1"       'grep -qx "FAKT backend_tests_failures: 1" "$TMP/health-facts.out"'
check "FAKT backend_tests_skipped: 1"        'grep -qx "FAKT backend_tests_skipped: 1" "$TMP/health-facts.out"'
check "FAKT backend_test_classes_failed: 1"  'grep -qx "FAKT backend_test_classes_failed: 1" "$TMP/health-facts.out"'
# 1.5 s + 0.5 s aus den beiden "Tests run:"-Zeilen; die 0.2 s der FAILURE-Einzelzeile zaehlen nicht mit.
check "FAKT backend_tests_seconds: 2.0"      'grep -qx "FAKT backend_tests_seconds: 2.0" "$TMP/health-facts.out"'
check "FAKT frontend_build_exit: 0"          'grep -qx "FAKT frontend_build_exit: 0" "$TMP/health-facts.out"'
check "FAKT frontend_lint_exit: 1"           'grep -qx "FAKT frontend_lint_exit: 1" "$TMP/health-facts.out"'
check "FAKT frontend_lint_errors: 1"         'grep -qx "FAKT frontend_lint_errors: 1" "$TMP/health-facts.out"'
check "FAKT frontend_lint_warnings: 3"       'grep -qx "FAKT frontend_lint_warnings: 3" "$TMP/health-facts.out"'
check "FAKT backend_sources: 4"              'grep -qx "FAKT backend_sources: 4" "$TMP/health-facts.out"'
check "FAKT files_over_300: 1"               'grep -qx "FAKT files_over_300: 1" "$TMP/health-facts.out"'
check "FAKT frontend_bundle_js_bytes: 4096"  'grep -qx "FAKT frontend_bundle_js_bytes: 4096" "$TMP/health-facts.out"'
check "FAKT backend_actuator_status: UP"     'grep -qx "FAKT backend_actuator_status: UP" "$TMP/health-facts.out"'
check "FAKT npm_audit gemessen"              'grep -q "^FAKT npm_audit: .*vulnerabilities" "$TMP/health-facts.out"'
check "FAKT docs_files: 1"                   'grep -qx "FAKT docs_files: 1" "$TMP/health-facts.out"'
check "Pflichtfeld-Liste im Blatt"           'grep -q "^PFLICHT-FAKTEN: " "$TMP/health-facts.out"'

echo "2) Health-Check: Luecken werden markiert statt geraten"
check "step_duration_seconds als Luecke" \
  'grep -q "^NICHT GEMESSEN: step_duration_seconds (" "$TMP/health-facts.out"'
check "Architektur-Kopplung als Luecke" \
  'grep -q "^NICHT GEMESSEN: architecture_coupling (" "$TMP/health-facts.out"'
EMPTY_VALUES="$(json_values execution_path="$TMP/base.txt" \
  block.backend_tests.output="$TMP/empty.txt" \
  block.frontend_build.output="$TMP/empty.txt" \
  block.frontend_lint.output="$TMP/empty.txt" \
  block.metrics.output="$TMP/empty.txt")"
render "$HEALTH" name:facts "$EMPTY_VALUES" > "$TMP/health-facts-empty.sh"
bash "$TMP/health-facts-empty.sh" > "$TMP/health-facts-empty.out" 2> "$TMP/health-facts-empty.err"
check "ohne Messbloecke: kein erfundener Exit-Code" \
  '! grep -q "^FAKT backend_tests_exit: " "$TMP/health-facts-empty.out"'
check "ohne Messbloecke: Luecke benannt" \
  'grep -q "^NICHT GEMESSEN: backend_tests_exit (" "$TMP/health-facts-empty.out"'
check "ohne Messbloecke: Plattenmessung bleibt" \
  'grep -qx "FAKT backend_sources: 4" "$TMP/health-facts-empty.out"'
check "ohne Messbloecke: keine stille Null bei tsc" \
  '! grep -q "^FAKT frontend_tsc_errors: " "$TMP/health-facts-empty.out" \
     && grep -q "^NICHT GEMESSEN: frontend_tsc_errors (" "$TMP/health-facts-empty.out"'
check "ohne Messbloecke laeuft ohne stderr" '[ ! -s "$TMP/health-facts-empty.err" ]'

# Abgebrochener metrics-Block: seine erste Zeile beginnt ebenfalls mit "Backend: ", enthaelt aber
# Quellzahlen statt eines Actuator-Status. Ein freies "tail -1" wuerde daraus einen Gesundheits-
# status erfinden — hier muss eine Luecke stehen.
printf '  CODE-METRIKEN\nBackend: 767 Sources, 723 Tests (94.3%%)\n' > "$TMP/metrics-abbruch.txt"
ABBRUCH_VALUES="$(json_values execution_path="$TMP/base.txt" \
  block.backend_tests.output="$TMP/tests-out.txt" \
  block.frontend_build.output="$TMP/build-out.txt" \
  block.frontend_lint.output="$TMP/lint-out.txt" \
  block.metrics.output="$TMP/metrics-abbruch.txt")"
render "$HEALTH" name:facts "$ABBRUCH_VALUES" > "$TMP/health-facts-abbruch.sh"
bash "$TMP/health-facts-abbruch.sh" > "$TMP/health-facts-abbruch.out" 2>/dev/null
check "abgebrochener metrics-Block: kein erfundener Status" \
  '! grep -q "^FAKT backend_actuator_status: " "$TMP/health-facts-abbruch.out"'
check "abgebrochener metrics-Block: Luecke benannt" \
  'grep -q "^NICHT GEMESSEN: backend_actuator_status (" "$TMP/health-facts-abbruch.out"'
check "abgebrochener metrics-Block: npm_audit ebenfalls Luecke" \
  'grep -q "^NICHT GEMESSEN: npm_audit (" "$TMP/health-facts-abbruch.out"'
printf 'Backend: DOWN\n' > "$TMP/metrics-down.txt"
DOWN_VALUES="$(json_values execution_path="$TMP/base.txt" \
  block.backend_tests.output="$TMP/tests-out.txt" \
  block.frontend_build.output="$TMP/build-out.txt" \
  block.frontend_lint.output="$TMP/lint-out.txt" \
  block.metrics.output="$TMP/metrics-down.txt")"
render "$HEALTH" name:facts "$DOWN_VALUES" > "$TMP/health-facts-down.sh"
bash "$TMP/health-facts-down.sh" > "$TMP/health-facts-down.out" 2>/dev/null
check "Statuswert DOWN wird uebernommen" \
  'grep -qx "FAKT backend_actuator_status: DOWN" "$TMP/health-facts-down.out"'

echo "3) Health-Check: Rohlogs nur gekuerzt (80 Zeilen je Block)"
{ echo "mvn test: exit 1 (Log: /nirgends, 200 Zeilen)"
  for i in $(seq 1 200); do echo "logzeile $i"; done; } > "$TMP/long-tests-out.txt"
LONG_VALUES="$(json_values execution_path="$TMP/base.txt" \
  block.backend_tests.output="$TMP/long-tests-out.txt" \
  block.frontend_build.output="$TMP/build-out.txt" \
  block.frontend_lint.output="$TMP/lint-out.txt" \
  block.metrics.output="$TMP/metrics-out.txt")"
render "$HEALTH" name:facts "$LONG_VALUES" > "$TMP/health-facts-long.sh"
bash "$TMP/health-facts-long.sh" > "$TMP/health-facts-long.out" 2>/dev/null
awk '/^## ROHLOG backend_tests/{f=1;next} f && /^## ROHLOG /{f=0} f' "$TMP/health-facts-long.out" \
  | grep -c . > "$TMP/rohlog-count.txt"
check "Rohlog backend_tests auf 80 Zeilen gekuerzt" '[ "$(cat "$TMP/rohlog-count.txt")" -eq 80 ]'
check "Rohlog endet mit der letzten Zeile"          'grep -qx "logzeile 200" "$TMP/health-facts-long.out"'
check "Rohlog beginnt nicht am Anfang"              '! grep -qx "logzeile 1" "$TMP/health-facts-long.out"'
check "FAKT maven_build_result: FAILURE bei exit 1" 'grep -qx "FAKT maven_build_result: BUILD FAILURE" "$TMP/health-facts-long.out"'

echo "4) Health-Check: Gate prueft die Pflicht-Fakten deterministisch"
printf 'Kurzfassung\ngate_status: GREEN\n' > "$TMP/prev-green.txt"
run_health_gate() {  # $1 = faktenblatt-datei
  GATE_VALUES="$(json_values previous_output="$TMP/prev-green.txt" block.facts.output="$1")"
  render "$HEALTH" label:Gate-Verdict "$GATE_VALUES" > "$TMP/health-gate.sh"
  bash "$TMP/health-gate.sh" > "$TMP/health-gate.out" 2> "$TMP/health-gate.err"
}
run_health_gate "$TMP/health-facts.out"
check "vollstaendiges Blatt: GREEN durchgereicht" 'grep -qx "gate_status: GREEN" "$TMP/health-gate.out"'
check "vollstaendiges Blatt: project-health"      'grep -qx "gate_category: project-health" "$TMP/health-gate.out"'
check "Gate laeuft ohne stderr"                   '[ ! -s "$TMP/health-gate.err" ]'
# Je Pflichtfeld ein eigener RED-Lauf. Die Liste kommt aus der PFLICHT-FAKTEN-Zeile des
# Faktenblatts; damit faellt auf, wenn die drei redundanten Listen (Echo im Messblock,
# Schleife im Gate, Aufzaehlung im Report-Prompt) auseinanderdriften.
mandatory_fields() {  # $1 = faktenblatt
  sed -n 's/^PFLICHT-FAKTEN: //p' "$1" | tr ' ' '\n' | grep -v '^$'
}
HEALTH_FIELDS="$(mandatory_fields "$TMP/health-facts.out")"
check "Health: Pflichtfeld-Liste nicht leer" '[ -n "$HEALTH_FIELDS" ]'
for FIELD in $HEALTH_FIELDS; do
  grep -v "^FAKT $FIELD: " "$TMP/health-facts.out" > "$TMP/health-facts-luecke.out"
  run_health_gate "$TMP/health-facts-luecke.out"
  check "ohne $FIELD: RED/audit-invalid mit Begruendung" \
    'grep -qx "gate_status: RED" "$TMP/health-gate.out" \
       && grep -qx "gate_category: audit-invalid" "$TMP/health-gate.out" \
       && grep "^gate_description:" "$TMP/health-gate.out" | grep -q "$FIELD"'
  check "Report-Prompt nennt Pflichtfeld $FIELD" 'grep -q "$FIELD" "$TMP/health-prompt.txt"'
done
run_health_gate "$TMP/empty.txt"
check "ohne Faktenblatt: RED"                     'grep -qx "gate_status: RED" "$TMP/health-gate.out"'
check "ohne Faktenblatt: audit-invalid"           'grep -qx "gate_category: audit-invalid" "$TMP/health-gate.out"'

echo "5) Health-Check: Report-Prompt bindet die KI ans Faktenblatt"
check "Prompt nutzt das Faktenblatt"      'grep -q "block.facts.output" "$TMP/health-prompt.txt"'
check "Prompt verbietet eigenes Messen"   'grep -qi "miss und zaehle nichts selbst" "$TMP/health-prompt.txt"'
check "Prompt kennt NICHT GEMESSEN"       'grep -q "NICHT GEMESSEN" "$TMP/health-prompt.txt"'
check "Prompt ohne rohe step_N_outputs"   '! grep -q "step_0_output\|step_1_output\|step_2_output\|step_3_output" "$TMP/health-prompt.txt"'

echo "6) Performance-Audit: Faktenblatt misst Quelltext-Muster statt zu schaetzen"
PERF_VALUES="$(json_values project_path="$TMP/base.txt" request_metrics="$TMP/request-metrics.txt")"
render "$PERF" name:facts "$PERF_VALUES" > "$TMP/perf-facts.sh" 2> "$TMP/perf-facts-render.err"
check "Faktenblatt-Block 'facts' extrahierbar" '[ -s "$TMP/perf-facts.sh" ]'
bash "$TMP/perf-facts.sh" > "$TMP/perf-facts.out" 2> "$TMP/perf-facts.err"
check "laeuft ohne stderr"                     '[ ! -s "$TMP/perf-facts.err" ]'
check "FAKT source_files_scanned: 4"           'grep -qx "FAKT source_files_scanned: 4" "$TMP/perf-facts.out"'
check "FAKT transactional_slow_call_count: 1"  'grep -qx "FAKT transactional_slow_call_count: 1" "$TMP/perf-facts.out"'
check "Treffer nennt SlowService mit Zeile" \
  'grep -q "^TREFFER transactional_slow_call: service/SlowService.java:[0-9]" "$TMP/perf-facts.out"'
check "FAKT repository_loop_count: 1"          'grep -qx "FAKT repository_loop_count: 1" "$TMP/perf-facts.out"'
check "Treffer nennt LoopService mit Zeile" \
  'grep -q "^TREFFER repository_in_loop: service/LoopService.java:[0-9]" "$TMP/perf-facts.out"'
check "FAKT unindexed_findby_columns_count: 2" 'grep -qx "FAKT unindexed_findby_columns_count: 2" "$TMP/perf-facts.out"'
check "owner_name als Kandidat gelistet"       'grep -q "^TREFFER unindexed_findby_column: owner_name" "$TMP/perf-facts.out"'
check "indizierte Spalte nicht gemeldet"       '! grep -q "^TREFFER unindexed_findby_column: thing_id" "$TMP/perf-facts.out"'
check "FAKT migration_index_statements: 1"     'grep -qx "FAKT migration_index_statements: 1" "$TMP/perf-facts.out"'
check "FAKT database_bytes: 2048"              'grep -qx "FAKT database_bytes: 2048" "$TMP/perf-facts.out"'
check "FAKT frontend_bundle_js_bytes: 4096"    'grep -qx "FAKT frontend_bundle_js_bytes: 4096" "$TMP/perf-facts.out"'
check "FAKT main_thread_log_lines: 1"          'grep -qx "FAKT main_thread_log_lines: 1" "$TMP/perf-facts.out"'
check "FAKT non_main_runtime_log_lines: 1"     'grep -qx "FAKT non_main_runtime_log_lines: 1" "$TMP/perf-facts.out"'
check "FAKT non_main_runtime_timeout_lines: 1" 'grep -qx "FAKT non_main_runtime_timeout_lines: 1" "$TMP/perf-facts.out"'
check "step_results_bytes als Luecke"          'grep -q "^NICHT GEMESSEN: step_results_bytes (" "$TMP/perf-facts.out"'
check "JVM entweder gemessen oder Luecke" \
  'grep -q "^FAKT jvm_memory_used_bytes: \|^NICHT GEMESSEN: jvm_memory_used_bytes (" "$TMP/perf-facts.out"'

# Request-Kosten: das Backend misst, der Shell-Block reicht nur durch (CLAUDE.md — Shell-Bloecke
# rufen die REST-API nicht auf).
check "Request-Kosten im Blatt"        'grep -qx "FAKT request_metrics_slowest_route: GET /api/v1/todos" "$TMP/perf-facts.out"'
check "SQL-Zahl im Blatt"              'grep -qx "FAKT request_metrics_max_sql_statements: 47" "$TMP/perf-facts.out"'
check "Wartezeit-Anteil im Blatt"      'grep -qx "FAKT request_metrics_max_wait_share_percent: 41.2" "$TMP/perf-facts.out"'
check "Drift im Blatt"                 'grep -qx "FAKT request_metrics_drift_detail: GET /api/v1/todos x7.5" "$TMP/perf-facts.out"'
check "Blatt ruft die REST-API nicht"  '! grep -q "localhost:9090/api/v1" "$TMP/perf-facts.sh"'
check "Pflichtfeld-Liste im Blatt"             'grep -q "^PFLICHT-FAKTEN: " "$TMP/perf-facts.out"'

echo "7) Performance-Audit: Gate prueft die Pflicht-Fakten deterministisch"
run_perf_gate() {  # $1 = faktenblatt-datei
  GATE_VALUES="$(json_values previous_output="$TMP/prev-green.txt" block.facts.output="$1")"
  render "$PERF" label:Gate-Verdict "$GATE_VALUES" > "$TMP/perf-gate.sh"
  bash "$TMP/perf-gate.sh" > "$TMP/perf-gate.out" 2> "$TMP/perf-gate.err"
}
run_perf_gate "$TMP/perf-facts.out"
check "vollstaendiges Blatt: GREEN durchgereicht" 'grep -qx "gate_status: GREEN" "$TMP/perf-gate.out"'
check "vollstaendiges Blatt: performance"         'grep -qx "gate_category: performance" "$TMP/perf-gate.out"'
check "Gate laeuft ohne stderr"                   '[ ! -s "$TMP/perf-gate.err" ]'
# Je Pflichtfeld ein eigener RED-Lauf, strukturgleich zu Abschnitt 4: auch hier stehen die
# Pflichtfelder dreimal (Echo im Messblock, Schleife im Gate, Aufzaehlung im Report-Prompt),
# und nur der Abgleich ueber die PFLICHT-FAKTEN-Zeile merkt, wenn eine Liste driftet.
PERF_FIELDS="$(mandatory_fields "$TMP/perf-facts.out")"
check "Performance: Pflichtfeld-Liste nicht leer" '[ -n "$PERF_FIELDS" ]'
for FIELD in $PERF_FIELDS; do
  grep -v "^FAKT $FIELD: " "$TMP/perf-facts.out" > "$TMP/perf-facts-luecke.out"
  run_perf_gate "$TMP/perf-facts-luecke.out"
  check "ohne $FIELD: RED/audit-invalid mit Begruendung" \
    'grep -qx "gate_status: RED" "$TMP/perf-gate.out" \
       && grep -qx "gate_category: audit-invalid" "$TMP/perf-gate.out" \
       && grep "^gate_description:" "$TMP/perf-gate.out" | grep -q "$FIELD"'
  check "Report-Prompt nennt Pflichtfeld $FIELD" 'grep -q "$FIELD" "$TMP/perf-prompt.txt"'
done
run_perf_gate "$TMP/empty.txt"
check "ohne Faktenblatt: RED"           'grep -qx "gate_status: RED" "$TMP/perf-gate.out"'
check "ohne Faktenblatt: audit-invalid" 'grep -qx "gate_category: audit-invalid" "$TMP/perf-gate.out"'

echo "8) Performance-Audit: Report-Prompt bindet die KI ans Faktenblatt"
check "Prompt nutzt das Faktenblatt"    'grep -q "block.facts.output" "$TMP/perf-prompt.txt"'
check "Prompt verbietet eigenes Messen" 'grep -qi "schaetze nichts selbst" "$TMP/perf-prompt.txt"'
check "Prompt kennt NICHT GEMESSEN"     'grep -q "NICHT GEMESSEN" "$TMP/perf-prompt.txt"'

echo "9) Alle Shell-Bloecke beider Definitionen sind syntaktisch gueltig (bash -n)"
python3 - "$HEALTH" "$PERF" "$TMP/syntax" <<'PY'
import json, os, re, sys
os.makedirs(sys.argv[3], exist_ok=True)
names = []
for path in sys.argv[1:3]:
    definition = json.load(open(path, encoding='utf-8'))
    stem = os.path.basename(path).replace('.json', '')
    for index, block in enumerate(definition['blocks']):
        if block.get('type') != 'shell':
            continue
        command = re.sub(r'\{\{\s*([^{}]+?)\s*}}', "'platzhalter'", block['config']['command'])
        name = '%s-%d.sh' % (stem, index)
        open(os.path.join(sys.argv[3], name), 'w', encoding='utf-8').write(command)
        names.append(name)
open(os.path.join(sys.argv[3], 'blocks.txt'), 'w', encoding='utf-8').write('\n'.join(names))
PY
SYNTAX_FAILED=0
for SCRIPT in "$TMP"/syntax/*.sh; do
  bash -n "$SCRIPT" 2>> "$TMP/syntax.err" || SYNTAX_FAILED=$((SYNTAX_FAILED + 1))
done
check "bash -n fuer jeden Shell-Block" '[ "$SYNTAX_FAILED" -eq 0 ]'
check "mindestens 8 Shell-Bloecke geprueft" '[ "$(ls "$TMP"/syntax/*.sh | wc -l)" -ge 8 ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
