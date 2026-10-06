#!/usr/bin/env bash
# Part of scripts/setup.sh: which port is free. Busy means that something accepts connections on the address
# the instance binds to — unless it is the running instance that is being set up right now
# (0e5b82b7). It frees its port on restart; it used to count, and --reconfigure without
# --port moved it to the next port (9090 → 9091). Reads INSTANCES_DIR, SCRIPT_DIR and BIND of the
# installer, kept_value from setup-kept.sh and instance_connect_addr from instance-runs.sh — not usable
# on its own. bash 3.2 (macOS).

# Same address as port_busy in instance.sh (065144ee): with --bind to an address the
# instance listens only there — a use on 127.0.0.1 does not stop it, and the port choice used to see one there
port_in_use() { (exec 3<>"/dev/tcp/$(instance_connect_addr "${BIND:-}")/$1") 2>/dev/null; }

# own_port NAME — the port of the running instance NAME: the one from its file if instance.sh reports it as
# running (on the machine via the PID, in the container via docker); otherwise empty
own_port() {
  [ -f "$INSTANCES_DIR/$1.env" ] || return 0
  case "$(bash "$SCRIPT_DIR/instance.sh" status --name "$1" 2>/dev/null)" in
    running*) kept_value "$1" MYSURICATES_PORT ;;
  esac
}

# free_port BASE [TAKEN…] [NAME] — first port from BASE that is neither busy nor already taken;
# it counts the port of the running instance NAME as free
free_port() {
  local p="$1" i=0 taken=" ${2:-} " own=""
  [ -z "${3:-}" ] || own="$(own_port "$3")"
  while { { port_in_use "$p" && [ "$p" != "$own" ]; } || [ "${taken#* $p }" != "$taken" ]; } && [ "$i" -lt 50 ]; do
    p=$((p + 1)); i=$((i + 1))
  done
  echo "$p"
}
