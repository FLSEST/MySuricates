#!/usr/bin/env bash
# Tests for scripts/public-readiness/arch-metrics.sh and arch_cycles.py (TODO 29173b90).
# Run: bash scripts/tests/test-arch-metrics.sh   (exit 0 = all checks ok)
#
# Time budget: the run against the real repo starts, if
# ai-automation-backend/target/classes is missing, a one-off `./mvnw -q -DskipTests compile` (~1-2 minutes).
# After that both passes run from the existing target/classes (~5 s per run).
# The fixture runs (sections 6 and 7) need only javac and take seconds.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/arch-metrics.sh"
CYCLES="$REPO/scripts/public-readiness/arch_cycles.py"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# No test run writes to the developer's real compile cache.
export AUTOMATION_ARCH_CACHE="$TMP/arch-cache-default"
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

expect_cycles() {  # $1 = label, $2 = edge list, $3 = expected output
  printf '%s' "$2" > "$TMP/edges.tsv"
  python3 "$CYCLES" < "$TMP/edges.tsv" > "$TMP/cycles.out" 2> "$TMP/cycles.err"
  local rc=$?
  printf '%s' "$3" > "$TMP/cycles.exp"
  if [ "$rc" -eq 0 ] && diff -u "$TMP/cycles.exp" "$TMP/cycles.out" > "$TMP/cycles.diff" 2>&1; then
    echo "  ok   $1"; PASS=$((PASS + 1))
  else
    echo "  FAIL $1 (rc=$rc)"; sed -n '1,20p' "$TMP/cycles.diff"; sed -n '1,5p' "$TMP/cycles.err"
    FAIL=$((FAIL + 1))
  fi
}

# Everything before "## run" is the measured, deterministic part; "## run" carries the
# runtime state (compile=/jdeps=) and is explicitly not comparable.
measured() { sed '/^## run$/,$d' "$1"; }

echo "0) Dateien vorhanden"
check "arch-metrics.sh existiert"  '[ -f "$SCRIPT" ]'
check "arch_cycles.py existiert"   '[ -f "$CYCLES" ]'
check "arch-metrics.sh ausfuehrbar" '[ -x "$SCRIPT" ]'
check "Testskript ausfuehrbar"      '[ -x "$HERE/test-arch-metrics.sh" ]'
if [ ! -f "$SCRIPT" ] || [ ! -f "$CYCLES" ]; then
  echo; echo "passed=$PASS failed=$FAIL"; exit 1
fi

echo "1) Zyklen-Erkennung auf Fixture-Kantenlisten"
expect_cycles "azyklisch: keine Ausgabe" \
  "a${TAB}b${TAB}3
b${TAB}c${TAB}1
" \
  ""

expect_cycles "wechselseitiges Paar: Zyklus + pair" \
  "a${TAB}b${TAB}2
b${TAB}a${TAB}1
" \
  "cycle${TAB}2${TAB}a,b
pair${TAB}a${TAB}b
"

expect_cycles "Dreier-Zyklus ohne Paar" \
  "a${TAB}b
b${TAB}c
c${TAB}a
" \
  "cycle${TAB}3${TAB}a,b,c
"

expect_cycles "Selbstkante zaehlt als Zyklus" \
  "x${TAB}x
a${TAB}b
" \
  "cycle${TAB}1${TAB}x
"

expect_cycles "Kommentare und Leerzeilen werden ignoriert" \
  "# von${TAB}nach${TAB}anzahl

a${TAB}b
b${TAB}a
" \
  "cycle${TAB}2${TAB}a,b
pair${TAB}a${TAB}b
"

expect_cycles "zwei Komponenten: groesste zuerst, Mitglieder sortiert" \
  "p.z${TAB}p.y
p.y${TAB}p.z
p.c${TAB}p.b
p.b${TAB}p.a
p.a${TAB}p.c
p.a${TAB}p.solo
" \
  "cycle${TAB}3${TAB}p.a,p.b,p.c
cycle${TAB}2${TAB}p.y,p.z
pair${TAB}p.y${TAB}p.z
"

printf 'b\ta\nc\ta\na\tb\na\tc\n'  | python3 "$CYCLES" > "$TMP/mix1.out" 2>/dev/null
printf 'a\tc\na\tb\nc\ta\nb\ta\n'  | python3 "$CYCLES" > "$TMP/mix2.out" 2>/dev/null
check "Eingabereihenfolge aendert die Ausgabe nicht" 'diff -q "$TMP/mix1.out" "$TMP/mix2.out" >/dev/null'
check "gemischte Eingabe findet den Zyklus"          'grep -q "^cycle" "$TMP/mix1.out"'

echo "2) Skriptlauf gegen das Repository"
bash "$SCRIPT" "$REPO" > "$TMP/run1.txt" 2> "$TMP/run1.err"; RC1=$?
check "exit 0"                      '[ "$RC1" -eq 0 ]'
check "Ausgabe nicht leer"          '[ -s "$TMP/run1.txt" ]'
for s in packages cycles sizes churn recent coupling files ratchet summary run; do
  check "Abschnitt ## $s vorhanden" "grep -qx '## $s' '$TMP/run1.txt'"
done
check "recent liefert aenderungen/neu/Pfad (oder sagt, dass nichts geaendert wurde)" \
  "sed -n '/^## recent\$/,/^## coupling\$/p' '$TMP/run1.txt' | grep -E '^([0-9]+${TAB}[01]${TAB}com/automation/.*\.java|# keine Aenderungen gemessen)\$' >/dev/null"
for k in packages edges cycles files files_over_300 files_over_500 churn_files_90d; do
  check "summary-Schluessel $k" "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^$k=[0-9]+\$' >/dev/null"
done
check "summary nennt cycles_state" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^cycles_state=(ok|failed|no_edges)\$' >/dev/null"
check "compile= steht in ## run, nicht in ## summary" \
  "sed -n '/^## run\$/,\$p' '$TMP/run1.txt' | grep -E '^compile=' >/dev/null && ! sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^compile=' >/dev/null"
check "jdeps= steht in ## run, nicht in ## summary" \
  "sed -n '/^## run\$/,\$p' '$TMP/run1.txt' | grep -E '^jdeps=' >/dev/null && ! sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^jdeps=' >/dev/null"
check "packages liefert com.automation-Kanten" \
  "grep -cE '^com\.automation[^${TAB}]*${TAB}com\.automation[^${TAB}]*${TAB}[0-9]+\$' '$TMP/run1.txt' | grep -vx 0 >/dev/null"
check "sizes liefert Zeilen/Pfad-Paare" \
  "sed -n '/^## sizes\$/,/^## churn\$/p' '$TMP/run1.txt' | grep -E '^[0-9]+${TAB}com/automation/.*\.java\$' >/dev/null"
check "coupling liefert Paket-Kennzahlen" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/run1.txt' | grep -E '^com\.automation[^${TAB}]*(${TAB}[0-9]+){4}\$' >/dev/null"
check "keine absoluten Benutzerpfade in der Ausgabe" \
  "! grep -qE '(/home/|/Users/)[a-zA-Z_-]+/' '$TMP/run1.txt'"
check "packages>0 in summary" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^packages=[1-9][0-9]*\$' >/dev/null"
check "files>0 in summary" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -E '^files=[1-9][0-9]*\$' >/dev/null"

echo "3) Zwei Laeufe liefern identische Messung (Determinismus)"
bash "$SCRIPT" "$REPO" > "$TMP/run2.txt" 2> "$TMP/run2.err"; RC2=$?
measured "$TMP/run1.txt" > "$TMP/run1.measured"; measured "$TMP/run2.txt" > "$TMP/run2.measured"
check "zweiter Lauf exit 0"                  '[ "$RC2" -eq 0 ]'
check "gemessener Teil byte-identisch"       'diff -q "$TMP/run1.measured" "$TMP/run2.measured" >/dev/null'
if ! diff -q "$TMP/run1.measured" "$TMP/run2.measured" >/dev/null 2>&1; then
  diff -u "$TMP/run1.measured" "$TMP/run2.measured" | sed -n 1,20p
fi

echo "4) Unbrauchbares Wurzelverzeichnis wird gemeldet statt still ignoriert"
OUT=$(bash "$SCRIPT" "$TMP/gibt-es-nicht" 2>&1); RC=$?
check "exit 2 bei fehlendem Verzeichnis" '[ "$RC" -eq 2 ]'
check "Meldung nennt das Verzeichnis"    'grep -q "gibt-es-nicht" <<<"$OUT"'

mkdir -p "$TMP/leer"
bash "$SCRIPT" "$TMP/leer" > "$TMP/leer.out" 2> "$TMP/leer.err"; RC=$?
check "leeres Verzeichnis: exit 0"            '[ "$RC" -eq 0 ]'
check "leeres Verzeichnis: summary vorhanden" 'grep -qx "## summary" "$TMP/leer.out"'
check "leeres Verzeichnis: error-Markierung"  'grep -qE "^error=" "$TMP/leer.out"'

echo "5) Pool-Workflow ruft Mess- und Einstufungsskript auf und uebergibt beides an den KI-Block"
POOL="$REPO/workflow-pool/mysuricates-architektur-review.json"
check "Pool-Datei ist valides JSON"      'python3 -m json.tool "$POOL" > /dev/null'
check "Shell-Block ruft arch-metrics.sh" 'grep -q "arch-metrics.sh" "$POOL"'
check "Pool-Vertrag (Reihenfolge, Name, Prompt)" 'python3 - "$POOL" <<'"'"'PY'"'"'
import json, sys
wf = json.load(open(sys.argv[1]))
blocks = wf["blocks"]
# By name: since b8c06b94 the arch_metrics block also calls frontend-metrics.sh (for the grading).
i_metrics = next(i for i, b in enumerate(blocks) if b.get("name") == "arch_metrics")
assert "arch-metrics.sh" in blocks[i_metrics]["config"].get("command", ""), "Block arch_metrics ruft arch-metrics.sh nicht auf"
i_report = next(i for i, b in enumerate(blocks) if b.get("name") == "report")
assert i_metrics < i_report, "Metrik-Block muss vor dem Report-Block liegen"
metrics = blocks[i_metrics]
name = metrics.get("name")
assert name, "Metrik-Block braucht einen Namen"
assert metrics["config"].get("timeout_seconds") == 600
command = metrics["config"]["command"]
assert "set -euo pipefail" in command, "Shell-Block ohne set -euo pipefail"
# b8c06b94: the grading runs in the same block, from both fact sheets.
assert "arch-findings.py" in command and "frontend-metrics.sh" in command, "Einstufung fehlt im Metrik-Block"
assert command.index("arch-findings.py") < command.index("awk"), "## findings muss vor dem Backend-Blatt stehen"
assert "not_measured=classification" in command, "fehlendes Einstufungsskript muss als nicht gemessen erscheinen"
prompt = blocks[i_report]["config"]["command"]
assert "{{ block.%s.output }}" % name in prompt, "Report-Prompt referenziert das Faktenblatt nicht"
assert "mysuricates:audit-result:v1" in prompt, "Envelope fehlt"
assert "gate_status:" in prompt, "Gate-Marker fehlt"
assert "Miss nichts selbst" in prompt, "Interpretations-Anweisung fehlt"
assert "Fehlt das Faktenblatt ganz" in prompt, "Regel fuer fehlendes Faktenblatt fehlt"
# Division of roles: findings and severity come unchanged from ## findings, the AI does not grade by itself.
assert "## findings" in prompt and "UNVERAENDERT" in prompt, "Uebernahme aus ## findings fehlt"
assert "keine eigene Schwere" in prompt, "KI darf keine eigene Schwere vergeben"
assert "not_measured" in prompt, "nicht gemessene Dimensionen fehlen im Prompt"
# The old edges are gone: no raw line edges, no package coupling as a finding.
for gone in (">200 Zeilen", ">300 Zeilen", ">5 Abhaengigkeiten", ">7 Abhaengigkeiten", "herabstufen",
             "Backend-Paket (Paketkopplung)", "Pakete und Verzeichnisse bekommen arch.high-coupling",
             "arch.duplicated-logic"):
    assert gone not in prompt, "alte Regel steht noch im Prompt: " + gone
assert "Paketkopplung" in prompt and "KEIN Befund mehr" in prompt, "Paketkopplung als Trend fehlt"
assert "Neu in 4.11.0:" in prompt, "one-off explanation of the known judgments is missing"
assert all("Neu in 4.%d.0:" % v not in prompt for v in (7, 8, 9, 10)), "earlier one-off explanations are done"
assert "Regelwechsel 4.6.0" not in prompt, "die einmalige Erklaerung von 4.6.0 ist erledigt"
assert "NCLOC" in prompt and "Hotspot" in prompt, "NCLOC-Baender und Hotspot-Regel fehlen"
assert "nur Kollaborateure" in prompt, "Zahl meint Kollaborateure"
assert wf["version"] == "4.11.0", wf["version"]
# 67128699: PMD from automation-agent-analysis measures duplicates (CPD per language, CSV) and complexity (SARIF);
# the tool blocks run before arch_metrics, which hands their outputs on to arch-findings.py.
tool_blocks = {b.get("name"): b for b in blocks if b["config"].get("container_args")}
# 2c07ce7e: the PMD block is pmd_design, because the evidence check of the platform ignores fact names under 4 characters.
assert set(tool_blocks) == {"cpd_java", "jscpd", "pmd_design", "lizard"}, set(tool_blocks)
assert all(len(name) >= 4 for name in tool_blocks), "fact prefixes must be at least 4 characters"
for tool in tool_blocks.values():
    assert tool["config"]["container_image"] == "automation-agent-analysis:latest"
    assert tool["config"].get("on_failure") == "continue", "ein fehlendes Image darf das Review nicht abbrechen"
    assert blocks.index(tool) < i_metrics, "Werkzeug-Bloecke muessen vor arch_metrics laufen"
assert "--no-fail-on-violation" in tool_blocks["cpd_java"]["config"]["container_args"]
assert tool_blocks["pmd_design"]["config"].get("output_format") == "sarif"
assert "/workspace/scripts/public-readiness/pmd-architecture.xml" in tool_blocks["pmd_design"]["config"]["container_args"]
assert "--cpd java=" in command and "--jscpd " in command and "--pmd " in command and "--lizard " in command, "Werkzeug-Fakten fehlen in der Einstufung"
# c608cdda: jscpd measures the frontend including .tsx via SARIF, lizard the complexity per frontend function.
assert tool_blocks["jscpd"]["config"].get("output_format") == "sarif" and "tsx" in ",".join(tool_blocks["jscpd"]["config"]["container_args"])
assert "-i" in tool_blocks["lizard"]["config"]["container_args"], "lizard endet sonst bei Warnungen mit Exit 1"
for name in ("cpd_java", "jscpd", "pmd_design", "lizard"):
    assert "{{ block.%s.output }}" % name in command, name
# 2c07ce7e: evidence cites the FAKT lines of ## FAKTENBLATT arch, the report states the measurement scope.
for marker in ("## FAKTENBLATT arch", "fakt-Namen der Kandidatenzeile", "**Messumfang**: <Wert von FAKT arch_scope", "nicht vergleichbar", "unbelegt"):
    assert marker in prompt, "evidence/scope rule missing in the prompt: " + marker
# ebf6602e: arch-findings.py holds every known judgment against this run; a carried-forward judgment keeps its evidence.
assert "{{ audit_findings }}" in command and "--known " in command, "known findings do not reach the classification"
for marker in ("## known_judgments", "Text hinter \"Beleg: \"", "existiert \"nein\"", "\"ungeprueft\""):
    assert marker in prompt, "known-judgment rule missing in the prompt: " + marker
assert "sonst \"unveraendert seit dem letzten Urteil\"" not in prompt, "carried-forward judgments must keep their evidence"
for marker in ("## duplicates", "## complexity", "art dublette", "frontend_complexity_state", "nie ein eigener Befund"):
    assert marker in prompt, "Werkzeug-Fakten im Prompt unvollstaendig: " + marker
# e0e9495f: the script chooses candidates, arch-seams.py measures their seams, the AI judges.
assert "ARCH_METRICS_CLASSES_OUT=" in command and "arch-seams.py" in command, "Seams fehlen im Metrik-Block"
assert command.index("python3 scripts/public-readiness/arch-findings.py") < command.index("python3 scripts/public-readiness/arch-seams.py"), "Seams brauchen die Kandidaten der Einstufung"
assert "\"## recent\"" in command, "## recent muss wie ## files aus dem Backend-Blatt gefiltert werden"
frontend = next(b for b in blocks if b.get("name") == "frontend_metrics")["config"]["command"]
assert "\"## recent\"" in frontend, "## recent muss aus dem Frontend-Blatt gefiltert werden"
for marker in ("URTEIL UEBER DIE KANDIDATEN (VERBINDLICH):", "## review_candidates", "## seams", "### Urteil je Kandidat",
               "\"passt so\"", "aenderungen_7d", "nie HIGH oder CRITICAL", "keine Mengen mit Einheit",
               "Verantwortlichkeiten (Urteil)", "offener bzw. neuer Urteilsbefund", "ein Urteilsbefund allein macht nie RED"):
    assert marker in prompt, "Urteilsschicht unvollstaendig: " + marker
assert blocks[i_report]["config"].get("timeout_seconds", 0) >= 1200, "Lesen der Kandidaten braucht Zeit"
gate = next(b for b in blocks if b["config"].get("label", "").startswith("Gate-Verdict"))["config"]["command"]
assert "{{ block.arch_metrics.output }}" in gate and "review_candidates" in gate, "Gate prueft das Urteil nicht"
delta = next(b for b in blocks if b.get("name") == "delta_scan")
assert delta["config"].get("timeout_seconds", 0) >= 180, "Struktur-Scan braucht Luft fuer Last"
# The collaborator count and the ratchet lists have lived in arch-metrics.sh since 4.6.0.
assert "NON_COLLAB_RE" not in delta["config"]["command"], "Kollaborateur-Zaehlung doppelt"
PY'

echo "5b) Faktenblatt zaehlt je Spring-Bean nur Kollaborateure (59e22c53, seit b8c06b94 in ## files)"
# Tools, configuration values and state are no dependencies: only injected
# "private final <type> <name>;" fields outside the exclusion list are counted. A List/Map/Set
# without an initializer is an injected bean collection and counts; Deque, Atomic* and locks
# without an initializer remain state, fields with an initializer never count.
SCAN_FIX="$TMP/scan-fixture"
SCAN_MAIN="$SCAN_FIX/ai-automation-backend/src/main/java/com/automation/service"
mkdir -p "$SCAN_MAIN"
{ echo "package com.automation.service;"
  echo "@Service"
  echo "public class BusyService {"
  for i in 1 2 3 4 5 6; do echo "    private final Collaborator$i collaborator$i;"; done
  echo "    private final ObjectMapper objectMapper;"
  echo "    private final TransactionTemplate transactionTemplate;"
  echo "    private final TransactionTemplate readOnlyTransactionTemplate;"
  echo "    private final String hostHome;"
  echo "    private final long ttlMs;"
  echo "    private final Clock clock;"
  echo "    private final TrayConfig trayConfig;"
  echo "    private final List<BlockExecutor> blockExecutors;"
  echo "    private final UnaryOperator<String> env;"
  echo "    private final LongSupplier nanoClock;"
  echo "    private final Map<String, Object> locks = new ConcurrentHashMap<>();"
  echo "    private final AtomicReference<Thread> running;"
  echo "    private final Deque<String> pending;"
  echo "    private final ReentrantLock lock;"
  echo "    private static final Logger log = LoggerFactory.getLogger(BusyService.class);"
  for i in $(seq 1 200); do echo "    // filler $i"; done
  echo "}"
} > "$SCAN_MAIN/BusyService.java"
{ echo "package com.automation.service;"
  echo "public class PlainEvent {"
  for i in 1 2 3 4 5 6 7; do echo "    private final Collaborator$i value$i;"; done
  for i in $(seq 1 200); do echo "    // filler $i"; done
  echo "}"
} > "$SCAN_MAIN/PlainEvent.java"
# Only fields of the outer class (e8226de2): RunStatistics.runs and .totals are the state of a
# value object. The brace after https:// in the Javadoc, those in literals and the one in the text block must not
# shift the depth, otherwise lateCollaborator would drop out as a field of the bean.
{ echo "package com.automation.service;"
  echo "/**"
  echo " * Zaehlt Laeufe, Quelle {@code https://example.invalid/runs}."
  echo " */"
  echo "@Service"
  echo "public class StatsService {"
  for i in 1 2 3 4 5 6; do echo "    private final Collaborator$i collaborator$i;"; done
  echo "    private static final String OPEN = \"{\";"
  echo "    private static final char BRACE = '{';"
  echo "    private static final String TEMPLATE = \"\"\""
  echo "            { \"offen\": true"
  echo "            \"\"\";"
  echo "    private final LateCollaborator lateCollaborator;"
  echo "    static final class RunStatistics {"
  echo "        private final RunList runs;"
  echo "        private final Totals totals;"
  echo "    }"
  for i in $(seq 1 200); do echo "    // filler $i"; done
  echo "}"
} > "$SCAN_MAIN/StatsService.java"
metrics_on() {  # $1 = root, $2 = output — without compiling, only the source sections count here
  ARCH_METRICS_COMPILE=0 bash "$SCRIPT" "$1" > "$2" 2> /dev/null
}
files_row() {  # $1 = output, $2 = path under src/main/java -> column deps of the ## files line
  sed -n '/^## files$/,/^## ratchet$/p' "$1" | awk -F'\t' -v p="$2" '$5 == p { print $4 }'
}
metrics_on "$SCAN_FIX" "$TMP/scan-metrics.out"
# 6 individual collaborators + the injected List<BlockExecutor> = 7.
check "BusyService: 7 Kollaborateure"            '[ "$(files_row "$TMP/scan-metrics.out" com/automation/service/BusyService.java)" = 7 ]'
check "Nicht-Bean ohne deps (-)"                 '[ "$(files_row "$TMP/scan-metrics.out" com/automation/service/PlainEvent.java)" = - ]'
# 6 collaborators + lateCollaborator = 7; the two fields of the inner class do not count.
check "StatsService: ohne Felder innerer Klassen" '[ "$(files_row "$TMP/scan-metrics.out" com/automation/service/StatsService.java)" = 7 ]'
check "summary zaehlt zwei Beans"                'grep -qx "beans=2" "$TMP/scan-metrics.out"'
check "## files nennt NCLOC, Zeilen und Aenderungen je Datei" \
  "sed -n '/^## files\$/,/^## ratchet\$/p' '$TMP/scan-metrics.out' | grep -E '^[0-9]+${TAB}[0-9]+${TAB}[0-9]+${TAB}(-|[0-9]+)${TAB}com/automation/service/BusyService\.java\$' >/dev/null"
# NCLOC of the bean: 3 header lines + 21 fields + closing brace; the 200 filler comments do not count.
check "BusyService: NCLOC ohne Kommentarzeilen (25 von 225 Zeilen)" \
  "sed -n '/^## files\$/,/^## ratchet\$/p' '$TMP/scan-metrics.out' | grep -x '25${TAB}225${TAB}0${TAB}7${TAB}com/automation/service/BusyService.java' >/dev/null"

echo "5c) Faktenblatt nennt die Grenzen der Architektur-Tests (810b12e9, seit b8c06b94 in ## ratchet)"
# PackageCycleRatchetTest pins every cycle component with more than two packages. ## ratchet prints them
# like the cycle lines of ## cycles (size, members sorted), plus ALLOWED_PAIRS and the
# ALLOWLIST of SourceSizeRatchetTest — the grading then downgrades or names the ceiling.
ratchet_lines() {  # $1 = output, $2 = kind -> lines of this kind from ## ratchet
  sed -n '/^## ratchet$/,/^## summary$/p' "$1" | awk -F'\t' -v k="$2" '$1 == k'
}
ARCH_TESTS="ai-automation-backend/src/test/java/com/automation/architecture"
mkdir -p "$SCAN_FIX/$ARCH_TESTS"
cat > "$SCAN_FIX/$ARCH_TESTS/PackageCycleRatchetTest.java" <<'JAVA'
package com.automation.architecture;

/** Grenzen in {@link #CYCLE_COMPONENTS} und {@link #ALLOWED_PAIRS}. */
class PackageCycleRatchetTest {
    static final Set<String> ALLOWED_PAIRS = Set.of(
            "com.automation.b <-> com.automation.a");

    static final int MAX_CYCLE_COMPONENT_SIZE = 4;

    static final List<Set<String>> CYCLE_COMPONENTS = List.of(
            Set.of(
                    "com.automation.d",
                    "com.automation.b",
                    "com.automation.a",
                    "com.automation.c"),
            Set.of("com.automation.z", "com.automation.x", "com.automation.y"));

    void check() {
        grownComponents(edges, CYCLE_COMPONENTS, MAX_CYCLE_COMPONENT_SIZE);
    }
}
JAVA
cat > "$SCAN_FIX/$ARCH_TESTS/SourceSizeRatchetTest.java" <<'JAVA'
package com.automation.architecture;

class SourceSizeRatchetTest {
    static final int MAX_LINES = 300;
    static final Map<String, Integer> ALLOWLIST = Map.ofEntries(
            entry("com/automation/service/BusyService.java", 330),
            entry("com/automation/service/StatsService.java", 310)
    );
}
JAVA
metrics_on "$SCAN_FIX" "$TMP/ratchet-fix.out"
printf '%s\n' "component${TAB}4${TAB}com.automation.a,com.automation.b,com.automation.c,com.automation.d" \
  "component${TAB}3${TAB}com.automation.x,com.automation.y,com.automation.z" > "$TMP/cycle-lines.exp"
ratchet_lines "$TMP/ratchet-fix.out" component > "$TMP/cycle-lines.txt"
check "je Komponente eine Zeile, Mitglieder sortiert, Reihenfolge wie im Test" \
  'diff -q "$TMP/cycle-lines.exp" "$TMP/cycle-lines.txt" >/dev/null'
check "ALLOWED_PAIRS als sortiertes Paar" \
  '[ "$(ratchet_lines "$TMP/ratchet-fix.out" pair)" = "pair${TAB}com.automation.a${TAB}com.automation.b" ]'
check "ALLOWLIST mit Obergrenze je Datei" \
  'ratchet_lines "$TMP/ratchet-fix.out" allowlist | grep -x "allowlist${TAB}330${TAB}com/automation/service/BusyService.java" >/dev/null'
check "state ok und MAX_LINES gelesen" \
  'ratchet_lines "$TMP/ratchet-fix.out" state | grep -x "state${TAB}ok" >/dev/null && ratchet_lines "$TMP/ratchet-fix.out" max_lines | grep -x "max_lines${TAB}300" >/dev/null'
check "summary nennt ratchet_state=ok" 'grep -qx "ratchet_state=ok" "$TMP/ratchet-fix.out"'
# The real test files: if their spelling falls out of the pattern, it shows up here and not only in the
# review (where an empty section would just read "nicht herabstufen", i.e. do not downgrade).
REAL_CYCLES="$REPO/$ARCH_TESTS/PackageCycleRatchetTest.java"
REAL_SIZES="$REPO/$ARCH_TESTS/SourceSizeRatchetTest.java"
mkdir -p "$TMP/scan-real/$ARCH_TESTS"
cp "$REAL_CYCLES" "$REAL_SIZES" "$TMP/scan-real/$ARCH_TESTS/"
metrics_on "$TMP/scan-real" "$TMP/ratchet-real.out"
ratchet_lines "$TMP/ratchet-real.out" component > "$TMP/real-cycle-lines.txt"
REAL_MAX="$(sed -nE 's/.*MAX_CYCLE_COMPONENT_SIZE = ([0-9]+);.*/\1/p' "$REAL_CYCLES")"
REAL_GROUPS="$(sed -n '/CYCLE_COMPONENTS = List.of(/,/);/p' "$REAL_CYCLES" | grep -c 'Set.of(')"
REAL_ENTRIES="$(grep -cE 'entry\("[^"]+", [0-9]+\)' "$REAL_SIZES")"
check "echter Test: state ok"                      'ratchet_lines "$TMP/ratchet-real.out" state | grep -x "state${TAB}ok" >/dev/null'
check "echter Test: eine Zeile je Set.of-Gruppe" \
  '[ "$REAL_GROUPS" -ge 1 ] && [ "$(wc -l < "$TMP/real-cycle-lines.txt")" -eq "$REAL_GROUPS" ]'
check "echter Test: die erste Komponente hat MAX_CYCLE_COMPONENT_SIZE Pakete" \
  '[ -n "$REAL_MAX" ] && head -1 "$TMP/real-cycle-lines.txt" | grep "^component${TAB}$REAL_MAX${TAB}com\.automation\." >/dev/null'
check "echter Test: jede Zeile nennt so viele Pakete wie ihre Groesse" \
  'awk -F"\t" "{ if (split(\$3, m, \",\") != \$2) bad++ } END { exit (bad > 0) }" "$TMP/real-cycle-lines.txt"'
check "echter Test: jede ALLOWLIST-Zeile gelesen" \
  '[ "$REAL_ENTRIES" -ge 1 ] && [ "$(ratchet_lines "$TMP/ratchet-real.out" allowlist | wc -l)" -eq "$REAL_ENTRIES" ]'
# Empty ALLOWED_PAIRS (the real state since 759b9fba) must not look like a pattern that no longer
# matches: the section then says explicitly "(keine)" (TODO ca0df066).
REAL_PAIRS="$(sed -n '/ALLOWED_PAIRS = Set.of(/,/);/p' "$REAL_CYCLES" | grep -c '<->')"
check "echter Test: ALLOWED_PAIRS zeigt Paare oder (keine), nie nichts" \
  'if [ "$REAL_PAIRS" -ge 1 ]; then [ "$(ratchet_lines "$TMP/ratchet-real.out" pair | wc -l)" -eq "$REAL_PAIRS" ];
   else ratchet_lines "$TMP/ratchet-real.out" "# pair" | grep -x "# pair${TAB}(keine)" >/dev/null; fi'
mkdir -p "$TMP/scan-empty/$ARCH_TESTS"
sed 's/ALLOWED_PAIRS = Set.of(/ALLOWED_PAIRS = Set.of();\n    static final Set<String> UNUSED = Set.of(/' \
  "$SCAN_FIX/$ARCH_TESTS/PackageCycleRatchetTest.java" > "$TMP/scan-empty/$ARCH_TESTS/PackageCycleRatchetTest.java"
cp "$SCAN_FIX/$ARCH_TESTS/SourceSizeRatchetTest.java" "$TMP/scan-empty/$ARCH_TESTS/"
metrics_on "$TMP/scan-empty" "$TMP/ratchet-empty.out"
check "leere ALLOWED_PAIRS: state ok, keine pair-Zeile, dafuer (keine)" \
  'ratchet_lines "$TMP/ratchet-empty.out" state | grep -x "state${TAB}ok" >/dev/null &&
   [ -z "$(ratchet_lines "$TMP/ratchet-empty.out" pair)" ] &&
   ratchet_lines "$TMP/ratchet-empty.out" "# pair" | grep -x "# pair${TAB}(keine)" >/dev/null'
check "nicht leere ALLOWED_PAIRS: kein (keine)" \
  '! ratchet_lines "$TMP/ratchet-fix.out" "# pair" | grep -x "# pair${TAB}(keine)" >/dev/null'
mkdir -p "$TMP/scan-none"
metrics_on "$TMP/scan-none" "$TMP/ratchet-none.out"
check "ohne Testdateien: state missing statt einer leeren Liste" \
  '[ "$(ratchet_lines "$TMP/ratchet-none.out" state)" = "state${TAB}missing" ] && grep -qx "ratchet_state=missing" "$TMP/ratchet-none.out"'

# ---------------------------------------------------------------------------
# Fixture repo: two packages with a mutual dependency, an empty file,
# an mvnw stub that builds into target/classes with javac. This way the
# self-compile path really runs through, instead of merely being claimed.
# ---------------------------------------------------------------------------
BE="$TMP/fixture/ai-automation-backend"
make_fixture() {
  rm -rf "$TMP/fixture"
  mkdir -p "$BE/src/main/java/com/automation/a" "$BE/src/main/java/com/automation/b"
  cat > "$BE/src/main/java/com/automation/a/A.java" <<'EOF'
package com.automation.a;

import com.automation.b.B;

public class A {
    public B make() {
        return new B();
    }
}
EOF
  cat > "$BE/src/main/java/com/automation/b/B.java" <<'EOF'
package com.automation.b;

import com.automation.a.A;

public class B {
    public A back() {
        return new A();
    }
}
EOF
  : > "$BE/src/main/java/com/automation/a/Leer.java"
  printf 'target/\n' > "$TMP/fixture/.gitignore"
  printf '<project/>\n' > "$BE/pom.xml"
  cat > "$BE/mvnw" <<'EOF'
#!/bin/sh
# Stub instead of Maven: builds the same classes into target/classes.
mkdir -p target/classes
exec javac -nowarn -d target/classes $(find src/main/java -name '*.java' ! -empty)
EOF
  chmod 755 "$BE/mvnw"
}

# Toolbox: only the programs the script really needs. This way "jdeps missing"
# can be tested without fiddling with the system PATH.
TOOLS="bash sh mkdir rm cp mv touch cat mktemp dirname find xargs awk sort uniq cut grep sed head wc tr git python3 javac jdeps"
make_toolbox() {  # $1 = target directory, $2 = optional tool to leave out
  local dir="$1" skip="${2:-}" t p
  mkdir -p "$dir"
  for t in $TOOLS; do
    [ "$t" = "$skip" ] && continue
    p="$(command -v "$t" 2>/dev/null)" || continue
    ln -sf "$p" "$dir/$t"
  done
}
make_toolbox "$TMP/bin"
make_toolbox "$TMP/bin-nojdeps" jdeps

echo "6) Selbst-Kompilier-Pfad im frischen Fixture-Repo"
make_fixture
PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fix1.txt" 2> "$TMP/fix1.err"; RC=$?
check "erster Lauf exit 0"                  '[ "$RC" -eq 0 ]'
check "hat selbst kompiliert (compile=compiled)" 'grep -qx "compile=compiled" "$TMP/fix1.txt"'
check "target/classes wurde angelegt"       '[ -d "$BE/target/classes" ]'
check "wechselseitige Kante erkannt"        "grep -qx 'pair${TAB}com.automation.a${TAB}com.automation.b' '$TMP/fix1.txt'"
check "cycles=1 gemessen"                   'grep -qx "cycles=1" "$TMP/fix1.txt"'
check "cycles_state=ok"                     'grep -qx "cycles_state=ok" "$TMP/fix1.txt"'
check "leere .java-Datei mit 0 Zeilen gelistet" "grep -qx '0${TAB}com/automation/a/Leer.java' '$TMP/fix1.txt'"
check "files zaehlt die leere Datei mit"    'grep -qx "files=3" "$TMP/fix1.txt"'

PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fix2.txt" 2> "$TMP/fix2.err"; RC=$?
measured "$TMP/fix1.txt" > "$TMP/fix1.measured"; measured "$TMP/fix2.txt" > "$TMP/fix2.measured"
check "zweiter Lauf exit 0"                          '[ "$RC" -eq 0 ]'
check "zweiter Lauf nutzt das Kompilat (reused)"     'grep -qx "compile=reused" "$TMP/fix2.txt"'
check "compiled/reused unterscheiden sich wirklich"  '! diff -q "$TMP/fix1.txt" "$TMP/fix2.txt" >/dev/null'
check "gemessener Teil trotzdem byte-identisch"      'diff -q "$TMP/fix1.measured" "$TMP/fix2.measured" >/dev/null'
if ! diff -q "$TMP/fix1.measured" "$TMP/fix2.measured" >/dev/null 2>&1; then
  diff -u "$TMP/fix1.measured" "$TMP/fix2.measured" | sed -n 1,20p
fi

# Der Aufruf im Pool-Workflow lautet `bash scripts/public-readiness/arch-metrics.sh "$BASE"`.
# If SCRIPT_DIR is only resolved after the cd into the measured root, the relative path points
# nowhere and the cycles silently count as not measured.
( cd "$REPO" && PATH="$TMP/bin" bash scripts/public-readiness/arch-metrics.sh "$TMP/fixture" ) \
  > "$TMP/relpath.txt" 2>/dev/null
check "relativer Skriptpfad: cycles_state=ok" 'grep -qx "cycles_state=ok" "$TMP/relpath.txt"'
check "relativer Skriptpfad: Zyklus gefunden" \
  "grep -qx 'pair${TAB}com.automation.a${TAB}com.automation.b' '$TMP/relpath.txt'"

echo "7) Fehlschlaege werden gemeldet, nicht als Sauberkeit ausgegeben"
mkdir -p "$TMP/stub-python"
printf '#!/bin/sh\nexit 1\n' > "$TMP/stub-python/python3"; chmod 755 "$TMP/stub-python/python3"
PATH="$TMP/stub-python:$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fail-py.txt" 2>/dev/null; RC=$?
check "python3-Fehlschlag: exit 0 (Faktenblatt bleibt)" '[ "$RC" -eq 0 ]'
check "python3-Fehlschlag: cycles_state=failed"         'grep -qx "cycles_state=failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: error nennt cycles_failed"   'grep -qE "^error=.*cycles_failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: cycles-Abschnitt sagt nicht gemessen" \
  "sed -n '/^## cycles\$/,/^## sizes\$/p' '$TMP/fail-py.txt' | grep 'nicht gemessen' >/dev/null"
check "python3-Fehlschlag: behauptet keine Zyklenfreiheit" \
  "! grep -q 'keine Zyklen zwischen' '$TMP/fail-py.txt'"

mkdir -p "$TMP/stub-jdeps"
printf '#!/bin/sh\nexit 1\n' > "$TMP/stub-jdeps/jdeps"; chmod 755 "$TMP/stub-jdeps/jdeps"
PATH="$TMP/stub-jdeps:$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fail-jdeps.txt" 2>/dev/null; RC=$?
check "jdeps-Fehlschlag: exit 0"                   '[ "$RC" -eq 0 ]'
check "jdeps-Fehlschlag: jdeps=failed in ## run"   'grep -qx "jdeps=failed" "$TMP/fail-jdeps.txt"'
check "jdeps-Fehlschlag: error nennt jdeps_failed" 'grep -qE "^error=.*jdeps_failed" "$TMP/fail-jdeps.txt"'
check "jdeps-Fehlschlag: packages sagt nicht gemessen" \
  "sed -n '/^## packages\$/,/^## cycles\$/p' '$TMP/fail-jdeps.txt' | grep 'nicht gemessen' >/dev/null"
check "jdeps-Fehlschlag: edges=0"                  'grep -qx "edges=0" "$TMP/fail-jdeps.txt"'

PATH="$TMP/bin-nojdeps" bash "$SCRIPT" "$TMP/fixture" > "$TMP/no-jdeps.txt" 2>/dev/null; RC=$?
check "jdeps fehlt: exit 0"                     '[ "$RC" -eq 0 ]'
check "jdeps fehlt: jdeps=missing in ## run"    'grep -qx "jdeps=missing" "$TMP/no-jdeps.txt"'
check "jdeps fehlt: error nennt jdeps_missing"  'grep -qE "^error=.*jdeps_missing" "$TMP/no-jdeps.txt"'
check "jdeps fehlt: sizes trotzdem gemessen"    "grep -qx 'files=3' '$TMP/no-jdeps.txt'"

echo "8) Kompilat-Cache fuer Bytecode-Messungen (TODO bf5872be)"
CACHE="$TMP/arch-cache"
make_fixture
( cd "$TMP/fixture" && git init -q \
    && git add -A \
    && git -c user.email=fixture@example.invalid -c user.name=Fixture commit -qm fixture ) >/dev/null 2>&1
FIX_SHA="$( cd "$TMP/fixture" && git rev-parse HEAD 2>/dev/null )"
check "Fixture ist ein Git-Repo mit genau einem Commit" '[ -n "$FIX_SHA" ]'

AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache1.txt" 2> "$TMP/cache1.err"
check "erster Lauf kompiliert (compile=compiled)" 'grep -qx "compile=compiled" "$TMP/cache1.txt"'
check "Kompilat liegt danach im Cache unter dem Commit" \
  '[ -n "$(find "$CACHE/$FIX_SHA" -name "*.class" -print -quit 2>/dev/null)" ]'

rm -rf "$BE/target"
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache2.txt" 2> "$TMP/cache2.err"
check "zweiter Lauf nutzt den Cache (compile=cached)" 'grep -qx "compile=cached" "$TMP/cache2.txt"'
check "der Cache-Lauf kompiliert wirklich nicht neu"  '[ ! -d "$BE/target/classes" ]'
measured "$TMP/cache1.txt" > "$TMP/cache1.measured"; measured "$TMP/cache2.txt" > "$TMP/cache2.measured"
check "Cache misst dasselbe wie das frische Kompilat" \
  'diff -q "$TMP/cache1.measured" "$TMP/cache2.measured" >/dev/null'
if ! diff -q "$TMP/cache1.measured" "$TMP/cache2.measured" >/dev/null 2>&1; then
  diff -u "$TMP/cache1.measured" "$TMP/cache2.measured" | sed -n 1,20p
fi

# A cache hit on the commit state would be a wrong measurement for changed files:
# the commit would be measured, the working state reported.
rm -rf "$BE/target"
printf 'unkommittiert\n' > "$TMP/fixture/dirty.txt"
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache3.txt" 2>/dev/null
check "unkommittierte Aenderung umgeht den Cache" 'grep -qx "compile=compiled" "$TMP/cache3.txt"'
rm -f "$TMP/fixture/dirty.txt"

# A hit is used, so freshly stamped: jdeps reads from it for seconds, and the
# keep-3 rank goes by mtime. Without the stamp a parallel run removes the entry exactly
# during the measurement — exit 0, no error=, half a class tree measured.
rm -rf "$BE/target"
touch -d '2020-01-01 00:00:00' "$CACHE/$FIX_SHA"
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache-touch.txt" 2>/dev/null
check "Treffer stempelt den Eintrag frisch" \
  '[ -n "$(find "$CACHE" -mindepth 1 -maxdepth 1 -name "$FIX_SHA" -newermt "2021-01-01" 2>/dev/null)" ]'
check "Treffer bleibt ein Treffer (compile=cached)" 'grep -qx "compile=cached" "$TMP/cache-touch.txt"'

# The same entry, but three younger foreign entries: without the stamp it would be rank 4 and
# fall victim to the cleanup, although it is just being measured from.
rm -rf "$BE/target"
touch -d '2020-01-01 00:00:00' "$CACHE/$FIX_SHA"
for fresh in eee fff ggg; do mkdir -p "$CACHE/$fresh"; : > "$CACHE/$fresh/marker"; done
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > /dev/null 2>&1
check "benutzter Eintrag ueberlebt juengere Fremdeintraege" '[ -d "$CACHE/$FIX_SHA" ]'
rm -rf "$CACHE/eee" "$CACHE/fff" "$CACHE/ggg"

# A valid entry is never replaced — otherwise a writer pulls the classes away from the reader
# during the running measurement.
: > "$CACHE/$FIX_SHA/nicht-ueberschreiben"
rm -rf "$BE/target"
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > /dev/null 2>&1
check "gueltiger Cache-Eintrag wird nicht neu geschrieben" '[ -f "$CACHE/$FIX_SHA/nicht-ueberschreiben" ]'

# A leftover staging directory of a crashed run must not count as an entry during cleanup
# and must not displace real compiled output.
mkdir -p "$CACHE/.staging.999"
rm -rf "$BE/target" "$CACHE/$FIX_SHA"
for stale in aaa bbb ccc ddd; do mkdir -p "$CACHE/$stale"; : > "$CACHE/$stale/marker"; done
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache4.txt" 2>/dev/null
check "Cache behaelt hoechstens 3 Eintraege" \
  '[ "$(find "$CACHE" -mindepth 1 -maxdepth 1 -type d ! -name ".*" | wc -l)" -le 3 ]'
check "der frische Eintrag ueberlebt das Aufraeumen" '[ -d "$CACHE/$FIX_SHA" ]'
check "fremdes Staging-Verzeichnis bleibt unangetastet" '[ -d "$CACHE/.staging.999" ]'

# An unusable cache directory must not turn the fact sheet red.
rm -rf "$BE/target"
: > "$TMP/cache-is-a-file"
AUTOMATION_ARCH_CACHE="$TMP/cache-is-a-file" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache5.txt" 2>/dev/null
RC=$?
check "unbrauchbarer Cache-Pfad: exit 0"            '[ "$RC" -eq 0 ]'
check "unbrauchbarer Cache-Pfad: trotzdem gemessen" 'grep -qx "cycles=1" "$TMP/cache5.txt"'

echo "9) Seams je Kandidat aus dem Bytecode (arch-seams.py, TODO e0e9495f)"
# Fixture with known accesses: two groups via shared collaborators, a third alone,
# an overload, a lambda, an inner class, a collaborator used only in the constructor and
# two fields that are no collaborators (configuration value, state with an initializer).
SEAMS="$REPO/scripts/public-readiness/arch-seams.py"
SEAM_SRC="$TMP/seams/src/main/java"; SEAM_CLASSES="$TMP/seams/classes"
mkdir -p "$SEAM_SRC/com/automation/fix" "$SEAM_CLASSES"
cat > "$SEAM_SRC/com/automation/fix/Seams.java" <<'JAVA'
package com.automation.fix;

import java.util.ArrayList;
import java.util.List;

public class Seams {
    private static final int LIMIT = 3;
    private final Alpha alpha;
    private final Beta beta;
    private final Gamma gamma;
    private final Delta onlyCtor;
    private final String name;
    private final List<Alpha> everyAlpha;
    private final List<String> cache = new ArrayList<>();

    public Seams(Alpha alpha, Beta beta, Gamma gamma, Delta onlyCtor, String name, List<Alpha> everyAlpha) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.onlyCtor = onlyCtor;
        this.name = name;
        this.everyAlpha = everyAlpha;
        onlyCtor.go();
    }

    public void first() {
        alpha.go();
    }

    public void second() {
        alpha.go();
        beta.go();
    }

    public void second(int times) {
        for (int i = 0; i < times; i++) {
            beta.go();
        }
    }

    public Runnable later() {
        return () -> gamma.go();
    }

    public int helper() {
        return LIMIT + cache.size() + name.length();
    }

    public int count() {
        return everyAlpha.size();
    }

    class Inner {
        void run() {
            gamma.go();
        }
    }
}

interface Alpha { void go(); }
interface Beta { void go(); }
interface Gamma { void go(); }
interface Delta { void go(); }
JAVA
javac -nowarn -g -d "$SEAM_CLASSES" "$SEAM_SRC/com/automation/fix/Seams.java" 2> "$TMP/seams-javac.err"
F=com/automation/fix/Seams.java
python3 "$SEAMS" "$SEAM_SRC" "$SEAM_CLASSES" "$F" frontend/src/pages/X.tsx > "$TMP/seams.out" 2> "$TMP/seams.err"; RC=$?
seam() {  # $1 = kind, $2 = key in the third field (or empty) -> the following fields, tab-separated
  awk -F'\t' -v a="$1" -v k="${2:-}" -v f="$F" '$1 == a && $2 == f && (k == "" || $3 == k) {
    s = (k == "") ? 3 : 4; out = ""
    for (i = s; i <= NF; i++) out = out (i > s ? "\t" : "") $i
    print out }' "$TMP/seams.out"
}
check "Fixture kompiliert"                 '[ -f "$SEAM_CLASSES/com/automation/fix/Seams.class" ]'
check "exit 0, stderr leer, state ok"      '[ "$RC" -eq 0 ] && [ ! -s "$TMP/seams.err" ] && grep -qx "state${TAB}ok" "$TMP/seams.out"'
check "class-Zeile: 5 Kollaborateure, 6 Methoden, 5 mit Kollaborateur, 3 Gruppen" \
  '[ "$(seam class)" = "kollaborateure=5${TAB}methoden=6${TAB}mit_kollaborateur=5${TAB}gruppen=3" ]'
check "Konfigurationswert und Zustand mit Initialisierer sind keine Kollaborateure" \
  '! cut -f5 "$TMP/seams.out" | tr "," "\n" | grep -xE "name|cache" >/dev/null'
check "Ueberladungen zusammengefasst: second nutzt alpha und beta" '[ "$(seam method second | cut -f2)" = "alpha,beta" ]'
check "Lambda zaehlt zur umschliessenden Methode"   '[ "$(seam method later | cut -f2)" = gamma ]'
check "innere Klasse heisst <Innere>.<methode>"      '[ "$(seam method Inner.run | cut -f2)" = gamma ]'
check "Bean-Sammlung ohne Initialisierer zaehlt"      '[ "$(seam method count | cut -f2)" = everyAlpha ]'
check "zeilen = erste bis letzte Codezeile"          '[ "$(seam method first | cut -f1)" = "27-28" ]'
check "Methode ohne Kollaborateur steht nicht als method-Zeile da" '[ -z "$(seam method helper)" ]'
check "Konstruktor steht nicht als Methode da"       '! grep -q "${TAB}Seams${TAB}" "$TMP/seams.out"'
check "Gruppe 1: first und second ueber alpha (mit beta)" '[ "$(seam group 1)" = "first,second${TAB}alpha,beta" ]'
check "Gruppe 2: later und Inner.run ueber gamma"         '[ "$(seam group 2)" = "later,Inner.run${TAB}gamma" ]'
check "Gruppe 3: count allein"                            '[ "$(seam group 3)" = "count${TAB}everyAlpha" ]'
check "nur im Konstruktor benutzt: onlyCtor"              '[ "$(awk -F"\t" "\$1 == \"nur_konstruktor\" { print \$3 }" "$TMP/seams.out")" = onlyCtor ]'
check "Frontend-Kandidat wird nicht gemessen und nicht gemeldet" '! grep -q "X.tsx" "$TMP/seams.out"'
python3 "$SEAMS" "$SEAM_SRC" "$SEAM_CLASSES" "$F" > "$TMP/seams2.out" 2>/dev/null
check "zwei Laeufe byte-identisch" 'diff -q <(grep -v "^#" "$TMP/seams.out" | grep -v X.tsx) <(grep -v "^#" "$TMP/seams2.out") >/dev/null'
# What cannot be measured is listed with its reason, never as an empty table.
touch -d '+1 minute' "$SEAM_SRC/$F"
python3 "$SEAMS" "$SEAM_SRC" "$SEAM_CLASSES" "$F" > "$TMP/seams-stale.out" 2>/dev/null
check "Quelltext juenger als Kompilat: missing stale_classes" 'grep -qx "missing${TAB}$F${TAB}stale_classes" "$TMP/seams-stale.out"'
python3 "$SEAMS" "$SEAM_SRC" "$SEAM_CLASSES" com/automation/fix/Gibts.java > "$TMP/seams-missing.out" 2>/dev/null
check "Klasse fehlt: missing class_missing" 'grep -qx "missing${TAB}com/automation/fix/Gibts.java${TAB}class_missing" "$TMP/seams-missing.out"'
python3 "$SEAMS" "$SEAM_SRC" "$TMP/seams/gibts-nicht" "$F" > "$TMP/seams-noclasses.out" 2>/dev/null; RC=$?
check "kein Kompilat: exit 0, state no_classes, Datei als missing" \
  '[ "$RC" -eq 0 ] && grep -qx "state${TAB}no_classes" "$TMP/seams-noclasses.out" && grep -qx "missing${TAB}$F${TAB}no_classes" "$TMP/seams-noclasses.out"'
python3 "$SEAMS" "$SEAM_SRC" > /dev/null 2>&1; RC=$?
check "falscher Aufruf endet mit 2" '[ "$RC" -eq 2 ]'
# arch-metrics.sh names the measured compiled output only on request and only in one file, never in the sheet.
ARCH_METRICS_CLASSES_OUT="$TMP/classes-out" bash "$SCRIPT" "$REPO" > "$TMP/run-classes.txt" 2>/dev/null
check "ARCH_METRICS_CLASSES_OUT bekommt ein vorhandenes Verzeichnis" '[ -d "$(cat "$TMP/classes-out" 2>/dev/null)" ]'
check "der Pfad steht nicht im Faktenblatt" '! grep -qF "$(cat "$TMP/classes-out" 2>/dev/null)" "$TMP/run-classes.txt"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
