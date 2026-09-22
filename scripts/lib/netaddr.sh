#!/usr/bin/env bash
# Netzadresse einer Instanz fuer die Abschlussmeldung (b8aa3866): Wer mit --bind 0.0.0.0 bindet, will
# von einem anderen Rechner zugreifen — "http://localhost:PORT" hilft dort nicht weiter.
#
# Nur lokale Abfragen, kein Netzverkehr: Linux `hostname -I`, macOS `ipconfig getifaddr enN`. Laesst
# sich nichts ermitteln, bleibt die Liste leer und der Aufrufer nennt einen Platzhalter.
# bash 3.2 (macOS), keine Linux-only-Pflicht: fehlt ein Kommando, wird das naechste versucht.

# netaddr_is_local BIND — nur von diesem Rechner erreichbar (Loopback)?
netaddr_is_local() {
  case "${1:-}" in
    ""|localhost|::1|127.*) return 0 ;;
    *) return 1 ;;
  esac
}

# netaddr_is_wildcard BIND — lauscht auf allen Schnittstellen?
netaddr_is_wildcard() {
  case "${1:-}" in 0.0.0.0|::|"[::]") return 0 ;; *) return 1 ;; esac
}

# IPv4-Adressen dieses Rechners ohne Loopback, eine je Zeile, ohne Dubletten
netaddr_host_addresses() {
  local out="" ifc a
  out="$(hostname -I 2>/dev/null)" || out=""
  if [ -z "$out" ] && command -v ipconfig >/dev/null 2>&1; then
    # macOS: en0 ist meist WLAN/Ethernet; weitere Schnittstellen der Reihe nach
    for ifc in en0 en1 en2 en3 en4 en5; do
      a="$(ipconfig getifaddr "$ifc" 2>/dev/null)" && out="$out $a"
    done
  fi
  for a in $out; do
    case "$a" in
      127.*|*:*) continue ;;                       # Loopback und IPv6 nicht nennen
      *[!0-9.]*) continue ;;
      *.*.*.*) printf '%s\n' "$a" ;;
    esac
  done | awk '!seen[$0]++'
}

# netaddr_urls BIND PORT — URLs, unter denen andere Rechner die Instanz erreichen; leer, wenn die
# Instanz nur lokal lauscht oder sich keine Adresse ermitteln laesst
netaddr_urls() {
  local bind="${1:-}" port="${2:-}" a
  netaddr_is_local "$bind" && return 0
  if netaddr_is_wildcard "$bind"; then
    for a in $(netaddr_host_addresses); do printf 'http://%s:%s\n' "$a" "$port"; done
  else
    case "$bind" in *:*) printf 'http://[%s]:%s\n' "$bind" "$port" ;; *) printf 'http://%s:%s\n' "$bind" "$port" ;; esac
  fi
}

# netaddr_lines BIND PORT — die Adresszeilen der Abschlussmeldung (setzt i18n.sh voraus):
# localhost, sofern die Instanz dort lauscht; bei Bind ins Netz zusaetzlich die Netzadresse(n) bzw.
# ein Platzhalter und der VPN-Hinweis aus --help.
netaddr_lines() {
  local bind="${1:-}" port="${2:-}" urls u
  if netaddr_is_local "$bind" || netaddr_is_wildcard "$bind"; then
    printf '%s\n' "$(msg done_open "http://localhost:$port")"
  fi
  netaddr_is_local "$bind" && return 0
  urls="$(netaddr_urls "$bind" "$port")"
  if [ -n "$urls" ]; then
    for u in $urls; do printf '%s\n' "$(msg done_network "$u")"; done
  else
    printf '%s\n' "$(msg done_network_unknown "$port")"
  fi
  printf '%s\n' "$(msg done_network_vpn)"
}
