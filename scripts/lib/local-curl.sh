#!/usr/bin/env bash
# curl gegen eine MySuricates-Instanz auf diesem Rechner — Health, Bereitschaft, REST-API (bb4f60a5).
# Jeder Aufruf der eigenen Instanz aus scripts/ geht hierueber; test-instance.sh haelt nackte Aufrufe fest.
#
# --noproxy '*': curl schickt auch 127.0.0.1 und localhost ueber http_proxy, https_proxy und all_proxy
# aus der Umgebung (curl 8.5). Hinter einem Proxy scheiterte so die Health-Pruefung — instance.sh up
# wartete 300 s und meldete „nicht gesund“ —, und der Proxy bekam den API-Key mit.
# Der Key geht ueber stdin an curl (-H @-), nie in die Argumente: die sieht jeder Benutzer des Rechners
# in der Prozessliste.
# Nicht fuer Downloads aus dem Netz (scripts/lib/deps.sh): die brauchen den Proxy. bash 3.2 (macOS).

# local_curl CURL-ARGS… — curl, ohne Proxy
local_curl() { curl --noproxy '*' "$@"; }

# local_curl_key KEY CURL-ARGS… — dazu der Header X-API-Key; leerer KEY: ohne Header. Leerraum faellt weg
# (eine Key-Datei endet mit einem Zeilenumbruch). stdin gehoert dem Header: Daten mit -d, nie per @-.
# Here-String statt printf | curl: steht vor dem Start in stdin, und der Exit-Code ist der von curl —
# eine Pipe gaebe unter pipefail 141 zurueck, wenn curl endet, bevor es stdin liest. (Bash vor 5.1 legt
# ihn kurz als Datei im TMPDIR ab: nur fuer den Eigentuemer lesbar, geloescht, bevor curl startet.)
local_curl_key() {
  local key
  key="$(printf '%s' "${1:-}" | tr -d '[:space:]')"; shift
  if [ -z "$key" ]; then local_curl "$@"; else local_curl -H @- "$@" <<< "X-API-Key: $key"; fi
}
