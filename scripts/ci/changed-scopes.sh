#!/usr/bin/env bash
#
# Welche CI-Suiten braucht eine Aenderung? (TODO fb82de25)
#
# Gibt zwei Zeilen fuer $GITHUB_OUTPUT aus:
#   backend=true|false
#   frontend=true|false
# Begruendungen gehen nach stderr, damit ein Ueberspringen im Log hoerbar ist.
#
# Grundsatz: Eine Luecke in den Listen darf hoechstens einen unnoetigen Lauf kosten, nie einen
# fehlenden. Deshalb wird das Backend UMGEKEHRT gefiltert — es laeuft immer, ausser *jede*
# geaenderte Datei steht nachweislich ausserhalb seiner Reichweite —, und jeder Zweifel
# (keine Basis, leere Liste, git-Fehler) ergibt fuer beide Suiten true.
#
# Backend-Tests lesen auch Dateien ausserhalb von ai-automation-backend/ (Stand 2026-09-29,
# per grep nach "../" in src/test und pom.xml):
#   - docs/**                                 pom kopiert Doku nach resources/docs; DocsConfigTest,
#                                             DataModelConfigKeysGateTest (docs/datenmodell.md, en/)
#   - workflow-pool/**                        Pool-/Hygiene-/Analyzer-Gates (PoolFileParserTest,
#                                             TemplateHygieneGateTest, LocalizedDefinitionGateTest …)
#   - ai-automation-frontend/src/data/**      pom kopiert blockConfigSchema.json; ModuleCatalogTextsTest,
#                                             EditorDefaultConfigTest (blockDefaultConfig.json)
#   - ai-automation-frontend/src/i18n/**      ProviderCatalogTest liest src/i18n/<lang>.ts
#   - scripts/lib/**                          *.generated.sh, i18n/<lang>.sh, hygiene-rules.sh
#   - deploy/Dockerfile                       ProviderCatalogTest
#   - CLAUDE.md (Wurzel)                      BlockSpecsTest
# Die Ausnahmeliste unten nennt deshalb nur, was sicher NICHT dazugehoert: der Rest des
# Frontends und Markdown ausserhalb von docs/, ai-automation-backend/ und workflow-pool/
# (ausser CLAUDE.md). Alles andere — auch jeder neue Ordner — startet das Backend.
#
# Das Frontend laeuft, wenn sich etwas unter ai-automation-frontend/ aendert, und zusaetzlich bei
# den Definitionen, die blockConfigSchema.test.ts gegen den Editor-Katalog prueft:
#   - ai-automation-backend/src/main/resources/templates/**   (builtin/, blueprints/)
#   - workflow-pool/**
# Aendert sich die CI-Definition oder dieses Skript, laufen beide Suiten.
#
# Dieselbe Einordnung nutzt das Pre-Push-Gate fuer seine Bauprüfung (scripts/ci/pre-push-checks.sh,
# check_build, TODO c60eb0e2) — eine Aenderung hier wirkt lokal und in der CI gleich.
#
# Aufrufe:
#   bash scripts/ci/changed-scopes.sh --files  < liste      Dateiliste von stdin (je Zeile ein Pfad)
#   BASE=<sha> HEAD=<sha> bash scripts/ci/changed-scopes.sh  Liste aus git diff --no-renames BASE...HEAD
# In der CI setzt GitHub zusaetzlich GITHUB_EVENT_NAME und GITHUB_REF_TYPE:
#   schedule, workflow_dispatch und Tags laufen immer voll; ebenso eine leere oder Null-Basis
#   und eine Basis, die nicht im Checkout liegt (Force-Push).
# Exit: immer 0 — ein Fehler hier darf keine Suite abschalten, er schaltet beide ein.
#
set -uo pipefail

ZERO="0000000000000000000000000000000000000000"

all_true() {  # $1 = Grund
  echo "changed-scopes: $1 — beide Suiten laufen" >&2
  echo "backend=true"
  echo "frontend=true"
  exit 0
}

# Reicht die Datei nachweislich nicht ins Backend hinein? (siehe Kopf)
backend_irrelevant() {  # $1 = Pfad relativ zur Repo-Wurzel
  case "$1" in
    ai-automation-frontend/src/data/*|ai-automation-frontend/src/i18n/*) return 1 ;;
    ai-automation-frontend/*) return 0 ;;
    CLAUDE.md|docs/*|ai-automation-backend/*|workflow-pool/*) return 1 ;;
    *.md) return 0 ;;
  esac
  return 1
}

frontend_relevant() {  # $1 = Pfad
  case "$1" in
    ai-automation-frontend/*) return 0 ;;
    ai-automation-backend/src/main/resources/templates/*) return 0 ;;
    workflow-pool/*) return 0 ;;
  esac
  return 1
}

# Aenderung an der CI selbst: beide Suiten, damit der neue Filter/Job einmal voll durchlaeuft.
ci_definition() {  # $1 = Pfad
  case "$1" in
    .github/workflows/ci.yml|scripts/ci/changed-scopes.sh) return 0 ;;
  esac
  return 1
}

FILES=""
if [ "${1:-}" = "--files" ]; then
  FILES="$(cat)"
elif [ -n "${1:-}" ]; then
  echo "changed-scopes: unbekanntes Argument '$1' (erlaubt: --files)" >&2
  all_true "Aufruf nicht verstanden"
else
  case "${GITHUB_EVENT_NAME:-}" in
    schedule|workflow_dispatch) all_true "Ereignis ${GITHUB_EVENT_NAME}" ;;
  esac
  [ "${GITHUB_REF_TYPE:-}" = "tag" ] && all_true "Tag"
  BASE="${BASE:-}"
  HEAD="${HEAD:-HEAD}"
  { [ -z "$BASE" ] || [ "$BASE" = "$ZERO" ]; } && all_true "keine Vergleichsbasis (neuer Ref oder kein Push-/PR-Ereignis)"
  git cat-file -e "${BASE}^{commit}" 2>/dev/null || all_true "Basis ${BASE} liegt nicht im Checkout (Force-Push?)"
  git cat-file -e "${HEAD}^{commit}" 2>/dev/null || all_true "Kopf ${HEAD} liegt nicht im Checkout"
  # --no-renames: eine verschobene Datei nennt alten UND neuen Pfad — ein Umzug aus dem Backend
  # heraus soll das Backend nicht ueberspringen. Drei Punkte: ab der Merge-Basis.
  FILES="$(git diff --no-renames --name-only "${BASE}...${HEAD}" 2>/dev/null)" \
    || all_true "git diff ${BASE}...${HEAD} fehlgeschlagen"
fi

BACKEND=false
FRONTEND=false
COUNT=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  COUNT=$((COUNT + 1))
  if ci_definition "$f"; then all_true "CI-Definition geaendert ($f)"; fi
  backend_irrelevant "$f" || BACKEND=true
  frontend_relevant "$f" && FRONTEND=true
done <<EOF
$FILES
EOF

[ "$COUNT" -eq 0 ] && all_true "keine geaenderten Dateien gefunden"

echo "changed-scopes: ${COUNT} Datei(en) — backend=${BACKEND}, frontend=${FRONTEND}" >&2
[ "$BACKEND" = false ] && echo "changed-scopes: Backend-Suite uebersprungen — alle Dateien liegen ausserhalb ihrer Reichweite" >&2
[ "$FRONTEND" = false ] && echo "changed-scopes: Frontend-Suite uebersprungen — nichts unter ai-automation-frontend/, templates/ oder workflow-pool/" >&2
echo "backend=${BACKEND}"
echo "frontend=${FRONTEND}"
