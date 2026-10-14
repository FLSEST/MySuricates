#!/usr/bin/env bash
# CI gate before a release leaves the working repo: the CI workflow on GitHub must have run green
# for the commit of the tag, and its newest push run on main must be green as well. The first cut and every handover call this in live mode, before
# anything reaches the public repo (2026-10-14: v0.7.8 went out with a red backend job because
# nobody looked at the run after the push; TODO 9592e2df).
#
# Usage: scripts/release-ci-check.sh --repo <working-repo> --tag <tag> [--workflow ci.yml] [--branch main]
#   The GitHub repo is taken from the working repo's origin (github.com:OWNER/NAME or
#   https://github.com/OWNER/NAME). Needs gh with read access to it.
# Prints one line per check. Exit: 0 = green, 1 = red, running, missing or not readable, 2 = usage error.
set -uo pipefail
GH="${GH:-gh}"
REPO="" TAG="" WORKFLOW="ci.yml" BRANCH="main"
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --workflow) WORKFLOW="${2:-}"; shift 2 ;;
    --branch) BRANCH="${2:-}"; shift 2 ;;
    *) echo "Unbekanntes Argument: $1"; exit 2 ;;
  esac
done
[ -n "$REPO" ] && [ -n "$TAG" ] || { echo "Aufruf: release-ci-check.sh --repo <arbeits-repo> --tag <tag>"; exit 2; }

SHA="$(git -C "$REPO" rev-parse --verify --quiet "$TAG^{commit}")" || { echo "Tag $TAG fehlt in $REPO"; exit 1; }
ORIGIN="$(git -C "$REPO" remote get-url origin 2>/dev/null)" || { echo "$REPO hat kein origin"; exit 1; }
SLUG="$(printf '%s' "$ORIGIN" | sed -nE 's#^(git@github\.com:|ssh://git@github\.com/|https://github\.com/)([^/]+/[^/]+)$#\2#p' | sed 's/\.git$//')"
[ -n "$SLUG" ] || { echo "origin ist kein GitHub-Repo: $ORIGIN"; exit 1; }

# One verdict per question: the runs of the tag commit, then the newest run on the branch (on
# 2026-10-14 the tag run of v0.7.8 was green and the main run right after it red - an
# order-dependent test; the tag alone would have let it through).
verdict() {  # <label> <gh run list filter args...>
  local label="$1" runs; shift
  runs="$("$GH" run list -R "$SLUG" --workflow "$WORKFLOW" --limit 20 "$@" \
    --json databaseId,status,conclusion 2>&1)" || { echo "CI-Status von $SLUG nicht lesbar: $(printf '%s' "$runs" | head -1)"; return 1; }
  printf '%s' "$runs" | python3 -c '
import json, sys
where = sys.argv[1]
runs = json.load(sys.stdin)
if not runs:
    print("kein Lauf von %s - erst pushen und abwarten" % where); sys.exit(1)
running = [r for r in runs if r["status"] != "completed"]
if running:
    print("%s läuft noch (Lauf %s)" % (where, running[0]["databaseId"])); sys.exit(1)
latest = runs[0]   # gh lists the newest run first; a green re-run counts
if latest["conclusion"] != "success":
    print("%s ist %s (Lauf %s)" % (where, latest["conclusion"], latest["databaseId"])); sys.exit(1)
print("%s grün (Lauf %s)" % (where, latest["databaseId"]))
' "$WORKFLOW $label in $SLUG"
}
RC=0
verdict "für $TAG (${SHA:0:9})" --commit "$SHA" || RC=1
verdict "auf $BRANCH" --branch "$BRANCH" --event push || RC=1
exit "$RC"
