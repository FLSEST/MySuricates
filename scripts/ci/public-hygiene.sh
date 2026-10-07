#!/usr/bin/env bash
#
# Public-Hygiene-Gate
#
# Checks what would end up in a public repository. Rules and path lists live
# in scripts/lib/hygiene-rules.sh and are read by this script and by the
# pre-commit hook together.
#
# Modi:
#   --tracked    (default) tracked files. This is the release question. Error.
#   --worktree   additionally the working tree: files that are neither tracked nor
#                ignored. Warning, not an error — locally you want to see this,
#                but not be blocked.
#   --staged     staged changes only. For the pre-commit hook. Error.
#   --range A..B only the changes of a commit range, the same checks as
#                --staged. For the pre-push gate (scripts/ci/pre-push-checks.sh). Error.
#
# Usage:  bash scripts/ci/public-hygiene.sh [--tracked|--worktree|--staged|--range A..B]
# Exit:   0 = clean (or warnings only), 1 = violations
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo root from the script path, not from git: in worktrees git exports GIT_DIR to
# hooks, and `git -C scripts/ci rev-parse --show-toplevel` then returns scripts/ci.
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

RULES_FILE="$REPO_ROOT/scripts/lib/hygiene-rules.sh"
if [ ! -f "$RULES_FILE" ]; then
  echo "Public-Hygiene-Gate: Regeldatei fehlt: $RULES_FILE" >&2
  exit 2
fi
# shellcheck source=../lib/hygiene-rules.sh
. "$RULES_FILE"

MODE="tracked"
RANGE=""
case "${1:-}" in
  --tracked)  MODE="tracked" ;;
  --worktree) MODE="worktree" ;;
  --staged)   MODE="staged" ;;
  --range)
    MODE="range"; RANGE="${2:-}"
    case "$RANGE" in
      *..*) ;;
      *) echo "--range erwartet einen Commit-Bereich A..B, bekommen: '${RANGE}'" >&2; exit 2 ;;
    esac
    ;;
  "")         ;;
  *) echo "Unbekannter Modus: $1" >&2; exit 2 ;;
esac

# Source of the change checks: the index (pre-commit) or a commit range (pre-push).
# Both modes ask the same question — what is NEW? — just at a different point.
if [ "$MODE" = "range" ]; then DIFF_SRC=("$RANGE"); else DIFF_SRC=(--cached); fi

FAIL=0
WARN=0
COUNT=0

# In CI as an annotation, otherwise human-readable.
annotate() {
  # $1=file, $2=line, $3=message
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error file=${1},line=${2}::${3}"
  else
    echo "  ✗ ${1}:${2} — ${3}" >&2
  fi
  FAIL=1
  COUNT=$((COUNT + 1))
}

warn() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::warning file=${1},line=${2}::${3}"
  else
    echo "  ! ${1}:${2} — ${3}" >&2
  fi
  WARN=$((WARN + 1))
}

# ---------------------------------------------------------------------------
# Check 1 — tracked files that should actually be ignored
#
# This used to be a hand-maintained list of ten directory names. The question
# can be asked completely: git knows the tracked files that fall under an
# ignore rule. That way every future ignore rule is covered automatically,
# without anyone having to update the list.
# ---------------------------------------------------------------------------
check_tracked_but_ignored() {
  local f
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    hygiene_is_allowlisted "$f" && continue
    annotate "$f" 1 "getrackte Datei faellt unter eine Ignore-Regel — bewusst? Dann in $HYGIENE_ALLOWLIST_FILE eintragen, sonst: git rm --cached '$f'"
  done < <(git ls-files --cached --ignored --exclude-standard 2>/dev/null || true)
}

# ---------------------------------------------------------------------------
# Check 2 — forbidden literals
# ---------------------------------------------------------------------------
check_patterns_tracked() {
  local entry pat desc file line
  for entry in "${HYGIENE_PATTERNS[@]}"; do
    pat="$(hygiene_pattern_of "$entry")"
    desc="$(hygiene_desc_of "$entry")"
    while IFS=: read -r file line _; do
      [ -z "$file" ] && continue
      hygiene_is_self_excluded "$file" && continue
      annotate "$file" "$line" "${desc}: '${pat}'"
    done < <(git grep -nI -F -- "$pat" 2>/dev/null || true)
  done
}

# Added lines of the change source as "file<TAB>content", once per run. A
# push range can have tens of thousands of lines — a bash loop per line and pattern
# took minutes for that, one grep per pattern takes fractions of a second.
# Sets ADDED_LINES_FILE — deliberately no command substitution: in its subshell
# the EXIT trap fired and deleted the file before a grep could read it.
ADDED_LINES_FILE=""
ensure_added_lines_file() {
  [ -n "$ADDED_LINES_FILE" ] && return 0
  ADDED_LINES_FILE="$(mktemp)"
  trap 'rm -f "$ADDED_LINES_FILE"' EXIT
  git diff "${DIFF_SRC[@]}" --no-color -U0 --diff-filter=AM 2>/dev/null \
    | awk 'index($0, "+++ b/") == 1 { f = substr($0, 7); next }
           index($0, "+++") == 1 { next }
           index($0, "+") == 1 { print f "\t" substr($0, 2) }' > "$ADDED_LINES_FILE"
}

check_patterns_staged() {
  local entry pat desc file
  ensure_added_lines_file
  for entry in "${HYGIENE_PATTERNS[@]}"; do
    pat="$(hygiene_pattern_of "$entry")"
    desc="$(hygiene_desc_of "$entry")"
    while IFS=$'\t' read -r file _; do
      [ -z "$file" ] && continue
      hygiene_is_self_excluded "$file" && continue
      annotate "$file" 1 "neue Zeile enthaelt '${pat}' (${desc})"
    done < <(grep -F -- "$pat" "$ADDED_LINES_FILE" 2>/dev/null || true)
  done
}

# Private terms in added lines (--staged/--range). The full-inventory run
# (check_private_terms) answers the release question; here the point is that no
# new hit is added — at commit time and once more before the push. A weak
# term (prefix low:, see hygiene_private_terms_load) only warns, as in the inventory.
check_private_terms_diff() {
  local list i file
  if ! list="$(hygiene_private_terms_path "$REPO_ROOT")"; then
    echo "  – ${HYGIENE_PRIVATE_TERMS_FILE} fehlt (auch kein \$HYGIENE_PRIVATE_TERMS und keine" \
         "~/.config/mysuricates/hygiene-private-terms.txt) — private Begriffe werden nicht geprueft" \
         "(uebersprungen)"
    return 0
  fi
  hygiene_private_terms_load "$list"
  ensure_added_lines_file
  for i in "${!HYGIENE_TERMS[@]}"; do
    while IFS=$'\t' read -r file _; do
      [ -z "$file" ] && continue
      hygiene_is_allowlisted "$file" && continue
      if [ "${HYGIENE_TERM_WEAK[$i]}" -eq 1 ]; then
        warn "$file" 1 "neue Zeile enthaelt einen privaten Begriff, schwach (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste) — zaehlt LOW, blockiert nicht"
      else
        annotate "$file" 1 "neue Zeile enthaelt einen privaten Begriff (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste)"
      fi
    done < <(grep -iF -- "${HYGIENE_TERMS[$i]}" "$ADDED_LINES_FILE" 2>/dev/null || true)
  done
}

# Staged files that fall under an ignore rule or look like a backup.
check_staged_files() {
  local f g
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    if git check-ignore -q "$f" 2>/dev/null && ! hygiene_is_allowlisted "$f"; then
      annotate "$f" 1 "ignorierte Datei wird gestaged (unstage: git restore --staged '$f')"
    fi
    for g in "${HYGIENE_BACKUP_GLOBS[@]}"; do
      # shellcheck disable=SC2254
      case "$f" in
        $g) annotate "$f" 1 "Sicherungsdatei wird gestaged — gehoert nicht ins Repository" ;;
      esac
    done
  done < <(git diff "${DIFF_SRC[@]}" --name-only --diff-filter=A 2>/dev/null || true)
}

# ---------------------------------------------------------------------------
# Check 3 — pool neutrality (separate script, also used by the built-in template)
# ---------------------------------------------------------------------------
check_pool() {
  local out rc reported=0
  out="$(bash "$REPO_ROOT/scripts/ci/pool-neutrality.sh" 2>&1)"
  rc=$?
  [ "$rc" -eq 0 ] && return 0
  while IFS=$'\t' read -r file tag desc; do
    [ -z "${file:-}" ] && continue
    case "$file" in
      *.json) annotate "$file" 1 "[$tag] $desc"; reported=1 ;;
    esac
  done < <(printf '%s\n' "$out")
  # An abort of the sub-script (exit 2 "Regeldatei fehlt", Python traceback, missing
  # python3) yields no TAB lines with .json — it used to vanish silently here and the
  # gate reported "OK". A run without usable finding lines is therefore an error itself.
  if [ "$reported" -eq 0 ]; then
    annotate "scripts/ci/pool-neutrality.sh" 1 \
      "Pool-Neutralitaet abgebrochen (exit ${rc}) ohne verwertbare Befundzeilen: $(printf '%s' "$out" | tr '\n' ' ')"
  fi
}

# ---------------------------------------------------------------------------
# Check 4 — repository weight
# ---------------------------------------------------------------------------
check_weight() {
  python3 - "$HYGIENE_MAX_BLOB_BYTES" "$HYGIENE_DUP_MIN_BYTES" <<'PY'
import subprocess, sys, collections
max_bytes, dup_min = int(sys.argv[1]), int(sys.argv[2])
ls = subprocess.run(['git','ls-files','-s'], capture_output=True, text=True).stdout
entries = []
for line in ls.splitlines():
    meta, _, path = line.partition('\t')
    parts = meta.split()
    if len(parts) >= 2:
        entries.append((parts[1], path))
if not entries:
    sys.exit(0)
blobs = sorted({b for b, _ in entries})
sizes = {}
proc = subprocess.run(['git','cat-file','--batch-check=%(objectname) %(objectsize)'],
                      input='\n'.join(blobs), capture_output=True, text=True)
for line in proc.stdout.splitlines():
    p = line.split()
    if len(p) == 2:
        sizes[p[0]] = int(p[1])
by_blob = collections.defaultdict(list)
for b, p in entries:
    by_blob[b].append(p)
for b, paths in sorted(by_blob.items()):
    size = sizes.get(b, 0)
    if len(paths) > 1 and size >= dup_min:
        print(f"DUP\t{paths[0]}\t{size}\tidentischer Inhalt auch unter: {', '.join(paths[1:])}")
    if size > max_bytes:
        print(f"BIG\t{paths[0]}\t{size}\tgetrackte Datei ueber Schwellwert")
PY
}

# ---------------------------------------------------------------------------
# Check 5 — neither tracked nor ignored (--worktree only)
# ---------------------------------------------------------------------------
check_worktree_strays() {
  local f g
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    for g in "${HYGIENE_BACKUP_GLOBS[@]}"; do
      # shellcheck disable=SC2254
      case "$f" in
        $g) warn "$f" 1 "Sicherungsdatei weder getrackt noch ignoriert — bei einem pauschalen 'git add' ginge sie mit"
            continue 2 ;;
      esac
    done
  done < <(git ls-files --others --exclude-standard 2>/dev/null || true)
}


# ---------------------------------------------------------------------------
# Check 6 — .gitignore does not reveal private file names
#
# .gitignore is tracked and is published along with the repository. Whatever is in it
# becomes public — comments included. Private definitions therefore belong in
# .git/info/exclude, which stays local.
#
# This check takes every entry from .git/info/exclude and looks whether its
# file name appears anywhere in .gitignore. CI has no exclude file;
# the check is then silently skipped.
# ---------------------------------------------------------------------------
check_gitignore_leaks() {
  local exclude=".git/info/exclude"
  [ -f "$exclude" ] || return 0
  [ -f ".gitignore" ] || return 0
  local entry base line
  while IFS= read -r entry; do
    case "$entry" in ''|'#'*) continue ;; esac
    base="$(basename "${entry%/}")"
    # Patterns like *.bak or very short names are no good as a signal.
    case "$base" in *'*'*|*'?'*|*'['*) continue ;; esac
    [ "${#base}" -lt 6 ] && continue
    hygiene_gitignore_leak_is_generic "$base" && continue
    line="$(grep -nF -- "$base" .gitignore | head -1 | cut -d: -f1)"
    [ -n "$line" ] && annotate ".gitignore" "$line" "nennt '$base' — der Name steht in $exclude und wird durch .gitignore oeffentlich"
  done < "$exclude"
}

# ---------------------------------------------------------------------------
# Check 7 — SECURITY.md: present, no private contact, real reporting channel
#
# What a visitor finds as the reporting channel for vulnerabilities should be GitHub's
# private security advisories, not an email address — otherwise a personal-data
# question arises again. Tracked files only, see hygiene-rules.sh.
# ---------------------------------------------------------------------------
check_security_md() {
  local f="$HYGIENE_SECURITY_FILE"
  local content
  if [ "$MODE" = "worktree" ]; then
    # --worktree deliberately describes untracked files too (see check_worktree_strays) — here
    # the working copy is the right source.
    if [ ! -f "$f" ]; then
      annotate "$f" 1 "Datei fehlt — kein dokumentierter Meldeweg fuer Sicherheitsluecken"
      return
    fi
    content="$(cat -- "$f" 2>/dev/null)"
  else
    # --tracked is the release question: what lands in the repository. A file that only
    # sits on disk (not yet "git add"ed) does not count for that — otherwise the gate reports
    # OK although SECURITY.md is not tracked at all.
    if ! git ls-files --error-unmatch -- "$f" >/dev/null 2>&1; then
      annotate "$f" 1 "ist nicht getrackt — kein dokumentierter Meldeweg fuer Sicherheitsluecken im Bestand"
      return
    fi
    content="$(git show "HEAD:$f" 2>/dev/null)"
  fi
  local line
  line="$(grep -nE "$HYGIENE_SECURITY_EMAIL_REGEX" <<<"$content" | head -1 | cut -d: -f1)"
  if [ -n "$line" ]; then
    annotate "$f" "$line" "enthaelt eine E-Mail-Adresse — Meldeweg soll GitHub Private Vulnerability Reporting sein, keine Mailadresse"
  fi
  local term found=0
  for term in "${HYGIENE_SECURITY_REQUIRED_TERMS[@]}"; do
    if grep -qiF -- "$term" <<<"$content"; then
      found=1
      break
    fi
  done
  if [ "$found" -eq 0 ]; then
    annotate "$f" 1 "nennt keinen der erwarteten Begriffe (${HYGIENE_SECURITY_REQUIRED_TERMS[*]}) — Meldeweg unklar"
  fi
}

# ---------------------------------------------------------------------------
# Check 8 — symlinks that lead out of the repository
#
# git grep does not read symlink blobs; a tracked link with an absolute target
# (e.g. under the developer home) slips past check 2. Also, such a link
# points in every clone, worktree and run snapshot to the original
# checkout instead of into its own tree. Only relative targets that stay
# inside the repository are allowed.
# Output: LINK<TAB>path<TAB>target — one line per violation.
# ---------------------------------------------------------------------------
check_symlinks() {
  python3 - "$MODE" "$RANGE" <<'PY'
import subprocess, sys, posixpath
mode, rng = sys.argv[1], sys.argv[2]
links = []  # (path, blob reference)
if mode in ("staged", "range"):
    src = [rng] if mode == "range" else ['--cached']
    raw = subprocess.run(['git', 'diff', *src, '--raw', '--diff-filter=AM', '-z'],
                         capture_output=True, text=True).stdout
    fields = raw.split('\0')
    i = 0
    while i + 1 < len(fields) and fields[i]:
        meta, path = fields[i], fields[i + 1]
        i += 2
        parts = meta.lstrip(':').split()
        if len(parts) >= 2 and parts[1] == '120000':
            links.append((path, (rng.split('..', 1)[1] + ':' + path) if mode == "range" else ':' + path))
else:
    ls = subprocess.run(['git', 'ls-files', '-s', '-z'], capture_output=True, text=True).stdout
    for entry in ls.split('\0'):
        meta, _, path = entry.partition('\t')
        parts = meta.split()
        if len(parts) >= 2 and parts[0] == '120000':
            links.append((path, parts[1]))
for path, ref in links:
    target = subprocess.run(['git', 'cat-file', '-p', ref], capture_output=True, text=True).stdout
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
    if target.startswith('/') or resolved == '..' or resolved.startswith('../'):
        print(f"LINK\t{path}\t{target}")
PY
}

# ---------------------------------------------------------------------------
# Check 9 — pool files in the index against the private exclusion list
#
# .git/info/exclude keeps the personal definitions out of the repository. This
# only holds locally, though: exclude is not cloned, and in a fresh clone
# a blanket "git add -A" brings the files back. This is caught here —
# in all three modes, because git ls-files reads the index and so also sees
# staged files before the commit.
#
# The list itself is not in the repository — it would otherwise be exactly the
# plain-text name list that exclude is meant to avoid. Clones and worktrees find
# it via the fallback locations from hygiene_private_list_path. If it is missing, that is no
# error but an audibly skipped check (template: the .example file).
# Only hits under workflow-pool/ count, even if a pattern is broader:
# the rule answers the pool question, no other.
# ---------------------------------------------------------------------------
check_private_pool_list() {
  local list label
  if ! list="$(hygiene_private_list_path "$REPO_ROOT")"; then
    echo "  – ${HYGIENE_PRIVATE_LIST_FILE} fehlt (auch kein \$HYGIENE_PRIVATE_LIST und keine" \
         "~/.config/mysuricates/hygiene-private.txt) — getrackte Pool-Dateien werden nicht gegen" \
         "die private Liste geprueft (uebersprungen; Vorlage: ${HYGIENE_PRIVATE_LIST_FILE}.example)"
    return 0
  fi
  # Annotations name the file that was actually read — relative within the repo.
  label="${list#"$REPO_ROOT/"}"
  local pattern f out rc lineno=0
  # "|| [ -n "$pattern" ]" picks up the last line when the file ends without
  # a newline — otherwise a one-line list is a silent no-op.
  while IFS= read -r pattern || [ -n "$pattern" ]; do
    lineno=$((lineno + 1))
    pattern="${pattern%%#*}"
    pattern="${pattern#"${pattern%%[![:space:]]*}"}"
    pattern="${pattern%"${pattern##*[![:space:]]}"}"
    [ -z "$pattern" ] && continue
    # The patterns come from .git/info/exclude and are often anchored there with a leading
    # "/" to the repo root. As a pathspec git chokes on that
    # ("Invalid path '/workflow-pool'"), so strip it here.
    pattern="${pattern#/}"
    out="$(git ls-files -- "$pattern" 2>&1)"
    rc=$?
    # A pattern that git cannot resolve is an error and no reason
    # to silently check nothing: "|| true" used to swallow this case.
    if [ "$rc" -ne 0 ]; then
      annotate "$label" "$lineno" \
        "Muster '${pattern}' ist keine gueltige Pfadangabe (git ls-files exit ${rc}): $(printf '%s' "$out" | tr '\n' ' ')"
      continue
    fi
    while IFS= read -r f; do
      case "$f" in
        workflow-pool/*) ;;
        *) continue ;;
      esac
      annotate "$f" 1 "Pool-Definition im Index passt auf ein Muster aus ${label} — gehoert nicht ins oeffentliche Repository: git rm --cached '$f'"
    done <<<"$out"
  done < "$list"
}

# ---------------------------------------------------------------------------
# Check 10 — terms from the private term list in the tracked files
#
# Names of private projects, employers or people cannot be kept as a
# literal in hygiene-rules.sh without publishing them thereby. The
# list therefore lives outside the repository (hygiene_private_terms_path).
# Here the inventory (--tracked/--worktree): the question is the state before
# publication. Added lines are checked by check_private_terms_diff (--staged/
# --range). The message names the list line, not the term — the output
# goes into the evidence report and the audit. A weak term (prefix low:)
# is a warning "privater Begriff, schwach": visible, but not a violation.
# ---------------------------------------------------------------------------
check_private_terms() {
  local list i file line
  if ! list="$(hygiene_private_terms_path "$REPO_ROOT")"; then
    echo "  – ${HYGIENE_PRIVATE_TERMS_FILE} fehlt (auch kein \$HYGIENE_PRIVATE_TERMS und keine" \
         "~/.config/mysuricates/hygiene-private-terms.txt) — private Begriffe werden nicht geprueft" \
         "(uebersprungen)"
    return 0
  fi
  hygiene_private_terms_load "$list"
  for i in "${!HYGIENE_TERMS[@]}"; do
    while IFS=: read -r file line _; do
      [ -z "$file" ] && continue
      hygiene_is_allowlisted "$file" && continue
      if [ "${HYGIENE_TERM_WEAK[$i]}" -eq 1 ]; then
        warn "$file" "$line" "privater Begriff, schwach (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste) — zaehlt LOW, blockiert nicht; bei Gelegenheit neutralisieren"
      else
        annotate "$file" "$line" "privater Begriff (Zeile ${HYGIENE_TERM_LINES[$i]} der privaten Begriffsliste) — neutralisieren oder die Datei aus dem Bestand nehmen"
      fi
    done < <(git grep -nIi -F -- "${HYGIENE_TERMS[$i]}" 2>/dev/null || true)
  done
}

# Report violations from check_symlinks as errors.
report_symlinks() {
  local kind path target
  while IFS=$'\t' read -r kind path target; do
    [ "${kind:-}" = "LINK" ] || continue
    annotate "$path" 1 "Symlink zeigt aus dem Repository hinaus: '${target}' — nur relative Ziele innerhalb des Repos"
  done < <(check_symlinks)
}

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
echo "Public-Hygiene-Gate (${MODE})"

if [ "$MODE" = "staged" ] || [ "$MODE" = "range" ]; then
  check_staged_files
  check_patterns_staged
  check_private_terms_diff
  # The pre-commit moment is exactly this scenario: a blanket "git add -A" has just
  # pulled the private definitions into the index. git ls-files reads the
  # index, so the check sees them before the commit.
  check_private_pool_list
  report_symlinks
else
  check_tracked_but_ignored
  check_patterns_tracked
  check_pool
  while IFS=$'\t' read -r kind path size desc; do
    [ -z "${kind:-}" ] && continue
    case "$kind" in
      DUP) annotate "$path" 1 "Dublette ($((size / 1024)) KiB): $desc" ;;
      BIG) warn     "$path" 1 "$desc ($((size / 1024 / 1024)) MiB) — gehoert das ins Repository?" ;;
    esac
  done < <(check_weight)
  check_gitignore_leaks
  check_private_pool_list
  check_private_terms
  check_security_md
  report_symlinks
  [ "$MODE" = "worktree" ] && check_worktree_strays
fi

if [ "$FAIL" -ne 0 ]; then
  if [ "$MODE" = "worktree" ]; then
    echo ""
    echo "Public-Hygiene-Gate: ${COUNT} Verstoss/Verstoesse, ${WARN} Warnung(en)."
    exit 1
  fi
  echo ""
  echo "Public-Hygiene-Gate FAILED — ${COUNT} Verstoss/Verstoesse, ${WARN} Warnung(en)."
  echo "Fix: getrackte Artefakte entfernen (git rm --cached), Literale ersetzen,"
  echo "bewusste Ausnahmen mit Begruendung in ${HYGIENE_ALLOWLIST_FILE} eintragen."
  exit 1
fi

if [ "$WARN" -ne 0 ]; then
  echo "Public-Hygiene-Gate OK — ${WARN} Warnung(en), kein Verstoss."
else
  echo "Public-Hygiene-Gate OK"
fi
