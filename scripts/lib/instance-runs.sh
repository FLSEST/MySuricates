#!/usr/bin/env bash
# Laufende Workflow-Runs einer Instanz (29820f58). Ein Neustart bricht sie ab, danach stehen sie als
# Ghost-Runs im Verlauf. Bevor scripts/setup.sh eine laufende Instanz neu startet und bevor
# scripts/instance.sh sie stoppt, fragen beide deshalb nach — nur lesend:
# GET /api/v1/executions?status=RUNNING, die Zahl steht als totalElements in der Seite.
#
# Gefragt wird ueber lib/local-curl.sh: nie ueber einen Proxy aus der Umgebung, der Key ueber stdin statt
# in den Argumenten (bb4f60a5). Ohne python und jq, bash 3.2 (macOS).
# shellcheck source=local-curl.sh
. "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/local-curl.sh"

# instance_api_base BIND PORT — wie dieser Rechner die Instanz erreicht: lauscht sie auf Loopback oder
# allen Schnittstellen, ueber 127.0.0.1, sonst unter der gebundenen Adresse
instance_api_base() {
  case "${1:-}" in
    ""|localhost|127.*|0.0.0.0|::|"[::]") printf 'http://127.0.0.1:%s' "$2" ;;
    *:*) printf 'http://[%s]:%s' "$1" "$2" ;;
    *) printf 'http://%s:%s' "$1" "$2" ;;
  esac
}

# instance_running_runs BASIS KEY — Zahl der laufenden Runs; leer, wenn sie sich nicht ermitteln laesst
# (kein Key, keine Antwort, abgelehnt, Antwort ohne Zahl). Was „unbekannt“ heisst, entscheidet der Aufrufer.
instance_running_runs() {
  local base="${1:-}" key body
  key="$(printf '%s' "${2:-}" | tr -d '[:space:]')"
  [ -n "$base" ] && [ -n "$key" ] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  body="$(local_curl_key "$key" -fsS --max-time "${INSTANCE_RUNS_TIMEOUT:-5}" \
        "$base/api/v1/executions?status=RUNNING&size=1" 2>/dev/null)" || return 0
  # Das letzte Vorkommen: die Seite endet mit totalElements und totalPages; ein Name in content, der
  # so aussieht, steht dort mit maskierten Anfuehrungszeichen und passt nicht
  printf '%s' "$body" | tr -d '\r\n' | sed -n 's/.*"totalElements"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p'
}

# instance_file_addr DATEI — „PORT,BIND“ aus einer Instanz-Datei (BIND leer = 127.0.0.1)
instance_file_addr() {
  printf '%s,%s' "$(grep '^MYSURICATES_PORT=' "$1" 2>/dev/null | tail -n 1 | cut -d= -f2)" \
    "$(grep '^MYSURICATES_BIND=' "$1" 2>/dev/null | tail -n 1 | cut -d= -f2)"
}

# Fuer scripts/setup.sh: die Adresse merken, bevor es die Datei neu schreibt — bis zum Neustart lauscht
# die Instanz dort, auch nach --reconfigure mit anderem Port. Erster Stand je Name zaehlt.
INSTANCE_PREV_ADDRS=""
instance_remember_addr() {  # NAME DATEI
  case " $INSTANCE_PREV_ADDRS " in *" $1="*) return 0 ;; esac
  INSTANCE_PREV_ADDRS="$INSTANCE_PREV_ADDRS $1=$(instance_file_addr "$2")"
}
instance_prev_addr() { printf '%s\n' $INSTANCE_PREV_ADDRS | sed -n "s/^$1=//p" | head -n 1; }   # NAME → PORT,BIND
