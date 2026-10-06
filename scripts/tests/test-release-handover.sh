#!/usr/bin/env bash
# Tests for scripts/release-handover.sh. The script really runs: a throwaway working repo with
# tags and the real gate scripts (public-hygiene, pool-neutrality, pre-push-checks) and a
# throwaway "public" repo (local: bare + clone). Only the four docs gates that
# pre-push-checks.sh --tree calls are dummies — they need the whole project (the
# API endpoint comparison e.g. real controller classes and docs/api-referenz.md).
#
# Checked: exclusion list, content replaced (ignored build caches stay), commit with
# noreply author, annotated tag, notes file, abort on gate finding (default rule, private
# term list, docs gate) with full reset, abort without change, preconditions,
# and that nothing is ever pushed.
#
# Run: bash scripts/tests/test-release-handover.sh   (exit 0 = all checks ok)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

command -v python3 >/dev/null || { echo "python3 fehlt — Test kann das Skript nicht fahren"; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
NOREPLY="12345+tester@users.noreply.github.com"
# Hits of the default rules assembled so that this file itself stays clean.
DEFAULT_CREDENTIAL="change""me"
PRIVATE_TERM="Geheim""projekt"

SRC="$TMP/work"
PUBLIC_BARE="$TMP/public.git"
PUB="$TMP/pub"

# --- Working repo --------------------------------------------------------------------------------
mkdir -p "$SRC/scripts/ci" "$SRC/scripts/lib" "$SRC/scripts/public-readiness" "$SRC/docs" \
         "$SRC/internal" "$SRC/assets"
cp "$REPO/scripts/release-handover.sh" "$REPO/scripts/release-notes.sh" "$SRC/scripts/"
cp "$REPO/scripts/lib/strip-todo-brackets.py" "$REPO/scripts/lib/hygiene-rules.sh" \
   "$REPO/scripts/lib/pool-sanitizer.generated.sh" "$REPO/scripts/lib/block-types.generated.sh" \
   "$SRC/scripts/lib/"
cp "$REPO/scripts/ci/public-hygiene.sh" "$REPO/scripts/ci/pool-neutrality.sh" \
   "$REPO/scripts/ci/pre-push-checks.sh" "$SRC/scripts/ci/"
# Docs gate dummies: green, unless a file DOCS_BROKEN lies in the checked state.
cat > "$SRC/scripts/public-readiness/doc-facts-check.sh" <<'SH'
#!/usr/bin/env bash
[ ! -f DOCS_BROKEN ] || { echo "Doku-Zahlen passen nicht"; exit 1; }
SH
printf 'import sys\nsys.exit(0)\n' > "$SRC/scripts/public-readiness/docs_parity_check.py"
printf 'import sys\nsys.exit(0)\n' > "$SRC/scripts/docs-diagrams.py"
printf '#!/usr/bin/env bash\nexit 0\n' > "$SRC/scripts/public-readiness/api-endpoint-diff.sh"
printf '# Ausnahmen\nassets/logo.png\n' > "$SRC/scripts/ci/hygiene-allowlist.txt"
cat > "$SRC/scripts/release-handover.exclude" <<'EXC'
# Testliste
internal/
docs/notes-*.md
EXC
printf '*.png\nbuild-cache/\n' > "$SRC/.gitignore"
printf '# Security\n\nPlease use Private vulnerability reporting.\n' > "$SRC/SECURITY.md"
echo "Projekt" > "$SRC/README.md"
echo "bleibt bis v0.2.0" > "$SRC/docs/old.md"
echo "private Notiz" > "$SRC/docs/notes-intern.md"
# The excluded one deliberately contains a hit of the default rules: if the exclusion did
# not take effect, the hygiene gate would trigger on the very first run.
echo "password=${DEFAULT_CREDENTIAL}" > "$SRC/internal/plan.md"
printf 'PNG-Attrappe' > "$SRC/assets/logo.png"
# CRLF file (like mvnw.cmd): must arrive byte-identical, even if the target clone has core.autocrlf=input.
printf '@echo off\r\necho ok\r\n' > "$SRC/win.cmd"

git -C "$SRC" init -q -b main
git -C "$SRC" config user.email "$NOREPLY"
git -C "$SRC" config user.name Tester
git -C "$SRC" config commit.gpgsign false
git -C "$SRC" config tag.gpgsign false
git -C "$SRC" config core.autocrlf false
git -C "$SRC" add -A
git -C "$SRC" add -f assets/logo.png
git -C "$SRC" commit -qm "Start"
git -C "$SRC" tag -a v0.1.0 -m "Release v0.1.0"

# Private lists outside the repo, as in operation (~/.config or variables).
printf 'workflow-pool/privat-*.json\n' > "$TMP/private-list.txt"
printf '# Begriffe\n%s\n' "$PRIVATE_TERM" > "$TMP/private-terms.txt"
export HYGIENE_PRIVATE_LIST="$TMP/private-list.txt"
export HYGIENE_PRIVATE_TERMS="$TMP/private-terms.txt"

# --- "Public" repo: empty, local ------------------------------------------------------------------
git init -q --bare -b main "$PUBLIC_BARE"
git clone -q "$PUBLIC_BARE" "$PUB" 2>/dev/null
git -C "$PUB" config user.email "$NOREPLY"
git -C "$PUB" config user.name Tester
git -C "$PUB" config commit.gpgsign false
git -C "$PUB" config tag.gpgsign false
git -C "$PUB" config core.autocrlf input

handover() { bash "$SRC/scripts/release-handover.sh" "$@" 2>&1; }
pub_head() { git -C "$PUB" rev-parse -q --verify HEAD || echo none; }
pub_clean() { [ -z "$(git -C "$PUB" status --porcelain)" ]; }
in_head() { git -C "$PUB" cat-file -e "HEAD:$1" 2>/dev/null; }
no_push() { [ -z "$(git -C "$PUBLIC_BARE" for-each-ref)" ]; }

echo "1) Erste Uebergabe in ein leeres oeffentliches Repo"
OUT1="$(handover v0.1.0 "$PUB")"; RC1=$?
[ "$RC1" -eq 0 ] || printf '%s\n' "$OUT1" | sed 's/^/     | /'
check "Skript laeuft durch (exit 0)"                     '[ "$RC1" -eq 0 ]'
check "Ausschluss: Verzeichnis internal/ fehlt"           '! in_head internal/plan.md'
check "Ausschluss: Glob docs/notes-*.md fehlt"            '! in_head docs/notes-intern.md'
check "Ausgeschlossenes wird genannt"                     'grep -qF -- "- internal/plan.md" <<< "$OUT1"'
check "Uebertragen: README, Doku, Gate-Skripte"           'in_head README.md && in_head docs/old.md && in_head scripts/ci/public-hygiene.sh'
check "ignorierte, aber getrackte Datei kommt mit"        'in_head assets/logo.png'
check "CRLF-Datei byte-gleich trotz autocrlf=input"      '[ "$(git -C "$PUB" rev-parse HEAD:win.cmd)" = "$(git -C "$SRC" rev-parse v0.1.0:win.cmd)" ] && git -C "$PUB" cat-file -p HEAD:win.cmd | grep $'"'"'\r'"'"' >/dev/null'
check "Treue-Pruefung gemeldet"                           'grep -q "Treue: .* wie im Tag" <<< "$OUT1"'
check "Notizdatei release-notes/v0.1.0.md im Commit"      'in_head release-notes/v0.1.0.md && git -C "$PUB" show HEAD:release-notes/v0.1.0.md | grep "^# v0.1.0" >/dev/null'
check "Commit heisst \"Release v0.1.0\""                  '[ "$(git -C "$PUB" log -1 --format=%s)" = "Release v0.1.0" ]'
check "Autor und Committer in noreply-Form"               '[ "$(git -C "$PUB" log -1 --format=%ae/%ce)" = "$NOREPLY/$NOREPLY" ]'
check "Tag v0.1.0 ist annotiert"                          '[ "$(git -C "$PUB" cat-file -t v0.1.0)" = "tag" ]'
check "Tag zeigt auf den Release-Commit"                  '[ "$(git -C "$PUB" rev-parse "v0.1.0^{commit}")" = "$(pub_head)" ]'
check "Ausgabe nennt Gates und Status READY"              'grep -q "^## Gates" <<< "$OUT1" && grep -q "^handover_status: READY" <<< "$OUT1"'
check "Ausgabe endet mit dem Push-Befehl"                 'tail -1 <<< "$OUT1" | grep -F "push --atomic origin refs/heads/main refs/tags/v0.1.0" >/dev/null'
check "Ziel-Klon danach sauber"                           'pub_clean'
check "nichts gepusht"                                    'no_push'
FIRST="$(pub_head)"

echo "2) Zweites Release: Inhalt ersetzt, alte Notizen bleiben, Build-Cache bleibt"
mkdir -p "$PUB/build-cache"; echo "cache" > "$PUB/build-cache/blob"
git -C "$SRC" checkout -q -b feat/kapitel
echo "neu" > "$SRC/docs/new.md"
git -C "$SRC" add docs/new.md
git -C "$SRC" rm -q docs/old.md
git -C "$SRC" commit -qm "docs(kapitel): Neues Kapitel statt altem (11111111 Neues Doku-Kapitel)"
git -C "$SRC" checkout -q main
git -C "$SRC" merge --no-ff -q -m "Merge branch 'feat/kapitel': Neues Doku-Kapitel (11111111 Neues Doku-Kapitel)" feat/kapitel
git -C "$SRC" tag -a v0.2.0 -m "Release v0.2.0"
OUT2="$(handover v0.2.0 "$PUB")"; RC2=$?
[ "$RC2" -eq 0 ] || printf '%s\n' "$OUT2" | sed 's/^/     | /'
check "Skript laeuft durch (exit 0)"                     '[ "$RC2" -eq 0 ]'
check "entfernte Datei ist weg"                           '! in_head docs/old.md && [ ! -e "$PUB/docs/old.md" ]'
check "neue Datei ist da"                                 'in_head docs/new.md'
check "Notizen des vorigen Releases bleiben"              'in_head release-notes/v0.1.0.md'
check "Notizen v0.2.0: Merge-Betreff ohne TODO-Klammer"   'git -C "$PUB" show HEAD:release-notes/v0.2.0.md | grep -F "Neues Doku-Kapitel" >/dev/null && ! git -C "$PUB" show HEAD:release-notes/v0.2.0.md | grep 11111111 >/dev/null'
check "ein Commit je Release, auf dem vorigen"            '[ "$(git -C "$PUB" rev-parse HEAD~1)" = "$FIRST" ] && [ "$(git -C "$PUB" rev-list --count HEAD)" = "2" ]'
check "ignorierter Build-Cache unangetastet, nicht im Commit" '[ -f "$PUB/build-cache/blob" ] && ! in_head build-cache/blob'
check "Diff-Statistik in der Ausgabe"                     'grep -qE "files? changed" <<< "$OUT2"'
check "nichts gepusht"                                    'no_push'
SECOND="$(pub_head)"

echo "3) Dasselbe Tag noch einmal"
OUT3="$(handover v0.2.0 "$PUB")"; RC3=$?
check "bricht ab (exit 1)"                                '[ "$RC3" -eq 1 ]'
check "nennt das vorhandene Tag"                          'grep -q "gibt es im Ziel schon" <<< "$OUT3"'
check "Ziel unveraendert"                                 '[ "$(pub_head)" = "$SECOND" ] && pub_clean'

echo "4) Keine Aenderung im oeffentlichen Stand (nur Ausgeschlossenes geaendert)"
echo "mehr intern" >> "$SRC/internal/plan.md"
git -C "$SRC" commit -qam "chore: interne Notiz"
git -C "$SRC" tag -a v0.2.1 -m "Release v0.2.1"
OUT4="$(handover v0.2.1 "$PUB")"; RC4=$?
check "sauberer Abbruch (exit 3)"                         '[ "$RC4" -eq 3 ]'
check "meldet NOTHING_TO_DO"                              'grep -q "^handover_status: NOTHING_TO_DO" <<< "$OUT4"'
check "kein Commit, kein Tag"                             '[ "$(pub_head)" = "$SECOND" ] && ! git -C "$PUB" rev-parse -q --verify refs/tags/v0.2.1 >/dev/null'
check "Ziel sauber, Build-Cache noch da"                  'pub_clean && [ -f "$PUB/build-cache/blob" ]'

echo "5) Hygiene-Befund (Standard-Regel) nimmt alles zurueck"
echo "default=${DEFAULT_CREDENTIAL}" > "$SRC/docs/leak.md"
git -C "$SRC" add docs/leak.md
git -C "$SRC" commit -qm "docs: Beispielkonfiguration"
git -C "$SRC" tag -a v0.3.0 -m "Release v0.3.0"
OUT5="$(handover v0.3.0 "$PUB")"; RC5=$?
check "bricht ab (exit 1)"                                '[ "$RC5" -eq 1 ]'
check "Hygiene-Gate als FEHLER gemeldet"                  'grep -q "Public-Hygiene (--tracked): FEHLER" <<< "$OUT5"'
check "Befund nennt die Datei"                            'grep -q "docs/leak.md" <<< "$OUT5"'
check "Commit zurueckgenommen, kein Tag"                  '[ "$(pub_head)" = "$SECOND" ] && ! git -C "$PUB" rev-parse -q --verify refs/tags/v0.3.0 >/dev/null'
check "Datei nicht im Ziel, Ziel sauber"                  '[ ! -e "$PUB/docs/leak.md" ] && pub_clean'
check "Notizdatei des Laufs entfernt"                     '[ ! -e "$PUB/release-notes/v0.3.0.md" ]'
check "Inhalt wie vorher (docs/new.md da)"                '[ -f "$PUB/docs/new.md" ] && [ -f "$PUB/release-notes/v0.2.0.md" ]'
git -C "$SRC" rm -q docs/leak.md
git -C "$SRC" commit -qm "docs: Beispiel wieder raus"

echo "6) Privater Begriff (Begriffsliste ausserhalb des Repos) blockiert"
echo "Teil von ${PRIVATE_TERM,,}" > "$SRC/docs/herkunft.md"
git -C "$SRC" add docs/herkunft.md
git -C "$SRC" commit -qm "docs: Herkunft"
git -C "$SRC" tag -a v0.4.0 -m "Release v0.4.0"
OUT6="$(handover v0.4.0 "$PUB")"; RC6=$?
check "bricht ab (exit 1)"                                '[ "$RC6" -eq 1 ]'
check "Befund nennt die Listenzeile, nicht den Begriff"   'grep -q "privater Begriff" <<< "$OUT6" && ! grep -qi "$PRIVATE_TERM" <<< "$OUT6"'
check "Ziel unveraendert und sauber"                      '[ "$(pub_head)" = "$SECOND" ] && pub_clean'
git -C "$SRC" rm -q docs/herkunft.md
git -C "$SRC" commit -qm "docs: Herkunft raus"

echo "7) Doku-Gate schlaegt an"
echo "x" > "$SRC/DOCS_BROKEN"
git -C "$SRC" add DOCS_BROKEN
git -C "$SRC" commit -qm "docs: kaputt"
git -C "$SRC" tag -a v0.5.0 -m "Release v0.5.0"
OUT7="$(handover v0.5.0 "$PUB")"; RC7=$?
check "bricht ab (exit 1)"                                '[ "$RC7" -eq 1 ]'
check "Doku-Gates als FEHLER gemeldet"                    'grep -q "Doku-Gates (pre-push-checks --tree): FEHLER" <<< "$OUT7"'
check "Ziel unveraendert und sauber"                      '[ "$(pub_head)" = "$SECOND" ] && pub_clean'
git -C "$SRC" rm -q DOCS_BROKEN
git -C "$SRC" commit -qm "docs: repariert"
echo "neu in 0.6" > "$SRC/docs/sechs.md"
git -C "$SRC" add docs/sechs.md
git -C "$SRC" commit -qm "feat: Kapitel sechs"
git -C "$SRC" tag -a v0.6.0 -m "Release v0.6.0"

echo "8) Vorbedingungen"
echo "lokal" > "$PUB/untracked.txt"
OUT8="$(handover v0.6.0 "$PUB")"; RC8=$?
check "ungetrackte Datei im Ziel: Abbruch"                '[ "$RC8" -eq 1 ] && grep -q "ungetrackte" <<< "$OUT8"'
check "ungetrackte Datei bleibt liegen"                   '[ -f "$PUB/untracked.txt" ] && [ "$(pub_head)" = "$SECOND" ]'
rm -f "$PUB/untracked.txt"

OUT9="$(GIT_AUTHOR_EMAIL=someone@example.com handover v0.6.0 "$PUB")"; RC9=$?
check "Autor ohne noreply: Abbruch"                       '[ "$RC9" -eq 1 ] && grep -q "noreply" <<< "$OUT9" && [ "$(pub_head)" = "$SECOND" ]'

mkdir -p "$TMP/empty-xdg"
OUT10="$(env -u HYGIENE_PRIVATE_LIST -u HYGIENE_PRIVATE_TERMS XDG_CONFIG_HOME="$TMP/empty-xdg" \
         bash "$SRC/scripts/release-handover.sh" v0.6.0 "$PUB" 2>&1)"; RC10=$?
check "private Listen fehlen: Abbruch"                    '[ "$RC10" -eq 1 ] && grep -q "private Liste" <<< "$OUT10" && [ "$(pub_head)" = "$SECOND" ]'

OUT11="$(handover release-1 "$PUB")"; RC11=$?
check "Tag nicht in Release-Form: Aufruffehler (exit 2)"  '[ "$RC11" -eq 2 ]'

OUT12="$(handover v0.6.0 "$SRC")"; RC12=$?
check "Ziel = Arbeits-Repo: Abbruch"                      '[ "$RC12" -eq 1 ] && grep -q "Arbeits-Repo selbst" <<< "$OUT12"'

OUT13="$(handover v9.9.9 "$PUB")"; RC13=$?
check "unbekanntes Tag: Abbruch"                          '[ "$RC13" -eq 1 ]'

echo "9) Ohne private Listen nur mit ausdruecklichem Schalter"
OUT14="$(env -u HYGIENE_PRIVATE_LIST -u HYGIENE_PRIVATE_TERMS XDG_CONFIG_HOME="$TMP/empty-xdg" \
         bash "$SRC/scripts/release-handover.sh" --allow-missing-private-lists v0.6.0 "$PUB" 2>&1)"; RC14=$?
[ "$RC14" -eq 0 ] || printf '%s\n' "$OUT14" | sed 's/^/     | /'
check "laeuft mit Warnung durch"                          '[ "$RC14" -eq 0 ] && grep -q "WARNUNG: private Liste" <<< "$OUT14"'
check "Tag v0.6.0 gesetzt"                                '[ "$(git -C "$PUB" cat-file -t v0.6.0)" = "tag" ]'
# Predecessor is the most recently handed-over release (v0.2.0), not v0.5.0: v0.3.0 to v0.5.0
# never arrived, their changes belong in these notes.
check "Notizen seit dem zuletzt uebergebenen Release"     'grep -qF "Vorgänger:     v0.2.0" <<< "$OUT14" && git -C "$PUB" show HEAD:release-notes/v0.6.0.md | grep -F "Kapitel sechs" >/dev/null && git -C "$PUB" show HEAD:release-notes/v0.6.0.md | grep -F "Herkunft raus" >/dev/null'
check "Stand v0.6.0 im Ziel (Reparaturen enthalten)"      'in_head docs/sechs.md && ! in_head DOCS_BROKEN && ! in_head docs/leak.md'
check "drei Release-Commits, nichts gepusht"              '[ "$(git -C "$PUB" rev-list --count HEAD)" = "3" ] && no_push'

echo "10) Aelteres Tag nach einem neueren"
OUT15="$(handover v0.5.0 "$PUB")"; RC15=$?
check "bricht ab (exit 1), nennt das neuere Release"      '[ "$RC15" -eq 1 ] && grep -q "neuere Release v0.6.0" <<< "$OUT15"'
check "nichts gepusht"                                    'no_push'

echo "11) --discard nimmt eine nicht gepushte Uebergabe zurueck"
THIRD="$(pub_head)"
git -C "$PUB" update-ref refs/remotes/origin/main "$THIRD"
OUT16="$(handover --discard v0.6.0 "$PUB")"; RC16=$?
check "verweigert, wenn ein Remote-Ref den Commit kennt"  '[ "$RC16" -eq 1 ] && grep -q "bereits auf einem Remote" <<< "$OUT16" && [ "$(pub_head)" = "$THIRD" ]'
git -C "$PUB" update-ref -d refs/remotes/origin/main
OUT17="$(handover --discard v0.6.0 "$PUB")"; RC17=$?
check "verwirft (exit 0)"                                  '[ "$RC17" -eq 0 ]'
check "Branch zurueck auf dem vorigen Release, Tag weg"    '[ "$(pub_head)" = "$SECOND" ] && ! git -C "$PUB" rev-parse -q --verify refs/tags/v0.6.0 >/dev/null'
check "Dateien des verworfenen Commits weg, Ziel sauber"   '[ ! -e "$PUB/docs/sechs.md" ] && [ ! -e "$PUB/release-notes/v0.6.0.md" ] && pub_clean'
OUT18="$(handover --allow-missing-private-lists v0.6.0 "$PUB")"; RC18=$?
check "danach laeuft dieselbe Uebergabe erneut"            '[ "$RC18" -eq 0 ] && [ "$(git -C "$PUB" cat-file -t v0.6.0)" = "tag" ]'
OUT19="$(handover --discard v0.5.0 "$PUB")"; RC19=$?
check "unbekanntes Tag im Ziel: nichts zu verwerfen"      '[ "$RC19" -eq 1 ] && grep -q "nichts zu verwerfen" <<< "$OUT19"'

echo "12) --discard nach der ersten Uebergabe in ein leeres Repo"
git init -q --bare -b main "$TMP/empty.git"
git clone -q "$TMP/empty.git" "$TMP/pub2" 2>/dev/null
git -C "$TMP/pub2" config user.email "$NOREPLY"; git -C "$TMP/pub2" config user.name Tester
git -C "$TMP/pub2" config commit.gpgsign false; git -C "$TMP/pub2" config tag.gpgsign false
OUT22="$(handover v0.3.0 "$TMP/pub2")"; RC22=$?
check "Gate-Befund bei der ersten Uebergabe: Abbruch"      '[ "$RC22" -eq 1 ] && ! git -C "$TMP/pub2" rev-parse -q --verify HEAD >/dev/null'
check "danach keine Datei im Klon (auch keine ignorierte)" '[ -z "$(cd "$TMP/pub2" && find . -path ./.git -prune -o -type f -print)" ] && [ -z "$(git -C "$TMP/pub2" ls-files)" ]'
OUT20="$(handover v0.1.0 "$TMP/pub2")"; RC20=$?
OUT21="$(handover --discard v0.1.0 "$TMP/pub2")"; RC21=$?
check "Uebergabe und Verwerfen laufen durch"               '[ "$RC20" -eq 0 ] && [ "$RC21" -eq 0 ]'
check "Branch wieder ungeboren, kein Tag"                  '! git -C "$TMP/pub2" rev-parse -q --verify HEAD >/dev/null && [ -z "$(git -C "$TMP/pub2" tag)" ]'
check "keine Datei mehr im Klon (auch keine ignorierte)"    '[ -z "$(cd "$TMP/pub2" && find . -path ./.git -prune -o -type f -print)" ]'
check "nichts gepusht"                                     'no_push && [ -z "$(git -C "$TMP/empty.git" for-each-ref)" ]'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
