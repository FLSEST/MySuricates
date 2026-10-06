#!/usr/bin/env bash
# Network address of an instance for the final message (b8aa3866): whoever binds with --bind 0.0.0.0 wants
# to reach it from another machine — "http://localhost:PORT" does not help there.
#
# Local queries only, no network traffic: Linux `hostname -I`, macOS `ipconfig getifaddr enN`. If nothing
# can be determined, the list stays empty and the caller names a placeholder.
# bash 3.2 (macOS), no Linux-only requirement: if a command is missing, the next one is tried.

# netaddr_is_local BIND — reachable from this machine only (loopback)?
netaddr_is_local() {
  case "${1:-}" in
    ""|localhost|::1|127.*) return 0 ;;
    *) return 1 ;;
  esac
}

# netaddr_is_wildcard BIND — listening on all interfaces?
netaddr_is_wildcard() {
  case "${1:-}" in 0.0.0.0|::|"[::]") return 0 ;; *) return 1 ;; esac
}

# netaddr_local_url BIND PORT — the address for this machine: localhost if the instance listens there
# (127.0.0.1 or all interfaces); for another loopback address such as 127.0.0.2 or ::1 that one —
# the instance does not answer on localhost then (065144ee)
netaddr_local_url() {
  local a="${1:-}"
  a="${a#\[}"; a="${a%\]}"
  case "$a" in
    ""|localhost|127.0.0.1|0.0.0.0|::) printf 'http://localhost:%s' "$2" ;;
    *:*) printf 'http://[%s]:%s' "$a" "$2" ;;
    *) printf 'http://%s:%s' "$a" "$2" ;;
  esac
}

# IPv4 addresses of this machine without loopback, one per line, without duplicates
netaddr_host_addresses() {
  local out="" ifc a
  out="$(hostname -I 2>/dev/null)" || out=""
  if [ -z "$out" ] && command -v ipconfig >/dev/null 2>&1; then
    # macOS: en0 is usually WiFi/Ethernet; further interfaces in order
    for ifc in en0 en1 en2 en3 en4 en5; do
      a="$(ipconfig getifaddr "$ifc" 2>/dev/null)" && out="$out $a"
    done
  fi
  for a in $out; do
    case "$a" in
      127.*|*:*) continue ;;                       # do not list loopback and IPv6
      *[!0-9.]*) continue ;;
      *.*.*.*) printf '%s\n' "$a" ;;
    esac
  done | awk '!seen[$0]++'
}

# netaddr_urls BIND PORT — URLs under which other machines reach the instance; empty if the
# instance listens locally only or no address can be determined
netaddr_urls() {
  local bind="${1:-}" port="${2:-}" a
  netaddr_is_local "$bind" && return 0
  if netaddr_is_wildcard "$bind"; then
    for a in $(netaddr_host_addresses); do printf 'http://%s:%s\n' "$a" "$port"; done
  else
    case "$bind" in *:*) printf 'http://[%s]:%s\n' "$bind" "$port" ;; *) printf 'http://%s:%s\n' "$bind" "$port" ;; esac
  fi
}

# netaddr_lines BIND PORT — the address lines of the final message (requires i18n.sh):
# localhost or the loopback address, if the instance listens there; when bound to the network additionally the
# network address(es) or a placeholder and the VPN note from --help.
netaddr_lines() {
  local bind="${1:-}" port="${2:-}" urls u
  if netaddr_is_local "$bind" || netaddr_is_wildcard "$bind"; then
    printf '%s\n' "$(msg done_open "$(netaddr_local_url "$bind" "$port")")"
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
