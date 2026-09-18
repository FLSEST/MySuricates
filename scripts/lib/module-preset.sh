#!/usr/bin/env bash
# Module preset for a fresh checkout: writes ai-automation-backend/config/application.yml.
# Sourced by scripts/setup.sh; tested by scripts/tests/test-module-preset.sh.
#
# Modules are decided at backend startup only (automation.modules.*, automation.hardening.*) —
# this helper never talks to a running backend and never overwrites an existing config file.
#
# The module catalog and its display texts come from module-catalog.generated.sh, which the
# backend test ModuleCatalogTextsTest writes from com.automation.modules.ModuleCatalog plus
# ai-automation-frontend/src/data/modules.<lang>.json. Setup runs before any build, so there is
# no backend and no jq here — plain bash arrays are the whole interface.

MODULE_PRESETS="core research full"

_MODULE_PRESET_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [ -r "${_MODULE_PRESET_LIB_DIR}/module-catalog.generated.sh" ]; then
  # shellcheck source=module-catalog.generated.sh
  . "${_MODULE_PRESET_LIB_DIR}/module-catalog.generated.sh"
else
  # Missing generated file: the preset choice still works, only the module list stays empty.
  MODULE_CATALOG_LANGS=(en)
  MODULE_CATALOG_KEYS=()
  MODULE_CATALOG_FEATURE_KEYS=()
  MODULE_CATALOG_HARDENING_KEYS=()
  declare -gA MODULE_CATALOG_TIER=() MODULE_CATALOG_MATURITY=() MODULE_CATALOG_DEFAULT=() \
    MODULE_CATALOG_PROPERTY=() MODULE_CATALOG_RISKS=() MODULE_CATALOG_REQUIRES=() \
    MODULE_TEXT_NAME=() MODULE_TEXT_DESC=()
fi

# ---------------------------------------------------------------------------
# Language of the module texts
# ---------------------------------------------------------------------------

# Prints the language to show module texts in: $1 (--lang) if given, else the prefix of
# $LC_ALL/$LC_MESSAGES/$LANG. Anything without its own JSON file falls back to English.
module_text_lang() {
  local raw="${1:-}" pref known
  if [ -z "$raw" ]; then raw="${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}"; fi
  pref="${raw%%[._@-]*}"
  pref="$(printf '%s' "$pref" | tr '[:upper:]' '[:lower:]')"
  for known in "${MODULE_CATALOG_LANGS[@]}"; do
    if [ "$pref" = "$known" ]; then printf '%s' "$pref"; return 0; fi
  done
  printf 'en'
}

# Display name of a module. $1 = key, $2 = language. Unknown key ⇒ the key itself.
module_text_name() {
  local value="${MODULE_TEXT_NAME[${2:-en}:${1:-}]:-}"
  if [ -n "$value" ]; then printf '%s' "$value"; else printf '%s' "${1:-}"; fi
}

# One-sentence description of a module. $1 = key, $2 = language. Unknown key ⇒ empty.
module_text_desc() {
  printf '%s' "${MODULE_TEXT_DESC[${2:-en}:${1:-}]:-}"
}

# Lists the feature modules a preset adds over the previous one, one "  Name — description" per
# line. $1 = preset, $2 = language. Display only — the catalog itself stays the backend's job.
module_preset_modules() {
  local preset="${1:-}" lang="${2:-en}" key tier
  for key in "${MODULE_CATALOG_FEATURE_KEYS[@]}"; do
    tier="${MODULE_CATALOG_TIER[$key]:-}"
    case "$preset" in
      research) if [ "$tier" != "research" ]; then continue; fi ;;
      full)     if [ "$tier" = "research" ]; then continue; fi ;;
      *)        continue ;;
    esac
    printf '         %s — %s\n' "$(module_text_name "$key" "$lang")" "$(module_text_desc "$key" "$lang")"
  done
}

# 0 if $1 is a known preset
module_preset_valid() {
  case "${1:-}" in
    core|research|full) return 0 ;;
    *) return 1 ;;
  esac
}

# Writes the local override config with the chosen preset.
# $1 = target file, $2 = preset. Returns 3 if the file already exists (left untouched), 2 on bad preset.
module_preset_write() {
  local file="$1" preset="$2"
  module_preset_valid "$preset" || return 2
  [ -e "$file" ] && return 3
  mkdir -p "$(dirname "$file")" || return 1
  cat > "$file" <<EOF
# Lokale Override-Config (nicht im Git — siehe .gitignore).
# Spring Boot lädt ./config/application.yml zusätzlich zur Classpath-Config und mit
# höherer Priorität; beim Dev-Start ist das Arbeitsverzeichnis ai-automation-backend/.
#
# Optionale Module werden nur beim Start gelesen (Änderung ⇒ Backend neu starten):
#   preset: core      nur der Plattform-Kern (Workflows, Runs, Projekte, TODOs, Schedules)
#   preset: research  Kern + Chains, Assistent, Insights, Memory, Audit Center, BPMN
#   preset: full      alle Module, auch Integrationen, Webhooks, Remote Access, Terminal, Staging
# Einzelne Module überschreiben das Preset, z. B.:
#   automation.modules.webhooks: true
# Optionale Workflow-Härtung (unabhängig vom Preset):
#   automation.hardening.sealing: true
# Übersicht mit Status und Snippets: Settings → „Module & Härtung“.

automation:
  modules:
    preset: ${preset}
EOF
}

# The handful of sentences around the module list. The module texts themselves are never
# hard-coded here — they come from the generated file. $1 = language, $2 = message id.
module_preset_msg() {
  case "${1:-en}:${2:-}" in
    de:question) printf '%s' "Welche Module soll diese Instanz beim Start aktivieren?" ;;
    de:core)     printf '%s' "nur der Plattform-Kern — kleinste Oberfläche und Angriffsfläche" ;;
    de:research) printf '%s' "Kern plus die Forschungsmodule:" ;;
    de:full)     printf '%s' "research plus die erweiterten Module:" ;;
    de:later)    printf '%s' "Später änderbar: ai-automation-backend/config/application.yml bearbeiten, Backend neu starten." ;;
    de:choice)   printf '%s' "Auswahl [1-3, Enter = %s]: " ;;
    *:question)  printf '%s' "Which modules should this instance enable at startup?" ;;
    *:core)      printf '%s' "the platform core only — smallest surface and attack surface" ;;
    *:research)  printf '%s' "core plus the research modules:" ;;
    *:full)      printf '%s' "research plus the advanced modules:" ;;
    *:later)     printf '%s' "Change it later: edit ai-automation-backend/config/application.yml and restart the backend." ;;
    *:choice)    printf '%s' "Choice [1-3, Enter = %s]: " ;;
  esac
}

# Interactive choice on a TTY; prints the chosen preset. $1 = default, $2 = language
# (empty ⇒ module_text_lang). The language changes only what is shown, never the result.
module_preset_prompt() {
  local default="${1:-core}" lang="${2:-}" answer choice
  if [ -z "$lang" ]; then lang="$(module_text_lang)"; fi
  {
    echo "  $(module_preset_msg "$lang" question)"
    echo "    1) core      $(module_preset_msg "$lang" core)"
    echo "    2) research  $(module_preset_msg "$lang" research)"
    module_preset_modules research "$lang"
    echo "    3) full      $(module_preset_msg "$lang" full)"
    module_preset_modules full "$lang"
    echo "  $(module_preset_msg "$lang" later)"
  } >&2
  choice="$(module_preset_msg "$lang" choice)"
  read -r -p "  ${choice/\%s/$default}" answer
  case "$answer" in
    1|core) echo core ;;
    2|research) echo research ;;
    3|full) echo full ;;
    "") echo "$default" ;;
    *) echo "$default" ;;
  esac
}
