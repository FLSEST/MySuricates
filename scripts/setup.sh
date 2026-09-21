#!/usr/bin/env bash
#
# MySuricates — Installer. Fuehrt in einem Durchgang von der nackten Maschine bis zum Login.
#
#   ./scripts/setup.sh                     # im Terminal: gefuehrt, Schritt fuer Schritt
#   ./scripts/setup.sh --yes               # ohne Rueckfragen: Defaults, fehlende Werkzeuge installieren, starten
#   ./scripts/setup.sh --check             # nur pruefen und den Plan zeigen (Exit 1, wenn etwas fehlt)
#
#   --mode one|staged            eine Instanz (Default) oder Dev · Test · Prod
#   --runtime container|host     Container (Default, nur Docker noetig) oder direkt auf diesem Rechner
#   --preset core|research|full  Module (Standard = core); --modules a,b fuer einzelne Module dazu
#   --provider claude|codex|pi   Standard-KI-Anbieter (Default: der erste angemeldete)
#   --port N                     Port der Instanz (Default 9090)
#   --bind ADDR                  Adresse (Default 127.0.0.1; 0.0.0.0 = auch aus dem Netz — VPN davor)
#   --projects DIR               Projektordner fuer den Container (Default: Ordner ueber dem Klon)
#   --name NAME                  Name der Instanz (Default main)
#   --lang de                    Deutsch (Default Englisch)
#   --reconfigure                vorhandene Instanz-Datei neu schreiben
#   --preset core --force        in einer vorhandenen Einrichtung nur das Preset aendern
#   --dry-run                    nichts installieren, bauen oder starten — nur zeigen und Dateien schreiben
#   --show-key                   API-Key am Ende vollstaendig zeigen (Default: maskiert)
#   --from-source                fuer Mitwirkende: Quellcode-Setup mit Vite (docs/entwicklung.md)
#   --install / --start          aeltere Schreibweise: ohne Rueckfragen installieren bzw. am Ende starten
#
# Ohne Terminal und ohne --yes/--install/--start wird nur geprueft. Jede Instanz landet als Datei
# in deploy/instances/<name>.env; starten, stoppen und ansehen danach mit scripts/instance.sh.
# Greift nie auf die Datenbank oder die REST-API einer Instanz zu. bash-3.2-tauglich (macOS).
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LIB="$SCRIPT_DIR/lib"
INSTANCES_DIR="${MYSURICATES_INSTANCES_DIR:-$REPO_ROOT/deploy/instances}"
# shellcheck source=lib/i18n.sh
. "$LIB/i18n.sh"
# shellcheck source=lib/tui.sh
. "$LIB/tui.sh"
# shellcheck source=lib/deps.sh
. "$LIB/deps.sh"
# shellcheck source=lib/providers.sh
. "$LIB/providers.sh"
# shellcheck source=lib/module-preset.sh
. "$LIB/module-preset.sh"
[ -r "$DEPS_DATA/env.sh" ] && . "$DEPS_DATA/env.sh"

# --- Flags ------------------------------------------------------------------------------------------
MODE=""; RUNTIME=""; PRESET=""; MODULES=""; PROVIDER=""; PORT=""; PROJECTS=""; NAME="main"; BIND="127.0.0.1"
LANG_OPT=""; YES=0; CHECK=0; DRY=0; SHOW_KEY=0; FROM_SOURCE=0; INSTALL=0; START=0; FORCE=0; RECONF=0
die() { echo "setup.sh: $*" >&2; exit 2; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift ;;          --mode=*) MODE="${1#*=}" ;;
    --runtime) RUNTIME="${2:-}"; shift ;;    --runtime=*) RUNTIME="${1#*=}" ;;
    --preset) PRESET="${2:-}"; shift ;;      --preset=*) PRESET="${1#*=}" ;;
    --modules) MODULES="${2:-}"; shift ;;    --modules=*) MODULES="${1#*=}" ;;
    --provider) PROVIDER="${2:-}"; shift ;;  --provider=*) PROVIDER="${1#*=}" ;;
    --port) PORT="${2:-}"; shift ;;          --port=*) PORT="${1#*=}" ;;
    --bind) BIND="${2:-}"; shift ;;          --bind=*) BIND="${1#*=}" ;;
    --projects) PROJECTS="${2:-}"; shift ;;  --projects=*) PROJECTS="${1#*=}" ;;
    --name) NAME="${2:-}"; shift ;;          --name=*) NAME="${1#*=}" ;;
    --lang) LANG_OPT="${2:-}"; shift ;;      --lang=*) LANG_OPT="${1#*=}" ;;
    --yes|-y) YES=1 ;;
    --check) CHECK=1 ;;
    --dry-run) DRY=1 ;;
    --show-key) SHOW_KEY=1 ;;
    --from-source) FROM_SOURCE=1 ;;
    --install) INSTALL=1 ;;
    --start) START=1 ;;
    --force) FORCE=1 ;;
    --reconfigure) RECONF=1 ;;
    -h|--help) sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unbekannte Option: $1 (siehe --help)" ;;
  esac
  shift
done
[ -z "$PRESET" ] || module_preset_valid "$PRESET" || die "Unbekanntes Preset: $PRESET (erlaubt: $MODULE_PRESETS)"
[ "$FORCE" = 0 ] || [ -n "$PRESET" ] || die "--force gilt nur zusammen mit --preset <${MODULE_PRESETS// /|}> (siehe --help)"
case "$MODE" in ""|one|staged) ;; *) die "--mode one|staged, nicht '$MODE'" ;; esac
case "$RUNTIME" in ""|container|host) ;; *) die "--runtime container|host, nicht '$RUNTIME'" ;; esac
case "$NAME" in *[!a-z0-9-]*|"") die "--name nur aus a-z, 0-9 und -" ;; esac
case "$BIND" in *[!0-9.:a-fA-F]*|"") die "--bind braucht eine IP-Adresse" ;; esac
[ -z "$PORT" ] || case "$PORT" in *[!0-9]*) die "--port braucht eine Zahl" ;; esac
if [ -n "$PROVIDER" ]; then
  PROVIDER="$(printf '%s' "$PROVIDER" | tr 'A-Z' 'a-z')"
  providers_ids | grep -x "$PROVIDER" >/dev/null || die "Unbekannter Anbieter: $PROVIDER (bekannt: $(providers_ids | tr '\n' ' '))"
fi
for m in $(printf '%s' "$MODULES" | tr ',' ' '); do
  printf '%s\n' "${MODULE_CATALOG_FEATURE_KEYS[@]}" | grep -x "$m" >/dev/null || die "Unbekanntes Modul: $m"
done

i18n_load "$LANG_OPT"
tui_init
INTERACTIVE=0; [ "$TUI_TTY" = 1 ] && [ "$YES" = 0 ] && INTERACTIVE=1
# Ohne Terminal und ohne Auftrag: nur pruefen — kein unbeabsichtigtes Installieren aus Pipes und CI
if [ "$INTERACTIVE" = 0 ] && [ "$YES" = 0 ] && [ "$INSTALL" = 0 ] && [ "$START" = 0 ] && [ "$DRY" = 0 ]; then CHECK=1; fi
DO_INSTALL=0; { [ "$YES" = 1 ] || [ "$INSTALL" = 1 ]; } && DO_INSTALL=1
DO_START=0; { [ "$YES" = 1 ] || [ "$START" = 1 ]; } && DO_START=1

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates"
LOG_DIR="$STATE_DIR/setup-logs"
mkdir -p "$LOG_DIR" 2>/dev/null
DOCKER_SG=0   # 1 = Docker fuer diesen Durchgang ueber 'sg docker' (frisch in die Gruppe aufgenommen)

STEPS=("$(msg step_welcome)" "$(msg step_mode)" "$(msg step_checks)" "$(msg step_modules)" "$(msg step_ai)" "$(msg step_ports)" "$(msg step_build)" "$(msg step_done)")

version() { sed -n '/<\/parent>/,$ s/.*<version>\(.*\)<\/version>.*/\1/p' "$REPO_ROOT/ai-automation-backend/pom.xml" | head -n 1 | sed 's/-SNAPSHOT$//'; }
section() {  # Schrittleiste vor jedem Schritt (nur im Terminal; sonst eine schlichte Ueberschrift)
  if [ "$TUI_TTY" = 1 ]; then printf '\n'; tui_rail "$1" "${STEPS[@]}"; printf '\n'
  else printf '\n== %s ==\n' "${STEPS[$1]}"; fi
}
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# free_port BASIS [VERGEBEN…] — erster Port ab BASIS, der weder belegt noch schon vergeben ist
free_port() {
  local p="$1" i=0 taken=" ${2:-} "
  while { port_in_use "$p" || [ "${taken#* $p }" != "$taken" ]; } && [ "$i" -lt 50 ]; do p=$((p + 1)); i=$((i + 1)); done
  echo "$p"
}
prop_env() { printf '%s' "$1" | tr 'a-z.' 'A-Z_' | tr -d '-'; }   # automation.modules.remote-access → AUTOMATION_MODULES_REMOTEACCESS
existing_instances() { local f; for f in "$INSTANCES_DIR"/*.env; do [ -f "$f" ] && basename "$f" .env; done; }

# --- Schritt 1: Willkommen --------------------------------------------------------------------------
step_welcome() {
  local ai="" id st l_lang l_ai l_time w=0 l
  for id in $(providers_ids); do
    st="$(providers_status | awk -v i="$id" '$1==i{print $2" "$3}')"
    case "$st" in
      "present logged-in") ai="$ai${C_GREEN}$id $T_OK${C_RESET}  " ;;
      "present logged-out") ai="$ai${C_AMBER}$id $T_WARN${C_RESET}  " ;;
      *) ai="$ai${C_GREY}$id $T_BAD${C_RESET}  " ;;
    esac
  done
  # Beschriftungen auf gleiche Breite — die Laenge haengt von der Sprache ab
  l_lang="$(msg header_language)"; l_ai="$(msg header_ai)"; l_time="$(msg header_time)"
  for l in "$l_lang" "$l_ai" "$l_time"; do [ "${#l}" -gt "$w" ] && w=${#l}; done
  if [ "$TUI_TTY" = 1 ]; then
    tui_header "${C_GREY}$(msg header_setup)${C_RESET}     ${C_BOLD}v$(version)${C_RESET}" \
      "${C_FRAME}$(_tui_repeat "$T_H" 30)${C_RESET}" \
      "${C_GREY}${l_lang}$(_tui_repeat ' ' $((w - ${#l_lang} + 2)))${C_RESET}$(msg header_language_value)" \
      "${C_GREY}${l_ai}$(_tui_repeat ' ' $((w - ${#l_ai} + 2)))${C_RESET}$ai" \
      "${C_GREY}${l_time}$(_tui_repeat ' ' $((w - ${#l_time} + 2)))${C_RESET}$(msg header_time_value 10)"
  else
    printf 'MySuricates %s v%s\n' "$(msg header_setup)" "$(version)"
  fi
  printf '  %s\n' "$(msg welcome_intro ${#STEPS[@]})"
  return 0
}

# --- Schritt 2: Modus (bei vorhandener Einrichtung: aendern oder neu) --------------------------------
step_mode() {
  local choice rc names
  section 1
  names="$(existing_instances | tr '\n' ' ')"
  if [ "$INTERACTIVE" = 1 ] && [ -n "$names" ] && [ -z "$MODE$RUNTIME" ] && [ "$RECONF" = 0 ]; then
    tui_select choice "$(msg rerun_title): $names" 0 \
      "$(msg rerun_change "$(existing_instances | head -n 1)")|$(msg rerun_change_hint)" \
      "$(msg rerun_fresh)|$(msg rerun_fresh_hint)" "$(msg rerun_quit)|"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in
      0) CHANGE_NAME="$(existing_instances | head -n 1)"; load_existing "$CHANGE_NAME"; RECONF=1; return 0 ;;
      1) RECONF=1 ;;
      2) return 3 ;;
    esac
  fi
  if [ -z "$MODE$RUNTIME" ] && [ "$INTERACTIVE" = 1 ]; then
    tui_select choice "$(msg step_of 2 ${#STEPS[@]}) · $(msg mode_title)" 0 \
      "$(msg mode_container)|$(msg mode_container_hint) · $(msg recommended)" \
      "$(msg mode_host)|$(msg mode_host_hint)" \
      "$(msg mode_staged)|$(msg mode_staged_hint)"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in 0) MODE=one; RUNTIME=container ;; 1) MODE=one; RUNTIME=host ;; 2) MODE=staged; RUNTIME=container ;; esac
  fi
  MODE="${MODE:-one}"; RUNTIME="${RUNTIME:-container}"; [ "$MODE" = staged ] && RUNTIME=container
  case "$MODE:$RUNTIME" in
    one:container) tui_done "$(msg step_mode)" "$(msg mode_container)" ;;
    one:host) tui_done "$(msg step_mode)" "$(msg mode_host)" ;;
    staged:*) tui_done "$(msg step_mode)" "$(msg mode_staged)" ;;
  esac
  return 0
}

# Werte einer vorhandenen Instanz als Vorbelegung fuer „Einstellungen aendern“
load_existing() {
  local f="$INSTANCES_DIR/$1.env" v
  NAME="$1"; MODE=one
  v="$(grep '^MYSURICATES_RUNTIME=' "$f" | cut -d= -f2)"; RUNTIME="${RUNTIME:-${v:-container}}"
  v="$(grep '^MYSURICATES_PORT=' "$f" | cut -d= -f2)"; PORT="${PORT:-$v}"
  v="$(grep '^MYSURICATES_PRESET=' "$f" | cut -d= -f2)"; PRESET="${PRESET:-$v}"
  v="$(grep '^AUTOMATION_AI_DEFAULT_PROVIDER=' "$f" | cut -d= -f2 | tr 'A-Z' 'a-z')"; PROVIDER="${PROVIDER:-$v}"
  v="$(grep '^MYSURICATES_PROJECTS=' "$f" | cut -d= -f2-)"; PROJECTS="${PROJECTS:-$v}"
}

# --- Schritt 3: Pruefen und installieren ------------------------------------------------------------
step_checks() {
  local need tool state lines plan missing="" rc
  section 2
  need="$(deps_required "$( [ "$RUNTIME" = host ] && echo host || echo container )")"
  lines=()
  for tool in $need; do
    if [ "$tool" = docker ]; then
      state="$(deps_docker_state)"
      case "$state" in running) lines[${#lines[@]}]="  ${C_GREEN}$T_OK${C_RESET} docker   ${C_GREY}$(deps_version docker)${C_RESET}" ;;
        missing) lines[${#lines[@]}]="  ${C_RED}$T_BAD${C_RESET} docker   ${C_GREY}$(msg status_missing)${C_RESET}" ;;
        *) lines[${#lines[@]}]="  ${C_AMBER}$T_WARN${C_RESET} docker   ${C_GREY}$state${C_RESET}" ;;
      esac
      continue
    fi
    state="$(deps_check "$tool")"
    case "${state%% *}" in
      ok) lines[${#lines[@]}]="  ${C_GREEN}$T_OK${C_RESET} $(printf '%-8s' "$tool") ${C_GREY}${state#ok }${C_RESET}" ;;
      old) lines[${#lines[@]}]="  ${C_RED}$T_BAD${C_RESET} $(printf '%-8s' "$tool") ${C_GREY}${state#old } — $(msg status_old)${C_RESET}" ;;
      *) lines[${#lines[@]}]="  ${C_RED}$T_BAD${C_RESET} $(printf '%-8s' "$tool") ${C_GREY}$(msg status_missing)${C_RESET}" ;;
    esac
  done
  plan="$(deps_plan "$( [ "$RUNTIME" = host ] && echo host || echo container )")"
  if [ -n "$plan" ]; then
    lines[${#lines[@]}]=""
    lines[${#lines[@]}]="${C_BOLD}$(msg checks_plan)${C_RESET}"
    while IFS= read -r l; do lines[${#lines[@]}]="  $(printf '%s' "$l" | awk '{m=$2; $1=""; $2=""; sub(/^ +/, ""); print}')"; done <<EOF
$plan
EOF
  fi
  tui_box "$(msg step_of 3 ${#STEPS[@]}) · $(msg step_checks) — $(msg checks_system "$(deps_os)/$(deps_arch)" "$(deps_pm)")" "${lines[@]}"
  missing="$(printf '%s\n' "$plan" | awk 'NF{print $1}' | tr '\n' ' ')"
  if [ "$CHECK" = 1 ]; then
    if [ -n "$plan" ]; then printf '\n%s\n' "$plan"; printf '  %s\n' "$(msg checks_summary_missing "$missing")"; return 4; fi
    printf '  %s\n' "$(msg checks_summary_ok)"; return 5
  fi
  if [ -n "$plan" ]; then
    if [ "$DO_INSTALL" = 0 ]; then
      if [ "$INTERACTIVE" = 1 ]; then tui_confirm "$(msg checks_install_q)" y || { printf '  %s\n' "$(msg checks_still_missing "$missing")"; return 1; }
      else printf '  %s\n' "$(msg checks_still_missing "$missing")"; return 1; fi
    fi
    install_plan "$plan" || return 1
  fi
  docker_ready || return 1
  tui_done "$(msg step_checks)" "$(msg checks_all_ok)"
  return 0
}

install_plan() {
  local tool method cmd rc=0
  while read -r tool method cmd; do
    [ -n "$tool" ] || continue
    case "$method" in
      pm)
        if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "$cmd")"; continue; fi
        tui_run "$tool" "$LOG_DIR/install-$tool.log" bash -c "$cmd" || rc=1
        case "$cmd" in *usermod\ -aG\ docker*) DOCKER_SG=1 ;; esac ;;
      download)
        if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "download $tool")"; continue; fi
        tui_run "$tool" "$LOG_DIR/download-$tool.log" deps_download "$( [ "$tool" = node ] && echo node || echo java )" || rc=1
        [ -r "$DEPS_DATA/env.sh" ] && . "$DEPS_DATA/env.sh" ;;
      manual)
        printf '  %s%s%s %s\n' "$C_AMBER" "$T_WARN" "$C_RESET" "$(msg checks_manual "$cmd")"; rc=1 ;;
    esac
  done <<EOF
$1
EOF
  [ "$DRY" = 1 ] && return 0
  # Nachpruefen: was jetzt noch fehlt, bleibt fehlend
  local still
  still="$(deps_plan "$( [ "$RUNTIME" = host ] && echo host || echo container )" | awk 'NF{print $1}' | tr '\n' ' ')"
  if [ -n "$still" ]; then printf '  %s\n' "$(msg checks_still_missing "$still")"; return 1; fi
  return 0
}

# Docker muss fuer Container-Wege laufen und benutzbar sein; frisch in der Gruppe ⇒ 'sg docker'
docker_ready() {
  [ "$RUNTIME" = container ] || return 0
  [ "$DRY" = 1 ] && return 0
  case "$(deps_docker_state)" in
    running) return 0 ;;
    no-permission)
      if command -v sg >/dev/null 2>&1 && sg docker -c 'docker info' >/dev/null 2>&1; then
        DOCKER_SG=1; printf '  %s\n' "$(msg checks_docker_group)"; return 0
      fi
      printf '  %s\n' "$(msg checks_docker_group)"; return 1 ;;
    *) printf '  %s\n' "$(msg checks_docker_down)"; return 1 ;;
  esac
}

# --- Schritt 4: Module ------------------------------------------------------------------------------
step_modules() {
  local choice rc items key tier on sel hard
  section 3
  if [ "$MODE" = staged ]; then tui_done "$(msg step_modules)" "$(msg modules_staged)"; return 0; fi
  CUSTOM_LINES=""
  if [ -z "$PRESET" ] && [ "$INTERACTIVE" = 1 ]; then
    tui_select choice "$(msg step_of 4 ${#STEPS[@]}) · $(msg modules_title)" 0 \
      "$(msg modules_standard)|$(msg modules_standard_hint) · $(msg recommended)" \
      "$(msg modules_research)|$(msg modules_research_hint)" \
      "$(msg modules_full)|$(msg modules_full_hint)" \
      "$(msg modules_custom)|$(msg modules_custom_hint)"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in
      0) PRESET=core ;; 1) PRESET=research ;; 2) PRESET=full ;;
      3)
        PRESET=core
        items=()
        for key in "${MODULE_CATALOG_FEATURE_KEYS[@]}"; do
          tier="$(module_catalog_get TIER "$key")"; on=off; [ "$tier" = standard ] && on=on
          items[${#items[@]}]="$on:$key:$(module_text_name "$key" "$I18N_LANG"):$tier"
        done
        tui_checklist sel "$(msg modules_pick_title)" "${items[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
        for key in "${MODULE_CATALOG_FEATURE_KEYS[@]}"; do
          tier="$(module_catalog_get TIER "$key")"
          if printf ' %s ' "$sel" | grep " $key " >/dev/null; then [ "$tier" = standard ] || CUSTOM_LINES="$CUSTOM_LINES$(prop_env "$(module_catalog_get PROPERTY "$key")")=true
"
          else [ "$tier" = standard ] && CUSTOM_LINES="$CUSTOM_LINES$(prop_env "$(module_catalog_get PROPERTY "$key")")=false
"; fi
        done
        items=()
        for key in "${MODULE_CATALOG_HARDENING_KEYS[@]}"; do
          on=off; [ "$(module_catalog_get DEFAULT "$key")" = true ] && on=on
          items[${#items[@]}]="$on:$key:$(module_text_name "$key" "$I18N_LANG")"
        done
        tui_checklist hard "$(msg modules_hardening_title)" "${items[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
        for key in "${MODULE_CATALOG_HARDENING_KEYS[@]}"; do
          on=false; printf ' %s ' "$hard" | grep " $key " >/dev/null && on=true
          CUSTOM_LINES="$CUSTOM_LINES$(prop_env "$(module_catalog_get PROPERTY "$key")")=$on
"
        done
        tui_confirm "$(msg modules_ui_editable_q)" n && CUSTOM_LINES="${CUSTOM_LINES}AUTOMATION_MODULES_UIEDITABLE=true
"
        if [ "$RUNTIME" = container ]; then tui_confirm "$(msg socket_q)" n && SOCKET=1; fi ;;
    esac
  fi
  PRESET="${PRESET:-core}"
  for key in $(printf '%s' "$MODULES" | tr ',' ' '); do
    CUSTOM_LINES="$CUSTOM_LINES$(prop_env "$(module_catalog_get PROPERTY "$key")")=true
"
  done
  tui_done "$(msg step_modules)" "$PRESET${MODULES:+ + $MODULES}"
  return 0
}

# --- Schritt 5: KI-Anbieter -------------------------------------------------------------------------
step_ai() {
  local id p l lines choice rc opts default=0 i=0 present=""
  section 4
  lines=()
  while read -r id p l; do
    case "$p:$l" in
      present:logged-in) lines[${#lines[@]}]="  ${C_GREEN}$T_OK${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_logged_in)${C_RESET}"; present="$present $id" ;;
      present:*) lines[${#lines[@]}]="  ${C_AMBER}$T_WARN${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_logged_out) — $(provider_get "$id" login)${C_RESET}"; present="$present $id" ;;
      *) lines[${#lines[@]}]="  ${C_GREY}–${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_missing)${C_RESET}" ;;
    esac
  done <<EOF
$(providers_status)
EOF
  if [ -z "$present" ]; then
    lines[${#lines[@]}]=""; lines[${#lines[@]}]="$(msg ai_none)"
    for id in $(providers_ids); do lines[${#lines[@]}]="  $(msg ai_install "$id" "$(provider_get "$id" install)" "$(provider_get "$id" login)")"; done
  fi
  tui_box "$(msg step_of 5 ${#STEPS[@]}) · $(msg ai_title)" "${lines[@]}"
  if [ -z "$PROVIDER" ]; then
    # Default: der erste angemeldete, sonst der erste vorhandene, sonst der erste bekannte
    PROVIDER="$(providers_status | awk '$2=="present" && $3=="logged-in"{print $1; exit}')"
    [ -n "$PROVIDER" ] || PROVIDER="$(printf '%s\n' $present | head -n 1)"
    [ -n "$PROVIDER" ] || PROVIDER="$(providers_ids | head -n 1)"
    set -- $present
    if [ "$INTERACTIVE" = 1 ] && [ "$#" -gt 1 ]; then
      opts=(); i=0
      for id in "$@"; do opts[${#opts[@]}]="$id|$(provider_get "$id" cli)"; [ "$id" = "$PROVIDER" ] && default=$i; i=$((i + 1)); done
      tui_select choice "$(msg ai_choose)" "$default" "${opts[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
      eval "PROVIDER=\"\${$((choice + 1))}\""
    fi
  fi
  tui_done "$(msg step_ai)" "$PROVIDER"
  return 0
}

# --- Schritt 6: Ports und Ordner --------------------------------------------------------------------
step_ports() {
  local p
  section 5
  if [ "$MODE" = staged ]; then
    P_DEV="$(free_port 9090)"; P_TEST="$(free_port 8090 "$P_DEV")"; P_PROD="$(free_port 9091 "$P_DEV $P_TEST")"
    tui_done "$(msg step_ports)" "Dev $P_DEV · Test $P_TEST · Prod $P_PROD"
    return 0
  fi
  if [ -z "$PORT" ]; then
    PORT="$(free_port 9090)"
    [ "$PORT" = 9090 ] || printf '  %s\n' "$(msg ports_busy 9090 "$PORT")"
    [ "$INTERACTIVE" = 1 ] && tui_input PORT "$(msg ports_q "$NAME")" "$PORT"
  fi
  if [ "$RUNTIME" = container ] && [ -z "$PROJECTS" ]; then
    PROJECTS="$(cd "$REPO_ROOT/.." && pwd)"
    [ "$INTERACTIVE" = 1 ] && tui_input PROJECTS "$(msg projects_q)" "$PROJECTS"
  fi
  tui_done "$(msg step_ports)" "$PORT${PROJECTS:+ · $PROJECTS}"
  return 0
}

# --- Schritt 7: Instanz-Dateien schreiben, bauen, starten -------------------------------------------
write_instance() {  # NAME RUNTIME PORT PRESET ENVIRONMENT SEALING REQUIRE_SEALED
  local f="$INSTANCES_DIR/$1.env"
  mkdir -p "$INSTANCES_DIR"
  if [ -f "$f" ] && [ "$RECONF" = 0 ]; then
    if [ "$FORCE" = 1 ] && [ -n "$PRESET_FLAG" ]; then
      sed "s/^MYSURICATES_PRESET=.*/MYSURICATES_PRESET=$PRESET_FLAG/" "$f" > "$f.tmp" && cat "$f.tmp" > "$f" && rm -f "$f.tmp"
      grep '^MYSURICATES_PRESET=' "$f" >/dev/null || printf 'MYSURICATES_PRESET=%s\n' "$PRESET_FLAG" >> "$f"
    fi
    printf '  %s\n' "$(msg kept_existing "deploy/instances/$1.env")"
    return 0
  fi
  {
    printf '# MySuricates-Instanz „%s“ — geschrieben von scripts/setup.sh.\n' "$1"
    printf '# Werte aendern, dann: scripts/instance.sh up --name %s   (AUTOMATION_*-Zeilen gehen an die Instanz)\n' "$1"
    printf 'MYSURICATES_RUNTIME=%s\nMYSURICATES_PORT=%s\nMYSURICATES_BIND=%s\nMYSURICATES_PRESET=%s\n' "$2" "$3" "$BIND" "$4"
    [ -n "$5" ] && printf 'MYSURICATES_ENVIRONMENT=%s\n' "$5"
    if [ "$2" = container ]; then printf 'MYSURICATES_PROJECTS=%s\nMYSURICATES_DOCKER_SOCKET=%s\n' "${PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}" "${SOCKET:-0}"; fi
    printf 'MYSURICATES_HARDENING_SEALING=%s\nMYSURICATES_HARDENING_REQUIRE_SEALED=%s\n' "${6:-false}" "${7:-false}"
    printf 'AUTOMATION_AI_DEFAULT_PROVIDER=%s\n' "$(printf '%s' "$PROVIDER" | tr 'a-z' 'A-Z')"
    [ "$1" = "$NAME" ] && [ -n "${CUSTOM_LINES:-}" ] && printf '%s' "$CUSTOM_LINES"
  } > "$f"
}

start_instance() {  # NAME
  local cmd="bash '$SCRIPT_DIR/instance.sh' up --name $1"
  [ "$RECONF" = 1 ] && [ "$RUNTIME" = host ] && cmd="bash '$SCRIPT_DIR/instance.sh' down --name $1 >/dev/null 2>&1; $cmd --rebuild"
  if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "scripts/instance.sh up --name $1")"; return 0; fi
  [ "$DOCKER_SG" = 1 ] && cmd="sg docker -c \"$cmd\""
  tui_run "$(msg build_run "$1")" "$LOG_DIR/start-$1.log" bash -c "$cmd" || { printf '  %s\n' "$(msg build_failed "$1" "$LOG_DIR/start-$1.log")"; return 1; }
}

step_build() {
  section 6
  if [ "$MODE" = staged ]; then
    write_instance dev container "$P_DEV" full Dev false false
    write_instance test container "$P_TEST" core Test true true
    write_instance prod container "$P_PROD" core Prod true true
    INSTANCES="dev test prod"
  else
    write_instance "$NAME" "$RUNTIME" "$PORT" "$PRESET" "" false false
    INSTANCES="$NAME"
  fi
  STARTED=0
  if [ "$DO_START" = 0 ] && [ "$INTERACTIVE" = 1 ]; then tui_confirm "$(msg build_q)" y && DO_START=1; fi
  if [ "$DO_START" = 1 ]; then
    for n in $INSTANCES; do start_instance "$n" || return 1; done
    STARTED=1
  fi
  return 0
}

# --- Schritt 8: Fertig ------------------------------------------------------------------------------
step_done() {
  local n port key lines
  section 7
  lines=()
  for n in $INSTANCES; do
    port="$(grep '^MYSURICATES_PORT=' "$INSTANCES_DIR/$n.env" | cut -d= -f2)"
    if [ "$STARTED" = 1 ] && [ "$DRY" = 0 ]; then
      lines[${#lines[@]}]="${C_BOLD}$(printf '%-5s' "$n")${C_RESET} $(msg done_open "http://localhost:$port")"
      key="$(bash "$SCRIPT_DIR/instance.sh" key --name "$n" 2>/dev/null)"
      if [ -n "$key" ]; then
        [ "$SHOW_KEY" = 1 ] || key="…${key: -4}"
        lines[${#lines[@]}]="      $(msg done_key "$key")"
      fi
    else
      lines[${#lines[@]}]="${C_BOLD}$(printf '%-5s' "$n")${C_RESET} $(msg done_not_started)"
    fi
  done
  n="$(printf '%s' "$INSTANCES" | cut -d' ' -f1)"
  lines[${#lines[@]}]=""
  lines[${#lines[@]}]="$(msg done_key_cmd)  ${C_BOLD}scripts/instance.sh key --name $n${C_RESET}"
  lines[${#lines[@]}]="$(msg done_manage)  ${C_BOLD}scripts/instance.sh up|down|status --name $n${C_RESET}"
  lines[${#lines[@]}]="$(msg done_change)  ${C_BOLD}scripts/setup.sh${C_RESET} · deploy/instances/$n.env"
  logged="$(providers_status | awk -v i="$PROVIDER" '$1==i{print $3}')"
  [ "$logged" = logged-in ] || lines[${#lines[@]}]="${C_AMBER}$(msg ai_login_hint "$PROVIDER" "$(provider_get "$PROVIDER" login)")${C_RESET}"
  tui_box "$(msg step_of 8 ${#STEPS[@]}) · $(msg step_done)" "${lines[@]}"
  return 0
}

# --- Quellcode-Weg fuer Mitwirkende -----------------------------------------------------------------
from_source() {
  local plan cfg="$REPO_ROOT/ai-automation-backend/config/application.yml" rc
  RUNTIME=host
  plan="$(deps_plan source)"
  if [ -n "$plan" ]; then
    printf '%s\n' "$plan"
    [ "$CHECK" = 1 ] && exit 1
    [ "$DO_INSTALL" = 1 ] || { tui_confirm "$(msg checks_install_q)" y || exit 1; }
    install_plan "$plan" || exit 1
  fi
  [ "$CHECK" = 1 ] && exit 0
  bash "$SCRIPT_DIR/install-hooks.sh" >/dev/null 2>&1
  tui_run "npm ci" "$LOG_DIR/npm-ci.log" bash -c "cd '$REPO_ROOT/ai-automation-frontend' && npm ci" || exit 1
  tui_run "backend (runtime JAR)" "$LOG_DIR/build.log" bash "$SCRIPT_DIR/start-autotrigger-dev.sh" build || exit 1
  if [ -e "$cfg" ]; then
    if [ "$FORCE" = 1 ]; then module_preset_update "$cfg" "$PRESET"; fi
  else
    module_preset_write "$cfg" "${PRESET:-core}" "$I18N_LANG"
  fi
  [ "$DO_START" = 1 ] || { printf '  ./scripts/start-autotrigger-dev.sh\n'; exit 0; }
  exec bash "$SCRIPT_DIR/start-autotrigger-dev.sh" $([ "$SHOW_KEY" = 1 ] && echo --show-key)
}

# --- Ablauf -----------------------------------------------------------------------------------------
PRESET_FLAG="$PRESET"
[ "$FROM_SOURCE" = 1 ] && from_source
SOCKET=0; CUSTOM_LINES=""
step_welcome
FLOW=(step_mode step_checks step_modules step_ai step_ports step_build step_done)
i=0
while [ "$i" -lt "${#FLOW[@]}" ]; do
  "${FLOW[$i]}"; rc=$?
  case "$rc" in
    0) i=$((i + 1)) ;;
    2) [ "$i" -gt 0 ] && i=$((i - 1)) ;;      # b = zurueck
    3) printf '\n  %s\n' "$(msg quit)"; exit 1 ;;
    4) exit 1 ;;                              # --check: etwas fehlt
    5) exit 0 ;;                              # --check: alles da
    *) exit 1 ;;
  esac
done
exit 0
