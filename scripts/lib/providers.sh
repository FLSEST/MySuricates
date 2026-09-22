#!/usr/bin/env bash
# KI-Anbieter fuer den Installer — gleichrangig, aus dem generierten Katalog des Backends
# (provider-catalog.generated.sh, ProviderCatalogTest). Kein Anbieter ist fest verdrahtet (2255f2ee).
#
# Erkennung ohne Netz und ohne die CLIs zu starten: vorhanden = auf dem PATH, angemeldet = eine der
# Anmeldedateien unter $HOME ist nicht leer (bzw. enthaelt das Muster bei "datei:muster") oder eine
# der Anmelde-Umgebungsvariablen (login_env, z. B. CLAUDE_CODE_OAUTH_TOKEN) ist gesetzt und nicht leer
# (0b1a3ae7). Den Wert gibt hier nichts aus.
# bash 3.2 (macOS): Skalare statt assoziativer Arrays.

_PROVIDERS_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_IDS=()
# shellcheck source=provider-catalog.generated.sh
[ -r "${_PROVIDERS_LIB_DIR}/provider-catalog.generated.sh" ] && . "${_PROVIDERS_LIB_DIR}/provider-catalog.generated.sh"

providers_ids() { local id; for id in "${PROVIDER_IDS[@]}"; do printf '%s\n' "$id"; done; }

# provider_get ID FELD  (cli, install, login, login_files, login_env)
provider_get() {
  local name="PROVIDER__$(printf '%s' "${1:-}" | tr -c 'a-z0-9_' '_')__${2:-}"
  printf '%s' "${!name-}"
}

provider_present() { command -v "$(provider_get "${1:-}" cli)" >/dev/null 2>&1; }

provider_logged_in() {
  local entry file pattern var
  for var in $(provider_get "${1:-}" login_env); do
    case "$var" in ''|[!A-Z]*|*[!A-Z0-9_]*) continue ;; esac
    [ -n "${!var-}" ] && return 0
  done
  for entry in $(provider_get "${1:-}" login_files); do
    file="${entry%%:*}"; pattern=""
    [ "$file" != "$entry" ] && pattern="${entry#*:}"
    [ -s "$HOME/$file" ] || continue
    if [ -z "$pattern" ] || grep -qF -- "$pattern" "$HOME/$file" 2>/dev/null; then return 0; fi
  done
  return 1
}

# Eine Zeile je Anbieter: "<id> present|missing logged-in|logged-out"
providers_status() {
  local id p l
  for id in "${PROVIDER_IDS[@]}"; do
    if provider_present "$id"; then p=present; else p=missing; fi
    if provider_logged_in "$id"; then l=logged-in; else l=logged-out; fi
    printf '%s %s %s\n' "$id" "$p" "$l"
  done
}
