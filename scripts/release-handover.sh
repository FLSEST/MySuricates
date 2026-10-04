#!/usr/bin/env bash
# Release-Übergabe: bringt den Baum eines Release-Tags aus dem Arbeits-Repo als EINEN geprüften
# Commit samt annotiertem Tag in einen vorhandenen Klon des öffentlichen Repositorys.
#
# Das öffentliche Repository bekommt keine Arbeits-Historie, sondern je Release einen
# zusammengefassten Commit — was dort ankommt, ist immer ein geprüfter Stand. Ablauf:
#   1. Vorbedingungen: Tag im Arbeits-Repo, Ziel-Klon sauber, auf einem Branch, nicht hinter
#      seinem Upstream, Tag dort noch frei, Autor/Committer in noreply-Form, private Listen da.
#   2. Export: `git archive <tag>` ohne die Pfade aus scripts/release-handover.exclude (Fassung
#      aus dem Tag). Im Ziel wird der getrackte Inhalt ersetzt — `.git` und `release-notes/`
#      (die Notizen früherer Releases) bleiben, ignorierte Dateien (Build-Caches) auch.
#   3. Keine Änderung gegenüber dem Ziel-Stand: sauber abbrechen (Exit 3), nichts bleibt zurück.
#   4. Notizen aus scripts/release-notes.sh nach release-notes/<tag>.md (die Release-Action im
#      öffentlichen Repo nimmt diese Datei), Commit "Release <tag>".
#   5. Gates auf genau diesem Commit: public-hygiene --tracked (mit privaten Listen),
#      pool-neutrality, README-Sync-Stand (README.md gibt die README.de.md des Tags wieder),
#      gitleaks (falls installiert), Doku-Gates (pre-push-checks.sh --tree),
#      mit --with-build zusätzlich mvn test, tsc und lint im Ziel.
#      Ein Befund nimmt den Commit zurück — das Ziel steht danach wie vorher.
#   6. Annotiertes Tag. KEIN Push: Das Skript endet mit dem Push-Befehl.
#
# Usage: scripts/release-handover.sh [Optionen] <tag> <ziel-klon>
#        scripts/release-handover.sh --discard <tag> <ziel-klon>
#   --discard                     eine nicht gepushte Übergabe zurücknehmen (Commit + Tag im Ziel)
#   --with-build                  zusätzlich Backend-Tests und Frontend-Typprüfung/Lint im Ziel
#   --prev <tag>                  Vorgänger für die Notizen ("" = erstes Release); Standard: das
#                                 zuletzt übergebene Release (höchstes v*-Tag davor, das auch im
#                                 Ziel liegt), bei der ersten Übergabe das Tag direkt davor
#   --source <repo>               Arbeits-Repo (Standard: das Repo dieses Skripts)
#   --allow-missing-private-lists ohne private Hygiene-Listen weiter (nur für Proben)
# Exit: 0 = bereit zum Push (bzw. verworfen), 1 = abgebrochen (Befund oder Vorbedingung), 2 = Aufruffehler,
#       3 = nichts zu übergeben
#
# Beschreibung und Workflow dazu: docs/entwicklung.md → „Übergabe ins öffentliche Repo".
set -uo pipefail

# Zwei Repos, beide über -C angesprochen: ein geerbtes GIT_DIR (etwa aus einem Hook) lenkte sonst
# jeden Aufruf in dasselbe Repo.
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
        # Ein leeres Argument (etwa eine unbelegte Workflow-Variable) ist kein Wert.
        "") ;;
        -*) usage_die "Unbekannte Option: $1" ;;
        *) POSITIONAL+=("$1") ;;
    esac
    shift
done
[ "${#POSITIONAL[@]}" -eq 2 ] || usage_die "Erwartet genau zwei Argumente: <tag> <ziel-klon>"
TAG="${POSITIONAL[0]}"
TARGET_IN="${POSITIONAL[1]}"

# ── 1. Vorbedingungen ─────────────────────────────────────────────────────────────────────────────
# Das Tag wird Dateiname (release-notes/<tag>.md) und Ref im Ziel — nur die Release-Form.
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
    || usage_die "Kein Release-Tag der Form vX.Y.Z: '$TAG'"

[ -d "$TARGET_IN" ] || die "Ziel-Klon fehlt: $TARGET_IN"
TARGET="$(cd "$TARGET_IN" && pwd -P)"
TOP="$(git -C "$TARGET" rev-parse --show-toplevel 2>/dev/null)" || die "Ziel ist kein Git-Klon: $TARGET"
[ "$(cd "$TOP" && pwd -P)" = "$TARGET" ] || die "Ziel muss die Wurzel eines Klons sein, nicht ein Unterordner: $TARGET"
BRANCH="$(git -C "$TARGET" symbolic-ref -q --short HEAD)" || die "Ziel steht auf keinem Branch (detached HEAD)"
OLD="$(git -C "$TARGET" rev-parse -q --verify HEAD || true)"

# ── --discard: eine freigegebene, aber nicht gepushte Übergabe zurücknehmen ─────────────────────────
# Für die Ablehnung in der Freigabe: Ohne das bliebe Commit + Tag im Klon liegen, und der nächste
# Lauf für dasselbe Tag bräche ab. Genommen wird nur, was eindeutig von der Übergabe stammt.
if [ "$DISCARD" -eq 1 ]; then
    git -C "$TARGET" rev-parse -q --verify "refs/tags/${TAG}" >/dev/null || die "Tag ${TAG} gibt es im Ziel nicht — nichts zu verwerfen"
    [ -n "$OLD" ] && [ "$(git -C "$TARGET" rev-parse "${TAG}^{commit}")" = "$OLD" ] \
        || die "Tag ${TAG} zeigt nicht auf HEAD von ${BRANCH} — von Hand prüfen"
    [ "$(git -C "$TARGET" log -1 --format=%s "$OLD")" = "Release ${TAG}" ] \
        || die "HEAD ist nicht der Commit \"Release ${TAG}\" — von Hand prüfen"
    [ -z "$(git -C "$TARGET" status --porcelain 2>/dev/null)" ] || die "Ziel-Klon nicht sauber — von Hand prüfen"
    # Gepusht ist, was ein Remote-Tracking-Ref schon enthält (ohne Netz: Stand des letzten fetch/push).
    if [ -n "$(git -C "$TARGET" for-each-ref --contains "$OLD" refs/remotes 2>/dev/null)" ]; then
        die "Commit ist bereits auf einem Remote — nicht mehr verwerfbar"
    fi
    git -C "$TARGET" tag -d "$TAG" >/dev/null || die "Konnte das Tag nicht löschen"
    if git -C "$TARGET" rev-parse -q --verify "${OLD}~1" >/dev/null; then
        git -C "$TARGET" reset -q --hard "${OLD}~1" || die "Zurücksetzen gescheitert"
    else
        # Erste Übergabe in ein leeres Repo: zurück zum ungeborenen Branch, Dateien des Commits weg.
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

# Autor und Committer nur in GitHub-noreply-Form, wie das Pre-Push-Gate im Arbeits-Repo verlangt.
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

# Private Hygiene-Listen: im Ziel gibt es die untrackten Repo-Kopien nicht. Liegen sie im
# Arbeits-Repo, reichen wir sie über dieselben Variablen durch, die das Gate selbst kennt.
if [ -z "${HYGIENE_PRIVATE_LIST:-}" ] && [ -f "$SOURCE/scripts/ci/hygiene-private.txt" ]; then
    export HYGIENE_PRIVATE_LIST="$SOURCE/scripts/ci/hygiene-private.txt"
fi
if [ -z "${HYGIENE_PRIVATE_TERMS:-}" ] && [ -f "$SOURCE/scripts/ci/hygiene-private-terms.txt" ]; then
    export HYGIENE_PRIVATE_TERMS="$SOURCE/scripts/ci/hygiene-private-terms.txt"
fi
private_list_found() {  # $1 = Wert der Variablen, $2 = Dateiname unter ~/.config/mysuricates
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

# Ausschlussliste: die Fassung aus dem Tag, sonst (älteres Tag) die aus dem Arbeitsbaum.
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

# Reihenfolge nach Semver aus dem Arbeits-Repo (dort gibt es jedes Tag). Das zuletzt übergebene
# Release ist das höchste Tag davor, das auch im Ziel liegt — nicht einfach das Tag direkt davor:
# Scheiterte eine Übergabe, stünden deren Änderungen sonst in keinen öffentlichen Notizen.
# Ein Tag danach, das schon im Ziel liegt, heißt: Es wurde bereits Neueres übergeben.
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

# Vorgänger für die Notizen: das zuletzt übergebene Release, ohne eines (erste Übergabe) das Tag
# direkt davor im Arbeits-Repo.
if [ "$PREV_SET" -eq 0 ]; then
    PREV="${PUBLISHED_PREV:-$SEMVER_PREV}"
fi

# Links der Notizen zeigen ins öffentliche Repo, nicht ins Arbeits-Repo.
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
# Dateiliste des Exports (NUL-getrennt, exakt wie im Archiv) und die ausgeschlossenen Pfade.
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

# Notizen vor jeder Änderung am Ziel: scheitert das Skript, bleibt das Ziel unberührt.
RELEASE_NOTES_REPO_SLUG="$TARGET_SLUG" bash "$SOURCE/scripts/release-notes.sh" "$PREV" "$TAG" > "$WORK/notes.md" \
    || die "scripts/release-notes.sh ist gescheitert"

# rollback — nimmt alles zurück, was dieser Lauf im Ziel verändert hat: die geschriebenen Dateien,
# den Commit und den Index. Ignorierte Dateien (Build-Caches) bleiben, wie sie waren.
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
# -f: Auch Dateien, die unter eine Ignore-Regel fallen, aber im Tag getrackt sind (etwa die
# Tray-Icons unter *.png), gehören in den Commit — der Baum soll dem Tag gleichen. Gestaged wird
# genau die Liste aus dem Archiv, nie ein pauschales "git add -A": das nähme Build-Caches mit.
# core.autocrlf=false: Eine globale Einstellung "input" wandelte sonst CRLF-Dateien (mvnw.cmd,
# XSD-Vorlagen) beim Stagen um — der öffentliche Stand wiche still vom Tag ab.
GIT_LITERAL_PATHSPECS=1 git -C "$TARGET" -c core.autocrlf=false -c core.safecrlf=false \
    add -f --pathspec-from-file="$WORK/files.nul" --pathspec-file-nul \
    || die "Konnte den Export im Ziel nicht stagen"

# ── 3. Keine Änderung → sauber abbrechen ─────────────────────────────────────────────────────────
if [ -n "$OLD" ] && git -C "$TARGET" diff --cached --quiet "$OLD" -- . ':(exclude)release-notes'; then
    rollback
    log "Nichts zu übergeben: ${TAG} ergibt im Ziel denselben Stand wie ${OLD_LABEL}."
    echo "handover_status: NOTHING_TO_DO"
    exit 3
fi

# ── 4. Notizen + Commit ───────────────────────────────────────────────────────────────────────────
mkdir -p "$TARGET/release-notes"
cp "$WORK/notes.md" "$TARGET/release-notes/${TAG}.md"
git -C "$TARGET" add -f -- "release-notes/${TAG}.md" || die "Konnte die Notizdatei nicht stagen"
if ! git -C "$TARGET" commit -q -m "Release ${TAG}" -m "Notizen: release-notes/${TAG}.md"; then
    die "Commit im Ziel gescheitert (Hook? Ausgabe oben)"
fi
COMMITTED=1
NEW="$(git -C "$TARGET" rev-parse HEAD)"
BASE="${OLD:-$EMPTY_TREE}"

# Treue: Jede übertragene Datei hat im Commit denselben Blob und Modus wie im Tag, und außer
# release-notes/ ist nichts dazugekommen. Fängt ab, was Git beim Stagen still verändern könnte
# (Zeilenenden, Filter aus .gitattributes, ein vergessener Pfad).
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

# ── 5. Gates auf dem Commit ───────────────────────────────────────────────────────────────────────
GATE_FAIL=0
GATE_SUMMARY=()
run_gate() {  # $1 = Name, dann der Befehl (läuft im Ziel)
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
missing_gate() {  # $1 = Name, $2 = Pfad
    echo "▶ ${1}"
    echo "    ${2} fehlt im übertragenen Stand — ein Gate, das nicht misst, ist nicht grün"
    GATE_SUMMARY+=("${1}: FEHLER (${2} fehlt)")
    GATE_FAIL=1
}

gate_script() {  # $1 = Name, $2 = Skriptpfad im Ziel, Rest = Argumente
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
# README-Sync (c13a942b): README.md muss die README.de.md des Releases wiedergeben. Im Ziel gibt es
# den Commit aus dem Sync-Stand nicht — aufloesbar bleibt er dort nur ueber den Inhalts-Hash, und
# der passt nur, wenn vor dem Tag synchronisiert wurde. Tags vor dem Skript haben es noch nicht.
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
        # 8.19+ heißt der Unterbefehl "git", ältere Versionen kennen nur "detect".
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
        # Frischer Klon: Abhängigkeiten genau nach Lockfile. Ein vorhandenes node_modules (aus
        # einem früheren Lauf, ignoriert und deshalb nicht angefasst) wird weiterverwendet.
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
