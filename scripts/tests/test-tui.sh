#!/usr/bin/env bash
# Tests for scripts/lib/tui.sh — the installer's terminal interface (panels, rail, menus, runner).
# Interactive parts are driven through a pseudo terminal (script) where available.
# Run: bash scripts/tests/test-tui.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/../lib"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
export LC_ALL="${TUI_TEST_LOCALE:-C.UTF-8}"
ESC="$(printf '\033')"
visible() { sed "s/${ESC}\[[0-9;?]*[A-Za-z]//g"; }
widths() { visible | while IFS= read -r l; do printf '%s\n' "${#l}"; done | sort -u; }
col() { visible | while IFS= read -r l; do l="${l%%"$1"*}"; echo "${#l}"; done; }   # column of $1, in characters
plain() { ( . "$LIB/tui.sh"; TUI_FORCE_PLAIN=1 tui_init; eval "$1" ) 2>&1; }
color() { ( . "$LIB/tui.sh"; TUI_FORCE_COLOR=256 tui_init; eval "$1" ) 2>&1; }

echo "1) box"
OUT="$(color 'tui_box "Step 4 of 8 · Modules" "Profile  (•) Standard" "Prüfung: äöü ✓" "$(printf "\033[1mfett\033[0m")"')"
check "all box lines have one visible width"     '[ "$(printf "%s\n" "$OUT" | widths | wc -l)" -eq 1 ]'
check "width is the panel width (78)"            '[ "$(printf "%s\n" "$OUT" | widths)" = 78 ]'
check "title in the top border"                  'printf "%s\n" "$OUT" | sed -n 1p | visible | grep "Step 4 of 8" >/dev/null'
LONG="$(printf "x%.0s" $(seq 1 120))"
OUT="$(plain "tui_box T $LONG")"
check "long lines are cut, not wrapped"          '[ "$(printf "%s\n" "$OUT" | widths)" = 78 ] && [ "$(printf "%s\n" "$OUT" | wc -l)" -eq 3 ]'

echo "2) rail"
OUT="$(plain 'tui_rail 2 Welcome Mode Checks Modules AI Ports Build Done')"
check "done, current, pending marked"            'printf "%s" "$OUT" | grep "✓ Welcome ─ ✓ Mode ─ ● Checks ─ ○ Modules" >/dev/null'
OUT="$(plain 'tui_rail 5 Willkommen Modus Prüfung Module KI Ports Bauen Fertig Zusatzschritt Nochmehr')"
check "too long: done steps collapse to ✓"       '[ "$(printf "%s\n" "$OUT" | visible | sed -n 1p | wc -m)" -le 80 ] && printf "%s" "$OUT" | grep "● Ports" >/dev/null'

echo "3) runner"
OUT="$(plain "tui_run \"Say hello\" \"$TMP/ok.log\" sh -c 'echo hello; echo world'"; echo "rc=$?")"
check "success returns 0 and marks ✓"            'printf "%s" "$OUT" | grep "rc=0" >/dev/null && printf "%s" "$OUT" | grep "✓ Say hello" >/dev/null'
check "output goes to the log, not the screen"   'grep -x world "$TMP/ok.log" >/dev/null && ! printf "%s" "$OUT" | grep -x world >/dev/null'
OUT="$(plain "tui_run \"Break\" \"$TMP/bad.log\" sh -c 'for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do echo line\$i; done; exit 7'"; echo "rc=$?")"
check "failure returns the exit code"            'printf "%s" "$OUT" | grep "rc=7" >/dev/null'
check "failure shows the last 15 lines only"     'printf "%s" "$OUT" | grep line20 >/dev/null && printf "%s" "$OUT" | grep line6 >/dev/null && ! printf "%s" "$OUT" | grep -w line5 >/dev/null'
check "failure names the log"                    'printf "%s" "$OUT" | grep "$TMP/bad.log" >/dev/null'

echo "4) menus without a terminal take the default"
OUT="$(plain 'tui_select CHOICE "Mode" 1 "Container|only Docker" "Host|Java, Node" "Staged|three"; echo "choice=$CHOICE"')"
check "select → default index"                   'printf "%s" "$OUT" | grep "choice=1" >/dev/null'
OUT="$(plain 'tui_checklist SEL "Modules" "on:chains:Chains" "off:assistant:Assistant" "on:bpmn:BPMN"; echo "sel=$SEL"')"
check "checklist → preset marks"                 'printf "%s" "$OUT" | grep "sel=chains bpmn" >/dev/null'
OUT="$(plain 'tui_confirm "Install now?" y && echo YES || echo NO')"
check "confirm → default"                        'printf "%s" "$OUT" | grep YES >/dev/null'
OUT="$(plain 'tui_input PORT "Port" 9090; echo "port=$PORT"')"
check "input → default"                          'printf "%s" "$OUT" | grep "port=9090" >/dev/null'
OUT="$(plain 'tui_secret T "Token"; echo "token=[$T]"')"
check "secret → empty, never asks"               'printf "%s" "$OUT" | grep -x "token=\[\]" >/dev/null'

echo "5) menus in a terminal (pseudo terminal via script)"
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1; then
  pty() {  # $1 = keys (printf format), $2 = shell code
    printf '%s\n' ". \"$LIB/tui.sh\"; tui_init; $2" > "$TMP/case.sh"
    printf "$1" | script -qec "bash $TMP/case.sh" /dev/null 2>&1 | visible | tr -d '\r'
  }
  OUT="$(pty '\033[B\n' 'tui_select C "Mode" 0 "A|a" "B|b" "C|c"; echo "choice=$C"')"
  check "arrow down + Enter → second option"     'printf "%s" "$OUT" | grep "choice=1" >/dev/null'
  OUT="$(pty '3' 'tui_select C "Mode" 0 "A|a" "B|b" "C|c"; echo "choice=$C"')"
  check "digit selects directly"                 'printf "%s" "$OUT" | grep "choice=2" >/dev/null'
  OUT="$(pty ' \033[B \n' 'tui_checklist S "Mods" "off:x:X" "on:y:Y" "off:z:Z"; echo "sel=$S"')"
  check "space toggles, arrows move"             'printf "%s" "$OUT" | grep "sel=x$" >/dev/null'
  OUT="$(pty 'b' 'tui_select C "Mode" 0 "A|a" "B|b"; echo "rc=$?"')"
  check "b means back (rc 2)"                    'printf "%s" "$OUT" | grep "rc=2" >/dev/null'
  # The input comes only after read -s has turned off the echo — typed earlier, the terminal echoes
  printf '%s\n' ". \"$LIB/tui.sh\"; tui_init; tui_secret T Token; echo \"len=\${#T}\"" > "$TMP/secret.sh"
  OUT="$( (sleep 1; printf 'sk-geheim\n') | script -qec "bash $TMP/secret.sh" /dev/null 2>&1 | visible | tr -d '\r')"
  check "secret is read but not echoed"          'printf "%s" "$OUT" | grep "len=9" >/dev/null && ! printf "%s" "$OUT" | grep "sk-geheim" >/dev/null'
  OUT="$(pty 'n\n' 'tui_confirm "Go?" y && echo YES || echo NO')"
  check "confirm reads n"                        'printf "%s" "$OUT" | grep NO >/dev/null'
  OUT="$(pty '\n' 'tui_checklist S "M" "on:a:Härtung:xhint" "off:b:Module:xhint"; echo "sel=$S"')"
  check "checklist: hint column equal with ä"     '[ "$(printf "%s\n" "$OUT" | grep -E "Härtung|Module" | col xhint | sort -u | wc -l | tr -d " ")" = 1 ]'
  OUT="$(pty 'j\n' ". \"$LIB/i18n.sh\"; i18n_load de; tui_confirm Weiter? n && echo JA || echo NEIN")"
  check "German confirm shows [j/N] and takes j"  'printf "%s" "$OUT" | grep "\[j/N\]" >/dev/null && printf "%s" "$OUT" | grep JA >/dev/null'
  # Title longer than the line, log still empty: the truncation must not become negative (Ubuntu probe)
  LONG="node (Node 24.21.0 → /home/someone/.local/share/mysuricates/node/and/more/path)"
  OUT="$(pty '' "tui_run \"$LONG\" \"$TMP/long.log\" sh -c 'sleep 0.5; echo x'; echo \"rc=\$?\"")"
  check "long title with empty log still runs"   'printf "%s" "$OUT" | grep "rc=0" >/dev/null && ! printf "%s" "$OUT" | grep "substring" >/dev/null'
else
  echo "  skip (no usable script command)"
fi

echo "5b) umlauts do not shift columns (printf pads bytes, not characters)"
OUT="$(color 'tui_done "Prüfung" "AAA"; tui_done "Modus" "AAA"')"
check "done lines: value column equal with ü"     '[ "$(printf "%s\n" "$OUT" | col AAA | sort -u | wc -l | tr -d " ")" = 1 ]'

echo "6) portability"
check "no bash-4-only syntax (code, not comments)" '! grep -v "^[[:space:]]*#" "$LIB/tui.sh" | grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}|read [^|]*-t [0-9]*\\.[0-9]" >/dev/null'
OUT="$( ( . "$LIB/tui.sh"; LC_ALL=C TUI_FORCE_PLAIN=1 tui_init; tui_box T "a" ) 2>&1 )"
check "without UTF-8 the frames are ASCII"       'printf "%s" "$OUT" | sed -n 1p | grep "^+-" >/dev/null'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
