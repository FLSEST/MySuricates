#!/usr/bin/env bash
# Teil von scripts/setup.sh: eine Instanz-Datei deploy/instances/<name>.env, die bleibt, wie sie ist
# (fd4eb89f). Ohne --reconfigure schreibt write_instance eine vorhandene Datei nicht neu. Frueher
# zeigten die Schritte trotzdem Vorgaben, die nie in die Datei kamen — „Port 9090 belegt, nehme 9091“,
# weil die eigene laufende Instanz 9090 belegt, oder einen neuen Standard-Anbieter. Jetzt gelten die
# Werte der Datei, auch gegen Flags, und die Schritte sagen, woher sie kommen; nicht wirkende Flags
# nennt eine Zeile. Dazu load_existing: die Werte einer Datei als Ausgangspunkt, wenn sie neu geschrieben
# wird („Einstellungen aendern“, --reconfigure). Liest und setzt die Variablen des Installers — fuer sich allein nicht
# nutzbar. bash 3.2 (macOS).

KEPT=0          # 1 = jede Datei dieses Laufs bleibt (die eine Instanz, oder Dev, Test und Prod)
KEPT_NAMES=""   # die Instanzen, deren Datei bleibt: "main" bzw. "dev, prod"
KEPT_IGNORED="" # Flags, die gegen die Datei nicht wirken
AI_LABEL=""     # KI-Zeile, wenn die behaltenen Dateien verschiedene Anbieter nennen

# kept_value NAME SCHLUESSEL — wie instance.sh liest: die letzte Zeile gewinnt; leer, wenn nicht da
kept_value() { grep "^$2=" "$INSTANCES_DIR/$1.env" 2>/dev/null | tail -n 1 | cut -d= -f2-; }
kept_or() { local v; v="$(kept_value "$1" "$2")"; printf '%s' "${v:-$3}"; }   # NAME SCHLUESSEL VORGABE
# kept_provider NAME — Standard-Anbieter der Datei; ohne Zeile die Vorgabe des Backends (claude)
kept_provider() { kept_or "$1" AUTOMATION_AI_DEFAULT_PROVIDER CLAUDE | tr 'A-Z' 'a-z'; }
# is_kept NAME — bleibt die Datei? (wie write_instance: vorhanden und kein --reconfigure)
is_kept() { [ "$RECONF" = 0 ] && [ -f "$INSTANCES_DIR/$1.env" ]; }

# kept_load — nach der Wahl des Modus: bleibt die Datei, uebernimmt der Lauf ihre Werte
kept_load() {
  local n rt port preset prov bind proj="" afh="" aprov="" sock=0 provs=""
  KEPT=0; KEPT_NAMES=""; KEPT_IGNORED=""; AI_LABEL=""
  if [ "$MODE" = staged ]; then
    for n in dev test prod; do is_kept "$n" && KEPT_NAMES="$KEPT_NAMES${KEPT_NAMES:+, }$n"; done
    [ "$KEPT_NAMES" = "dev, test, prod" ] || return 0
    # Alle drei bleiben: die Fragen nach Anbieter und KI-Zugang entfallen, gezeigt wird, was drinsteht
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
  if [ "$rt" = container ]; then   # Vorgaben wie in instance.sh
    proj="$(kept_or "$NAME" MYSURICATES_PROJECTS "$(cd "$REPO_ROOT/.." && pwd)")"
    afh="$(kept_or "$NAME" MYSURICATES_AI_FROM_HOST login)"
    aprov="$(kept_value "$NAME" MYSURICATES_AI_PROVIDERS)"
    sock="$(kept_or "$NAME" MYSURICATES_DOCKER_SOCKET 0)"
  fi
  _kept_flag --runtime "$RUNTIME" "$rt"
  _kept_flag --port "$PORT" "$port"
  [ "$FORCE" = 1 ] || _kept_flag --preset "$PRESET" "$preset"   # --force traegt das Preset ein
  _kept_flag --provider "$PROVIDER" "$prov"
  [ "$BIND" = 127.0.0.1 ] || _kept_flag --bind "$BIND" "$bind"   # 127.0.0.1 ist die Vorgabe von --bind
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
_kept_flag() {  # FLAG GEGEBEN AUS_DER_DATEI — per Flag gegeben, aber anders als in der Datei?
  [ -n "$2" ] && [ "$2" != "$3" ] && KEPT_IGNORED="$KEPT_IGNORED${KEPT_IGNORED:+, }$1 $2"
  return 0
}

# kept_mark [modules] — Kennzeichen hinter der Zeile eines Schritts: woher der Wert kommt
kept_mark() {
  [ -n "$KEPT_NAMES" ] || return 0
  if [ "$MODE" = staged ]; then printf ' · %s' "$(msg kept_files "$KEPT_NAMES")"
  elif [ "${1:-}" = modules ] && [ "$FORCE" = 1 ]; then printf ' · %s' "$(msg kept_into "deploy/instances/$NAME.env")"
  else printf ' · %s' "$(msg kept_from "deploy/instances/$NAME.env")"; fi
}

# kept_port NAME BASIS [VERGEBEN…] — Port im Modus Dev · Test · Prod: aus der Datei, die bleibt, sonst
# der erste freie ab BASIS, der nicht schon vergeben ist; den der laufenden Instanz NAME zaehlt er als frei
kept_port() { if is_kept "$1"; then kept_value "$1" MYSURICATES_PORT; else free_port "$2" "${3:-}" "$1"; fi; }

# load_existing NAME — die Werte einer vorhandenen Instanz als Ausgangspunkt fuer „Einstellungen aendern“
# und fuer --reconfigure, auch ohne Terminal (ca902719): Flags gehen vor, alles andere bleibt, wie es in
# der Datei steht. Wie kept_value: je Schluessel ein Wert, die letzte Zeile gewinnt (0e5b82b7).
# Nimmt auch BIND, AI_FROM_HOST, AI_PROVIDERS, den Docker-Socket (fbb1c61e), Umgebung und Siegel-Schalter
# mit, dazu jede AUTOMATION_*-Zeile (Haertung, Module, von Hand ergaenzt) — sonst schreibt ein Reconfigure
# ohne die passenden Flags stillschweigend die Vorgaben (127.0.0.1, kein Socket, keine Haertung) in die
# Datei. Setzt LOADED_LINES (fuer step_modules) und PORT_LOADED=1, wenn der Port aus der Datei kommt.
load_existing() {
  local p0="${PORT:-}" line
  NAME="$1"; MODE=one
  RUNTIME="${RUNTIME:-$(kept_or "$1" MYSURICATES_RUNTIME container)}"
  PORT="${PORT:-$(kept_value "$1" MYSURICATES_PORT)}"
  PORT_LOADED=0; [ -n "$p0" ] || [ -z "$PORT" ] || PORT_LOADED=1
  PRESET="${PRESET:-$(kept_value "$1" MYSURICATES_PRESET)}"
  PROVIDER="${PROVIDER:-$(kept_value "$1" AUTOMATION_AI_DEFAULT_PROVIDER | tr 'A-Z' 'a-z')}"
  PROJECTS="${PROJECTS:-$(kept_value "$1" MYSURICATES_PROJECTS)}"
  # BIND traegt "127.0.0.1" schon als Vorgabe (kein leerer String) — wie kept_load erkennen wir ein
  # gegebenes --bind daran, dass es davon abweicht.
  [ "${BIND:-127.0.0.1}" != 127.0.0.1 ] || BIND="$(kept_or "$1" MYSURICATES_BIND 127.0.0.1)"
  AI_FROM_HOST="${AI_FROM_HOST:-$(kept_value "$1" MYSURICATES_AI_FROM_HOST)}"
  AI_PROVIDERS="${AI_PROVIDERS:-$(kept_value "$1" MYSURICATES_AI_PROVIDERS)}"
  # SOCKET traegt zu diesem Zeitpunkt schon SOCKET_FLAG (0 ohne --docker-socket) — wie bei kept_load
  # zaehlt nur ein gegebenes --docker-socket als Flag, kein "aus".
  [ "${SOCKET_FLAG:-0}" = 1 ] || SOCKET="$(kept_or "$1" MYSURICATES_DOCKER_SOCKET 0)"
  LOADED_ENVIRONMENT="$(kept_value "$1" MYSURICATES_ENVIRONMENT)"
  LOADED_SEALING="$(kept_or "$1" MYSURICATES_HARDENING_SEALING false)"
  LOADED_REQUIRE_SEALED="$(kept_or "$1" MYSURICATES_HARDENING_REQUIRE_SEALED false)"
  # AUTOMATION_*-Zeilen in ihrer Reihenfolge (die letzte gewinnt weiter); den Standard-Anbieter schreibt
  # write_instance selbst
  LOADED_LINES=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in AUTOMATION_AI_DEFAULT_PROVIDER=*) ;; AUTOMATION_*=*) LOADED_LINES="$LOADED_LINES$line
" ;; esac
  done < "$INSTANCES_DIR/$1.env"
  CUSTOM_LINES="${CUSTOM_LINES:-}$LOADED_LINES"
}
