#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/arch-findings.py und ncloc.py (TODO b8c06b94).
# Run: bash scripts/tests/test-arch-findings.sh   (exit 0 = alle Checks ok)
#
# Zeitbudget: Sekunden. Die Einstufung liest nur Faktenblaetter; die Fixtures hier sind kleine
# Faktenblaetter mit bekannten Zahlen, je Regel eine Befund- und eine Gegenprobe (Bandgrenzen,
# Hotspot ja/nein, Ratchet-Datei, Kollaborateure 9/10, Paketkopplung ohne Befund, Zyklen,
# Schichtkanten, Frontend, nicht Gemessenes). Kein jdeps, kein Maven, kein Netz.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FINDINGS="$REPO/scripts/public-readiness/arch-findings.py"
NCLOC="$REPO/scripts/public-readiness/ncloc.py"
POOL="$REPO/workflow-pool/mysuricates-architektur-review.json"
CATALOG="$REPO/ai-automation-backend/src/main/resources/audit/rule-keys.json"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}
row() { local IFS="$TAB"; printf '%s\n' "$*"; }
section() {  # $1 = Ausgabe, $2 = Abschnitt -> Zeilen ohne Kopf und Kommentare
  awk -v s="## $2" '/^## / { on = ($0 == s); next } on && NF && !/^#/' "$1"
}
finding() {  # $1 = Ausgabe, $2 = subject -> "ruleKey<TAB>severity" oder leer
  section "$1" findings | awk -F'\t' -v s="$2" '$2 == s { print $1 "\t" $3 }'
}
observed() {  # $1 = Ausgabe, $2 = subject -> art oder leer
  section "$1" observations | awk -F'\t' -v s="$2" '$2 == s { print $1 }'
}
evidence() {  # $1 = Ausgabe, $2 = subject
  section "$1" findings | awk -F'\t' -v s="$2" '$2 == s { print $4 }'
}
summary() {  # $1 = Ausgabe, $2 = Schluessel
  section "$1" findings_summary | sed -n "s/^$2=//p"
}

echo "0) Dateien vorhanden"
check "arch-findings.py existiert"   '[ -f "$FINDINGS" ]'
check "arch-findings.py ausfuehrbar" '[ -x "$FINDINGS" ]'
check "ncloc.py existiert"           '[ -f "$NCLOC" ]'
check "ncloc.py ausfuehrbar"         '[ -x "$NCLOC" ]'
check "Testskript ausfuehrbar"       '[ -x "$HERE/test-arch-findings.sh" ]'
if [ ! -f "$FINDINGS" ] || [ ! -f "$NCLOC" ]; then
  echo; echo "passed=$PASS failed=$FAIL"; exit 1
fi

# ---------------------------------------------------------------------------
# Fixture: Backend-Faktenblatt. Spalten von ## files: ncloc zeilen aenderungen deps datei.
# Jede Datei steht fuer genau einen Fall; die Namen sagen, welchen.
# ---------------------------------------------------------------------------
P="com/automation/service"
backend_sheet() {  # $1 = Ziel, $2 = jdeps-Zustand (Standard ok), $3 = mit Ratchet (1/0)
  local jdeps="${2:-ok}" ratchet="${3:-1}"
  {
    echo "## packages"
    echo "# von${TAB}nach${TAB}anzahl"
    row com.automation.service.dlq com.automation.controller.admin 2
    row com.automation.controller com.automation.repository 3
    row com.automation.controller com.automation.service.workflow 40
    row com.automation.repository com.automation.entity 9
    echo
    echo "## cycles"
    echo "# cycle${TAB}groesse${TAB}pakete"
    row cycle 3 com.automation.k1,com.automation.k2,com.automation.k3
    row cycle 3 com.automation.n1,com.automation.n2,com.automation.n3
    row cycle 3 com.automation.p1,com.automation.p2,com.automation.p3
    row cycle 2 com.automation.q1,com.automation.q2
    row cycle 2 com.automation.r1,com.automation.r2
    row pair com.automation.p1 com.automation.p2
    row pair com.automation.q1 com.automation.q2
    row pair com.automation.r1 com.automation.r2
    echo
    echo "## coupling"
    echo "# paket${TAB}ausgehend${TAB}eingehend${TAB}ausgehende_kanten${TAB}eingehende_kanten"
    row com.automation.controller 52 0 454 0
    row com.automation.service.workflow 24 25 341 77
    echo
    echo "## files"
    echo "# ncloc${TAB}zeilen${TAB}aenderungen${TAB}deps${TAB}datei"
    row 249 300 9 3 "$P/Band249.java"
    row 250 300 9 3 "$P/Band250.java"
    row 400 480 9 3 "$P/Band400.java"
    row 401 480 9 3 "$P/Band401.java"
    row 600 700 9 3 "$P/Band600.java"
    row 601 700 9 3 "$P/Band601.java"
    row 601 700 9 10 "$P/Band601Deps10.java"
    row 601 700 9 11 "$P/Band601Deps11.java"
    row 900 950 9 - "$P/BigRecord.java"
    row 500 600 4 3 "$P/StableBig.java"
    row 500 600 5 3 "$P/HotBig.java"
    row 500 440 2 4 "$P/RatchetStable.java"
    row 500 440 6 4 "$P/RatchetHot.java"
    row 150 200 9 9 "$P/Deps9Hot.java"
    row 150 200 9 10 "$P/Deps10Hot.java"
    row 150 200 1 10 "$P/Deps10Stable.java"
    row 300 380 1 10 "$P/Deps10StableSized.java"
    row 300 380 9 10 "$P/Deps10HotSized.java"
    row 10 20 0 - "$P/Tiny.java"
    echo
    echo "## ratchet"
    if [ "$ratchet" = 1 ]; then
      row state ok
      row max_lines 300
      row allowlist 440 "$P/RatchetStable.java"
      row allowlist 450 "$P/RatchetHot.java"
      row pair com.automation.q1 com.automation.q2
      row component 4 com.automation.k1,com.automation.k2,com.automation.k3,com.automation.k4
    else
      row state missing
    fi
    echo
    echo "## summary"
    echo "packages=20"
    echo "cycles=5"
    echo "cycles_state=ok"
    echo "files=19"
    [ "$jdeps" = ok ] || echo "error=jdeps_failed"
    echo
    echo "## run"
    echo "compile=reused"
    echo "jdeps=$jdeps"
  } > "$1"
}

frontend_sheet() {  # $1 = Ziel, $2 = unresolved_imports (Standard 0)
  {
    echo "## cycles"
    row cycle 2 a.ts,b.ts
    row cycle 4 c1.ts,c2.ts,c3.ts,c4.ts
    row pair a.ts b.ts
    echo
    echo "## coupling"
    row pages 30 1 552 0
    echo
    echo "## files"
    echo "# ncloc${TAB}zeilen${TAB}aenderungen${TAB}modul"
    row 3100 3120 170 i18n/de.ts
    row 700 760 9 pages/Huge.tsx
    row 450 500 7 pages/Big.tsx
    row 450 500 4 pages/BigStable.tsx
    row 300 340 9 components/Mid.tsx
    row 100 120 30 pages/Small.tsx
    echo
    echo "## summary"
    echo "modules=6"
    echo "cycles=2"
    echo "cycles_state=ok"
    echo "unresolved_imports=${2:-0}"
    [ "${2:-0}" = 0 ] || echo "error=unresolved_imports"
    echo
    echo "## run"
    echo "scan=ok"
  } > "$1"
}

backend_sheet "$TMP/backend.txt"
frontend_sheet "$TMP/frontend.txt"
python3 "$FINDINGS" "$TMP/backend.txt" "$TMP/frontend.txt" > "$TMP/out.txt" 2> "$TMP/out.err"; RC=$?

echo "1) Aufruf und Abschnitte"
check "exit 0, stderr leer" '[ "$RC" -eq 0 ] && [ ! -s "$TMP/out.err" ]'
for s in findings observations bands findings_summary; do
  check "Abschnitt ## $s vorhanden" "grep -qx '## $s' '$TMP/out.txt'"
done
check "jede Befundzeile hat vier Spalten" \
  "section '$TMP/out.txt' findings | awk -F'\t' 'NF != 4 { bad++ } END { exit bad > 0 }'"
check "kein subject enthaelt einen Doppelpunkt" \
  "! section '$TMP/out.txt' findings | cut -f2 | grep -q ':'"
check "not_measured leer bei vollstaendigen Blaettern" '[ -z "$(summary "$TMP/out.txt" not_measured)" ]'
python3 "$FINDINGS" > /dev/null 2>&1; RC_USAGE=$?
check "falscher Aufruf endet mit 2" '[ "$RC_USAGE" -eq 2 ]'

echo "2) NCLOC-Baender (Hotspot, 3 Kollaborateure)"
check "249 NCLOC: weder Befund noch Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" "$P/Band249.java")" ] && [ -z "$(observed "$TMP/out.txt" "$P/Band249.java")" ]'
check "250 NCLOC: Beobachtung"                '[ "$(observed "$TMP/out.txt" "$P/Band250.java")" = groesse ]'
check "250 NCLOC: kein Befund"                '[ -z "$(finding "$TMP/out.txt" "$P/Band250.java")" ]'
check "400 NCLOC: noch Beobachtung"           '[ "$(observed "$TMP/out.txt" "$P/Band400.java")" = groesse ]'
check "401 NCLOC: Befund MEDIUM"              '[ "$(finding "$TMP/out.txt" "$P/Band401.java")" = "arch.god-class${TAB}MEDIUM" ]'
check "600 NCLOC: noch MEDIUM"                '[ "$(finding "$TMP/out.txt" "$P/Band600.java")" = "arch.god-class${TAB}MEDIUM" ]'
check "601 NCLOC: HIGH"                       '[ "$(finding "$TMP/out.txt" "$P/Band601.java")" = "arch.god-class${TAB}HIGH" ]'
check "601 NCLOC + 10 deps: HIGH, kein CRITICAL (erst ueber 10)" \
  '[ "$(finding "$TMP/out.txt" "$P/Band601Deps10.java")" = "arch.high-coupling${TAB}HIGH" ]'
check "601 NCLOC + 11 deps: CRITICAL, ein Befund als arch.high-coupling" \
  '[ "$(finding "$TMP/out.txt" "$P/Band601Deps11.java")" = "arch.high-coupling${TAB}CRITICAL" ]'
check "Nicht-Bean ueber 600: HIGH, nie CRITICAL" \
  '[ "$(finding "$TMP/out.txt" "$P/BigRecord.java")" = "arch.god-class${TAB}HIGH" ]'
check "evidence nennt NCLOC, Zeilen, deps und Aenderungen" \
  '[ "$(evidence "$TMP/out.txt" "$P/Band601Deps11.java")" = "601 NCLOC (700 Zeilen), 11 deps, 9 Aenderungen in 90 Tagen (Hotspot ab 5)" ]'

echo "3) Hotspot ja/nein"
check "500 NCLOC, 4 Aenderungen: Beobachtung, kein Befund" \
  '[ -z "$(finding "$TMP/out.txt" "$P/StableBig.java")" ] && [ "$(observed "$TMP/out.txt" "$P/StableBig.java")" = groesse ]'
check "500 NCLOC, 5 Aenderungen: Befund MEDIUM" \
  '[ "$(finding "$TMP/out.txt" "$P/HotBig.java")" = "arch.god-class${TAB}MEDIUM" ]'
check "stabile Datei: evidence sagt stabil" \
  'section "$TMP/out.txt" observations | grep -q "StableBig.java${TAB}.*4 Aenderungen in 90 Tagen (stabil, Hotspot ab 5)"'

echo "4) Ratchet-Dateien: nie wegen reiner Groesse, nur als Hotspot"
check "Ratchet-Datei, stabil: nur Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" "$P/RatchetStable.java")" ] && [ "$(observed "$TMP/out.txt" "$P/RatchetStable.java")" = groesse ]'
check "Ratchet-Datei, Hotspot: Befund, ohne Herabstufung" \
  '[ "$(finding "$TMP/out.txt" "$P/RatchetHot.java")" = "arch.god-class${TAB}MEDIUM" ]'
check "evidence nennt die Ratchet-Obergrenze ohne Einheit" \
  'evidence "$TMP/out.txt" "$P/RatchetHot.java" | grep -q "Ratchet-Obergrenze 450$"'

echo "5) Kollaborateure 9/10"
check "9 deps, Hotspot, klein: kein Befund, keine Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" "$P/Deps9Hot.java")" ] && [ -z "$(observed "$TMP/out.txt" "$P/Deps9Hot.java")" ]'
check "10 deps, Hotspot, klein: arch.high-coupling MEDIUM" \
  '[ "$(finding "$TMP/out.txt" "$P/Deps10Hot.java")" = "arch.high-coupling${TAB}MEDIUM" ]'
check "10 deps, Hotspot, ab 250 NCLOC: HIGH" \
  '[ "$(finding "$TMP/out.txt" "$P/Deps10HotSized.java")" = "arch.high-coupling${TAB}HIGH" ]'
check "10 deps, stabil: kein Befund" \
  '[ -z "$(finding "$TMP/out.txt" "$P/Deps10Stable.java")" ] && [ -z "$(finding "$TMP/out.txt" "$P/Deps10StableSized.java")" ]'
check "10 deps, stabil: Beobachtung kopplung bzw. groesse+kopplung" \
  '[ "$(observed "$TMP/out.txt" "$P/Deps10Stable.java")" = kopplung ] && [ "$(observed "$TMP/out.txt" "$P/Deps10StableSized.java")" = "groesse+kopplung" ]'
check "kleine, stabile Datei taucht nirgends auf" \
  '! grep -q "Tiny.java" "$TMP/out.txt"'

echo "6) Paketkopplung erzeugt keinen Befund"
check "kein Befund fuer ein Paket (52 ausgehend)" \
  '! section "$TMP/out.txt" findings | cut -f2 | grep -qE "^com\.automation\.(controller|service\.workflow)$"'
check "kein Befund fuer ein Frontend-Verzeichnis (30 ausgehend)" \
  '! section "$TMP/out.txt" findings | cut -f2 | grep -qx "frontend/src/pages"'
check "arch.high-coupling nur fuer Dateien" \
  'section "$TMP/out.txt" findings | awk -F"\t" "\$1 == \"arch.high-coupling\" && \$2 !~ /\\.java\$/ { bad++ } END { exit bad > 0 }"'

echo "7) Paketzyklen und Schichtkanten"
check "bekannte Komponente (Teil von CYCLE_COMPONENTS): MEDIUM" \
  '[ "$(finding "$TMP/out.txt" cycles/component/com.automation.k1)" = "arch.package-cycle${TAB}MEDIUM" ]'
check "neue Komponente: HIGH, evidence nennt die neuen Pakete" \
  '[ "$(finding "$TMP/out.txt" cycles/component/com.automation.n1)" = "arch.package-cycle${TAB}HIGH" ] \
     && evidence "$TMP/out.txt" cycles/component/com.automation.n1 | grep -q "com.automation.n1,com.automation.n2,com.automation.n3"'
check "Komponente mit neuem Paar: CRITICAL" \
  '[ "$(finding "$TMP/out.txt" cycles/component/com.automation.p1)" = "arch.package-cycle${TAB}CRITICAL" ]'
check "Paar in gemeldeter Komponente: kein eigener Befund" \
  '[ -z "$(finding "$TMP/out.txt" "cycles/pair/com.automation.p1<->com.automation.p2")" ]'
check "Paar in ALLOWED_PAIRS: MEDIUM" \
  '[ "$(finding "$TMP/out.txt" "cycles/pair/com.automation.q1<->com.automation.q2")" = "arch.package-cycle${TAB}MEDIUM" ]'
check "neues Paar: HIGH" \
  '[ "$(finding "$TMP/out.txt" "cycles/pair/com.automation.r1<->com.automation.r2")" = "arch.package-cycle${TAB}HIGH" ]'
check "Komponente aus zwei Paketen ist kein Komponenten-Befund" \
  '[ -z "$(finding "$TMP/out.txt" cycles/component/com.automation.q1)" ]'
check "service -> controller: CRITICAL" \
  '[ "$(finding "$TMP/out.txt" "layer/com.automation.service.dlq->com.automation.controller.admin")" = "arch.cross-layer-violation${TAB}CRITICAL" ]'
check "controller -> repository: HIGH" \
  '[ "$(finding "$TMP/out.txt" "layer/com.automation.controller->com.automation.repository")" = "arch.cross-layer-violation${TAB}HIGH" ]'
check "erlaubte Kanten sind kein Befund" '[ "$(section "$TMP/out.txt" findings | grep -c "^arch.cross-layer-violation")" -eq 2 ]'
backend_sheet "$TMP/backend-noratchet.txt" ok 0
python3 "$FINDINGS" "$TMP/backend-noratchet.txt" "$TMP/frontend.txt" > "$TMP/noratchet.txt"
check "ohne Ratchet-Listen keine Herabstufung (bekannte Komponente HIGH)" \
  '[ "$(finding "$TMP/noratchet.txt" cycles/component/com.automation.k1)" = "arch.package-cycle${TAB}HIGH" ]'
check "ohne Ratchet-Listen gilt jedes Paar als neu" \
  '[ "$(finding "$TMP/noratchet.txt" "cycles/pair/com.automation.q1<->com.automation.q2")" = "arch.package-cycle${TAB}HIGH" ]'
check "ohne Ratchet-Listen keine Obergrenze in der evidence" '! grep -q "Ratchet-Obergrenze" "$TMP/noratchet.txt"'

echo "8) Frontend"
check ".tsx ueber 600, Hotspot: HIGH (ohne Kollaborateur-Zahl nie CRITICAL)" \
  '[ "$(finding "$TMP/out.txt" frontend/src/pages/Huge.tsx)" = "arch.god-component${TAB}HIGH" ]'
check ".tsx ueber 400, Hotspot: MEDIUM"     '[ "$(finding "$TMP/out.txt" frontend/src/pages/Big.tsx)" = "arch.god-component${TAB}MEDIUM" ]'
check ".tsx ueber 400, stabil: Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" frontend/src/pages/BigStable.tsx)" ] && [ "$(observed "$TMP/out.txt" frontend/src/pages/BigStable.tsx)" = groesse ]'
check ".ts-Modul, gross und Hotspot: nur Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" frontend/src/i18n/de.ts)" ] && [ "$(observed "$TMP/out.txt" frontend/src/i18n/de.ts)" = groesse-modul ]'
check "Import-Zyklus aus 2 Modulen: HIGH" \
  '[ "$(finding "$TMP/out.txt" cycles/component/frontend/src/a.ts)" = "arch.package-cycle${TAB}HIGH" ]'
check "Import-Zyklus aus 4 Modulen: CRITICAL" \
  '[ "$(finding "$TMP/out.txt" cycles/component/frontend/src/c1.ts)" = "arch.package-cycle${TAB}CRITICAL" ]'
check "## bands zaehlt je Seite alle Dateien" \
  '[ "$(section "$TMP/out.txt" bands | awk -F"\t" "\$1 == \"backend\" { print \$2 + \$3 + \$4 + \$5 }")" -eq 19 ] \
     && [ "$(section "$TMP/out.txt" bands | awk -F"\t" "\$1 == \"frontend\" { print \$2 + \$3 + \$4 + \$5 }")" -eq 6 ]'

echo "9) Nicht Gemessenes ist keine Sauberkeit"
backend_sheet "$TMP/backend-nojdeps.txt" failed
python3 "$FINDINGS" "$TMP/backend-nojdeps.txt" "$TMP/frontend.txt" > "$TMP/nojdeps.txt"
check "jdeps gescheitert: Zyklen und Schichten nicht gemessen" \
  '[ "$(summary "$TMP/nojdeps.txt" not_measured)" = "backend_cycles,backend_layers" ]'
check "jdeps gescheitert: keine Zyklen- oder Schichtbefunde" \
  '! section "$TMP/nojdeps.txt" findings | grep -qE "^arch\.(package-cycle|cross-layer-violation)${TAB}(cycles/component/com|cycles/pair|layer)"'
check "jdeps gescheitert: Groessenbefunde bleiben (Quelltext gemessen)" \
  '[ "$(finding "$TMP/nojdeps.txt" "$P/HotBig.java")" = "arch.god-class${TAB}MEDIUM" ]'
python3 "$FINDINGS" "$TMP/backend.txt" "$TMP/fehlt.txt" > "$TMP/nofront.txt"
check "Frontend-Blatt fehlt: Frontend nicht gemessen" \
  '[ "$(summary "$TMP/nofront.txt" not_measured)" = "frontend_cycles,frontend_sizes" ] && ! grep -q "frontend/src/" "$TMP/nofront.txt"'
frontend_sheet "$TMP/frontend-unresolved.txt" 3
python3 "$FINDINGS" "$TMP/backend.txt" "$TMP/frontend-unresolved.txt" > "$TMP/unresolved.txt"
check "unaufgeloeste Importe: Frontend-Zyklen nicht gemessen, Groessen schon" \
  '[ "$(summary "$TMP/unresolved.txt" not_measured)" = "frontend_cycles" ] \
     && [ "$(finding "$TMP/unresolved.txt" frontend/src/pages/Big.tsx)" = "arch.god-component${TAB}MEDIUM" ]'
: > "$TMP/leer.txt"
python3 "$FINDINGS" "$TMP/leer.txt" "$TMP/leer.txt" > "$TMP/leer.out"; RC_EMPTY=$?
check "leere Blaetter: exit 0, alles nicht gemessen, keine Befunde" \
  '[ "$RC_EMPTY" -eq 0 ] && [ "$(summary "$TMP/leer.out" findings)" = 0 ] \
     && [ "$(summary "$TMP/leer.out" not_measured)" = "backend_cycles,backend_layers,backend_sizes,frontend_cycles,frontend_sizes" ]'

echo "10) Ausgabe stabil und passend zu Prompt und Katalog"
python3 "$FINDINGS" "$TMP/backend.txt" "$TMP/frontend.txt" > "$TMP/out2.txt"
check "zwei Laeufe byte-identisch" 'diff -q "$TMP/out.txt" "$TMP/out2.txt" >/dev/null'
check "Zaehler in ## findings_summary passen zu ## findings" \
  '[ "$(summary "$TMP/out.txt" findings)" -eq "$(section "$TMP/out.txt" findings | wc -l | tr -d " ")" ] \
     && [ "$(summary "$TMP/out.txt" critical)" -eq "$(section "$TMP/out.txt" findings | cut -f3 | grep -cx CRITICAL)" ]'
check "Befunde nach Schwere sortiert (CRITICAL zuerst)" \
  '[ "$(section "$TMP/out.txt" findings | head -1 | cut -f3)" = CRITICAL ]'
check "jeder vergebene ruleKey steht im Prompt und im Katalog" 'python3 - "$TMP/out.txt" "$POOL" "$CATALOG" "$FINDINGS" <<'"'"'PY'"'"'
import json, re, sys
out, pool, catalog, script = sys.argv[1:5]
emitted = {line.split("\t")[0] for line in open(out) if line.startswith("arch.")}
prompt = next(b for b in json.load(open(pool))["blocks"] if b.get("name") == "report")["config"]["command"]
allowed = set(re.findall(r"^- ruleKey \"([a-z0-9.\-]+)\"", prompt, re.M))
declared = set(re.search(r"RULE_KEYS = \((.*?)\)", open(script).read(), re.S).group(1).replace("\"", "").replace(" ", "").replace("\n", "").strip(",").split(","))
keys = set(json.load(open(catalog))["mysuricates.architecture"])
assert emitted and emitted <= declared, (emitted, declared)
assert declared == allowed == keys, (declared, allowed, keys)
PY'
check "evidence traegt die Einheiten der Verschlechterungspruefung (Zeilen, deps)" \
  'evidence "$TMP/out.txt" "$P/Band601Deps11.java" | grep -qE "\(700 Zeilen\), 11 deps"'

echo "11) NCLOC-Zaehlung (ncloc.py)"
mkdir -p "$TMP/nc"
cat > "$TMP/nc/Sample.java" <<'JAVA'
package com.automation.sample;

import java.util.List;
import static java.util.Objects.requireNonNull;

/**
 * Javadoc zaehlt nicht.
 */
public class Sample {
    // Zeilenkommentar zaehlt nicht
    private final String url = "https://example.invalid/a"; // Code mit Kommentar zaehlt
    /* Blockkommentar
       ueber zwei Zeilen */
    private static final String BLOCK = """
            /* kein Kommentar, Textblock */
            """;
    int f() { return 1; } /* dahinter */
}
JAVA
# Zaehlbar: package, class, url, BLOCK, zwei Textblockzeilen, f(), } = 8 von 18 Zeilen.
cat > "$TMP/nc/sample.tsx" <<'TSX'
import {
  a,
  b,
} from './ab';
import type { T } from './t';
import './style.css';
export { c } from './c';

// Kommentar
const url = `https://example.invalid/${a}/${ `x//${b}` }`;
const lazy = () =>
  import('./lazy');
/* Block */ export const X = (p: T) => <div>{url}{lazy}</div>;
TSX
# Zaehlbar: export-from, url, const lazy, import('./lazy'), export const X = 5 von 13 Zeilen.
printf '' > "$TMP/nc/Empty.java"
printf 'Sample.java\nsample.tsx\nEmpty.java\n' | python3 "$NCLOC" "$TMP/nc" > "$TMP/nc.out" 2> "$TMP/nc.err"; RC_NC=$?
check "ncloc.py exit 0, stderr leer" '[ "$RC_NC" -eq 0 ] && [ ! -s "$TMP/nc.err" ]'
check "Java: Leer-, Kommentar- und Importzeilen zaehlen nicht, Textblock zaehlt" \
  'grep -qx "8${TAB}18${TAB}Sample.java" "$TMP/nc.out"'
check "TSX: mehrzeiliger Import, import type und Seiteneffekt-Import zaehlen nicht" \
  'grep -qx "5${TAB}13${TAB}sample.tsx" "$TMP/nc.out"'
check "leere Datei: 0 und 0" 'grep -qx "0${TAB}0${TAB}Empty.java" "$TMP/nc.out"'
check "Reihenfolge wie die Eingabe" '[ "$(cut -f3 "$TMP/nc.out" | tr "\n" " ")" = "Sample.java sample.tsx Empty.java " ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
