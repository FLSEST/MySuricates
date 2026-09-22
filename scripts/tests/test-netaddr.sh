#!/usr/bin/env bash
# Tests for scripts/lib/netaddr.sh — the network address named when an instance binds beyond
# localhost (b8aa3866). hostname/ipconfig are stubs, nothing touches the network.
# Run: bash scripts/tests/test-netaddr.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
mkdir -p "$TMP/linux" "$TMP/mac" "$TMP/none" "$TMP/tools"
# Eigener PATH: nur die Werkzeuge, die netaddr.sh braucht — sonst kaeme das echte hostname zum Zug
for t in awk sed cat dirname basename; do ln -s "$(command -v "$t")" "$TMP/tools/$t"; done
cat > "$TMP/linux/hostname" <<'STUB'
#!/bin/sh
[ "$1" = -I ] && echo "192.168.1.20 127.0.0.1 10.8.0.3 fe80::1 192.168.1.20 "
STUB
# macOS: hostname kennt -I nicht, ipconfig getifaddr liefert je Schnittstelle eine Adresse
cat > "$TMP/mac/hostname" <<'STUB'
#!/bin/sh
echo "hostname: illegal option -- I" >&2; exit 1
STUB
cat > "$TMP/mac/ipconfig" <<'STUB'
#!/bin/sh
case "$2" in en0) echo 10.0.0.7 ;; en1) echo 10.0.0.8 ;; *) exit 1 ;; esac
STUB
chmod +x "$TMP"/linux/* "$TMP"/mac/*
run() {  # OS-VERZEICHNIS SPRACHE BEFEHL
  ( PATH="$TMP/$1:$TMP/tools"; export PATH; . "$HERE/../lib/i18n.sh"; i18n_load "$2"; . "$HERE/../lib/netaddr.sh"; eval "$3" )
}

echo "1) local or network?"
check "127.0.0.1 is local"                        'run none en "netaddr_is_local 127.0.0.1"'
check "::1 is local"                              'run none en "netaddr_is_local ::1"'
check "0.0.0.0 is not local but wildcard"         'run none en "! netaddr_is_local 0.0.0.0 && netaddr_is_wildcard 0.0.0.0"'

echo "2) addresses of this machine"
check "Linux: hostname -I, no loopback/IPv6/dupes" '[ "$(run linux en netaddr_host_addresses | tr "\n" " ")" = "192.168.1.20 10.8.0.3 " ]'
check "macOS: ipconfig getifaddr en0, en1"        '[ "$(run mac en netaddr_host_addresses | tr "\n" " ")" = "10.0.0.7 10.0.0.8 " ]'
check "nothing found: empty"                      '[ -z "$(run none en netaddr_host_addresses)" ]'

echo "3) URLs"
check "local bind: no network URL"                '[ -z "$(run linux en "netaddr_urls 127.0.0.1 9090")" ]'
check "0.0.0.0: one URL per address"              '[ "$(run linux en "netaddr_urls 0.0.0.0 9090" | tr "\n" " ")" = "http://192.168.1.20:9090 http://10.8.0.3:9090 " ]'
check "specific address: exactly that one"        '[ "$(run linux en "netaddr_urls 192.168.5.5 9090")" = "http://192.168.5.5:9090" ]'
check "IPv6 address in brackets"                  '[ "$(run none en "netaddr_urls fd00::5 9090")" = "http://[fd00::5]:9090" ]'

echo "4) lines of the final message"
OUT_LOCAL="$(run linux en "netaddr_lines 127.0.0.1 9090")"
check "local: only localhost, no VPN hint"        '[ "$OUT_LOCAL" = "→  http://localhost:9090" ]'
OUT="$(run linux en "netaddr_lines 0.0.0.0 9090")"
check "0.0.0.0: localhost first"                  '[ "$(printf "%s\n" "$OUT" | head -n 1)" = "→  http://localhost:9090" ]'
check "0.0.0.0: network address named"            'printf "%s" "$OUT" | grep -F "http://192.168.1.20:9090   (from the network)" >/dev/null'
check "0.0.0.0: VPN hint"                         'printf "%s" "$OUT" | grep "VPN" >/dev/null'
OUT="$(run none de "netaddr_lines 0.0.0.0 9090")"
check "nothing found: placeholder (de)"           'printf "%s" "$OUT" | grep -F "http://<Adresse dieses Rechners>:9090" >/dev/null'
check "nothing found: VPN hint (de)"              'printf "%s" "$OUT" | grep "VPN davor" >/dev/null'
OUT="$(run linux en "netaddr_lines 192.168.5.5 9090")"
check "specific address: no localhost line"       '! printf "%s" "$OUT" | grep localhost >/dev/null && printf "%s" "$OUT" | grep -F "http://192.168.5.5:9090" >/dev/null'

echo "5) bash 3.2 hygiene"
check "no bash-4-only syntax"                     '! grep -nE "declare -g?A|mapfile|readarray" "$HERE/../lib/netaddr.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
