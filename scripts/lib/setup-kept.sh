#!/usr/bin/env bash
# Part of scripts/setup.sh: an instance file deploy/instances/<name>.env that stays as it is
# (fd4eb89f). Without --reconfigure write_instance does not rewrite an existing file. The steps used to
# show defaults nevertheless that never made it into the file — "Port 9090 busy, taking 9091",
# because the own running instance occupies 9090, or a new default provider. Now the
# values of the file apply, even against flags, and the steps say where they come from; flags that have no effect
# are named in one line. Plus load_existing: the values of a file as starting point when it
# is rewritten ("Einstellungen aendern", --reconfigure). Reads and sets the variables of the installer — not usable on its own.
# bash 3.2 (macOS).

KEPT=0          # 1 = every file of this run stays (the one instance, or dev, test and prod)
KEPT_NAMES=""   # the instances whose file stays: "main" or "dev, prod"
KEPT_IGNORED="" # flags that have no effect against the file
AI_LABEL=""     # AI line if the kept files name different providers

# kept_value NAME KEY — how instance.sh reads: the last line wins; empty if not there
kept_value() { grep "^$2=" "$INSTANCES_DIR/$1.env" 2>/dev/null | tail -n 1 | cut -d= -f2-; }
kept_or() { local v; v="$(kept_value "$1" "$2")"; printf '%s' "${v:-$3}"; }   # NAME KEY DEFAULT
# kept_provider NAME — default provider of the file; without a line the backend's default (claude)
kept_provider() { kept_or "$1" AUTOMATION_AI_DEFAULT_PROVIDER CLAUDE | tr 'A-Z' 'a-z'; }
# is_kept NAME — does the file stay? (like write_instance: present and no --reconfigure)
is_kept() { [ "$RECONF" = 0 ] && [ -f "$INSTANCES_DIR/$1.env" ]; }

# kept_load — after the mode choice: if the file stays, the run adopts its values
kept_load() {
  local n rt port preset prov bind proj="" afh="" aprov="" sock=0 provs=""
  KEPT=0; KEPT_NAMES=""; KEPT_IGNORED=""; AI_LABEL=""
  if [ "$MODE" = staged ]; then
    for n in dev test prod; do is_kept "$n" && KEPT_NAMES="$KEPT_NAMES${KEPT_NAMES:+, }$n"; done
    [ "$KEPT_NAMES" = "dev, test, prod" ] || return 0
    # All three stay: the questions about provider and AI access are dropped, what is in the file is shown
    KEPT=1
    for n in dev test prod; do provs="$provs${provs:+ · }$n $(kept_provider "$n")"; done
    _kept_flag --port "$PORT" "$(kept_value dev MYSURICATES_PORT)"
    _kept_flag --provider "$PROVIDER" "$(kept_provider dev)"
    _kept_flag --ai-from-host "$AI_FROM_HOST" "$(kept_or dev MYSURICATES_AI_FROM_HOST login)"
    _kept_flag --ai-providers "$AI_PROVIDERS" "$(kept_value dev MYSURICATES_AI_PROVIDERS)"
    PROVIDER="$(kept_provider dev)"
    [ "$provs" = "dev $PROVIDER · test $PROVIDER · prod $PROVIDER" ] || AI_LABEL="$provs"
    AI_FROM_HOST="$(kept_or dev MYSURICATES_AI_FROM_HOST login)"
    AI_PROVIDERS="$(kept_value dev MYSURICATES_AI_PROVIDERS)"
    [ -z "$KEPT_IGNORED" ] || printf '  %s\n' "$(msg kept_ignored "deploy/instances/{dev,test,prod}.env" "$KEPT_IGNORED")"
    return 0
  fi
  is_kept "$NAME" || return 0
  KEPT=1; KEPT_NAMES="$NAME"
  rt="$(kept_or "$NAME" MYSURICATES_RUNTIME container)"
  port="$(kept_value "$NAME" MYSURICATES_PORT)"
  preset="$(kept_or "$NAME" MYSURICATES_PRESET core)"
  prov="$(kept_provider "$NAME")"
  bind="$(kept_or "$NAME" MYSURICATES_BIND 127.0.0.1)"
  if [ "$rt" = container ]; then   # defaults as in instance.sh
    proj="$(kept_or "$NAME" MYSURICATES_PROJECTS "$(cd "$REPO_ROOT/.." && pwd)")"
    afh="$(kept_or "$NAME" MYSURICATES_AI_FROM_HOST login)"
    aprov="$(kept_value "$NAME" MYSURICATES_AI_PROVIDERS)"
    sock="$(kept_or "$NAME" MYSURICATES_DOCKER_SOCKET 0)"
  fi
  _kept_flag --runtime "$RUNTIME" "$rt"
  _kept_flag --port "$PORT" "$port"
  [ "$FORCE" = 1 ] || _kept_flag --preset "$PRESET" "$preset"   # --force writes the preset
  _kept_flag --provider "$PROVIDER" "$prov"
  [ "$BIND" = 127.0.0.1 ] || _kept_flag --bind "$BIND" "$bind"   # 127.0.0.1 is the default of --bind
  _kept_flag --projects "$PROJECTS" "$proj"
  _kept_flag --ai-from-host "$AI_FROM_HOST" "$afh"
  _kept_flag --ai-providers "$AI_PROVIDERS" "$aprov"
  [ -z "$MODULES" ] || KEPT_IGNORED="$KEPT_IGNORED${KEPT_IGNORED:+, }--modules $MODULES"
  [ "$SOCKET_FLAG" = 0 ] || [ "$sock" = 1 ] || KEPT_IGNORED="$KEPT_IGNORED${KEPT_IGNORED:+, }--docker-socket"
  MODE=one; RUNTIME="$rt"; PORT="$port"; PROVIDER="$prov"; BIND="$bind"
  PROJECTS="$proj"; AI_FROM_HOST="$afh"; AI_PROVIDERS="$aprov"; SOCKET="$sock"; MODULES=""
  [ "$FORCE" = 1 ] || PRESET="$preset"
  [ -z "$KEPT_IGNORED" ] || printf '  %s\n' "$(msg kept_ignored "deploy/instances/$NAME.env" "$KEPT_IGNORED")"
  return 0
}
_kept_flag() {  # FLAG GIVEN FROM_THE_FILE — given as a flag, but different from the file?
  [ -n "$2" ] && [ "$2" != "$3" ] && KEPT_IGNORED="$KEPT_IGNORED${KEPT_IGNORED:+, }$1 $2"
  return 0
}

# kept_mark [modules] — marker after the line of a step: where the value comes from
kept_mark() {
  [ -n "$KEPT_NAMES" ] || return 0
  if [ "$MODE" = staged ]; then printf ' · %s' "$(msg kept_files "$KEPT_NAMES")"
  elif [ "${1:-}" = modules ] && [ "$FORCE" = 1 ]; then printf ' · %s' "$(msg kept_into "deploy/instances/$NAME.env")"
  else printf ' · %s' "$(msg kept_from "deploy/instances/$NAME.env")"; fi
}

# kept_port NAME BASE [TAKEN…] — port in mode dev · test · prod: from the file that stays, otherwise
# the first free one from BASE that is not already taken; it counts the one of the running instance NAME as free
kept_port() { if is_kept "$1"; then kept_value "$1" MYSURICATES_PORT; else free_port "$2" "${3:-}" "$1"; fi; }

# load_existing NAME — the values of an existing instance as starting point for "Einstellungen aendern"
# and for --reconfigure, even without a terminal (ca902719): flags come first, everything else stays as it is in
# the file. Like kept_value: one value per key, the last line wins (0e5b82b7).
# Also takes BIND, AI_FROM_HOST, AI_PROVIDERS, the Docker socket (fbb1c61e), environment and seal switches
# along, plus every AUTOMATION_* line (hardening, modules, added by hand) — otherwise a reconfigure
# without the matching flags silently writes the defaults (127.0.0.1, no socket, no hardening) into the
# file. Sets LOADED_LINES (for step_modules) and PORT_LOADED=1 if the port comes from the file.
load_existing() {
  local p0="${PORT:-}" line
  NAME="$1"; MODE=one
  RUNTIME="${RUNTIME:-$(kept_or "$1" MYSURICATES_RUNTIME container)}"
  PORT="${PORT:-$(kept_value "$1" MYSURICATES_PORT)}"
  PORT_LOADED=0; [ -n "$p0" ] || [ -z "$PORT" ] || PORT_LOADED=1
  PRESET="${PRESET:-$(kept_value "$1" MYSURICATES_PRESET)}"
  PROVIDER="${PROVIDER:-$(kept_value "$1" AUTOMATION_AI_DEFAULT_PROVIDER | tr 'A-Z' 'a-z')}"
  PROJECTS="${PROJECTS:-$(kept_value "$1" MYSURICATES_PROJECTS)}"
  # BIND already carries "127.0.0.1" as default (no empty string) — as in kept_load we recognize a
  # given --bind by it differing from that.
  [ "${BIND:-127.0.0.1}" != 127.0.0.1 ] || BIND="$(kept_or "$1" MYSURICATES_BIND 127.0.0.1)"
  AI_FROM_HOST="${AI_FROM_HOST:-$(kept_value "$1" MYSURICATES_AI_FROM_HOST)}"
  AI_PROVIDERS="${AI_PROVIDERS:-$(kept_value "$1" MYSURICATES_AI_PROVIDERS)}"
  # SOCKET already carries SOCKET_FLAG at this point (0 without --docker-socket) — as in kept_load
  # only a given --docker-socket counts as a flag, not an "off".
  [ "${SOCKET_FLAG:-0}" = 1 ] || SOCKET="$(kept_or "$1" MYSURICATES_DOCKER_SOCKET 0)"
  LOADED_ENVIRONMENT="$(kept_value "$1" MYSURICATES_ENVIRONMENT)"
  LOADED_SEALING="$(kept_or "$1" MYSURICATES_HARDENING_SEALING false)"
  LOADED_REQUIRE_SEALED="$(kept_or "$1" MYSURICATES_HARDENING_REQUIRE_SEALED false)"
  # AUTOMATION_* lines in their order (the last one keeps winning); the default provider is written by
  # write_instance itself
  LOADED_LINES=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in AUTOMATION_AI_DEFAULT_PROVIDER=*) ;; AUTOMATION_*=*) LOADED_LINES="$LOADED_LINES$line
" ;; esac
  done < "$INSTANCES_DIR/$1.env"
  CUSTOM_LINES="${CUSTOM_LINES:-}$LOADED_LINES"
}
