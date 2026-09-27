#!/usr/bin/env bash
# Tests fuer scripts/release-notes.sh (TODO 3c75962d). Laeuft in einem Wegwerf-Repo mit echten
# Merge-Commits, damit die Ueberblick-Zeilen und die Typ-Abschnitte gegen echtes Git-Verhalten
# geprueft werden, nicht gegen eine Attrappe.
#
# Run: bash scripts/tests/test-release-notes.sh   (exit 0 = alle Checks ok)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/scripts" "$TMP/docs"
cp "$REPO/scripts/release-notes.sh" "$TMP/scripts/release-notes.sh"

git -C "$TMP" init -q -b main
git -C "$TMP" config user.email test@example.com
git -C "$TMP" config user.name Test
git -C "$TMP" remote add origin git@github.com:example/mini-repo.git

echo "start" > "$TMP/README.md"
git -C "$TMP" add -A
git -C "$TMP" commit -qm "Start"
git -C "$TMP" tag -a v0.1.0 -m "Release v0.1.0"

# --- v0.1.0 -> v0.2.0: zwei Feature-Branches gemergt, dazwischen ein direkter Fix-Commit --------
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

echo "1) Erstes Release ohne Vorgaenger-Tag"
OUT1="$( (cd "$TMP" && bash scripts/release-notes.sh "" v0.1.0) )"; RC1=$?
check "Skript laeuft durch"                    '[ "$RC1" -eq 0 ]'
check "kein Vergleichs-Abschnitt"              '! grep -q "## Vergleich" <<< "$OUT1"'
check "Titel nennt das Tag"                    'grep -q "^# v0.1.0" <<< "$OUT1"'

echo "2) Release mit Vorgaenger: Ueberblick aus Merge-Commits"
OUT2="$( (cd "$TMP" && bash scripts/release-notes.sh v0.1.0 v0.2.0) )"; RC2=$?
check "Skript laeuft durch"                    '[ "$RC2" -eq 0 ]'
check "Ueberblick-Abschnitt vorhanden"          'grep -q "^## Überblick" <<< "$OUT2"'
check "Merge-Betreff Feature A gelistet"        'grep -qF "Merge branch '"'"'feat/a'"'"': Feature A" <<< "$OUT2"'
check "Merge-Betreff Bug B gelistet"            'grep -qF "Merge branch '"'"'fix/b'"'"': Bug B" <<< "$OUT2"'
check "TODO-Klammer aus Merge-Betreff entfernt" '! grep -q "11111111" <<< "$OUT2"'

echo "3) Typ-Abschnitte aus Nicht-Merge-Commits"
check "Features-Abschnitt vorhanden"           'grep -q "^### Features" <<< "$OUT2"'
check "Feature-Zeile ohne TODO-Klammer"         'grep -qF -- "- Feature A einbauen" <<< "$OUT2" && ! grep -qF -- "- Feature A einbauen (11111111 Feature A)" <<< "$OUT2"'
check "Fixes-Abschnitt vorhanden"               'grep -q "^### Fixes" <<< "$OUT2"'
check "Fix-Zeile ohne TODO-Klammer"             'grep -qF -- "- Bug B beheben" <<< "$OUT2" && ! grep -qF -- "22222222" <<< "$OUT2"'
check "Dokumentation-Abschnitt vorhanden"       'grep -q "^### Dokumentation" <<< "$OUT2"'
check "Doku-Zeile ohne bloße Hash-Klammer"      'grep -qF -- "- Notiz ergaenzen" <<< "$OUT2" && ! grep -qF -- "33333333" <<< "$OUT2"'

echo "4) Vergleichs-Link, wenn ein Vorgaenger-Tag existiert"
check "Vergleichs-Abschnitt vorhanden"          'grep -q "^## Vergleich" <<< "$OUT2"'
check "Link auf compare-View"                   'grep -qF "https://github.com/example/mini-repo/compare/v0.1.0...v0.2.0" <<< "$OUT2"'

echo "5) Fehlerfaelle"
OUT5="$( (cd "$TMP" && bash scripts/release-notes.sh "" v9.9.9) 2>&1 )"; RC5=$?
check "unbekanntes Tag bricht ab"               '[ "$RC5" -ne 0 ]'
OUT6="$( (cd "$TMP" && bash scripts/release-notes.sh) 2>&1 )"; RC6=$?
check "fehlendes Tag-Argument bricht ab"        '[ "$RC6" -ne 0 ]'

echo "6) Verhaltensaenderungen: entfaellt ohne docs/entwicklung.md"
check "kein Verhaltensaenderungen-Abschnitt ohne Doku" '! grep -q "## Verhaltensänderungen" <<< "$OUT2"'

echo "7) Verhaltensaenderungen: nur Eintraege nach dem Vorgaenger-Tag"
cat > "$TMP/docs/entwicklung.md" <<'DOCS'
## Versionierung & Releases

### Verhaltensänderungen

**Seit 2000-01-01 (Commit aaaaaaaa): Alter Eintrag vor v0.2.0.**
Text.

**Seit 2099-01-01 (Commit bbbbbbbb): Neuer Eintrag nach v0.2.0.**
Text.
DOCS
git -C "$TMP" add -A
git -C "$TMP" commit -qm "docs: Verhaltensaenderungen ergaenzen (44444444)"
git -C "$TMP" tag -a v0.3.0 -m "Release v0.3.0"

OUT7="$( (cd "$TMP" && bash scripts/release-notes.sh v0.2.0 v0.3.0) )"; RC7=$?
check "Skript laeuft durch"                            '[ "$RC7" -eq 0 ]'
check "Verhaltensaenderungen-Abschnitt vorhanden"       'grep -q "^## Verhaltensänderungen" <<< "$OUT7"'
check "alter Eintrag (vor v0.2.0) fehlt"                '! grep -q "Alter Eintrag" <<< "$OUT7"'
check "neuer Eintrag (nach v0.2.0) gelistet"            'grep -q "Neuer Eintrag nach v0.2.0" <<< "$OUT7"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
