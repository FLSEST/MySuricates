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
#           pfad     zu pruefende Dateien/Verzeichnisse (Default: docs README.de.md)
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
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(docs README.de.md)

BACKEND="$ROOT/ai-automation-backend"
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
fact_controllers() {
  [ -d "$JAVA_SRC" ] || { MISSING+=("controllers: $JAVA_SRC fehlt"); echo 0; return; }
  find "$JAVA_SRC" -name '*Controller.java' | wc -l | tr -d ' '
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
# Commit-Zahl der README-Kopftabelle. Kein Git-Repo (Fixture, entpacktes Archiv) ist kein
# Fehler — dann wird die Zahl nachweislich uebersprungen statt still durchgewunken.
fact_commits() {
  git -C "$ROOT" rev-list --count HEAD 2>/dev/null | tr -d ' '
}

BLOCK_TYPES="$(fact_block_types)"
CONTROLLERS="$(fact_controllers)"
MIGRATION_MAX="$(fact_migration_max)"
MIGRATION_COUNT="$(fact_migrations)"
TEMPLATES="$(fact_templates)"
SKILLS="$(fact_skills)"
PROVIDERS="$(fact_providers)"
COMMITS="$(fact_commits)"

COMMITS_JSON=""
COMMITS_NOTE=""
if [ -n "$COMMITS" ] && [ "$COMMITS" != "0" ]; then
  COMMITS_JSON=",\"commits\":$COMMITS"
  COMMITS_NOTE=", Commits $COMMITS"
else
  echo "doc-facts-check: uebersprungen — kein Git-Repository (oder kein Commit) unter $ROOT:" \
       "die Commit-Zahl der README-Kopftabelle wird nicht geprueft." >&2
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
DOC_FACTS="$(printf '{"block-types":%s,"controllers":%s,"migration-max":%s,"migrations":%s,"templates":%s,"skills":%s,"integration-providers":%s%s}' \
  "$BLOCK_TYPES" "$CONTROLLERS" "$MIGRATION_MAX" "$MIGRATION_COUNT" \
  "$TEMPLATES" "$SKILLS" "$PROVIDERS" "$COMMITS_JSON")" \
  python3 "$PY" "${FILES[@]}"
RC=$?

if [ "$RC" -eq 0 ]; then
  echo "Doku-Zahlen OK — ${#FILES[@]} Dateien, 7 Groessen (Blocktypen $BLOCK_TYPES, Controller $CONTROLLERS, Migrationen $MIGRATION_COUNT/V$MIGRATION_MAX, Templates $TEMPLATES, Skills $SKILLS, Provider $PROVIDERS)${COMMITS_NOTE}, Migrationstabellen und Testzahl-Datierung."
fi
exit "$RC"
