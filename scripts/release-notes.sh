#!/usr/bin/env bash
# Generates release notes for a v* tag deterministically from the Git history -- without AI, so the
# GitHub action (release.yml) needs no model call (TODO 3c75962d). Writes to stdout.
#
# Usage: scripts/release-notes.sh <prev-tag> <tag>
#   <prev-tag>  predecessor tag, or "" for the very first release: then the notes are one short
#               paragraph, since listing the whole history (thousands of lines for the first public
#               release, 2026-10-13) tells a reader nothing
#   <tag>       the tag to publish (must exist)
#
# The notes are English, like the public repo. Must run in BOTH repos (private working repo +
# curated public repo): the public repo has no merge commits from the working repo and possibly no
# docs/en/entwicklung.md with the same entries -- affected sections are then simply omitted instead
# of throwing an error. See docs/entwicklung.md -> "GitHub-Release bei jedem Tag".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    echo "Usage: $(basename "$0") <prev-tag|''> <tag>" >&2
}

PREV="${1-}"
TAG="${2-}"

if [ -z "$TAG" ]; then
    usage
    exit 1
fi

cd "$PROJECT_ROOT"

if ! git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "Tag ${TAG} existiert nicht in diesem Repo." >&2
    exit 1
fi
if [ -n "$PREV" ] && ! git rev-parse -q --verify "refs/tags/${PREV}" >/dev/null; then
    echo "Vorgaenger-Tag ${PREV} existiert nicht in diesem Repo." >&2
    exit 1
fi

RANGE="${TAG}"
[ -n "$PREV" ] && RANGE="${PREV}..${TAG}"

# GitHub slug for the links (comparison, behavior changes). RELEASE_NOTES_REPO_SLUG sets it
# from outside -- the handover (scripts/release-handover.sh) generates the notes in the working repo, but the
# links must point to the public repo. Set and empty: no links. Without the variable,
# origin applies, but only a GitHub address: a local path as origin would otherwise yield
# "https://github.com//path/…".
if [ -n "${RELEASE_NOTES_REPO_SLUG+x}" ]; then
    REPO_SLUG="$RELEASE_NOTES_REPO_SLUG"
else
    REMOTE_URL="$(git config --get remote.origin.url 2>/dev/null || true)"
    REPO_SLUG=""
    case "$REMOTE_URL" in
        git@github.com:*|https://github.com/*|ssh://git@github.com/*)
            REPO_SLUG="$(sed -E 's#^git@github\.com:##; s#^ssh://git@github\.com/##; s#^https://github\.com/##; s#\.git$##; s#/$##' <<< "$REMOTE_URL")"
            ;;
    esac
fi

# ── Strip TODO abbreviation brackets: internal (repo-local short IDs), nothing for public notes.
# Two forms (scripts/lib/strip-todo-brackets.py): a trailing bracket whose content starts with
# an 8-hex abbreviation (title/commas/nested brackets after it do not matter), and a bracket
# anywhere in the subject that consists only of abbreviations ("(TODO 2738fda0)", "(a4644103, e8a4ebb4)").
# Batch processing (one Python call per section instead of one per line).
strip_todo_brackets() {
    python3 "$SCRIPT_DIR/lib/strip-todo-brackets.py"
}

echo "# ${TAG}"
echo

if [ -z "$PREV" ]; then
    echo "First public release. The history up to this version is part of the repository (\`git log ${TAG}\`)."
    exit 0
fi

# ── Overview: subject lines of the merge commits in the first-parent history ─────────────────────
MERGE_SUBJECTS="$(git log --first-parent --merges --format='%s' "$RANGE" -- 2>/dev/null | strip_todo_brackets)"
if [ -n "$MERGE_SUBJECTS" ]; then
    echo "## Overview"
    echo
    while IFS= read -r subject; do
        [ -n "$subject" ] || continue
        echo "- ${subject}"
    done <<< "$MERGE_SUBJECTS"
    echo
fi

# ── Changes by type: non-merge commits, grouped by Conventional prefix ──────────────────────────
NONMERGE_SUBJECTS="$(git log --no-merges --format='%s' "$RANGE" -- 2>/dev/null | strip_todo_brackets)"

declare -A TYPE_LABEL=(
    [feat]="Features"
    [fix]="Fixes"
    [perf]="Performance"
    [refactor]="Refactorings"
    [docs]="Documentation"
    [test]="Tests"
)
TYPE_ORDER="feat fix perf refactor docs test"

if [ -n "$NONMERGE_SUBJECTS" ]; then
    TYPE_SECTIONS=""
    for type in $TYPE_ORDER; do
        MATCHES="$(grep -E "^${type}(\\(.*\\))?!?:" <<< "$NONMERGE_SUBJECTS" || true)"
        [ -n "$MATCHES" ] || continue
        TYPE_SECTIONS="1"
    done
    if [ -n "$TYPE_SECTIONS" ]; then
        echo "## Changes by type"
        echo
        for type in $TYPE_ORDER; do
            MATCHES="$(grep -E "^${type}(\\(.*\\))?!?:" <<< "$NONMERGE_SUBJECTS" || true)"
            [ -n "$MATCHES" ] || continue
            echo "### ${TYPE_LABEL[$type]}"
            echo
            while IFS= read -r subject; do
                [ -n "$subject" ] || continue
                stripped="$(sed -E 's/^[a-z]+(\(.*\))?!?:[[:space:]]*//' <<< "$subject")"
                echo "- ${stripped}"
            done <<< "$MATCHES"
            echo
        done
    fi
fi

# ── Behavior changes: entries from docs/en/entwicklung.md, dated after the predecessor tag ─────
DOCS_FILE="$PROJECT_ROOT/docs/en/entwicklung.md"
if [ -f "$DOCS_FILE" ]; then
    PREV_DATE=""
    if [ -n "$PREV" ]; then
        # Annotated tag: %ai of the tag object itself; a lightweight tag has none, then the
        # commit it points to.
        PREV_DATE="$(git for-each-ref --format='%(creatordate:short)' "refs/tags/${PREV}" 2>/dev/null)"
        [ -n "$PREV_DATE" ] || PREV_DATE="$(git log -1 --format=%ad --date=short "$PREV" 2>/dev/null)"
    fi

    ENTRIES=""
    while IFS= read -r heading; do
        entry_date="$(sed -E 's/^\*\*Since ([0-9]{4}-[0-9]{2}-[0-9]{2}).*/\1/' <<< "$heading")"
        [[ "$entry_date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || continue
        if [ -z "$PREV_DATE" ] || [[ "$entry_date" > "$PREV_DATE" ]]; then
            ENTRIES="${ENTRIES}${heading}"$'\n'
        fi
    done < <(grep -E '^\*\*Since [0-9]{4}-[0-9]{2}-[0-9]{2} ' "$DOCS_FILE")

    if [ -n "$ENTRIES" ]; then
        echo "## Behavior changes"
        echo
        if [ -n "$REPO_SLUG" ]; then
            echo "Details: [docs/en/entwicklung.md → Behavior Changes](https://github.com/${REPO_SLUG}/blob/${TAG}/docs/en/entwicklung.md#behavior-changes)"
        else
            echo "Details: docs/en/entwicklung.md → Behavior Changes"
        fi
        echo
        while IFS= read -r heading; do
            [ -n "$heading" ] || continue
            echo "- ${heading}"
        done <<< "$ENTRIES"
        echo
    fi
fi

# ── Comparison: link to the compare view, omitted for the first release ──────────────────────────
if [ -n "$PREV" ] && [ -n "$REPO_SLUG" ]; then
    echo "## Comparison"
    echo
    echo "[${PREV}...${TAG}](https://github.com/${REPO_SLUG}/compare/${PREV}...${TAG})"
    echo
fi
