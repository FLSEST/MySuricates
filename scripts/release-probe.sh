#!/usr/bin/env bash
# Release probe: the two GitHub repos of the release process as local bare repos, so the whole way
# (first cut, handovers, discard) can be played through before the real repos exist. Never touches a
# remote of the working repo.
#
# Layout of the probe directory (default ~/.local/state/mysuricates/release-probe):
#   work/          clone of the working repo - stands in for the archive working copy; main starts
#                  at the cut commit, the later commits wait under refs/probe/future
#   archive.git/   bare repo, origin of work/ - stands in for GitHub MySuricates-archive
#   public.git/    bare repo, empty after reset - stands in for the new public GitHub repo
#   public/        clone of public.git, created after the first cut - the handover target
#   events.jsonl   event log of the probe (scripts/release-overview.py)
#
# Usage: release-probe.sh reset   [--dir DIR] [--source REPO] --cut REV [--tag vX.Y.Z]
#        release-probe.sh advance [--dir DIR] --to REV [--tag vX.Y.Z] [--no-push]
#   reset    builds the probe from scratch: work/ with main at REV and the tag on it, pushed to
#            archive.git, and an empty public.git. Refuses a directory that is no probe.
#   advance  moves main of work/ forward to REV (fast-forward, REV from the source history),
#            tags it and pushes main and tag to archive.git unless --no-push.
# Tags are annotated, tagger is the git identity of the source repo (noreply form, see hooks),
# which reset copies into work/.
set -uo pipefail
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="${HOME}/.local/state/mysuricates/release-probe"
SOURCE="$(cd "$HERE/.." && pwd)"
REV=""
TAG=""
PUSH=1
MARKER=".release-probe"

die() { echo "[probe] ABBRUCH: $*" >&2; exit 1; }
log() { echo "[probe] $*"; }
usage() { sed -n '/^# Usage:/,/^# which reset/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

CMD="${1:-}"; [ $# -gt 0 ] && shift
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift ;;
    --source) SOURCE="$2"; shift ;;
    --cut|--to) REV="$2"; shift ;;
    --tag) TAG="$2"; shift ;;
    --no-push) PUSH=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[probe] unbekannte Option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case "$CMD" in reset|advance) ;; *) usage >&2; exit 2 ;; esac
[ -n "$REV" ] || { usage >&2; exit 2; }
if [ -n "$TAG" ] && ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then die "Tag muss vX.Y.Z sein: $TAG"; fi

WORK="$DIR/work"
identity() {  # $1 = repo; the probe tags carry the same noreply identity as the real ones
  NAME="$(git -C "$1" config user.name || true)"
  EMAIL="$(git -C "$1" config user.email || true)"
  [ -n "$NAME" ] && [ -n "$EMAIL" ] || die "git user.name/user.email in $1 fehlen"
}
tag_head() {  # $1 = tag
  git -C "$WORK" -c user.name="$NAME" -c user.email="$EMAIL" tag -a "$1" -m "Release $1 (Probe)" \
    || die "Tag $1 konnte nicht angelegt werden"
  log "Tag $1 auf $(git -C "$WORK" rev-parse --short HEAD)"
}

if [ "$CMD" = reset ]; then
  if [ -e "$DIR" ]; then
    [ -f "$DIR/$MARKER" ] || die "$DIR ist kein Probe-Verzeichnis (Markierung $MARKER fehlt) — nichts gelöscht"
    rm -rf "$DIR"
  fi
  identity "$SOURCE"
  git -C "$SOURCE" rev-parse --verify --quiet "$REV^{commit}" >/dev/null || die "Commit $REV gibt es im Quell-Repo nicht"
  mkdir -p "$DIR" && touch "$DIR/$MARKER"
  git clone --quiet --no-local --single-branch --branch main "$SOURCE" "$WORK" || die "Klon von $SOURCE fehlgeschlagen"
  git -C "$WORK" config user.name "$NAME"
  git -C "$WORK" config user.email "$EMAIL"
  # Keep the later commits reachable for "advance", then let main start at the cut.
  git -C "$WORK" update-ref refs/probe/future "$(git -C "$WORK" rev-parse main)"
  git -C "$WORK" reset --quiet --hard "$REV" || die "reset auf $REV fehlgeschlagen"
  git -C "$WORK" tag -l | xargs -r git -C "$WORK" tag -d >/dev/null
  git -C "$WORK" remote remove origin
  git init --quiet --bare --initial-branch=main "$DIR/archive.git"
  git init --quiet --bare --initial-branch=main "$DIR/public.git"
  git -C "$WORK" remote add origin "$DIR/archive.git"
  [ -n "$TAG" ] && tag_head "$TAG"
  git -C "$WORK" push --quiet origin main --tags || die "Push ins Probe-Archiv fehlgeschlagen"
  git -C "$WORK" branch --quiet --set-upstream-to=origin/main main
  log "Probe neu aufgebaut in $DIR: main auf $(git -C "$WORK" rev-parse --short main), $(git -C "$WORK" rev-list --count main) Commits"
  exit 0
fi

# advance
[ -f "$DIR/$MARKER" ] || die "keine Probe in $DIR — erst reset"
identity "$WORK"
TARGET="$(git -C "$WORK" rev-parse --verify --quiet "$REV^{commit}")" || die "Commit $REV gibt es in der Probe nicht"
git -C "$WORK" merge-base --is-ancestor "$TARGET" refs/probe/future || die "$REV liegt nicht auf der Quell-History"
git -C "$WORK" merge --quiet --ff-only "$TARGET" || die "main lässt sich nicht auf $REV vorspulen"
log "main auf $(git -C "$WORK" rev-parse --short main) ($(git -C "$WORK" rev-list --count main) Commits)"
[ -n "$TAG" ] && tag_head "$TAG"
if [ "$PUSH" = 1 ]; then
  git -C "$WORK" push --quiet origin main --tags || die "Push ins Probe-Archiv fehlgeschlagen"
  log "main und Tags ins Probe-Archiv gepusht"
fi
