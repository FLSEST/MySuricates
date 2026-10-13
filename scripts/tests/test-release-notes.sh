#!/usr/bin/env bash
# Tests for scripts/release-notes.sh (TODO 3c75962d). Runs in a throwaway repo with real
# merge commits so that the overview lines and the type sections are checked against real git behavior,
# not against a dummy.
#
# Run: bash scripts/tests/test-release-notes.sh   (exit 0 = all checks ok)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/scripts/lib" "$TMP/docs/en"
cp "$REPO/scripts/release-notes.sh" "$TMP/scripts/release-notes.sh"
cp "$REPO/scripts/lib/strip-todo-brackets.py" "$TMP/scripts/lib/strip-todo-brackets.py"

git -C "$TMP" init -q -b main
git -C "$TMP" config user.email test@example.com
git -C "$TMP" config user.name Test
git -C "$TMP" remote add origin git@github.com:example/mini-repo.git

echo "start" > "$TMP/README.md"
git -C "$TMP" add -A
git -C "$TMP" commit -qm "Start"
git -C "$TMP" tag -a v0.1.0 -m "Release v0.1.0"

# --- v0.1.0 -> v0.2.0: two feature branches merged, with a direct fix commit in between ----
git -C "$TMP" checkout -q -b feat/a
echo "a" >> "$TMP/README.md"
git -C "$TMP" commit -qam "feat(a): Feature A einbauen (11111111 Feature A)"
git -C "$TMP" checkout -q main
git -C "$TMP" merge --no-ff -q -m "Merge branch 'feat/a': Feature A (11111111 Feature A)" feat/a

git -C "$TMP" checkout -q -b fix/b
echo "b" >> "$TMP/README.md"
git -C "$TMP" commit -qam "fix(b): Bug B beheben (22222222 Bug B)"
git -C "$TMP" checkout -q main
git -C "$TMP" merge --no-ff -q -m "Merge branch 'fix/b': Bug B (22222222 Bug B)" fix/b

echo "direct" >> "$TMP/README.md"
git -C "$TMP" commit -qam "docs: Notiz ergaenzen (33333333)"

git -C "$TMP" tag -a v0.2.0 -m "Release v0.2.0"

echo "1) First release without a predecessor tag: one short paragraph, no lists"
OUT1="$( (cd "$TMP" && bash scripts/release-notes.sh "" v0.2.0) )"; RC1=$?
check "script runs through"                    '[ "$RC1" -eq 0 ]'
check "title names the tag"                    'grep -q "^# v0.2.0" <<< "$OUT1"'
check "says it is the first public release"    'grep -qF "First public release." <<< "$OUT1"'
check "no sections and no list items"          '! grep -qE "^##|^- " <<< "$OUT1"'

echo "2) Release with a predecessor: overview from the merge commits"
OUT2="$( (cd "$TMP" && bash scripts/release-notes.sh v0.1.0 v0.2.0) )"; RC2=$?
check "script runs through"                    '[ "$RC2" -eq 0 ]'
check "overview section present"               'grep -q "^## Overview" <<< "$OUT2"'
check "merge subject feature A listed"         'grep -qF "Merge branch '"'"'feat/a'"'"': Feature A" <<< "$OUT2"'
check "merge subject bug B listed"             'grep -qF "Merge branch '"'"'fix/b'"'"': Bug B" <<< "$OUT2"'
check "TODO bracket removed from merge subject" '! grep -q "11111111" <<< "$OUT2"'

echo "3) Type sections from the non-merge commits"
check "changes-by-type section present"        'grep -q "^## Changes by type" <<< "$OUT2"'
check "features section present"               'grep -q "^### Features" <<< "$OUT2"'
check "feature line without TODO bracket"      'grep -qF -- "- Feature A einbauen" <<< "$OUT2" && ! grep -qF -- "- Feature A einbauen (11111111 Feature A)" <<< "$OUT2"'
check "fixes section present"                  'grep -q "^### Fixes" <<< "$OUT2"'
check "fix line without TODO bracket"          'grep -qF -- "- Bug B beheben" <<< "$OUT2" && ! grep -qF -- "22222222" <<< "$OUT2"'
check "documentation section present"          'grep -q "^### Documentation" <<< "$OUT2"'
check "docs line without bare hash bracket"    'grep -qF -- "- Notiz ergaenzen" <<< "$OUT2" && ! grep -qF -- "33333333" <<< "$OUT2"'
check "no German heading left"                 '! grep -qE "^#+ .*(Überblick|Änderungen|Dokumentation|Verhaltens|Vergleich)" <<< "$OUT2"'

echo "4) Compare link when a predecessor tag exists"
check "comparison section present"             'grep -q "^## Comparison" <<< "$OUT2"'
check "link to the compare view"               'grep -qF "https://github.com/example/mini-repo/compare/v0.1.0...v0.2.0" <<< "$OUT2"'

echo "5) Error cases"
OUT5="$( (cd "$TMP" && bash scripts/release-notes.sh "" v9.9.9) 2>&1 )"; RC5=$?
check "unknown tag aborts"                     '[ "$RC5" -ne 0 ]'
OUT6="$( (cd "$TMP" && bash scripts/release-notes.sh) 2>&1 )"; RC6=$?
check "missing tag argument aborts"           '[ "$RC6" -ne 0 ]'

echo "6) Behavior changes: omitted without docs/en/entwicklung.md"
check "no behavior-changes section without the doc" '! grep -q "## Behavior changes" <<< "$OUT2"'

echo "7) Behavior changes: only entries after the predecessor tag, from the English doc"
cat > "$TMP/docs/en/entwicklung.md" <<'DOCS'
## Versioning & Releases

### Behavior Changes

**Since 2000-01-01 (commit aaaaaaaa): Old entry before v0.2.0.**
Text.

**Since 2099-01-01 (commit bbbbbbbb): New entry after v0.2.0.**
Text.
DOCS
# The German source must not be read: its entries would put German into the notes.
printf '**Seit 2099-01-01 (Commit cccccccc): Deutscher Eintrag.**\n' > "$TMP/docs/entwicklung.md"
git -C "$TMP" add -A
git -C "$TMP" commit -qm "docs: Verhaltensaenderungen ergaenzen (44444444)"
git -C "$TMP" tag -a v0.3.0 -m "Release v0.3.0"

OUT7="$( (cd "$TMP" && bash scripts/release-notes.sh v0.2.0 v0.3.0) )"; RC7=$?
check "script runs through"                            '[ "$RC7" -eq 0 ]'
check "behavior-changes section present"               'grep -q "^## Behavior changes" <<< "$OUT7"'
check "old entry (before v0.2.0) missing"              '! grep -q "Old entry" <<< "$OUT7"'
check "new entry (after v0.2.0) listed"                'grep -q "New entry after v0.2.0" <<< "$OUT7"'
check "no entry from the German doc"                   '! grep -q "Deutscher Eintrag" <<< "$OUT7"'
check "details link to the English section"            'grep -qF "docs/en/entwicklung.md#behavior-changes" <<< "$OUT7"'

echo "8) Several short ids, nested bracket in the title, a normal bracket stays"
# Pure short-id list without title (series merge format, e.g. "series/w3-…: … (id, id, id, id)")
git -C "$TMP" checkout -q -b feat/series
echo "series" >> "$TMP/README.md"
git -C "$TMP" commit -qam "feat(series): Themenbuendel A, B, C, D"
git -C "$TMP" checkout -q main
git -C "$TMP" merge --no-ff -q -m "Merge series/w3-x: Themenbuendel A, B, C (66666666, 77777777, 88888888)" feat/series

# Title that itself contains a parenthesis (e.g. "(448 statt hoechstens 435 Zeilen)")
echo "nested" >> "$TMP/README.md"
git -C "$TMP" commit -qam "refactor(x): Modellstatistik verschoben (99999999 Titel mit (Klammer) drin)"

# Normal parenthesis without short id stays (type must be in TYPE_ORDER, otherwise the
# commit drops out of the type sections completely instead of merely staying unstripped)
echo "normal" >> "$TMP/README.md"
git -C "$TMP" commit -qam "fix(x): Abhaengigkeit aktualisiert (kein Kuerzel)"

# Short id at the end of a parenthesis with running text before it (e.g. "(Stand 2026-09-22, 8f28fad0)")
echo "trailing" >> "$TMP/README.md"
git -C "$TMP" commit -qam "docs(readme): Sync nachgezogen (Stand 2026-09-22, aaaaaaaa)"

git -C "$TMP" tag -a v0.4.0 -m "Release v0.4.0"

OUT8="$( (cd "$TMP" && bash scripts/release-notes.sh v0.3.0 v0.4.0) )"; RC8=$?
check "script runs through"                              '[ "$RC8" -eq 0 ]'
check "series merge subject without short-id list"       'grep -qF -- "Merge series/w3-x: Themenbuendel A, B, C" <<< "$OUT8" && ! grep -qE "66666666|77777777|88888888" <<< "$OUT8"'
check "nested bracket in the title removed completely"   'grep -qF -- "- Modellstatistik verschoben" <<< "$OUT8" && ! grep -qF -- "99999999" <<< "$OUT8" && ! grep -qF -- "Klammer" <<< "$OUT8"'
check "normal bracket without short id stays"           'grep -qF -- "- Abhaengigkeit aktualisiert (kein Kuerzel)" <<< "$OUT8"'
check "short id at the end after running text removed"  'grep -qF -- "- Sync nachgezogen" <<< "$OUT8" && ! grep -qF -- "aaaaaaaa" <<< "$OUT8" && ! grep -qF -- "Stand 2026-09-22" <<< "$OUT8"'
# Behavior-change entries come from docs/entwicklung.md and deliberately quote their commit/
# TODO hash (see the convention there) — the only allowed places outside
# that are the compare link (tag names without hex) itself.
check "no 8-hex short id outside the compare link and behavior changes" \
  '[ -z "$(grep -E "[0-9a-f]{8}" <<< "$OUT8" | grep -v "compare/" | grep -v "^- \*\*Since ")" ]'

echo "9) Slug of the links: set from outside, empty, local origin"
OUT9="$( (cd "$TMP" && RELEASE_NOTES_REPO_SLUG=public/repo bash scripts/release-notes.sh v0.1.0 v0.2.0) )"
check "a set slug replaces origin in the compare link"  'grep -qF "https://github.com/public/repo/compare/v0.1.0...v0.2.0" <<< "$OUT9" && ! grep -qF "example/mini-repo" <<< "$OUT9"'
OUT10="$( (cd "$TMP" && RELEASE_NOTES_REPO_SLUG= bash scripts/release-notes.sh v0.1.0 v0.2.0) )"
check "empty slug: no comparison section"              '! grep -q "## Comparison" <<< "$OUT10"'
git -C "$TMP" remote set-url origin "$TMP/elsewhere.git"
OUT11="$( (cd "$TMP" && bash scripts/release-notes.sh v0.1.0 v0.2.0) )"
check "local origin: no broken GitHub link"            '! grep -q "github.com" <<< "$OUT11"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
