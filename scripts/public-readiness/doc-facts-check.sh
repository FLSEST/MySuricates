#!/usr/bin/env bash
#
# Doku-Zahlen gegen den Code pruefen (TODO 3df2df73).
#
# Die Groessen in docs/ und README.de.md driften bei jedem Release erneut:
# Controller standen an vier Stellen falsch, der Migrationsstand an sechs,
# die Blocktyp-Zahl an zwei. Fuer den Code gibt es Tests (BlockTypeListsGuardTest,
# blockTypes.test.ts), fuer die Doku gab es nichts.
#
# Dieses Skript erhebt die Zahlen deterministisch aus dem Code und meldet jede
# Stelle in der Doku, die etwas anderes behauptet. Es aendert nichts.
#
# Usage:  scripts/public-readiness/doc-facts-check.sh [--root <verzeichnis>] [--facts] [pfad ...]
#           --root   Repo-Wurzel (Default: git-Toplevel, sonst das Arbeitsverzeichnis)
#           --facts  nur die erhobenen Zahlen ausgeben, nichts pruefen
#           pfad     zu pruefende Dateien/Verzeichnisse
#                    (Default: docs README.de.md workflow-pool/README.md AGENTS.md)
#
# Exit:   0 = keine Drift, 1 = Drift gefunden, 2 = Faktenquelle fehlt
#
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="$SELF_DIR/doc_facts_check.py"

ROOT=""
FACTS_ONLY=0
TARGETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --root)  ROOT="${2:-}"; shift 2 ;;
    --facts) FACTS_ONLY=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) TARGETS+=("$1"); shift ;;
  esac
done

if [ -z "$ROOT" ]; then
  ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
ROOT="$(cd "$ROOT" 2>/dev/null && pwd)" || { echo "doc-facts-check: Wurzel nicht lesbar" >&2; exit 2; }
# workflow-pool/README.md nennt die Zahl der Built-in-Templates und gehoert deshalb
# in die Pruefung -- sonst waere sie eine eingefrorene Zahl ausserhalb der Reichweite
# des Gates, genau die Sorte, die dieses Skript verhindern soll. AGENTS.md ist die
# Kurzreferenz fuer externe Agents: Ihre curl-Beispiele werden woertlich kopiert und
# gehoeren deshalb unter die --noproxy-Regel (TODO 2e3cf243).
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(docs README.de.md workflow-pool/README.md AGENTS.md)

BACKEND="$ROOT/ai-automation-backend"
FRONTEND="$ROOT/ai-automation-frontend"
POM="$BACKEND/pom.xml"
PKG="$FRONTEND/package.json"
JAVA_SRC="$BACKEND/src/main/java"
RES="$BACKEND/src/main/resources"
REGISTRY="$JAVA_SRC/com/automation/service/assistant/context/BlockTypesIndexSection.java"
MIGRATIONS="$RES/db/migration"

MISSING=()

# ---------------------------------------------------------------------------
# Zahlen erheben
#
# Jede Groesse hat genau eine Quelle. Ergibt eine Quelle 0, ist das ein harter
# Fehler: ein leeres Ergebnis wuerde sonst jede Doku-Zahl als Drift melden oder,
# schlimmer, stillschweigend nichts pruefen.
# ---------------------------------------------------------------------------
fact_block_types() {
  [ -f "$REGISTRY" ] || { MISSING+=("block-types: $REGISTRY fehlt"); echo 0; return; }
  grep -oE 'new BlockTypeEntry\("[a-z-]+"' "$REGISTRY" | sort -u | wc -l | tr -d ' '
}
# Die Namen selbst, fuer die Pruefung von "type"-Aufzaehlungen (TODO e36ca773).
fact_block_type_names() {
  [ -f "$REGISTRY" ] || { echo "[]"; return; }
  grep -oE 'new BlockTypeEntry\("[a-z-]+"' "$REGISTRY" | sed 's/.*("\([a-z-]*\)"/"\1"/' \
    | awk '!seen[$0]++' | paste -sd',' - | sed 's/^/[/; s/$/]/'
}
fact_controllers() {
  [ -d "$JAVA_SRC" ] || { MISSING+=("controllers: $JAVA_SRC fehlt"); echo 0; return; }
  find "$JAVA_SRC" -name '*Controller.java' | wc -l | tr -d ' '
}
# Die Namen selbst (ohne "Controller"), fuer die Aufzaehlung "**Controller:** A, B, …" in
# docs/entwicklung.md: Die Zahl stimmte, die Liste darunter war zweimal abgedriftet (TODO 65eab274).
fact_controller_names() {
  [ -d "$JAVA_SRC" ] || { echo "[]"; return; }
  find "$JAVA_SRC" -name '*Controller.java' -printf '%f\n' | sed 's/Controller\.java$//' | sort -u \
    | sed 's/.*/"&"/' | paste -sd',' - | sed 's/^/[/; s/$/]/'
}
fact_migration_max() {
  [ -d "$MIGRATIONS" ] || { MISSING+=("migration-max: $MIGRATIONS fehlt"); echo 0; return; }
  find "$MIGRATIONS" -maxdepth 1 -name 'V*.sql' -printf '%f\n' 2>/dev/null \
    | sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p' | sort -n | tail -1
}
fact_migrations() {
  [ -d "$MIGRATIONS" ] || { MISSING+=("migrations: $MIGRATIONS fehlt"); echo 0; return; }
  find "$MIGRATIONS" -maxdepth 1 -name 'V*.sql' | wc -l | tr -d ' '
}
# Alle vorhandenen Nummern, nicht nur Anzahl und Hoechststand: Die Vollstaendigkeitspruefung
# der Migrationstabellen vergleicht gegen die WIRKLICH vorhandenen Dateien. Eine Reihe
# anzunehmen waere falsch -- Nummern koennen uebersprungen worden sein, und eine nie
# existierende V4 als fehlende Tabellenzeile zu melden waere ein Fehlalarm (TODO ffa6d846).
fact_migration_numbers() {
  [ -d "$MIGRATIONS" ] || { echo "[]"; return; }
  find "$MIGRATIONS" -maxdepth 1 -name 'V*.sql' -printf '%f\n' 2>/dev/null \
    | sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p' | sort -n \
    | paste -sd',' - | sed 's/^/[/; s/$/]/' | sed 's/\[\]/[]/'
}
fact_templates() {
  [ -d "$RES/templates/builtin" ] || { MISSING+=("templates: $RES/templates/builtin fehlt"); echo 0; return; }
  find "$RES/templates/builtin" -maxdepth 1 -name '*.json' | wc -l | tr -d ' '
}
fact_skills() {
  [ -d "$RES/skills/builtin" ] || { MISSING+=("skills: $RES/skills/builtin fehlt"); echo 0; return; }
  find "$RES/skills/builtin" -maxdepth 1 -name '*.json' | wc -l | tr -d ' '
}
fact_providers() {
  [ -d "$JAVA_SRC" ] || { MISSING+=("integration-providers: $JAVA_SRC fehlt"); echo 0; return; }
  grep -rl 'implements IntegrationProvider' "$JAVA_SRC" 2>/dev/null | wc -l | tr -d ' '
}
# Commit-Zahl der README-Kopftabelle. Zwei Faelle sind keine Messung und werden deshalb
# nachweislich uebersprungen statt still durchgewunken:
#
#   1. Kein Git-Repo (Fixture, entpacktes Archiv) — git antwortet nicht.
#   2. Flacher Klon — git antwortet mit der geholten Tiefe statt mit der Wahrheit, also mit
#      einer plausibel aussehenden falschen Zahl. Das ist der gefaehrlichere Fall: Am
#      2026-09-19 checkte der Public-Hygiene-Job ohne `fetch-depth: 0` aus, `rev-list` zaehlte
#      1, und das Gate meldete "commits sagt 1550, Code 1" — ein Fehlbefund, der die CI rot
#      faerbte und nichts ueber die Doku aussagte. Ein Gate, das eine Zahl nicht messen kann,
#      darf keine nennen.
fact_commits_shallow() {
  [ "$(git -C "$ROOT" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]
}
fact_commits() {
  fact_commits_shallow && return
  git -C "$ROOT" rev-list --count HEAD 2>/dev/null | tr -d ' '
}

BLOCK_TYPES="$(fact_block_types)"
BLOCK_TYPE_NAMES="$(fact_block_type_names)"
CONTROLLERS="$(fact_controllers)"
CONTROLLER_NAMES="$(fact_controller_names)"
MIGRATION_MAX="$(fact_migration_max)"
MIGRATION_COUNT="$(fact_migrations)"
MIGRATION_NUMBERS="$(fact_migration_numbers)"
TEMPLATES="$(fact_templates)"
SKILLS="$(fact_skills)"
PROVIDERS="$(fact_providers)"
COMMITS="$(fact_commits)"

# ---------------------------------------------------------------------------
# Versionen von Technologietabellen ("| **React** | 19.2 |", heute keine in der Doku)
#
# Quelle ist pom.xml bzw. package.json. Die Doku kuerzt ("React 19.2" fuer
# 19.2.4) — geprueft wird deshalb komponentenweise als Praefix, siehe
# doc_facts_check.py. Fehlt eine Quelle, bricht der Lauf ab: eine leere Version
# wuerde jede Tabellenzeile stumm durchwinken.
# ---------------------------------------------------------------------------
pom_property() {  # $1 = Name des Property-Tags
  sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" "$POM" 2>/dev/null | head -1
}
pom_parent_version() {
  grep -A4 'spring-boot-starter-parent' "$POM" 2>/dev/null \
    | sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' | head -1
}
pkg_version() {  # $1 = Paketname
  sed -n "s|.*\"$1\"[[:space:]]*:[[:space:]]*\"[^0-9]*\([0-9][^\"]*\)\".*|\1|p" "$PKG" 2>/dev/null | head -1
}

[ -f "$POM" ] || MISSING+=("versions: $POM fehlt")
[ -f "$PKG" ] || MISSING+=("versions: $PKG fehlt")

VERSION_LABELS=("Spring Boot" "SQLite" "Docker Java Client" \
                "React" "TypeScript" "Vite" "TailwindCSS" "Cypress" "Vite PWA")
VERSION_VALUES=("$(pom_parent_version)" "$(pom_property sqlite-jdbc.version)" \
                "$(pom_property docker-java.version)" \
                "$(pkg_version react)" "$(pkg_version typescript)" "$(pkg_version vite)" \
                "$(pkg_version tailwindcss)" "$(pkg_version cypress)" "$(pkg_version vite-plugin-pwa)")
VERSIONS_JSON=""
for i in "${!VERSION_LABELS[@]}"; do
  if [ -z "${VERSION_VALUES[$i]}" ]; then
    MISSING+=("version '${VERSION_LABELS[$i]}': keine Quelle gefunden (pom.xml/package.json)")
  fi
  VERSIONS_JSON="${VERSIONS_JSON}${VERSIONS_JSON:+,}\"${VERSION_LABELS[$i]}\":\"${VERSION_VALUES[$i]}\""
done

# Dateinamen, die das Onboarding frueher nannte, obwohl es sie nicht gibt. Geprueft
# wird jedes Mal neu, ob sie wirklich fehlen — taucht die Datei wieder auf, faellt
# die Regel von selbst weg statt falschen Alarm zu schlagen.
ABSENT_CANDIDATES=(tailwind.config.ts BlockTypeSelector.tsx BuiltInTemplates.java)
ABSENT_JSON=""
for name in "${ABSENT_CANDIDATES[@]}"; do
  found="$(find "$ROOT" -name "$name" -not -path '*/node_modules/*' -not -path '*/target/*' \
             -print -quit 2>/dev/null)"
  [ -n "$found" ] && continue
  ABSENT_JSON="${ABSENT_JSON}${ABSENT_JSON:+,}\"$name\""
done

COMMITS_JSON=""
COMMITS_NOTE=""
if [ -n "$COMMITS" ] && [ "$COMMITS" != "0" ]; then
  COMMITS_JSON=",\"commits\":$COMMITS"
  COMMITS_NOTE=", Commits $COMMITS"
elif fact_commits_shallow; then
  echo "doc-facts-check: uebersprungen — flacher Klon unter $ROOT (rev-list zaehlt dort nur die" \
       "geholte Tiefe, nicht die Historie): die Commit-Zahl der README-Kopftabelle wird nicht" \
       "geprueft. Damit sie geprueft wird: actions/checkout mit 'fetch-depth: 0'." >&2
else
  echo "doc-facts-check: uebersprungen — kein Git-Repository (oder kein Commit) unter $ROOT:" \
       "die Commit-Zahl der README-Kopftabelle wird nicht geprueft." >&2
fi

# Stichtag-Commits der README-Kopftabelle ("Stand … Commit `d23a09ed`"): Dort gilt die Zahl des
# Stichtags, nicht die von HEAD (TODO 65c6dedc). Ein Commit, den git nicht kennt (etwa nach einem
# History-Rewrite), geht als null mit – der Checker meldet ihn, statt still an HEAD zu messen.
# Dasselbe gilt fuer einen Commit ausserhalb der Historie von HEAD: Nach einem Rewrite kennt ihn
# lokal noch ein Sicherungs-Branch, die CI nicht – lokal gruen, in der CI rot (2026-09-30).
COMMITS_AT_JSON=""
if [ -n "$COMMITS_JSON" ] && [ -f "$ROOT/README.de.md" ]; then
  pairs=""
  while IFS= read -r sha; do
    [ -z "$sha" ] && continue
    count=""
    git -C "$ROOT" merge-base --is-ancestor "$sha" HEAD 2>/dev/null \
      && count="$(git -C "$ROOT" rev-list --count "$sha" 2>/dev/null | tr -d ' ')"
    pairs="${pairs}${pairs:+,}\"$sha\":${count:-null}"
  done < <(grep -oE 'Commit `[0-9a-f]{7,40}`' "$ROOT/README.de.md" | grep -oE '[0-9a-f]{7,40}' | sort -u)
  [ -n "$pairs" ] && COMMITS_AT_JSON=",\"commits-at\":{$pairs}"
fi

# MISSING wird in Subshells nicht gefuellt (Kommandosubstitution) — deshalb hier
# zusaetzlich gegen 0 pruefen. Das ist die eigentliche Absicherung.
for pair in "block-types:$BLOCK_TYPES" "controllers:$CONTROLLERS" \
            "migration-max:$MIGRATION_MAX" "migrations:$MIGRATION_COUNT" \
            "templates:$TEMPLATES" "skills:$SKILLS" \
            "integration-providers:$PROVIDERS"; do
  value="${pair##*:}"
  if [ -z "$value" ] || [ "$value" = "0" ]; then
    MISSING+=("${pair%%:*}: keine Quelle gefunden (Ergebnis leer oder 0)")
  fi
done

if [ ${#MISSING[@]} -ne 0 ]; then
  echo "doc-facts-check: Faktenquellen fehlen — es wurde NICHTS geprueft:" >&2
  printf '  %s\n' "${MISSING[@]}" >&2
  exit 2
fi

if [ "$FACTS_ONLY" -eq 1 ]; then
  printf '%-22s %s\n' \
    block-types "$BLOCK_TYPES" \
    controllers "$CONTROLLERS" \
    migration-max "$MIGRATION_MAX" \
    migrations "$MIGRATION_COUNT" \
    templates "$TEMPLATES" \
    skills "$SKILLS" \
    integration-providers "$PROVIDERS" \
    commits "${COMMITS:-(kein Git-Repository)}"
  for i in "${!VERSION_LABELS[@]}"; do
    printf '%-22s %s\n' "version:${VERSION_LABELS[$i]}" "${VERSION_VALUES[$i]}"
  done
  printf '%-22s %s\n' absent-files "${ABSENT_JSON:-(keine)}"
  exit 0
fi

# ---------------------------------------------------------------------------
# Dateien einsammeln (relativ zur Wurzel, damit die Meldungen kurz bleiben)
# ---------------------------------------------------------------------------
FILES=()
for target in "${TARGETS[@]}"; do
  if [ -d "$ROOT/$target" ]; then
    while IFS= read -r f; do FILES+=("${f#"$ROOT/"}"); done \
      < <(find "$ROOT/$target" -name '*.md' -type f | sort)
  elif [ -f "$ROOT/$target" ]; then
    FILES+=("$target")
  fi
done

if [ ${#FILES[@]} -eq 0 ]; then
  echo "doc-facts-check: keine Markdown-Dateien unter ${TARGETS[*]} — nichts zu pruefen."
  exit 0
fi

DOC_FACTS_ROOT="$ROOT" \
DOC_FACTS="$(printf '{"block-types":%s,"block-type-names":%s,"controllers":%s,"controller-names":%s,"migration-max":%s,"migrations":%s,"migration-numbers":%s,"templates":%s,"skills":%s,"integration-providers":%s%s%s,"versions":{%s},"absent-files":[%s]}' \
  "$BLOCK_TYPES" "$BLOCK_TYPE_NAMES" "$CONTROLLERS" "$CONTROLLER_NAMES" "$MIGRATION_MAX" "$MIGRATION_COUNT" "$MIGRATION_NUMBERS" \
  "$TEMPLATES" "$SKILLS" "$PROVIDERS" "$COMMITS_JSON" "$COMMITS_AT_JSON" "$VERSIONS_JSON" "$ABSENT_JSON")" \
  python3 "$PY" "${FILES[@]}"
RC=$?

if [ "$RC" -eq 0 ]; then
  echo "Doku-Zahlen OK — ${#FILES[@]} Dateien, 7 Groessen (Blocktypen $BLOCK_TYPES, Controller $CONTROLLERS, Migrationen $MIGRATION_COUNT/V$MIGRATION_MAX, Templates $TEMPLATES, Skills $SKILLS, Provider $PROVIDERS)${COMMITS_NOTE}, Migrationstabellen, Blocktyp- und Controller-Aufzaehlungen, Testzahl-Datierung, Zahlen ueber die ausgelieferten Definitionen und --noproxy an API-Key-curls."
fi
exit "$RC"
