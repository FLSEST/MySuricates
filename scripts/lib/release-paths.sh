# Paths of the release process for one mode - sourced by the release workflows
# (docs/entwicklung.md -> "Release-Lagebild"). Probe = the GitHub repos as local bare repos
# (scripts/release-probe.sh), live = the real working repo and the clone of the public repo.
#
# In:  MODE (probe|live), ARCHIVE_REPO (working repo; live: the archive, probe: source of the probe
#      and home of the page), PUBLIC_CLONE (live), PUBLIC_REMOTE (live, only for the first cut),
#      PROBE_DIR and BACKUP_DIR (optional)
# Out: ARCHIVE + ARCHIVE_UPSTREAM (line "archive"), PUBLIC + PUBLIC_REF (line "public": what counts as
#      published), PUBLIC_CLONE (handover target), PUBLIC_REMOTE, EVENTS, PAGE, STATUS_FILE,
#      BACKUP_DIR, REWRITE_WORK (work clone of the first cut)
release_paths() {
  local state="${HOME}/.local/state/mysuricates"
  [ -n "${ARCHIVE_REPO:-}" ] || { echo "archive_repo fehlt im Kontext" >&2; return 2; }
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
      PAGE="$ARCHIVE_REPO/reports/release-lagebild-probe.html"
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
      PAGE="$ARCHIVE_REPO/reports/release-lagebild.html"
      ;;
    *) echo "mode muss probe oder live sein, nicht: ${MODE}" >&2; return 2 ;;
  esac
  ARCHIVE_UPSTREAM=origin/main
  STATUS_FILE="$(dirname "$EVENTS")/lagebild-status.txt"
  mkdir -p "$(dirname "$EVENTS")"
}
