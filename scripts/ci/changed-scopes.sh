#!/usr/bin/env bash
#
# Which CI suites does a change need? (TODO fb82de25)
#
# Prints two lines for $GITHUB_OUTPUT:
#   backend=true|false
#   frontend=true|false
# Reasons go to stderr so that a skip is audible in the log.
#
# Principle: a gap in the lists may cost at most an unnecessary run, never a
# missing one. Therefore the backend is filtered the OTHER WAY AROUND — it always runs, unless *every*
# changed file is demonstrably outside its reach —, and any doubt
# (no base, empty list, git error) yields true for both suites.
#
# Backend tests also read files outside ai-automation-backend/ (as of 2026-09-29,
# via grep for "../" in src/test and pom.xml):
#   - docs/**                                 pom copies docs to resources/docs; DocsConfigTest,
#                                             DataModelConfigKeysGateTest (docs/datenmodell.md, en/)
#   - workflow-pool/**                        pool/hygiene/analyzer gates (PoolFileParserTest,
#                                             TemplateHygieneGateTest, LocalizedDefinitionGateTest …)
#   - ai-automation-frontend/src/data/**      pom copies blockConfigSchema.json; ModuleCatalogTextsTest,
#                                             EditorDefaultConfigTest (blockDefaultConfig.json)
#   - ai-automation-frontend/src/i18n/**      ProviderCatalogTest reads src/i18n/<lang>.ts
#   - scripts/lib/**                          *.generated.sh, i18n/<lang>.sh, hygiene-rules.sh
#   - deploy/Dockerfile                       ProviderCatalogTest
#   - CLAUDE.md (root)                        BlockSpecsTest
# The exception list below therefore names only what definitely does NOT belong: the rest of the
# frontend and Markdown outside docs/, ai-automation-backend/ and workflow-pool/
# (except CLAUDE.md). Everything else — including any new folder — starts the backend.
#
# The frontend runs when anything under ai-automation-frontend/ changes, and additionally for
# the definitions that blockConfigSchema.test.ts checks against the editor catalog:
#   - ai-automation-backend/src/main/resources/templates/**   (builtin/, blueprints/)
#   - workflow-pool/**
# If the CI definition or this script changes, both suites run.
#
# The pre-push gate uses the same classification for its build check (scripts/ci/pre-push-checks.sh,
# check_build, TODO c60eb0e2) — a change here takes effect locally and in CI alike.
#
# Invocations:
#   bash scripts/ci/changed-scopes.sh --files  < list      file list from stdin (one path per line)
#   BASE=<sha> HEAD=<sha> bash scripts/ci/changed-scopes.sh  list from git diff --no-renames BASE...HEAD
# In CI, GitHub additionally sets GITHUB_EVENT_NAME and GITHUB_REF_TYPE:
#   schedule, workflow_dispatch and tags always run in full; so does an empty or zero base
#   and a base that is not in the checkout (force push).
# Exit: always 0 — an error here must not switch off a suite, it switches both on.
#
set -uo pipefail

ZERO="0000000000000000000000000000000000000000"

all_true() {  # $1 = reason
  echo "changed-scopes: $1 — beide Suiten laufen" >&2
  echo "backend=true"
  echo "frontend=true"
  exit 0
}

# Is the file demonstrably out of reach of the backend? (see header)
backend_irrelevant() {  # $1 = path relative to the repo root
  case "$1" in
    ai-automation-frontend/src/data/*|ai-automation-frontend/src/i18n/*) return 1 ;;
    ai-automation-frontend/*) return 0 ;;
    CLAUDE.md|docs/*|ai-automation-backend/*|workflow-pool/*) return 1 ;;
    *.md) return 0 ;;
  esac
  return 1
}

frontend_relevant() {  # $1 = path
  case "$1" in
    ai-automation-frontend/*) return 0 ;;
    ai-automation-backend/src/main/resources/templates/*) return 0 ;;
    workflow-pool/*) return 0 ;;
  esac
  return 1
}

# Change to the CI itself: both suites, so that the new filter/job runs in full once.
ci_definition() {  # $1 = path
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
  # --no-renames: a moved file names the old AND the new path — a move out of the backend
  # must not make the backend skip. Three dots: from the merge base.
  FILES="$(git -c core.quotePath=false diff --no-renames --name-only "${BASE}...${HEAD}" 2>/dev/null)" \
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
