#!/usr/bin/env bash
#
# Pre-Push-Gate
#
# What can be checked locally before a push — deterministic, without judgement, in seconds
# up to a minute:
#   1. Commit metadata of the push range: author and committer carry only the
#      GitHub noreply form; the message contains no forbidden literals, no private
#      terms (weak ones, prefix low:, only as a warning) and no mail address outside
#      a trailer. The subject of new commits has at most 72 characters (TODO 7ce5c71a).
#   2. Hygiene gate on the push range (public-hygiene.sh --range): what is NEWLY added.
#   3. Existing CI gates that depend on the code: doc numbers, doc references with
#      DE/EN parity, diagram drift.
#   4. gitleaks on the range — only if installed, otherwise audibly skipped.
#      Nothing is installed.
#   4b. Exception files in the range: whoever changes .gitleaksignore, the hygiene rules, the gate itself or
#      the hooks in the same push may be loosening exactly the check that is about to wave them
#      through. That blocks until a human releases it for exactly this state
#      (MYSURICATES_PREPUSH_ALLOW_RULES=<sha of the pushed state>); the CI only reports it.
#   5. Build check by scope (same classification as the CI, scripts/ci/changed-scopes.sh):
#      frontend suite affected -> tsc + lint; backend suite affected -> mvn test-compile.
#
# Not here: the full test suite (CI and the commit rule in CLAUDE.md), the tracked
# inventory (public-hygiene.sh --tracked in the CI and in the "Public Readiness" chain) and
# everything that needs a judgement.
#
# Callers: scripts/git-hooks/pre-push (--hook, refs on stdin), scripts/git-hooks/commit-msg
# (--message-file), CI (--tree and --range), by hand (--range A..B).
#
# Usage: bash scripts/ci/pre-push-checks.sh --hook | --range A..B | --tree | --message-file <file>
#                                           [--skip-tree] [--skip-build] [--rules-report-only]
#        --rules-report-only  only report changed exception files (warning + job summary), do not
#                             block — for the CI, where no human can set a release.
# Env:   MYSURICATES_PREPUSH_SKIP=tree,build — for the hooks, which take no arguments.
#        Every omission is reported; a gate that does not measure is not green.
#        MYSURICATES_PREPUSH_ALLOW_RULES=<sha>[,<sha>…] — release of changed exception files,
#        applies only to the range whose tip starts with this hash (at least 7 characters).
#        Deliberately not coverable via MYSURICATES_PREPUSH_SKIP.
# Exit:  0 = clean, 1 = violation or failure, 2 = gate not runnable
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo root from the script path, not from git: in worktrees git exports GIT_DIR to
# hooks (see public-hygiene.sh).
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

RULES_FILE="$REPO_ROOT/scripts/lib/hygiene-rules.sh"
HYGIENE_GATE="$REPO_ROOT/scripts/ci/public-hygiene.sh"
if [ ! -f "$RULES_FILE" ]; then
  echo "Pre-Push-Gate: Regeldatei fehlt: $RULES_FILE" >&2
  exit 2
fi
# shellcheck source=../lib/hygiene-rules.sh
. "$RULES_FILE"

MODE=""
RANGE=""
MSG_FILE=""
SKIP_TREE=0
SKIP_BUILD=0
RULES_REPORT_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --hook)         MODE="hook" ;;
    --tree)         MODE="tree" ;;
    --range)        MODE="range"; RANGE="${2:-}"; shift ;;
    --message-file) MODE="message"; MSG_FILE="${2:-}"; shift ;;
    --skip-tree)    SKIP_TREE=1 ;;
    --skip-build)   SKIP_BUILD=1 ;;
    --rules-report-only) RULES_REPORT_ONLY=1 ;;
    *) echo "Pre-Push-Gate: unbekanntes Argument '$1'" >&2; exit 2 ;;
  esac
  shift
done
[ -z "$MODE" ] && { echo "Pre-Push-Gate: Modus fehlt (--hook | --range A..B | --tree | --message-file <datei>)" >&2; exit 2; }
case ",${MYSURICATES_PREPUSH_SKIP:-}," in *,tree,*)  SKIP_TREE=1 ;; esac
case ",${MYSURICATES_PREPUSH_SKIP:-}," in *,build,*) SKIP_BUILD=1 ;; esac

FAIL=0
COUNT=0
WARN=0
ZERO="0000000000000000000000000000000000000000"

# Which kinds of findings the run had — the final hint names the matching way out for each kind.
# Every check sets KIND before it can call fail().
KIND="meta"
FAIL_KINDS=","
fail() {  # $1 = message
  case "$FAIL_KINDS" in *",${KIND},"*) ;; *) FAIL_KINDS="${FAIL_KINDS}${KIND}," ;; esac
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::${1}"; else echo "  ✗ ${1}" >&2; fi
  FAIL=1
  COUNT=$((COUNT + 1))
}
# Visible, but not a violation — e.g. a weak private term (prefix low:).
warn() {  # $1 = meldung
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::${1}"; else echo "  ! ${1}" >&2; fi
  WARN=$((WARN + 1))
}
note() { echo "  – $1"; }
step() { echo "▶ $1"; }

# ---------------------------------------------------------------------------
# Private term list — load once (parser: hygiene_private_terms_load, the same as in the
# hygiene gate), messages name the list line, never the term.
# ---------------------------------------------------------------------------
TERMS_LOADED=0
TERMS_LC=()
# Lowercasing: from Bash 4 via parameter expansion (also folds umlauts in a UTF-8 locale, as
# before). Bash 3.2 (macOS) does not have it — there python3, otherwise awk (mawk/BSD awk fold only
# ASCII). The expansion is only evaluated at runtime, Bash 3.2 never reaches this branch.
if [ "${BASH_VERSINFO[0]}" -ge 4 ]; then
  lower_stream() { local l; while IFS= read -r l || [ -n "$l" ]; do printf '%s\n' "${l,,}"; done; }
elif command -v python3 >/dev/null 2>&1; then
  lower_stream() { python3 -c 'import sys; sys.stdout.write(sys.stdin.read().lower())'; }
else
  lower_stream() { awk '{ print tolower($0) }'; }
fi
to_lower() { printf '%s\n' "$1" | lower_stream; }
load_private_terms() {
  [ "$TERMS_LOADED" -eq 1 ] && return 0
  TERMS_LOADED=1
  local list
  if ! list="$(hygiene_private_terms_path "$REPO_ROOT")"; then
    note "${HYGIENE_PRIVATE_TERMS_FILE} fehlt (auch kein \$HYGIENE_PRIVATE_TERMS und keine ~/.config/mysuricates/hygiene-private-terms.txt) — private Begriffe in Commit-Nachrichten werden nicht geprueft (uebersprungen)"
    return 0
  fi
  hygiene_private_terms_load "$list"
  local i
  for i in "${!HYGIENE_TERMS[@]}"; do TERMS_LC[$i]="$(to_lower "${HYGIENE_TERMS[$i]}")"; done
}

# ---------------------------------------------------------------------------
# 1 — Commit metadata
# ---------------------------------------------------------------------------
check_email() {  # $1 = label, $2 = role, $3 = address
  [[ "$3" =~ $HYGIENE_COMMIT_EMAIL_REGEX ]] && return 0
  fail "${1}: ${2}-Adresse ist keine GitHub-noreply-Adresse — git config user.email '<id>+<login>@users.noreply.github.com', dann git commit --amend --reset-author"
}

check_message() {  # $1 = label, $2 = message (multi-line)
  local label="$1" line content lc i lc_term entry pat desc
  load_private_terms
  # Original line (fd 3) and lowercased line (fd 4) in parallel, folded once per message.
  while IFS= read -r line <&3 && IFS= read -r lc <&4; do
    [[ "$line" =~ $HYGIENE_COMMIT_TRAILER_REGEX ]] && continue
    case "$line" in '#'*) continue ;; esac   # comment lines of the commit template
    for entry in "${HYGIENE_PATTERNS[@]}"; do
      pat="$(hygiene_pattern_of "$entry")"
      desc="$(hygiene_desc_of "$entry")"
      case "$line" in *"$pat"*) fail "${label}: Nachricht enthaelt '${pat}' (${desc})" ;; esac
    done
    for i in "${!HYGIENE_TERMS[@]}"; do
      lc_term="${TERMS_LC[$i]}"
      case "$lc" in
        *"$lc_term"*)
          if [ "${HYGIENE_TERM_WEAK[$i]}" -eq 1 ]; then
            warn "${label}: Nachricht enthaelt einen privaten Begriff, schwach (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste) — zaehlt LOW, blockiert nicht"
          else
            fail "${label}: Nachricht enthaelt einen privaten Begriff (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste)"
          fi
          ;;
      esac
    done
    if [[ "$line" =~ $HYGIENE_EMAIL_REGEX ]]; then
      fail "${label}: Nachricht enthaelt eine Mailadresse ausserhalb eines Trailers"
    fi
  done 3<<<"$2" 4< <(printf '%s\n' "$2" | lower_stream)
}

# Subject = first line that is neither empty nor a comment of the commit template.
check_subject() {  # $1 = label, $2 = message (multi-line)
  local line subject=""
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    subject="$line"; break
  done <<<"$2"
  [ -z "$subject" ] && return 0
  [[ "$subject" =~ $HYGIENE_SUBJECT_EXEMPT_REGEX ]] && return 0
  [ "${#subject}" -le "$HYGIENE_SUBJECT_MAX" ] && return 0
  fail "${1}: Betreff hat ${#subject} Zeichen (hoechstens ${HYGIENE_SUBJECT_MAX}) — das TODO gehoert als Trailer 'Todo: <uuid> (<Titel>)' ans Ende, Einzelheiten in den Body"
}

check_commits() {  # $@ = rev-list arguments
  KIND="meta"
  local c short ae ce at body n=0
  while IFS= read -r c; do
    [ -z "$c" ] && continue
    n=$((n + 1))
    short="$(git rev-parse --short "$c")"
    ae="$(git log -1 --format=%ae "$c")"
    ce="$(git log -1 --format=%ce "$c")"
    body="$(git log -1 --format=%B "$c")"
    at="$(git log -1 --format=%at "$c")"
    check_email "commit ${short}" "Autor" "$ae"
    check_email "commit ${short}" "Committer" "$ce"
    check_message "commit ${short}" "$body"
    [ "$at" -ge "$HYGIENE_SUBJECT_SINCE" ] && check_subject "commit ${short}" "$body"
  done < <(git rev-list "$@" 2>/dev/null)
  note "${n} Commit(s) geprueft"
}

# ---------------------------------------------------------------------------
# 2 — Hygiene on the range, 4 — gitleaks, 5 — build check
# ---------------------------------------------------------------------------
check_hygiene_range() {  # $1 = A..B
  KIND="hygiene"
  local out rc warns n
  if [ ! -f "$HYGIENE_GATE" ]; then
    echo "Pre-Push-Gate: Hygiene-Gate fehlt: $HYGIENE_GATE" >&2
    exit 2
  fi
  out="$(bash "$HYGIENE_GATE" --range "$1" 2>&1)"; rc=$?
  case "$rc" in
    0)
      # Warnings (e.g. a weak private term) do not block, but should not
      # disappear in the OK — otherwise nobody sees them before the push.
      warns="$(printf '%s\n' "$out" | grep -E '^(  ! |::warning)' || true)"
      if [ -n "$warns" ]; then
        n="$(printf '%s\n' "$warns" | grep -c .)"
        printf '%s\n' "$warns" | sed 's/^/    /'
        WARN=$((WARN + n))
        note "Hygiene-Gate auf ${1}: OK — ${n} Warnung(en), kein Verstoss"
      else
        note "Hygiene-Gate auf ${1}: OK"
      fi
      ;;
    1) printf '%s\n' "$out" | sed 's/^/    /'; fail "Hygiene-Gate auf ${1} meldet Verstoesse" ;;
    *) printf '%s\n' "$out" >&2; echo "Pre-Push-Gate: Hygiene-Gate nicht lauffaehig (exit ${rc})" >&2; exit 2 ;;
  esac
}

check_gitleaks() {  # $1 = A..B
  KIND="gitleaks"
  local out rc
  if ! command -v gitleaks >/dev/null 2>&1; then
    note "gitleaks ist nicht installiert — Secret-Scan des Bereichs uebersprungen (die CI scannt mit gitleaks-action)"
    return 0
  fi
  # From 8.19 the subcommand is called "git", older versions only know "detect".
  out="$(gitleaks git --no-banner --redact --log-opts="$1" . 2>&1)"; rc=$?
  if [ "$rc" -gt 1 ]; then
    out="$(gitleaks detect --no-banner --redact --source . --log-opts="$1" 2>&1)"; rc=$?
  fi
  case "$rc" in
    0) note "gitleaks auf ${1}: keine Funde" ;;
    1) printf '%s\n' "$out" | sed 's/^/    /'; fail "gitleaks meldet Funde im Bereich ${1}" ;;
    *) printf '%s\n' "$out" >&2; fail "gitleaks konnte den Bereich ${1} nicht scannen (exit ${rc})" ;;
  esac
}

# ---------------------------------------------------------------------------
# 4b — Exception files in the range (TODO e8257473)
#
# Gate and CI read their exceptions from the repo itself. On 2026-09-30 a push released
# gitleaks false alarms via .gitleaksignore — agreed upon, but the second push went through smoothly
# without any hint. A change to these files is therefore not a violation, but
# a decision a human makes for exactly this state. The switch takes the
# hash of the range tip, not just "1": a permanently exported env thus does not cover a
# later push with new loosenings.
# ---------------------------------------------------------------------------
is_rule_file() {  # $1 = Pfad
  case "$1" in
    .gitleaksignore|.gitleaks.toml) return 0 ;;
    scripts/lib/hygiene-rules.sh) return 0 ;;
    scripts/ci/pre-push-checks.sh|scripts/ci/public-hygiene.sh) return 0 ;;
    # Heads the list of paths that backend/frontend build and tests leave out — locally as in the CI.
    scripts/ci/changed-scopes.sh) return 0 ;;
    scripts/git-hooks/*) return 0 ;;
    .github/workflows/ci.yml) return 0 ;;
  esac
  return 1
}

rules_allowed_for() {  # $1 = voller Hash der Bereichsspitze
  local v
  local IFS=','
  for v in ${MYSURICATES_PREPUSH_ALLOW_RULES:-}; do
    v="$(to_lower "${v// /}")"
    [ "${#v}" -ge 7 ] || continue
    case "$1" in "$v"*) return 0 ;; esac
  done
  return 1
}

check_rule_files() {  # $1 = A..B
  KIND="rules"
  local f files=() tip short stat changed
  # An error here is not "unchanged": then the gate would not know what it lets through.
  if ! changed="$(git -c core.quotePath=false diff --no-renames --name-only "$1" 2>/dev/null)"; then
    echo "Pre-Push-Gate: git diff ${1} fehlgeschlagen — geaenderte Ausnahmedateien nicht bestimmbar" >&2
    exit 2
  fi
  while IFS= read -r f; do
    [ -n "$f" ] && is_rule_file "$f" && files+=("$f")
  done <<<"$changed"
  if [ "${#files[@]}" -eq 0 ]; then
    note "Ausnahmedateien: im Bereich ${1} unveraendert"
    return 0
  fi
  tip="$(git rev-parse --verify -q "${1#*..}^{commit}")" || tip=""
  short="$(git rev-parse --short "${tip:-HEAD}" 2>/dev/null)"
  stat="$(git -c core.quotePath=false diff --no-renames --stat "$1" -- "${files[@]}" 2>/dev/null)"
  echo "  ┌─ Ausnahmedateien geaendert im Bereich ${1} — hieraus lesen Gate und CI ihre Ausnahmen:"
  printf '%s\n' "$stat" | sed 's/^/  │ /'
  echo "  └─ Pruefen: git diff ${1} -- ${files[*]}"
  if [ "$RULES_REPORT_ONLY" -eq 1 ]; then
    warn "Bereich ${1} aendert Ausnahmedateien (${files[*]}) — Diff pruefen, die CI blockiert das nicht"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
      {
        echo "### Pre-Push-Gate: Ausnahmedateien geaendert"
        echo ""
        echo "Der Bereich \`${1}\` aendert Dateien, aus denen Gate und CI ihre Ausnahmen lesen. Bitte den Diff pruefen:"
        echo ""
        for f in "${files[@]}"; do echo "- \`${f}\`"; done
        echo ""
      } >> "$GITHUB_STEP_SUMMARY"
    fi
    return 0
  fi
  if [ -n "$tip" ] && rules_allowed_for "$tip"; then
    warn "Ausnahmedateien im Bereich ${1} geaendert — FREIGEGEBEN per MYSURICATES_PREPUSH_ALLOW_RULES fuer ${short} (${files[*]})"
    return 0
  fi
  case ",${MYSURICATES_PREPUSH_SKIP:-}," in
    *,rules,*) note "MYSURICATES_PREPUSH_SKIP deckt geaenderte Ausnahmedateien nicht ab — Freigabe nur per MYSURICATES_PREPUSH_ALLOW_RULES" ;;
  esac
  fail "Bereich ${1} aendert Ausnahmedateien (${files[*]}) — nach Pruefung des Diffs freigeben: MYSURICATES_PREPUSH_ALLOW_RULES=${short} git push"
}

# Which suite a change needs is decided by the same script as in the CI
# (scripts/ci/changed-scopes.sh, TODO c60eb0e2): backend filtered the other way round — it builds unless
# every file demonstrably lies outside its reach (docs/, workflow-pool/, parts of the
# frontend and scripts/lib count among them) —, frontend also for templates/ and workflow-pool/.
# A list of our own here would drift from the CI: green locally what the CI builds, or vice versa.
SCOPES_SCRIPT="$REPO_ROOT/scripts/ci/changed-scopes.sh"
check_build() {  # $1 = A..B
  KIND="build"
  local files scopes frontend=0 backend=0 out rc
  if [ ! -f "$SCOPES_SCRIPT" ]; then
    echo "Pre-Push-Gate: Scope-Einordnung fehlt: $SCOPES_SCRIPT" >&2
    exit 2
  fi
  # core.quotePath=false: non-ASCII paths come as plain text, not as "docs/\303\244.md" —
  # otherwise changed-scopes.sh would classify them wrongly.
  if ! files="$(git -c core.quotePath=false diff --no-renames --name-only "$1" 2>/dev/null)"; then
    echo "Pre-Push-Gate: git diff ${1} fehlgeschlagen — Bau-Scope nicht bestimmbar" >&2
    exit 2
  fi
  if [ -z "$files" ]; then
    note "Bauprüfung: keine geaenderte Datei in ${1} — nichts zu bauen"
    return 0
  fi
  # Without GITHUB_* environment: changed-scopes.sh should only classify the list here.
  scopes="$(printf '%s\n' "$files" | env -u GITHUB_EVENT_NAME -u GITHUB_REF_TYPE bash "$SCOPES_SCRIPT" --files 2>&1)"
  printf '%s\n' "$scopes" | sed -n 's/^changed-scopes: /  – Scope: /p'
  grep -qx 'backend=true' <<<"$scopes" && backend=1
  grep -qx 'frontend=true' <<<"$scopes" && frontend=1
  if [ "$frontend" -eq 0 ] && [ "$backend" -eq 0 ]; then
    note "Bauprüfung: keine Datei in Reichweite der Backend- oder Frontend-Suite (changed-scopes.sh) — nichts zu bauen"
    return 0
  fi
  if [ "$frontend" -eq 1 ]; then
    if [ ! -d ai-automation-frontend/node_modules ]; then
      echo "Pre-Push-Gate: Frontend-Suite betroffen, aber ai-automation-frontend/node_modules fehlt — erst npm ci" >&2
      exit 2
    fi
    step "Frontend: Typpruefung und Lint (tsc --noEmit, npm run lint)"
    out="$(cd ai-automation-frontend && npx tsc --noEmit 2>&1 && npm run lint 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ]; then printf '%s\n' "$out" | tail -40 | sed 's/^/    /'; fail "Frontend: Typpruefung oder Lint schlagen fehl"; else note "Frontend: OK"; fi
  fi
  if [ "$backend" -eq 1 ]; then
    if ! command -v mvn >/dev/null 2>&1; then
      echo "Pre-Push-Gate: Backend-Suite betroffen, aber mvn fehlt im PATH" >&2
      exit 2
    fi
    step "Backend: Kompilieren inkl. Tests (mvn -q test-compile)"
    out="$(cd ai-automation-backend && mvn -q test-compile 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ]; then printf '%s\n' "$out" | tail -40 | sed 's/^/    /'; fail "Backend: test-compile schlaegt fehl"; else note "Backend: OK"; fi
  fi
}

# ---------------------------------------------------------------------------
# 3 — Existing gates (the same as in the CI job "Public Hygiene")
# ---------------------------------------------------------------------------
TREE_GATES=(
  "Doku-Zahlen gegen den Code|bash|scripts/public-readiness/doc-facts-check.sh"
  "Doku-Verweise und DE/EN-Gleichstand|python3|scripts/public-readiness/docs_parity_check.py"
  "Diagramm-Seiten gegen die Markdown-Quelle|python3|scripts/docs-diagrams.py|--check"
  "API-Endpunkte gegen docs/api-referenz.md (DE/EN)|bash|scripts/public-readiness/api-endpoint-diff.sh"
)
check_tree() {
  KIND="tree"
  local entry label runner script extra out rc
  for entry in "${TREE_GATES[@]}"; do
    IFS='|' read -r label runner script extra <<<"$entry"
    if [ ! -f "$script" ]; then
      echo "Pre-Push-Gate: Bestands-Gate '${label}' fehlt: ${script}" >&2
      exit 2
    fi
    # shellcheck disable=SC2086
    out="$("$runner" "$script" $extra 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ]; then
      printf '%s\n' "$out" | tail -30 | sed 's/^/    /'
      fail "${label}: ${script} meldet Befunde (exit ${rc})"
    else
      note "${label}: OK"
    fi
  done
}

# ---------------------------------------------------------------------------
# Determine ranges
# ---------------------------------------------------------------------------
RANGES=()
ranges_from_stdin() {
  local local_ref local_sha remote_ref remote_sha oldest base
  while read -r local_ref local_sha remote_ref remote_sha; do
    [ -z "${local_sha:-}" ] && continue
    [ "$local_sha" = "$ZERO" ] && { note "${remote_ref}: Loeschung — nichts zu pruefen"; continue; }
    if [ "${remote_sha:-$ZERO}" != "$ZERO" ] && git cat-file -e "${remote_sha}^{commit}" 2>/dev/null; then
      RANGES+=("${remote_sha}..${local_sha}")
      continue
    fi
    # New branch on the remote: everything that no remote ref knows yet.
    oldest="$(git rev-list "$local_sha" --not --remotes 2>/dev/null | tail -1)"
    if [ -z "$oldest" ]; then
      note "${local_ref}: kein neuer Commit gegenueber den Remotes — nichts zu pruefen"
      continue
    fi
    if base="$(git rev-parse --verify -q "${oldest}^")"; then
      RANGES+=("${base}..${local_sha}")
    else
      RANGES+=("$(git hash-object -t tree /dev/null)..${local_sha}")
    fi
  done
}

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
echo "Pre-Push-Gate (${MODE})"

case "$MODE" in
  message)
    [ -f "$MSG_FILE" ] || { echo "Pre-Push-Gate: Nachrichtendatei fehlt: '${MSG_FILE}'" >&2; exit 2; }
    KIND="meta"
    ident="$(git var GIT_AUTHOR_IDENT 2>/dev/null)"; ident="${ident#*<}"; check_email "dieser Commit" "Autor" "${ident%%>*}"
    ident="$(git var GIT_COMMITTER_IDENT 2>/dev/null)"; ident="${ident#*<}"; check_email "dieser Commit" "Committer" "${ident%%>*}"
    check_message "dieser Commit" "$(cat "$MSG_FILE")"
    check_subject "dieser Commit" "$(cat "$MSG_FILE")"
    ;;
  tree)
    step "Bestands-Gates"
    check_tree
    ;;
  hook|range)
    if [ "$MODE" = "hook" ]; then
      ranges_from_stdin
    else
      case "$RANGE" in *..*) RANGES=("$RANGE") ;; *) echo "--range erwartet A..B, bekommen: '${RANGE}'" >&2; exit 2 ;; esac
    fi
    if [ "${#RANGES[@]}" -eq 0 ]; then
      echo "Pre-Push-Gate OK — nichts zu pruefen"
      exit 0
    fi
    for r in "${RANGES[@]}"; do
      step "Commit-Bereich ${r}"
      check_commits "$r"
      check_hygiene_range "$r"
      check_gitleaks "$r"
      check_rule_files "$r"
    done
    if [ "$SKIP_TREE" -eq 1 ]; then
      note "Bestands-Gates uebersprungen (MYSURICATES_PREPUSH_SKIP bzw. --skip-tree) — die CI prueft sie"
    else
      step "Bestands-Gates"
      check_tree
    fi
    if [ "$SKIP_BUILD" -eq 1 ]; then
      note "Bauprüfung uebersprungen (MYSURICATES_PREPUSH_SKIP bzw. --skip-build) — die CI baut"
    else
      for r in "${RANGES[@]}"; do check_build "$r"; done
    fi
    ;;
esac

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "Pre-Push-Gate FAILED — ${COUNT} Verstoss/Verstoesse."
  case "$FAIL_KINDS" in *,meta,*)
    echo "Fix (Nachricht/Adresse): git commit --amend (nach git config user.email … mit --reset-author), aeltere Commits per git rebase -i; Literale und private Begriffe ersetzen." ;; esac
  case "$FAIL_KINDS" in *,hygiene,*)
    echo "Fix (Inhalt): die gemeldeten Zeilen in den Dateien korrigieren und neu committen — die Fundstellen stehen oben." ;; esac
  case "$FAIL_KINDS" in *,gitleaks,*)
    echo "Fix (Secret): Geheimnis aus der Historie entfernen (Commit umschreiben) und den Schluessel rotieren; ein Fehlalarm gehoert begruendet in .gitleaksignore — das ist dann eine Ausnahmedatei und braucht die Freigabe." ;; esac
  case "$FAIL_KINDS" in *,rules,*)
    echo "Fix (Ausnahmedateien): nichts zu reparieren, aber zu entscheiden — Diff lesen; passt er, gibt ein Mensch genau diesen Stand frei (Befehl oben). Kein Agent setzt den Schalter." ;; esac
  case "$FAIL_KINDS" in *,tree,*)
    echo "Fix (Bestands-Gates): das genannte Skript einzeln laufen lassen und Doku bzw. Diagramme nachziehen." ;; esac
  case "$FAIL_KINDS" in *,build,*)
    echo "Fix (Bau): den Fehler oben beheben, lokal pruefen (npx tsc --noEmit && npm run lint bzw. mvn -q test-compile), neu committen." ;; esac
  echo "Kein Bypass per Reflex — git push --no-verify nur, wenn der Befund bewusst geprueft ist."
  exit 1
fi
if [ "$WARN" -ne 0 ]; then
  echo "Pre-Push-Gate OK — ${WARN} Warnung(en), kein Verstoss."
else
  echo "Pre-Push-Gate OK"
fi
