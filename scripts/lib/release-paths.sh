# Paths of the release process for one mode - sourced by the release workflows
# (docs/entwicklung.md -> "Release-Lagebild"). Probe = the GitHub repos as local bare repos
# (scripts/release-probe.sh), live = the real working repo and the clone of the public repo.
#
# In:  MODE (probe|live), ARCHIVE_REPO (working repo; live: the archive, probe: source of the probe),
#      PUBLIC_CLONE (live), PUBLIC_REMOTE (live, only for the first cut), PROBE_DIR, BACKUP_DIR and
#      PAGE_DIR (optional)
# The page is no part of a repo, and it must not sit in a hidden directory either: a browser from a
# snap may read only non-hidden folders of the home directory. Default: the documents folder.
# Out: ARCHIVE + ARCHIVE_UPSTREAM (line "archive"), PUBLIC + PUBLIC_REF (line "public": what counts as
#      published), PUBLIC_CLONE (handover target), PUBLIC_REMOTE, EVENTS, PAGE, STATUS_FILE,
#      BACKUP_DIR, REWRITE_WORK (work clone of the first cut)
release_paths() {
  local state="${HOME}/.local/state/mysuricates"
  [ -n "${ARCHIVE_REPO:-}" ] || { echo "archive_repo fehlt im Kontext" >&2; return 2; }
  if [ -z "${PAGE_DIR:-}" ]; then
    local docs
    docs="$(xdg-user-dir DOCUMENTS 2>/dev/null || true)"
    [ -n "$docs" ] && [ "$docs" != "$HOME" ] || docs="$HOME/Documents"  # unconfigured: xdg-user-dir says $HOME
    PAGE_DIR="$docs/MySuricates"
  fi
  case "${MODE:-probe}" in
    probe)
      PROBE_DIR="${PROBE_DIR:-$state/release-probe}"
      ARCHIVE="$PROBE_DIR/work"
      PUBLIC="$PROBE_DIR/public.git"
      PUBLIC_REF=main
      PUBLIC_CLONE="$PROBE_DIR/public"
      PUBLIC_REMOTE="$PROBE_DIR/public.git"
      EVENTS="$PROBE_DIR/events.jsonl"
      BACKUP_DIR="${BACKUP_DIR:-$PROBE_DIR/backup}"
      REWRITE_WORK="$PROBE_DIR/rewrite"
      PAGE="$PAGE_DIR/release-lagebild-probe.html"
      ;;
    live)
      [ -n "${PUBLIC_CLONE:-}" ] || { echo "public_clone fehlt im Kontext" >&2; return 2; }
      ARCHIVE="$ARCHIVE_REPO"
      PUBLIC="$PUBLIC_CLONE"
      PUBLIC_REF=origin/main  # only what has been pushed counts as published
      PUBLIC_REMOTE="${PUBLIC_REMOTE:-}"
      EVENTS="$state/release/events.jsonl"
      BACKUP_DIR="${BACKUP_DIR:-$state/release/backup}"
      REWRITE_WORK="$state/release/rewrite"
      PAGE="$PAGE_DIR/release-lagebild.html"
      ;;
    *) echo "mode muss probe oder live sein, nicht: ${MODE}" >&2; return 2 ;;
  esac
  ARCHIVE_UPSTREAM=origin/main
  STATUS_FILE="$(dirname "$EVENTS")/lagebild-status.txt"
  mkdir -p "$(dirname "$EVENTS")"
}
