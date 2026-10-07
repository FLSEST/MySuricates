#!/usr/bin/env bash
#
# Shared rule base for the hygiene gate.
#
# Read by scripts/ci/public-hygiene.sh (CI and local) and by
# scripts/git-hooks/pre-commit (staged changes). Previously the pattern and path
# lists were duplicated verbatim in both files — an addition in one place was
# then missing in the other.
#
# This file only defines data and small helpers, it executes nothing.
#
# shellcheck shell=bash

# ---------------------------------------------------------------------------
# Forbidden literals in tracked files
#
# Format: "pattern|description". Deliberately literals, not regexes: they are searched with
# git grep -F or a Bash substring comparison so that hook and CI
# have the same semantics.
# ---------------------------------------------------------------------------
HYGIENE_PATTERNS=(
  "/home/flo|absolute developer path"
  "test-e2e-key|hardcoded test API key string"
  "ebf4f50c-20c2-4c35-a84a-a0b350124523|internal project UUID"
  "changeme|known default credential"
  "[auto-generated]|nie ersetzter Stempel-Platzhalter, ersatzlos streichen (siehe TODO 994af971)"
)

# ---------------------------------------------------------------------------
# Files that legitimately contain the patterns as strings
# ---------------------------------------------------------------------------
HYGIENE_SELF_EXCLUDE=(
  "scripts/lib/hygiene-rules.sh"
  "scripts/ci/public-hygiene.sh"
  "scripts/ci/pool-neutrality.sh"
  "scripts/git-hooks/pre-commit"
  "scripts/tests/test-public-hygiene.sh"
)

# ---------------------------------------------------------------------------
# Backup files that must be neither tracked nor ignored
#
# Locally such a file is only a warning, when staging it is an error: this is exactly how
# workflow-pool/job-shortlist-template.html.bak-<timestamp> almost ended up in
# a release commit.
# ---------------------------------------------------------------------------
# Commit-Metadaten (Pre-Push-Gate, scripts/ci/pre-push-checks.sh; TODOs 7030f362, 5e0edb7c):
# Author and committer carry only the GitHub noreply form, never a private address.
# The address itself appears nowhere in the repository — only its form.
HYGIENE_COMMIT_EMAIL_REGEX='@users\.noreply\.github\.com$'
# Trailers whose addresses are allowed in the commit message (AI authorship, sign-off).
# All other lines of a message must not contain an email address.
HYGIENE_COMMIT_TRAILER_REGEX='^(Co-Authored-By|Co-authored-by|Generated-By|Signed-off-by|Reviewed-by): '
# Subject line of new commits (TODO 7ce5c71a): at most HYGIENE_SUBJECT_MAX characters. The commit-msg hook
# always checks; push range and CI only check commits whose author date is not before HYGIENE_SUBJECT_SINCE
# (Unix time of the rule, 2026-10-06), so older commits are not blocked - rebased ones neither, a rebase keeps
# the author date. Subjects Git writes itself (revert, autosquash) are exempt.
HYGIENE_SUBJECT_MAX=72
HYGIENE_SUBJECT_SINCE=1791316709
HYGIENE_SUBJECT_EXEMPT_REGEX='^(Revert "|fixup! |squash! |amend! )'
HYGIENE_EMAIL_REGEX='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'

HYGIENE_BACKUP_GLOBS=(
  '*.bak' '*.bak-*' '*.orig' '*.old' '*~' '*.save' '*.rej'
)

# ---------------------------------------------------------------------------
# Repo weight
# ---------------------------------------------------------------------------
# From this size a tracked blob needs an explanation.
HYGIENE_MAX_BLOB_BYTES=${HYGIENE_MAX_BLOB_BYTES:-5242880}   # 5 MiB
# From this size a doubly tracked blob counts as a duplicate.
HYGIENE_DUP_MIN_BYTES=${HYGIENE_DUP_MIN_BYTES:-262144}      # 256 KiB

# ---------------------------------------------------------------------------
# SECURITY.md — mandatory file with a real reporting channel
#
# Checked only in the tracked tree (--tracked/--worktree), not with --staged:
# blocking every single commit would be too intrusive for a file that
# rarely changes. The release question is "is the final state correct?".
# ---------------------------------------------------------------------------
HYGIENE_SECURITY_FILE="SECURITY.md"
HYGIENE_SECURITY_EMAIL_REGEX='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}'
# At least one of these terms must occur (reporting channel recognizable).
HYGIENE_SECURITY_REQUIRED_TERMS=(
  "Private vulnerability reporting"
  "Security Advisories"
)

# ---------------------------------------------------------------------------
# Curation rules for the public workflow pool (TODO 523702f4)
#
# The plan is in docs/workflow-pool-kuratierung.md; the values here are its
# machine-checkable side. Read by scripts/ci/pool-neutrality.sh.
# ---------------------------------------------------------------------------

# Allowed values of the mandatory field "scope".
#   repo     — accesses the structure of this repository
#   portable — parameterized via project_path (or similar), runs in any project
#   external — needs a tool outside this repository and the chosen
#              project; the prerequisite is stated in the description
HYGIENE_POOL_SCOPES=("repo" "portable" "external")

# Registered block types. Source: BlockTypesIndexSection.BLOCK_TYPES in the backend
# (itself pinned there against the BlockExecutor classes, BlockTypeListsGuardTest).
# scripts/tests/test-public-hygiene.sh compares both lists — if one drifts,
# it is noticed there.
# HYGIENE_POOL_BLOCK_TYPES is not defined here but comes from the generated
# file next to it (source: BlockTypesIndexSection.BLOCK_TYPES in the backend, generated
# by BlockTypesGateListTest). Previously the list was maintained by hand and only
# compared afterwards by check 13 of the hygiene tests — a new block type
# was therefore only noticed when someone ran those tests (TODO bdc784e1).
# It is read in below together with pool-sanitizer.generated.sh.

# Markers that bind an entry to THIS repository. In an
# entry with scope: portable they are a finding — whoever installs it into their own
# project would otherwise run into nothing.
HYGIENE_POOL_REPO_MARKERS=(
  "ai-automation-backend" "ai-automation-frontend" "scripts/" "mvnw" "workflow-pool/"
)

# Keys that identify a running instance. As a field of the definition, in the
# defaultContext or in the metadata they are a finding — unless the
# value is empty or a template variable ({{ project_id }}), because that is
# exactly the portable parameterization.
#
# HYGIENE_POOL_INSTANCE_KEYS is not defined here but comes from the generated
# file next to it: the source is PoolExportSanitizer in the backend, which removes the same
# keys on export. Previously there were two uncoupled lists —
# if one drifted, nobody noticed (TODO 96640e6f).
HYGIENE_RULES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$HYGIENE_RULES_DIR/pool-sanitizer.generated.sh" ]; then
  # shellcheck source=pool-sanitizer.generated.sh
  . "$HYGIENE_RULES_DIR/pool-sanitizer.generated.sh"
else
  echo "FEHLER: scripts/lib/pool-sanitizer.generated.sh fehlt (erzeugt von PoolSanitizerKeysTest)." >&2
  return 2 2>/dev/null || exit 2
fi

if [ -f "$HYGIENE_RULES_DIR/block-types.generated.sh" ]; then
  # shellcheck source=block-types.generated.sh
  . "$HYGIENE_RULES_DIR/block-types.generated.sh"
else
  echo "FEHLER: scripts/lib/block-types.generated.sh fehlt (erzeugt von BlockTypesGateListTest)." >&2
  return 2 2>/dev/null || exit 2
fi

# ---------------------------------------------------------------------------
# Criterion "definition calls its own REST API or reads credentials"
# (TODO 1ccdc6d5)
#
# The house rule: workflow blocks call neither their own REST API nor the
# database — everything they need is injected by the WorkflowExecutor as a
# context variable ({{ open_todos }}, {{ previous_report }}, …). In a
# co-published definition a second problem arises: a prompt that
# explains how to fetch the API key from app_settings is a guide
# to reading credentials — every reader installs it along with the rest.
# ---------------------------------------------------------------------------

# Credentials: no exception, the occurrence alone is the finding. Plus the key file of the
# instance in the data folder (DataHome, even without curl afterwards: whoever reads it holds the key in plain text,
# TODO ee8bb93d) — the same path the analyzer reports as self-api-call (SelfAccessRules.KEY_FILE).
# ")/api-key" catches the data folder from a command substitution ($(data_home …)/api-key, lib/data-home.sh).
# Copies such as api-key.bak or api-key-backup deliberately match too: they contain the same key.
HYGIENE_POOL_CREDENTIAL_MARKERS=("X-API-Key" "app_settings" ".mysuricates/api-key" ".autotrigger/api-key"
  "AUTOMATION_DATA_HOME/api-key" "AUTOMATION_DATA_HOME}/api-key" ")/api-key")

# Own REST API. The prefix alone is not enough for a finding: mocking-gap-audit
# lists "/api/v1/" as a search pattern in a list of network markers, and
# that is not a call. Only the combination of path and call is reported.
# /actuator/ belongs here: apart from /actuator/health everything there requires the key (security/PublicPaths,
# TODO 7721d8cb — /actuator/metrics silently returned 403 to system-performance-analysis).
HYGIENE_POOL_API_PREFIXES=("/api/v1/" "/actuator/")
HYGIENE_POOL_API_CALLERS=("curl" "wget" "requests." "urllib" "fetch(" "http.client")

# Reachable without an API key (security/PublicPaths) and therefore allowed as a liveness probe
# — mysuricates-performance-audit uses it to measure response time and reads the
# three heap figures from /api/v1/system/jvm-memory (c2cda5ed; /actuator/metrics needs the key).
# In the shell code of a pool block, every other concrete /api/v1/ path is a finding, even without
# a call nearby; compared exactly as in the filter (28007626).
# The workflow analyzer keeps the same list for live definitions (SelfAccessRules.KEY_FREE_PROBES,
# rule self-api-call, d0348479); SelfAccessRulesTest keeps both equal and checks that every
# probe is public in PublicPaths.
HYGIENE_POOL_API_ALLOWED=(
  "/api/v1/system/health" "/api/v1/system/info" "/api/v1/system/auth-status"
  "/api/v1/system/jvm-memory" "/actuator/health"
)

# Direct database access. Looking at the file (stat, du) remains allowed —
# what is meant is reading the content, which bypasses Hibernate and pool-size=1.
HYGIENE_POOL_DB_FILES=("automation.db")
HYGIENE_POOL_DB_ACCESS=("sqlite3" "SELECT " "INSERT INTO" "UPDATE " "DELETE FROM" ".tables" ".schema")

# ---------------------------------------------------------------------------
# Generic build names: allowed in .git/info/exclude AND .gitignore
#
# check_gitignore_leaks (public-hygiene.sh) would otherwise report every name from the
# local exclude file that also appears in .gitignore as a leak. These
# names are too generic to reveal anything personal — every
# Node/Java/Python project produces them. The rule remains in force for everything else,
# in particular workflow-pool/* (see TODO 819b387e).
# ---------------------------------------------------------------------------
HYGIENE_GITIGNORE_LEAK_ALLOWED_NAMES=(
  "node_modules" "target" "dist" "build" ".venv" "__pycache__" "*.log"
)

# hygiene_gitignore_leak_is_generic <basename>
hygiene_gitignore_leak_is_generic() {
  local base="$1" n
  for n in "${HYGIENE_GITIGNORE_LEAK_ALLOWED_NAMES[@]}"; do
    [ "$base" = "$n" ] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Allowlist: deliberate exceptions, only paths with a justification
#
# Deliberately without pattern literals — the allowlist itself should reveal nothing.
# ---------------------------------------------------------------------------
HYGIENE_ALLOWLIST_FILE="scripts/ci/hygiene-allowlist.txt"

# ---------------------------------------------------------------------------
# Private exclusion list for the workflow pool (TODO 0640f29b)
#
# The personal pool definitions are kept out of the tracked tree via .git/info/exclude —
# there is no plain-text name list in the public repository.
# But exclude is not cloned and does not apply in any worktree: a blanket
# "git add -A" brings the files back without anything triggering. This list
# is the enforceable half of it; it is itself not in the repository
# (it too is in .git/info/exclude). If it is missing, the check is audibly
# skipped instead of silently passing. Template: the .example file next to it.
#
# Because it is untracked, it is missing in every clone and every worktree — also in the
# isolated run workspace in which the evidence workflow runs the gate (TODO
# 6e601c77, private hygiene list missing). Hence there are two fallback locations
# outside the repository: $HYGIENE_PRIVATE_LIST and the user configuration.
# ---------------------------------------------------------------------------
HYGIENE_PRIVATE_LIST_FILE="scripts/ci/hygiene-private.txt"

# hygiene_private_list_path <repo-root> — prints the path of the first existing
# list (repo file, then $HYGIENE_PRIVATE_LIST, then
# ${XDG_CONFIG_HOME:-$HOME/.config}/mysuricates/hygiene-private.txt); exit 1
# if none exists.
hygiene_private_list_path() {
  local root="$1" c
  for c in "$root/$HYGIENE_PRIVATE_LIST_FILE" \
           "${HYGIENE_PRIVATE_LIST:-}" \
           "${XDG_CONFIG_HOME:-${HOME:-}/.config}/mysuricates/hygiene-private.txt"; do
    [ -n "$c" ] && [ -f "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

# ---------------------------------------------------------------------------
# Private term list (TODO 1b84ecab)
#
# The forbidden literals above stand in plain text in the repository — that is no good for names
# of private projects, employers or persons: the list would itself be the
# disclosure. Therefore this list, like the private pool list, lives
# outside the tracked tree and is found via the same fallback locations. If it is missing
# (CI, foreign clone), the check is audibly skipped.
#
# Format: one term per line, comments with #. Searched literally and without
# regard to case. Messages do not name the term,
# only its line number in the list — the output ends up in the evidence report.
#
# Prefix "low:" (TODO 2542ef85): "low: <term>" marks a weak
# term. It stays on the list, but a hit is only a warning
# ("private term, weak") — no violation, no exit 1, neither in the tracked tree
# nor on commit or push; the evidence workflow rates it as LOW. Meant
# for terms that should stay visible but not hold up a release.
# ---------------------------------------------------------------------------
HYGIENE_PRIVATE_TERMS_FILE="scripts/ci/hygiene-private-terms.txt"

# hygiene_private_terms_path <repo-root> — like hygiene_private_list_path, for
# the term list ($HYGIENE_PRIVATE_TERMS, then the user configuration).
hygiene_private_terms_path() {
  local root="$1" c
  for c in "$root/$HYGIENE_PRIVATE_TERMS_FILE" \
           "${HYGIENE_PRIVATE_TERMS:-}" \
           "${XDG_CONFIG_HOME:-${HOME:-}/.config}/mysuricates/hygiene-private-terms.txt"; do
    [ -n "$c" ] && [ -f "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

# hygiene_private_terms_load <list> — the only parser of the term list. Fills
# three parallel arrays:
#   HYGIENE_TERMS       term (trimmed, without comment and without prefix)
#   HYGIENE_TERM_LINES  line number in the list — messages name only it
#   HYGIENE_TERM_WEAK   1 = weak (prefix low:), 0 = normal
# public-hygiene.sh (tracked tree and added lines) and pre-push-checks.sh
# (commit messages) read the list only through this. Previously each check had
# its own loop — a format rule in one place was missing in the other,
# and a "low:" entry would have silently run into nothing there as a literal term.
HYGIENE_TERMS=()
HYGIENE_TERM_LINES=()
HYGIENE_TERM_WEAK=()
hygiene_private_terms_load() {
  local list="$1" term lineno=0 weak
  HYGIENE_TERMS=(); HYGIENE_TERM_LINES=(); HYGIENE_TERM_WEAK=()
  # "|| [ -n "$term" ]" picks up the last line if the file ends without a line break.
  while IFS= read -r term || [ -n "$term" ]; do
    lineno=$((lineno + 1))
    term="${term%%#*}"
    term="${term#"${term%%[![:space:]]*}"}"
    term="${term%"${term##*[![:space:]]}"}"
    weak=0
    case "$term" in
      low:*)
        weak=1
        term="${term#low:}"
        term="${term#"${term%%[![:space:]]*}"}"
        ;;
    esac
    [ -z "$term" ] && continue
    HYGIENE_TERMS+=("$term")
    HYGIENE_TERM_LINES+=("$lineno")
    HYGIENE_TERM_WEAK+=("$weak")
  done < "$list"
}

# Reads the allowlist as a path list (comments and blank lines are dropped).
hygiene_allowlist_paths() {
  [ -f "$HYGIENE_ALLOWLIST_FILE" ] || return 0
  sed -e 's/#.*//' -e 's/[[:space:]]*$//' "$HYGIENE_ALLOWLIST_FILE" | grep -v '^$' || true
}

# hygiene_is_allowlisted <path>
hygiene_is_allowlisted() {
  local p="$1" entry
  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    [ "$p" = "$entry" ] && return 0
  done < <(hygiene_allowlist_paths)
  return 1
}

# hygiene_is_self_excluded <path>
hygiene_is_self_excluded() {
  local p="$1" e
  for e in "${HYGIENE_SELF_EXCLUDE[@]}"; do
    [ "$p" = "$e" ] && return 0
  done
  return 1
}

# hygiene_pattern_of <entry> / hygiene_desc_of <entry>
hygiene_pattern_of() { echo "${1%%|*}"; }
hygiene_desc_of()    { echo "${1##*|}"; }
