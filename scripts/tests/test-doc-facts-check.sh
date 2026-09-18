#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/doc-facts-check.sh (TODO 3df2df73).
#
# Das Skript prueft die in der Doku genannten Groessen (Blocktypen, Controller,
# Migrationen, Templates, Skills, Integrations-Provider) gegen den Code. Die
# Tests arbeiten auf einem Miniatur-Repository mit bekannten Zahlen, damit sie
# nicht mitwandern, wenn das echte Repo waechst.
#
# Run: bash scripts/tests/test-doc-facts-check.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/doc-facts-check.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
GIT="git -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false"

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# ---------------------------------------------------------------------------
# Miniatur-Repository mit bekannten Groessen:
#   4 Blocktypen, 2 Controller, Migrationen V1/V2/V5 (Hoechste 5, Anzahl 3),
#   6 Templates, 3 Skills, 2 Integrations-Provider
# ---------------------------------------------------------------------------
FAKE="$TMP/fake"
JAVA="$FAKE/ai-automation-backend/src/main/java/com/automation"
RES="$FAKE/ai-automation-backend/src/main/resources"
mkdir -p "$JAVA/controller" "$JAVA/service/assistant/context" \
         "$JAVA/service/integration/providers" \
         "$RES/db/migration" "$RES/templates/builtin" "$RES/skills/builtin" \
         "$FAKE/docs/en"

cat > "$JAVA/service/assistant/context/BlockTypesIndexSection.java" <<'JAVA_EOF'
public class BlockTypesIndexSection {
    private static final List<BlockTypeEntry> BLOCK_TYPES = List.of(
        new BlockTypeEntry("simple", "..."),
        new BlockTypeEntry("shell", "..."),
        new BlockTypeEntry("retry", "..."),
        new BlockTypeEntry("todo-create", "..."));
}
JAVA_EOF
echo 'class AlphaController {}' > "$JAVA/controller/AlphaController.java"
echo 'class BetaController {}'  > "$JAVA/controller/BetaController.java"
for n in 1 2 5; do echo "-- x" > "$RES/db/migration/V${n}__step.sql"; done
for n in 1 2 3 4 5 6; do echo '{}' > "$RES/templates/builtin/t$n.json"; done
for n in 1 2 3; do echo '{}' > "$RES/skills/builtin/s$n.json"; done
echo 'class OneProvider implements IntegrationProvider {}' > "$JAVA/service/integration/providers/OneProvider.java"
echo 'class TwoProvider implements IntegrationProvider {}' > "$JAVA/service/integration/providers/TwoProvider.java"

# Versionsquellen mit unverwechselbaren Zahlen (nichts davon kommt im echten Repo vor).
cat > "$FAKE/ai-automation-backend/pom.xml" <<'POM_EOF'
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>9.9.9</version>
    </parent>
    <properties>
        <docker-java.version>1.2.3</docker-java.version>
        <sqlite-jdbc.version>4.5.6.7</sqlite-jdbc.version>
    </properties>
</project>
POM_EOF
mkdir -p "$FAKE/ai-automation-frontend"
cat > "$FAKE/ai-automation-frontend/package.json" <<'PKG_EOF'
{
  "dependencies": {
    "react": "^8.1.2",
    "react-dom": "^8.1.2",
    "@types/react": "^8.0.0"
  },
  "devDependencies": {
    "typescript": "^3.2.1",
    "vite": "^2.3.4",
    "tailwindcss": "^5.6.7",
    "cypress": "^7.8.9",
    "vite-plugin-pwa": "^0.9.8"
  }
}
PKG_EOF

echo "1) Korrekte Zahlen erzeugen keine Drift"
cat > "$FAKE/docs/ok.md" <<'MD_EOF'
# Korrekt
Das Backend umfasst **2 REST-Controller** und **4 Block-Typen**.
Flyway-Migrationen: V1-V5 (SQLite), insgesamt 3 Migrationen.
Es gibt 6 Built-in-Templates, 3 Built-in-Skills und 2 Integrations-Provider.
MD_EOF
cat > "$FAKE/docs/en/ok.md" <<'MD_EOF'
# Correct
The backend comprises 2 REST controllers and 4 block types.
Flyway migrations: V1-V5 (SQLite), 3 migrations in total.
There are 6 built-in templates, 3 built-in skills and 2 providers.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "sauberer Bestand: exit 0"     '[ "$RC" -eq 0 ]'
check "sauberer Bestand: kein DRIFT" '! grep -q "^DRIFT" <<<"$OUT"'

echo "2) Jede Groesse wird einzeln erkannt"
cat > "$FAKE/docs/drift.md" <<'MD_EOF'
# Veraltet
Das Backend umfasst 38 REST-Controller und 14 Block-Typen.
Flyway-Migrationen: V1-V19 (SQLite), alle 68 Migrationen laufen beim Start.
Es gibt 44 Built-in-Templates, 16 eingebaute Skills und 9 Integrations-Provider.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Drift: exit 1"                '[ "$RC" -eq 1 ]'
check "Controller-Drift gemeldet"    'grep -qE "^DRIFT docs/drift.md:2 .*sagt 38, Code 2" <<<"$OUT"'
check "Blocktyp-Drift gemeldet"      'grep -qE "^DRIFT docs/drift.md:2 .*sagt 14, Code 4" <<<"$OUT"'
check "Migrationsstand gemeldet"     'grep -qE "^DRIFT docs/drift.md:3 .*sagt 19, Code 5" <<<"$OUT"'
check "Migrationsanzahl gemeldet"    'grep -qE "^DRIFT docs/drift.md:3 .*sagt 68, Code 3" <<<"$OUT"'
check "Template-Drift gemeldet"      'grep -qE "^DRIFT docs/drift.md:4 .*sagt 44, Code 6" <<<"$OUT"'
check "Skill-Drift gemeldet"         'grep -qE "^DRIFT docs/drift.md:4 .*sagt 16, Code 3" <<<"$OUT"'
check "Provider-Drift gemeldet"      'grep -qE "^DRIFT docs/drift.md:4 .*sagt 9, Code 2" <<<"$OUT"'
check "ok.md bleibt unbeanstandet"   '! grep -q "docs/ok.md" <<<"$OUT"'
rm "$FAKE/docs/drift.md"

echo "3) Englische Schreibweisen werden ebenso erkannt"
cat > "$FAKE/docs/en/drift.md" <<'MD_EOF'
# Outdated
The backend comprises 38 REST controllers and 14 block types.
Flyway migrations: V1-V19 (SQLite); Flyway runs all 68 migrations.
There are 44 built-in templates, 16 predefined skills and 9 providers.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "EN Controller-Drift"          'grep -qE "^DRIFT docs/en/drift.md:2 .*sagt 38, Code 2" <<<"$OUT"'
check "EN Blocktyp-Drift"            'grep -qE "^DRIFT docs/en/drift.md:2 .*sagt 14, Code 4" <<<"$OUT"'
check "EN Migrationsstand"           'grep -qE "^DRIFT docs/en/drift.md:3 .*sagt 19, Code 5" <<<"$OUT"'
check "EN Migrationsanzahl"          'grep -qE "^DRIFT docs/en/drift.md:3 .*sagt 68, Code 3" <<<"$OUT"'
check "EN Template-Drift"            'grep -qE "^DRIFT docs/en/drift.md:4 .*sagt 44, Code 6" <<<"$OUT"'
check "EN Skill-Drift"               'grep -qE "^DRIFT docs/en/drift.md:4 .*sagt 16, Code 3" <<<"$OUT"'
check "EN Provider-Drift"            'grep -qE "^DRIFT docs/en/drift.md:4 .*sagt 9, Code 2" <<<"$OUT"'
rm "$FAKE/docs/en/drift.md"

echo "4) Falsch-Positive: Teilmengen, KI-Provider, Versionsziffern, Tabellenzeilen"
cat > "$FAKE/docs/noise.md" <<'MD_EOF'
# Kein Befund
Der Audit-Pool bringt 4 audit templates mit, dazu 3 Fix-Workflows.
Es gibt 3 KI-Provider: Claude, Codex und Pi.
OAuth2 providers brauchen authorize- und token-URL.
Die Migrationstabelle:

| V1-V2 | Basistabellen |
| V3-V5 | Indexe |
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Teilmengen/KI-Provider/Tabellen ohne Befund" '! grep -q "docs/noise.md" <<<"$OUT"'
check "ohne Drift wieder exit 0"                    '[ "$RC" -eq 0 ]'
rm "$FAKE/docs/noise.md"

# Spannen-Zeilen und Loecher (TODO ffa6d846 / c9c9f3eb): docs/architektur.md fasst zwei
# Migrationen zu "| V28-V29 | ... |" zusammen. Wer die Spanne nicht liest, haelt das fuer ein
# Loch. Wer nur die Hoechstnummer prueft, uebersieht ein echtes -- genau so ist beim Nachtragen
# von V75 die V74-Zeile verschwunden, ohne dass das Gate etwas sagte.
echo "4h) Migrationstabelle: Spannen zaehlen, Loecher fallen auf"
cat > "$FAKE/docs/tabelle-spanne.md" <<'MD_EOF'
# Vollstaendig ueber Spannen
| V1-V2 | Basis |
| V3-V5 | Rest |
MD_EOF
cat > "$FAKE/docs/tabelle-loch.md" <<'MD_EOF'
# Loch in der Mitte
| V1 | Basis |
| V5 | Rest |
MD_EOF
cat > "$FAKE/docs/tabelle-beginnt-spaet.md" <<'MD_EOF'
# Beginnt bewusst spaet
| V4 | Indexe |
| V5 | Rest |
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1)
check "Spanne deckt die Reihe ab: kein Befund" '! grep -q "tabelle-spanne.md" <<<"$OUT"'
check "fehlende Zeile in der Mitte gemeldet" \
  'grep -qE "^DRIFT docs/tabelle-loch.md:[0-9]+ migration-table-luecke: 1 Zeile\(n\) fehlen.*V2" <<<"$OUT"'
check "spaet beginnende Tabelle ist kein Loch" '! grep -q "tabelle-beginnt-spaet.md" <<<"$OUT"'
rm "$FAKE/docs/tabelle-spanne.md" "$FAKE/docs/tabelle-loch.md" "$FAKE/docs/tabelle-beginnt-spaet.md"

echo "4d) Weitere Formulierungen fuer Templates"
cat > "$FAKE/docs/wording.md" <<'MD_EOF'
# Wortwahl
44 vordefinierte Workflow-Vorlagen werden beim Start geseedet.
There are 44 predefined workflow templates.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "'vordefinierte Workflow-Vorlagen' erkannt" 'grep -qE "^DRIFT docs/wording.md:2 templates sagt 44, Code 6" <<<"$OUT"'
check "'predefined workflow templates' erkannt"   'grep -qE "^DRIFT docs/wording.md:3 templates sagt 44, Code 6" <<<"$OUT"'
rm "$FAKE/docs/wording.md"

echo "4e) Zusatz zwischen Beiwort und Substantiv ('19 Built-in + benutzerdefinierte Skills')"
cat > "$FAKE/docs/zusatz.md" <<'MD_EOF'
# Glossar
Wiederverwendbarer Automatisierungsbaustein. 16 Built-in + benutzerdefinierte Skills.
Reusable automation building block. 16 built-in + user-defined skills.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "'Built-in + benutzerdefinierte Skills' erkannt" 'grep -qE "^DRIFT docs/zusatz.md:2 skills sagt 16, Code 3" <<<"$OUT"'
check "'built-in + user-defined skills' erkannt"       'grep -qE "^DRIFT docs/zusatz.md:3 skills sagt 16, Code 3" <<<"$OUT"'
rm "$FAKE/docs/zusatz.md"

echo "4f) Teilspanne des Grundschemas ist kein Migrationsstand"
cat > "$FAKE/docs/grundschema.md" <<'MD_EOF'
# Migrationen
Die folgende Tabelle beschreibt das Grundschema (V1-V2). V3-V5 ergaenzen Spalten und Indizes.
The table below describes the base schema (V1-V2). V3-V5 mostly add columns.
Flyway-Migrationen: V1-V2 (SQLite)
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Grundschema-Zeile DE ohne Befund" '! grep -qE "^DRIFT docs/grundschema.md:2" <<<"$OUT"'
check "Grundschema-Zeile EN ohne Befund" '! grep -qE "^DRIFT docs/grundschema.md:3" <<<"$OUT"'
check "echte Standangabe weiter gemeldet" 'grep -qE "^DRIFT docs/grundschema.md:4 migration-max sagt 2, Code 5" <<<"$OUT"'
rm "$FAKE/docs/grundschema.md"

echo "4g) Die Ausnahme gilt der Spanne, nicht der ganzen Zeile"
# Eine Teilspanne ist nur dann belegt, wenn die Zeile auch sagt, wie es weitergeht
# (Fortsetzungsspanne bis zur Hoechstnummer). Eine blosse Standangabe im Nebensatz
# reicht nicht — sonst deckt jedes "aktuell V5" jede falsche Spanne derselben Zeile.
cat > "$FAKE/docs/teilspanne.md" <<'MD_EOF'
# Migrationen
Das Grundschema (V1-V3). Aktueller Stand ist V5 in der Produktion.
Die Tabelle zeigt das Grundschema (V1-V2). V3-V5 ergaenzen Spalten.
Das Grundschema (V1-V3). V2-V5 ergaenzen Spalten.
V1-V5 (Grundschema) ist vollstaendig.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "falsche Teilspanne neben blosser Standangabe gemeldet" \
  'grep -qE "^DRIFT docs/teilspanne.md:2 migration-max sagt 3, Code 5" <<<"$OUT"'
check "belegte Teilspanne mit Fortsetzung bleibt frei" \
  '! grep -qE "^DRIFT docs/teilspanne.md:3" <<<"$OUT"'
check "Fortsetzung, die nicht anschliesst, belegt nichts" \
  'grep -qE "^DRIFT docs/teilspanne.md:4 migration-max sagt 3, Code 5" <<<"$OUT"'
check "vollstaendige Spanne bleibt frei" \
  '! grep -qE "^DRIFT docs/teilspanne.md:5" <<<"$OUT"'
rm "$FAKE/docs/teilspanne.md"

echo "4c) Deutscher Dativ-Plural wird erfasst"
cat > "$FAKE/docs/dativ.md" <<'MD_EOF'
# Dativ
Auszug: 3 der 38 REST-Controller, verteilt auf 9 Integrations-Providern.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Dativ Controller erkannt" 'grep -qE "^DRIFT docs/dativ.md:2 .*sagt 38, Code 2" <<<"$OUT"'
check "Dativ Provider erkannt"   'grep -qE "^DRIFT docs/dativ.md:2 .*sagt 9, Code 2" <<<"$OUT"'
check "Teilmengen-Zahl 3 loest nichts aus" '! grep -qE "sagt 3, Code 2" <<<"$OUT"'
rm "$FAKE/docs/dativ.md"

echo "4b) Verzeichnisbaeume in Codebloecken werden mitgeprueft"
cat > "$FAKE/docs/baum.md" <<'MD_EOF'
# Aufbau
```
src/main/java/
├── controller/          38 REST-Controller
└── resources/db/migration/   ← 68 Flyway-Migrationen (V1-V68)
```
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Controller im Codeblock erkannt" 'grep -qE "^DRIFT docs/baum.md:4 .*sagt 38, Code 2" <<<"$OUT"'
check "Migrationen im Codeblock erkannt" 'grep -qE "^DRIFT docs/baum.md:5 .*sagt 68, Code 3" <<<"$OUT"'
rm "$FAKE/docs/baum.md"

echo "5) Zeilen mit Ignore-Marker werden uebersprungen"
cat > "$FAKE/docs/historisch.md" <<'MD_EOF'
# Momentaufnahme
Damals waren es 20 REST-Controller. <!-- doc-facts-check: ignore -->
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "Ignore-Marker wirkt"          '! grep -q "docs/historisch.md" <<<"$OUT"'
rm "$FAKE/docs/historisch.md"

echo "6) Fehlende Faktenquelle ist ein harter Fehler, kein stilles OK"
mv "$JAVA/service/assistant/context/BlockTypesIndexSection.java" "$TMP/registry.bak"
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "exit 2 bei fehlender Registry"  '[ "$RC" -eq 2 ]'
check "nennt die fehlende Groesse"     'grep -qi "block" <<<"$OUT"'
check "meldet kein OK"                 '! grep -q "Doku-Zahlen OK" <<<"$OUT"'
mv "$TMP/registry.bak" "$JAVA/service/assistant/context/BlockTypesIndexSection.java"

echo "7) Der Faktenteil laesst sich einzeln ausgeben"
OUT=$(bash "$SCRIPT" --root "$FAKE" --facts 2>&1); RC=$?
check "--facts: exit 0"                '[ "$RC" -eq 0 ]'
check "--facts nennt block-types 4"    'grep -qE "^block-types[[:space:]]+4$" <<<"$OUT"'
check "--facts nennt controllers 2"    'grep -qE "^controllers[[:space:]]+2$" <<<"$OUT"'
check "--facts nennt migration-max 5"  'grep -qE "^migration-max[[:space:]]+5$" <<<"$OUT"'
check "--facts nennt migrations 3"     'grep -qE "^migrations[[:space:]]+3$" <<<"$OUT"'
check "--facts nennt templates 6"      'grep -qE "^templates[[:space:]]+6$" <<<"$OUT"'
check "--facts nennt skills 3"         'grep -qE "^skills[[:space:]]+3$" <<<"$OUT"'
check "--facts nennt providers 2"      'grep -qE "^integration-providers[[:space:]]+2$" <<<"$OUT"'

echo "8) Historische Momentaufnahmen sind ausgenommen"
cat > "$FAKE/docs/analysis.md" <<'MD_EOF'
# Gap-Analyse von damals
- **Documented:** "20 REST-Controller" — **Actual:** 26 REST controllers
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "docs/analysis.md ausgenommen" '! grep -q "analysis.md" <<<"$OUT"'
rm "$FAKE/docs/analysis.md"

echo "9) Das echte Repository ist frei von Zahlen-Drift"
OUT=$(bash "$SCRIPT" 2>&1); RC=$?
check "Repo-Lauf ohne Drift (exit 0)" '[ "$RC" -eq 0 ]'
[ "$RC" -eq 0 ] || printf '%s\n' "$OUT" | sed 's/^/     /'

echo "10) Migrationstabellen: die hoechste genannte Vn zaehlt"
cat > "$FAKE/docs/tabelle-alt.md" <<'MD_EOF'
# Schema
| Migration | Datei | Wirkung |
|---|---|---|
| V1 | `V1__init.sql` | Grundtabellen |
| V3 | `V3__index.sql` | Indexe |
MD_EOF
cat > "$FAKE/docs/tabelle-auszug.md" <<'MD_EOF'
# Schema (Auszug)
Die Tabelle beschreibt das Grundschema; der aktuelle Stand ist V5.

| Migration | Datei | Wirkung |
|---|---|---|
| V1 | `V1__init.sql` | Grundtabellen |
| V3 | `V3__index.sql` | Indexe |
MD_EOF
cat > "$FAKE/docs/tabelle-aktuell.md" <<'MD_EOF'
# Schema
| Migration | Datei | Wirkung |
|---|---|---|
| V1 | `V1__init.sql` | Grundtabellen |
| V2 | `V2__step.sql` | Zwischenschritt |
| V5 | `V5__step.sql` | Letzter Stand |
MD_EOF
# Dieselbe Tabelle ohne die V2-Zeile: Hoechststand stimmt, eine vorhandene Migration fehlt
# trotzdem. Genau diese Luecke ist beim Nachtragen von V75 unbemerkt entstanden.
cat > "$FAKE/docs/tabelle-loch-real.md" <<'MD_EOF'
# Schema mit fehlender Zeile
| Migration | Datei | Wirkung |
|---|---|---|
| V1 | `V1__init.sql` | Grundtabellen |
| V5 | `V5__step.sql` | Letzter Stand |
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "veraltete Migrationstabelle gemeldet" \
  'grep -qE "^DRIFT docs/tabelle-alt.md:5 migration-table sagt 3, Code 5" <<<"$OUT"'
check "als Auszug ausgewiesene Tabelle bleibt frei" '! grep -q "tabelle-auszug.md" <<<"$OUT"'
check "aktuelle, vollstaendige Tabelle bleibt frei"  '! grep -q "tabelle-aktuell.md" <<<"$OUT"'
check "vorhandene Migration fehlt in der Tabelle: gemeldet" \
  'grep -qE "^DRIFT docs/tabelle-loch-real.md:[0-9]+ migration-table-luecke: 1 Zeile\(n\) fehlen.*V2" <<<"$OUT"'
check "eine nie existierende Nummer wird NICHT als fehlend gemeldet" \
  '! grep -qE "tabelle-loch-real.md.*(V3|V4)" <<<"$OUT"'
rm "$FAKE/docs/tabelle-alt.md" "$FAKE/docs/tabelle-auszug.md" "$FAKE/docs/tabelle-aktuell.md" \
   "$FAKE/docs/tabelle-loch-real.md"

echo "11) README-Kopfzahlen: Commits mit Toleranz, Testzahlen nur mit Datum"
( cd "$FAKE" && git init -q . && $GIT add -A && $GIT commit -q -m eins \
    && $GIT commit -q --allow-empty -m zwei && $GIT commit -q --allow-empty -m drei )
cat > "$FAKE/README.de.md" <<'MD_EOF'
# MySuricates
| | |
|---|---|
| Entstanden seit | 2026-02-15, rund 40 Commits, ein Autor |
| Tests | Backend 8 767, Frontend 1 013 |
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "veraltete Commit-Zahl gemeldet" \
  'grep -qE "^DRIFT README.de.md:4 commits sagt 40, Code 3" <<<"$OUT"'
check "Testzahl ohne Datum gemeldet" \
  'grep -qE "^DRIFT README.de.md:5 testzahl-ohne-datum" <<<"$OUT"'
cat > "$FAKE/README.de.md" <<'MD_EOF'
# MySuricates
| | |
|---|---|
| Entstanden seit | 2026-02-15, rund 3 Commits, ein Autor |
| Tests | Backend 8 767, Frontend 1 013 — Lauf vom 2026-09-18 |
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "passende Commit-Zahl und datierte Testzahl: exit 0" '[ "$RC" -eq 0 ]'
check "README.de.md ohne Befund" '! grep -q "README.de.md" <<<"$OUT"'
rm "$FAKE/README.de.md"

echo "12) Onboarding-Fakten: Versionen, verbotene Muster, nicht existierende Dateien"
mkdir -p "$FAKE/docs/onboarding"
cat > "$FAKE/docs/onboarding/README.md" <<'MD_EOF'
# Onboarding
| Technologie | Version | Zweck |
|---|---|---|
| **Spring Boot** | 9.9.8 | Web-Framework |
| **React** | 8.1 | UI-Framework |
| **SQLite** | sqlite-jdbc 4.5.6.7 | Datenbank |
| **Vite** | 2.4 | Build-Tool |
| **Java** | 21 (LTS) | Sprache |
MD_EOF
cat > "$FAKE/docs/onboarding/SETUP.md" <<'MD_EOF'
# Setup
Starte das Backend mit `mvn spring-boot:run`.
> **Nicht** `mvn spring-boot:run` verwenden: der RuntimeClasspathGuard bricht ab.
Das Routing nutzt React Router v6.
Der Workflow laeuft mit `--model claude-opus-4-6`.
Die Themes stehen in `tailwind.config.ts`.
Unter `src/test/java` liegen 715 Testklassen.
Stand 2026-09-18: 114 Vitest-Dateien unter `src/`.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "veraltete Version gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/README.md:4 version\(Spring Boot\) sagt 9.9.8, Code 9.9.9" <<<"$OUT"'
check "gekuerzte, passende Version bleibt frei" '! grep -q "README.md:5" <<<"$OUT"'
check "Version im Fliesstext der Zelle erkannt" '! grep -q "README.md:6" <<<"$OUT"'
check "falsche Nebenversion gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/README.md:7 version\(Vite\) sagt 2.4, Code 2.3.4" <<<"$OUT"'
check "Zeile ohne Versionszahl bleibt frei"     '! grep -q "README.md:8" <<<"$OUT"'
check "mvn spring-boot:run gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/SETUP.md:2 verbotenes-muster" <<<"$OUT"'
check "Warn-Blockquote bleibt erlaubt"          '! grep -q "SETUP.md:3" <<<"$OUT"'
check "veraltete React-Router-Version gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/SETUP.md:4 verbotenes-muster" <<<"$OUT"'
check "datierte Modell-ID gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/SETUP.md:5 verbotenes-muster.*[Mm]odell" <<<"$OUT"'
check "nicht existierende Datei gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/SETUP.md:6 fehlende-datei.*tailwind.config.ts" <<<"$OUT"'
check "undatierte Testzahl im Onboarding gemeldet" \
  'grep -qE "^DRIFT docs/onboarding/SETUP.md:7 testzahl-ohne-datum" <<<"$OUT"'
check "datierte Testzahl bleibt frei"           '! grep -q "SETUP.md:8" <<<"$OUT"'
rm -r "$FAKE/docs/onboarding"

echo "13) Fehlende Versionsquelle ist ein harter Fehler"
mv "$FAKE/ai-automation-frontend/package.json" "$TMP/package.bak"
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "exit 2 ohne package.json"   '[ "$RC" -eq 2 ]'
check "nennt die fehlende Quelle"  'grep -qi "package.json" <<<"$OUT"'
check "meldet kein OK"             '! grep -q "Doku-Zahlen OK" <<<"$OUT"'
mv "$TMP/package.bak" "$FAKE/ai-automation-frontend/package.json"

echo "14) Verbotene Muster, fehlende Dateien und Testzahl-Datierung gelten in ganz docs/ (TODO c9c9f3eb)"
# Bewusst KEINE Onboarding-Datei: vor c9c9f3eb galten diese drei Regeln nur unter
# docs/onboarding, hier wird die Ausweitung selbst geprueft.
cat > "$FAKE/docs/regeln.md" <<'MD_EOF'
# Regeln
Starte das Backend mit `mvn spring-boot:run`.
> **Nicht** `mvn spring-boot:run` verwenden: der RuntimeClasspathGuard bricht ab.
Der Workflow laeuft mit `--model claude-opus-4-6`.
Eine konkrete ID wie `claude-opus-5` ist erlaubt, sie veraltet nicht.
Die Themes stehen in `tailwind.config.ts`.
Unter `src/test/java` liegen 715 Testklassen.
Es gibt 42 Testklassen im Repo (Stand
2026-09-18, per grep gezaehlt).
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "mvn spring-boot:run auch ausserhalb des Onboardings gemeldet" \
  'grep -qE "^DRIFT docs/regeln.md:2 verbotenes-muster" <<<"$OUT"'
check "Warn-Blockquote bleibt auch hier erlaubt"      '! grep -q "regeln.md:3" <<<"$OUT"'
check "datierte Modell-ID auch ausserhalb gemeldet" \
  'grep -qE "^DRIFT docs/regeln.md:4 verbotenes-muster.*[Mm]odell" <<<"$OUT"'
check "einsegmentige Modell-ID (claude-opus-5) bleibt frei" '! grep -q "regeln.md:5" <<<"$OUT"'
check "fehlende Datei auch ausserhalb gemeldet" \
  'grep -qE "^DRIFT docs/regeln.md:6 fehlende-datei.*tailwind.config.ts" <<<"$OUT"'
check "undatierte Testzahl auch ausserhalb gemeldet" \
  'grep -qE "^DRIFT docs/regeln.md:7 testzahl-ohne-datum" <<<"$OUT"'
check "Datierung hinter dem Zeilenumbruch zaehlt"    '! grep -q "regeln.md:8" <<<"$OUT"'
rm "$FAKE/docs/regeln.md"

# Gegenprobe zur Umbruch-Toleranz: ein Datum in der Folgezeile darf eine
# abgeschlossene Aussage NICHT stumm stellen (sonst deckt jede datierte
# Nachbarzeile eine undatierte Testzahl).
cat > "$FAKE/docs/regeln.md" <<'MD_EOF'
# Regeln
Unter `src/test/java` liegen 715 Testklassen.
Stand 2026-09-18: die Messung lief auf main.
MD_EOF
OUT=$(bash "$SCRIPT" --root "$FAKE" 2>&1); RC=$?
check "abgeschlossener Satz wird von datierter Nachbarzeile nicht gedeckt" \
  'grep -qE "^DRIFT docs/regeln.md:2 testzahl-ohne-datum" <<<"$OUT"'
rm "$FAKE/docs/regeln.md"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
