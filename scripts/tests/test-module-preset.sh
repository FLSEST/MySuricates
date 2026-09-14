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
check "unknown preset exits 2 before any check"       'bash "$SETUP" --preset everything >/dev/null 2>&1; [ $? -eq 2 ]'

echo
echo "passed: $PASS, failed: $FAIL"
[ "$FAIL" -eq 0 ]
