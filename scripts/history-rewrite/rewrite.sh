#!/usr/bin/env bash
# History rewrite for the first public repository (TODO 0bf5eed4): one git filter-repo pass over a
# fresh clone of the working repo, then a verification of the result. Never touches a remote.
#
# What the pass does:
#   - drops the paths listed in <state>/remove-paths.txt from every commit (personal data),
#   - maps author/committer identities with <state>/mailmap.txt (private address -> noreply),
#   - replaces every commit message by its entry in <state>/map.jsonl (TODO trailer + English
#     translation, see messages.py) and then applies <state>/message-replacements.txt,
#   - optionally strips blobs above a size (--strip-blobs-bigger-than).
# The three text files are private by nature (they name what has to go) and therefore live only in the
# state directory, never in the repository. Line formats:
#   remove-paths.txt          one repo-relative path per line, # comments
#   mailmap.txt               git mailmap lines, e.g. "Name <noreply@...> <private@...>"
#   message-replacements.txt  "literal==>replacement" or "regex:pattern==>replacement" (filter-repo
#                             syntax); without "==>" the match is replaced by "***REMOVED***"
#
# Usage: rewrite.sh run    [options]   clone, rewrite, verify (exit 1 on a finding)
#        rewrite.sh verify [options]   verify an existing work clone again
#   --state DIR     state directory (default ~/.local/state/mysuricates/history-rewrite)
#   --source REPO   working repo to clone (default: the repo of this script)
#   --work DIR      work clone (default <state>/work); "run" refuses an existing one
#   --branch NAME   branch that goes public (default main); other branches are not cloned
#   --tag NAME      release tag the public branch ends at (e.g. v0.8.0): it must lie on the branch,
#                   but not necessarily at its tip - scripts/release.sh commits the next -SNAPSHOT
#                   after the tag, and that commit stays in the archive. If the tag does not exist in
#                   the source, --create-tag sets it on the tip of the work clone. With a
#                   tag, it is the only tag that stays - the first public state carries one version
#                   (decided 2026-10-12); without one, all v* tags stay
#   --create-tag    see --tag (for trial runs before the real tag exists)
#   --strip-blobs-bigger-than SIZE   passed on to git filter-repo (e.g. 5M)
#   --allow-missing-map              trial run: commits without a map entry keep their message
set -uo pipefail
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE="${HOME}/.local/state/mysuricates/history-rewrite"
SOURCE="$(cd "$HERE/../.." && pwd)"
WORK=""
BRANCH="main"
TAG=""
CREATE_TAG=0
STRIP=""
ALLOW_MISSING_MAP=0

log() { echo "[rewrite] $*"; }
die() { echo "[rewrite] ABBRUCH: $*" >&2; exit 1; }
usage() { sed -n '/^# Usage:/,/^#   --allow-missing-map/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

CMD="${1:-}"; [ $# -gt 0 ] && shift
while [ $# -gt 0 ]; do
  case "$1" in
    --state) STATE="$2"; shift ;;
    --source) SOURCE="$2"; shift ;;
    --work) WORK="$2"; shift ;;
    --branch) BRANCH="$2"; shift ;;
    --tag) TAG="$2"; shift ;;
    --create-tag) CREATE_TAG=1 ;;
    --strip-blobs-bigger-than) STRIP="$2"; shift ;;
    --allow-missing-map) ALLOW_MISSING_MAP=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[rewrite] unbekannte Option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case "$CMD" in run|verify) ;; *) usage >&2; exit 2 ;; esac
[ -n "$WORK" ] || WORK="$STATE/work"
PATHS_FILE="$STATE/remove-paths.txt"
MAILMAP="$STATE/mailmap.txt"
REPLACEMENTS="$STATE/message-replacements.txt"
MAP="$STATE/map.jsonl"

# Lines of a list file without comments and blank lines.
entries() { [ -f "$1" ] && grep -vE '^[[:space:]]*(#|$)' "$1"; }

# ── verification ─────────────────────────────────────────────────────────────────────────────────
FINDINGS=0
finding() { echo "  FEHLER $*"; FINDINGS=$((FINDINGS + 1)); }
ok() { echo "  ok     $*"; }

verify() {
  local w="$1" path n emails private_terms term msgs leaks
  log "Gegenprobe in $w"
  # 1. Removed paths appear in no commit.
  while IFS= read -r path; do
    n="$(git -C "$w" log --all --format=%H -- "$path" | wc -l | tr -d ' ')"
    [ "$n" = 0 ] && ok "Pfad entfernt: (${#path} Zeichen)" || finding "Pfad noch in $n Commit(s) (Eintrag ${#path} Zeichen)"
  done < <(entries "$PATHS_FILE")
  # 2. Only the mapped identities remain; no address from the left side of the mailmap.
  emails="$(git -C "$w" log --all --format='%ae%n%ce' | sort -u)"
  ok "Adressen in der History: $(grep -c . <<<"$emails")"
  while IFS= read -r line; do
    for old in $(printf '%s\n' "$line" | grep -oE '<[^>]+>' | sed -n '2,$p' | tr -d '<>'); do
      grep -qxF -- "$old" <<<"$emails" && finding "alte Adresse noch vorhanden (Mailmap-Zeile)" || true
    done
  done < <(entries "$MAILMAP")
  # 3. Messages: no private trailer, no replaced pattern left, no German left.
  msgs="$(git -C "$w" log --all --format='%B%x1e')"
  n="$(grep -c '^Claude-Session:' <<<"$msgs" || true)"
  [ "$n" = 0 ] && ok "keine Claude-Session-Trailer" || finding "$n Claude-Session-Trailer"
  private_terms=0
  [ -f "$REPLACEMENTS" ] && private_terms="$(python3 -I "$HERE/messages.py" patterns-left --repo "$w" --replacements "$REPLACEMENTS")"
  [ "$private_terms" = 0 ] && ok "keine zu ersetzenden Muster mehr in den Nachrichten" \
    || finding "$private_terms Ersetzungsmuster kommen noch in Nachrichten vor"
  n="$(python3 -I "$HERE/messages.py" german-left --repo "$w")"
  ok "Nachrichten mit deutschem Text (Hinweis, kein Fehler): $n"
  # 4. Content of the public tip is unchanged: same tree as the source at the cut.
  if [ "$(git -C "$w" rev-parse "$BRANCH^{tree}")" = "$(git -C "$SOURCE" rev-parse "$CUT^{tree}")" ]; then
    ok "Baum der Spitze gleicht $CUT_NAME der Quelle"
  else
    finding "Baum der Spitze weicht von $CUT_NAME der Quelle ab"
  fi
  # 5. Only the public branch; with --tag only that tag (on the tip), otherwise only v* tags.
  n="$(git -C "$w" for-each-ref --format='%(refname)' refs/heads | grep -vxF "refs/heads/$BRANCH" | wc -l | tr -d ' ')"
  [ "$n" = 0 ] && ok "nur Branch $BRANCH" || finding "$n weitere Branches"
  if [ -n "$TAG" ]; then
    n="$(git -C "$w" tag -l | grep -vxF "$TAG" | wc -l | tr -d ' ')"
    [ "$n" = 0 ] && ok "nur das Tag $TAG" || finding "$n weitere Tags neben $TAG"
  else
    n="$(git -C "$w" tag -l | grep -vE '^v[0-9]' | wc -l | tr -d ' ')"
    [ "$n" = 0 ] && ok "nur v*-Tags" || finding "$n Tags ausserhalb von v*"
  fi
  if [ -n "$TAG" ]; then
    [ "$(git -C "$w" rev-parse "$TAG^{commit}" 2>/dev/null)" = "$(git -C "$w" rev-parse "$BRANCH")" ] \
      && ok "Tag $TAG auf der Spitze" || finding "Tag $TAG fehlt oder liegt nicht auf der Spitze"
  fi
  # 6. Secrets over the whole history, if gitleaks is installed.
  if command -v gitleaks >/dev/null 2>&1; then
    leaks="$(cd "$w" && gitleaks git --no-banner --redact --log-opts=--all . 2>&1 | tail -1)"
    case "$leaks" in *"no leaks found"*) ok "gitleaks: keine Funde" ;; *) finding "gitleaks: $leaks" ;; esac
  else
    echo "  hinweis gitleaks nicht installiert - Geheimnis-Scan uebersprungen"
  fi
  # 7. README sync marker still resolves (it names a source hash that the rewrite changes).
  if [ -f "$w/scripts/readme-sync/readme_sync.py" ]; then
    (cd "$w" && python3 -I scripts/readme-sync/readme_sync.py check >/dev/null 2>&1) \
      && ok "README-Sync-Marke loest auf" || finding "README-Sync-Marke loest nicht auf"
  fi
  echo
  echo "Commits: Quelle $(git -C "$SOURCE" rev-list --count "$CUT") bis $CUT_NAME, neu $(git -C "$w" rev-list --count "$BRANCH")"
  [ "$FINDINGS" = 0 ] && { log "Gegenprobe ohne Befund"; return 0; }
  log "Gegenprobe: $FINDINGS Befund(e)"; return 1
}

# Where the public branch ends in the source: at the tag if the source has it (it must lie on the
# branch), otherwise at the branch tip (no tag, or --create-tag for a trial run).
CUT="$BRANCH"; CUT_NAME="$BRANCH"
if [ -n "$TAG" ] && git -C "$SOURCE" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  git -C "$SOURCE" merge-base --is-ancestor "$TAG" "$BRANCH" || die "Tag $TAG liegt nicht auf $BRANCH der Quelle"
  CUT="$TAG^{commit}"; CUT_NAME="Tag $TAG"
fi

if [ "$CMD" = verify ]; then
  [ -d "$WORK/.git" ] || die "kein Arbeitsklon in $WORK"
  verify "$WORK"; exit $?
fi

# ── run ──────────────────────────────────────────────────────────────────────────────────────────
command -v git-filter-repo >/dev/null 2>&1 || die "git-filter-repo fehlt"
[ -e "$WORK" ] && die "$WORK existiert schon - fuer einen neuen Lauf entfernen oder --work angeben"
[ -s "$PATHS_FILE" ] || die "$PATHS_FILE fehlt oder ist leer"
[ -s "$MAILMAP" ] || die "$MAILMAP fehlt oder ist leer"
[ -s "$MAP" ] || die "$MAP fehlt - erst den Uebersetzungs-Workflow laufen lassen"
[ -f "$REPLACEMENTS" ] || log "Hinweis: $REPLACEMENTS fehlt - keine Ersetzungen in Nachrichten"

log "Klon von $SOURCE ($BRANCH) nach $WORK"
git clone --quiet --no-local --single-branch --branch "$BRANCH" "$SOURCE" "$WORK" || die "Klon gescheitert"
git -C "$WORK" remote remove origin
# Only release tags go public; other tags (stable-*, test markers) are dropped.
git -C "$WORK" tag -l | grep -vE '^v[0-9]' | while IFS= read -r t; do git -C "$WORK" tag -d "$t" >/dev/null; done
if [ -n "$TAG" ] && ! git -C "$WORK" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  [ "$CREATE_TAG" = 1 ] || die "Tag $TAG fehlt in der Quelle (fuer einen Probelauf: --create-tag)"
  git -C "$WORK" tag -a "$TAG" -m "Release $TAG" "$BRANCH"
  log "Tag $TAG im Arbeitsklon gesetzt (Probelauf)"
fi
# The first public state carries one version: with --tag, every other tag goes.
if [ -n "$TAG" ]; then
  git -C "$WORK" tag -l | grep -vxF "$TAG" | while IFS= read -r t; do git -C "$WORK" tag -d "$t" >/dev/null; done
  # The public branch ends at the tag; what came after it (the next -SNAPSHOT) stays in the archive.
  AFTER="$(git -C "$WORK" rev-list --count "$TAG..$BRANCH")"
  if [ "$AFTER" != 0 ]; then
    git -C "$WORK" reset --quiet --hard "$TAG" || die "reset auf $TAG gescheitert"
    log "$BRANCH endet am Tag $TAG - $AFTER Commit(s) nach dem Tag bleiben im Archiv"
  fi
fi

MISSING="$(python3 -I "$HERE/messages.py" map-missing --repo "$WORK" --map "$MAP")"
if [ "$MISSING" != 0 ]; then
  [ "$ALLOW_MISSING_MAP" = 1 ] || die "$MISSING Commits ohne Eintrag in map.jsonl (Probelauf: --allow-missing-map)"
  log "Hinweis: $MISSING Commits ohne Eintrag behalten ihre Nachricht (Probelauf)"
fi

CALLBACK="$(python3 -I "$HERE/messages.py" callback --map "$MAP" --replacements "$REPLACEMENTS")"
ARGS=(--force --invert-paths --paths-from-file "$PATHS_FILE" --mailmap "$MAILMAP" --commit-callback "$CALLBACK")
[ -n "$STRIP" ] && ARGS+=(--strip-blobs-bigger-than "$STRIP")
log "git filter-repo ($(entries "$PATHS_FILE" | wc -l | tr -d ' ') Pfade, Mailmap, Nachrichten${STRIP:+, Blobs > $STRIP})"
(cd "$WORK" && git filter-repo "${ARGS[@]}") || die "git filter-repo gescheitert"
git -C "$WORK" reflog expire --expire=now --all && git -C "$WORK" gc --prune=now --quiet

verify "$WORK"; RC=$?
echo
echo "Naechste Schritte (nur Flo): Repo auf GitHub privat anlegen, dann in $WORK:"
echo "  git remote add origin git@github.com:<owner>/<repo>.git"
echo "  git push origin $BRANCH${TAG:+ refs/tags/$TAG}"
exit $RC
