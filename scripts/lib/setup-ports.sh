#!/usr/bin/env bash
# Teil von scripts/setup.sh: welcher Port frei ist. Belegt heisst, auf 127.0.0.1 nimmt etwas Verbindungen
# an — ausser es ist die laufende Instanz, die gerade eingerichtet wird (0e5b82b7). Die gibt ihren Port
# beim Neustart frei; frueher zaehlte sie mit, und --reconfigure ohne --port zog sie auf den naechsten
# Port (9090 → 9091). Liest INSTANCES_DIR und SCRIPT_DIR des Installers und kept_value aus
# setup-kept.sh — fuer sich allein nicht nutzbar. bash 3.2 (macOS).

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# own_port NAME — der Port der laufenden Instanz NAME: der aus ihrer Datei, wenn instance.sh sie als
# laufend meldet (auf dem Rechner ueber die PID, im Container ueber docker); sonst leer
own_port() {
  [ -f "$INSTANCES_DIR/$1.env" ] || return 0
  case "$(bash "$SCRIPT_DIR/instance.sh" status --name "$1" 2>/dev/null)" in
    running*) kept_value "$1" MYSURICATES_PORT ;;
  esac
}

# free_port BASIS [VERGEBEN…] [NAME] — erster Port ab BASIS, der weder belegt noch schon vergeben ist;
# den Port der laufenden Instanz NAME zaehlt er als frei
free_port() {
  local p="$1" i=0 taken=" ${2:-} " own=""
  [ -z "${3:-}" ] || own="$(own_port "$3")"
  while { { port_in_use "$p" && [ "$p" != "$own" ]; } || [ "${taken#* $p }" != "$taken" ]; } && [ "$i" -lt 50 ]; do
    p=$((p + 1)); i=$((i + 1))
  done
  echo "$p"
}
