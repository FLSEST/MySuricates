#!/usr/bin/env bash
# Tests for scripts/lib/module-preset.sh — preset validation and the local config writer.
# Run: bash scripts/tests/test-module-preset.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/module-preset.sh
source "$HERE/../lib/module-preset.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

echo "1) validation"
check "core, research and full are valid"  'module_preset_valid core && module_preset_valid research && module_preset_valid full'
check "unknown and empty presets are rejected" '! module_preset_valid everything && ! module_preset_valid ""'

echo "2) writing the local config"
CFG="$TMP/backend/config/application.yml"
module_preset_write "$CFG" research; RC=$?
check "writes a new file (rc=$RC)"                    '[ "$RC" -eq 0 ] && [ -f "$CFG" ]'
check "creates the config directory"                  '[ -d "$TMP/backend/config" ]'
check "contains the preset under automation.modules"  'grep -q "^automation:$" "$CFG" && grep -q "^  modules:$" "$CFG" && grep -q "^    preset: research$" "$CFG"'
check "explains how to override single modules"       'grep -q "automation.modules.webhooks: true" "$CFG"'

echo "3) never overwrites"
echo "automation.modules.preset: full  # user edited" > "$CFG"
module_preset_write "$CFG" core; RC=$?
check "existing file returns 3"                       '[ "$RC" -eq 3 ]'
check "existing content is untouched"                 'grep -q "user edited" "$CFG" && ! grep -q "preset: core" "$CFG"'

echo "4) bad preset"
module_preset_write "$TMP/other.yml" nope; RC=$?
check "bad preset returns 2 and writes nothing"       '[ "$RC" -eq 2 ] && [ ! -e "$TMP/other.yml" ]'

echo "5) prompt mapping"
check "1 → core"            '[ "$(module_preset_prompt core <<<"1" 2>/dev/null)" = core ]'
check "3 → full"            '[ "$(module_preset_prompt core <<<"3" 2>/dev/null)" = full ]'
check "Enter → default"     '[ "$(module_preset_prompt research <<<"" 2>/dev/null)" = research ]'
check "garbage → default"   '[ "$(module_preset_prompt core <<<"x" 2>/dev/null)" = core ]'

echo "6) setup.sh argument handling"
SETUP="$HERE/../setup.sh"
check "--help documents --preset"                     'bash "$SETUP" --help | grep -q -- "--preset core"'
check "--help documents --lang"                       'bash "$SETUP" --help | grep -q -- "--lang de"'
check "unknown preset exits 2 before any check"       'bash "$SETUP" --preset everything >/dev/null 2>&1; [ $? -eq 2 ]'

echo "7) generated module catalog"
GENERATED="$HERE/../lib/module-catalog.generated.sh"
check "the generated file exists"                     '[ -f "$GENERATED" ]'
check "the generated file parses (bash -n)"           'bash -n "$GENERATED"'
check "says it is generated, not hand-written"        'grep -q "generiert von ModuleCatalogTextsTest" "$GENERATED"'
check "keys = feature keys + hardening keys"          '[ "${#MODULE_CATALOG_KEYS[@]}" -eq $(( ${#MODULE_CATALOG_FEATURE_KEYS[@]} + ${#MODULE_CATALOG_HARDENING_KEYS[@]} )) ]'
check "carries tier, risks and requires"              '[ "${MODULE_CATALOG_TIER[remote-access]}" = advanced ] && [ "${MODULE_CATALOG_RISKS[terminal]}" = host-access ] && [ "${MODULE_CATALOG_REQUIRES[sealing]}" = provenance ]'
check "every key has a name in every language"        'for l in "${MODULE_CATALOG_LANGS[@]}"; do for k in "${MODULE_CATALOG_KEYS[@]}"; do [ -n "${MODULE_TEXT_NAME[$l:$k]:-}" ] && [ -n "${MODULE_TEXT_DESC[$l:$k]:-}" ] || exit 1; done; done'

echo "8) language choice (--lang / \$LANG, fallback en)"
check "--lang de wins"                                '[ "$(module_text_lang de)" = de ]'
check "--lang en wins"                                '[ "$(module_text_lang en)" = en ]'
check "unknown --lang falls back to en"               '[ "$(module_text_lang fr)" = en ]'
check "no flag: LANG prefix decides"                  '[ "$(LANG=de_DE.UTF-8 module_text_lang)" = de ]'
check "no flag: LANG=fr_FR falls back to en"          '[ "$(LANG=fr_FR module_text_lang)" = en ]'
check "no flag and no LANG falls back to en"          '[ "$(LANG= LC_ALL= LC_MESSAGES= module_text_lang)" = en ]'
check "LC_ALL beats LANG"                             '[ "$(LC_ALL=de_DE.UTF-8 LANG=fr_FR module_text_lang)" = de ]'
check "unknown module key falls back to the key"      '[ "$(module_text_name nope de)" = nope ] && [ -z "$(module_text_desc nope de)" ]'

echo "9) the preset prompt shows the module texts"
# The menu goes to stderr, the chosen preset to stdout — capture stderr only. No pipe into
# grep: with pipefail an early-exiting grep would SIGPIPE the writer and fail the check.
menu() { module_preset_prompt core "${1:-}" <<<"" 2>&1 >/dev/null; }
MENU_DE="$(menu de)"
MENU_FALLBACK="$(LANG=fr_FR menu)"
check "--lang de shows German names"                  '[[ "$MENU_DE" == *Fernzugriff* ]]'
check "--lang de shows German descriptions"           '[[ "$MENU_DE" == *"über Tailscale"* ]]'
check "--lang de lists the research modules"          '[[ "$MENU_DE" == *Workflow-Memory* ]]'
check "--lang de speaks German around the list"       '[[ "$MENU_DE" == *"Welche Module"* ]]'
check "no flag + LANG=fr_FR shows English"            '[[ "$MENU_FALLBACK" == *"Remote access"* ]]'
check "no flag + LANG=fr_FR has no German text"       '[[ "$MENU_FALLBACK" != *Fernzugriff* ]]'
check "no flag + LANG=fr_FR speaks English"           '[[ "$MENU_FALLBACK" == *"Which modules"* ]]'
check "every feature module is offered exactly once"  '[ "$(printf "%s\n" "$MENU_DE" | grep -c "^ \{9\}[^ ].* — ")" -eq "${#MODULE_CATALOG_FEATURE_KEYS[@]}" ]'
check "the language never changes the result"         '[ "$(LANG=fr_FR module_preset_prompt research <<<"" 2>/dev/null)" = research ]'

echo "10) the comment header of the written config follows --lang (TODO 7c90b447)"
CFG_EN="$TMP/backend-en/config/application.yml"
module_preset_write "$CFG_EN" core en; RC=$?
check "writes with rc=0"                              '[ "$RC" -eq 0 ]'
check "header text is English"                        'grep -q "^# Local override config" "$CFG_EN"'
check "still explains how to override single modules" 'grep -q "automation.modules.webhooks: true" "$CFG_EN"'
check "no German header text leaks in"                '! grep -q "Lokale Override-Config" "$CFG_EN"'

CFG_DE="$TMP/backend-de/config/application.yml"
module_preset_write "$CFG_DE" core de; RC=$?
check "writes with rc=0"                              '[ "$RC" -eq 0 ]'
check "header text is German"                         'grep -q "^# Lokale Override-Config" "$CFG_DE"'

CFG_DEFAULT="$TMP/backend-default/config/application.yml"
module_preset_write "$CFG_DEFAULT" core; RC=$?
check "no lang argument keeps the German default"     'grep -q "^# Lokale Override-Config" "$CFG_DEFAULT"'

echo
echo "passed: $PASS, failed: $FAIL"
[ "$FAIL" -eq 0 ]
