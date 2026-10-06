#!/usr/bin/env bash
# Tests for scripts/public-readiness/arch-findings.py and ncloc.py (TODO b8c06b94).
# Run: bash scripts/tests/test-arch-findings.sh   (exit 0 = all checks ok)
#
# Time budget: seconds. The rating only reads fact sheets; the fixtures here are small
# fact sheets with known numbers, per rule one finding and one counter-check (band limits,
# hotspot yes/no, ratchet file, collaborators 9/10, package coupling without finding, cycles,
# layer edges, frontend, not measured). No jdeps, no Maven, no network.
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
section() {  # $1 = output, $2 = section -> lines without header and comments
  awk -v s="## $2" '/^## / { on = ($0 == s); next } on && NF && !/^#/' "$1"
}
finding() {  # $1 = output, $2 = subject -> "ruleKey<TAB>severity" or empty
  section "$1" findings | awk -F'\t' -v s="$2" '$2 == s { print $1 "\t" $3 }'
}
observed() {  # $1 = output, $2 = subject -> kind or empty
  section "$1" observations | awk -F'\t' -v s="$2" '$2 == s { print $1 }'
}
evidence() {  # $1 = output, $2 = subject -> evidence without the leading fact name (Review 4.10.0)
  section "$1" findings | awk -F'\t' -v s="$2" '$2 == s { sub(/^arch_[A-Za-z0-9_.-]*: /, "", $4); print $4 }'
}
summary() {  # $1 = output, $2 = key
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
# Fixture: backend fact sheet. Columns of ## files: ncloc lines changes deps file.
# Each file stands for exactly one case; the names say which.
# ---------------------------------------------------------------------------
P="com/automation/service"
backend_sheet() {  # $1 = target, $2 = jdeps state (default ok), $3 = with ratchet (1/0)
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

frontend_sheet() {  # $1 = target, $2 = unresolved_imports (default 0)
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
  "! section '$TMP/out.txt' findings | cut -f2 | grep ':' >/dev/null"
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
# Switching limit as up to 4.5.2: a size finding becomes arch.high-coupling only from 11 collaborators.
check "601 NCLOC + 10 deps: HIGH, kein CRITICAL, bleibt arch.god-class (Identitaet stabil)" \
  '[ "$(finding "$TMP/out.txt" "$P/Band601Deps10.java")" = "arch.god-class${TAB}HIGH" ]'
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
  'section "$TMP/out.txt" observations | grep "StableBig.java${TAB}.*4 Aenderungen in 90 Tagen (stabil, Hotspot ab 5)" >/dev/null'

echo "4) Ratchet-Dateien: nie wegen reiner Groesse, nur als Hotspot"
check "Ratchet-Datei, stabil: nur Beobachtung" \
  '[ -z "$(finding "$TMP/out.txt" "$P/RatchetStable.java")" ] && [ "$(observed "$TMP/out.txt" "$P/RatchetStable.java")" = groesse ]'
check "Ratchet-Datei, Hotspot: Befund, ohne Herabstufung" \
  '[ "$(finding "$TMP/out.txt" "$P/RatchetHot.java")" = "arch.god-class${TAB}MEDIUM" ]'
check "evidence nennt die Ratchet-Obergrenze ohne Einheit" \
  'evidence "$TMP/out.txt" "$P/RatchetHot.java" | grep "Ratchet-Obergrenze 450$" >/dev/null'

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
  '! section "$TMP/out.txt" findings | cut -f2 | grep -E "^com\.automation\.(controller|service\.workflow)$" >/dev/null'
check "kein Befund fuer ein Frontend-Verzeichnis (30 ausgehend)" \
  '! section "$TMP/out.txt" findings | cut -f2 | grep -x "frontend/src/pages" >/dev/null'
check "arch.high-coupling nur fuer Dateien" \
  'section "$TMP/out.txt" findings | awk -F"\t" "\$1 == \"arch.high-coupling\" && \$2 !~ /\\.java\$/ { bad++ } END { exit bad > 0 }"'

echo "7) Paketzyklen und Schichtkanten"
check "bekannte Komponente (Teil von CYCLE_COMPONENTS): MEDIUM" \
  '[ "$(finding "$TMP/out.txt" cycles/component/com.automation.k1)" = "arch.package-cycle${TAB}MEDIUM" ]'
check "neue Komponente: HIGH, evidence nennt die neuen Pakete" \
  '[ "$(finding "$TMP/out.txt" cycles/component/com.automation.n1)" = "arch.package-cycle${TAB}HIGH" ] \
     && evidence "$TMP/out.txt" cycles/component/com.automation.n1 | grep "com.automation.n1,com.automation.n2,com.automation.n3" >/dev/null'
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
  '! section "$TMP/nojdeps.txt" findings | grep -E "^arch\.(package-cycle|cross-layer-violation)${TAB}(cycles/component/com|cycles/pair|layer)" >/dev/null'
check "jdeps gescheitert: Groessenbefunde bleiben (Quelltext gemessen)" \
  '[ "$(finding "$TMP/nojdeps.txt" "$P/HotBig.java")" = "arch.god-class${TAB}MEDIUM" ]'
with_error() {  # $1 = sheet, $2 = error marker, $3 = target — appends error=<marker> to ## summary
  awk -v e="$2" '{ print } $0 == "## summary" { print "error=" e }' "$1" > "$3"
}
for MARK in collaborators_failed ncloc_failed shallow_history no_git_repo; do
  with_error "$TMP/backend.txt" "$MARK" "$TMP/backend-$MARK.txt"
  python3 "$FINDINGS" "$TMP/backend-$MARK.txt" "$TMP/frontend.txt" > "$TMP/be-$MARK.txt"
  check "Backend error=$MARK: Groessen nicht gemessen, keine Datei-Befunde" \
    '[ "$(summary "$TMP/be-$MARK.txt" not_measured)" = "backend_sizes" ] \
       && ! section "$TMP/be-$MARK.txt" findings | cut -f2 | grep "\.java$" >/dev/null'
  check "Backend error=$MARK: Zyklen und Frontend bleiben gemessen" \
    '[ -n "$(finding "$TMP/be-$MARK.txt" cycles/component/com.automation.k1)" ] \
       && [ -n "$(finding "$TMP/be-$MARK.txt" frontend/src/pages/Big.tsx)" ]'
done
for MARK in ncloc_failed shallow_history; do
  with_error "$TMP/frontend.txt" "$MARK" "$TMP/frontend-$MARK.txt"
  python3 "$FINDINGS" "$TMP/backend.txt" "$TMP/frontend-$MARK.txt" > "$TMP/fe-$MARK.txt"
  check "Frontend error=$MARK: Groessen nicht gemessen, Zyklen schon" \
    '[ "$(summary "$TMP/fe-$MARK.txt" not_measured)" = "frontend_sizes" ] \
       && [ -z "$(finding "$TMP/fe-$MARK.txt" frontend/src/pages/Big.tsx)" ] \
       && [ -n "$(finding "$TMP/fe-$MARK.txt" cycles/component/frontend/src/a.ts)" ]'
done
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
  '[ "$(section "$TMP/out.txt" findings | sed -n 1p | cut -f3)" = CRITICAL ]'
check "jeder vergebene ruleKey steht im Prompt und im Katalog" 'python3 - "$TMP/out.txt" "$POOL" "$CATALOG" "$FINDINGS" <<'"'"'PY'"'"'
import json, re, sys
out, pool, catalog, script = sys.argv[1:5]
emitted = {line.split("\t")[0] for line in open(out) if line.startswith("arch.")}
prompt = next(b for b in json.load(open(pool))["blocks"] if b.get("name") == "report")["config"]["command"]
allowed = set(re.findall(r"^- ruleKey \"([a-z0-9.\-]+)\"", prompt, re.M))
def declared_keys(name):
    body = re.search(r"^" + name + r" = \((.*?)\)", open(script).read(), re.S | re.M).group(1)
    return set(body.replace("\"", "").replace(" ", "").replace("\n", "").strip(",").split(","))
measured, judged = declared_keys("RULE_KEYS"), declared_keys("JUDGMENT_RULE_KEYS")
keys = set(json.load(open(catalog))["mysuricates.architecture"])
# The rating only assigns measurement keys; the judgment keys are assigned by the AI, prompt and catalog know both.
assert emitted and emitted <= measured, (emitted, measured)
assert not measured & judged, (measured, judged)
assert measured | judged == allowed == keys, (measured, judged, allowed, keys)
PY'
check "evidence traegt die Einheiten der Verschlechterungspruefung (Zeilen, deps)" \
  'evidence "$TMP/out.txt" "$P/Band601Deps11.java" | grep -E "\(700 Zeilen\), 11 deps" >/dev/null'

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
# Countable: package, class, url, BLOCK, two text block lines, f(), } = 8 of 18 lines.
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
# Countable: export-from, url, const lazy, import('./lazy'), export const X = 5 of 13 lines.
cat > "$TMP/nc/tricky.tsx" <<'TSX'
const a = '/* kein Kommentar';
const clean = (s: string) => s.replace(/[#*_`>]/g, '');
const half = a.length / 2;
export const V = () => (
  <div>
    {/* JSX-Kommentar */}
    {clean(a)}{half}
  </div>
);
const b = `ende`;
TSX
# Countable: all lines except {/* JSX-Kommentar */} = 9 of 10. The string with /* does not open a
# comment, the backtick in the regex no template string (otherwise everything up to the next
# backtick would fall through as a string), and a.length / 2 is a division, not a regex.
printf '' > "$TMP/nc/Empty.java"
printf 'Sample.java\nsample.tsx\ntricky.tsx\nEmpty.java\n' | python3 "$NCLOC" "$TMP/nc" > "$TMP/nc.out" 2> "$TMP/nc.err"; RC_NC=$?
check "ncloc.py exit 0, stderr leer" '[ "$RC_NC" -eq 0 ] && [ ! -s "$TMP/nc.err" ]'
check "Java: Leer-, Kommentar- und Importzeilen zaehlen nicht, Textblock zaehlt" \
  'grep -qx "8${TAB}18${TAB}Sample.java" "$TMP/nc.out"'
check "TSX: mehrzeiliger Import, import type und Seiteneffekt-Import zaehlen nicht" \
  'grep -qx "5${TAB}13${TAB}sample.tsx" "$TMP/nc.out"'
check "TSX: /* im String, {/* */} in JSX, Regex mit Backtick, Division" \
  'grep -qx "9${TAB}10${TAB}tricky.tsx" "$TMP/nc.out"'
check "leere Datei: 0 und 0" 'grep -qx "0${TAB}0${TAB}Empty.java" "$TMP/nc.out"'
check "Reihenfolge wie die Eingabe" '[ "$(cut -f3 "$TMP/nc.out" | tr "\n" " ")" = "Sample.java sample.tsx tricky.tsx Empty.java " ]'

echo "12) Flacher Klon: fehlende Historie ist eine Messluecke, keine Sauberkeit"
# git clone --depth 1 knows only the last commit: every file would have 0 or 1 change, no hotspot
# would be measurable, and all size/coupling findings would silently expire. Both measuring scripts mark this
# as error=shallow_history, the rating then reports the sizes as not measured.
ORIGIN="$TMP/origin"
mkdir -p "$ORIGIN/ai-automation-backend/src/main/java/com/automation/x" "$ORIGIN/ai-automation-frontend/src"
g() { git -C "$ORIGIN" -c user.name=test -c user.email=test@example.invalid -c core.hooksPath=/dev/null -c commit.gpgsign=false "$@"; }
g init -q
for i in 1 2 3; do
  printf 'package com.automation.x;\npublic class X {\n    int v = %s;\n}\n' "$i" > "$ORIGIN/ai-automation-backend/src/main/java/com/automation/x/X.java"
  printf 'export const v = %s;\n' "$i" > "$ORIGIN/ai-automation-frontend/src/v.ts"
  g add -A && g commit -q -m "stand $i"
done
git clone -q --depth 1 "file://$ORIGIN" "$TMP/shallow" 2>/dev/null
git clone -q "file://$ORIGIN" "$TMP/full" 2>/dev/null
for KIND in shallow full; do
  ARCH_METRICS_COMPILE=0 AUTOMATION_ARCH_CACHE="$TMP/cache" \
    bash "$REPO/scripts/public-readiness/arch-metrics.sh" "$TMP/$KIND" > "$TMP/$KIND-backend.txt" 2>/dev/null
  bash "$REPO/scripts/public-readiness/frontend-metrics.sh" "$TMP/$KIND" > "$TMP/$KIND-frontend.txt" 2>/dev/null
  python3 "$FINDINGS" "$TMP/$KIND-backend.txt" "$TMP/$KIND-frontend.txt" > "$TMP/$KIND-findings.txt"
done
check "flacher Klon ist wirklich flach" '[ "$(git -C "$TMP/shallow" rev-parse --is-shallow-repository)" = true ]'
check "arch-metrics.sh: error=shallow_history" 'grep -qE "^error=.*shallow_history" "$TMP/shallow-backend.txt"'
check "frontend-metrics.sh: error=shallow_history" 'grep -qE "^error=.*shallow_history" "$TMP/shallow-frontend.txt"'
check "Einstufung: Groessen beider Seiten nicht gemessen" \
  'summary "$TMP/shallow-findings.txt" not_measured | tr "," "\n" | grep -x backend_sizes >/dev/null \
     && summary "$TMP/shallow-findings.txt" not_measured | tr "," "\n" | grep -x frontend_sizes >/dev/null'
check "Gegenprobe voller Klon: keine shallow_history, Groessen gemessen" \
  '! grep -q shallow_history "$TMP/full-backend.txt" "$TMP/full-frontend.txt" \
     && ! summary "$TMP/full-findings.txt" not_measured | grep -E "(backend|frontend)_sizes" >/dev/null'
check "Gegenprobe voller Klon: Aenderungen gezaehlt (3 je Datei)" \
  'grep -qE "^[0-9]+${TAB}[0-9]+${TAB}3${TAB}-${TAB}com/automation/x/X.java$" "$TMP/full-backend.txt"'

echo "13) Kandidaten fuer das Urteil (## review_candidates, TODO e0e9495f)"
# Own small sheets: one case per file. Columns of ## recent: changes new file.
C="com/automation/cand"
{
  echo "## files"
  row 450 520 5 3 "$C/FindingFew.java"      # finding (hotspot, over 400), few changes
  row 100 140 8 10 "$C/CoupledHot.java"     # finding arch.high-coupling, more changes
  row 300 360 20 3 "$C/ObsMany.java"        # observation, hotspot with many changes
  row 300 360 4 3 "$C/ObsStable.java"       # observation, but no hotspot
  row 120 150 9 2 "$C/Plain.java"           # hotspot without finding and without observation
  row 180 230 1 2 "$C/NewBig.java"          # new, from 100 NCLOC
  row 90 120 1 2 "$C/NewSmall.java"         # new, under 100 NCLOC
  row 260 320 6 2 "$C/NewHot.java"          # new AND hotspot observation: only once, as hotspot
  echo
  echo "## recent"
  echo "# aenderungen${TAB}neu${TAB}datei"
  row 2 0 "$C/CoupledHot.java"
  row 1 1 "$C/NewBig.java"
  row 1 1 "$C/NewSmall.java"
  row 3 1 "$C/NewHot.java"
  echo
  echo "## summary"
  echo "cycles_state=ok"
  echo
  echo "## run"
  echo "jdeps=ok"
} > "$TMP/cand-backend.txt"
{
  echo "## files"
  echo "# ncloc${TAB}zeilen${TAB}aenderungen${TAB}modul"
  row 300 340 12 pages/HotPage.tsx
  row 300 340 30 hooks/useThing.ts
  row 250 300 2 components/NewPanel.tsx
  row 200 240 1 components/NewHelper.ts
  echo
  echo "## recent"
  row 4 0 pages/HotPage.tsx
  row 2 1 components/NewPanel.tsx
  row 1 1 components/NewHelper.ts
  row 1 1 components/__tests__/NewPanel.test.tsx
  echo
  echo "## summary"
  echo "cycles_state=ok"
  echo "unresolved_imports=0"
  echo
  echo "## run"
  echo "scan=ok"
} > "$TMP/cand-frontend.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" "$TMP/cand-frontend.txt" > "$TMP/cand.txt" 2> "$TMP/cand.err"
candidates() { section "$1" review_candidates | awk -F'\t' '{ print $1 " " $2 }'; }
check "Abschnitte in fester Reihenfolge, Werkzeug-Fakten zwischen Kandidaten und Baendern" \
  '[ "$(grep -n "^## " "$TMP/cand.txt" | cut -d: -f2 | tr "\n" " ")" = "## findings ## observations ## review_candidates ## duplicates ## complexity ## bands ## findings_summary ## FAKTENBLATT arch " ]'
check "Hotspots: Befunde zuerst (nach Aenderungen), dann Beobachtungen; neue nach NCLOC; .ts und Tests nie" \
  '[ "$(candidates "$TMP/cand.txt" | tr "\n" "|")" = "hotspot $C/CoupledHot.java|hotspot $C/FindingFew.java|hotspot $C/ObsMany.java|hotspot frontend/src/pages/HotPage.tsx|hotspot $C/NewHot.java|neu frontend/src/components/NewPanel.tsx|neu $C/NewBig.java|" ]'
check "aenderungen_7d aus ## recent, 0 ohne Eintrag" \
  '[ "$(section "$TMP/cand.txt" review_candidates | awk -F"\t" "{ print \$3 }" | tr "\n" " ")" = "2 0 0 4 3 2 1 " ]'
check "angaben = evidence der Einstufung plus Befund bzw. Beobachtung" \
  'section "$TMP/cand.txt" review_candidates | grep -qx "hotspot${TAB}$C/CoupledHot.java${TAB}2${TAB}100 NCLOC (140 Zeilen), 10 deps, 8 Aenderungen in 90 Tagen (Hotspot ab 5); befund arch.high-coupling MEDIUM${TAB}arch_com.automation.cand.CoupledHot.java"'
check "findings_summary zaehlt die Kandidaten" '[ "$(summary "$TMP/cand.txt" review_candidates)" = 7 ]'
check "kein Kandidat ist ein Hotspot ohne Befund oder Beobachtung, keiner stabil" \
  '! section "$TMP/cand.txt" review_candidates | grep -E "Plain.java|ObsStable.java|NewSmall.java" >/dev/null'
# Upper limits: 8 hotspot observations and 5 new files -> 6 and 3.
{
  echo "## files"
  for i in 1 2 3 4 5 6 7 8; do row 300 360 $((10 + i)) 3 "$C/Hot$i.java"; done
  for i in 1 2 3 4 5; do row $((100 + i)) 150 1 2 "$C/New$i.java"; done
  echo
  echo "## recent"
  for i in 1 2 3 4 5; do row 1 1 "$C/New$i.java"; done
  echo
  echo "## summary"
  echo "cycles_state=ok"
  echo
  echo "## run"
  echo "jdeps=ok"
} > "$TMP/cap-backend.txt"
python3 "$FINDINGS" "$TMP/cap-backend.txt" "$TMP/cand-frontend.txt" > "$TMP/cap.txt"
check "hoechstens 6 Hotspots, die mit den meisten Aenderungen" \
  '[ "$(candidates "$TMP/cap.txt" | grep -c "^hotspot $C/Hot")" -eq 6 ] && ! candidates "$TMP/cap.txt" | grep -qE "Hot[12]\.java"'
check "hoechstens 3 neue Dateien, die groessten" \
  '[ "$(candidates "$TMP/cap.txt" | grep -c "^neu")" -eq 3 ] && [ "$(candidates "$TMP/cap.txt" | grep "^neu" | head -1)" = "neu frontend/src/components/NewPanel.tsx" ]'
# Without ## recent (older sheet) there are no new files, and the section says so.
awk '/^## recent$/ { skip = 1; next } /^## / { skip = 0 } !skip' "$TMP/cand-backend.txt" > "$TMP/cand-norecent.txt"
python3 "$FINDINGS" "$TMP/cand-norecent.txt" "$TMP/cand-frontend.txt" > "$TMP/norecent.txt"
check "ohne ## recent: Hinweis und keine neuen Backend-Dateien" \
  'grep -q "^# ## recent fehlt (backend)" "$TMP/norecent.txt" && ! candidates "$TMP/norecent.txt" | grep -q "^neu $C/"'
check "ohne ## recent: Hotspots bleiben" 'candidates "$TMP/norecent.txt" | grep -qx "hotspot $C/CoupledHot.java"'
python3 "$FINDINGS" "$TMP/leer.txt" "$TMP/leer.txt" > "$TMP/leer-cand.out"
check "nichts gemessen: (keine Kandidaten) mit Hinweis" \
  'section "$TMP/leer-cand.out" review_candidates | grep -qx "(keine Kandidaten)" && grep -q "^# Groessen nicht gemessen" "$TMP/leer-cand.out"'

echo "14) Werkzeug-Fakten: CPD-Duplikate als Kandidaten, PMD-Komplexitaet je Kandidat (TODO 67128699)"
W=/workspace/ai-automation-backend/src/main/java
{
  echo "lines,tokens,occurrences"
  echo "40,250,2,10,$W/$C/NewBig.java,12,$W/$C/Plain.java"
  echo "30,200,2,5,$W/$C/CoupledHot.java,7,$W/$C/ObsStable.java"
  echo "20,150,2,5,$W/$C/Plain.java,90,$W/$C/Plain.java"
  echo "18,120,2,3,/workspace/ai-automation-backend/src/test/java/com/automation/cand/PlainTest.java,4,$W/$C/ObsStable.java"
  echo "15,110,3,1,$W/$C/Gamma.java,2,$W/$C/Delta.java,3,$W/$C/Epsilon.java"
  echo "12,105,2,1,$W/$C/Zeta.java,2,$W/$C/Eta.java"
} > "$TMP/cpd-java.csv"
printf 'lines,tokens,occurrences\n' > "$TMP/cpd-ts.csv"
{
  echo "## FAKTENBLATT pmd (PMD 7.28.0, 1 SARIF-Datei)"
  echo "FAKT pmd_findings: 3"
  echo "TREFFER pmd: ai-automation-backend/src/main/java/$C/CoupledHot.java:40 MEDIUM CognitiveComplexity – The method 'run()' has a cognitive complexity of 30, current threshold is 18"
  echo "TREFFER pmd: ai-automation-backend/src/main/java/$C/CoupledHot.java:12 MEDIUM NcssCount – The method 'init()' has a NCSS line count of 70."
  echo "TREFFER pmd: ai-automation-backend/src/main/java/$C/Elsewhere.java:5 MEDIUM CognitiveComplexity – not a candidate"
} > "$TMP/pmd.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" "$TMP/cand-frontend.txt" --cpd java="$TMP/cpd-java.csv" \
  --cpd typescript="$TMP/cpd-ts.csv" --pmd "$TMP/pmd.txt" > "$TMP/tools.txt" 2> "$TMP/tools.err"; RC=$?
check "mit Werkzeug-Eingaben: exit 0, stderr leer" '[ "$RC" -eq 0 ] && [ ! -s "$TMP/tools.err" ]'
check "dublette: groesstes Duplikat, erste freie Datei, die anderen Stellen in angaben" \
  'section "$TMP/tools.txt" review_candidates | grep -qx "dublette${TAB}$C/Plain.java${TAB}0${TAB}40 Zeilen (250 Tokens) doppelt, hier ab Zeile 12; ebenso in $C/NewBig.java:10${TAB}arch_com.automation.cand.Plain.java"'
check "dublette: schon gewaehlte Kandidaten werden nicht erneut subject (CoupledHot -> ObsStable)" \
  'section "$TMP/tools.txt" review_candidates | grep -q "^dublette${TAB}$C/ObsStable.java${TAB}"'
check "dublette: hoechstens 3, Duplikate innerhalb einer Datei und Testdateien zaehlen nicht" \
  '[ "$(section "$TMP/tools.txt" review_candidates | grep -c "^dublette")" -eq 3 ] \
     && ! section "$TMP/tools.txt" review_candidates | grep -q "PlainTest" && ! section "$TMP/tools.txt" review_candidates | grep -q "Zeta"'
check "duplicates: nur dateiuebergreifende, nach Zeilen sortiert, Testpfade entfernt" \
  '[ "$(section "$TMP/tools.txt" duplicates | sed -n 1p)" = "40${TAB}250${TAB}$C/NewBig.java:10, $C/Plain.java:12" ] \
     && [ "$(section "$TMP/tools.txt" duplicates | wc -l | tr -d " ")" -eq 4 ]'
check "Zaehler: 4 ueber Dateigrenzen, 1 innerhalb (Testzeile hat nur noch eine Stelle)" \
  '[ "$(summary "$TMP/tools.txt" duplicates_cross_file)" = 4 ] && [ "$(summary "$TMP/tools.txt" duplicates_within_file)" = 1 ] \
     && [ -z "$(summary "$TMP/tools.txt" duplicates_not_measured)" ]'
check "duplicates: ohne jscpd ist .tsx ausdruecklich als ungeprueft benannt" 'grep -q "^# .tsx ungeprueft" "$TMP/tools.txt"'
check "complexity: nur Kandidaten, je Treffer eine Zeile, Zustand ok" \
  '[ "$(section "$TMP/tools.txt" complexity | cut -f1 | sort -u)" = "$C/CoupledHot.java" ] \
     && [ "$(section "$TMP/tools.txt" complexity | wc -l | tr -d " ")" -eq 2 ] && [ "$(summary "$TMP/tools.txt" complexity_state)" = ok ]'
# Nicht Gemessenes bleibt sichtbar: fehlende CPD-Ausgabe, NICHT GEMESSEN im Faktenblatt, abgeschnittene Liste.
printf 'Error: something broke\n' > "$TMP/cpd-broken.csv"
printf '## FAKTENBLATT pmd\nNICHT GEMESSEN: pmd_findings (keine SARIF-Datei)\n' > "$TMP/pmd-missing.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" "$TMP/cand-frontend.txt" --cpd java="$TMP/cpd-broken.csv" --pmd "$TMP/pmd-missing.txt" > "$TMP/tools-gap.txt"
check "kaputte bzw. fehlende CPD-Ausgabe: beide Sprachen nicht gemessen, keine dublette" \
  '[ "$(summary "$TMP/tools-gap.txt" duplicates_not_measured)" = "java,typescript" ] && ! grep -q "^dublette" "$TMP/tools-gap.txt" \
     && grep -q "^# nicht gemessen: java,typescript" "$TMP/tools-gap.txt"'
check "pmd NICHT GEMESSEN: complexity_state=not_measured" '[ "$(summary "$TMP/tools-gap.txt" complexity_state)" = not_measured ]'
{ cat "$TMP/pmd.txt"; echo "TREFFER pmd: (+12 weitere, nicht einzeln aufgefuehrt)"; } > "$TMP/pmd-cut.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" --pmd "$TMP/pmd-cut.txt" > "$TMP/tools-cut.txt"
check "abgeschnittene Trefferliste: complexity_state=truncated" '[ "$(summary "$TMP/tools-cut.txt" complexity_state)" = truncated ]'
check "ohne Werkzeug-Eingaben: complexity_state=not_given, beide Sprachen nicht gemessen" \
  '[ "$(summary "$TMP/cand.txt" complexity_state)" = not_given ] && [ "$(summary "$TMP/cand.txt" duplicates_not_measured)" = "java,typescript" ]'
python3 "$FINDINGS" "$TMP/cand-backend.txt" --cpd ruby="$TMP/cpd-ts.csv" > /dev/null 2>&1; RC=$?
check "unbekannte CPD-Sprache: exit 2" '[ "$RC" -eq 2 ]'

echo "15) Frontend-Werkzeuge: jscpd (samt .tsx) und lizard (TODO c608cdda)"
F=/workspace/ai-automation-frontend/src
{
  echo "## FAKTENBLATT jscpd (jscpd 5.4.0, 1 SARIF-Datei)"
  echo "FAKT jscpd_findings: 2"
  echo "TREFFER jscpd: ai-automation-frontend/src/components/NewHelper.ts:24 MEDIUM jscpd/duplicate-code – Duplicated code block (300 tokens), duplicated at [pages/HotPage.tsx:28](0)"
  echo "TREFFER jscpd: ai-automation-frontend/src/components/__tests__/X.test.tsx:1 MEDIUM jscpd/duplicate-code – Duplicated code block (90 tokens), duplicated at [components/Y.tsx:2](0)"
} > "$TMP/jscpd.txt"
{
  echo "$F/pages/HotPage.tsx:30: warning: HotPage has 120 NLOC, 22 CCN, 600 token, 0 PARAM, 140 length, 0 ND"
  echo "$F/components/Elsewhere.tsx:3: warning: Elsewhere has 50 NLOC, 16 CCN, 200 token, 1 PARAM, 60 length, 0 ND"
} > "$TMP/lizard.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" "$TMP/cand-frontend.txt" --cpd java="$TMP/cpd-java.csv" \
  --jscpd "$TMP/jscpd.txt" --lizard "$TMP/lizard.txt" > "$TMP/fe-tools.txt" 2> "$TMP/fe-tools.err"; RC=$?
check "jscpd/lizard: exit 0, stderr leer" '[ "$RC" -eq 0 ] && [ ! -s "$TMP/fe-tools.err" ]'
check "jscpd: .tsx-Duplikat steht in der Frontend-Liste, zeilen -, Testdatei faellt weg" \
  'section "$TMP/fe-tools.txt" duplicates | grep -qx -- "-${TAB}300${TAB}frontend/src/components/NewHelper.ts:24, frontend/src/pages/HotPage.tsx:28" \
     && ! grep -q "X.test.tsx" "$TMP/fe-tools.txt"'
check "jscpd: Frontend gemessen, Hinweis nennt jscpd statt ungeprueft" \
  '[ -z "$(summary "$TMP/fe-tools.txt" duplicates_not_measured)" ] && grep -q "^# Frontend samt .tsx jscpd" "$TMP/fe-tools.txt" && ! grep -q "^# .tsx ungeprueft" "$TMP/fe-tools.txt"'
check "jscpd: dublette nennt Tokens ohne Zeilenzahl, schon gewaehlte Kandidaten bleiben aussen vor" \
  'section "$TMP/fe-tools.txt" review_candidates | grep -q "^dublette${TAB}frontend/src/components/NewHelper.ts${TAB}1${TAB}300 Tokens doppelt, hier ab Zeile 24; ebenso in frontend/src/pages/HotPage.tsx:28${TAB}arch_frontend.src.components.NewHelper.ts$"'
check "lizard: Komplexitaet nur fuer Frontend-Kandidaten, Zustand ok" \
  '[ "$(section "$TMP/fe-tools.txt" complexity | cut -f1-3 | tr "\n" "|")" = "frontend/src/pages/HotPage.tsx${TAB}30${TAB}CCN|" ] \
     && [ "$(summary "$TMP/fe-tools.txt" frontend_complexity_state)" = ok ]'
printf 'docker: Error response from daemon: No such image\n' > "$TMP/lizard-broken.txt"
printf '## FAKTENBLATT jscpd\nNICHT GEMESSEN: jscpd_findings (keine SARIF-Datei)\n' > "$TMP/jscpd-missing.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" "$TMP/cand-frontend.txt" --cpd java="$TMP/cpd-java.csv" \
  --jscpd "$TMP/jscpd-missing.txt" --lizard "$TMP/lizard-broken.txt" > "$TMP/fe-gap.txt"
check "Fehlermeldung statt Warnungen: lizard nicht gemessen, keine Frontend-Komplexitaet" \
  '[ "$(summary "$TMP/fe-gap.txt" frontend_complexity_state)" = not_measured ] && ! section "$TMP/fe-gap.txt" complexity | grep -q "^frontend/"'
check "jscpd NICHT GEMESSEN: Frontend-Duplikate nicht gemessen, .tsx als ungeprueft benannt" \
  '[ "$(summary "$TMP/fe-gap.txt" duplicates_not_measured)" = typescript ] && grep -q "^# .tsx ungeprueft" "$TMP/fe-gap.txt"'
printf '\n' > "$TMP/lizard-empty.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" --lizard "$TMP/lizard-empty.txt" > "$TMP/fe-empty.txt"
check "lizard ohne Warnung: gemessen, nur nichts ueber der Schwelle" '[ "$(summary "$TMP/fe-empty.txt" frontend_complexity_state)" = ok ]'
check "dublette wechselt zwischen Backend und Frontend (erst das groesste Backend-, dann das Frontend-Duplikat)" \
  '[ "$(section "$TMP/fe-tools.txt" review_candidates | awk -F"\t" "\$1 == \"dublette\" { print \$2 }" | head -2 | tr "\n" "|")" = "$C/Plain.java|frontend/src/components/NewHelper.ts|" ]'
check "duplicates: Backend-Liste vor der Frontend-Liste" \
  'section "$TMP/fe-tools.txt" duplicates | sed -n 1p | grep -q "^40${TAB}250${TAB}$C/NewBig.java" \
     && section "$TMP/fe-tools.txt" duplicates | tail -1 | grep -q "frontend/src/"'

echo "16) Evidence as fact sheet and measurement scope (TODO 2c07ce7e)"
facts() { section "$1" "FAKTENBLATT arch"; }
# Fact sheets are read into a variable and grepped via here-string: under pipefail, grep -q closing
# the pipe early would turn awk's SIGPIPE into a failed check.
OUT_FACTS="$(facts "$TMP/out.txt")"
check "every measured finding's evidence starts with the name of a FAKT line of the same run" \
  'section "$TMP/out.txt" findings | { ok=1; while IFS="$TAB" read -r rule subj sev ev; do
     grep -q "^FAKT ${ev%%: *}: " <<< "$OUT_FACTS" || ok=0; done; [ "$ok" = 1 ]; }'
check "fact names use allowed characters only and derive from the subject (pair, layer edge, file)" \
  'grep -q "^FAKT arch_cycles.pair.com.automation.q1.and.com.automation.q2: befund arch.package-cycle MEDIUM" <<< "$OUT_FACTS" \
     && grep -q "^FAKT arch_layer.com.automation.controller.to.com.automation.repository: " <<< "$OUT_FACTS" \
     && grep -q "^FAKT arch_frontend.src.pages.Huge.tsx: " <<< "$OUT_FACTS" \
     && [ -z "$(grep "^FAKT " <<< "$OUT_FACTS" | cut -d" " -f2 | grep -v "^[A-Za-z0-9_.-]*:$")" ]'
check "a candidate with a finding gets one FAKT line carrying both" \
  '[ "$(grep -c "^FAKT arch_com.automation.cand.CoupledHot.java: " <<< "$(facts "$TMP/cand.txt")")" -eq 1 ] \
     && grep -q "^FAKT arch_com.automation.cand.CoupledHot.java: befund arch.high-coupling MEDIUM, .*; kandidat hotspot, aenderungen_7d=2, " <<< "$(facts "$TMP/cand.txt")"'
check "every candidate row names a fakt the fact sheet knows" \
  'section "$TMP/fe-tools.txt" review_candidates | while IFS="$TAB" read -r art subj ch det fakt; do
     grep -q "^FAKT $fakt: .*kandidat $art" <<< "$(facts "$TMP/fe-tools.txt")" || exit 1; done'
check "arch_scope lists every dimension and tool that delivered" \
  'grep -qx "FAKT arch_scope: backend_cycles,backend_layers,backend_sizes,frontend_cycles,frontend_sizes,cpd_java,jscpd,lizard" <<< "$(facts "$TMP/fe-tools.txt")"'
check "arch_scope without tools: only the measured dimensions" \
  'grep -qx "FAKT arch_scope: backend_sizes,frontend_cycles,frontend_sizes" <<< "$(facts "$TMP/nojdeps.txt")"'
check "fact names meet the evidence check's minimum length (4 characters)" \
  '! facts "$TMP/fe-tools.txt" | grep "^FAKT " | cut -d" " -f2 | tr -d ":" | grep -E "^.{0,3}$" >/dev/null'
sed 's/pmd/pmd_design/g' "$TMP/pmd.txt" > "$TMP/pmd-design.txt"
python3 "$FINDINGS" "$TMP/cand-backend.txt" --pmd "$TMP/pmd-design.txt" > "$TMP/pmd-design.out"
check "pmd_design: prefix read from the header, complexity and scope as with pmd" \
  '[ "$(summary "$TMP/pmd-design.out" complexity_state)" = ok ] && grep -q "CognitiveComplexity" <<< "$(section "$TMP/pmd-design.out" complexity)" \
     && grep -q "^FAKT arch_scope: .*pmd_design" <<< "$(facts "$TMP/pmd-design.out")"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
