#!/usr/bin/env bash
# Erzeugt Release-Notes fuer ein v*-Tag deterministisch aus der Git-Historie — ohne KI, damit die
# GitHub-Action (release.yml) keinen Modellaufruf braucht (TODO 3c75962d). Schreibt nach stdout.
#
# Usage: scripts/release-notes.sh <prev-tag> <tag>
#   <prev-tag>  Vorgaenger-Tag, oder "" beim allerersten Release (keine Zahlen/Vergleich davor)
#   <tag>       das zu veroeffentlichende Tag (muss existieren)
#
# Muss in BEIDEN Repos laufen (privates Arbeits-Repo + kuratiertes oeffentliches Repo): das
# oeffentliche Repo hat keine Merge-Commits aus dem Arbeits-Repo und ggf. keine docs/entwicklung.md
# mit denselben Eintraegen — betroffene Abschnitte entfallen dann einfach, statt einen Fehler zu
# werfen. Siehe docs/entwicklung.md -> "GitHub-Release bei jedem Tag".
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

REMOTE_URL="$(git config --get remote.origin.url 2>/dev/null || true)"
REPO_SLUG="$(sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##' <<< "$REMOTE_URL")"

# ── TODO-Klammer am Ende eines Betreffs entfernen: "… (<8-hex> Titel)" ist ein internes Kuerzel ──
strip_todo_bracket() {
    sed -E 's/[[:space:]]*\([0-9a-f]{6,8}([[:space:]]+[^()]*)?\)[[:space:]]*$//'
}

echo "# ${TAG}"
echo

# ── Ueberblick: Betreffzeilen der Merge-Commits im First-Parent-Verlauf ─────────────────────────
MERGE_SUBJECTS="$(git log --first-parent --merges --format='%s' "$RANGE" -- 2>/dev/null)"
if [ -n "$MERGE_SUBJECTS" ]; then
    echo "## Überblick"
    echo
    while IFS= read -r subject; do
        [ -n "$subject" ] || continue
        echo "- $(printf '%s' "$subject" | strip_todo_bracket)"
    done <<< "$MERGE_SUBJECTS"
    echo
fi

# ── Änderungen nach Typ: Nicht-Merge-Commits, gruppiert nach Conventional-Praefix ───────────────
NONMERGE_SUBJECTS="$(git log --no-merges --format='%s' "$RANGE" -- 2>/dev/null)"

declare -A TYPE_LABEL=(
    [feat]="Features"
    [fix]="Fixes"
    [perf]="Performance"
    [refactor]="Refactorings"
    [docs]="Dokumentation"
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
        echo "## Änderungen nach Typ"
        echo
        for type in $TYPE_ORDER; do
            MATCHES="$(grep -E "^${type}(\\(.*\\))?!?:" <<< "$NONMERGE_SUBJECTS" || true)"
            [ -n "$MATCHES" ] || continue
            echo "### ${TYPE_LABEL[$type]}"
            echo
            while IFS= read -r subject; do
                [ -n "$subject" ] || continue
                stripped="$(printf '%s' "$subject" | strip_todo_bracket | sed -E 's/^[a-z]+(\(.*\))?!?:[[:space:]]*//')"
                echo "- ${stripped}"
            done <<< "$MATCHES"
            echo
        done
    fi
fi

# ── Verhaltensänderungen: Eintraege aus docs/entwicklung.md, datiert nach dem Vorgaenger-Tag ────
DOCS_FILE="$PROJECT_ROOT/docs/entwicklung.md"
if [ -f "$DOCS_FILE" ]; then
    PREV_DATE=""
    if [ -n "$PREV" ]; then
        # Annotiertes Tag: %ai des Tag-Objekts selbst; leichtgewichtiges Tag hat keins, dann der
        # Commit, auf den es zeigt.
        PREV_DATE="$(git for-each-ref --format='%(creatordate:short)' "refs/tags/${PREV}" 2>/dev/null)"
        [ -n "$PREV_DATE" ] || PREV_DATE="$(git log -1 --format=%ad --date=short "$PREV" 2>/dev/null)"
    fi

    ENTRIES=""
    while IFS= read -r heading; do
        entry_date="$(sed -E 's/^\*\*Seit ([0-9]{4}-[0-9]{2}-[0-9]{2}).*/\1/' <<< "$heading")"
        [[ "$entry_date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || continue
        if [ -z "$PREV_DATE" ] || [[ "$entry_date" > "$PREV_DATE" ]]; then
            ENTRIES="${ENTRIES}${heading}"$'\n'
        fi
    done < <(grep -E '^\*\*Seit [0-9]{4}-[0-9]{2}-[0-9]{2} ' "$DOCS_FILE")

    if [ -n "$ENTRIES" ]; then
        echo "## Verhaltensänderungen"
        echo
        if [ -n "$REPO_SLUG" ]; then
            echo "Details: [docs/entwicklung.md → Verhaltensänderungen](https://github.com/${REPO_SLUG}/blob/${TAG}/docs/entwicklung.md#verhaltensänderungen)"
        else
            echo "Details: docs/entwicklung.md → Verhaltensänderungen"
        fi
        echo
        while IFS= read -r heading; do
            [ -n "$heading" ] || continue
            echo "- ${heading}"
        done <<< "$ENTRIES"
        echo
    fi
fi

# ── Vergleich: Link auf den Compare-View, entfaellt beim ersten Release ────────────────────────
if [ -n "$PREV" ] && [ -n "$REPO_SLUG" ]; then
    echo "## Vergleich"
    echo
    echo "[${PREV}...${TAG}](https://github.com/${REPO_SLUG}/compare/${PREV}...${TAG})"
    echo
fi
