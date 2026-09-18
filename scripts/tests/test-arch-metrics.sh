#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/arch-metrics.sh und arch_cycles.py (TODO 29173b90).
# Run: bash scripts/tests/test-arch-metrics.sh   (exit 0 = alle Checks ok)
#
# Zeitbudget: der Skriptlauf startet bei fehlendem ai-automation-backend/target/classes
# einmalig `./mvnw -q -DskipTests compile` (~1-2 Minuten). Danach laufen beide Durchgaenge
# aus dem vorhandenen target/classes (~10-20 Sekunden je Lauf).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/arch-metrics.sh"
CYCLES="$REPO/scripts/public-readiness/arch_cycles.py"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

expect_cycles() {  # $1 = label, $2 = Kantenliste (stdin), $3 = erwartete Ausgabe
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

echo "0) Dateien vorhanden"
check "arch-metrics.sh existiert" '[ -f "$SCRIPT" ]'
check "arch_cycles.py existiert"  '[ -f "$CYCLES" ]'
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

# Determinismus: gemischte Eingabereihenfolge ergibt dieselbe Ausgabe.
printf 'b\ta\nc\ta\na\tb\na\tc\n'  | python3 "$CYCLES" > "$TMP/mix1.out" 2>/dev/null
printf 'a\tc\na\tb\nc\ta\nb\ta\n'  | python3 "$CYCLES" > "$TMP/mix2.out" 2>/dev/null
check "Eingabereihenfolge aendert die Ausgabe nicht" 'diff -q "$TMP/mix1.out" "$TMP/mix2.out" >/dev/null'
check "gemischte Eingabe findet den Zyklus"          'grep -q "^cycle" "$TMP/mix1.out"'

echo "2) Skriptlauf gegen das Repository"
bash "$SCRIPT" "$REPO" > "$TMP/run1.txt" 2> "$TMP/run1.err"; RC1=$?
check "exit 0"                      '[ "$RC1" -eq 0 ]'
check "Ausgabe nicht leer"          '[ -s "$TMP/run1.txt" ]'
for s in packages cycles sizes churn coupling summary; do
  check "Abschnitt ## $s vorhanden" "grep -qx '## $s' '$TMP/run1.txt'"
done
for k in packages edges cycles files files_over_300 files_over_500 churn_files_90d; do
  check "summary-Schluessel $k" "sed -n '/^## summary\$/,\$p' '$TMP/run1.txt' | grep -qE '^$k=[0-9]+\$'"
done
check "packages liefert com.automation-Kanten" \
  "grep -cE '^com\.automation[^${TAB}]*${TAB}com\.automation[^${TAB}]*${TAB}[0-9]+\$' '$TMP/run1.txt' | grep -qvx 0"
check "sizes liefert Zeilen/Pfad-Paare" \
  "sed -n '/^## sizes\$/,/^## churn\$/p' '$TMP/run1.txt' | grep -qE '^[0-9]+${TAB}com/automation/.*\.java\$'"
check "coupling liefert Paket-Kennzahlen" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/run1.txt' | grep -qE '^com\.automation[^${TAB}]*(${TAB}[0-9]+){4}\$'"
check "keine absoluten Benutzerpfade in der Ausgabe" \
  "! grep -qE '(/home/|/Users/)[a-zA-Z_-]+/' '$TMP/run1.txt'"
check "packages>0 in summary" \
  "sed -n '/^## summary\$/,\$p' '$TMP/run1.txt' | grep -qE '^packages=[1-9][0-9]*\$'"
check "files>0 in summary" \
  "sed -n '/^## summary\$/,\$p' '$TMP/run1.txt' | grep -qE '^files=[1-9][0-9]*\$'"

echo "3) Zwei Laeufe liefern identische Ausgabe (Determinismus)"
bash "$SCRIPT" "$REPO" > "$TMP/run2.txt" 2> "$TMP/run2.err"; RC2=$?
check "zweiter Lauf exit 0"     '[ "$RC2" -eq 0 ]'
check "Ausgabe byte-identisch"  'diff -q "$TMP/run1.txt" "$TMP/run2.txt" >/dev/null'
if ! diff -q "$TMP/run1.txt" "$TMP/run2.txt" >/dev/null 2>&1; then
  diff -u "$TMP/run1.txt" "$TMP/run2.txt" | head -20
fi

echo "4) Unbrauchbares Wurzelverzeichnis wird gemeldet statt still ignoriert"
OUT=$(bash "$SCRIPT" "$TMP/gibt-es-nicht" 2>&1); RC=$?
check "exit 2 bei fehlendem Verzeichnis" '[ "$RC" -eq 2 ]'
check "Meldung nennt das Verzeichnis"    'grep -q "gibt-es-nicht" <<<"$OUT"'

mkdir -p "$TMP/leer"
bash "$SCRIPT" "$TMP/leer" > "$TMP/leer.out" 2> "$TMP/leer.err"; RC=$?
check "leeres Verzeichnis: exit 0"          '[ "$RC" -eq 0 ]'
check "leeres Verzeichnis: summary vorhanden" 'grep -qx "## summary" "$TMP/leer.out"'
check "leeres Verzeichnis: error-Markierung"  'grep -qE "^error=" "$TMP/leer.out"'

echo "5) Pool-Workflow ruft das Skript auf und uebergibt es an den KI-Block"
POOL="$REPO/workflow-pool/mysuricates-architektur-review.json"
check "Pool-Datei ist valides JSON"  'python3 -m json.tool "$POOL" > /dev/null'
check "Shell-Block ruft arch-metrics.sh" 'grep -q "arch-metrics.sh" "$POOL"'
check "KI-Block liest das Faktenblatt"   'python3 - "$POOL" <<'"'"'PY'"'"'
import json, sys
wf = json.load(open(sys.argv[1]))
blocks = wf["blocks"]
names = [b.get("name") for b in blocks]
i_metrics = next(i for i, b in enumerate(blocks)
                 if b["type"] == "shell" and "arch-metrics.sh" in b["config"].get("command", ""))
i_report = next(i for i, b in enumerate(blocks) if b.get("name") == "report")
assert i_metrics < i_report, "Metrik-Block muss vor dem Report-Block liegen"
metrics_name = blocks[i_metrics].get("name")
assert metrics_name, "Metrik-Block braucht einen Namen"
prompt = blocks[i_report]["config"]["command"]
assert "{{ block.%s.output }}" % metrics_name in prompt, "Report-Prompt referenziert das Faktenblatt nicht"
assert blocks[i_metrics]["config"].get("timeout_seconds") == 600
assert "mysuricates:audit-result:v1" in prompt, "Envelope fehlt"
assert "gate_status:" in prompt, "Gate-Marker fehlt"
assert "Miss nichts selbst" in prompt, "Interpretations-Anweisung fehlt"
PY'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
