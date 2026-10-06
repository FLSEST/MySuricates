#!/usr/bin/env bash
#
# Check doc figures against the code (TODO 3df2df73).
#
# The figures in docs/ and README.de.md drift anew with every release:
# controllers were wrong in four places, the migration state in six,
# the block-type count in two. For the code there are tests (BlockTypeListsGuardTest,
# blockTypes.test.ts), for the docs there was nothing.
#
# This script collects the figures deterministically from the code and reports every
# place in the docs that claims something else. It changes nothing.
#
# Usage:  scripts/public-readiness/doc-facts-check.sh [--root <directory>] [--facts] [path ...]
#           --root   repo root (default: git toplevel, else the working directory)
#           --facts  only print the collected figures, check nothing
#           path     files/directories to check
#                    (default: docs README.de.md workflow-pool/README.md AGENTS.md)
#
# Exit:   0 = no drift, 1 = drift found, 2 = fact source missing
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
# workflow-pool/README.md names the number of built-in templates and therefore belongs
# in the check -- otherwise it would be a frozen number outside the gate's reach,
# exactly the kind this script is meant to prevent. AGENTS.md is the
# short reference for external agents: its curl examples are copied verbatim and
# therefore fall under the --noproxy rule (TODO 2e3cf243).
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
# Collect figures
#
# Every quantity has exactly one source. If a source yields 0, that is a hard
# error: an empty result would otherwise report every doc figure as drift or,
# worse, silently check nothing.
# ---------------------------------------------------------------------------
fact_block_types() {
  [ -f "$REGISTRY" ] || { MISSING+=("block-types: $REGISTRY fehlt"); echo 0; return; }
  grep -oE 'new BlockTypeEntry\("[a-z-]+"' "$REGISTRY" | sort -u | wc -l | tr -d ' '
}
# The names themselves, for checking "type" enumerations (TODO e36ca773).
fact_block_type_names() {
  [ -f "$REGISTRY" ] || { echo "[]"; return; }
  grep -oE 'new BlockTypeEntry\("[a-z-]+"' "$REGISTRY" | sed 's/.*("\([a-z-]*\)"/"\1"/' \
    | awk '!seen[$0]++' | paste -sd',' - | sed 's/^/[/; s/$/]/'
}
fact_controllers() {
  [ -d "$JAVA_SRC" ] || { MISSING+=("controllers: $JAVA_SRC fehlt"); echo 0; return; }
  find "$JAVA_SRC" -name '*Controller.java' | wc -l | tr -d ' '
}
# The names themselves (without "Controller"), for the enumeration "**Controller:** A, B, …" in
# docs/entwicklung.md: the number was right, the list below it had drifted twice (TODO 65eab274).
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
# All existing numbers, not just count and maximum: the completeness check
# of the migration tables compares against the files that ACTUALLY exist. Assuming a
# sequence would be wrong -- numbers may have been skipped, and reporting a never
# existing V4 as a missing table row would be a false alarm (TODO ffa6d846).
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
# Commit count of the README header table. Two cases are no measurement and are therefore
# demonstrably skipped instead of silently waved through:
#
#   1. No git repo (fixture, unpacked archive) — git does not answer.
#   2. Shallow clone — git answers with the fetched depth instead of the truth, i.e. with
#      a plausible-looking wrong number. That is the more dangerous case: on
#      2026-09-19 the public-hygiene job checked out without `fetch-depth: 0`, `rev-list` counted
#      1, and the gate reported "commits says 1550, code 1" — a false finding that turned CI red
#      and said nothing about the docs. A gate that cannot measure a number
#      must not name one.
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
# Versions in technology tables ("| **React** | 19.2 |", none in the docs today)
#
# The source is pom.xml or package.json. The docs abbreviate ("React 19.2" for
# 19.2.4) — so the check is a component-wise prefix match, see
# doc_facts_check.py. If a source is missing, the run aborts: an empty version
# would silently wave every table row through.
# ---------------------------------------------------------------------------
pom_property() {  # $1 = name of the property tag
  sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" "$POM" 2>/dev/null | head -1
}
pom_parent_version() {
  grep -A4 'spring-boot-starter-parent' "$POM" 2>/dev/null \
    | sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' | head -1
}
pkg_version() {  # $1 = package name
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

# File names the onboarding used to mention although they do not exist. Each time it is
# re-checked whether they really are missing — if the file reappears, the rule
# drops out by itself instead of raising a false alarm.
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

# Reference-date commits of the README header table ("Stand … Commit `d23a09ed`"): there the number of the
# reference date applies, not that of HEAD (TODO 65c6dedc). A commit git does not know (e.g. after a
# history rewrite) goes in as null — the checker reports it instead of silently measuring at HEAD.
# The same holds for a commit outside the history of HEAD: after a rewrite a backup branch
# still knows it locally, CI does not — green locally, red in CI (2026-09-30).
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

# MISSING is not filled in subshells (command substitution) — hence additionally
# check against 0 here. That is the actual safeguard.
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
# Collect files (relative to the root, so the messages stay short)
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
