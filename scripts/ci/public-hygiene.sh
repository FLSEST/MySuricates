#!/usr/bin/env bash
#
# Public-Hygiene-Gate
#
# Prüft, was in ein öffentliches Repository geriete. Regeln und Pfadlisten stehen
# in scripts/lib/hygiene-rules.sh und werden von diesem Skript und vom
# pre-commit-Hook gemeinsam gelesen.
#
# Modi:
#   --tracked    (Default) getrackter Bestand. Das ist die Release-Frage. Fehler.
#   --worktree   zusätzlich der Arbeitsbaum: Dateien, die weder getrackt noch
#                ignoriert sind. Warnung, kein Fehler — lokal will man das sehen,
#                aber nicht blockiert werden.
#   --staged     nur gestagte Änderungen. Für den Hook. Fehler.
#
# Usage:  bash scripts/ci/public-hygiene.sh [--tracked|--worktree|--staged]
# Exit:   0 = sauber (bzw. nur Warnungen), 1 = Verstöße
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo-Wurzel aus dem Skriptpfad, nicht aus git: in Worktrees exportiert git GIT_DIR an
# Hooks, und `git -C scripts/ci rev-parse --show-toplevel` liefert dann scripts/ci.
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
case "${1:-}" in
  --tracked)  MODE="tracked" ;;
  --worktree) MODE="worktree" ;;
  --staged)   MODE="staged" ;;
  "")         ;;
  *) echo "Unbekannter Modus: $1" >&2; exit 2 ;;
esac

FAIL=0
WARN=0
COUNT=0

# In der CI als Annotation, sonst lesbar.
annotate() {
  # $1=datei, $2=zeile, $3=meldung
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
# Check 1 — getrackte Dateien, die eigentlich ignoriert sind
#
# Früher stand hier eine handgepflegte Liste von zehn Verzeichnisnamen. Die Frage
# lässt sich vollständig stellen: git kennt die getrackten Dateien, die unter eine
# Ignore-Regel fallen. Damit ist jede künftige Ignore-Regel automatisch mit
# abgedeckt, ohne dass jemand die Liste nachzieht.
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
# Check 2 — verbotene Literale
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

# ---------------------------------------------------------------------------
# Check 2b — verbotene Literale, aber nur in *.md (TODO 994af971)
#
# Eigene Funktion statt Wiederverwendung von check_patterns_tracked/_staged:
# die Suche bleibt auf *.md beschraenkt, damit Test-Fixtures fuer dieses Gate
# (z. B. in scripts/tests/test-public-hygiene.sh, keine .md-Datei) den
# Stempel als Beispieltext verwenden duerfen, ohne selbst anzuschlagen.
# ---------------------------------------------------------------------------
check_md_patterns_tracked() {
  local entry pat desc file line
  for entry in "${HYGIENE_MD_PATTERNS[@]}"; do
    pat="$(hygiene_pattern_of "$entry")"
    desc="$(hygiene_desc_of "$entry")"
    while IFS=: read -r file line _; do
      [ -z "$file" ] && continue
      annotate "$file" "$line" "${desc}: '${pat}'"
    done < <(git grep -nI -F -- "$pat" -- '*.md' 2>/dev/null || true)
  done
}

check_md_patterns_staged() {
  local current_file="" line content entry pat desc
  while IFS= read -r line; do
    case "$line" in
      "+++ b/"*) current_file="${line#+++ b/}"; continue ;;
      "+++"*)    continue ;;
    esac
    case "$current_file" in *.md) ;; *) continue ;; esac
    case "$line" in
      +*)
        content="${line:1}"
        for entry in "${HYGIENE_MD_PATTERNS[@]}"; do
          pat="$(hygiene_pattern_of "$entry")"
          desc="$(hygiene_desc_of "$entry")"
          case "$content" in
            *"$pat"*) annotate "$current_file" 1 "neue Zeile enthaelt '${pat}' (${desc})" ;;
          esac
        done
        ;;
    esac
  done < <(git diff --cached --no-color -U0 --diff-filter=AM 2>/dev/null || true)
}

check_patterns_staged() {
  local current_file="" line content entry pat desc
  while IFS= read -r line; do
    case "$line" in
      "+++ b/"*) current_file="${line#+++ b/}"; continue ;;
      "+++"*)    continue ;;
    esac
    hygiene_is_self_excluded "$current_file" && continue
    case "$line" in
      +*)
        content="${line:1}"
        for entry in "${HYGIENE_PATTERNS[@]}"; do
          pat="$(hygiene_pattern_of "$entry")"
          desc="$(hygiene_desc_of "$entry")"
          case "$content" in
            *"$pat"*) annotate "$current_file" 1 "neue Zeile enthaelt '${pat}' (${desc})" ;;
          esac
        done
        ;;
    esac
  done < <(git diff --cached --no-color -U0 --diff-filter=AM 2>/dev/null || true)
}

# Gestagte Dateien, die unter eine Ignore-Regel fallen oder wie eine Sicherung aussehen.
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
  done < <(git diff --cached --name-only --diff-filter=A 2>/dev/null || true)
}

# ---------------------------------------------------------------------------
# Check 3 — Pool-Neutralität (eigenes Skript, auch vom Built-in-Template genutzt)
# ---------------------------------------------------------------------------
check_pool() {
  local out
  if out="$(bash "$REPO_ROOT/scripts/ci/pool-neutrality.sh" 2>&1)"; then
    return 0
  fi
  while IFS=$'\t' read -r file tag desc; do
    [ -z "${file:-}" ] && continue
    case "$file" in *.json) annotate "$file" 1 "[$tag] $desc" ;; esac
  done < <(printf '%s\n' "$out")
}

# ---------------------------------------------------------------------------
# Check 4 — Repo-Gewicht
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
# Check 5 — weder getrackt noch ignoriert (nur --worktree)
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
# Check 6 — .gitignore verraet keine privaten Dateinamen
#
# .gitignore ist getrackt und geht mit der Veroeffentlichung mit. Was dort steht,
# wird oeffentlich — auch in Kommentaren. Private Definitionen gehoeren deshalb
# nach .git/info/exclude, das lokal bleibt.
#
# Diese Pruefung nimmt jeden Eintrag aus .git/info/exclude und schaut, ob sein
# Dateiname irgendwo in .gitignore auftaucht. In der CI gibt es die exclude-Datei
# nicht; dann faellt die Pruefung still aus.
# ---------------------------------------------------------------------------
check_gitignore_leaks() {
  local exclude=".git/info/exclude"
  [ -f "$exclude" ] || return 0
  [ -f ".gitignore" ] || return 0
  local entry base line
  while IFS= read -r entry; do
    case "$entry" in ''|'#'*) continue ;; esac
    base="$(basename "${entry%/}")"
    # Muster wie *.bak oder sehr kurze Namen taugen nicht als Signal.
    case "$base" in *'*'*|*'?'*|*'['*) continue ;; esac
    [ "${#base}" -lt 6 ] && continue
    line="$(grep -nF -- "$base" .gitignore | head -1 | cut -d: -f1)"
    [ -n "$line" ] && annotate ".gitignore" "$line" "nennt '$base' — der Name steht in $exclude und wird durch .gitignore oeffentlich"
  done < "$exclude"
}

# ---------------------------------------------------------------------------
# Check 7 — SECURITY.md: vorhanden, kein privater Kontakt, echter Meldeweg
#
# Was ein Besucher als Meldeweg fuer Sicherheitsluecken vorfindet, soll GitHubs
# private Sicherheitshinweise sein, keine Mailadresse — sonst entsteht wieder
# eine Personendaten-Frage. Nur getrackter Bestand, siehe hygiene-rules.sh.
# ---------------------------------------------------------------------------
check_security_md() {
  local f="$HYGIENE_SECURITY_FILE"
  local content
  if [ "$MODE" = "worktree" ]; then
    # --worktree beschreibt bewusst auch Ungetracktes (siehe check_worktree_strays) — hier
    # ist die Arbeitskopie die richtige Quelle.
    if [ ! -f "$f" ]; then
      annotate "$f" 1 "Datei fehlt — kein dokumentierter Meldeweg fuer Sicherheitsluecken"
      return
    fi
    content="$(cat -- "$f" 2>/dev/null)"
  else
    # --tracked ist die Release-Frage: was landet im Repository. Eine Datei, die nur auf
    # Platte liegt (noch nicht "git add"ed), zaehlt dafuer nicht — sonst meldet das Gate
    # OK, obwohl SECURITY.md gar nicht getrackt ist.
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
# Check 8 — Symlinks, die aus dem Repository hinausfuehren
#
# git grep liest keine Symlink-Blobs; ein getrackter Link mit absolutem Ziel
# (z. B. unter dem Entwickler-Home) rutscht an Check 2 vorbei. Ausserdem zeigt
# so ein Link in jedem Clone, Worktree und Run-Snapshot auf den Original-
# Checkout statt in den eigenen Baum. Erlaubt sind nur relative Ziele, die
# innerhalb des Repositories bleiben.
# Ausgabe: LINK<TAB>pfad<TAB>ziel — je Verstoss eine Zeile.
# ---------------------------------------------------------------------------
check_symlinks() {
  python3 - "$MODE" <<'PY'
import subprocess, sys, posixpath
mode = sys.argv[1]
links = []  # (pfad, blob-referenz)
if mode == "staged":
    raw = subprocess.run(['git', 'diff', '--cached', '--raw', '--diff-filter=AM', '-z'],
                         capture_output=True, text=True).stdout
    fields = raw.split('\0')
    i = 0
    while i + 1 < len(fields) and fields[i]:
        meta, path = fields[i], fields[i + 1]
        i += 2
        parts = meta.lstrip(':').split()
        if len(parts) >= 2 and parts[1] == '120000':
            links.append((path, ':' + path))
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

# Verstoesse aus check_symlinks als Fehler melden.
report_symlinks() {
  local kind path target
  while IFS=$'\t' read -r kind path target; do
    [ "${kind:-}" = "LINK" ] || continue
    annotate "$path" 1 "Symlink zeigt aus dem Repository hinaus: '${target}' — nur relative Ziele innerhalb des Repos"
  done < <(check_symlinks)
}

# ---------------------------------------------------------------------------
# Lauf
# ---------------------------------------------------------------------------
echo "Public-Hygiene-Gate (${MODE})"

if [ "$MODE" = "staged" ]; then
  check_staged_files
  check_patterns_staged
  check_md_patterns_staged
  report_symlinks
else
  check_tracked_but_ignored
  check_patterns_tracked
  check_md_patterns_tracked
  check_pool
  while IFS=$'\t' read -r kind path size desc; do
    [ -z "${kind:-}" ] && continue
    case "$kind" in
      DUP) annotate "$path" 1 "Dublette ($((size / 1024)) KiB): $desc" ;;
      BIG) warn     "$path" 1 "$desc ($((size / 1024 / 1024)) MiB) — gehoert das ins Repository?" ;;
    esac
  done < <(check_weight)
  check_gitignore_leaks
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
