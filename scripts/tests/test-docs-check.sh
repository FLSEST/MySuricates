#!/usr/bin/env bash
# Tests for scripts/public-readiness/docs-check.sh (TODO 3e57b1be).
#
# The script reports which public documents exist and sets the
# gate to YELLOW as soon as one of the mandatory documents is missing. Until 2026-09-20 it checked
# five file names that never existed in this project (docs/architecture.md,
# docs/agentic-development.md, docs/public-readiness.md, docs/security.md,
# docs/use-cases.md) - the gate therefore stood permanently at YELLOW and was worthless as a
# signal. Case 4 pins exactly this regression.
#
# The tests work on a miniature repository so that they do not move along
# when the real repo grows.
#
# Run: bash scripts/tests/test-docs-check.sh   (exit 0 = all checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/docs-check.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Miniature repo with all mandatory documents.
FAKE="$TMP/fake"
mkdir -p "$FAKE/docs"
for f in README.md README.de.md LICENSE NOTICE SECURITY.md CONTRIBUTING.md; do
  echo "# $f" > "$FAKE/$f"
done
# A README without a heading for trying it out has been a finding since TODO 1b84ecab.
for f in README.md README.de.md; do printf '# %s\n\nKurz.\n\n## Quickstart\n' "$f" > "$FAKE/$f"; done
for f in GETTING-STARTED.md architektur.md baukasten.md workflow-pool-kuratierung.md; do
  echo "# $f" > "$FAKE/docs/$f"
done

run_gate() { PUBLIC_READINESS_ROOT="$FAKE" bash "$SCRIPT" 2>/dev/null; }

echo "Fall 1: vollstaendiges Repo -> GREEN"
OUT="$(run_gate)"
check "gate_status GREEN"            'grep -q "^gate_status: GREEN" <<< "$OUT"'
check "kein todo_item"               '! grep -q "^todo_item:" <<< "$OUT"'
check "alle zehn als present"        '[ "$(grep -c "^present: " <<< "$OUT")" -eq 10 ]'
check "kein missing"                 '! grep -q "^missing: " <<< "$OUT"'

echo "Fall 2: LICENSE fehlt -> YELLOW mit todo_item"
rm "$FAKE/LICENSE"
OUT="$(run_gate)"
check "gate_status YELLOW"           'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "todo_item ausgegeben"         'grep -q "^todo_item: " <<< "$OUT"'
check "LICENSE als missing gemeldet" 'grep -q "^missing: LICENSE$" <<< "$OUT"'

echo "Fall 3: nur eine Nicht-Pflichtdatei fehlt -> weiterhin GREEN"
echo "# LICENSE" > "$FAKE/LICENSE"
rm "$FAKE/NOTICE"
OUT="$(run_gate)"
check "gate_status GREEN"            'grep -q "^gate_status: GREEN" <<< "$OUT"'
check "NOTICE als missing gemeldet"  'grep -q "^missing: NOTICE$" <<< "$OUT"'

echo "Fall 4: Regression — keine Phantom-Dateinamen mehr in der Erwartungsliste"
echo "# NOTICE" > "$FAKE/NOTICE"
OUT="$(run_gate)"
for phantom in docs/architecture.md docs/agentic-development.md docs/public-readiness.md \
               docs/security.md docs/use-cases.md; do
  check "nicht erwartet: $phantom"   '! grep -q "^\(present\|missing\): '"$phantom"'$" <<< "$OUT"'
done
check "vollstaendiges Repo bleibt GREEN" 'grep -q "^gate_status: GREEN" <<< "$OUT"'

echo "Fall 5: Pflichtdokument in docs/ fehlt -> YELLOW"
rm "$FAKE/docs/baukasten.md"
OUT="$(run_gate)"
check "gate_status YELLOW"           'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "docs/baukasten.md reported as missing" 'grep -q "^missing: docs/baukasten.md$" <<< "$OUT"'

echo "Fall 6: Schnellstart hinter zu viel Text -> YELLOW ohne todo_item (TODO 1b84ecab)"
echo "# baukasten.md" > "$FAKE/docs/baukasten.md"
{ echo "# README"; for i in $(seq 1 60); do echo "eins zwei drei vier fuenf sechs sieben acht neun zehn"; done
  echo "<!-- ein langer Kommentar"; for i in $(seq 1 60); do echo "wort wort wort wort wort wort wort wort wort wort"; done; echo "-->"
  echo "## Quickstart"; } > "$FAKE/README.md"
OUT="$(run_gate)"
check "gate_status YELLOW"                  'grep -q "^gate_status: YELLOW" <<< "$OUT"'
check "schnellstart-spaet gemeldet"         'grep -q "BEFUND: schnellstart-spaet README.md:" <<< "$OUT"'
check "Kommentar zaehlt nicht mit (602)"    'grep -q "602 Woerter davor" <<< "$OUT"'
check "kein todo_item"                      '! grep -q "^todo_item:" <<< "$OUT"'
OUT="$(PUBLIC_READINESS_QUICKSTART_MAX_WORDS=700 PUBLIC_READINESS_ROOT="$FAKE" bash "$SCRIPT" 2>/dev/null)"
check "Grenze per Umgebung einstellbar"     'grep -q "^gate_status: GREEN" <<< "$OUT"'

echo "Fall 7: README ohne Ueberschrift zum Ausprobieren -> Befund"
printf '# README\n\n## Ueber das Projekt\n' > "$FAKE/README.md"
OUT="$(run_gate)"
check "readme-ohne-schnellstart gemeldet"   'grep -q "BEFUND: readme-ohne-schnellstart README.md" <<< "$OUT"'
printf '# README\n\n### Ausprobieren\n' > "$FAKE/README.md"
OUT="$(run_gate)"
check "deutsche Ueberschrift wird erkannt"  'grep -q "^gate_status: GREEN" <<< "$OUT"'

echo "Fall 8: Maintainer-Marker in CLAUDE.md -> Befund, API-Beispiel in AGENTS.md nicht"
printf 'sessionId: $SHELL_SESSION_ID\n`claude --resume <uuid>`\n' > "$FAKE/CLAUDE.md"
printf '{"sourceAgent": "Codex CLI"}\n' > "$FAKE/AGENTS.md"
OUT="$(run_gate)"
check "SHELL_SESSION_ID gemeldet"           'grep -q "BEFUND: maintainer-anweisung CLAUDE.md .*SHELL_SESSION_ID" <<< "$OUT"'
check "claude --resume gemeldet"            'grep -q "BEFUND: maintainer-anweisung CLAUDE.md .*claude --resume" <<< "$OUT"'
check "AGENTS.md ohne Befund"               '! grep -q "BEFUND: maintainer-anweisung AGENTS.md" <<< "$OUT"'
check "gate_status YELLOW"                  'grep -q "^gate_status: YELLOW" <<< "$OUT"'

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
