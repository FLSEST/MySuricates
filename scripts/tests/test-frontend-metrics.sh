#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/frontend-metrics.sh (TODO 72c57e66).
# Run: bash scripts/tests/test-frontend-metrics.sh   (exit 0 = alle Checks ok)
#
# Zeitbudget: reines Lesen, kein npm und kein node_modules — der volle Lauf dauert Sekunden.
# Die Fixture kennt ihre Zahlen genau (Zeilen je Datei, ein Zyklus A -> B -> A), damit die
# Checks etwas belegen und nicht nur "irgendeine Ausgabe" abnicken.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/frontend-metrics.sh"
CYCLES="$REPO/scripts/public-readiness/arch_cycles.py"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Alles vor "## run" ist der gemessene, deterministische Teil.
measured() { sed '/^## run$/,$d' "$1"; }

echo "0) Dateien vorhanden"
check "frontend-metrics.sh existiert"   '[ -f "$SCRIPT" ]'
check "frontend-metrics.sh ausfuehrbar" '[ -x "$SCRIPT" ]'
check "arch_cycles.py wird unveraendert mitbenutzt" '[ -f "$CYCLES" ]'
check "Testskript ausfuehrbar"          '[ -x "$HERE/test-frontend-metrics.sh" ]'
if [ ! -f "$SCRIPT" ]; then
  echo; echo "passed=$PASS failed=$FAIL"; exit 1
fi

# ---------------------------------------------------------------------------
# Fixture: ein Mini-Frontend mit bekannten Zahlen.
#   a.ts (4 Zeilen)  -> b.ts        \ wechselseitig, beides Wert-Importe:
#   b.ts (4 Zeilen)  -> a.ts        /  das ist der EINE echte Zyklus
#   t1.ts (2) <-> t2.ts (2)   nur `import type` — darf KEINEN Zyklus ergeben, tsc loescht es
#   mixed.ts (7) -> t1 (nur { type T1 }: Typkante), -> a ({ type A, useB }: Wertkante),
#                  -> t2 (Typ-Query import('./t2').T2), -> lib (typeof import('./lib')),
#                  -> lazy (import('./lazy').then(…) — Promise-Methode, also echt dynamisch)
#   deep/c.tsx (12) -> ../a (mehrzeilig), ../lib (Barrel), '@/a' (tsconfig-Alias),
#                      'react' (extern), './fehlt' (unaufloesbar), './style.css' (Asset),
#                      import('../lazy') (dynamisch)
#   lib/index.ts (3) reexportiert a  -> export-from erzeugt eine Kante
#   lazy.ts (1), leer.ts (0 Zeilen)
#   a.test.ts, __tests__/x.ts, vite-env.d.ts zaehlen nicht mit
# ---------------------------------------------------------------------------
FE="$TMP/fixture/ai-automation-frontend"
make_fixture() {
  rm -rf "$TMP/fixture"
  mkdir -p "$FE/src/deep" "$FE/src/lib" "$FE/src/__tests__"
  printf "import { b } from './b';\nexport const a = 1;\nexport type A = typeof a;\nexport const useB = () => b;\n" > "$FE/src/a.ts"
  printf "import { a } from './a';\nexport const b = 2;\nexport type B = typeof b;\nexport const useA = () => a;\n" > "$FE/src/b.ts"
  printf "import type { T2 } from './t2';\nexport type T1 = { t?: T2 };\n" > "$FE/src/t1.ts"
  printf "import type { T1 } from './t1';\nexport type T2 = { t?: T1 };\n" > "$FE/src/t2.ts"
  # mixed.ts traegt zusaetzlich die drei Formen aus TODO 26b3da40:
  #   Zeile 5: `import('./t2').T2`         -> Typ-Query, Typkante nach t2
  #   Zeile 6: `typeof import('./lib')`    -> Typ-Query, Typkante nach lib
  #   Zeile 7: `import('./lazy').then(…)`  -> Promise-Methode, also echt dynamisch
  cat > "$FE/src/mixed.ts" <<'EOF'
import { type T1 } from './t1';
import { type A, useB } from './a';
export const m = useB;
export type M = T1 | A;
export type QueriedT2 = import('./t2').T2;
export type LibModule = typeof import('./lib');
export const load = () => import('./lazy').then((mod) => mod.lazy);
EOF
  printf "export const lazy = 1;\n" > "$FE/src/lazy.ts"
  cat > "$FE/src/deep/c.tsx" <<'EOF'
import {
  a,
  useB,
} from '../a';
import { all } from '../lib';
import alias from '@/a';
import React from 'react';
import missing from './fehlt';
import './style.css';
import styles from './style.css';
const Lazy = () => import('../lazy');
export const C = () => [a, useB, all, alias, React, missing, styles, Lazy];
EOF
  printf "export { a } from '../a';\nexport const all = 1;\nexport type All = typeof all;\n" > "$FE/src/lib/index.ts"
  printf 'body { color: red; }\n' > "$FE/src/deep/style.css"
  : > "$FE/src/leer.ts"
  printf "import { a } from './a';\nexport const t = a;\n" > "$FE/src/a.test.ts"
  printf "import { a } from '../a';\nexport const t = a;\n" > "$FE/src/__tests__/x.ts"
  printf '/// <reference types="vite/client" />\n' > "$FE/src/vite-env.d.ts"
  printf '{\n  "compilerOptions": {\n    "baseUrl": ".",\n    "paths": { "@/*": ["src/*"] }\n  }\n}\n' > "$FE/tsconfig.json"
}
make_fixture

echo "1) Fixture: Abschnitte und Grundzahlen"
bash "$SCRIPT" "$TMP/fixture" > "$TMP/fix1.txt" 2> "$TMP/fix1.err"; RC=$?
check "exit 0"           '[ "$RC" -eq 0 ]'
check "Ausgabe nicht leer" '[ -s "$TMP/fix1.txt" ]'
for s in packages cycles sizes churn coupling summary run; do
  check "Abschnitt ## $s vorhanden" "grep -qx '## $s' '$TMP/fix1.txt'"
done
check "modules=9 (Testdateien und *.d.ts zaehlen nicht)" 'grep -qx "modules=9" "$TMP/fix1.txt"'
check "test_files=2"                                     'grep -qx "test_files=2" "$TMP/fix1.txt"'
check "scan=ok in ## run"                                'grep -qx "scan=ok" "$TMP/fix1.txt"'
check "scan= steht nicht in ## summary" \
  "! sed -n '/^## summary\$/,/^## run\$/p' '$TMP/fix1.txt' | grep -E '^scan=' >/dev/null"

echo "2) Fixture: bekannte Zeilenzahlen"
check "a.ts hat 4 Zeilen"          "grep -qx '4${TAB}a.ts' '$TMP/fix1.txt'"
check "deep/c.tsx hat 12 Zeilen"   "grep -qx '12${TAB}deep/c.tsx' '$TMP/fix1.txt'"
check "leere Datei mit 0 Zeilen"   "grep -qx '0${TAB}leer.ts' '$TMP/fix1.txt'"
check "max_file_lines=12"          'grep -qx "max_file_lines=12" "$TMP/fix1.txt"'
check "Verzeichnissumme deep=12"   "grep -qx '12${TAB}1${TAB}deep' '$TMP/fix1.txt'"

echo "3) Fixture: bekannter Zyklus A -> B -> A"
check "cycles=1"                  'grep -qx "cycles=1" "$TMP/fix1.txt"'
check "cycle_pairs=1"             'grep -qx "cycle_pairs=1" "$TMP/fix1.txt"'
check "cycles_state=ok"           'grep -qx "cycles_state=ok" "$TMP/fix1.txt"'
check "Zyklus nennt a.ts und b.ts" "grep -qx 'cycle${TAB}2${TAB}a.ts,b.ts' '$TMP/fix1.txt'"
check "Paar nennt a.ts und b.ts"   "grep -qx 'pair${TAB}a.ts${TAB}b.ts' '$TMP/fix1.txt'"

echo "3a) Fixture: Typkanten sind keine Laufzeitkanten (kein Scheinzyklus)"
check "type_edges=5"     'grep -qx "type_edges=5" "$TMP/fix1.txt"'
check "runtime_edges=6"  'grep -qx "runtime_edges=6" "$TMP/fix1.txt"'
check "edges=13 (alle Arten)" 'grep -qx "edges=13" "$TMP/fix1.txt"'
check "der reine import-type-Zyklus t1<->t2 wird NICHT als Zyklus gemeldet" \
  "! grep -q 't1\.ts' <<<\"\$(sed -n '/^## cycles\$/,/^## sizes\$/p' '$TMP/fix1.txt')\""
check "t1 <-> t2 taucht trotzdem als Verzeichniskopplung/Kante auf" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/fix1.txt' | grep -E '^t1\.ts${TAB}[0-9]+${TAB}[0-9]+\$' >/dev/null"
check "gemischter Spezifizierer { type A, useB } bleibt Laufzeitkante (mixed.ts -> a.ts)" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/fix1.txt' | grep -E '^mixed\.ts${TAB}5${TAB}0\$' >/dev/null"

echo "3b) Fixture: dynamische Importe"
check "dynamic_imports=2" 'grep -qx "dynamic_imports=2" "$TMP/fix1.txt"'
check "dynamic_edges=2"   'grep -qx "dynamic_edges=2" "$TMP/fix1.txt"'
check "dynamischer Import zaehlt als Verzeichniskante deep -> (src)" \
  "grep -qx 'deep${TAB}(src)${TAB}2' '$TMP/fix1.txt'"
check "lazy.ts hat zwei eingehende Importe (deep/c.tsx direkt, mixed.ts via .then)"  \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/fix1.txt' | grep -E '^lazy\.ts${TAB}0${TAB}2\$' >/dev/null"
# Die Einordnung selbst ist in der Fixture oben schon gepinnt (type_edges=5 gegen
# dynamic_edges=2; waeren die beiden Typ-Queries dynamisch gezaehlt, stuende dort 3 zu 4).
# Weil die einzelnen edge-Saetze nur in der Zwischendatei stehen und nicht im Faktenblatt,
# belegt eine eigene Mini-Fixture die drei Formen einzeln und ohne Nebenkanten.
echo "3c) Typ-Query gegen echten dynamischen Import (TODO 26b3da40)"
type_query_case() {   # $1 = Inhalt von q.ts, danach: $TMP/tq.txt
  rm -rf "$TMP/tq"
  mkdir -p "$TMP/tq/ai-automation-frontend/src"
  printf 'export const ziel = 1;\nexport type Ziel = typeof ziel;\n' \
    > "$TMP/tq/ai-automation-frontend/src/ziel.ts"
  printf '%s\n' "$1" > "$TMP/tq/ai-automation-frontend/src/q.ts"
  bash "$SCRIPT" "$TMP/tq" > "$TMP/tq.txt" 2>/dev/null
}

type_query_case "export type Q = import('./ziel').Ziel;"
check "import('./ziel').Ziel -> Typkante, nicht dynamisch" \
  'grep -qx "type_edges=1" "$TMP/tq.txt" && grep -qx "dynamic_edges=0" "$TMP/tq.txt" \
     && grep -qx "dynamic_imports=0" "$TMP/tq.txt"'

type_query_case "export type Q = typeof import('./ziel');"
check "typeof import('./ziel') -> Typkante, nicht dynamisch" \
  'grep -qx "type_edges=1" "$TMP/tq.txt" && grep -qx "dynamic_edges=0" "$TMP/tq.txt"'

type_query_case "export const q = () => import('./ziel').then((m) => m.ziel);"
check "import('./ziel').then(…) bleibt dynamisch (Promise-Methode, keine Typ-Query)" \
  'grep -qx "dynamic_edges=1" "$TMP/tq.txt" && grep -qx "dynamic_imports=1" "$TMP/tq.txt" \
     && grep -qx "type_edges=0" "$TMP/tq.txt"'

type_query_case "export const q = async () => (await import('./ziel')).ziel;"
check "(await import('./ziel')).ziel bleibt dynamisch" \
  'grep -qx "dynamic_edges=1" "$TMP/tq.txt" && grep -qx "type_edges=0" "$TMP/tq.txt"'
check "dynamischer Import ist weder external noch unresolved" \
  'grep -qx "external_imports=1" "$TMP/fix1.txt" && grep -qx "unresolved_imports=1" "$TMP/fix1.txt"'

echo "4) Fixture: Aufloesung von Importen"
check "mehrzeiliger Import wird gesehen (deep/c.tsx -> a.ts)" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/fix1.txt' | grep -E '^a\.ts${TAB}[0-9]+${TAB}[0-9]+\$' >/dev/null"
check "Barrel-Import ueber index.ts aufgeloest (Verzeichniskante deep -> lib)" \
  "grep -qE '^deep${TAB}lib${TAB}[0-9]+\$' '$TMP/fix1.txt'"
check "export-from erzeugt eine Kante (lib -> (src))" \
  "grep -qE '^lib${TAB}\(src\)${TAB}[0-9]+\$' '$TMP/fix1.txt'"
check "tsconfig-Alias @/ aufgeloest (aliases=1)" 'grep -qx "aliases=1" "$TMP/fix1.txt"'
check "Paket-Import zaehlt als external"         'grep -qx "external_imports=1" "$TMP/fix1.txt"'
check "vorhandene Nicht-Modul-Datei zaehlt als asset" 'grep -qx "asset_imports=1" "$TMP/fix1.txt"'
check "fehlender relativer Import wird gemeldet" 'grep -qx "unresolved_imports=1" "$TMP/fix1.txt"'
check "unresolved landet in error="              'grep -qE "^error=.*unresolved_imports" "$TMP/fix1.txt"'
check "Seiteneffekt-Import erzeugt keine Kante (nur 1 Asset)" \
  'grep -qx "asset_imports=1" "$TMP/fix1.txt"'

echo "4a) Relativer Aufrufpfad findet arch_cycles.py trotzdem"
# Der Aufruf im Pool-Workflow lautet `bash scripts/public-readiness/frontend-metrics.sh "$BASE"`.
# Wird SCRIPT_DIR erst nach dem cd in die gemessene Wurzel aufgeloest, zeigt der relative
# Pfad ins Leere und die Zyklen gelten still als nicht gemessen.
( cd "$REPO" && bash scripts/public-readiness/frontend-metrics.sh "$TMP/fixture" ) \
  > "$TMP/relpath.txt" 2>/dev/null
check "relativer Skriptpfad: cycles_state=ok" 'grep -qx "cycles_state=ok" "$TMP/relpath.txt"'
check "relativer Skriptpfad: Zyklus gefunden" "grep -qx 'pair${TAB}a.ts${TAB}b.ts' '$TMP/relpath.txt'"

echo "5) Zwei Laeufe liefern identische Messung (Determinismus)"
bash "$SCRIPT" "$TMP/fixture" > "$TMP/fix2.txt" 2> "$TMP/fix2.err"; RC=$?
measured "$TMP/fix1.txt" > "$TMP/fix1.measured"; measured "$TMP/fix2.txt" > "$TMP/fix2.measured"
check "zweiter Lauf exit 0"            '[ "$RC" -eq 0 ]'
check "gemessener Teil byte-identisch" 'diff -q "$TMP/fix1.measured" "$TMP/fix2.measured" >/dev/null'
if ! diff -q "$TMP/fix1.measured" "$TMP/fix2.measured" >/dev/null 2>&1; then
  diff -u "$TMP/fix1.measured" "$TMP/fix2.measured" | head -20
fi

echo "6) Fehlerpfade melden NICHT GEMESSEN statt Sauberkeit"
OUT=$(bash "$SCRIPT" "$TMP/gibt-es-nicht" 2>&1); RC=$?
check "exit 2 bei fehlendem Verzeichnis" '[ "$RC" -eq 2 ]'
check "Meldung nennt das Verzeichnis"    'grep -q "gibt-es-nicht" <<<"$OUT"'

mkdir -p "$TMP/leer"
bash "$SCRIPT" "$TMP/leer" > "$TMP/leer.out" 2> "$TMP/leer.err"; RC=$?
check "kein Frontend: exit 0"                '[ "$RC" -eq 0 ]'
check "kein Frontend: summary vorhanden"     'grep -qx "## summary" "$TMP/leer.out"'
check "kein Frontend: error nennt source_missing" 'grep -qE "^error=.*source_missing" "$TMP/leer.out"'
check "kein Frontend: scan=no_source"        'grep -qx "scan=no_source" "$TMP/leer.out"'
check "kein Frontend: packages sagt NICHT GEMESSEN" \
  "sed -n '/^## packages\$/,/^## cycles\$/p' '$TMP/leer.out' | grep 'NICHT GEMESSEN' >/dev/null"
check "kein Frontend: behauptet keine Zyklenfreiheit" \
  "! grep -q 'keine Import-Zyklen zwischen Modulen gefunden' '$TMP/leer.out'"

mkdir -p "$TMP/stub-python"
printf '#!/bin/sh\nexit 1\n' > "$TMP/stub-python/python3"; chmod 755 "$TMP/stub-python/python3"
PATH="$TMP/stub-python:$PATH" bash "$SCRIPT" "$TMP/fixture" > "$TMP/fail-py.txt" 2>/dev/null; RC=$?
check "python3-Fehlschlag: exit 0 (Faktenblatt bleibt)" '[ "$RC" -eq 0 ]'
check "python3-Fehlschlag: scan=failed"                 'grep -qx "scan=failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: error nennt scan_failed"     'grep -qE "^error=.*scan_failed" "$TMP/fail-py.txt"'
check "python3-Fehlschlag: cycles sagt NICHT GEMESSEN"  \
  "sed -n '/^## cycles\$/,/^## sizes\$/p' '$TMP/fail-py.txt' | grep 'NICHT GEMESSEN' >/dev/null"
check "python3-Fehlschlag: behauptet keine Zyklenfreiheit" \
  "! grep -q 'keine Import-Zyklen zwischen Modulen gefunden' '$TMP/fail-py.txt'"

echo "7) Lauf gegen das echte Frontend"
bash "$SCRIPT" "$REPO" > "$TMP/real.txt" 2> "$TMP/real.err"; RC=$?
check "exit 0"                        '[ "$RC" -eq 0 ]'
check "scan=ok"                       'grep -qx "scan=ok" "$TMP/real.txt"'
check "modules>0"                     'sed -n "/^## summary\$/,/^## run\$/p" "$TMP/real.txt" | grep -E "^modules=[1-9][0-9]*\$" >/dev/null'
check "edges>0"                       'sed -n "/^## summary\$/,/^## run\$/p" "$TMP/real.txt" | grep -E "^edges=[1-9][0-9]*\$" >/dev/null'
check "keine unaufloesbaren Importe"  'grep -qx "unresolved_imports=0" "$TMP/real.txt"'
check "Typkanten werden getrennt ausgewiesen" \
  'sed -n "/^## summary\$/,/^## run\$/p" "$TMP/real.txt" | grep -E "^type_edges=[1-9][0-9]*\$" >/dev/null'
check "dynamische Importe werden gefunden (Router-Lazy-Loading)" \
  'sed -n "/^## summary\$/,/^## run\$/p" "$TMP/real.txt" | grep -E "^dynamic_imports=[1-9][0-9]*\$" >/dev/null'
check "edges = runtime + type + dynamic" 'python3 - "$TMP/real.txt" <<'"'"'PY'"'"'
import re, sys
text = open(sys.argv[1]).read()
def value(key):
    return int(re.search(r"(?m)^%s=(\d+)$" % key, text).group(1))
assert value("edges") == value("runtime_edges") + value("type_edges") + value("dynamic_edges")
PY'
check "sizes liefert Zeilen/Pfad-Paare" \
  "sed -n '/^## sizes\$/,/^## churn\$/p' '$TMP/real.txt' | grep -E '^[0-9]+${TAB}[a-z].*\.tsx?\$' >/dev/null"
check "coupling liefert Verzeichnis-Kennzahlen" \
  "sed -n '/^## coupling\$/,/^## summary\$/p' '$TMP/real.txt' | grep -E '^[a-z(][^${TAB}]*(${TAB}[0-9]+){4}\$' >/dev/null"
check "keine absoluten Benutzerpfade in der Ausgabe" \
  "! grep -qE '(/home/|/Users/)[a-zA-Z_-]+/' '$TMP/real.txt'"
check "churn gemessen" 'sed -n "/^## summary\$/,/^## run\$/p" "$TMP/real.txt" | grep -E "^churn_files_90d=[0-9]+\$" >/dev/null'

echo "8) Pool-Workflow ruft das Skript auf und uebergibt es an den KI-Block"
POOL="$REPO/workflow-pool/mysuricates-architektur-review.json"
check "Pool-Datei ist valides JSON"          'python3 -m json.tool "$POOL" > /dev/null'
check "Shell-Block ruft frontend-metrics.sh" 'grep -q "frontend-metrics.sh" "$POOL"'
check "Pool-Vertrag (Reihenfolge, Name, Prompt)" 'python3 - "$POOL" <<'"'"'PY'"'"'
import json, sys
wf = json.load(open(sys.argv[1]))
blocks = wf["blocks"]
i_metrics = next(i for i, b in enumerate(blocks)
                 if b["type"] == "shell" and "frontend-metrics.sh" in b["config"].get("command", ""))
i_report = next(i for i, b in enumerate(blocks) if b.get("name") == "report")
assert i_metrics < i_report, "Frontend-Metrik-Block muss vor dem Report-Block liegen"
metrics = blocks[i_metrics]
name = metrics.get("name")
assert name, "Metrik-Block braucht einen Namen"
assert "set -euo pipefail" in metrics["config"]["command"], "Shell-Block ohne set -euo pipefail"
assert metrics["config"].get("timeout_seconds", 0) >= 60
prompt = blocks[i_report]["config"]["command"]
assert "{{ block.%s.output }}" % name in prompt, "Report-Prompt referenziert das Frontend-Blatt nicht"
assert "Frontend-Zahlen" in prompt, "Regel fuer die Herkunft der Frontend-Zahlen fehlt"
PY'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
