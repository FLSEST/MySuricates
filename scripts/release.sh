#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
POM_FILE="$PROJECT_ROOT/ai-automation-backend/pom.xml"
FRONTEND_DIR="$PROJECT_ROOT/ai-automation-frontend"
PACKAGE_JSON="$FRONTEND_DIR/package.json"
PACKAGE_LOCK="$FRONTEND_DIR/package-lock.json"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[release]${NC} $*"; }
warn() { echo -e "${YELLOW}[release]${NC} $*"; }
err()  { echo -e "${RED}[release]${NC} $*" >&2; }

usage() {
    echo "Usage: $(basename "$0") [patch|minor|major]"
    echo ""
    echo "Releases the next version from the last tag: writes it into pom.xml, package.json and"
    echo "package-lock.json, commits, tags it, and then sets all three to the next -SNAPSHOT."
    echo ""
    echo "  patch  (default)  v0.1.0 → v0.1.1   (afterwards 0.1.2-SNAPSHOT)"
    echo "  minor             v0.1.1 → v0.2.0   (afterwards 0.2.1-SNAPSHOT)"
    echo "  major             v0.2.0 → v1.0.0   (afterwards 1.0.1-SNAPSHOT)"
}

# ── Read current version ──────────────────────────────────
# The last tag, not the pom: since the tagged commit carries the release version and the commit
# after it the next -SNAPSHOT, reading the pom would count one version too far (TODO dd82775b).
last_tag_version() {
    git -C "$PROJECT_ROOT" tag --list 'v[0-9]*' --sort=-v:refname | head -1 | sed 's/^v//'
}


read_version() {
    # Match the project-level <version> (first <version> after </parent>), strip -SNAPSHOT
    local raw
    raw=$(sed -n '/<\/parent>/,$ { s/.*<version>\(.*\)<\/version>.*/\1/p; }' "$POM_FILE" | head -1)
    echo "${raw%-SNAPSHOT}"
}

# ── Compute next version ──────────────────────────────────

bump_version() {
    local version="$1"
    local bump="${2:-patch}"

    IFS='.' read -r major minor patch <<< "$version"

    case "$bump" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
        *) err "Unknown bump type: $bump"; usage; exit 1 ;;
    esac

    echo "${major}.${minor}.${patch}"
}

# ── Update files ──────────────────────────────────────────

# update_pom VERSION [release|snapshot] — release writes the bare number. The backend derives its
# build type from the suffix (SystemIdentityService), so a tag with -SNAPSHOT would report a
# release as SNAPSHOT for good.
update_pom() {
    local new_version="$1" suffix=""
    [ "${2:-release}" = "snapshot" ] && suffix="-SNAPSHOT"
    # Replace the project-level <version> (first <version> after </parent>)
    local line_num
    line_num=$(sed -n '/<\/parent>/,$ { /[0-9]*\.[0-9]*\.[0-9]*/ { =; q; } }' "$POM_FILE")
    if [ -n "$line_num" ]; then
        sed -i "${line_num}s|<version>.*</version>|<version>${new_version}${suffix}</version>|" "$POM_FILE"
    fi
}

# package.json and package-lock.json together: a lockfile left at the old version makes every
# fresh clone dirty after its first `npm install` (found by scripts/installer-probe.sh, 453c01b1).
# `npm version` touches exactly the three version fields and nothing else.
update_package_json() {
    local new_version="$1"
    (cd "$FRONTEND_DIR" && npm version "$new_version" --no-git-tag-version --allow-same-version --ignore-scripts >/dev/null)
}

# ── Main ──────────────────────────────────────────────────

BUMP="${1:-patch}"

if [[ "$BUMP" == "-h" || "$BUMP" == "--help" ]]; then
    usage
    exit 0
fi

# Ensure clean working tree
cd "$PROJECT_ROOT"
if ! git diff --quiet || ! git diff --cached --quiet; then
    err "Working tree is not clean. Commit or stash changes first."
    exit 1
fi

CURRENT=$(last_tag_version)
[ -n "$CURRENT" ] || CURRENT=$(read_version)
NEXT=$(bump_version "$CURRENT" "$BUMP")
DEV=$(bump_version "$NEXT" patch)

if git rev-parse -q --verify "refs/tags/v${NEXT}" >/dev/null; then
    err "Tag v${NEXT} exists already."
    exit 1
fi

log "Last release:    ${CURRENT}"
log "Bump type:       $BUMP"
log "New version:     $NEXT"
echo ""

# Release commit: the version without a suffix — this is what the tag carries
log "Updating pom.xml → ${NEXT}"
update_pom "$NEXT" release

log "Updating package.json + package-lock.json → ${NEXT}"
update_package_json "$NEXT"

log "Committing..."
git add "$POM_FILE" "$PACKAGE_JSON" "$PACKAGE_LOCK"
git commit -m "Release v${NEXT}"

log "Tagging v${NEXT}..."
# Annotated, not lightweight: carries date/author/message and is what --follow-tags pushes.
# staging.sh promote's stable-* tags are lightweight on purpose (deploy timestamps, never meant
# to leave this machine) — --follow-tags skips those, so they never reach GitHub by accident.
git tag -a "v${NEXT}" -m "Release v${NEXT}"

# Back to development: without this, every later commit would carry the release number.
# Backend and frontend move together — the dashboard tile compares both numbers and would warn
# about drift for good if package.json stayed on the release number.
log "Continuing on ${DEV}-SNAPSHOT"
update_pom "$DEV" snapshot
update_package_json "${DEV}-SNAPSHOT"
git add "$POM_FILE" "$PACKAGE_JSON" "$PACKAGE_LOCK"
git commit -m "Weiter an v${DEV}"

echo ""
log "Done! Released ${NEXT}"
log "  Git tag v${NEXT}:  pom ${NEXT}, package.json ${NEXT} (package-lock.json mitgezogen)"
log "  danach:            pom + package.json ${DEV}-SNAPSHOT"
echo ""
log "To push: git push origin main \"v${NEXT}\"  (or: git push --follow-tags)"
