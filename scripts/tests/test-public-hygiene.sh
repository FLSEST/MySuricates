#!/usr/bin/env bash
# Tests for scripts/ci/public-hygiene.sh and scripts/git-hooks/pre-commit (TODOs 5dbabe7c, 3fcc2cfe).
# Run: bash scripts/tests/test-public-hygiene.sh   (exit 0 = all checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
# The fallback locations of the private list (hygiene_private_list_path) lie outside
# the repo. Without this isolation the gate would find the machine's real list.
# GITHUB_ACTIONS has to be included: annotate() writes '::error file=x,line=1::' there
# instead of 'x:1 — ', and assertions on 'file:line' then fail only in CI
# (run 35910938266). As in test-pre-push-checks.sh.
export XDG_CONFIG_HOME="$TMP/xdg"
unset HYGIENE_PRIVATE_LIST HYGIENE_PRIVATE_TERMS GITHUB_ACTIONS

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Assemble the forbidden literal at runtime so that this file itself stays clean.
FORBIDDEN="$(printf '%s%s' '/home/' 'flo')"
# noreply form, because the commit-msg hook (pre-push gate) rejects private addresses.
GIT="git -c user.name=t -c user.email=1+t@users.noreply.github.com"

# Fresh repo with the gate files, hook via core.hooksPath (like scripts/install-hooks.sh).
make_repo() {  # $1 = path
  git init -q "$1"
  mkdir -p "$1/scripts"
  cp -r "$REPO/scripts/ci" "$REPO/scripts/lib" "$REPO/scripts/git-hooks" "$1/scripts/"
  # The private exclusion list is untracked and exists only locally — it must not
  # wander into the test repos, otherwise the result depends on the machine.
  rm -f "$1/scripts/ci/hygiene-private.txt" "$1/scripts/ci/hygiene-private-terms.txt"
  ( cd "$1" && $GIT add . && $GIT commit -q -m init && git config core.hooksPath scripts/git-hooks )
}

echo "1) Hook blockiert im Haupt-Checkout"
make_repo "$TMP/main"
( cd "$TMP/main" && printf 'x=%s/x\n' "$FORBIDDEN" > bad.txt && git add bad.txt )
OUT=$(cd "$TMP/main" && $GIT commit -q -m bad 2>&1); RC=$?
check "commit abgelehnt (exit != 0)"        '[ "$RC" -ne 0 ]'
check "Gate meldet FAILED"                  'grep -q "Public-Hygiene-Gate FAILED" <<<"$OUT"'
check "keine fehlende Funktion"             '! grep -qi "not found\|nicht gefunden" <<<"$OUT"'

echo "2) Hook blockiert im Worktree (git exportiert dort GIT_DIR)"
( cd "$TMP/main" && git reset -q && rm -f bad.txt && git worktree add -q "$TMP/wt" -b wt-test )
( cd "$TMP/wt" && printf 'x=%s/x\n' "$FORBIDDEN" > bad.txt && git add bad.txt )
OUT=$(cd "$TMP/wt" && $GIT commit -q -m bad 2>&1); RC=$?
check "commit im Worktree abgelehnt"        '[ "$RC" -ne 0 ]'
check "Gate meldet FAILED im Worktree"      'grep -q "Public-Hygiene-Gate FAILED" <<<"$OUT"'
check "keine fehlende Funktion im Worktree" '! grep -qi "not found\|nicht gefunden" <<<"$OUT"'

echo "3) Gate mit exportiertem GIT_DIR und cwd scripts/ci findet die Regeln"
OUT=$(cd "$TMP/wt/scripts/ci" && GIT_DIR="$(git rev-parse --absolute-git-dir)" bash public-hygiene.sh --staged 2>&1); RC=$?
check "Verstoss erkannt (exit 1)"           '[ "$RC" -eq 1 ]'
check "Regeldatei gefunden"                 '! grep -q "hygiene-rules.sh" <<<"$OUT"'

echo "4) Fehlende Regeldatei ist ein harter Fehler, kein stilles OK"
rm "$TMP/wt/scripts/lib/hygiene-rules.sh"
OUT=$(cd "$TMP/wt" && bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "exit 2"                              '[ "$RC" -eq 2 ]'
check "meldet fehlende Regeldatei"          'grep -q "hygiene-rules.sh" <<<"$OUT"'
check "meldet kein OK"                      '! grep -q "Public-Hygiene-Gate OK" <<<"$OUT"'

echo "5) Symlink mit absolutem Ziel wird beim Commit abgelehnt"
make_repo "$TMP/links"
( cd "$TMP/links" && ln -s /etc/hostname bad-link && git add bad-link )
OUT=$(cd "$TMP/links" && $GIT commit -q -m link 2>&1); RC=$?
check "commit mit absolutem Symlink abgelehnt" '[ "$RC" -ne 0 ]'
check "Gate nennt den Symlink"                 'grep -q "bad-link.*Symlink zeigt aus dem Repository hinaus" <<<"$OUT"'

echo "6) Relativer Symlink innerhalb des Repos ist erlaubt"
( cd "$TMP/links" && git reset -q && rm -f bad-link && mkdir -p sub target-dir && ln -s ../target-dir sub/ok-link && git add sub/ok-link )
OUT=$(cd "$TMP/links" && $GIT commit -q -m oklink 2>&1); RC=$?
check "commit mit relativem Symlink erlaubt"   '[ "$RC" -eq 0 ]'

echo "7) Relativer Symlink, der aus dem Repo hinausfuehrt, faellt im Bestand auf"
( cd "$TMP/links" && ln -s ../../outside escape-link && git add escape-link && $GIT commit -q --no-verify -m escape )
OUT=$(cd "$TMP/links" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "--tracked meldet Verstoss (exit 1)"     '[ "$RC" -eq 1 ]'
check "Gate nennt escape-link"                 'grep -q "escape-link" <<<"$OUT"'
check "sub/ok-link bleibt unbeanstandet"       '! grep -q "ok-link" <<<"$OUT"'

echo "8) docs/plans ist im echten Repository untrackt und ignoriert (TODO 6e9e00e9)"
TRACKED_PLANS="$(cd "$REPO" && git ls-files docs/plans)"
check "keine getrackten Dateien unter docs/plans" '[ -z "$TRACKED_PLANS" ]'
check ".gitignore ignoriert docs/plans/"          'grep -qxF "docs/plans/" "$REPO/.gitignore"'

echo "9) entrypoint.sh hat keinen persoenlichen APP_USER-Default (TODO 0cf6bd37)"
ENTRYPOINT_DEFAULT="$(printf '%s' 'APP_USER="${APP_USER:-flo}"')"
check "kein 'flo' als APP_USER-Default" \
  '! grep -qF "$ENTRYPOINT_DEFAULT" "$REPO/ai-automation-backend/docker/entrypoint.sh"'

echo "10) SECURITY.md fehlt, enthaelt eine Mailadresse, oder nennt keinen Meldeweg"
make_repo "$TMP/sec"
OUT=$(cd "$TMP/sec" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "fehlendes SECURITY.md erkannt (exit 1)" '[ "$RC" -eq 1 ]'
check "Gate nennt SECURITY.md"                 'grep -q "SECURITY.md" <<<"$OUT"'

( cd "$TMP/sec" && printf 'Contact: security@example.com\n\nSee Security Advisories.\n' > SECURITY.md && $GIT add SECURITY.md && $GIT commit -q -m sec )
OUT=$(cd "$TMP/sec" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Mailadresse in SECURITY.md erkannt (exit 1)" '[ "$RC" -eq 1 ]'
check "Gate nennt die Mailadresse"                  'grep -qi "Mail" <<<"$OUT"'

( cd "$TMP/sec" && printf 'No contact info, no reporting path here.\n' > SECURITY.md && $GIT add SECURITY.md && $GIT commit -q -m sec2 )
OUT=$(cd "$TMP/sec" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "fehlender Meldeweg erkannt (exit 1)" '[ "$RC" -eq 1 ]'

( cd "$TMP/sec" && printf 'Report vulnerabilities via Private vulnerability reporting on GitHub.\n' > SECURITY.md && $GIT add SECURITY.md && $GIT commit -q -m sec3 )
OUT=$(cd "$TMP/sec" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "korrektes SECURITY.md akzeptiert (exit 0)" '[ "$RC" -eq 0 ]'

echo "11) --tracked prueft den getrackten Bestand, nicht die Arbeitskopie"
make_repo "$TMP/sec2"
( cd "$TMP/sec2" && printf 'Report vulnerabilities via Private vulnerability reporting on GitHub.\n' > SECURITY.md )
OUT=$(cd "$TMP/sec2" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "nur auf Platte liegende SECURITY.md wird nicht akzeptiert (exit 1)" '[ "$RC" -eq 1 ]'
check "Gate nennt SECURITY.md als nicht getrackt"                         'grep -qi "SECURITY.md" <<<"$OUT"'

( cd "$TMP/sec2" && $GIT add SECURITY.md && $GIT commit -q -m sec )
OUT=$(cd "$TMP/sec2" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "einmal committete SECURITY.md wird akzeptiert (exit 0)" '[ "$RC" -eq 0 ]'

echo "12) Pool-Kuratierungsregeln (TODO 523702f4): scope, projectId, Beschreibung/Tags, Blocktypen"
POOL="$TMP/pool"
mkdir -p "$POOL"
write_pool() { cat > "$POOL/$1.json"; }

write_pool ok-portable <<'JSON'
{"name": {"de": "Portabel", "en": "Portable"},
 "description": {"de": "Prueft ein Projekt.", "en": "Checks a project."},
 "scope": "portable", "tags": ["quality"],
 "defaultContext": {"project_path": ""},
 "blocks": [{"type": "shell", "config": {"command": "cd {{ project_path }} && ls"}},
            {"type": "simple", "config": {"goal": "Fasse zusammen."}}]}
JSON

write_pool ok-repo <<'JSON'
{"name": {"de": "Repo-gebunden", "en": "Repo bound"},
 "description": {"de": "Prueft dieses Repository.", "en": "Checks this repository."},
 "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "bash scripts/ci/public-hygiene.sh && ./mvnw -q test"}}]}
JSON

write_pool ok-external <<'JSON'
{"name": {"de": "Extern", "en": "External"},
 "description": {"de": "Braucht die factory-CLI im Projekt.", "en": "Needs the factory CLI in the project."},
 "scope": "external", "tags": ["saas-factory"],
 "blocks": [{"type": "shell", "config": {"command": "./saas-factory/tools/factory test"}}]}
JSON

write_pool missing-scope <<'JSON'
{"name": {"de": "Ohne", "en": "Without"},
 "description": {"de": "Text.", "en": "Text."}, "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool bad-scope <<'JSON'
{"name": {"de": "Falsch", "en": "Wrong"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "private", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool has-projectid <<'JSON'
{"name": {"de": "Gebunden", "en": "Bound"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "projectId": "proj-42",
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool ctx-projectid <<'JSON'
{"name": {"de": "Kontext", "en": "Context"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "defaultContext": {"project_id": "e1b2c3d4"},
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

# Spelling that does NOT appear verbatim in the generated key list: the export
# (PoolExportSanitizer) normalizes before the comparison, the gate has to do the same (TODO 96640e6f).
write_pool ctx-kebab-projectid <<'JSON'
{"name": {"de": "Kebab", "en": "Kebab"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "defaultContext": {"project-id": "e1b2c3d4"},
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool ctx-placeholder <<'JSON'
{"name": {"de": "Platzhalter", "en": "Placeholder"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "defaultContext": {"project_id": "{{ project_id }}"},
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool empty-tags <<'JSON'
{"name": {"de": "Ohne Tags", "en": "No tags"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": [],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool empty-desc <<'JSON'
{"name": {"de": "Ohne Text", "en": "No text"},
 "description": {"de": "", "en": ""}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool string-desc <<'JSON'
{"name": "Nur ein String",
 "description": "Nur ein String statt de/en.", "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON

write_pool unknown-blocktype <<'JSON'
{"name": {"de": "Unbekannt", "en": "Unknown"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "magic", "config": {}}]}
JSON

# A block without "config" is valid JSON and runs in the backend (config is optional).
write_pool blocktype-ohne-config <<'JSON'
{"name": {"de": "Ohne Config", "en": "No config"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "alchemy"}]}
JSON

# The only real nesting places are switch -> cases[].blocks and default
# (SwitchBlockExecutor.java:37-38,59,67; ExecutionMode.java:68-76).
write_pool nested-blocktype <<'JSON'
{"name": {"de": "Verschachtelt", "en": "Nested"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "switch", "config": {"expression": "{{ x }}",
   "cases": [{"when": "a", "blocks": [{"type": "sorcery", "config": {}},
                                      {"type": "wizardry"}]}]}}]}
JSON

write_pool switch-default-blocktype <<'JSON'
{"name": {"de": "Default-Zweig", "en": "Default branch"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "switch", "config": {"expression": "{{ x }}",
   "cases": [{"when": "a", "blocks": [{"type": "shell", "config": {"command": "ls"}}]}],
   "default": [{"type": "secretblock"}]}}]}
JSON

write_pool portable-repo-dep <<'JSON'
{"name": {"de": "Portabel?", "en": "Portable?"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "bash scripts/ci/public-hygiene.sh"}}]}
JSON

# Not every producer writes UUIDs in lowercase — the sanitizer in the backend checks both spellings.
write_pool uppercase-uuid <<'JSON'
{"name": {"de": "Grossbuchstaben", "en": "Uppercase"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "echo 3F2504E0-4F89-11D3-9A0C-0305E82C3301"}}]}
JSON

write_pool dot-home-path <<'JSON'
{"name": {"de": "Punktverzeichnis", "en": "Dot directory"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls ~/.mysuricates/quarantine"}}]}
JSON

# --- Criterion "calls the own REST API or reads credentials" (TODO 1ccdc6d5) ---
write_pool self-api-curl <<'JSON'
{"name": {"de": "Self-Call", "en": "Self call"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s http://localhost:9090/api/v1/todos"}}]}
JSON

write_pool self-api-prompt <<'JSON'
{"name": {"de": "Prompt-Call", "en": "Prompt call"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "agentic", "config": {"goal": "Lege ein TODO an: POST /api/v1/todos mit dem Report."}}]}
JSON

write_pool api-key-header <<'JSON'
{"name": {"de": "Key", "en": "Key"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "simple", "config": {"command": "Setze den X-API-Key Header bei allen Aufrufen."}}]}
JSON

write_pool app-settings-key <<'JSON'
{"name": {"de": "Store", "en": "Store"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "simple", "config": {"command": "Den API-Key aus app_settings holen."}}]}
JSON

write_pool key-file-read <<'JSON'
{"name": {"de": "Schlüsseldatei", "en": "Key file"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "KEY=$(cat ~/.mysuricates/api-key); echo ${#KEY}"}}]}
JSON

write_pool key-file-data-home-fn <<'JSON'
{"name": {"de": "Datenordner-Funktion", "en": "Data home function"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "KEY=$(cat \"$(data_home \"$HOME\")/api-key\")"}}]}
JSON

write_pool key-file-data-home <<'JSON'
{"name": {"de": "Datenordner", "en": "Data home"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "wc -c \"${AUTOMATION_DATA_HOME}/api-key\""}}]}
JSON

write_pool db-sqlite <<'JSON'
{"name": {"de": "DB", "en": "DB"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "sqlite3 data/automation.db 'SELECT count(*) FROM todos'"}}]}
JSON

# Allowed: looking at a file size is no database access (mysuricates-performance-audit).
write_pool db-filesize-ok <<'JSON'
{"name": {"de": "Groesse", "en": "Size"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "stat -c %s ai-automation-backend/data/automation.db"}}]}
JSON

# Allowed: the auth-free health probe.
write_pool api-health-ok <<'JSON'
{"name": {"de": "Sonde", "en": "Probe"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s -o /dev/null -w '%{time_total}' http://localhost:9090/api/v1/system/health"}}]}
JSON

# Allowed: the three heap numbers without key (c2cda5ed) — the performance audit reads them there.
write_pool api-jvm-memory-ok <<'JSON'
{"name": {"de": "Heap", "en": "Heap"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s -m 5 http://localhost:9090/api/v1/system/jvm-memory"}}]}
JSON

# Not allowed: the neighbor under /system requires the key — the exception applies only to the heap path.
write_pool self-api-request-metrics <<'JSON'
{"name": {"de": "Kosten", "en": "Costs"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s http://localhost:9090/api/v1/system/request-metrics"}}]}
JSON

# Allowed: the prefix as a search pattern in a list, without a call (mocking-gap-audit).
write_pool api-prefix-as-marker-ok <<'JSON'
{"name": {"de": "Marker", "en": "Marker"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "python3 -c \"MARKERS = ['resttemplate', 'webclient', '/api/v1/', 'wiremock']\""}}]}
JSON

# --- In shell code every endpoint counts except the key-free probes, no matter how far the
# call is away (TODO 28007626): the path in the loop variable lay more than 200 characters before curl. ---
PAD="$(printf '%0240d' 0 | tr 0 x)"
write_pool self-api-loopvar <<JSON
{"name": {"de": "Schleife", "en": "Loop"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "for E in /api/v1/todos /api/v1/workflows; do\n  # $PAD\n  curl -s http://localhost:9090\$E\ndone"}}]}
JSON

write_pool self-api-variable <<JSON
{"name": {"de": "Variable", "en": "Variable"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ROUTE=/api/v1/executions\n# $PAD\nwget -qO- \"http://localhost:9090\$ROUTE\""}}]}
JSON

# Shell code also lives in blocks with execution_mode shell (here in the switch case) and in the
# items_command of foreach.
write_pool self-api-nested-shell <<'JSON'
{"name": {"de": "Verschachtelt", "en": "Nested"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "switch", "config": {"expression": "{{ x }}", "execution_mode": "shell",
   "cases": [{"when": "a", "blocks": [{"type": "simple",
     "config": {"execution_mode": "shell", "command": "echo /api/v1/schedules"}}]}]}}]}
JSON

write_pool self-api-items-command <<'JSON'
{"name": {"de": "Liste", "en": "List"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "foreach", "config": {"items_source": "command", "items_command": "echo /api/v1/projects",
   "command": "Fasse {{ item }} zusammen."}}]}
JSON

# The detour via a probe is not a key-free path: comparison is exact, as in the ApiKeyAuthFilter.
write_pool self-api-probe-detour <<'JSON'
{"name": {"de": "Umweg", "en": "Detour"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s http://localhost:9090/api/v1/system/jvm-memory/../request-metrics"}}]}
JSON

# Counter-samples: the key-free probes in a loop (as in the performance audit) and a path in the
# prompt without a call stay allowed — the new rule applies to shell code.
write_pool api-keyfree-loop-ok <<JSON
{"name": {"de": "Sonden", "en": "Probes"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "for E in /actuator/health /api/v1/system/jvm-memory; do\n  # $PAD\n  curl -s -m 5 http://localhost:9090\$E\ndone"}}]}
JSON

# A sentence period does not belong to the path: the probe at the end of the sentence stays allowed, the key endpoint at the
# end of the sentence is reported without the period.
write_pool api-probe-sentence-ok <<'JSON'
{"name": {"de": "Satz", "en": "Sentence"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "# Heap aus /api/v1/system/jvm-memory.\ncurl -s -m 5 http://localhost:9090/api/v1/system/jvm-memory"}}]}
JSON

write_pool self-api-sentence <<'JSON'
{"name": {"de": "Satz", "en": "Sentence"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "# Die Kosten stehen unter /api/v1/todos.\necho ok"}}]}
JSON

write_pool api-path-in-prompt-ok <<'JSON'
{"name": {"de": "Prompt", "en": "Prompt"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "simple", "config": {"command": "Die Route /api/v1/todos war laut Faktenblatt die langsamste."}}]}
JSON

# --- Composed paths (TODO 2a0c9420): the endpoint never stands in one piece in the shell code, but
# arises only from prefix and variable or from a base URL in a variable. ---
write_pool self-api-composed-loop <<'JSON'
{"name": {"de": "Zusammengesetzt", "en": "Composed"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "for E in todos executions; do\n  curl -s \"http://localhost:9090/api/v1/$E\"\ndone"}}]}
JSON

write_pool self-api-composed-braces <<'JSON'
{"name": {"de": "Klammern", "en": "Braces"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s {{ backend_url }}/api/v1/\"${ROUTE}\""}}]}
JSON

write_pool self-api-base-var <<'JSON'
{"name": {"de": "Basis", "en": "Base"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "API=http://localhost:9090/api/v1\ncurl -s \"$API/todos\""}}]}
JSON

write_pool self-api-base-var-python <<'JSON'
{"name": {"de": "Basis Python", "en": "Base Python"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "python3 - <<'PY'\nbase = 'http://localhost:9090/api/v1/'\nPY"}}]}
JSON

# Counter-sample: the prefix in an output text is no call (mysuricates-performance-audit).
write_pool api-prefix-in-echo-ok <<'JSON'
{"name": {"de": "Ausgabe", "en": "Output"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "echo '# Request-Kosten der /api/v1-Routen'"}}]}
JSON

# --- Actuator (TODO 7721d8cb): except /actuator/health everything below requires the key — /actuator/metrics
# silently returned 403 to system-performance-analysis and SECURITY warnings per run. ---
write_pool self-api-actuator-metrics <<'JSON'
{"name": {"de": "Metriken", "en": "Metrics"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "for metric in jvm.memory.used process.cpu.usage; do\n  curl -s \"{{ backend_url }}/actuator/metrics/$metric\"\ndone"}}]}
JSON

write_pool self-api-actuator-env <<'JSON'
{"name": {"de": "Umgebung", "en": "Environment"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s http://localhost:9090/actuator/env"}}]}
JSON

write_pool self-api-actuator-prompt <<'JSON'
{"name": {"de": "Prompt", "en": "Prompt"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "agentic", "config": {"goal": "Hole per curl GET /actuator/metrics/jvm.memory.used und bewerte den Heap."}}]}
JSON

write_pool api-actuator-health-ok <<'JSON'
{"name": {"de": "Health", "en": "Health"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "curl -s -m 5 http://localhost:9090/actuator/health"}}]}
JSON

# --- Base URL without path or only with /api in a variable, the endpoint arises at the call — the same
# forms as BaseUrlVariables in the analyzer (parity). The probes stay allowed. ---
write_pool self-api-short-base-actuator <<'JSON'
{"name": {"de": "Basis kurz", "en": "Short base"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "BASE=http://localhost:9090; curl $BASE/actuator/metrics"}}]}
JSON

write_pool self-api-short-base-api <<'JSON'
{"name": {"de": "Basis /api", "en": "Base /api"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "API=http://localhost:9090/api\ncurl -s \"$API/v1/todos\""}}]}
JSON

write_pool self-api-short-base-fstring <<'JSON'
{"name": {"de": "Basis f-String", "en": "Base f-string"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "python3 - <<'PY'\napi = 'http://localhost:9090/api'\nrequests.get(f'{api}/v1/executions')\nPY"}}]}
JSON

write_pool api-short-base-probes-ok <<'JSON'
{"name": {"de": "Basis Sonden", "en": "Base probes"},
 "description": {"de": "Text.", "en": "Text."}, "scope": "repo", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "HOST=http://localhost:9090; curl -sf \"$HOST/actuator/health\"\nAPI=http://localhost:9090/api\ncurl -s \"$API/v1/system/jvm-memory\"\necho \"$API\""}}]}
JSON

OUT=$(bash "$REPO/scripts/ci/pool-neutrality.sh" --all "$POOL" 2>&1); RC=$?
check "Befunde gemeldet (exit 1)"              '[ "$RC" -eq 1 ]'
check "ok-portable unbeanstandet"              '! grep -q "ok-portable" <<<"$OUT"'
check "ok-repo darf scripts/ und mvnw nutzen"  '! grep -q "ok-repo" <<<"$OUT"'
check "scope external ist erlaubt"             '! grep -q "ok-external" <<<"$OUT"'
check "fehlendes scope erkannt"                'grep -q "missing-scope.*SCOPE" <<<"$OUT"'
check "ungueltiges scope erkannt"              'grep -q "bad-scope.*SCOPE" <<<"$OUT"'
check "projectId im Eintrag erkannt"           'grep -q "has-projectid.*PROJECT-ID" <<<"$OUT"'
check "project_id im defaultContext erkannt"   'grep -q "ctx-projectid.*PROJECT-ID" <<<"$OUT"'
check "project-id (kebab) ebenso erkannt"      'grep -q "ctx-kebab-projectid.*PROJECT-ID" <<<"$OUT"'
check "Platzhalter-project_id bleibt erlaubt"  '! grep -q "ctx-placeholder" <<<"$OUT"'
check "leere tags erkannt"                     'grep -q "empty-tags.*TAGS" <<<"$OUT"'
check "leere description erkannt"              'grep -q "empty-desc.*DESCRIPTION" <<<"$OUT"'
check "description als String erkannt"         'grep -q "string-desc.*DESCRIPTION" <<<"$OUT"'
check "unbekannter Blocktyp erkannt"           'grep -q "unknown-blocktype.*BLOCK-TYPE" <<<"$OUT"'
check "unbekannter Blocktyp in cases[].blocks" 'grep -q "nested-blocktype.*BLOCK-TYPE.*sorcery" <<<"$OUT"'
check "cases[].blocks und ohne config"         'grep -q "nested-blocktype.*BLOCK-TYPE.*wizardry" <<<"$OUT"'
check "switch-default ohne config erkannt"     'grep -q "switch-default-blocktype.*BLOCK-TYPE.*secretblock" <<<"$OUT"'
check "switch selbst bleibt unbeanstandet"     '! grep -q "BLOCK-TYPE.*switch$" <<<"$OUT"'
check "unbekannter Blocktyp ohne config"       'grep -q "blocktype-ohne-config.*BLOCK-TYPE.*alchemy" <<<"$OUT"'
check "portable mit scripts/-Pfad erkannt"     'grep -q "portable-repo-dep.*REPO-DEP" <<<"$OUT"'
check "UUID in Grossbuchstaben erkannt"        'grep -q "uppercase-uuid.*UUID" <<<"$OUT"'
check "Tilde-Pfad auf Punktverzeichnis erkannt" 'grep -q "dot-home-path.*PATH-HOME" <<<"$OUT"'
check "curl auf die eigene API erkannt"        'grep -q "self-api-curl.*SELF-API" <<<"$OUT"'
check "API-Aufruf im Prompt erkannt"           'grep -q "self-api-prompt.*SELF-API" <<<"$OUT"'
check "X-API-Key erkannt"                      'grep -q "api-key-header.*CREDENTIAL" <<<"$OUT"'
check "app_settings erkannt"                   'grep -q "app-settings-key.*CREDENTIAL" <<<"$OUT"'
check "Schlüsseldatei ohne curl erkannt"       'grep -q "key-file-read.*CREDENTIAL" <<<"$OUT"'
check "Schlüsseldatei im Datenordner erkannt"  'grep -q "key-file-data-home\.json.*CREDENTIAL" <<<"$OUT"'
check "Schlüsseldatei über data_home erkannt"  'grep -q "key-file-data-home-fn.*CREDENTIAL" <<<"$OUT"'
check "sqlite3 auf die DB erkannt"             'grep -q "db-sqlite.*DB-DIRECT" <<<"$OUT"'
check "Dateigroesse der DB bleibt erlaubt"     '! grep -q "db-filesize-ok" <<<"$OUT"'
check "auth-freie Health-Sonde bleibt erlaubt" '! grep -q "api-health-ok" <<<"$OUT"'
check "Heap-Zahlen ohne Key bleiben erlaubt"   '! grep -q "api-jvm-memory-ok" <<<"$OUT"'
check "Request-Kosten mit Key erkannt"         'grep -q "self-api-request-metrics.*SELF-API" <<<"$OUT"'
check "API-Praefix als Suchmuster erlaubt"     '! grep -q "api-prefix-as-marker-ok" <<<"$OUT"'
check "Pfad in der Schleifenvariable, curl weit weg: erkannt" \
  'grep -q "self-api-loopvar.*SELF-API.*/api/v1/todos.*im Shell-Code" <<<"$OUT"'
check "Pfad in einer Variable, wget weit weg: erkannt" \
  'grep -q "self-api-variable.*SELF-API.*/api/v1/executions" <<<"$OUT"'
check "Shell-Code im switch-Case erkannt"        'grep -q "self-api-nested-shell.*SELF-API.*/api/v1/schedules" <<<"$OUT"'
check "items_command von foreach erkannt"        'grep -q "self-api-items-command.*SELF-API.*/api/v1/projects" <<<"$OUT"'
check "Umweg ueber eine Sonde erkannt"           'grep -q "self-api-probe-detour.*SELF-API" <<<"$OUT"'
check "Sonden in der Schleife bleiben erlaubt"   '! grep -q "api-keyfree-loop-ok" <<<"$OUT"'
check "Pfad im Prompt ohne Aufruf bleibt erlaubt" '! grep -q "api-path-in-prompt-ok" <<<"$OUT"'
check "Sonde mit Satzpunkt bleibt erlaubt"       '! grep -q "api-probe-sentence-ok" <<<"$OUT"'
check "Endpunkt mit Satzpunkt: gemeldet ohne den Punkt" \
  'grep -q "self-api-sentence.*SELF-API.*'"'"'/api/v1/todos'"'"' (im Shell-Code)" <<<"$OUT"'
check "Praefix plus Schleifenvariable erkannt (2a0c9420)" \
  'grep -q "self-api-composed-loop.*SELF-API.*/api/v1/\$E" <<<"$OUT"'
check "Praefix plus \${VAR} in Anfuehrungszeichen erkannt" 'grep -q "self-api-composed-braces.*SELF-API" <<<"$OUT"'
check "Basis-URL in einer Variable erkannt"      'grep -q "self-api-base-var\\.json.*SELF-API" <<<"$OUT"'
check "Basis-URL in Python-Variable erkannt"     'grep -q "self-api-base-var-python.*SELF-API" <<<"$OUT"'
check "Praefix im Ausgabetext bleibt erlaubt"    '! grep -q "api-prefix-in-echo-ok" <<<"$OUT"'
check "Actuator-Metriken per Schleife erkannt (7721d8cb)" \
  'grep -q "self-api-actuator-metrics.*SELF-API.*/actuator/metrics/" <<<"$OUT"'
check "Actuator-Endpunkt mit Key erkannt"        'grep -q "self-api-actuator-env.*SELF-API.*/actuator/env" <<<"$OUT"'
check "Actuator-Aufruf im Prompt erkannt"        'grep -q "self-api-actuator-prompt.*SELF-API" <<<"$OUT"'
check "/actuator/health bleibt erlaubt"          '! grep -q "api-actuator-health-ok" <<<"$OUT"'
check "Basis ohne Pfad + /actuator/metrics erkannt" 'grep -q "self-api-short-base-actuator.*SELF-API.*/actuator/metrics" <<<"$OUT"'
check "Basis mit /api + /v1/todos erkannt"       'grep -q "self-api-short-base-api.*SELF-API.*/api/v1/todos" <<<"$OUT"'
check "Basis mit /api im f-String erkannt"       'grep -q "self-api-short-base-fstring.*SELF-API.*/api/v1/executions" <<<"$OUT"'
check "Sonden ueber eine Basis-Variable erlaubt" '! grep -q "api-short-base-probes-ok" <<<"$OUT"'

echo "13) Blocktyp-Drift: die erzeugte Gate-Liste deckt sich mit der Registry im Backend"
# Since TODO bdc784e1 HYGIENE_POOL_BLOCK_TYPES is no longer maintained by hand, but
# is generated by BlockTypesGateListTest from BlockTypesIndexSection.BLOCK_TYPES.
# This check is thus the drift test without Maven: red if one side was changed
# without regenerating the file.
REGISTRY="$REPO/ai-automation-backend/src/main/java/com/automation/service/assistant/context/BlockTypesIndexSection.java"
GENERATED_TYPES="$REPO/scripts/lib/block-types.generated.sh"
JAVA_TYPES="$(grep -oE 'new BlockTypeEntry\("[a-z-]+"' "$REGISTRY" | sed 's/.*("//;s/"//' | sort | tr '\n' ' ')"
# shellcheck source=../lib/hygiene-rules.sh
. "$REPO/scripts/lib/hygiene-rules.sh"
GATE_TYPES="$(printf '%s\n' "${HYGIENE_POOL_BLOCK_TYPES[@]:-}" | sort | tr '\n' ' ')"
check "erzeugte Blocktyp-Datei vorhanden"      '[ -f "$GENERATED_TYPES" ]'
check "Datei als erzeugt gekennzeichnet" \
  'grep -q "generiert von BlockTypesGateListTest" "$GENERATED_TYPES"'
check "Liste kommt aus der erzeugten Datei, nicht aus hygiene-rules.sh" \
  '! grep -qE "^HYGIENE_POOL_BLOCK_TYPES=\(" "$REPO/scripts/lib/hygiene-rules.sh"'
check "Registry-Liste nicht leer"              '[ -n "$JAVA_TYPES" ]'
check "Gate-Liste deckt sich mit der Registry" '[ "$JAVA_TYPES" = "$GATE_TYPES" ]'

echo "14) Die getrackten Pool-Definitionen erfuellen die Kuratierungsregeln"
OUT=$(cd "$REPO" && bash scripts/ci/pool-neutrality.sh 2>&1); RC=$?
check "Pool-Neutralitaet im Bestand OK (exit 0)" '[ "$RC" -eq 0 ]'
[ "$RC" -eq 0 ] || printf '%s\n' "$OUT" | sed 's/^/     /'

echo "15) docs/analysis.md (DE/EN) ist im echten Repository untrackt und ignoriert (TODO 72900609)"
TRACKED_ANALYSIS="$(cd "$REPO" && git ls-files docs/analysis.md docs/en/analysis.md)"
check "keine getrackten analysis.md-Dateien"      '[ -z "$TRACKED_ANALYSIS" ]'
check ".gitignore ignoriert docs/analysis.md"     'grep -qxF "docs/analysis.md" "$REPO/.gitignore"'
check ".gitignore ignoriert docs/en/analysis.md"  'grep -qxF "docs/en/analysis.md" "$REPO/.gitignore"'

echo "16) docs/specs ist im echten Repository untrackt und ignoriert (TODO 089473cd)"
TRACKED_SPECS="$(cd "$REPO" && git ls-files docs/specs)"
check "keine getrackten Dateien unter docs/specs" '[ -z "$TRACKED_SPECS" ]'
check ".gitignore ignoriert docs/specs/"          'grep -qxF "docs/specs/" "$REPO/.gitignore"'

echo "17) Der [auto-generated]-Stempel ist aus dem getrackten Doku-Bestand entfernt (TODO 994af971)"
STAMPED="$(cd "$REPO" && git grep -l -F "[auto-generated]" -- '*.md' 2>/dev/null || true)"
check "kein getracktes .md mit [auto-generated]-Stempel" '[ -z "$STAMPED" ]'

echo "18) Hygiene-Gate lehnt einen neu hinzugefuegten [auto-generated]-Stempel in .md ab (TODO 994af971)"
make_repo "$TMP/stamp"
( cd "$TMP/stamp" && mkdir -p docs && printf '> *Zuletzt aktualisiert: [auto-generated]*\n\n# Test\n' > docs/sample.md && $GIT add docs/sample.md )
OUT=$(cd "$TMP/stamp" && $GIT commit -q -m stamp 2>&1); RC=$?
check "commit mit [auto-generated]-Stempel abgelehnt" '[ "$RC" -ne 0 ]'
check "Gate nennt den Stempel"                        'grep -qi "auto-generated" <<<"$OUT"'
( cd "$TMP/stamp" && git reset -q )
OUT=$(cd "$TMP/stamp" && printf '# Anderer Text ohne Stempel\n' > docs/sample.md && $GIT add docs/sample.md && $GIT commit -q -m ok 2>&1); RC=$?
check "commit ohne Stempel bleibt erlaubt (exit 0)" '[ "$RC" -eq 0 ]'

echo "19) ai-automation-frontend/cypress/TODO-TESTS.md ist untrackt und ignoriert (TODO af7e5d22)"
TRACKED_CYPRESS_NOTE="$(cd "$REPO" && git ls-files ai-automation-frontend/cypress/TODO-TESTS.md)"
check "TODO-TESTS.md nicht getrackt" '[ -z "$TRACKED_CYPRESS_NOTE" ]'
check ".gitignore ignoriert TODO-TESTS.md" \
  'grep -qxF "ai-automation-frontend/cypress/TODO-TESTS.md" "$REPO/.gitignore"'

echo "20) Die in der Doku genannten Zahlen decken sich mit dem Code (TODO 3df2df73)"
# Runs in ~0.4 s and needs no network — hence here and not only as its own test.
OUT=$(cd "$REPO" && bash scripts/public-readiness/doc-facts-check.sh 2>&1); RC=$?
check "keine Zahlen-Drift in docs/ und README.de.md (exit 0)" '[ "$RC" -eq 0 ]'
[ "$RC" -eq 0 ] || printf '%s\n' "$OUT" | sed 's/^/     /'

echo "21) check_gitignore_leaks nimmt generische Build-Namen aus (TODO 819b387e)"
make_repo "$TMP/leak"
( cd "$TMP/leak" && printf 'ai-automation-frontend/node_modules/\nworkflow-pool/privat.json\n' >> .gitignore && $GIT add .gitignore && $GIT commit -q -m gitignore )
( cd "$TMP/leak" && printf 'ai-automation-frontend/node_modules\nworkflow-pool/privat.json\n' > .git/info/exclude )
OUT=$(cd "$TMP/leak" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "generischer Name node_modules bleibt unbeanstandet" '! grep -q "node_modules" <<<"$OUT"'
check "workflow-pool-Eintrag weiterhin als Leck erkannt"    'grep -q "privat.json" <<<"$OUT"'
check "Gate meldet weiterhin einen Verstoss (exit 1)"       '[ "$RC" -eq 1 ]'

echo "22) Abbruch der Pool-Neutralitaet schlaegt das Gate (kein stilles OK)"
make_repo "$TMP/pool-crash"
( cd "$TMP/pool-crash" && printf 'Report vulnerabilities via Private vulnerability reporting on GitHub.\n' > SECURITY.md \
    && $GIT add SECURITY.md && $GIT commit -q -m sec )
OUT=$(cd "$TMP/pool-crash" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Ausgangslage ohne Pool-Definitionen ist sauber (exit 0)" '[ "$RC" -eq 0 ]'

# The real sub-script, but without ../lib next to it: it aborts with exit 2 ("Regeldatei fehlt")
# and prints not a single finding line. Exactly this case used to drop out silently.
mkdir -p "$TMP/pool-orphan"
cp "$REPO/scripts/ci/pool-neutrality.sh" "$TMP/pool-orphan/"
printf '#!/usr/bin/env bash\nexec bash "%s/pool-neutrality.sh" "$@"\n' "$TMP/pool-orphan" \
  > "$TMP/pool-crash/scripts/ci/pool-neutrality.sh"
OUT=$(cd "$TMP/pool-crash" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Abbruch schlaegt das Gate (exit 1)"          '[ "$RC" -eq 1 ]'
check "Gate meldet kein OK"                         '! grep -q "Public-Hygiene-Gate OK" <<<"$OUT"'
check "Meldung nennt die Roh-Ausgabe"               'grep -q "Regeldatei fehlt" <<<"$OUT"'
check "Meldung nennt den Exit-Code"                 'grep -q "exit 2" <<<"$OUT"'

echo "23) Verwertbare Befundzeilen werden weiterhin je Definition annotiert"
printf '#!/usr/bin/env bash\nprintf "workflow-pool/x.json\\tPATH-ABS\\tabsoluter Pfad im Prompt\\n"\nexit 1\n' \
  > "$TMP/pool-crash/scripts/ci/pool-neutrality.sh"
OUT=$(cd "$TMP/pool-crash" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Befund schlaegt das Gate (exit 1)"           '[ "$RC" -eq 1 ]'
check "Gate nennt die Definition"                   'grep -q "workflow-pool/x.json" <<<"$OUT"'
check "Gate nennt Tag und Beschreibung"             'grep -q "PATH-ABS.*absoluter Pfad im Prompt" <<<"$OUT"'
check "kein zusaetzlicher Abbruch-Fehler"           '! grep -q "Pool-Neutralitaet abgebrochen" <<<"$OUT"'

echo "24) Die Instanz-Schluesselliste des Gates stammt aus dem PoolExportSanitizer (TODO 96640e6f)"
SANITIZER="$REPO/ai-automation-backend/src/main/java/com/automation/service/pool/PoolExportSanitizer.java"
# The camelCase list from INSTANCE_REFERENCE_KEYS, plus the snake_case spelling of every key
JAVA_KEYS="$(sed -n '/INSTANCE_REFERENCE_KEYS = List.of(/,/);/p' "$SANITIZER" \
  | grep -oE '"[a-zA-Z]+"' | tr -d '"' \
  | sed -E 'p; s/([a-z0-9])([A-Z])/\1_\2/g' | tr 'A-Z' 'a-z' | sort -u | tr '\n' ' ')"
GATE_KEYS="$(printf '%s\n' "${HYGIENE_POOL_INSTANCE_KEYS[@]:-}" | tr 'A-Z' 'a-z' | sort -u | tr '\n' ' ')"
check "Sanitizer-Liste nicht leer"             '[ -n "$JAVA_KEYS" ]'
check "Gate-Liste deckt sich mit dem Sanitizer" '[ "$JAVA_KEYS" = "$GATE_KEYS" ]'
check "generierte Datei ist getrackt"          '[ -n "$(cd "$REPO" && git ls-files scripts/lib/pool-sanitizer.generated.sh)" ]'

echo "25) Ohne die generierte Schluesselliste bricht die Regeldatei ab, statt leer weiterzulaufen"
make_repo "$TMP/no-keys"
rm -f "$TMP/no-keys/scripts/lib/pool-sanitizer.generated.sh"
OUT=$(cd "$TMP/no-keys" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Gate meldet einen Fehler (exit != 0)"   '[ "$RC" -ne 0 ]'
check "Meldung nennt die generierte Datei"     'grep -q "pool-sanitizer.generated.sh" <<<"$OUT"'

echo "26) Fehlende Pool-Privatliste wird hoerbar uebersprungen (TODO 0640f29b)"
make_repo "$TMP/priv"
( cd "$TMP/priv" && printf 'Report vulnerabilities via Private vulnerability reporting on GitHub.\n' > SECURITY.md \
    && mkdir -p workflow-pool && cat > workflow-pool/persoenlich-beispiel.json <<'JSON'
{"name": {"de": "Portabel", "en": "Portable"},
 "description": {"de": "Prueft ein Projekt.", "en": "Checks a project."},
 "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON
  $GIT add SECURITY.md workflow-pool && $GIT commit -q -m pool )
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "ohne Liste bleibt das Gate gruen (exit 0)" '[ "$RC" -eq 0 ]'
check "Gate nennt die fehlende Liste"             'grep -q "hygiene-private.txt" <<<"$OUT"'
check "Gate sagt, dass es uebersprungen hat"      'grep -qi "uebersprungen\|nicht geprueft" <<<"$OUT"'

echo "27) Getrackte Pool-Datei auf der Privatliste schlaegt das Gate"
printf '# privat\nworkflow-pool/persoenlich-*.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Treffer schlaegt das Gate (exit 1)"   '[ "$RC" -eq 1 ]'
check "Gate nennt die Datei"                 'grep -q "workflow-pool/persoenlich-beispiel.json" <<<"$OUT"'
check "Gate meldet kein OK"                  '! grep -q "Public-Hygiene-Gate OK" <<<"$OUT"'

echo "28) Muster ohne Treffer laesst das Gate gruen"
printf '# privat\nworkflow-pool/etwas-anderes-*.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "kein Treffer: exit 0"                 '[ "$RC" -eq 0 ]'
check "Gate nennt keine Pool-Datei"          '! grep -q "persoenlich-beispiel.json" <<<"$OUT"'
check "kein Skip-Hinweis mehr"               '! grep -qi "hygiene-private.txt fehlt" <<<"$OUT"'

echo "29) Die Privatliste selbst liegt nicht im getrackten Bestand, die Vorlage schon"
check "hygiene-private.txt nicht getrackt" \
  '[ -z "$(cd "$REPO" && git ls-files scripts/ci/hygiene-private.txt)" ]'
check "hygiene-private.txt.example getrackt" \
  '[ -n "$(cd "$REPO" && git ls-files scripts/ci/hygiene-private.txt.example)" ]'

echo "30) Letzte Zeile ohne Zeilenumbruch zaehlt mit"
# A one-line list without a trailing \n used to be a silent no-op:
# read returns false at EOF without a line break, the loop never ran.
printf 'workflow-pool/persoenlich-*.json' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Treffer trotz fehlendem Zeilenumbruch (exit 1)" '[ "$RC" -eq 1 ]'
check "Gate nennt die Datei"                           'grep -q "workflow-pool/persoenlich-beispiel.json" <<<"$OUT"'

echo "31) Muster in der Schreibweise von .git/info/exclude (fuehrender /)"
printf '/workflow-pool/persoenlich-*.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "fuehrender / wird abgestreift, Treffer erkannt" '[ "$RC" -eq 1 ]'
check "Gate nennt die Datei"                           'grep -q "workflow-pool/persoenlich-beispiel.json" <<<"$OUT"'
check "kein git-Fehler in der Ausgabe"                 '! grep -qi "Invalid path\|ungueltiger Pfad" <<<"$OUT"'

echo "32) Ein Muster, das git nicht aufloesen kann, ist ein Gate-Fehler"
printf ':(bogus)workflow-pool/x.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "unaufloesbares Muster schlaegt das Gate (exit 1)" '[ "$RC" -eq 1 ]'
check "Meldung nennt das Muster"                         'grep -q "bogus" <<<"$OUT"'
check "Meldung nennt die Liste"                          'grep -q "hygiene-private.txt" <<<"$OUT"'
check "Gate meldet kein OK"                              '! grep -q "Public-Hygiene-Gate OK" <<<"$OUT"'

echo "33) Auch der Pre-Commit-Moment (--staged) prueft die Privatliste"
( cd "$TMP/priv" && cat > workflow-pool/neu-privat.json <<'JSON'
{"name": {"de": "Neu", "en": "New"},
 "description": {"de": "Prueft ein Projekt.", "en": "Checks a project."},
 "scope": "portable", "tags": ["quality"],
 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
JSON
  $GIT add workflow-pool/neu-privat.json )
printf 'workflow-pool/neu-*.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "--staged meldet die gestagte Pool-Datei (exit 1)" '[ "$RC" -eq 1 ]'
check "Gate nennt die gestagte Datei"                    'grep -q "workflow-pool/neu-privat.json" <<<"$OUT"'
printf 'workflow-pool/etwas-anderes-*.json\n' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "--staged ohne Treffer bleibt gruen (exit 0)"      '[ "$RC" -eq 0 ]'

echo "34) Klon ohne Repo-Liste findet die Privatliste an den Ausweichorten (TODO 6e601c77)"
rm -f "$TMP/priv/scripts/ci/hygiene-private.txt"
mkdir -p "$XDG_CONFIG_HOME/mysuricates"
printf 'workflow-pool/persoenlich-*.json
' > "$XDG_CONFIG_HOME/mysuricates/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "XDG-Liste greift (exit 1)"                        '[ "$RC" -eq 1 ]'
check "Gate nennt die Datei"                             'grep -q "workflow-pool/persoenlich-beispiel.json" <<<"$OUT"'
check "kein Skip-Hinweis"                                '! grep -qi "hygiene-private.txt fehlt" <<<"$OUT"'
rm -f "$XDG_CONFIG_HOME/mysuricates/hygiene-private.txt"
printf 'workflow-pool/persoenlich-*.json
' > "$TMP/env-liste.txt"
OUT=$(cd "$TMP/priv" && HYGIENE_PRIVATE_LIST="$TMP/env-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "\$HYGIENE_PRIVATE_LIST greift (exit 1)"           '[ "$RC" -eq 1 ]'
check "Gate nennt die Datei"                             'grep -q "workflow-pool/persoenlich-beispiel.json" <<<"$OUT"'
printf 'workflow-pool/etwas-anderes-*.json
' > "$TMP/priv/scripts/ci/hygiene-private.txt"
OUT=$(cd "$TMP/priv" && HYGIENE_PRIVATE_LIST="$TMP/env-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Repo-Liste hat Vorrang vor der Env-Liste (exit 0)" '[ "$RC" -eq 0 ]'

echo "35) Private Begriffsliste: Treffer im getrackten Bestand, Begriff bleibt ungenannt (TODO 1b84ecab)"
make_repo "$TMP/terms"
( cd "$TMP/terms" && printf 'Notiz zu Geheimprojekt Zebrafink\n' > notiz.md && $GIT add notiz.md && $GIT commit -q -m notiz )
OUT=$(cd "$TMP/terms" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "ohne Liste: hoerbar uebersprungen"                'grep -q "hygiene-private-terms.txt fehlt" <<<"$OUT"'
check "ohne Liste: kein Begriffsbefund"                  '! grep -q "privater Begriff" <<<"$OUT"'
printf '# Kommentar\n\nzebrafink\n' > "$TMP/terms-liste.txt"
OUT=$(cd "$TMP/terms" && HYGIENE_PRIVATE_TERMS="$TMP/terms-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Treffer ohne Gross-/Kleinschreibung (exit 1)"     '[ "$RC" -eq 1 ]'
check "Gate nennt Datei und Zeile"                       'grep -q "notiz.md:1" <<<"$OUT"'
check "Gate nennt die Listenzeile"                       'grep -q "Zeile 3 der privaten Begriffsliste" <<<"$OUT"'
check "Gate nennt den Begriff nicht"                     '! grep -qi "zebrafink" <<<"$OUT"'
mkdir -p "$XDG_CONFIG_HOME/mysuricates"
cp "$TMP/terms-liste.txt" "$XDG_CONFIG_HOME/mysuricates/hygiene-private-terms.txt"
OUT=$(cd "$TMP/terms" && bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "XDG-Begriffsliste greift (exit 1)"                '[ "$RC" -eq 1 ]'
# Since the pre-push gate (7030f362) --staged checks the added lines
# (check_private_terms_diff), the existing content still not: notiz.md stays unnamed.
OUT=$(cd "$TMP/terms" && printf 'zebrafink\n' > neu.md && git add neu.md && bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "--staged prueft den Bestand nicht"                '! grep -q "privater Begriff" <<<"$OUT" && ! grep -q "notiz.md" <<<"$OUT"'
check "--staged prueft die neue Zeile (exit 1)"          '[ "$RC" -eq 1 ] && grep -q "neu.md:1 — neue Zeile enthaelt einen privaten Begriff (Zeile 3" <<<"$OUT"'
rm -f "$XDG_CONFIG_HOME/mysuricates/hygiene-private-terms.txt"
printf 'Schnecke\n' > "$TMP/terms-liste.txt"
OUT=$(cd "$TMP/terms" && HYGIENE_PRIVATE_TERMS="$TMP/terms-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "Liste ohne Treffer: kein Begriffsbefund"          '! grep -q "privater Begriff" <<<"$OUT"'

echo "36) Praefix low: — schwacher Begriff warnt nur, im Bestand und in neuen Zeilen (TODO 2542ef85)"
make_repo "$TMP/weak"
( cd "$TMP/weak" && printf 'Report vulnerabilities via Private vulnerability reporting on GitHub.\n' > SECURITY.md \
    && printf 'Notiz: Entscheidung Zaunkoenig zum Moorhuhn-Projekt\n' > notiz.md \
    && $GIT add SECURITY.md notiz.md && $GIT commit -q -m notiz )
printf '# Kommentar\nlow: zaunkoenig\n' > "$TMP/weak-liste.txt"
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "nur schwacher Treffer: Gate bleibt gruen (exit 0)"  '[ "$RC" -eq 0 ]'
check "Warnung nennt Datei, Zeile und Listenzeile" \
  'grep -q "notiz.md:1 — privater Begriff, schwach (Zeile 2 der privaten Begriffsliste)" <<<"$OUT"'
check "Warnung statt Verstoss"                             'grep -q "Public-Hygiene-Gate OK — 1 Warnung(en), kein Verstoss" <<<"$OUT"'
check "Begriff steht nicht in der Ausgabe"                 '! grep -qi "zaunkoenig" <<<"$OUT"'
check "Praefix wird nicht als Begriff gelesen"             '! grep -q "privater Begriff (Zeile" <<<"$OUT"'

printf '   low:zaunkoenig   \n' > "$TMP/weak-liste.txt"
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "low: ohne Leerzeichen, eingerueckt: ebenso schwach" \
  '[ "$RC" -eq 0 ] && grep -q "privater Begriff, schwach (Zeile 1" <<<"$OUT"'

printf 'low: zaunkoenig\nmoorhuhn\n' > "$TMP/weak-liste.txt"
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --tracked 2>&1); RC=$?
check "normaler Begriff daneben schlaegt das Gate (exit 1)" '[ "$RC" -eq 1 ]'
check "normaler Treffer als Verstoss"                      'grep -q "notiz.md:1 — privater Begriff (Zeile 2" <<<"$OUT"'
check "schwacher Treffer bleibt Warnung"                   'grep -q "notiz.md:1 — privater Begriff, schwach (Zeile 1" <<<"$OUT"'

printf 'low: zaunkoenig\n' > "$TMP/weak-liste.txt"
( cd "$TMP/weak" && printf 'Neu: Zaunkoenig war da\n' > neu.md && git add neu.md )
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "--staged: schwacher Treffer blockiert nicht (exit 0)" '[ "$RC" -eq 0 ]'
check "--staged: Warnung nennt Datei und Listenzeile" \
  'grep -q "neu.md:1 — neue Zeile enthaelt einen privaten Begriff, schwach (Zeile 1" <<<"$OUT"'
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" $GIT commit -q -m neu 2>&1); RC=$?
check "pre-commit-Hook laesst den Commit zu"               '[ "$RC" -eq 0 ]'
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --range HEAD~1..HEAD 2>&1); RC=$?
check "--range: schwacher Treffer blockiert nicht (exit 0)"  '[ "$RC" -eq 0 ]'
check "--range: Warnung nennt die Datei" \
  'grep -q "neu.md:1 — neue Zeile enthaelt einen privaten Begriff, schwach" <<<"$OUT"'
printf 'zaunkoenig\n' > "$TMP/weak-liste.txt"
OUT=$(cd "$TMP/weak" && HYGIENE_PRIVATE_TERMS="$TMP/weak-liste.txt" bash scripts/ci/public-hygiene.sh --range HEAD~1..HEAD 2>&1); RC=$?
check "--range: ohne low: weiterhin ein Verstoss (exit 1)" '[ "$RC" -eq 1 ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
