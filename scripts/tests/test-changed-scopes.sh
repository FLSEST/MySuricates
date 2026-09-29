#!/usr/bin/env bash
# Tests for scripts/ci/changed-scopes.sh — which CI suites a change needs (fb82de25).
# File lists via --files, git mode against a throwaway repo; nothing touches this repository.
# Run: bash scripts/tests/test-changed-scopes.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../ci/changed-scopes.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
# scopes "<datei>\n<datei>…" -> "backend=… frontend=…" (stderr verworfen, CI-Variablen leer)
scopes() { printf '%b' "$1" | env -u GITHUB_EVENT_NAME -u GITHUB_REF_TYPE bash "$SCRIPT" --files 2>/dev/null | tr '\n' ' '; }
BOTH="backend=true frontend=true "
NONE="backend=false frontend=false "
FE="backend=false frontend=true "
BE="backend=true frontend=false "

echo "1) file lists"
check "docs only (README, CONTRIBUTING): nothing runs"       '[ "$(scopes "README.md\nREADME.de.md\nCONTRIBUTING.md\n")" = "$NONE" ]'
check "frontend only: frontend runs, backend skipped"        '[ "$(scopes "ai-automation-frontend/src/pages/Home.tsx\nai-automation-frontend/package.json\n")" = "$FE" ]'
check "frontend src/data: backend runs too"                  '[ "$(scopes "ai-automation-frontend/src/data/modules.de.json\n")" = "$BOTH" ]'
check "frontend src/i18n: backend runs too (ProviderCatalogTest)" '[ "$(scopes "ai-automation-frontend/src/i18n/en.ts\n")" = "$BOTH" ]'
check "docs/*.md: backend runs (resources/docs)"             '[ "$(scopes "docs/architektur.md\n")" = "$BE" ]'
check "docs/ non-md: backend runs"                           '[ "$(scopes "docs/architektur-diagrams.html\n")" = "$BE" ]'
check "workflow-pool: both run"                              '[ "$(scopes "workflow-pool/self-care-health.json\n")" = "$BOTH" ]'
check "workflow-pool/*.md: both run"                         '[ "$(scopes "workflow-pool/README.md\n")" = "$BOTH" ]'
check "root CLAUDE.md: backend runs (BlockSpecsTest)"        '[ "$(scopes "CLAUDE.md\n")" = "$BE" ]'
check "backend resource .md: backend runs"                   '[ "$(scopes "ai-automation-backend/src/main/resources/agent-instructions.md\n")" = "$BE" ]'
check "backend templates: frontend runs too"                 '[ "$(scopes "ai-automation-backend/src/main/resources/templates/builtin/x.json\n")" = "$BOTH" ]'
check "backend java: backend only"                           '[ "$(scopes "ai-automation-backend/src/main/java/A.java\n")" = "$BE" ]'
check "scripts/lib: backend runs (generated catalogs)"       '[ "$(scopes "scripts/lib/provider-catalog.generated.sh\n")" = "$BE" ]'
check "unknown top-level path: backend runs (inverse filter)" '[ "$(scopes "somewhere-new/file.txt\n")" = "$BE" ]'
check "mixed docs + frontend: frontend only"                 '[ "$(scopes "README.md\nai-automation-frontend/src/App.tsx\n")" = "$FE" ]'
check "mixed frontend + backend: both"                       '[ "$(scopes "ai-automation-frontend/src/App.tsx\nai-automation-backend/pom.xml\n")" = "$BOTH" ]'
check "mixed docs + scripts: backend"                        '[ "$(scopes "README.md\nscripts/setup.sh\n")" = "$BE" ]'
check "ci.yml changed: both"                                 '[ "$(scopes "README.md\n.github/workflows/ci.yml\n")" = "$BOTH" ]'
check "this script changed: both"                            '[ "$(scopes "scripts/ci/changed-scopes.sh\n")" = "$BOTH" ]'
check "empty list: both"                                     '[ "$(scopes "")" = "$BOTH" ]'
check "blank lines only: both"                               '[ "$(scopes "\n\n")" = "$BOTH" ]'
check "unknown argument: both, exit 0"                       '[ "$(bash "$SCRIPT" --bogus 2>/dev/null </dev/null | tr "\n" " ")" = "$BOTH" ]'
check "skip is audible on stderr"                            'printf "README.md\n" | bash "$SCRIPT" --files 2>&1 >/dev/null | grep "Backend-Suite uebersprungen" >/dev/null'

echo "2) git mode"
R="$TMP/repo"
git init -q "$R"
g() { git -C "$R" -c user.name=t -c user.email=t@example.invalid "$@"; }
mkdir -p "$R/ai-automation-frontend/src" "$R/ai-automation-backend/src"
echo a > "$R/README.md"; echo a > "$R/ai-automation-backend/src/A.java"
g add -A; g commit -q -m base
BASE_SHA="$(g rev-parse HEAD)"
echo b > "$R/README.md"; echo b > "$R/ai-automation-frontend/src/App.tsx"
g add -A; g commit -q -m frontend
FE_SHA="$(g rev-parse HEAD)"
g mv ai-automation-backend/src/A.java ai-automation-frontend/src/A.java; g commit -q -m move
MOVE_SHA="$(g rev-parse HEAD)"
gscopes() { ( cd "$R" && env -u GITHUB_EVENT_NAME -u GITHUB_REF_TYPE "$@" bash "$SCRIPT" 2>/dev/null | tr '\n' ' ' ); }
check "push range docs+frontend: frontend only"   '[ "$(gscopes BASE="$BASE_SHA" HEAD="$FE_SHA")" = "$FE" ]'
check "move out of backend: backend runs (--no-renames)" '[ "$(gscopes BASE="$FE_SHA" HEAD="$MOVE_SHA")" = "$BOTH" ]'
check "HEAD defaults to HEAD"                     '[ "$(gscopes BASE="$BASE_SHA")" = "$BOTH" ]'
check "empty base: both"                          '[ "$(gscopes BASE= HEAD="$FE_SHA")" = "$BOTH" ]'
check "null SHA (new ref): both"                  '[ "$(gscopes BASE=0000000000000000000000000000000000000000 HEAD="$FE_SHA")" = "$BOTH" ]'
check "unreachable base (force push): both"       '[ "$(gscopes BASE=1111111111111111111111111111111111111111 HEAD="$FE_SHA")" = "$BOTH" ]'
check "base == head (empty diff): both"           '[ "$(gscopes BASE="$FE_SHA" HEAD="$FE_SHA")" = "$BOTH" ]'
check "tag: both"                                 '[ "$(gscopes GITHUB_REF_TYPE=tag BASE="$BASE_SHA" HEAD="$FE_SHA")" = "$BOTH" ]'
check "schedule: both"                            '[ "$(gscopes GITHUB_EVENT_NAME=schedule BASE="$BASE_SHA" HEAD="$FE_SHA")" = "$BOTH" ]'
check "workflow_dispatch: both"                   '[ "$(gscopes GITHUB_EVENT_NAME=workflow_dispatch BASE="$BASE_SHA" HEAD="$FE_SHA")" = "$BOTH" ]'
check "pull_request range: filtered like a push"  '[ "$(gscopes GITHUB_EVENT_NAME=pull_request BASE="$BASE_SHA" HEAD="$FE_SHA")" = "$FE" ]'
check "exit code always 0"                        '( cd "$R" && BASE=1111111111111111111111111111111111111111 bash "$SCRIPT" >/dev/null 2>&1 )'

echo "3) ci.yml wiring"
CI="$HERE/../../.github/workflows/ci.yml"
check "changes job calls this script"             'grep -q "bash scripts/ci/changed-scopes.sh" "$CI"'
check "backend/frontend skip only on explicit false" '[ "$(grep -c "outputs\.\(backend\|frontend\) != .false." "$CI")" -eq 2 ]'
check "weekly full run scheduled"                 'grep -q "cron: \"0 4 \* \* 1\"" "$CI"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
