#!/usr/bin/env bash
# Module preset for a fresh checkout: writes ai-automation-backend/config/application.yml.
# Sourced by scripts/setup.sh; tested by scripts/tests/test-module-preset.sh.
#
# Modules are decided at backend startup only (automation.modules.*, automation.hardening.*) —
# this helper never talks to a running backend and never overwrites an existing config file.

MODULE_PRESETS="core research full"

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

# Interactive choice on a TTY; prints the chosen preset. $1 = default.
module_preset_prompt() {
  local default="${1:-core}" answer
  {
    echo "  Welche Module soll diese Instanz beim Start aktivieren?"
    echo "    1) core      nur der Plattform-Kern — kleinste Oberfläche und Angriffsfläche"
    echo "    2) research  Kern + Forschungsmodule (Chains, Assistent, Insights, Memory, Audit, BPMN)"
    echo "    3) full      alles, auch Integrationen, Webhooks, Remote Access, Terminal, Staging"
  } >&2
  read -r -p "  Auswahl [1-3, Enter = ${default}]: " answer
  case "$answer" in
    1|core) echo core ;;
    2|research) echo research ;;
    3|full) echo full ;;
    "") echo "$default" ;;
    *) echo "$default" ;;
  esac
}
