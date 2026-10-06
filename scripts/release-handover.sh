#!/usr/bin/env bash
# Release handover: brings the tree of a release tag from the working repo as ONE checked
# commit with an annotated tag into an existing clone of the public repository.
#
# The public repository gets no working history, but one squashed commit per release --
# what arrives there is always a checked state. Flow:
#   1. Preconditions: tag in the working repo, target clone clean, on a branch, not behind
#      its upstream, tag still free there, author/committer in noreply form, private lists present.
#   2. Export: `git archive <tag>` without the paths from scripts/release-handover.exclude (version
#      from the tag). In the target the tracked content is replaced -- `.git` and `release-notes/`
#      (the notes of earlier releases) stay, ignored files (build caches) too.
#   3. No change compared to the target state: abort cleanly (exit 3), nothing is left behind.
#   4. Notes from scripts/release-notes.sh to release-notes/<tag>.md (the release action in the
#      public repo takes this file), commit "Release <tag>".
#   5. Gates on exactly this commit: public-hygiene --tracked (with private lists),
#      pool-neutrality, README sync state (README.md reproduces the README.de.md of the tag),
#      gitleaks (if installed), doc gates (pre-push-checks.sh --tree),
#      with --with-build additionally mvn test, tsc and lint in the target.
#      A finding reverts the commit -- the target is left as it was before.
#   6. Annotated tag. NO push: the script ends with the push command.
#
# Usage: scripts/release-handover.sh [options] <tag> <target-clone>
#        scripts/release-handover.sh --discard <tag> <target-clone>
#   --discard                     revert an unpushed handover (commit + tag in the target)
#   --with-build                  additionally backend tests and frontend type check/lint in the target
#   --prev <tag>                  predecessor for the notes ("" = first release); default: the
#                                 most recently handed-over release (highest v* tag before it that
#                                 also exists in the target), for the first handover the tag right before it
#   --source <repo>               working repo (default: the repo of this script)
#   --allow-missing-private-lists continue without private hygiene lists (for trial runs only)
# Exit: 0 = ready to push (or discarded), 1 = aborted (finding or precondition), 2 = usage error,
#       3 = nothing to hand over
#
# Description and workflow: docs/entwicklung.md → „Übergabe ins öffentliche Repo".
set -uo pipefail

# Two repos, both addressed via -C: an inherited GIT_DIR (e.g. from a hook) would otherwise steer
# every call into the same repo.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="$(cd "$SCRIPT_DIR/.." && pwd)"
EXCLUDE_PATH="scripts/release-handover.exclude"
NOREPLY_REGEX='@users\.noreply\.github\.com$'
EMPTY_TREE="4b825dc642cb6eb9a060e54bf8d69288fbee4904"

log()  { echo "[handover] $*"; }
die()  { echo "[handover] ABBRUCH: $*" >&2; exit 1; }
usage() {
    sed -n '/^# Usage:/,/^#       3 = /p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}
usage_die() { echo "[handover] $*" >&2; usage >&2; exit 2; }

WITH_BUILD=0
DISCARD=0
ALLOW_MISSING_LISTS=0
PREV=""
PREV_SET=0
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        --with-build) WITH_BUILD=1 ;;
        --discard) DISCARD=1 ;;
        --allow-missing-private-lists) ALLOW_MISSING_LISTS=1 ;;
        --prev)   [ $# -ge 2 ] || usage_die "--prev braucht einen Wert"; PREV="$2"; PREV_SET=1; shift ;;
        --source) [ $# -ge 2 ] || usage_die "--source braucht einen Wert"; SOURCE="$2"; shift ;;
        -h|--help) usage; exit 0 ;;
        # An empty argument (e.g. an unset workflow variable) is not a value.
        "") ;;
        -*) usage_die "Unbekannte Option: $1" ;;
        *) POSITIONAL+=("$1") ;;
    esac
    shift
done
[ "${#POSITIONAL[@]}" -eq 2 ] || usage_die "Erwartet genau zwei Argumente: <tag> <ziel-klon>"
TAG="${POSITIONAL[0]}"
TARGET_IN="${POSITIONAL[1]}"

# ── 1. Preconditions ───────────────────────────────────────────────────────────────────────────────
# The tag becomes a file name (release-notes/<tag>.md) and a ref in the target -- release form only.
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
    || usage_die "Kein Release-Tag der Form vX.Y.Z: '$TAG'"

[ -d "$TARGET_IN" ] || die "Ziel-Klon fehlt: $TARGET_IN"
TARGET="$(cd "$TARGET_IN" && pwd -P)"
TOP="$(git -C "$TARGET" rev-parse --show-toplevel 2>/dev/null)" || die "Ziel ist kein Git-Klon: $TARGET"
[ "$(cd "$TOP" && pwd -P)" = "$TARGET" ] || die "Ziel muss die Wurzel eines Klons sein, nicht ein Unterordner: $TARGET"
BRANCH="$(git -C "$TARGET" symbolic-ref -q --short HEAD)" || die "Ziel steht auf keinem Branch (detached HEAD)"
OLD="$(git -C "$TARGET" rev-parse -q --verify HEAD || true)"

# ── --discard: revert an approved but unpushed handover ─────────────────────────────────────────────
# For rejection in the approval: without it, commit + tag would stay in the clone and the next
# run for the same tag would abort. Only what clearly stems from the handover is taken back.
if [ "$DISCARD" -eq 1 ]; then
    git -C "$TARGET" rev-parse -q --verify "refs/tags/${TAG}" >/dev/null || die "Tag ${TAG} gibt es im Ziel nicht — nichts zu verwerfen"
    [ -n "$OLD" ] && [ "$(git -C "$TARGET" rev-parse "${TAG}^{commit}")" = "$OLD" ] \
        || die "Tag ${TAG} zeigt nicht auf HEAD von ${BRANCH} — von Hand prüfen"
    [ "$(git -C "$TARGET" log -1 --format=%s "$OLD")" = "Release ${TAG}" ] \
        || die "HEAD ist nicht der Commit \"Release ${TAG}\" — von Hand prüfen"
    [ -z "$(git -C "$TARGET" status --porcelain 2>/dev/null)" ] || die "Ziel-Klon nicht sauber — von Hand prüfen"
    # A push is what a remote-tracking ref already contains (offline: state of the last fetch/push).
    if [ -n "$(git -C "$TARGET" for-each-ref --contains "$OLD" refs/remotes 2>/dev/null)" ]; then
        die "Commit ist bereits auf einem Remote — nicht mehr verwerfbar"
    fi
    git -C "$TARGET" tag -d "$TAG" >/dev/null || die "Konnte das Tag nicht löschen"
    if git -C "$TARGET" rev-parse -q --verify "${OLD}~1" >/dev/null; then
        git -C "$TARGET" reset -q --hard "${OLD}~1" || die "Zurücksetzen gescheitert"
    else
        # First handover into an empty repo: back to the unborn branch, drop the files of the commit.
        (cd "$TARGET" && git ls-files -z | xargs -0 rm -f -- && git update-ref -d "refs/heads/${BRANCH}" \
            && git read-tree --empty && git clean -fdq) || die "Zurücksetzen gescheitert"
    fi
    log "Verworfen: Commit und Tag ${TAG} aus ${TARGET} entfernt (${BRANCH} steht wieder auf dem Stand davor)."
    exit 0
fi

git -C "$SOURCE" rev-parse --git-dir >/dev/null 2>&1 || die "Arbeits-Repo ist kein Git-Repository: $SOURCE"
SOURCE="$(git -C "$SOURCE" rev-parse --show-toplevel)"
git -C "$SOURCE" rev-parse -q --verify "refs/tags/${TAG}" >/dev/null \
    || die "Tag ${TAG} gibt es im Arbeits-Repo nicht ($SOURCE)"
TAG_COMMIT="$(git -C "$SOURCE" rev-parse "${TAG}^{commit}")"
SRC_COMMON="$(cd "$SOURCE" && cd "$(git rev-parse --git-common-dir)" && pwd -P)"
TGT_COMMON="$(cd "$TARGET" && cd "$(git rev-parse --git-common-dir)" && pwd -P)"
[ "$SRC_COMMON" != "$TGT_COMMON" ] || die "Ziel ist das Arbeits-Repo selbst (oder einer seiner Worktrees)"

if git -C "$TARGET" rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    die "Tag ${TAG} gibt es im Ziel schon — bereits übergeben? Nicht gepusht und verwerfen: scripts/release-handover.sh --discard ${TAG} ${TARGET}"
fi
if [ -n "$(git -C "$TARGET" status --porcelain 2>/dev/null)" ]; then
    die "Ziel-Klon hat uncommittete oder ungetrackte Dateien — erst aufräumen (git status in $TARGET)"
fi
if [ -n "$OLD" ] && git -C "$TARGET" rev-parse -q --verify '@{u}' >/dev/null 2>&1; then
    BEHIND="$(git -C "$TARGET" rev-list --count 'HEAD..@{u}' 2>/dev/null || echo 0)"
    [ "$BEHIND" = "0" ] || die "Ziel liegt ${BEHIND} Commit(s) hinter seinem Upstream — erst git pull in $TARGET"
fi

# Author and committer only in GitHub noreply form, as the pre-push gate in the working repo requires.
ident_email() {  # $1 = GIT_AUTHOR_IDENT | GIT_COMMITTER_IDENT
    local ident
    ident="$(git -C "$TARGET" var "$1" 2>/dev/null)" || return 1
    ident="${ident#*<}"
    printf '%s\n' "${ident%%>*}"
}
for role in GIT_AUTHOR_IDENT GIT_COMMITTER_IDENT; do
    mail="$(ident_email "$role")" || die "Ziel hat keine Git-Identität (${role}) — git -C $TARGET config user.email '<id>+<login>@users.noreply.github.com'"
    [[ "$mail" =~ $NOREPLY_REGEX ]] \
        || die "${role} im Ziel ist keine GitHub-noreply-Adresse — git -C $TARGET config user.email '<id>+<login>@users.noreply.github.com'"
done

# Private hygiene lists: the target has no untracked repo copies of them. If they exist in the
# working repo, we pass them through via the same variables the gate itself knows.
if [ -z "${HYGIENE_PRIVATE_LIST:-}" ] && [ -f "$SOURCE/scripts/ci/hygiene-private.txt" ]; then
    export HYGIENE_PRIVATE_LIST="$SOURCE/scripts/ci/hygiene-private.txt"
fi
if [ -z "${HYGIENE_PRIVATE_TERMS:-}" ] && [ -f "$SOURCE/scripts/ci/hygiene-private-terms.txt" ]; then
    export HYGIENE_PRIVATE_TERMS="$SOURCE/scripts/ci/hygiene-private-terms.txt"
fi
private_list_found() {  # $1 = value of the variable, $2 = file name under ~/.config/mysuricates
    [ -n "$1" ] && [ -f "$1" ] && return 0
    [ -f "${XDG_CONFIG_HOME:-${HOME:-}/.config}/mysuricates/$2" ]
}
MISSING_LISTS=""
private_list_found "${HYGIENE_PRIVATE_LIST:-}" hygiene-private.txt || MISSING_LISTS="${MISSING_LISTS} hygiene-private.txt"
private_list_found "${HYGIENE_PRIVATE_TERMS:-}" hygiene-private-terms.txt || MISSING_LISTS="${MISSING_LISTS} hygiene-private-terms.txt"
if [ -n "$MISSING_LISTS" ]; then
    if [ "$ALLOW_MISSING_LISTS" -eq 1 ]; then
        log "WARNUNG: private Liste(n) fehlen:${MISSING_LISTS} — die Gates prüfen ohne sie (--allow-missing-private-lists)"
    else
        die "private Liste(n) fehlen:${MISSING_LISTS} — ohne sie prüft das Hygiene-Gate keine privaten Namen (\$HYGIENE_PRIVATE_LIST/\$HYGIENE_PRIVATE_TERMS oder ~/.config/mysuricates/)"
    fi
fi

[ -f "$SOURCE/scripts/release-notes.sh" ] || die "scripts/release-notes.sh fehlt im Arbeits-Repo"

WORK="$(mktemp -d)"
ROLLBACK=0
COMMITTED=0
on_exit() {
    local rc=$?
    if [ "$ROLLBACK" -eq 1 ]; then
        rollback
        log "Ziel zurückgesetzt auf den Stand vor dem Lauf"
    fi
    rm -rf "$WORK"
    exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

# Exclude list: the version from the tag, otherwise (older tag) the one from the working tree.
if git -C "$SOURCE" cat-file -e "${TAG}:${EXCLUDE_PATH}" 2>/dev/null; then
    git -C "$SOURCE" show "${TAG}:${EXCLUDE_PATH}" > "$WORK/exclude"
    EXCLUDE_ORIGIN="${TAG}:${EXCLUDE_PATH}"
elif [ -f "$SOURCE/$EXCLUDE_PATH" ]; then
    cp "$SOURCE/$EXCLUDE_PATH" "$WORK/exclude"
    EXCLUDE_ORIGIN="${EXCLUDE_PATH} (Arbeitsbaum — das Tag trägt keine eigene Liste)"
else
    die "Ausschlussliste fehlt: ${EXCLUDE_PATH} weder im Tag noch im Arbeitsbaum"
fi
PATHSPECS=()
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] && continue
    line="${line#/}"
    line="${line%/}"
    PATHSPECS+=(":(exclude,glob)${line}")
done < "$WORK/exclude"

# Order by semver from the working repo (it has every tag). The most recently handed-over
# release is the highest tag before it that also exists in the target -- not simply the tag right before it:
# if a handover failed, its changes would otherwise appear in no public notes.
# A later tag that already exists in the target means: something newer was already handed over.
PUBLISHED_PREV=""
SEMVER_PREV=""
SEEN_TAG=0
while IFS= read -r t; do
    [ -z "$t" ] && continue
    if [ "$t" = "$TAG" ]; then SEEN_TAG=1; continue; fi
    in_target=0
    git -C "$TARGET" rev-parse -q --verify "refs/tags/${t}" >/dev/null && in_target=1
    if [ "$SEEN_TAG" -eq 0 ]; then
        SEMVER_PREV="$t"
        [ "$in_target" -eq 1 ] && PUBLISHED_PREV="$t"
    elif [ "$in_target" -eq 1 ]; then
        die "Im Ziel liegt schon das neuere Release ${t} — ${TAG} käme danach als Rückschritt"
    fi
done < <(git -C "$SOURCE" tag --list 'v[0-9]*' --sort=v:refname)

# Predecessor for the notes: the most recently handed-over release, without one (first handover) the tag
# right before it in the working repo.
if [ "$PREV_SET" -eq 0 ]; then
    PREV="${PUBLISHED_PREV:-$SEMVER_PREV}"
fi

# Links in the notes point to the public repo, not to the working repo.
TARGET_URL="$(git -C "$TARGET" config --get remote.origin.url 2>/dev/null || true)"
case "$TARGET_URL" in
    git@github.com:*|https://github.com/*|ssh://git@github.com/*)
        TARGET_SLUG="$(sed -E 's#^git@github\.com:##; s#^ssh://git@github\.com/##; s#^https://github\.com/##; s#\.git$##; s#/$##' <<< "$TARGET_URL")" ;;
    *) TARGET_SLUG="" ;;
esac

log "Arbeits-Repo:  $SOURCE"
log "Tag:           ${TAG} ($(git -C "$SOURCE" rev-parse --short "$TAG_COMMIT"))"
log "Vorgänger:     ${PREV:-keiner}"
if [ -n "$OLD" ]; then
    OLD_LABEL="${BRANCH} ($(git -C "$TARGET" rev-parse --short "$OLD"))"
else
    OLD_LABEL="${BRANCH} (noch ohne Commit)"
fi
log "Ziel-Klon:     ${TARGET}, ${OLD_LABEL}"
log "Ausschluss:    ${EXCLUDE_ORIGIN}, ${#PATHSPECS[@]} Muster"

# ── 2. Export ─────────────────────────────────────────────────────────────────────────────────────
git -C "$SOURCE" archive --format=tar -o "$WORK/tree.tar" "$TAG" -- . "${PATHSPECS[@]}" \
    || die "git archive ist gescheitert (Muster in ${EXCLUDE_PATH} prüfen)"
git -C "$SOURCE" ls-tree -r -z --name-only "$TAG" > "$WORK/all.nul"
# File list of the export (NUL-separated, exactly as in the archive) and the excluded paths.
python3 - "$WORK/tree.tar" "$WORK/all.nul" "$WORK/files.nul" "$WORK/excluded.txt" <<'PY' \
    || die "Archiv nicht lesbar"
import sys, tarfile
tar, all_nul, files_nul, excluded_txt = sys.argv[1:5]
with tarfile.open(tar) as t:
    names = [m.name for m in t.getmembers() if not m.isdir()]
with open(files_nul, 'wb') as f:
    f.write(b''.join(n.encode('utf-8', 'surrogateescape') + b'\0' for n in names))
kept = set(names)
with open(all_nul, 'rb') as f:
    every = [p.decode('utf-8', 'surrogateescape') for p in f.read().split(b'\0') if p]
with open(excluded_txt, 'w') as f:
    f.write(''.join(p + '\n' for p in every if p not in kept))
PY
FILE_COUNT="$(tr -cd '\0' < "$WORK/files.nul" | wc -c | tr -d ' ')"
EXCLUDED_COUNT="$(grep -c . "$WORK/excluded.txt" || true)"
[ "$FILE_COUNT" -gt 0 ] || die "Der Export ist leer — schließt die Ausschlussliste alles aus?"
if tr '\0' '\n' < "$WORK/files.nul" | grep -q '^release-notes/'; then
    die "Das Tag enthält release-notes/ — das Verzeichnis gehört dem öffentlichen Repo (Notizen früherer Releases)"
fi
log "Export:        ${FILE_COUNT} Datei(en), ${EXCLUDED_COUNT} ausgeschlossen"
if [ "$EXCLUDED_COUNT" -gt 0 ]; then
    head -50 "$WORK/excluded.txt" | sed 's/^/                 - /'
    [ "$EXCLUDED_COUNT" -gt 50 ] && echo "                 … und $((EXCLUDED_COUNT - 50)) weitere"
fi

# Notes before any change to the target: if the script fails, the target stays untouched.
RELEASE_NOTES_REPO_SLUG="$TARGET_SLUG" bash "$SOURCE/scripts/release-notes.sh" "$PREV" "$TAG" > "$WORK/notes.md" \
    || die "scripts/release-notes.sh ist gescheitert"

# rollback -- reverts everything this run changed in the target: the written files,
# the commit and the index. Ignored files (build caches) stay as they were.
rollback() {
    ROLLBACK=0
    (
        cd "$TARGET" || exit 1
        xargs -0 rm -f -- < "$WORK/files.nul" 2>/dev/null
        rm -f -- "release-notes/${TAG}.md"
        if [ -n "$OLD" ]; then
            git reset -q --hard "$OLD"
        else
            [ "$COMMITTED" -eq 1 ] && git update-ref -d "refs/heads/${BRANCH}"
            git read-tree --empty
        fi
        git clean -fdq
    )
}

ROLLBACK=1
if [ -n "$OLD" ]; then
    git -C "$TARGET" rm -r -q --ignore-unmatch -- . ':(exclude)release-notes' \
        || die "Konnte den bisherigen Inhalt des Ziels nicht entfernen"
fi
tar -xf "$WORK/tree.tar" -C "$TARGET" || die "Entpacken ins Ziel gescheitert"
# -f: also files that fall under an ignore rule but are tracked in the tag (e.g. the
# tray icons under *.png) belong in the commit -- the tree should equal the tag. Staged is
# exactly the list from the archive, never a blanket "git add -A": that would pick up build caches.
# core.autocrlf=false: a global setting "input" would otherwise convert CRLF files (mvnw.cmd,
# XSD templates) when staging -- the public state would silently deviate from the tag.
GIT_LITERAL_PATHSPECS=1 git -C "$TARGET" -c core.autocrlf=false -c core.safecrlf=false \
    add -f --pathspec-from-file="$WORK/files.nul" --pathspec-file-nul \
    || die "Konnte den Export im Ziel nicht stagen"

# ── 3. No change → abort cleanly ──────────────────────────────────────────────────────────────
if [ -n "$OLD" ] && git -C "$TARGET" diff --cached --quiet "$OLD" -- . ':(exclude)release-notes'; then
    rollback
    log "Nichts zu übergeben: ${TAG} ergibt im Ziel denselben Stand wie ${OLD_LABEL}."
    echo "handover_status: NOTHING_TO_DO"
    exit 3
fi

# ── 4. Notes + commit ────────────────────────────────────────────────────────────────────────────
mkdir -p "$TARGET/release-notes"
cp "$WORK/notes.md" "$TARGET/release-notes/${TAG}.md"
git -C "$TARGET" add -f -- "release-notes/${TAG}.md" || die "Konnte die Notizdatei nicht stagen"
if ! git -C "$TARGET" commit -q -m "Release ${TAG}" -m "Notizen: release-notes/${TAG}.md"; then
    die "Commit im Ziel gescheitert (Hook? Ausgabe oben)"
fi
COMMITTED=1
NEW="$(git -C "$TARGET" rev-parse HEAD)"
BASE="${OLD:-$EMPTY_TREE}"

# Fidelity: every transferred file has the same blob and mode in the commit as in the tag, and apart from
# release-notes/ nothing was added. Catches what Git might silently change when staging
# (line endings, filters from .gitattributes, a forgotten path).
git -C "$SOURCE" ls-tree -r -z --full-tree "$TAG" > "$WORK/src-tree.nul"
git -C "$TARGET" ls-tree -r -z --full-tree "$NEW" > "$WORK/tgt-tree.nul"
if ! python3 - "$WORK/src-tree.nul" "$WORK/tgt-tree.nul" "$WORK/files.nul" <<'PY'
import sys
def entries(path):
    out = {}
    for rec in open(path, 'rb').read().split(b'\0'):
        if not rec:
            continue
        meta, _, name = rec.partition(b'\t')
        mode, _, sha = meta.split(b' ')
        out[name] = (mode, sha)
    return out
src, tgt = entries(sys.argv[1]), entries(sys.argv[2])
files = [f for f in open(sys.argv[3], 'rb').read().split(b'\0') if f]
wanted = set(files)
bad = [f.decode(errors='replace') for f in files if src.get(f) != tgt.get(f)]
extra = [n.decode(errors='replace') for n in tgt if n not in wanted and not n.startswith(b'release-notes/')]
for f in bad[:20]:
    print(f"    weicht vom Tag ab: {f}")
for f in extra[:20]:
    print(f"    nicht aus dem Tag: {f}")
sys.exit(1 if bad or extra else 0)
PY
then
    die "Der Commit im Ziel gleicht dem Tag nicht (siehe oben) — nichts übergeben"
fi
log "Treue:         ${FILE_COUNT} Datei(en) mit Blob und Modus wie im Tag"

# ── 5. Gates on the commit ─────────────────────────────────────────────────────────────────────
GATE_FAIL=0
GATE_SUMMARY=()
run_gate() {  # $1 = name, then the command (runs in the target)
    local name="$1" out="$WORK/gate.out" rc warns
    shift
    echo "▶ ${name}"
    ( cd "$TARGET" && "$@" ) > "$out" 2>&1
    rc=$?
    if [ "$rc" -eq 0 ]; then
        warns="$(grep -E '^(  ! |::warning)' "$out" || true)"
        if [ -n "$warns" ]; then
            printf '%s\n' "$warns" | head -20 | sed 's/^/    /'
            GATE_SUMMARY+=("${name}: OK ($(printf '%s\n' "$warns" | grep -c .) Warnung(en))")
        else
            GATE_SUMMARY+=("${name}: OK")
        fi
        grep -E '(übersprungen|uebersprungen)' "$out" | head -5 | sed 's/^/    /'
    else
        tail -40 "$out" | sed 's/^/    /'
        GATE_SUMMARY+=("${name}: FEHLER (exit ${rc})")
        GATE_FAIL=1
    fi
}
missing_gate() {  # $1 = name, $2 = path
    echo "▶ ${1}"
    echo "    ${2} fehlt im übertragenen Stand — ein Gate, das nicht misst, ist nicht grün"
    GATE_SUMMARY+=("${1}: FEHLER (${2} fehlt)")
    GATE_FAIL=1
}

gate_script() {  # $1 = name, $2 = script path in the target, rest = arguments
    local name="$1" script="$2"
    shift 2
    if [ -f "$TARGET/$script" ]; then
        run_gate "$name" bash "$script" "$@"
    else
        missing_gate "$name" "$script"
    fi
}

gate_script "Public-Hygiene (--tracked)" scripts/ci/public-hygiene.sh --tracked
gate_script "Pool-Neutralität" scripts/ci/pool-neutrality.sh
# README sync (c13a942b): README.md must reproduce the README.de.md of the release. In the target there is
# no commit from the sync state -- it stays resolvable there only via the content hash, and
# that matches only if the sync happened before the tag. Tags before the script do not have it yet.
if [ -f "$TARGET/scripts/readme-sync/readme_sync.py" ]; then
    run_gate "README-Sync-Stand" python3 scripts/readme-sync/readme_sync.py --root . check --current
else
    echo "▶ README-Sync-Stand"
    echo "    scripts/readme-sync/readme_sync.py fehlt im Tag (älter als der Sync-Stand) — übersprungen"
    GATE_SUMMARY+=("README-Sync-Stand: übersprungen (Skript fehlt im Tag)")
fi

if command -v gitleaks >/dev/null 2>&1; then
    if [ -n "$OLD" ]; then LEAK_RANGE="${OLD}..${NEW}"; else LEAK_RANGE="$NEW"; fi
    LEAK_LABEL="gitleaks (Release-Commit $(git -C "$TARGET" rev-parse --short "$NEW"))"
    gitleaks_scan() {
        local rc
        gitleaks git --no-banner --redact --verbose --no-color --log-opts="$1" . ; rc=$?
        # 8.19+ the subcommand is called "git", older versions only know "detect".
        if [ "$rc" -gt 1 ]; then
            gitleaks detect --no-banner --redact --verbose --no-color --source . --log-opts="$1"; rc=$?
        fi
        return "$rc"
    }
    run_gate "$LEAK_LABEL" gitleaks_scan "$LEAK_RANGE"
else
    echo "▶ gitleaks"
    echo "    nicht installiert — Secret-Scan übersprungen (die CI des öffentlichen Repos scannt)"
    GATE_SUMMARY+=("gitleaks: übersprungen (nicht installiert)")
fi

gate_script "Doku-Gates (pre-push-checks --tree)" scripts/ci/pre-push-checks.sh --tree

if [ "$WITH_BUILD" -eq 1 ]; then
    build_backend() {
        command -v mvn >/dev/null 2>&1 || { echo "mvn fehlt im PATH"; return 2; }
        cd ai-automation-backend && mvn -q test
    }
    build_frontend() {
        command -v npm >/dev/null 2>&1 || { echo "npm fehlt im PATH"; return 2; }
        cd ai-automation-frontend || return 2
        # Fresh clone: dependencies exactly per lockfile. An existing node_modules (from
        # an earlier run, ignored and therefore not touched) is reused.
        if [ ! -d node_modules ]; then
            npm ci --no-audit --no-fund || return 1
        fi
        npx tsc --noEmit && npm run lint
    }
    if [ -d "$TARGET/ai-automation-backend" ]; then
        run_gate "Backend: mvn test" build_backend
    else
        missing_gate "Backend: mvn test" ai-automation-backend
    fi
    if [ -d "$TARGET/ai-automation-frontend" ]; then
        run_gate "Frontend: tsc + lint" build_frontend
    else
        missing_gate "Frontend: tsc + lint" ai-automation-frontend
    fi
else
    GATE_SUMMARY+=("Build/Tests: nicht angefordert (--with-build)")
fi

echo
echo "## Gates"
for s in "${GATE_SUMMARY[@]}"; do echo "- ${s}"; done
echo

if [ "$GATE_FAIL" -ne 0 ]; then
    echo "handover_status: FAILED"
    die "Gate-Befund — Commit zurückgenommen, kein Tag. Befund im Arbeits-Repo beheben, neues Tag, neuer Lauf."
fi

# ── 6. Tag ────────────────────────────────────────────────────────────────────────────────────────
git -C "$TARGET" tag -a "$TAG" -m "Release ${TAG}" "$NEW" || die "Konnte das Tag ${TAG} im Ziel nicht setzen"
ROLLBACK=0

echo "## Änderungen gegenüber ${OLD_LABEL}"
git -C "$TARGET" diff --shortstat "$BASE" "$NEW"
echo
echo "Verzeichnisse (Anteil geänderter Dateien):"
git -C "$TARGET" diff --dirstat=files,3 "$BASE" "$NEW" | sed 's/^/  /'
echo
NAME_STATUS="$(git -C "$TARGET" diff --name-status --no-renames "$BASE" "$NEW")"
NS_COUNT="$(printf '%s\n' "$NAME_STATUS" | grep -c .)"
echo "Dateien (A neu, M geändert, D entfernt):"
printf '%s\n' "$NAME_STATUS" | head -60 | sed 's/^/  /'
[ "$NS_COUNT" -gt 60 ] && echo "  … und $((NS_COUNT - 60)) weitere"
echo

echo "handover_status: READY"
echo "handover_tag: ${TAG}"
echo "handover_commit: ${NEW}"
echo "handover_branch: ${BRANCH}"
echo
log "Bereit. Commit $(git -C "$TARGET" rev-parse --short "$NEW") + annotiertes Tag ${TAG} liegen im Ziel, nichts ist gepusht."
if git -C "$TARGET" config --get remote.origin.url >/dev/null 2>&1; then
    log "Push (nach Freigabe):"
    printf '  git -C %q push --atomic origin %q %q\n' "$TARGET" "refs/heads/${BRANCH}" "refs/tags/${TAG}"
else
    log "Ziel hat kein Remote 'origin' — Push-Ziel selbst wählen:"
    printf '  git -C %q push --atomic <remote> %q %q\n' "$TARGET" "refs/heads/${BRANCH}" "refs/tags/${TAG}"
fi
