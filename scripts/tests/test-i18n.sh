#!/usr/bin/env bash
# Tests for scripts/lib/i18n.sh and the language files scripts/lib/i18n/<lang>.sh.
# A new language is one more file; English is the complete base and the fallback per key (145d1f30).
# Run: bash scripts/tests/test-i18n.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/../lib"; DIR="$LIB/i18n"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
keys() { grep -oE '^M_[a-z0-9_]+=' "$1" | sed 's/=$//' | sort -u; }
# Nur Kommentare und M_name="Wert"; ein Wert darf ueber mehrere Zeilen gehen (Hilfe-Text), enthaelt
# aber nie ", $ oder ` — die Datei wird per source geladen, ein $(…) darin liefe mit.
wellformed() {
  awk '
    !/^#/ && /[$`]/ { bad = 1 }
    inq { if ($0 ~ /^[^"]*"$/) inq = 0; else if ($0 ~ /"/) bad = 1; next }
    /^(#.*)?$/ { next }
    /^M_[a-z0-9_]+="[^"]*"$/ { next }
    /^M_[a-z0-9_]+="[^"]*$/ { inq = 1; next }
    { bad = 1 }
    END { exit (bad || inq) }' "$1"
}
run() { ( I18N_DIR="${I18N_DIR:-$DIR}"; export I18N_DIR; . "$LIB/i18n.sh"; eval "$1" ); }

echo "1) language files"
check "English base exists"                    '[ -f "$DIR/en.sh" ]'
check "at least two languages"                 '[ "$(run i18n_languages | wc -l)" -ge 2 ]'
for f in "$DIR"/*.sh; do
  l="$(basename "$f" .sh)"
  check "$l: only M_ assignments and comments"  'wellformed "$f"'
  check "$l: parses (bash -n)"                  'bash -n "$f"'
  check "$l: no key outside the English base"   '[ -z "$(comm -13 <(keys "$DIR/en.sh") <(keys "$f"))" ]'
done

T="$(mktemp -d)"
printf 'M_a="x $(id)"\n' > "$T/sub.sh"; printf 'M_a="one\ntwo"\n' > "$T/multi.sh"; printf 'M_a="open\n' > "$T/open.sh"
printf 'echo hi\n' > "$T/cmd.sh"
check "format check rejects \$(…), code and open quotes" '! wellformed "$T/sub.sh" && ! wellformed "$T/cmd.sh" && ! wellformed "$T/open.sh"'
check "format check accepts multi-line values"  'wellformed "$T/multi.sh"'
rm -rf "$T"

echo "2) lookup"
check "English by default"                     '[ "$(run "i18n_load; msg step_mode")" = "Mode" ]'
check "German with --lang de"                  '[ "$(run "i18n_load de; msg step_mode")" = "Modus" ]'
check "unknown language falls back to English" '[ "$(run "i18n_load xx; msg step_mode")" = "Mode" ] && [ "$(run "i18n_load xx; echo \$I18N_LANG")" = en ]'
check "odd language names are ignored"         '[ "$(run "i18n_load ../en; echo \$I18N_LANG")" = en ]'
# Eine Test-Sprache, die nur einen Schluessel kennt: alles andere muss englisch bleiben
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cp "$DIR/en.sh" "$TMP/en.sh"; printf 'M_step_mode="Moda"\n' > "$TMP/xy.sh"
check "missing key in a language -> English"   '[ "$(I18N_DIR="$TMP" run "i18n_load xy; msg step_mode; msg step_ai")" = "ModaAI" ]'
check "unknown key prints the key"             '[ "$(run "i18n_load de; msg no_such_key")" = "no_such_key" ]'
check "printf arguments are filled in"         '[ "$(run "i18n_load; msg step_of 2 8")" = "Step 2 of 8" ]'

echo "3) every key the scripts use exists in English"
USED="$(grep -ohE "msg [a-z0-9_]+" "$HERE/../setup.sh" "$LIB"/*.sh 2>/dev/null | awk "{print \$2}" | sort -u)"
MISSING="$(comm -23 <(printf "%s\n" "$USED" | grep .) <(keys "$DIR/en.sh" | sed "s/^M_//"))"
check "no used key is missing (${MISSING:-none})" '[ -z "$MISSING" ]'

echo "4) bash 3.2 hygiene"
check "no bash-4-only syntax"                  '! grep -nE "declare -g?A|mapfile|readarray" "$LIB/i18n.sh" "$DIR"/*.sh'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
