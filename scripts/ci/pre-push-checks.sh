#!/usr/bin/env bash
#
# Pre-Push-Gate
#
# Was sich vor einem Push lokal pruefen laesst — deterministisch, ohne Urteil, in Sekunden
# bis zu einer Minute:
#   1. Commit-Metadaten des Push-Bereichs: Autor und Committer tragen nur die
#      GitHub-noreply-Form; die Nachricht enthaelt keine verbotenen Literale, keine privaten
#      Begriffe (schwache, Praefix low:, nur als Warnung) und keine Mailadresse ausserhalb
#      eines Trailers.
#   2. Hygiene-Gate auf den Push-Bereich (public-hygiene.sh --range): was NEU dazukommt.
#   3. Bestands-Gates der CI, die vom Code abhaengen: Doku-Zahlen, Doku-Verweise mit
#      DE/EN-Gleichstand, Diagramm-Drift.
#   4. gitleaks auf den Bereich — nur wenn installiert, sonst hoerbar uebersprungen.
#      Es wird nichts nachinstalliert.
#   5. Bauprüfung nach Scope (dieselbe Einordnung wie die CI, scripts/ci/changed-scopes.sh):
#      Frontend-Suite betroffen -> tsc + lint; Backend-Suite betroffen -> mvn test-compile.
#
# Nicht hier: die volle Testsuite (CI und die Commit-Regel in CLAUDE.md), der getrackte
# Bestand (public-hygiene.sh --tracked in der CI und in der Kette „Public Readiness") und
# alles, was ein Urteil braucht.
#
# Aufrufer: scripts/git-hooks/pre-push (--hook, Refs auf stdin), scripts/git-hooks/commit-msg
# (--message-file), CI (--tree und --range), von Hand (--range A..B).
#
# Usage: bash scripts/ci/pre-push-checks.sh --hook | --range A..B | --tree | --message-file <datei>
#                                           [--skip-tree] [--skip-build]
# Env:   MYSURICATES_PREPUSH_SKIP=tree,build — fuer die Hooks, die keine Argumente nehmen.
#        Jede Auslassung wird gemeldet; ein Gate, das nicht misst, ist nicht gruen.
# Exit:  0 = sauber, 1 = Verstoss oder Fehlschlag, 2 = Gate nicht lauffaehig
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo-Wurzel aus dem Skriptpfad, nicht aus git: in Worktrees exportiert git GIT_DIR an
# Hooks (siehe public-hygiene.sh).
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
while [ $# -gt 0 ]; do
  case "$1" in
    --hook)         MODE="hook" ;;
    --tree)         MODE="tree" ;;
    --range)        MODE="range"; RANGE="${2:-}"; shift ;;
    --message-file) MODE="message"; MSG_FILE="${2:-}"; shift ;;
    --skip-tree)    SKIP_TREE=1 ;;
    --skip-build)   SKIP_BUILD=1 ;;
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

fail() {  # $1 = meldung
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::${1}"; else echo "  ✗ ${1}" >&2; fi
  FAIL=1
  COUNT=$((COUNT + 1))
}
# Sichtbar, aber kein Verstoss — etwa ein schwacher privater Begriff (Praefix low:).
warn() {  # $1 = meldung
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::${1}"; else echo "  ! ${1}" >&2; fi
  WARN=$((WARN + 1))
}
note() { echo "  – $1"; }
step() { echo "▶ $1"; }

# ---------------------------------------------------------------------------
# Private Begriffsliste — einmal laden (Parser: hygiene_private_terms_load, derselbe wie im
# Hygiene-Gate), Meldungen nennen die Listenzeile, nie den Begriff.
# ---------------------------------------------------------------------------
TERMS_LOADED=0
load_private_terms() {
  [ "$TERMS_LOADED" -eq 1 ] && return 0
  TERMS_LOADED=1
  local list
  if ! list="$(hygiene_private_terms_path "$REPO_ROOT")"; then
    note "${HYGIENE_PRIVATE_TERMS_FILE} fehlt (auch kein \$HYGIENE_PRIVATE_TERMS und keine ~/.config/mysuricates/hygiene-private-terms.txt) — private Begriffe in Commit-Nachrichten werden nicht geprueft (uebersprungen)"
    return 0
  fi
  hygiene_private_terms_load "$list"
}

# ---------------------------------------------------------------------------
# 1 — Commit-Metadaten
# ---------------------------------------------------------------------------
check_email() {  # $1 = label, $2 = rolle, $3 = adresse
  [[ "$3" =~ $HYGIENE_COMMIT_EMAIL_REGEX ]] && return 0
  fail "${1}: ${2}-Adresse ist keine GitHub-noreply-Adresse — git config user.email '<id>+<login>@users.noreply.github.com', dann git commit --amend --reset-author"
}

check_message() {  # $1 = label, $2 = nachricht (mehrzeilig)
  local label="$1" line content lc i lc_term entry pat desc
  load_private_terms
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ $HYGIENE_COMMIT_TRAILER_REGEX ]] && continue
    case "$line" in '#'*) continue ;; esac   # Kommentarzeilen der Commit-Vorlage
    for entry in "${HYGIENE_PATTERNS[@]}"; do
      pat="$(hygiene_pattern_of "$entry")"
      desc="$(hygiene_desc_of "$entry")"
      case "$line" in *"$pat"*) fail "${label}: Nachricht enthaelt '${pat}' (${desc})" ;; esac
    done
    lc="${line,,}"
    for i in "${!HYGIENE_TERMS[@]}"; do
      lc_term="${HYGIENE_TERMS[$i],,}"
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
  done <<<"$2"
}

check_commits() {  # $@ = rev-list-Argumente
  local c short ae ce body n=0
  while IFS= read -r c; do
    [ -z "$c" ] && continue
    n=$((n + 1))
    short="$(git rev-parse --short "$c")"
    ae="$(git log -1 --format=%ae "$c")"
    ce="$(git log -1 --format=%ce "$c")"
    body="$(git log -1 --format=%B "$c")"
    check_email "commit ${short}" "Autor" "$ae"
    check_email "commit ${short}" "Committer" "$ce"
    check_message "commit ${short}" "$body"
  done < <(git rev-list "$@" 2>/dev/null)
  note "${n} Commit(s) geprueft"
}

# ---------------------------------------------------------------------------
# 2 — Hygiene auf den Bereich, 4 — gitleaks, 5 — Bauprüfung
# ---------------------------------------------------------------------------
check_hygiene_range() {  # $1 = A..B
  local out rc warns n
  if [ ! -f "$HYGIENE_GATE" ]; then
    echo "Pre-Push-Gate: Hygiene-Gate fehlt: $HYGIENE_GATE" >&2
    exit 2
  fi
  out="$(bash "$HYGIENE_GATE" --range "$1" 2>&1)"; rc=$?
  case "$rc" in
    0)
      # Warnungen (etwa ein schwacher privater Begriff) blockieren nicht, sollen aber nicht
      # im OK verschwinden — sonst sieht sie vor dem Push niemand.
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
  local out rc
  if ! command -v gitleaks >/dev/null 2>&1; then
    note "gitleaks ist nicht installiert — Secret-Scan des Bereichs uebersprungen (die CI scannt mit gitleaks-action)"
    return 0
  fi
  # 8.19+ heisst der Unterbefehl "git", aeltere Versionen kennen nur "detect".
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

# Welche Suite eine Aenderung braucht, entscheidet dasselbe Skript wie in der CI
# (scripts/ci/changed-scopes.sh, TODO c60eb0e2): Backend umgekehrt gefiltert — es baut, ausser
# jede Datei liegt nachweislich ausserhalb seiner Reichweite (docs/, workflow-pool/, Teile des
# Frontends und scripts/lib zaehlen dazu) —, Frontend auch bei templates/ und workflow-pool/.
# Eine eigene Liste hier liefe der CI davon: lokal gruen, was die CI baut, oder umgekehrt.
SCOPES_SCRIPT="$REPO_ROOT/scripts/ci/changed-scopes.sh"
check_build() {  # $1 = A..B
  local files scopes frontend=0 backend=0 out rc
  if [ ! -f "$SCOPES_SCRIPT" ]; then
    echo "Pre-Push-Gate: Scope-Einordnung fehlt: $SCOPES_SCRIPT" >&2
    exit 2
  fi
  if ! files="$(git diff --no-renames --name-only "$1" 2>/dev/null)"; then
    echo "Pre-Push-Gate: git diff ${1} fehlgeschlagen — Bau-Scope nicht bestimmbar" >&2
    exit 2
  fi
  if [ -z "$files" ]; then
    note "Bauprüfung: keine geaenderte Datei in ${1} — nichts zu bauen"
    return 0
  fi
  # Ohne GITHUB_*-Umgebung: changed-scopes.sh soll hier nur die Liste einordnen.
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
# 3 — Bestands-Gates (dieselben wie im CI-Job "Public Hygiene")
# ---------------------------------------------------------------------------
TREE_GATES=(
  "Doku-Zahlen gegen den Code|bash|scripts/public-readiness/doc-facts-check.sh"
  "Doku-Verweise und DE/EN-Gleichstand|python3|scripts/public-readiness/docs_parity_check.py"
  "Diagramm-Seiten gegen die Markdown-Quelle|python3|scripts/docs-diagrams.py|--check"
  "API-Endpunkte gegen docs/api-referenz.md (DE/EN)|bash|scripts/public-readiness/api-endpoint-diff.sh"
)
check_tree() {
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
# Bereiche bestimmen
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
    # Neuer Branch auf dem Remote: alles, was noch kein Remote-Ref kennt.
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
# Lauf
# ---------------------------------------------------------------------------
echo "Pre-Push-Gate (${MODE})"

case "$MODE" in
  message)
    [ -f "$MSG_FILE" ] || { echo "Pre-Push-Gate: Nachrichtendatei fehlt: '${MSG_FILE}'" >&2; exit 2; }
    ident="$(git var GIT_AUTHOR_IDENT 2>/dev/null)"; ident="${ident#*<}"; check_email "dieser Commit" "Autor" "${ident%%>*}"
    ident="$(git var GIT_COMMITTER_IDENT 2>/dev/null)"; ident="${ident#*<}"; check_email "dieser Commit" "Committer" "${ident%%>*}"
    check_message "dieser Commit" "$(cat "$MSG_FILE")"
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
  echo "Fix: Nachricht oder Autor mit git commit --amend (bzw. git rebase -i) korrigieren, Literale ersetzen."
  echo "Kein Bypass per Reflex — git push --no-verify nur, wenn der Befund bewusst gepruft ist."
  exit 1
fi
if [ "$WARN" -ne 0 ]; then
  echo "Pre-Push-Gate OK — ${WARN} Warnung(en), kein Verstoss."
else
  echo "Pre-Push-Gate OK"
fi
