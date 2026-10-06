#!/usr/bin/env bash
#
# MySuricates — Installer. Takes you in one pass from the bare machine to the login.
# Flags and examples: ./scripts/setup.sh --help (text in scripts/lib/i18n/<language>.sh, M_help).
#
# Without a terminal and without --yes/--install/--start it only checks. Every instance ends up as a file
# in deploy/instances/<name>.env; start, stop and inspect it afterwards with scripts/instance.sh.
# Never touches an instance's database; it queries its REST API only read-only, whether workflow runs
# are running, before restarting it (29820f58). bash-3.2 compatible (macOS).
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
# shellcheck source=lib/netaddr.sh
. "$LIB/netaddr.sh"
# shellcheck source=lib/instance-runs.sh
. "$LIB/instance-runs.sh"
# shellcheck source=lib/setup-kept.sh
. "$LIB/setup-kept.sh"    # an existing instance file that stays: its values apply (fd4eb89f)
# shellcheck source=lib/setup-ports.sh
. "$LIB/setup-ports.sh"   # free ports; the one of the running own instance counts as free (0e5b82b7)
# shellcheck source=lib/host-mounts.sh
. "$LIB/host-mounts.sh"   # ai_env_value / claude_token_plausible — same rule as at start
[ -r "$DEPS_DATA/env.sh" ] && . "$DEPS_DATA/env.sh"

# --- Flags ------------------------------------------------------------------------------------------
MODE=""; RUNTIME=""; PRESET=""; MODULES=""; PROVIDER=""; PORT=""; PROJECTS=""; NAME="main"; BIND="127.0.0.1"
LANG_OPT=""; YES=0; CHECK=0; DRY=0; SHOW_KEY=0; FROM_SOURCE=0; INSTALL=0; START=0; FORCE=0; RECONF=0
HELP=0; SOCKET_FLAG=0; CHANGE_NAME=""; PORT_LOADED=0; LOADED_LINES=""
LOADED_ENVIRONMENT=""; LOADED_SEALING=false; LOADED_REQUIRE_SEALED=false; AI_FROM_HOST=""; AI_PROVIDERS=""; WITH_PI=0; STORE_AI_ENV=0
AI_ENV="${XDG_CONFIG_HOME:-$HOME/.config}/mysuricates/ai.env"
die() { echo "setup.sh: $*" >&2; exit 2; }
_prev=""
for _a in "$@"; do
  [ "$_prev" = --lang ] && LANG_OPT="$_a"
  case "$_a" in --lang=*) LANG_OPT="${_a#*=}" ;; esac
  _prev="$_a"
done
i18n_load "$LANG_OPT"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift ;;          --mode=*) MODE="${1#*=}" ;;
    --runtime) RUNTIME="${2:-}"; shift ;;    --runtime=*) RUNTIME="${1#*=}" ;;
    --preset) PRESET="${2:-}"; shift ;;      --preset=*) PRESET="${1#*=}" ;;
    --modules) MODULES="${2:-}"; shift ;;    --modules=*) MODULES="${1#*=}" ;;
    --provider) PROVIDER="${2:-}"; shift ;;  --provider=*) PROVIDER="${1#*=}" ;;
    --ai-from-host) AI_FROM_HOST="${2:-}"; shift ;;  --ai-from-host=*) AI_FROM_HOST="${1#*=}" ;;
    --ai-providers) AI_PROVIDERS="${2:-}"; shift ;;  --ai-providers=*) AI_PROVIDERS="${1#*=}" ;;
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
    --docker-socket) SOCKET_FLAG=1 ;;
    --with-pi) WITH_PI=1 ;;
    --store-ai-env) STORE_AI_ENV=1 ;;
    -h|--help) HELP=1 ;;
    *) die "$(msg err_option "$1")" ;;
  esac
  shift
done
if [ "$HELP" = 1 ]; then printf '%s\n' "$(msg help)"; exit 0; fi
[ -z "$PRESET" ] || module_preset_valid "$PRESET" || die "$(msg err_preset "$PRESET" "$MODULE_PRESETS")"
[ "$FORCE" = 0 ] || [ -n "$PRESET" ] || die "$(msg err_force "${MODULE_PRESETS// /|}")"
case "$MODE" in ""|one|staged) ;; *) die "$(msg err_mode "$MODE")" ;; esac
case "$RUNTIME" in ""|container|host) ;; *) die "$(msg err_runtime "$RUNTIME")" ;; esac
case "$NAME" in *[!a-z0-9-]*|"") die "$(msg err_name)" ;; esac
case "$BIND" in *[!0-9.:a-fA-F]*|"") die "$(msg err_bind)" ;; esac
[ -z "$PORT" ] || case "$PORT" in *[!0-9]*) die "$(msg err_port)" ;; esac
if [ -n "$PROVIDER" ]; then
  PROVIDER="$(printf '%s' "$PROVIDER" | tr 'A-Z' 'a-z')"
  providers_ids | grep -x "$PROVIDER" >/dev/null || die "$(msg err_provider "$PROVIDER" "$(providers_ids | tr '\n' ' ')")"
fi
case "$AI_FROM_HOST" in ""|programs|login|all) ;; *) die "$(msg err_ai_from_host "$AI_FROM_HOST")" ;; esac
AI_PROVIDERS="$(printf '%s' "$AI_PROVIDERS" | tr 'A-Z ' 'a-z,' | tr -s ',' | sed 's/^,//; s/,$//')"
for p in $(printf '%s' "$AI_PROVIDERS" | tr ',' ' '); do
  providers_ids | grep -x "$p" >/dev/null || die "$(msg err_ai_providers "$p" "$(providers_ids | tr '\n' ' ')")"
done
for m in $(printf '%s' "$MODULES" | tr ',' ' '); do
  printf '%s\n' "${MODULE_CATALOG_FEATURE_KEYS[@]}" | grep -x "$m" >/dev/null || die "$(msg err_module "$m")"
done

# What came via flag — in the terminal "change settings" asks for the remaining values with the file's value
# as default, not the ones given via flag (ca902719)
F_MODE="$MODE"; F_RUNTIME="$RUNTIME"; F_PRESET="$PRESET"; F_PROVIDER="$PROVIDER"; F_PROJECTS="$PROJECTS"
F_AI_FROM_HOST="$AI_FROM_HOST"; F_AI_PROVIDERS="$AI_PROVIDERS"; FRESH=0
# from_file FLAGVALUE — in the terminal, values loaded from the file and the value did not come via flag: ask
from_file() { [ "$INTERACTIVE" = 1 ] && [ -n "$CHANGE_NAME" ] && [ -z "$1" ]; }

tui_init
INTERACTIVE=0; [ "$TUI_TTY" = 1 ] && [ "$YES" = 0 ] && INTERACTIVE=1
# Without a terminal and without a request: only check — no unintended installing from pipes and CI
if [ "$INTERACTIVE" = 0 ] && [ "$YES" = 0 ] && [ "$INSTALL" = 0 ] && [ "$START" = 0 ] && [ "$DRY" = 0 ]; then CHECK=1; fi
DO_INSTALL=0; { [ "$YES" = 1 ] || [ "$INSTALL" = 1 ]; } && DO_INSTALL=1
DO_START=0; { [ "$YES" = 1 ] || [ "$START" = 1 ]; } && DO_START=1

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates"
LOG_DIR="$STATE_DIR/setup-logs"
mkdir -p "$LOG_DIR" 2>/dev/null
DOCKER_SG=0   # 1 = Docker for this pass via 'sg docker' (freshly added to the group)

STEPS=("$(msg step_welcome)" "$(msg step_mode)" "$(msg step_checks)" "$(msg step_modules)" "$(msg step_ai)" "$(msg step_ports)" "$(msg step_build)" "$(msg step_done)")

version() { sed -n '/<\/parent>/,$ s/.*<version>\(.*\)<\/version>.*/\1/p' "$REPO_ROOT/ai-automation-backend/pom.xml" | head -n 1 | sed 's/-SNAPSHOT$//'; }
section() {  # step bar before every step (terminal only; otherwise a plain heading)
  if [ "$TUI_TTY" = 1 ]; then printf '\n'; tui_rail "$1" "${STEPS[@]}"; printf '\n'
  else printf '\n== %s ==\n' "${STEPS[$1]}"; fi
}
existing_instances() { local f; for f in "$INSTANCES_DIR"/*.env; do [ -f "$f" ] && basename "$f" .env; done; }

# --- Step 1: Welcome -------------------------------------------------------------------------- -----
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
  # Labels to equal width — the length depends on the language
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

# --- Step 2: Mode (with an existing setup: change or new) -------------------------------------- -----
step_mode() {
  local choice rc names def
  section 1
  names="$(existing_instances | tr '\n' ' ')"
  if [ "$INTERACTIVE" = 1 ] && [ -n "$names" ] && [ -z "$MODE$RUNTIME" ] && [ "$RECONF" = 0 ]; then
    tui_select choice "$(msg rerun_title): $names" 0 \
      "$(msg rerun_change "$(existing_instances | head -n 1)")|$(msg rerun_change_hint)" \
      "$(msg rerun_fresh)|$(msg rerun_fresh_hint)" "$(msg rerun_quit)|"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in
      0) CHANGE_NAME="$(existing_instances | head -n 1)"; load_existing "$CHANGE_NAME"; RECONF=1 ;;
      1) RECONF=1; FRESH=1 ;;   # set up anew: the values of the file do not apply
      2) return 3 ;;
    esac
  fi
  # --reconfigure, also without a terminal: start from the values of the file and change only the given flags
  # (ca902719) — previously fresh defaults applied: an instance on the machine became one in the container without
  # --runtime, the preset fell back to core, the provider to the first logged-in one
  if [ "$RECONF" = 1 ] && [ "$FRESH" = 0 ] && [ -z "$CHANGE_NAME" ] && [ "$MODE" != staged ] && [ -f "$INSTANCES_DIR/$NAME.env" ]; then
    CHANGE_NAME="$NAME"; load_existing "$NAME"
  fi
  if [ "$INTERACTIVE" = 1 ] && { [ -z "$MODE$RUNTIME" ] || from_file "$F_MODE$F_RUNTIME"; }; then
    def=0; [ "$RUNTIME" = host ] && def=1; [ "$MODE" = staged ] && def=2
    tui_select choice "$(msg step_of 2 ${#STEPS[@]}) · $(msg mode_title)" "$def" \
      "$(msg mode_container)|$(msg mode_container_hint) · $(msg recommended)" \
      "$(msg mode_host)|$(msg mode_host_hint)" \
      "$(msg mode_staged)|$(msg mode_staged_hint)"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in 0) MODE=one; RUNTIME=container ;; 1) MODE=one; RUNTIME=host ;; 2) MODE=staged; RUNTIME=container ;; esac
  fi
  kept_load
  MODE="${MODE:-one}"; RUNTIME="${RUNTIME:-container}"; [ "$MODE" = staged ] && RUNTIME=container
  case "$MODE:$RUNTIME" in
    one:container) tui_done "$(msg step_mode)" "$(msg mode_container)$(kept_mark)" ;;
    one:host) tui_done "$(msg step_mode)" "$(msg mode_host)$(kept_mark)" ;;
    staged:*) tui_done "$(msg step_mode)" "$(msg mode_staged)$(kept_mark)" ;;
  esac
  return 0
}

# --- Step 3: Check and install ---------------------------------------------------------------- -----
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
  # Re-check: what is still missing stays missing
  local still
  still="$(deps_plan "$( [ "$RUNTIME" = host ] && echo host || echo container )" | awk 'NF{print $1}' | tr '\n' ' ')"
  if [ -n "$still" ]; then printf '  %s\n' "$(msg checks_still_missing "$still")"; return 1; fi
  return 0
}

# Docker must run and be usable for container paths; freshly in the group ⇒ 'sg docker'
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

# --- Step 4: Modules --------------------------------------------------------------------------- ----
# Lines of a file that is rewritten stay (ca902719) — but never twice: custom_set replaces
# a line with the same key instead of appending a second one.
custom_set() {  # KEY=VALUE
  local key="${1%%=*}" line out=""
  while IFS= read -r line; do
    [ -n "$line" ] && [ "${line%%=*}" != "$key" ] && out="$out$line
"
  done <<EOF
${CUSTOM_LINES:-}
EOF
  CUSTOM_LINES="$out$1
"
}
# custom_drop_modules [all] — discard the module overrides of the file: whoever chooses a preset (anew)
# gets the preset, not silently the earlier selection. AUTOMATION_MODULES_UIEDITABLE is not a
# module and stays — except with all (the own selection asks for it again). Hardening lines always stay.
custom_drop_modules() {
  local line out=""
  while IFS= read -r line; do
    case "$line" in
      "") continue ;;
      AUTOMATION_MODULES_UIEDITABLE=*) [ "${1:-}" = all ] && continue ;;
      AUTOMATION_MODULES_*) continue ;;
    esac
    out="$out$line
"
  done <<EOF
${CUSTOM_LINES:-}
EOF
  CUSTOM_LINES="$out"
}
step_modules() {
  local choice rc items key tier on sel hard label="" def
  section 3
  if [ "$MODE" = staged ]; then tui_done "$(msg step_modules)" "$(msg modules_staged)$(kept_mark)"; return 0; fi
  CUSTOM_LINES="$LOADED_LINES"   # lines of a file that is rewritten stay (ca902719)
  [ -z "$F_PRESET" ] || custom_drop_modules   # --preset: the preset applies, no old overrides
  if [ "$INTERACTIVE" = 1 ] && { [ -z "$PRESET" ] || from_file "$F_PRESET"; }; then
    case "$PRESET" in research) def=1 ;; full) def=2 ;; *) def=0 ;; esac
    tui_select choice "$(msg step_of 4 ${#STEPS[@]}) · $(msg modules_title)" "$def" \
      "$(msg modules_standard)|$(msg modules_standard_hint) · $(msg recommended)" \
      "$(msg modules_research)|$(msg modules_research_hint)" \
      "$(msg modules_full)|$(msg modules_full_hint)" \
      "$(msg modules_custom)|$(msg modules_custom_hint)"
    rc=$?; [ "$rc" = 0 ] || return "$rc"
    case "$choice" in
      0) PRESET=core; custom_drop_modules ;; 1) PRESET=research; custom_drop_modules ;; 2) PRESET=full; custom_drop_modules ;;
      3)
        PRESET=core; label="$(msg modules_custom_done)"; custom_drop_modules all
        items=()
        for key in "${MODULE_CATALOG_FEATURE_KEYS[@]}"; do
          tier="$(module_catalog_get TIER "$key")"; on=off; [ "$tier" = standard ] && on=on
          items[${#items[@]}]="$on:$key:$(module_text_name "$key" "$I18N_LANG"):$tier"
        done
        tui_checklist sel "$(msg modules_pick_title)" "${items[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
        for key in "${MODULE_CATALOG_FEATURE_KEYS[@]}"; do
          tier="$(module_catalog_get TIER "$key")"
          if printf ' %s ' "$sel" | grep " $key " >/dev/null; then [ "$tier" = standard ] || custom_set "$(prop_env "$(module_catalog_get PROPERTY "$key")")=true"
          else [ "$tier" = standard ] && custom_set "$(prop_env "$(module_catalog_get PROPERTY "$key")")=false"; fi
        done
        items=()
        for key in "${MODULE_CATALOG_HARDENING_KEYS[@]}"; do
          on=off; [ "$(module_catalog_get DEFAULT "$key")" = true ] && on=on
          items[${#items[@]}]="$on:$key:$(module_text_name "$key" "$I18N_LANG")"
        done
        tui_checklist hard "$(msg modules_hardening_title)" "${items[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
        for key in "${MODULE_CATALOG_HARDENING_KEYS[@]}"; do
          on=false; printf ' %s ' "$hard" | grep " $key " >/dev/null && on=true
          custom_set "$(prop_env "$(module_catalog_get PROPERTY "$key")")=$on"
        done
        tui_confirm "$(msg modules_ui_editable_q)" n && custom_set AUTOMATION_MODULES_UIEDITABLE=true
        if [ "$RUNTIME" = container ]; then tui_confirm "$(msg socket_q)" n && SOCKET=1; fi ;;
    esac
  fi
  PRESET="${PRESET:-core}"
  for key in $(printf '%s' "$MODULES" | tr ',' ' '); do
    custom_set "$(prop_env "$(module_catalog_get PROPERTY "$key")")=true"
  done
  tui_done "$(msg step_modules)" "${label:-$PRESET}${MODULES:+ + $MODULES}$(kept_mark modules)"
  return 0
}

# --- Step 5: AI providers --------------------------------------------------------------------- -----
step_ai() {
  local id p l lines choice rc opts default=0 i=0 present="" hints="" ask
  section 4
  lines=()
  while read -r id p l; do
    case "$p:$l" in
      present:logged-in) lines[${#lines[@]}]="  ${C_GREEN}$T_OK${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_logged_in)${C_RESET}"; present="$present $id" ;;
      present:*) lines[${#lines[@]}]="  ${C_AMBER}$T_WARN${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_logged_out)${C_RESET}"; present="$present $id"
        hints="$hints  $(msg ai_login_hint "$id" "$(provider_login_line "$id")")
" ;;
      *) lines[${#lines[@]}]="  ${C_GREY}–${C_RESET} $(printf '%-8s' "$id") ${C_GREY}$(msg ai_missing)${C_RESET}" ;;
    esac
  done <<EOF
$(providers_status)
EOF
  if [ -z "$present" ]; then
    hints="  $(msg ai_none)
"
    for id in $(providers_ids); do hints="$hints    $(msg ai_install "$id" "$(provider_shell_cmd "$id" install)" "$(provider_login_line "$id")")
"; done
  fi
  tui_box "$(msg step_of 5 ${#STEPS[@]}) · $(msg ai_title)" "${lines[@]}"
  # The commands below the box, not inside it: it truncates long lines, and half a command does not help
  printf '%s' "$hints"
  offer_pi
  offer_profile
  present="$(providers_status | awk '$2=="present"{print $1}' | tr '\n' ' ')"   # pi may have been added
  ask=0; from_file "$F_PROVIDER" && ask=1
  if [ -z "$PROVIDER" ]; then
    # Default: the first logged-in one, otherwise the first present one, otherwise the first known one
    PROVIDER="$(providers_status | awk '$2=="present" && $3=="logged-in"{print $1; exit}')"
    [ -n "$PROVIDER" ] || PROVIDER="$(printf '%s\n' $present | head -n 1)"
    [ -n "$PROVIDER" ] || PROVIDER="$(providers_ids | head -n 1)"
    ask=1
  fi
  if [ "$ask" = 1 ]; then
    set -- $present
    # The provider from the file stays selectable even if it is missing here — Enter changes nothing
    case " $present " in *" $PROVIDER "*) ;; *) [ -n "$CHANGE_NAME" ] && set -- "$PROVIDER" "$@" ;; esac
    if [ "$INTERACTIVE" = 1 ] && [ "$#" -gt 1 ]; then
      opts=(); i=0
      for id in "$@"; do opts[${#opts[@]}]="$id|$(provider_get "$id" cli)"; [ "$id" = "$PROVIDER" ] && default=$i; i=$((i + 1)); done
      tui_select choice "$(msg ai_choose)" "$default" "${opts[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
      eval "PROVIDER=\"\${$((choice + 1))}\""
    fi
  fi
  tui_done "$(msg step_ai)" "${AI_LABEL:-$PROVIDER}$(kept_mark)"
  ai_container
}

# Container: what comes in from this machine's AI providers (262948a4) — the access mode, the
# selection and with `login` for Claude the token (Claude replaces its login file on every renewal,
# a mounted file would go stale in the container).
ai_container() {
  local choice rc sel id items present all_present def on
  [ "$RUNTIME" = container ] || return 0
  if [ -z "$AI_FROM_HOST" ] || from_file "$F_AI_FROM_HOST"; then
    # Set only after the selection: whoever goes back here gets the question again next time
    if [ "$INTERACTIVE" = 1 ]; then
      case "$AI_FROM_HOST" in programs) def=1 ;; all) def=2 ;; *) def=0 ;; esac
      tui_select choice "$(msg ai_access_q)" "$def" "$(msg ai_access_login)" "$(msg ai_access_programs)" "$(msg ai_access_all)"
      rc=$?; [ "$rc" = 0 ] || return "$rc"
      case "$choice" in 1) AI_FROM_HOST=programs ;; 2) AI_FROM_HOST=all ;; *) AI_FROM_HOST=login ;; esac
    else
      AI_FROM_HOST=login
    fi
  fi
  present="$(providers_status | awk '$2=="present"{print $1}')"
  all_present="$(echo $present)"
  if { [ -z "$AI_PROVIDERS" ] || from_file "$F_AI_PROVIDERS"; } && [ "$KEPT" = 0 ] && [ "$INTERACTIVE" = 1 ] && [ "$(printf '%s\n' $present | grep -c .)" -gt 1 ]; then
    items=()
    for id in $present; do
      on=on; case ",${AI_PROVIDERS:-$id}," in *",$id,"*) ;; *) on=off ;; esac   # preselected as in the file
      items[${#items[@]}]="$on:$id:$id"
    done
    tui_checklist sel "$(msg ai_providers_q)" "${items[@]}"; rc=$?; [ "$rc" = 0 ] || return "$rc"
    # All deselected is almost always a mistake — then all stay
    if [ -n "$sel" ] && [ "$sel" != "$all_present" ]; then AI_PROVIDERS="$(printf '%s' "$sel" | tr ' ' ',')"; else AI_PROVIDERS=""; fi
  fi
  ai_claude_token
  ai_env_only_hints
  tui_done "$(msg ai_access_done)" "$AI_FROM_HOST${AI_PROVIDERS:+ · $AI_PROVIDERS}$(kept_mark)"
  return 0
}

# Logged in only via environment variable (90b4422c): step 5 counts that as "logged in", but by itself
# the variable does not get into the container. In login mode host-mounts.sh passes the login
# variables of the chosen providers through from ai.env — they get there only with explicit
# consent: asked in the terminal (default no), otherwise only with --store-ai-env, not by itself even with --yes.
# With all, whole directories come, no variables (only the hint); with programs
# each instance logs in itself. Claude runs in login mode via the token (ai_claude_token).
# Names of the variables are printed, never their values.
ai_env_only_hints() {
  local id vars v names covered
  [ "$AI_FROM_HOST" = programs ] && return 0
  for id in $(printf '%s' "${AI_PROVIDERS:-$(providers_ids | tr '\n' ',')}" | tr ',' ' '); do
    { [ "$id" = claude ] && [ "$AI_FROM_HOST" = login ]; } && continue
    provider_present "$id" || continue
    provider_login_file "$id" && continue
    vars="$(provider_login_env_names "$id")"
    [ -n "$vars" ] || continue
    names="$(printf '%s' "$vars" | sed 's/ /, /g')"
    if [ "$AI_FROM_HOST" != login ]; then
      printf '  %s\n' "$(msg ai_env_only "$id" "$names" "$(provider_login_line "$id")")"; continue
    fi
    # Already in ai.env (also just taken over for another provider, e.g. OPENAI_API_KEY): done
    covered=0
    for v in $(provider_get "$id" login_env); do [ -n "$(ai_env_value "$AI_ENV" "$v")" ] && covered=1; done
    [ "$covered" = 1 ] && continue
    if [ "$STORE_AI_ENV" = 1 ] || { [ "$INTERACTIVE" = 1 ] && tui_confirm "$(msg ai_env_store_q "$id" "$names" "$AI_ENV")" n; }; then
      for v in $vars; do
        if ai_env_put "$v" "${!v}"; then
          printf '  %s\n' "$(msg ai_env_stored "$v" "$AI_ENV")"
          # Shared with Claude (ANTHROPIC_API_KEY): claude then takes the key before its token
          case ",${AI_PROVIDERS:-claude}, $(provider_get claude login_env) " in
            *,claude,*" $v "*) [ "$id" = claude ] || printf '  %s\n' "$(msg ai_env_shared_claude "$v")" ;;
          esac
        else printf '  %s\n' "$(msg ai_env_not_stored "$v" "$AI_ENV")"; fi
      done
      continue
    fi
    printf '  %s\n' "$(msg ai_env_only_login "$id" "$names" "$AI_ENV" "$(provider_login_line "$id")")"
  done
  return 0
}

# ai_env_put VAR VALUE — VAR=VALUE into ai.env: file 600, directory 700, other entries stay,
# an old line of the same variable goes. The value is printed nowhere. One with a line break
# fits no env file and is rejected (return 1).
ai_env_put() {
  local dir
  case "$2" in ''|*"
"*) return 1 ;; esac
  dir="$(dirname "$AI_ENV")"
  ( umask 077
    mkdir -p "$dir" && chmod 700 "$dir" || exit 1
    { grep -v "^$1=" "$AI_ENV" 2>/dev/null; printf '%s=%s\n' "$1" "$2"; } > "$AI_ENV.tmp" \
      && chmod 600 "$AI_ENV.tmp" && mv -f "$AI_ENV.tmp" "$AI_ENV" ) || { rm -f "$AI_ENV.tmp"; return 1; }
}

ai_claude_token() {
  local token="" have
  [ "$AI_FROM_HOST" = login ] || return 0
  case ",${AI_PROVIDERS:-claude}," in *,claude,*) ;; *) return 0 ;; esac
  provider_present claude || return 0
  have="$(claude_token_from_file "$AI_ENV")"
  claude_token_plausible "$have" && return 0
  # A placeholder from a guide does not count as a login (9bef7e8c)
  [ -n "$have" ] && printf '  %s\n' "$(msg ai_token_implausible "$AI_ENV")"
  if [ "$INTERACTIVE" = 1 ] && [ "$DRY" = 0 ] && tui_confirm "$(msg ai_token_q)" y; then
    claude setup-token || true   # browser login; only displays the token
    tui_secret token "$(msg ai_token_paste)"
    token="$(printf '%s' "$token" | tr -d '[:space:]')"
    if [ -n "$token" ] && ! claude_token_plausible "$token"; then
      printf '  %s\n' "$(msg ai_token_rejected)"; token=""
    fi
  fi
  if [ -z "$token" ]; then printf '  %s\n' "$(msg ai_token_hint "$AI_ENV")"; return 0; fi
  ai_env_put CLAUDE_CODE_OAUTH_TOKEN "$token" && printf '  %s\n' "$(msg ai_token_saved "$AI_ENV")"
}

# Install pi as well (0a078449): only on request — asked in the terminal, otherwise with --with-pi; default
# no, even with --yes: no unasked global npm package. The command comes from the provider catalog,
# with sudo only where npm -g cannot write by itself (Node from apt/dnf). With a downloaded Node
# pi ends up under ~/.local/share/mysuricates/node/bin — instance.sh finds it via env.sh.
pi_install_cmd() {
  local cmd
  cmd="$(provider_get pi install)"
  case "$cmd" in "npm install -g "*) ;; *) return 1 ;; esac
  command -v npm >/dev/null 2>&1 || return 1
  if deps_npm_needs_sudo; then printf 'sudo %s' "$cmd"; else printf '%s' "$cmd"; fi
}

offer_pi() {
  local cmd
  provider_present pi && return 0
  if ! cmd="$(pi_install_cmd)"; then
    [ "$WITH_PI" = 0 ] || printf '  %s\n' "$(msg ai_pi_no_npm)"
    return 0
  fi
  if [ "$WITH_PI" = 0 ]; then
    [ "$INTERACTIVE" = 1 ] || return 0
    tui_confirm "$(msg ai_pi_q "$cmd")" n || return 0
  fi
  if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "$cmd")"; return 0; fi
  if ! tui_run "$(msg ai_pi_run)" "$LOG_DIR/install-pi.log" bash -c "$cmd"; then
    printf '  %s\n' "$(msg ai_pi_failed "$cmd")"; return 0   # continue without pi
  fi
  provider_present pi || printf '  %s\n' "$(msg ai_pi_not_found "$(npm prefix -g 2>/dev/null)/bin")"
  return 0
}

# A downloaded Node — and thereby npm, codex, pi — exists in the user's shell only with env.sh
# in the profile (2605ed1f). In the guided flow add on request (default no), with --yes or in a
# dry run only show the line.
offer_profile() {
  local profile
  deps_env_active || return 0
  profile="$(deps_profile_file)"
  deps_profile_has_env "$profile" && return 0
  if [ "$INTERACTIVE" = 1 ] && [ "$DRY" = 0 ] && tui_confirm "$(msg profile_q "$(deps_tilde "$profile")")" n; then
    if deps_profile_add "$profile"; then
      printf '  %s\n' "$(msg profile_added "$(deps_tilde "$profile")" "$(deps_profile_hint)")"; return 0
    fi
  fi
  printf '  %s\n' "$(msg profile_hint "$(deps_tilde "$profile")" "$(deps_profile_hint)")"
}

# A provider's login command, plus what to do afterwards — in the installer's language: the
# catalog names only a key for it (login_note, eab43a8b), the text is in lib/i18n
provider_login_line() {  # ID
  local note
  note="$(provider_get "$1" login_note)"
  printf '%s' "$(provider_shell_cmd "$1" login)"
  [ -z "$note" ] || printf ', %s' "$(msg "login_note_$note")"
}

# A provider command as it runs in the user's shell (2605ed1f): if the program —
# or npm, with which it first comes — comes from the download and does not load the profile env.sh, env.sh precedes it.
provider_shell_cmd() {  # ID install|login
  local cmd prog where
  cmd="$(provider_get "$1" "$2")"
  { deps_env_active && ! deps_profile_has_env; } || { printf '%s' "$cmd"; return 0; }
  prog="$(provider_get "$1" cli)"
  if [ "$2" = install ] || ! command -v "$prog" >/dev/null 2>&1; then
    case "$(provider_get "$1" install)" in "npm "*) prog=npm ;; *) printf '%s' "$cmd"; return 0 ;; esac
  fi
  where="$(command -v "$prog" 2>/dev/null)"
  case "$where" in "$DEPS_DATA"/*) printf '%s && %s' "$(deps_profile_hint)" "$cmd" ;; *) printf '%s' "$cmd" ;; esac
}

# --- Step 6: Ports and folders ---------------------------------------------------------------- -----
step_ports() {
  local p
  section 5
  if [ "$MODE" = staged ]; then
    # Ports of kept files are fixed — the others yield to them (fd4eb89f). With --port N
    # dev starts at N, test and prod at N+1 and N+2 — for a machine on which 9090, 8090 and
    # 9091 already belong to others (e.g. the installer probe next to running instances)
    local bd=9090 bt=8090 bp=9091
    [ -z "$PORT" ] || { bd="$PORT"; bt=$((PORT + 1)); bp=$((PORT + 2)); }
    p="$(for n in dev test prod; do is_kept "$n" && kept_value "$n" MYSURICATES_PORT; done | tr '\n' ' ')"
    P_DEV="$(kept_port dev "$bd" "$p")"; P_TEST="$(kept_port test "$bt" "$p $P_DEV")"; P_PROD="$(kept_port prod "$bp" "$p $P_DEV $P_TEST")"
    tui_done "$(msg step_ports)" "Dev $P_DEV · Test $P_TEST · Prod $P_PROD$(kept_mark)"
    return 0
  fi
  if [ -z "$PORT" ]; then
    PORT="$(free_port 9090 "" "$NAME")"
    [ "$PORT" = 9090 ] || printf '  %s\n' "$(msg ports_busy 9090 "$PORT")"
    [ "$INTERACTIVE" = 1 ] && tui_input PORT "$(msg ports_q "$NAME")" "$PORT"
  elif [ "$PORT_LOADED" = 1 ]; then
    # Port from the file that is rewritten: stays — unless another one occupies it meanwhile
    # (the running own instance counts as free, 0e5b82b7)
    p="$(free_port "$PORT" "" "$NAME")"
    [ "$p" = "$PORT" ] || { printf '  %s\n' "$(msg ports_busy "$PORT" "$p")"; PORT="$p"; }
    [ "$INTERACTIVE" = 1 ] && tui_input PORT "$(msg ports_q "$NAME")" "$PORT"   # value of the file as default
  fi
  if [ "$RUNTIME" = container ] && { [ -z "$PROJECTS" ] || from_file "$F_PROJECTS"; }; then
    PROJECTS="${PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}"
    [ "$INTERACTIVE" = 1 ] && tui_input PROJECTS "$(msg projects_q)" "$PROJECTS"
  fi
  tui_done "$(msg step_ports)" "$PORT${PROJECTS:+ · $PROJECTS}$(kept_mark)"
  return 0
}

# --- Step 7: Write instance files, build, start ----------------------------------------------- -----
write_instance() {  # NAME RUNTIME PORT PRESET ENVIRONMENT SEALING REQUIRE_SEALED
  local f="$INSTANCES_DIR/$1.env"
  mkdir -p "$INSTANCES_DIR"
  [ -f "$f" ] && instance_remember_addr "$1" "$f"
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
    if [ "$2" = container ]; then
      printf 'MYSURICATES_PROJECTS=%s\nMYSURICATES_DOCKER_SOCKET=%s\n' "${PROJECTS:-$(cd "$REPO_ROOT/.." && pwd)}" "${SOCKET:-0}"
      # What from the machine's AI providers comes into the container (262948a4)
      printf 'MYSURICATES_AI_FROM_HOST=%s\n' "${AI_FROM_HOST:-login}"
      [ -n "$AI_PROVIDERS" ] && printf 'MYSURICATES_AI_PROVIDERS=%s\n' "$AI_PROVIDERS"
    fi
    printf 'MYSURICATES_HARDENING_SEALING=%s\nMYSURICATES_HARDENING_REQUIRE_SEALED=%s\n' "${6:-false}" "${7:-false}"
    printf 'AUTOMATION_AI_DEFAULT_PROVIDER=%s\n' "$(printf '%s' "$PROVIDER" | tr 'a-z' 'A-Z')"
    [ "$1" = "$NAME" ] && [ -n "${CUSTOM_LINES:-}" ] && printf '%s' "$CUSTOM_LINES"
  } > "$f"
}

# On the machine `instance.sh up` builds only if the build does not match the clone (instance.sh built,
# 1c016162). The step therefore says beforehand whether it builds — instead of "✓ bauen 0:00" after a
# git pull. A running instance is never stopped by `up` itself; with an old state or new settings
# the installer restarts it, because that is what it was called for. In the container docker compose builds and
# replaces a running container as soon as image or settings have changed — not
# visible beforehand. Both abort running workflow runs; restart_ok asks first (29820f58).
start_instance() {  # NAME
  local inst="bash '$SCRIPT_DIR/instance.sh'" cmd title st="" built="" head="" running=0
  cmd="$inst up --name $1"; title="$(msg build_run "$1")"
  if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "scripts/instance.sh up --name $1")"; return 0; fi
  case "$(bash "$SCRIPT_DIR/instance.sh" status --name "$1" 2>/dev/null)" in running*) running=1 ;; esac
  if [ "$RUNTIME" = host ]; then
    read -r st built head <<EOF
$(bash "$SCRIPT_DIR/instance.sh" built --name "$1" 2>/dev/null)
EOF
    if [ "$running" = 1 ] && [ "$st" != outdated ] && [ "$RECONF" = 0 ]; then
      tui_done "$(msg step_build)" "$(msg build_running_kept "$1" "$(short_sha "$built")")"; return 0
    fi
  fi
  if [ "$running" = 1 ] && ! restart_ok "$1"; then
    if [ "$st" = outdated ]; then tui_done "$(msg step_build)" "$(msg runs_kept_outdated "$1" "$(short_sha "$built")" "$(short_sha "$head")")"
    elif [ "$RECONF" = 1 ]; then tui_done "$(msg step_build)" "$(msg runs_kept_settings "$1")"
    else tui_done "$(msg step_build)" "$(msg runs_kept "$1")"; fi
    return 0
  fi
  if [ "$RUNTIME" = host ]; then
    if [ "$st" = outdated ]; then
      if [ "$running" = 1 ]; then printf '  %s\n' "$(msg build_outdated_running "$1" "$(short_sha "$built")" "$(short_sha "$head")")"
      else printf '  %s\n' "$(msg build_outdated "$1" "$(short_sha "$built")" "$(short_sha "$head")")"; fi
    fi
    [ "$running" = 1 ] && cmd="$inst down --name $1 >/dev/null 2>&1; $cmd"
    case "$st" in current|unknown) title="$(msg build_start "$1") — $(msg build_kept "$(short_sha "$built")")" ;; esac
  fi
  [ "$DOCKER_SG" = 1 ] && cmd="sg docker -c \"$cmd\""
  tui_run "$title" "$LOG_DIR/start-$1.log" bash -c "$cmd" || { printf '  %s\n' "$(msg build_failed "$1" "$LOG_DIR/start-$1.log")"; return 1; }
}

# Before restarting a running instance, its running workflow runs (29820f58) — read-only, key
# from `instance.sh key`, port and address from the instance file, as it was when the instance started.
# If any are running: ask in the terminal (default no), otherwise — even with --yes — do not stop, only name the
# command for later. If the instance does not answer or the key is missing: as before, with a hint.
KEPT_RUNNING=""   # not restarted: " NAME … "
restart_ok() {  # NAME → 0 = restart
  local a key n
  a="$(instance_prev_addr "$1")"; [ -n "$a" ] || a="$(instance_file_addr "$INSTANCES_DIR/$1.env")"
  key="$(bash "$SCRIPT_DIR/instance.sh" key --name "$1" 2>/dev/null)"
  n="$(instance_running_runs "$(instance_api_base "${a#*,}" "${a%%,*}")" "$key")"
  if [ -z "$n" ]; then printf '  %s\n' "$(msg runs_unchecked "$1")"; return 0; fi
  [ "$n" -gt 0 ] || return 0
  if [ "$INTERACTIVE" = 1 ]; then tui_confirm "$(msg runs_q "$1" "$n")" n && return 0
  else printf '  %s\n' "$(msg runs_active "$1" "$n")"; fi
  printf '  %s\n' "$(msg runs_later "scripts/instance.sh down --name $1 && scripts/instance.sh up --name $1")"
  KEPT_RUNNING="$KEPT_RUNNING $1 "
  return 1
}
short_sha() { if [ "${1:--}" = - ]; then msg build_state_unknown; else printf '%s' "${1:0:8}"; fi; }

# Staged operation (84a78bb7): dev knows test and prod — all three in the shared Docker network, reachable there
# under their instance name (not via host.docker.internal: the ports are only on
# 127.0.0.1); dev reads the keys of the targets from files that `instance.sh sync-keys` fetches. Missing
# lines also go into kept files — what is already there stays.
ensure_line() {  # NAME KEY VALUE
  local f="$INSTANCES_DIR/$1.env"
  grep "^$2=" "$f" >/dev/null 2>&1 || printf '%s=%s\n' "$2" "$3" >> "$f"
}
staged_sync_lines() {
  local n f="$INSTANCES_DIR/dev.env"
  for n in dev test prod; do ensure_line "$n" MYSURICATES_NETWORK staged; done
  grep '^MYSURICATES_SYNC_TARGETS=' "$f" >/dev/null 2>&1 \
    || printf '# Definition-Sync nach Test und Prod (Einstellungen → Umgebungen); Keys: scripts/instance.sh sync-keys --name dev\n' >> "$f"
  ensure_line dev MYSURICATES_SYNC_TARGETS test,prod
  ensure_line dev AUTOMATION_SYNC_TEST_URL http://test:9090
  ensure_line dev AUTOMATION_SYNC_TEST_KEY_FILE /app/sync-keys/test.key
  ensure_line dev AUTOMATION_SYNC_PROD_URL http://prod:9090
  ensure_line dev AUTOMATION_SYNC_PROD_KEY_FILE /app/sync-keys/prod.key
}
# Only sealed definitions go to prod (84a78bb7). A dev file from before the sync does not
# seal — if it stays, the installer says how; if --reconfigure rewrites it, that dev now seals.
sealing_hint() {  # BEFORE (value of MYSURICATES_HARDENING_SEALING before writing)
  local now
  now="$(kept_value dev MYSURICATES_HARDENING_SEALING)"
  if [ "$now" != true ]; then
    printf '  %s%s%s\n' "$C_AMBER" "$(msg sealing_dev_off "deploy/instances/dev.env" "scripts/setup.sh --mode staged --reconfigure")" "$C_RESET"
  elif [ "${1:-}" = false ]; then
    printf '  %s\n' "$(msg sealing_dev_now)"
  fi
}
# After the start: fetch the keys of test and prod for dev. If a target is not running, the
# installer names the command for later.
fetch_sync_keys() {
  local cmd="scripts/instance.sh sync-keys --name dev" run="bash '$SCRIPT_DIR/instance.sh' sync-keys --name dev"
  if [ "$DRY" = 1 ]; then printf '  %s\n' "$(msg dry_run "$cmd")"; return 0; fi
  [ "$DOCKER_SG" = 1 ] && run="sg docker -c \"$run\""
  mkdir -p "$LOG_DIR"
  if bash -c "$run" > "$LOG_DIR/sync-keys.log" 2>&1; then printf '  %s\n' "$(msg sync_keys_ok)"
  else printf '  %s\n' "$(msg sync_keys_later "$cmd")"; fi
}

step_build() {
  section 6
  if [ "$MODE" = staged ]; then
    # Dev seals (without obligation for unattended runs): only sealed definitions go to prod
    local sealing_before
    sealing_before="$(kept_value dev MYSURICATES_HARDENING_SEALING)"
    write_instance dev container "$P_DEV" full Dev true false
    write_instance test container "$P_TEST" core Test true true
    write_instance prod container "$P_PROD" core Prod true true
    staged_sync_lines
    sealing_hint "$sealing_before"
    INSTANCES="dev test prod"
  else
    write_instance "$NAME" "$RUNTIME" "$PORT" "$PRESET" "$LOADED_ENVIRONMENT" "$LOADED_SEALING" "$LOADED_REQUIRE_SEALED"
    INSTANCES="$NAME"
  fi
  STARTED=0
  if [ "$DO_START" = 0 ] && [ "$INTERACTIVE" = 1 ]; then tui_confirm "$(msg build_q)" y && DO_START=1; fi
  if [ "$DO_START" = 1 ]; then
    for n in $INSTANCES; do start_instance "$n" || return 1; done
    STARTED=1
    [ "$MODE" = staged ] && fetch_sync_keys
  elif [ "$MODE" = staged ]; then
    printf '  %s\n' "$(msg sync_keys_later "scripts/instance.sh sync-keys --name dev")"
  fi
  return 0
}

# --- Step 8: Done ----------------------------------------------------------------------------- -----
step_done() {
  local n port bind key lines l_key l_manage l_change l_update w l first addr
  section 7
  lines=()
  for n in $INSTANCES; do
    port="$(kept_value "$n" MYSURICATES_PORT)"
    # From the file, not from --bind: a kept instance file may bind differently (b8aa3866)
    bind="$(kept_value "$n" MYSURICATES_BIND)"
    # Not restarted (29820f58): it still listens where it listened before the file was written
    case "$KEPT_RUNNING" in *" $n "*) l="$(instance_prev_addr "$n")"; [ -z "$l" ] || { port="${l%%,*}"; bind="${l#*,}"; } ;; esac
    if [ "$STARTED" = 1 ] && [ "$DRY" = 0 ]; then
      first=1
      while IFS= read -r addr; do
        if [ "$first" = 1 ]; then
          lines[${#lines[@]}]="${C_BOLD}$(printf '%-5s' "$n")${C_RESET} $addr"; first=0
        else
          lines[${#lines[@]}]="      $addr"
        fi
      done <<ADDR
$(netaddr_lines "${bind:-127.0.0.1}" "$port")
ADDR
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
  # Labels to equal width, the length depends on the language, never pad in the translation
  l_key="$(msg done_key_cmd)"; l_manage="$(msg done_manage)"; l_change="$(msg done_change)"; l_update="$(msg done_update)"; w=0
  for l in "$l_key" "$l_manage" "$l_change" "$l_update"; do [ "${#l}" -gt "$w" ] && w=${#l}; done
  lines[${#lines[@]}]="${l_key}$(_tui_repeat ' ' $((w - ${#l_key} + 2)))${C_BOLD}scripts/instance.sh key --name $n${C_RESET}"
  lines[${#lines[@]}]="${l_manage}$(_tui_repeat ' ' $((w - ${#l_manage} + 2)))${C_BOLD}scripts/instance.sh up|down|status --name $n${C_RESET}"
  lines[${#lines[@]}]="${l_change}$(_tui_repeat ' ' $((w - ${#l_change} + 2)))${C_BOLD}scripts/setup.sh${C_RESET} · deploy/instances/$n.env"
  # After git pull: down and up — up rebuilds if the state has changed (1c016162)
  lines[${#lines[@]}]="${l_update}$(_tui_repeat ' ' $((w - ${#l_update} + 2)))${C_BOLD}git pull && scripts/instance.sh down --name $n${C_RESET}"
  lines[${#lines[@]}]="$(_tui_repeat ' ' $((w + 2)))${C_BOLD}scripts/instance.sh up --name $n${C_RESET}"
  tui_box "$(msg step_of 8 ${#STEPS[@]}) · $(msg step_done)" "${lines[@]}"
  # Honestly say what happens after a restart of the machine (f9e2a033): a container comes back with
  # the Docker service (restart: unless-stopped), an instance on the machine does not — an
  # autostart for that is a separate step (f52c1fff).
  [ "$RUNTIME" = host ] && printf '  %s\n' "$(msg done_no_autostart "$n" "scripts/instance.sh up --name $n")"
  # Under the box: it would truncate the command, and with env.sh in front it gets long (2605ed1f)
  logged="$(providers_status | awk -v i="$PROVIDER" '$1==i{print $3}')"
  [ "$logged" = logged-in ] || printf '  %s%s%s\n' "$C_AMBER" "$(msg ai_login_hint "$PROVIDER" "$(provider_login_line "$PROVIDER")")" "$C_RESET"
  return 0
}

# --- Source-code path for contributors --------------------------------------------------------- ----
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
  tui_run "backend (runtime JAR)" "$LOG_DIR/build.log" bash "$SCRIPT_DIR/start-mysuricates-dev.sh" build || exit 1
  if [ -e "$cfg" ]; then
    if [ "$FORCE" = 1 ]; then module_preset_update "$cfg" "$PRESET"; fi
  else
    module_preset_write "$cfg" "${PRESET:-core}" "$I18N_LANG"
  fi
  offer_profile   # otherwise a later call from the own shell does not find the downloaded npm
  [ "$DO_START" = 1 ] || { printf '  ./scripts/start-mysuricates-dev.sh\n'; exit 0; }
  exec bash "$SCRIPT_DIR/start-mysuricates-dev.sh" $([ "$SHOW_KEY" = 1 ] && echo --show-key)
}

# --- Flow ------------------------------------------------------------------------------------- -----
PRESET_FLAG="$PRESET"
[ "$FROM_SOURCE" = 1 ] && from_source
SOCKET="$SOCKET_FLAG"; CUSTOM_LINES=""
step_welcome
FLOW=(step_mode step_checks step_modules step_ai step_ports step_build step_done)
i=0
while [ "$i" -lt "${#FLOW[@]}" ]; do
  "${FLOW[$i]}"; rc=$?
  case "$rc" in
    0) i=$((i + 1)) ;;
    2) [ "$i" -gt 0 ] && i=$((i - 1)) ;;      # b = back
    3) printf '\n  %s\n' "$(msg quit)"; exit 1 ;;
    4) exit 1 ;;                              # --check: something is missing
    5) exit 0 ;;                              # --check: everything present
    *) exit 1 ;;
  esac
done
exit 0
