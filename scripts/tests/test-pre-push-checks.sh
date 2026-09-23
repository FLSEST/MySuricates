#!/usr/bin/env bash
# Tests fuer scripts/ci/pre-push-checks.sh, scripts/git-hooks/pre-push und
# scripts/git-hooks/commit-msg (TODO 7030f362).
# Run: bash scripts/tests/test-pre-push-checks.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
# Die Ausweichorte der Privatlisten liegen ausserhalb des Repos — ohne Isolation faende
# das Gate die echte Liste des Rechners (siehe test-public-hygiene.sh).
export XDG_CONFIG_HOME="$TMP/xdg"
unset HYGIENE_PRIVATE_LIST HYGIENE_PRIVATE_TERMS MYSURICATES_PREPUSH_SKIP GITHUB_ACTIONS

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Verbotenes Literal zur Laufzeit zusammensetzen, damit diese Datei selbst sauber bleibt.
FORBIDDEN="$(printf '%s%s' '/home/' 'flo')"
NOREPLY="git -c user.name=t -c user.email=1+t@users.noreply.github.com"
PRIVATE="git -c user.name=t -c user.email=t@example.invalid"
# In den Testrepos gibt es keine Bestands-Gates und keinen Build — beides wird
# ausdruecklich uebersprungen und muss als uebersprungen gemeldet werden (Fall 12).
SKIP="MYSURICATES_PREPUSH_SKIP=tree,build"

# Frisches Repo mit Gate-Dateien, Hooks via core.hooksPath, dazu ein Bare-Remote "origin".
make_repo() {  # $1 = pfad
  git init -q -b main "$1"
  mkdir -p "$1/scripts"
  cp -r "$REPO/scripts/ci" "$REPO/scripts/lib" "$REPO/scripts/git-hooks" "$1/scripts/"
  rm -f "$1/scripts/ci/hygiene-private.txt" "$1/scripts/ci/hygiene-private-terms.txt"
  git init -q --bare "$1.remote"
  ( cd "$1" && $NOREPLY add . && $NOREPLY commit -q -m init \
      && git config core.hooksPath scripts/git-hooks && git remote add origin "$1.remote" \
      && env $SKIP git push -q origin main 2>/dev/null )
}
commit_file() {  # $1 = repo, $2 = datei, $3 = inhalt, $4 = nachricht, $5.. = git-Praefix
  local repo="$1" f="$2" content="$3" msg="$4"; shift 4
  ( cd "$repo" && printf '%s\n' "$content" > "$f" && "$@" add "$f" && "$@" commit -q --no-verify -m "$msg" )
}

echo "1) Sauberer Push geht durch (Hook, Refs auf stdin)"
make_repo "$TMP/clean"
commit_file "$TMP/clean" ok.txt "hallo" "feat: harmlos" $NOREPLY
OUT=$(cd "$TMP/clean" && env $SKIP git push origin main 2>&1); RC=$?
check "push akzeptiert (exit 0)"                 '[ "$RC" -eq 0 ]'
check "Gate meldet OK"                           'grep -q "Pre-Push-Gate OK" <<<"$OUT"'
check "genau 1 Commit geprueft"                  'grep -q "1 Commit(s) geprueft" <<<"$OUT"'
check "keine fehlende Funktion"                  '! grep -qi "not found\|nicht gefunden\|unbound" <<<"$OUT"'

echo "2) Verbotenes Literal in der Commit-Nachricht blockiert den Push"
commit_file "$TMP/clean" a.txt "x" "fix: Pfad ${FORBIDDEN}/x korrigiert" $NOREPLY
SHORT=$(cd "$TMP/clean" && git rev-parse --short HEAD)
OUT=$(cd "$TMP/clean" && env $SKIP git push origin main 2>&1); RC=$?
check "push abgelehnt"                           '[ "$RC" -ne 0 ]'
check "Gate meldet FAILED"                       'grep -q "Pre-Push-Gate FAILED" <<<"$OUT"'
check "nennt den Commit"                         'grep -q "commit ${SHORT}.*absolute developer path" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q --hard HEAD~1 )

echo "3) commit-msg-Hook blockiert dieselbe Nachricht schon beim Commit"
OUT=$(cd "$TMP/clean" && printf 'y\n' > b.txt && $NOREPLY add b.txt && $NOREPLY commit -q -m "chore: siehe ${FORBIDDEN}/y" 2>&1); RC=$?
check "commit abgelehnt"                         '[ "$RC" -ne 0 ]'
check "meldet das Literal"                       'grep -q "dieser Commit: Nachricht enthaelt" <<<"$OUT"'
check "kein Commit entstanden"                   '[ "$(cd "$TMP/clean" && git log --oneline | wc -l)" -eq 2 ]'
( cd "$TMP/clean" && git reset -q && rm -f b.txt )

echo "4) Private Autor-Adresse blockiert den Push"
commit_file "$TMP/clean" c.txt "x" "feat: sauber" $PRIVATE
OUT=$(cd "$TMP/clean" && env $SKIP git push origin main 2>&1); RC=$?
check "push abgelehnt"                           '[ "$RC" -ne 0 ]'
check "nennt Autor und noreply"                  'grep -q "Autor-Adresse ist keine GitHub-noreply-Adresse" <<<"$OUT"'
check "nennt auch den Committer"                 'grep -q "Committer-Adresse ist keine GitHub-noreply-Adresse" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q --hard HEAD~1 )

echo "5) commit-msg-Hook blockiert bei privater Autor-Adresse"
OUT=$(cd "$TMP/clean" && printf 'z\n' > d.txt && $PRIVATE add d.txt && $PRIVATE commit -q -m "feat: sauber" 2>&1); RC=$?
check "commit abgelehnt"                         '[ "$RC" -ne 0 ]'
check "meldet die Adresse als Befund"            'grep -q "dieser Commit: Autor-Adresse" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q && rm -f d.txt )

echo "6) Trailer-Adresse ist erlaubt, Adresse im Text nicht"
commit_file "$TMP/clean" e.txt "x" "$(printf 'feat: mit Trailer\n\nCo-Authored-By: Claude <noreply@anthropic.com>')" $NOREPLY
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "Trailer akzeptiert (exit 0)"              '[ "$RC" -eq 0 ]'
commit_file "$TMP/clean" f.txt "x" "$(printf 'fix: Rueckfragen an jemand@example.invalid')" $NOREPLY
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "Adresse im Text abgelehnt (exit 1)"       '[ "$RC" -eq 1 ]'
check "meldet Mailadresse ausserhalb Trailer"    'grep -q "Mailadresse ausserhalb eines Trailers" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q --hard HEAD~2 )

echo "7) Privater Begriff in der Nachricht: Treffer, Begriff bleibt ungenannt"
printf '# Kommentar\nSchnecke\n' > "$TMP/terms.txt"
commit_file "$TMP/clean" g.txt "x" "docs: Notiz zur schnecke" $NOREPLY
OUT=$(cd "$TMP/clean" && HYGIENE_PRIVATE_TERMS="$TMP/terms.txt" bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "Begriff erkannt (exit 1, ohne Gross/Klein)" '[ "$RC" -eq 1 ]'
check "nennt die Listenzeile 2"                  'grep -q "privaten Begriff (Zeile 2 der privaten Begriffsliste)" <<<"$OUT"'
check "Begriff steht nicht in der Ausgabe"       '! grep -qi "schnecke" <<<"$OUT"'
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "ohne Liste: hoerbar uebersprungen"        'grep -q "private Begriffe in Commit-Nachrichten werden nicht geprueft" <<<"$OUT"'
check "ohne Liste: kein Befund (exit 0)"         '[ "$RC" -eq 0 ]'
( cd "$TMP/clean" && git reset -q --hard HEAD~1 )

echo "8) Privater Begriff in einer hinzugefuegten Zeile faellt im Push-Bereich auf (Hygiene --range)"
commit_file "$TMP/clean" h.txt "Die Schnecke kriecht" "docs: harmlos" $NOREPLY
OUT=$(cd "$TMP/clean" && HYGIENE_PRIVATE_TERMS="$TMP/terms.txt" bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "Hygiene-Gate im Bereich meldet Verstoss"  'grep -q "Hygiene-Gate auf HEAD~1..HEAD meldet Verstoesse" <<<"$OUT"'
check "nennt Datei und Listenzeile"              'grep -q "h.txt:1 .*privaten Begriff (Zeile 2" <<<"$OUT"'
check "exit 1"                                   '[ "$RC" -eq 1 ]'
( cd "$TMP/clean" && git reset -q --hard HEAD~1 )

echo "9) Auch der pre-commit-Hook (--staged) sieht den privaten Begriff in neuen Zeilen"
OUT=$(cd "$TMP/clean" && printf 'schnecke\n' > i.txt && git add i.txt && HYGIENE_PRIVATE_TERMS="$TMP/terms.txt" bash scripts/ci/public-hygiene.sh --staged 2>&1); RC=$?
check "--staged meldet Verstoss (exit 1)"        '[ "$RC" -eq 1 ]'
check "nennt i.txt und Listenzeile 2"            'grep -q "i.txt:1 .*privaten Begriff (Zeile 2" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q && rm -f i.txt )

echo "10) Loeschung und unveraenderter Stand: nichts zu pruefen"
OUT=$(cd "$TMP/clean" && printf 'refs/heads/x 0000000000000000000000000000000000000000 refs/heads/x %s\n' "$(git rev-parse HEAD)" | bash scripts/ci/pre-push-checks.sh --hook 2>&1); RC=$?
check "Loeschung: exit 0"                        '[ "$RC" -eq 0 ]'
check "Loeschung gemeldet"                       'grep -q "Loeschung — nichts zu pruefen" <<<"$OUT"'
OUT=$(cd "$TMP/clean" && env $SKIP git push origin main 2>&1); RC=$?
# git ruft den Hook auch ohne neue Refs auf — mit leerem stdin. Das muss ein hoerbares Nichts sein.
check "nichts Neues: Hook meldet nichts zu pruefen (exit 0)" '[ "$RC" -eq 0 ] && grep -q "Pre-Push-Gate OK — nichts zu pruefen" <<<"$OUT"'

echo "11) Neuer Branch ohne Remote-Stand: alle Commits bis zum letzten Remote-Ref"
( cd "$TMP/clean" && git checkout -q -b feature )
commit_file "$TMP/clean" j.txt "x" "feat: erster" $NOREPLY
commit_file "$TMP/clean" k.txt "x" "feat: ${FORBIDDEN}/zweiter" $NOREPLY
OUT=$(cd "$TMP/clean" && env $SKIP git push origin feature 2>&1); RC=$?
check "push abgelehnt"                           '[ "$RC" -ne 0 ]'
check "2 Commits geprueft"                       'grep -q "2 Commit(s) geprueft" <<<"$OUT"'
( cd "$TMP/clean" && git reset -q --hard HEAD~1 )
OUT=$(cd "$TMP/clean" && env $SKIP git push origin feature 2>&1); RC=$?
check "sauberer neuer Branch geht durch"         '[ "$RC" -eq 0 ] && grep -q "1 Commit(s) geprueft" <<<"$OUT"'
( cd "$TMP/clean" && git checkout -q main )

echo "12) Auslassungen werden gemeldet, nie still"
commit_file "$TMP/clean" l.txt "x" "feat: skip" $NOREPLY
OUT=$(cd "$TMP/clean" && env $SKIP git push origin main 2>&1); RC=$?
check "Bestands-Gates als uebersprungen gemeldet" 'grep -q "Bestands-Gates uebersprungen (MYSURICATES_PREPUSH_SKIP" <<<"$OUT"'
check "Bauprüfung als uebersprungen gemeldet"     'grep -q "Bauprüfung uebersprungen (MYSURICATES_PREPUSH_SKIP" <<<"$OUT"'

echo "13) Fehlendes Bestands-Gate ist ein harter Fehler, kein stilles OK"
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --tree 2>&1); RC=$?
check "exit 2"                                   '[ "$RC" -eq 2 ]'
check "nennt das fehlende Skript"                'grep -q "Bestands-Gate .* fehlt: scripts/public-readiness/doc-facts-check.sh" <<<"$OUT"'
check "meldet kein OK"                           '! grep -q "Pre-Push-Gate OK" <<<"$OUT"'

echo "14) Bauprüfung nach Scope: Docs beruehren nichts, Frontend ohne node_modules ist ein harter Fehler"
commit_file "$TMP/clean" notes.md "text" "docs: nur Doku" $NOREPLY
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree 2>&1); RC=$?
check "Doku-Push: nichts zu bauen (exit 0)"      '[ "$RC" -eq 0 ] && grep -q "weder Frontend noch Backend beruehrt" <<<"$OUT"'
( cd "$TMP/clean" && mkdir -p ai-automation-frontend/src )
commit_file "$TMP/clean" ai-automation-frontend/src/x.ts "export {}" "feat(ui): x" $NOREPLY
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree 2>&1); RC=$?
check "Frontend ohne node_modules: exit 2"       '[ "$RC" -eq 2 ]'
check "verlangt npm ci"                          'grep -q "node_modules fehlt — erst npm ci" <<<"$OUT"'

echo "15) gitleaks: fehlt -> hoerbar uebersprungen; vorhanden -> Fund blockiert"
OUT=$(cd "$TMP/clean" && PATH="/usr/bin:/bin" /bin/bash -c 'command -v gitleaks >/dev/null && exit 99; bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build' 2>&1); RC=$?
if [ "$RC" -eq 99 ]; then
  echo "  skip gitleaks ist auf diesem Rechner installiert — Abwesenheitsfall nicht pruefbar"
else
  check "ohne gitleaks: uebersprungen gemeldet"  'grep -q "gitleaks ist nicht installiert — Secret-Scan des Bereichs uebersprungen" <<<"$OUT"'
fi
mkdir -p "$TMP/fakebin"
printf '#!/usr/bin/env bash\necho "Finding: fake-secret"; exit 1\n' > "$TMP/fakebin/gitleaks"; chmod +x "$TMP/fakebin/gitleaks"
OUT=$(cd "$TMP/clean" && PATH="$TMP/fakebin:$PATH" bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "gitleaks-Fund blockiert (exit 1)"         '[ "$RC" -eq 1 ] && grep -q "gitleaks meldet Funde" <<<"$OUT"'
printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/fakebin/gitleaks"
OUT=$(cd "$TMP/clean" && PATH="$TMP/fakebin:$PATH" bash scripts/ci/pre-push-checks.sh --range HEAD~1..HEAD --skip-tree --skip-build 2>&1); RC=$?
check "gitleaks ohne Fund: keine Funde gemeldet" '[ "$RC" -eq 0 ] && grep -q "gitleaks auf HEAD~1..HEAD: keine Funde" <<<"$OUT"'

echo "16) Hooks laufen auch im Worktree (git exportiert dort GIT_DIR)"
( cd "$TMP/clean" && git worktree add -q "$TMP/wt" -b wt-test )
commit_file "$TMP/wt" m.txt "x" "feat: ${FORBIDDEN}/wt" $NOREPLY
OUT=$(cd "$TMP/wt" && env $SKIP git push origin wt-test 2>&1); RC=$?
check "push aus Worktree abgelehnt"              '[ "$RC" -ne 0 ] && grep -q "Pre-Push-Gate FAILED" <<<"$OUT"'
check "keine fehlende Funktion im Worktree"      '! grep -qi "not found\|nicht gefunden\|unbound" <<<"$OUT"'

echo "17) Falscher Aufruf ist exit 2"
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh 2>&1); RC=$?
check "ohne Modus: exit 2"                       '[ "$RC" -eq 2 ]'
OUT=$(cd "$TMP/clean" && bash scripts/ci/pre-push-checks.sh --range kaputt 2>&1); RC=$?
check "--range ohne ..: exit 2"                  '[ "$RC" -eq 2 ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
