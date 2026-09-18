#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/arch-metrics.sh und arch_cycles.py (TODO 29173b90).
# Run: bash scripts/tests/test-arch-metrics.sh   (exit 0 = alle Checks ok)
#
# Zeitbudget: der Lauf gegen das echte Repo startet bei fehlendem
# ai-automation-backend/target/classes einmalig `./mvnw -q -DskipTests compile` (~1-2 Minuten).
# Danach laufen beide Durchgaenge aus dem vorhandenen target/classes (~5 s je Lauf).
# Die Fixture-Laeufe (Abschnitte 6 und 7) brauchen nur javac und dauern Sekunden.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/arch-metrics.sh"
CYCLES="$REPO/scripts/public-readiness/arch_cycles.py"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# Kein Testlauf schreibt in den echten Kompilat-Cache des Entwicklers.
export AUTOMATION_ARCH_CACHE="$TMP/arch-cache-default"
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

expect_cycles() {  # $1 = label, $2 = Kantenliste, $3 = erwartete Ausgabe
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

# Alles vor "## run" ist der gemessene, deterministische Teil; "## run" traegt den
# Laufzeit-Zustand (compile=/jdeps=) und ist ausdruecklich nicht vergleichbar.
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
for s in packages cycles sizes churn coupling summary run; do
  check "Abschnitt ## $s vorhanden" "grep -qx '## $s' '$TMP/run1.txt'"
done
for k in packages edges cycles files files_over_300 files_over_500 churn_files_90d; do
  check "summary-Schluessel $k" "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^$k=[0-9]+\$'"
done
check "summary nennt cycles_state" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^cycles_state=(ok|failed|no_edges)\$'"
check "compile= steht in ## run, nicht in ## summary" \
  "sed -n '/^## run\$/,\$p' '$TMP/run1.txt' | grep -qE '^compile=' && ! sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^compile='"
check "jdeps= steht in ## run, nicht in ## summary" \
  "sed -n '/^## run\$/,\$p' '$TMP/run1.txt' | grep -qE '^jdeps=' && ! sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^jdeps='"
check "packages liefert com.automation-Kanten" \
  "grep -cE '^com\.automation[^${TAB}]*${TAB}com\.automation[^${TAB}]*${TAB}[0-9]+\$' '$TMP/run1.txt' | grep -qvx 0"
check "sizes liefert Zeilen/Pfad-Paare" \
  "sed -n '/^## sizes\$/,/^## churn\$/p' '$TMP/run1.txt' | grep -qE '^[0-9]+${TAB}com/automation/.*\.java\$'"
check "coupling liefert Paket-Kennzahlen" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/run1.txt' | grep -qE '^com\.automation[^${TAB}]*(${TAB}[0-9]+){4}\$'"
check "keine absoluten Benutzerpfade in der Ausgabe" \
  "! grep -qE '(/home/|/Users/)[a-zA-Z_-]+/' '$TMP/run1.txt'"
check "packages>0 in summary" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^packages=[1-9][0-9]*\$'"
check "files>0 in summary" \
  "sed -n '/^## summary\$/,/^## run\$/p' '$TMP/run1.txt' | grep -qE '^files=[1-9][0-9]*\$'"

echo "3) Zwei Laeufe liefern identische Messung (Determinismus)"
bash "$SCRIPT" "$REPO" > "$TMP/run2.txt" 2> "$TMP/run2.err"; RC2=$?
measured "$TMP/run1.txt" > "$TMP/run1.measured"; measured "$TMP/run2.txt" > "$TMP/run2.measured"
check "zweiter Lauf exit 0"                  '[ "$RC2" -eq 0 ]'
check "gemessener Teil byte-identisch"       'diff -q "$TMP/run1.measured" "$TMP/run2.measured" >/dev/null'
if ! diff -q "$TMP/run1.measured" "$TMP/run2.measured" >/dev/null 2>&1; then
  diff -u "$TMP/run1.measured" "$TMP/run2.measured" | head -20
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

echo "5) Pool-Workflow ruft das Skript auf und uebergibt es an den KI-Block"
POOL="$REPO/workflow-pool/mysuricates-architektur-review.json"
check "Pool-Datei ist valides JSON"      'python3 -m json.tool "$POOL" > /dev/null'
check "Shell-Block ruft arch-metrics.sh" 'grep -q "arch-metrics.sh" "$POOL"'
check "Pool-Vertrag (Reihenfolge, Name, Prompt)" 'python3 - "$POOL" <<'"'"'PY'"'"'
import json, sys
wf = json.load(open(sys.argv[1]))
blocks = wf["blocks"]
i_metrics = next(i for i, b in enumerate(blocks)
                 if b["type"] == "shell" and "arch-metrics.sh" in b["config"].get("command", ""))
i_report = next(i for i, b in enumerate(blocks) if b.get("name") == "report")
assert i_metrics < i_report, "Metrik-Block muss vor dem Report-Block liegen"
metrics = blocks[i_metrics]
name = metrics.get("name")
assert name, "Metrik-Block braucht einen Namen"
assert metrics["config"].get("timeout_seconds") == 600
assert "set -euo pipefail" in metrics["config"]["command"], "Shell-Block ohne set -euo pipefail"
prompt = blocks[i_report]["config"]["command"]
assert "{{ block.%s.output }}" % name in prompt, "Report-Prompt referenziert das Faktenblatt nicht"
assert "mysuricates:audit-result:v1" in prompt, "Envelope fehlt"
assert "gate_status:" in prompt, "Gate-Marker fehlt"
assert "Miss nichts selbst" in prompt, "Interpretations-Anweisung fehlt"
assert "Fehlt das Faktenblatt ganz" in prompt, "Regel fuer fehlendes Faktenblatt fehlt"
# Zweifaktoriges God-Class-Kriterium (Zeilen UND Abhaengigkeiten) muss erhalten sein.
assert ">300 Zeilen" in prompt and ">7 Abhaengigkeiten" in prompt, "God-Class-Kriterium nicht zweifaktorig"
assert ">200 Zeilen" in prompt and ">5 Abhaengigkeiten" in prompt, "HIGH-Stufe der God Classes fehlt"
assert "Ratchet" in prompt and "herabstufen" in prompt, "Ratchet-Herabstufung fehlt"
assert "## cycles" in prompt, "Zyklen-Kriterium fehlt"
PY'

# ---------------------------------------------------------------------------
# Fixture-Repo: zwei Pakete mit wechselseitiger Abhaengigkeit, eine leere Datei,
# ein mvnw-Stub, der mit javac nach target/classes baut. Damit laeuft der
# Selbst-Kompilier-Pfad wirklich durch, statt nur behauptet zu werden.
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
# Stub statt Maven: baut dieselben Klassen nach target/classes.
mkdir -p target/classes
exec javac -nowarn -d target/classes $(find src/main/java -name '*.java' ! -empty)
EOF
  chmod 755 "$BE/mvnw"
}

# Werkzeugkasten: nur die Programme, die das Skript wirklich braucht. So laesst sich
# "jdeps fehlt" testen, ohne am System-PATH zu drehen.
TOOLS="bash sh mkdir rm cp mv cat mktemp dirname find xargs awk sort uniq cut grep sed head wc tr git python3 javac jdeps"
make_toolbox() {  # $1 = Zielverzeichnis, $2 = optional auszulassendes Werkzeug
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
  diff -u "$TMP/fix1.measured" "$TMP/fix2.measured" | head -20
fi

echo "7) Fehlschlaege werden gemeldet, nicht als Sauberkeit ausgegeben"
mkdir -p "$TMP/stub-python"
printf '#!/bin/sh\nexit 1\n' > "$TMP/stub-python/python3"; chmod 755 "$TMP/stub-python/python3"
PATH="$TMP/stub-python:$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fail-py.txt" 2>/dev/null; RC=$?
check "python3-Fehlschlag: exit 0 (Faktenblatt bleibt)" '[ "$RC" -eq 0 ]'
check "python3-Fehlschlag: cycles_state=failed"         'grep -qx "cycles_state=failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: error nennt cycles_failed"   'grep -qE "^error=.*cycles_failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: cycles-Abschnitt sagt nicht gemessen" \
  "sed -n '/^## cycles\$/,/^## sizes\$/p' '$TMP/fail-py.txt' | grep -q 'nicht gemessen'"
check "python3-Fehlschlag: behauptet keine Zyklenfreiheit" \
  "! grep -q 'keine Zyklen zwischen' '$TMP/fail-py.txt'"

mkdir -p "$TMP/stub-jdeps"
printf '#!/bin/sh\nexit 1\n' > "$TMP/stub-jdeps/jdeps"; chmod 755 "$TMP/stub-jdeps/jdeps"
PATH="$TMP/stub-jdeps:$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fail-jdeps.txt" 2>/dev/null; RC=$?
check "jdeps-Fehlschlag: exit 0"                   '[ "$RC" -eq 0 ]'
check "jdeps-Fehlschlag: jdeps=failed in ## run"   'grep -qx "jdeps=failed" "$TMP/fail-jdeps.txt"'
check "jdeps-Fehlschlag: error nennt jdeps_failed" 'grep -qE "^error=.*jdeps_failed" "$TMP/fail-jdeps.txt"'
check "jdeps-Fehlschlag: packages sagt nicht gemessen" \
  "sed -n '/^## packages\$/,/^## cycles\$/p' '$TMP/fail-jdeps.txt' | grep -q 'nicht gemessen'"
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
  diff -u "$TMP/cache1.measured" "$TMP/cache2.measured" | head -20
fi

# Ein Cache-Treffer auf den Commit-Stand waere bei geaenderten Dateien eine falsche Messung:
# gemessen wuerde der Commit, gemeldet der Arbeitsstand.
rm -rf "$BE/target"
printf 'unkommittiert\n' > "$TMP/fixture/dirty.txt"
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache3.txt" 2>/dev/null
check "unkommittierte Aenderung umgeht den Cache" 'grep -qx "compile=compiled" "$TMP/cache3.txt"'
rm -f "$TMP/fixture/dirty.txt"

rm -rf "$BE/target" "$CACHE/$FIX_SHA"
for stale in aaa bbb ccc ddd; do mkdir -p "$CACHE/$stale"; : > "$CACHE/$stale/marker"; done
AUTOMATION_ARCH_CACHE="$CACHE" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache4.txt" 2>/dev/null
check "Cache behaelt hoechstens 3 Eintraege" \
  '[ "$(find "$CACHE" -mindepth 1 -maxdepth 1 -type d | wc -l)" -le 3 ]'
check "der frische Eintrag ueberlebt das Aufraeumen" '[ -d "$CACHE/$FIX_SHA" ]'

# Ein unbrauchbares Cache-Verzeichnis darf das Faktenblatt nicht roetlich faerben.
rm -rf "$BE/target"
: > "$TMP/cache-is-a-file"
AUTOMATION_ARCH_CACHE="$TMP/cache-is-a-file" PATH="$TMP/bin" bash "$SCRIPT" "$TMP/fixture" > "$TMP/cache5.txt" 2>/dev/null
RC=$?
check "unbrauchbarer Cache-Pfad: exit 0"            '[ "$RC" -eq 0 ]'
check "unbrauchbarer Cache-Pfad: trotzdem gemessen" 'grep -qx "cycles=1" "$TMP/cache5.txt"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
