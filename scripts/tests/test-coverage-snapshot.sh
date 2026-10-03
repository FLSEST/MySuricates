#!/usr/bin/env bash
# Tests fuer den Messblock von workflow-pool/mysuricates-test-coverage.json (TODO 68f37d25).
#
# Das Audit-Center wertet den Envelope des Report-Blocks als vollstaendigen Snapshot:
# ein Finding, das im naechsten Snapshot fehlt, wird RESOLVED. Solange der Messblock
# nur ein Top-N-Fenster liefert, verschwinden Klassen aus dem Snapshot, ohne getestet
# worden zu sein. Dieser Test pinnt deshalb: der Messblock listet ALLE Klassen unter
# der Schwelle, nennt die Gesamtzahl und liefert je Klasse genau eine kompakte Zeile.
#
# Run: bash scripts/tests/test-coverage-snapshot.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEF="$REPO/workflow-pool/mysuricates-test-coverage.json"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
TAB="$(printf '\t')"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# ---------------------------------------------------------------------------
# Fixture: 21 ungetestete Klassen (mehr als das alte Top-15-Fenster), davon eine
# ohne jede "public "-Zeile, zwei duenn getestete und eine getestete Klasse ohne
# "public "-Zeile. Die Klassen ohne "public " sind der Regressionsfall zu
# "grep -c ... || echo 0": grep gibt bei 0 Treffern "0" aus UND Exit 1 zurueck,
# das alte "|| echo 0" haengte eine zweite Null an. Im UNTESTED-Abschnitt
# verschob das die Spalten der Messzeile (im Repo 24 von 115 Zeilen), in den
# duennen Tests brach es die Pruefung mit "Ganzzahliger Ausdruck erwartet" ab.
# ---------------------------------------------------------------------------
FIX="$TMP/fixture"
MAIN="$FIX/ai-automation-backend/src/main/java/com/automation"
TESTS="$FIX/ai-automation-backend/src/test/java/com/automation"
mkdir -p "$MAIN/service/deep" "$MAIN/controller" "$TESTS/service/deep" "$FIX/ai-automation-frontend/src"

java_class() {  # $1 = datei, $2 = zeilen, $3 = anzahl public-methoden
  local file="$1" lines="$2" pubs="$3" i
  { echo "package com.automation;"
    for ((i = 0; i < pubs; i++)); do echo "    public void m$i() {}"; done
    for ((i = 0; i < lines - pubs - 1; i++)); do echo "    // filler $i"; done
  } > "$file"
}

for i in $(seq -w 1 20); do
  java_class "$MAIN/service/deep/Untested$i.java" "$((10#$i * 10))" 3
done
# Duenn getestet: 8 public, 2 @Test  -> 25 %
java_class "$MAIN/controller/ThinOne.java" 60 8
{ echo "class ThinOneTest {"; echo "    @Test void a() {}"; echo "    @Test void b() {}"; echo "}"; } \
  > "$TESTS/ThinOneTest.java"
# Duenn getestet: 10 public, 1 @Test -> 10 %
java_class "$MAIN/controller/ThinTwo.java" 70 10
{ echo "class ThinTwoTest {"; echo "    @Test void a() {}"; echo "}"; } > "$TESTS/ThinTwoTest.java"
# Getestet, aber ohne public-Zeile: darf keinen Shell-Fehler ausloesen
{ echo "package com.automation.controller;"; echo "class NoPublic {}"; } > "$MAIN/controller/NoPublic.java"
{ echo "class NoPublicTest {"; echo "    @Test void a() {}"; echo "}"; } > "$TESTS/NoPublicTest.java"
# Ungetestet UND ohne public-Zeile: der im Repo haeufige Fall (24 von 115),
# dessen Messzeile die alte Variante verstuemmelt hat
{ echo "package com.automation.controller;"; echo "enum NoPublicUntested { A, B }"; } \
  > "$MAIN/controller/NoPublicUntested.java"
# Records und Interfaces als Top-Level-Typ sind keine Testgegenstaende (59e22c53) — auch ohne public.
{ echo "package com.automation.service.deep;"; echo "public record PlainRecord(String a) {}"; } \
  > "$MAIN/service/deep/PlainRecord.java"
{ echo "package com.automation.service.deep;"; echo "interface PlainPort {"; echo "    void run();"; echo "}"; } \
  > "$MAIN/service/deep/PlainPort.java"
{ echo "package com.automation.service.deep;"; echo "public sealed interface Shape permits Circle {}"; } \
  > "$MAIN/service/deep/Shape.java"
echo "export const x = 1;" > "$FIX/ai-automation-frontend/src/x.ts"
git init -q "$FIX" && ( cd "$FIX" && git -c user.name=t -c user.email=t@example.invalid \
  add -A && git -c user.name=t -c user.email=t@example.invalid commit -q -m init )

# ---------------------------------------------------------------------------
# Messblock aus der Pool-Definition ziehen und gegen das Fixture laufen lassen
# ---------------------------------------------------------------------------
python3 - "$DEF" "$FIX" > "$TMP/command.sh" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
blocks = [b for b in definition['blocks']
          if b.get('config', {}).get('label', '').startswith('Coverage-Metriken')]
assert len(blocks) == 1, f"erwartet genau einen Messblock, gefunden: {len(blocks)}"
print(blocks[0]['config']['command'].replace('{{ project_path }}', sys.argv[2]))
PY
check "Messblock extrahierbar" '[ -s "$TMP/command.sh" ]'

( cd "$FIX" && bash "$TMP/command.sh" ) > "$TMP/out.txt" 2> "$TMP/err.txt"
UNTESTED_ROWS="$TMP/untested.txt"
awk '/^UNTESTED gesamt:/{f=1;next} f && /^[[:space:]]*$/{f=0} f' "$TMP/out.txt" > "$UNTESTED_ROWS"
THIN_ROWS="$TMP/thin.txt"
awk '/^DUENNE TESTS gesamt:/{f=1;next} f && /^[[:space:]]*$/{f=0} f' "$TMP/out.txt" > "$THIN_ROWS"

echo "1) Vollstaendigkeit statt Top-N-Fenster"
check "Zaehlzeile 'UNTESTED gesamt: 21'"      'grep -qx "UNTESTED gesamt: 21" "$TMP/out.txt"'
check "21 gelistete ungetestete Klassen"      '[ "$(wc -l < "$UNTESTED_ROWS")" -eq 21 ]'
check "kleinste Klasse (Rang 20) gelistet"    'grep -q "Untested01.java" "$UNTESTED_ROWS"'
check "groesste Klasse gelistet"              'grep -q "Untested20.java" "$UNTESTED_ROWS"'
check "Klasse ohne public-Zeile gelistet"     'grep -q "^controller/NoPublicUntested\.java${TAB}[0-9]\+$" "$UNTESTED_ROWS"'
check "kein head -15 im Messblock"            '! grep -q "head -15" "$TMP/command.sh"'
check "Record/Interface nicht gelistet"       '! grep -qE "PlainRecord|PlainPort|Shape" "$UNTESTED_ROWS"'
check "Record/Interface als ausgenommen gezaehlt" \
  'grep -qx "Datenklassen ausgenommen (DTO/Entity/Repository/Event/Enum/Record/Interface): 3" "$TMP/out.txt"'

echo "2) Kompaktes, stabiles Zeilenformat (paket/Klasse.java<TAB>LOC)"
check "jede Zeile paket/Klasse.java<TAB>LOC" \
  'ROWS=$(grep -cE "^[a-z][a-zA-Z0-9/]*/[A-Za-z0-9]+\.java${TAB}[0-9]+$" "$UNTESTED_ROWS"); [ "$ROWS" -eq 21 ]'
check "kein com/automation-Praefix"           '! grep -q "com/automation" "$UNTESTED_ROWS"'
check "kein absoluter Pfad in den Zeilen"     '! grep -q "^/" "$UNTESTED_ROWS"'
check "absteigend nach LOC sortiert" \
  'cut -f2 "$UNTESTED_ROWS" | sort -rn | cmp -s - <(cut -f2 "$UNTESTED_ROWS")'
check "erste Zeile ist die groesste Klasse"   'head -1 "$UNTESTED_ROWS" | grep "Untested20.java" >/dev/null'

echo "3) Duenne Tests ebenfalls vollstaendig und ohne Shell-Fehler"
check "Zaehlzeile 'DUENNE TESTS gesamt: 2'"   'grep -qx "DUENNE TESTS gesamt: 2" "$TMP/out.txt"'
check "2 gelistete duenne Tests"              '[ "$(wc -l < "$THIN_ROWS")" -eq 2 ]'
check "ThinOne gelistet"                      'grep -q "^ThinOne\.java${TAB}" "$THIN_ROWS"'
check "ThinTwo gelistet"                      'grep -q "^ThinTwo\.java${TAB}" "$THIN_ROWS"'
check "keine Ganzzahl-Fehlermeldung (stderr)" '! grep -qiE "Ganzzahl|integer expression" "$TMP/err.txt"'

echo "4) Soll-Groesse des Snapshots"
check "Zeile 'ERWARTETE FINDINGS: 23'"        'grep -qx "ERWARTETE FINDINGS: 23" "$TMP/out.txt"'

echo "5) Report-Prompt bindet den Snapshot an die vollstaendige Liste"
# Seit fa66ea31 setzt der Shell-Block report (metadata.audit.reportBlock) den Envelope zusammen; der
# KI-Prompt steht im Block metadata.audit.promptBlock.
PROMPT="$TMP/prompt.txt"
python3 - "$DEF" > "$PROMPT" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
audit = definition['metadata']['audit']
prompt = audit.get('promptBlock', audit['reportBlock'])
print(next(b['config']['command'] for b in definition['blocks'] if b.get('name') == prompt))
PY
check "Prompt nennt 'UNTESTED gesamt'"        'grep -q "UNTESTED gesamt" "$PROMPT"'
check "Prompt nennt kein Top-15-Fenster"      '! grep -q "Top 15" "$PROMPT"'
check "Prompt begruendet snapshotComplete"    'grep -q "snapshotComplete" "$PROMPT"'
check "Prompt kennt den Unvollstaendig-Fall"  'grep -q "snapshotComplete\": *false\|snapshotComplete\":false" "$PROMPT"'
# 1cf996df: Empfohlen wird nur, was nicht bewusst eingeordnet ist; im Snapshot bleibt jede gelistete
# Klasse, sonst stimmte die Soll-Zahl nicht mehr.
check "Prompt bekommt die bekannten Befunde"   'grep -q "{{ audit_findings }}" "$PROMPT"'
check "Empfehlung nur nicht eingeordnete Klassen" \
  'grep -q "^Waehlbar fuer NAECHSTER SCHRITT und die Roadmap (naechste 5) sind nur Klassen, die unter BEKANNTE BEFUNDE nicht ACCEPTED_RISK oder DISMISSED sind" "$PROMPT"'
check "Prompt sagt: eingeordnete Klassen bleiben im Envelope (Zusammenbau, Abschnitt 9)" \
  'grep -q "laesst keine gelistete Klasse weg, auch keine eingeordnete (ACCEPTED_RISK, DISMISSED; sie zaehlen in ERWARTETE FINDINGS mit)" "$PROMPT"'
check "Prompt verlangt keinen eigenen Envelope mehr" \
  'grep -q "Den Envelope (mysuricates:audit-result:v1) schreibst du NICHT" "$PROMPT" && ! grep -q "^Schliesse die Antwort mit genau einem versteckten Envelope ab" "$PROMPT"'
check "Prompt fordert naechster_schritt und BEFUND-TEXTE an" \
  'grep -q "naechster_schritt: <subject>" "$PROMPT" && grep -q "^2. Ein Abschnitt, der mit der Zeile \"BEFUND-TEXTE:\" beginnt" "$PROMPT"'

echo "6) Gate-Verdict prueft den Snapshot deterministisch"
# Platzhalter werden wie in TemplateResolver.resolveShellSafe single-quoted eingesetzt
# (Block fuehrt "shell_escape_variables": true), damit der Block lokal laufen kann.
render_gate() {  # $1 = previous_output, $2 = metrik-output, $3 = report-output
  python3 - "$DEF" "$1" "$2" "$3" <<'PY'
import json, re, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
blocks = [b for b in definition['blocks']
          if b.get('config', {}).get('label', '').startswith('Gate-Verdict')]
assert len(blocks) == 1, f"erwartet genau einen Gate-Block, gefunden: {len(blocks)}"
config = blocks[0]['config']
assert config.get('shell_escape_variables') is True, "Gate-Block braucht shell_escape_variables"
values = {'previous_output': sys.argv[2],
          'block.metrics.output': sys.argv[3],
          'block.report.output': sys.argv[4]}
def substitute(match):
    key = match.group(1).strip()
    assert key in values, f"unerwarteter Platzhalter: {key}"
    return "'" + values[key].replace("'", "'\\''") + "'"
print(re.sub(r'\{\{\s*([^}]+?)\s*}}', substitute, config['command']))
PY
}

envelope_report() {  # $1 = anzahl ruleKeys, $2 = snapshotComplete (true|false)
  local i findings=""
  for ((i = 0; i < $1; i++)); do
    findings="$findings{\"ruleKey\":\"test-coverage.untested-class\",\"subject\":\"p/C$i.java\",\"severity\":\"MEDIUM\",\"title\":\"t\"},"
  done
  printf '### Coverage-Coach\n\ngate_status: YELLOW\n\n<!-- mysuricates:audit-result:v1\n{"snapshotComplete":%s,"findings":[%s]}\n-->\n' \
    "$2" "${findings%,}"
}

GATE_METRICS="$(printf 'UNTESTED gesamt: 20\nDUENNE TESTS gesamt: 0\nERWARTETE FINDINGS: 20\n')"
GATE_PREV="$(printf 'Kurzfassung\ngate_status: YELLOW\n')"

run_gate() {  # $1 = report-output, schreibt Gate-Ausgabe nach $TMP/gate.txt
  render_gate "$GATE_PREV" "$GATE_METRICS" "$1" > "$TMP/gate.sh"
  bash "$TMP/gate.sh" > "$TMP/gate.txt" 2> "$TMP/gate-err.txt"
}

run_gate "$(envelope_report 20 true)"
check "20/20 Findings: nicht RED"            '! grep -q "gate_status: RED" "$TMP/gate.txt"'
check "20/20 Findings: Status aus Report"    'grep -qx "gate_status: YELLOW" "$TMP/gate.txt"'
check "20/20 Findings: test-coverage"        'grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
check "Gate laeuft ohne stderr"              '[ ! -s "$TMP/gate-err.txt" ]'

run_gate "$(envelope_report 18 true)"
check "18/20 Findings: RED"                  'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "18/20 Findings: audit-incomplete"     'grep -qx "gate_category: audit-incomplete" "$TMP/gate.txt"'
check "18/20 Findings: Zahlen im Grund"      'grep "^gate_description:" "$TMP/gate.txt" | grep "18" >/dev/null && grep "^gate_description:" "$TMP/gate.txt" | grep "20" >/dev/null'

run_gate "$(printf '### Coverage-Coach\n\ngate_status: GREEN\n')"
check "kein Envelope: RED"                   'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "kein Envelope: audit-invalid"         'grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'

run_gate "$(envelope_report 20 false)"
check "snapshotComplete false: RED"          'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "snapshotComplete false: audit-invalid" 'grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'

GATE_METRICS="$(printf 'UNTESTED gesamt: 20\n')" run_gate "$(envelope_report 20 true)"
check "ohne ERWARTETE FINDINGS: RED"         'grep -qx "gate_status: RED" "$TMP/gate.txt"'
check "ohne ERWARTETE FINDINGS: incomplete"  'grep -qx "gate_category: audit-incomplete" "$TMP/gate.txt"'

echo "7) Gate zaehlt wie die Ingestion nur den letzten Envelope (1cf996df)"
# previous_report sind die ersten 3000 Zeichen des Vorlaufs, bei kurzen Reports samt Envelope. Gibt das
# Modell ihn wieder, steht vor dem eigenen ein alter Envelope, ganz oder abgeschnitten. Gezaehlt wird
# nur der letzte, wie in AuditResultParser; ein offener letzter gilt als fehlend.
CUT_ECHO="$(printf '%s\n' '<!-- mysuricates:audit-result:v1' \
  '{"snapshotComplete":true,"findings":[{"ruleKey":"test-coverage.untested-class","subject":"p/C0.java"' \
  '[...gekürzt]')"
run_gate "$(envelope_report 18 true; envelope_report 20 true)"
check "alter Envelope (18) vor dem eigenen (20): nicht RED" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
run_gate "$(printf '%s\n' "$CUT_ECHO"; envelope_report 20 true)"
check "abgeschnittener alter vor dem eigenen: nicht RED" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
run_gate "$(envelope_report 20 true; printf '%s\n' '<!-- mysuricates:audit-result:v1' '{"snapshotComplete":true,"findings":[')"
check "eigener abgeschnitten nach vollstaendigem altem: RED" \
  'grep -qx "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'
# Beginnt und endet der Envelope in derselben Zeile, zaehlt er ebenso; was danach kommt, gehoert nicht dazu.
GATE_METRICS="$(printf 'UNTESTED gesamt: 1\nDUENNE TESTS gesamt: 0\nERWARTETE FINDINGS: 1\n')" \
  run_gate "$(printf '%s\n' '### Coverage-Coach' 'gate_status: YELLOW' \
  '<!-- mysuricates:audit-result:v1 {"snapshotComplete":true,"findings":[{"ruleKey":"test-coverage.untested-class","subject":"p/C0.java","severity":"MEDIUM","title":"t"}]} -->' \
  'Nachsatz ohne Envelope')"
check "einzeiliger Envelope wird gezaehlt, der Nachsatz nicht" \
  '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
# Zitate beginnen und beenden nichts, wie im Parser: ein Marker ohne "<!--" oder ohne JSON-Beginn, ein
# Envelope-Anfang mitten in einer Zeile eines offenen Envelopes (Text eines Findings), ein "-->" im Text.
# Je Zeile ein Finding, das Zitat im elften: Begaenne das Gate dort neu oder schloesse es dort, fehlten
# Findings, und es meldete RED, obwohl die Ingestion den Report annimmt.
envelope_lines() {  # $1 = anzahl findings, $2 = description des elften
  local i sep desc
  printf '%s\n' '### Coverage-Coach' 'gate_status: YELLOW' '<!-- mysuricates:audit-result:v1' \
    '{"snapshotComplete":true,"findings":['
  for ((i = 0; i < $1; i++)); do
    sep=','; [ "$i" -eq $(($1 - 1)) ] && sep=''
    desc='d'; [ "$i" -eq 10 ] && desc="$2"
    printf '{"ruleKey":"test-coverage.untested-class","subject":"p/C%d.java","severity":"MEDIUM","title":"t","description":"%s"}%s\n' \
      "$i" "$desc" "$sep"
  done
  printf '%s\n' ']}' '-->'
}
for QUOTE_CASE in "Marker ohne Kommentarbeginn|erwaehnt mysuricates:audit-result:v1 im Text" \
                  "Anfang mit JSON mitten in der Zeile|Zitat <!-- mysuricates:audit-result:v1 {x" \
                  "Kommentarende ohne Klammer|a --> b"; do
  run_gate "$(envelope_lines 20 "${QUOTE_CASE#*|}")"
  check "Zitat im Finding (${QUOTE_CASE%%|*}): alle 20 gezaehlt" \
    '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
done
NACHSATZ_NR=0
for NACHSATZ in 'Hinweis: mysuricates:audit-result:v1 steht oben.' \
                'Der Envelope beginnt mit `<!-- mysuricates:audit-result:v1`.'; do
  NACHSATZ_NR=$((NACHSATZ_NR + 1))
  run_gate "$(envelope_lines 20 d; printf '%s\n' "$NACHSATZ")"
  check "Nachsatz nennt den Marker ohne JSON ($NACHSATZ_NR): kein offener Envelope" \
    '! grep -q "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
done
run_gate "$(envelope_lines 20 d; printf '%s\n' 'Nachtrag: <!-- mysuricates:audit-result:v1 {')"
check "Nachsatz mit Marker und JSON-Beginn: offener Envelope, RED wie die Ingestion" \
  'grep -qx "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'
check "Gate laeuft auch hier ohne stderr" '[ ! -s "$TMP/gate-err.txt" ]'

echo "8) Records mit eigener Logik bleiben testpflichtig (134e7226)"
# Seit 59e22c53 nimmt der Messblock Records aus, gedacht fuer reine Datentraeger. Ein Record mit Parser
# (ICalContentLine, 19 Verzweigungen) fiel damit aus dem Snapshot. Ausgenommen bleibt ein Record nur ohne
# Methode mit Rumpf, abgesehen von Accessoren und Konstruktoren; ein Methodenkopf im Kommentar zaehlt nicht.
FIXR="$TMP/fixture-records"
MAINR="$FIXR/ai-automation-backend/src/main/java/com/automation/service/ical"
mkdir -p "$MAINR" "$FIXR/ai-automation-backend/src/test/java/com/automation/service/ical" \
         "$FIXR/ai-automation-frontend/src"
cat > "$MAINR/ContentLine.java" <<'JAVA'
package com.automation.service.ical;

public record ContentLine(String name, java.util.Map<String, String> params, String value) {

    public static ContentLine parse(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        return new ContentLine(line.substring(0, colon), java.util.Map.of(), line.substring(colon + 1));
    }
}
JAVA
cat > "$MAINR/Window.java" <<'JAVA'
package com.automation.service.ical;

public record Window(java.time.Instant from, java.util.List<String> tags) {

    public Window {
        tags = java.util.List.copyOf(tags);
    }

    public Window(java.time.Instant from) {
        this(from, java.util.List.of());
    }

    @Override
    public java.time.Instant from() {
        return from;
    }
}
JAVA
cat > "$MAINR/Quoted.java" <<'JAVA'
package com.automation.service.ical;

public record Quoted(String text) {
    /*
    static String format(String s) {
    */
    static final String HINT = "void render() {";
}
JAVA
printf '%s\n' 'package com.automation.service.ical;' 'public record Checked(String value) {' \
  '    boolean isBlank() {' '        return value == null || value.isBlank();' '    }' '}' > "$MAINR/Checked.java"
printf '%s\n' 'class CheckedTest {' '    @Test void a() {}' '}' \
  > "$FIXR/ai-automation-backend/src/test/java/com/automation/service/ical/CheckedTest.java"
echo "export const x = 1;" > "$FIXR/ai-automation-frontend/src/x.ts"
git init -q "$FIXR" && ( cd "$FIXR" && git -c user.name=t -c user.email=t@example.invalid \
  add -A && git -c user.name=t -c user.email=t@example.invalid commit -q -m init )
python3 - "$DEF" "$FIXR" > "$TMP/command-records.sh" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
block = next(b for b in definition['blocks'] if b.get('config', {}).get('label', '').startswith('Coverage-Metriken'))
print(block['config']['command'].replace('{{ project_path }}', sys.argv[2]))
PY
( cd "$FIXR" && bash "$TMP/command-records.sh" ) > "$TMP/out-records.txt" 2> "$TMP/err-records.txt"
awk '/^UNTESTED gesamt:/{f=1;next} f && /^[[:space:]]*$/{f=0} f' "$TMP/out-records.txt" > "$TMP/untested-records.txt"
check "Record mit Parser-Methode ist ungetestet gelistet" \
  'grep -q "^service/ical/ContentLine\.java${TAB}[0-9]\+$" "$TMP/untested-records.txt"'
check "Accessor, kompakter und zweiter Konstruktor sind keine Logik" '! grep -q "Window" "$TMP/untested-records.txt"'
check "Methodenkopf in Kommentar oder String ist keine Logik"         '! grep -q "Quoted" "$TMP/untested-records.txt"'
check "Record mit Logik und Test bleibt ungelistet"                    '! grep -q "Checked" "$TMP/untested-records.txt"'
check "genau ein ungetesteter Record, Soll-Groesse 1" \
  'grep -qx "UNTESTED gesamt: 1" "$TMP/out-records.txt" && grep -qx "ERWARTETE FINDINGS: 1" "$TMP/out-records.txt"'
check "nur die zwei Records ohne Logik ausgenommen" \
  'grep -qx "Datenklassen ausgenommen (DTO/Entity/Repository/Event/Enum/Record/Interface): 2" "$TMP/out-records.txt"'
check "Messblock laeuft ohne stderr" '[ ! -s "$TMP/err-records.txt" ]'

echo "9) Envelope deterministisch aus der Soll-Liste (fa66ea31)"
# Run 073e9933: die KI gab drei Klassen doppelt aus (52 statt 50 Findings), das Gate wurde RED und der Lauf
# war wertlos. Jetzt baut der Shell-Block report den Envelope aus den Listen des Messblocks; die KI liefert
# nur Bericht, naechster_schritt und Texte je Klasse. Duplikate sind damit unmoeglich, fehlende Texte
# ersetzt ein Standardtext, ein Envelope der KI zaehlt nicht.
render_block() {  # $1 = name des Blocks, $2 = metrik-output, $3 = coach-output, $4 = report-output
  python3 - "$DEF" "$1" "$2" "$3" "$4" <<'PY'
import json, re, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
block = [b for b in definition['blocks'] if b.get('name') == sys.argv[2]]
assert len(block) == 1, f"erwartet genau einen Block {sys.argv[2]}, gefunden: {len(block)}"
assert block[0]['type'] == 'shell' and block[0]['config'].get('shell_escape_variables') is True
values = {'block.metrics.output': sys.argv[3], 'block.coach.output': sys.argv[4], 'block.report.output': sys.argv[5]}
def substitute(match):
    key = match.group(1).strip()
    assert key in values, f"unerwarteter Platzhalter: {key}"
    return "'" + values[key].replace("'", "'\\''") + "'"
print(re.sub(r'\{\{\s*([^}]+?)\s*}}', substitute, block[0]['config']['command']))
PY
}
check "reportBlock ist der Shell-Block report, promptBlock der KI-Block coach" \
  'python3 -c "import json,sys; d=json.load(open(sys.argv[1])); a=d[\"metadata\"][\"audit\"]; t={b.get(\"name\"): b[\"type\"] for b in d[\"blocks\"]}; sys.exit(not (a[\"reportBlock\"] == \"report\" and t[\"report\"] == \"shell\" and a[\"promptBlock\"] == \"coach\" and t[\"coach\"] == \"simple\"))" "$DEF"'
assemble() {  # $1 = metrik-output, $2 = coach-output -> $TMP/report.txt, Envelope-JSON in $TMP/envelope.json
  render_block report "$1" "$2" "" > "$TMP/report.sh"
  bash "$TMP/report.sh" > "$TMP/report.txt" 2> "$TMP/report-err.txt"
  awk '/^<!-- mysuricates:audit-result:v1$/ { getline; print }' "$TMP/report.txt" > "$TMP/envelope.json"
}
finding_field() {  # $1 = subject, $2 = feld
  python3 -c 'import json,sys; e=json.load(open(sys.argv[1])); print(next(f[sys.argv[3]] for f in e["findings"] if f["subject"] == sys.argv[2]))' \
    "$TMP/envelope.json" "$1" "$2"
}
METRICS_OUT="$(cat "$TMP/out.txt")"
# Wie Run 073e9933: die KI listet Klassen doppelt, nennt eine erfundene, schreibt einen eigenen Envelope
# mit 25 Findings und benutzt Tab, " | " und Aufzaehlungszeichen gemischt.
COACH_OUT="$(printf '%s\n' '### Coverage-Coach' '' '**Status**: 40.0%' '' \
  '**naechster_schritt:** `service/deep/Untested05.java`' '' 'BEFUND-TEXTE:' \
  "service/deep/Untested20.java${TAB}Groesste Klasse: Kernpfade mit UntestedTest als Vorlage pruefen." \
  'service/deep/Untested20.java | Doppelt genannt, der erste Text gilt.' \
  '- controller/NoPublicUntested.java | Enum-Werte und --> Pfeil im Text abdecken.' \
  'ThinOne.java | Die sechs fehlenden public-Methoden abdecken.' \
  'service/Erfunden.java | Diese Klasse gibt es nicht.' \
  'ENDE BEFUND-TEXTE' '' 'gate_status: YELLOW' 'gate_category: test-coverage' 'gate_description: 40%' '' \
  '<!-- mysuricates:audit-result:v1' \
  "{\"snapshotComplete\":true,\"expectedFindings\":25,\"findings\":[$(for i in $(seq 1 25); do printf '{"ruleKey":"test-coverage.untested-class","subject":"p/C.java","severity":"MEDIUM","title":"t"},'; done | sed 's/,$//')]}" \
  '-->')"
assemble "$METRICS_OUT" "$COACH_OUT"
check "Zusammenbau laeuft ohne stderr"            '[ ! -s "$TMP/report-err.txt" ]'
check "genau ein Envelope in der Ausgabe"         '[ "$(grep -c "mysuricates:audit-result:v1" "$TMP/report.txt")" -eq 1 ]'
check "Envelope ist gueltiges JSON"               'python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$TMP/envelope.json"'
read -r ENV_TOTAL ENV_UNIQUE ENV_EXPECTED ENV_COMPLETE ENV_HIGH ENV_UNTESTED ENV_THIN <<< "$(python3 -c '
import json, sys
e = json.load(open(sys.argv[1]))
f = e["findings"]
print(len(f), len({(x["ruleKey"], x["subject"]) for x in f}), e["expectedFindings"], e["snapshotComplete"],
      sum(x["severity"] == "HIGH" for x in f), sum(x["ruleKey"] == "test-coverage.untested-class" for x in f),
      sum(x["ruleKey"] == "test-coverage.thin-test" for x in f))' "$TMP/envelope.json")"
check "23 Findings wie ERWARTETE FINDINGS, keins doppelt" '[ "$ENV_TOTAL" -eq 23 ] && [ "$ENV_UNIQUE" -eq 23 ]'
check "expectedFindings 23, snapshotComplete true" '[ "$ENV_EXPECTED" -eq 23 ] && [ "$ENV_COMPLETE" = True ]'
check "21 untested-class, 2 thin-test"             '[ "$ENV_UNTESTED" -eq 21 ] && [ "$ENV_THIN" -eq 2 ]'
check "genau eine HIGH: der naechste Schritt"      '[ "$ENV_HIGH" -eq 1 ] && [ "$(finding_field service/deep/Untested05.java severity)" = HIGH ]'
check "uebrige ungetestete MEDIUM, duenne LOW" \
  '[ "$(finding_field service/deep/Untested20.java severity)" = MEDIUM ] && [ "$(finding_field ThinOne.java severity)" = LOW ]'
check "Text der KI je Klasse, beim doppelten der erste" \
  '[ "$(finding_field service/deep/Untested20.java description)" = "Groesste Klasse: Kernpfade mit UntestedTest als Vorlage pruefen." ]'
check "Aufzaehlungszeichen und \" | \" gelesen, --> entschaerft" \
  '[ "$(finding_field controller/NoPublicUntested.java description)" = "Enum-Werte und -> Pfeil im Text abdecken." ]'
check "ohne Text der KI: Standardtext" \
  'finding_field service/deep/Untested01.java description | grep "^Eigenen Test fuer Untested01 anlegen" >/dev/null'
check "erfundene Klasse nicht im Envelope"         '! grep -q "Erfunden" "$TMP/envelope.json"'
check "evidence und Titel aus der Messung" \
  '[ "$(finding_field service/deep/Untested20.java evidence)" = "200 LOC" ] && [ "$(finding_field ThinTwo.java evidence)" = "1/10 @Test/public (10%)" ] && [ "$(finding_field ThinTwo.java title)" = "Duenner Test: ThinTwo" ]'
check "Bericht bleibt, Texte-Abschnitt und naechster_schritt nicht" \
  'grep -qx "### Coverage-Coach" "$TMP/report.txt" && grep -qx "gate_status: YELLOW" "$TMP/report.txt" && ! grep -q "BEFUND-TEXTE\|naechster_schritt\|Diese Klasse gibt es nicht" "$TMP/report.txt"'
GATE_METRICS="$METRICS_OUT" run_gate "$(cat "$TMP/report.txt")"
check "Gate auf dem zusammengesetzten Report: Status aus dem Bericht" \
  'grep -qx "gate_status: YELLOW" "$TMP/gate.txt" && grep -qx "gate_category: test-coverage" "$TMP/gate.txt"'
assemble "$METRICS_OUT" "$(printf '%s\n' 'naechster_schritt: service/Erfunden.java' 'gate_status: GREEN')"
check "unbekannter naechster Schritt: keine HIGH, alle mit Standardtext" \
  '! grep -q "\"HIGH\"" "$TMP/envelope.json" && [ "$(grep -o "Eigenen Test fuer" "$TMP/envelope.json" | wc -l)" -eq 21 ]'
assemble "$(printf '%s\n' "$METRICS_OUT" | sed '/^DUENNE TESTS gesamt:/d')" "$COACH_OUT"
check "Kopfzeile fehlt: snapshotComplete false" \
  'python3 -c "import json,sys; sys.exit(json.load(open(sys.argv[1]))[\"snapshotComplete\"] is not False)" "$TMP/envelope.json"'
GATE_METRICS="$METRICS_OUT" run_gate "$(cat "$TMP/report.txt")"
check "Kopfzeile fehlt: Gate RED audit-invalid" \
  'grep -qx "gate_status: RED" "$TMP/gate.txt" && grep -qx "gate_category: audit-invalid" "$TMP/gate.txt"'
assemble "$(printf '%s\n' "$METRICS_OUT" | sed '/^service\/deep\/Untested03\.java/d')" "$COACH_OUT"
check "Kopfzeile zaehlt mehr Zeilen als gelistet: snapshotComplete false" 'grep -q "\"snapshotComplete\": false" "$TMP/envelope.json"'

# Fehlt "ENDE BEFUND-TEXTE", endet der Abschnitt an der naechsten gate_- oder naechster_schritt-Zeile: Sonst
# verschluckte er Urteil und naechsten Schritt (Review fa66ea31).
assemble "$METRICS_OUT" "$(printf '%s\n' '### Coverage-Coach' 'BEFUND-TEXTE:' \
  'service/deep/Untested09.java | Text ohne Abschlusszeile.' '' '**gate_status:** YELLOW' 'gate_category: test-coverage' \
  'naechster_schritt: service/deep/Untested05.java' 'Schlusssatz des Berichts.')"
check "ohne ENDE BEFUND-TEXTE: gate-Zeilen bleiben im Bericht" \
  'grep -qx "\*\*gate_status:\*\* YELLOW" "$TMP/report.txt" && grep -qx "gate_category: test-coverage" "$TMP/report.txt" && grep -qx "Schlusssatz des Berichts." "$TMP/report.txt"'
check "ohne ENDE BEFUND-TEXTE: naechster Schritt wird HIGH, Text davor gilt" \
  '[ "$(finding_field service/deep/Untested05.java severity)" = HIGH ] && [ "$(finding_field service/deep/Untested09.java description)" = "Text ohne Abschlusszeile." ]'
# Nennt die KI ein subject mit anderem Pfad, gilt der Text der Klasse mit demselben Dateinamen, wenn er
# eindeutig ist; sonst bleibt der Standardtext. Dasselbe fuer naechster_schritt.
TWIN_METRICS="$(printf '%s\n' 'UNTESTED gesamt: 3' "a/Twin.java${TAB}50" "b/Twin.java${TAB}40" "service/deep/Solo.java${TAB}30" '' \
  'DUENNE TESTS gesamt: 0' '' 'ERWARTETE FINDINGS: 3')"
assemble "$TWIN_METRICS" "$(printf '%s\n' 'naechster_schritt: com/automation/service/deep/Solo.java' 'BEFUND-TEXTE:' \
  'Solo.java | Text fuer Solo ueber den Dateinamen.' 'Twin.java | Mehrdeutig, darf nicht greifen.' 'ENDE BEFUND-TEXTE')"
check "abweichender Pfad, eindeutiger Dateiname: Text der KI" \
  '[ "$(finding_field service/deep/Solo.java description)" = "Text fuer Solo ueber den Dateinamen." ]'
check "abweichender Pfad im naechsten Schritt: HIGH ueber den Dateinamen" \
  '[ "$(finding_field service/deep/Solo.java severity)" = HIGH ]'
check "mehrdeutiger Dateiname: Standardtext fuer beide" \
  'finding_field a/Twin.java description | grep "^Eigenen Test fuer Twin" >/dev/null && finding_field b/Twin.java description | grep "^Eigenen Test fuer Twin" >/dev/null'

# Gleichnamige Klassen in zwei Paketen ergaeben dasselbe thin-test-subject zweimal: der Messblock listet
# je Name nur die duennste Zeile, ERWARTETE FINDINGS zaehlt sie einmal.
FIXD="$TMP/fixture-dup"
mkdir -p "$FIXD/ai-automation-backend/src/main/java/com/automation/a" "$FIXD/ai-automation-backend/src/main/java/com/automation/b" \
         "$FIXD/ai-automation-backend/src/test/java/com/automation/a" "$FIXD/ai-automation-frontend/src"
java_class "$FIXD/ai-automation-backend/src/main/java/com/automation/a/Twin.java" 40 8
java_class "$FIXD/ai-automation-backend/src/main/java/com/automation/b/Twin.java" 40 10
printf '%s\n' 'class TwinTest {' '    @Test void a() {}' '}' > "$FIXD/ai-automation-backend/src/test/java/com/automation/a/TwinTest.java"
echo "export const x = 1;" > "$FIXD/ai-automation-frontend/src/x.ts"
git init -q "$FIXD" && ( cd "$FIXD" && git -c user.name=t -c user.email=t@example.invalid \
  add -A && git -c user.name=t -c user.email=t@example.invalid commit -q -m init )
python3 - "$DEF" "$FIXD" > "$TMP/command-dup.sh" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1], encoding='utf-8'))
block = next(b for b in definition['blocks'] if b.get('name') == 'metrics')
print(block['config']['command'].replace('{{ project_path }}', sys.argv[2]))
PY
( cd "$FIXD" && bash "$TMP/command-dup.sh" ) > "$TMP/out-dup.txt" 2> "$TMP/err-dup.txt"
check "gleichnamige duenne Klassen: eine Zeile, die duennste" \
  'grep -qx "DUENNE TESTS gesamt: 1" "$TMP/out-dup.txt" && grep -qx "Twin.java${TAB}1/10${TAB}10%" "$TMP/out-dup.txt" && grep -qx "ERWARTETE FINDINGS: 1" "$TMP/out-dup.txt"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
