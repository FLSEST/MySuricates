#!/usr/bin/env bash
# Generate a self-signed TLS certificate for local/Tailscale development.
# Usage: ./scripts/generate-dev-cert.sh [tailscale-hostname]
#
# The certificate covers localhost, 127.0.0.1, and the Tailscale hostname/IP.
# Output: certs/dev.crt + certs/dev.key (+ PKCS12 for Spring Boot: certs/dev.p12)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CERT_DIR="$PROJECT_DIR/certs"
mkdir -p "$CERT_DIR"

# Keystore-Passwort: eigener Wert per AUTOMATION_SSL_PASSWORD, sonst der Dev-Default,
# den application.yml fuer server.ssl.key-store-password ebenfalls verwendet.
KEYSTORE_PASSWORD="${AUTOMATION_SSL_PASSWORD:-mysuricates}"

# Detect Tailscale hostname and IP
TS_HOSTNAME="${1:-}"
TS_IP=""
if [ -z "$TS_HOSTNAME" ]; then
  TS_HOSTNAME=$(tailscale status --self --json 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
dns = d.get('Self', {}).get('DNSName', '')
print(dns.rstrip('.'))
" 2>/dev/null || true)
fi
if command -v tailscale &>/dev/null; then
  TS_IP=$(tailscale ip -4 2>/dev/null || true)
fi

# Build SAN entries
SAN="DNS:localhost"
[ -n "$TS_HOSTNAME" ] && SAN="$SAN,DNS:$TS_HOSTNAME"
SAN="$SAN,IP:127.0.0.1"
[ -n "$TS_IP" ] && SAN="$SAN,IP:$TS_IP"

echo "Generating certificate with SAN: $SAN"

# Generate cert + key (valid 825 days — Chrome max for self-signed)
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/dev.key" \
  -out "$CERT_DIR/dev.crt" \
  -days 825 \
  -subj "/CN=MySuricates Dev" \
  -addext "subjectAltName=$SAN" \
  -addext "basicConstraints=CA:FALSE" \
  -addext "keyUsage=digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth" \
  2>/dev/null

# Generate PKCS12 keystore for Spring Boot
openssl pkcs12 -export \
  -in "$CERT_DIR/dev.crt" \
  -inkey "$CERT_DIR/dev.key" \
  -out "$CERT_DIR/dev.p12" \
  -name mysuricates \
  -passout "pass:$KEYSTORE_PASSWORD" \
  2>/dev/null

chmod 600 "$CERT_DIR/dev.key" "$CERT_DIR/dev.p12"

echo ""
echo "Certificates generated in $CERT_DIR/"
echo "  dev.crt  — Certificate (Vite, Browser-Import)"
echo "  dev.key  — Private Key (Vite)"
echo "  dev.p12  — PKCS12 Keystore (Spring Boot)"
echo ""
if [ -n "${AUTOMATION_SSL_PASSWORD:-}" ]; then
  echo "Keystore-Passwort: aus AUTOMATION_SSL_PASSWORD uebernommen."
else
  echo "Keystore-Passwort: Dev-Default aus application.yml (siehe docs/GETTING-STARTED.md,"
  echo "Abschnitt 7). Fuer einen eigenen Wert AUTOMATION_SSL_PASSWORD setzen und neu erzeugen."
  echo "Achtung: Mit diesem Dev-Default verweigert das Backend den Start, sobald"
  echo "AUTOMATION_SSL_ENABLED=true gesetzt ist — vorher AUTOMATION_SSL_PASSWORD setzen."
fi
echo ""
if [ -n "$TS_HOSTNAME" ]; then
  echo "Tailscale: https://$TS_HOSTNAME:3000 (dev) / :9090 (backend)"
fi
echo ""
echo "To trust on mobile: transfer dev.crt to your phone and install it."
echo "  Android: Settings > Security > Install certificate"
echo "  iOS:     Settings > Profile Downloaded > Install, then"
echo "           Settings > General > About > Certificate Trust Settings > Enable"
