#!/usr/bin/env bash
# AI providers for the installer — equal-ranking, from the backend's generated catalog
# (provider-catalog.generated.sh, ProviderCatalogTest). No provider is hard-wired (2255f2ee).
#
# Detection without network and without starting the CLIs: present = on the PATH, logged in = one of the
# login files under $HOME is not empty (or contains the pattern for "file:pattern") or one
# of the login environment variables (login_env, e.g. CLAUDE_CODE_OAUTH_TOKEN) is set and not empty
# (0b1a3ae7). Nothing here prints the value.
# bash 3.2 (macOS): scalars instead of associative arrays.

_PROVIDERS_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_IDS=()
# shellcheck source=provider-catalog.generated.sh
[ -r "${_PROVIDERS_LIB_DIR}/provider-catalog.generated.sh" ] && . "${_PROVIDERS_LIB_DIR}/provider-catalog.generated.sh"

providers_ids() { local id; for id in "${PROVIDER_IDS[@]}"; do printf '%s\n' "$id"; done; }

# provider_get ID FIELD  (cli, install, login, login_note, login_files, login_env)
provider_get() {
  local name="PROVIDER__$(printf '%s' "${1:-}" | tr -c 'a-z0-9_' '_')__${2:-}"
  printf '%s' "${!name-}"
}

provider_present() { command -v "$(provider_get "${1:-}" cli)" >/dev/null 2>&1; }

# Names of the set, non-empty login variables, separated by spaces — never their values
provider_login_env_names() {
  local var out=""
  for var in $(provider_get "${1:-}" login_env); do
    case "$var" in ''|[!A-Z]*|*[!A-Z0-9_]*) continue ;; esac
    [ -n "${!var-}" ] && out="${out:+$out }$var"
  done
  printf '%s' "$out"
}

# Logged in via a file under $HOME — scripts/lib/host-mounts.sh mounts it. A login only via
# environment variable reaches a container only through ~/.config/mysuricates/ai.env (setup.sh asks)
provider_login_file() {
  local entry file pattern
  for entry in $(provider_get "${1:-}" login_files); do
    file="${entry%%:*}"; pattern=""
    [ "$file" != "$entry" ] && pattern="${entry#*:}"
    [ -s "$HOME/$file" ] || continue
    if [ -z "$pattern" ] || grep -qF -- "$pattern" "$HOME/$file" 2>/dev/null; then return 0; fi
  done
  return 1
}

provider_logged_in() { [ -n "$(provider_login_env_names "${1:-}")" ] || provider_login_file "${1:-}"; }

# One line per provider: "<id> present|missing logged-in|logged-out"
providers_status() {
  local id p l
  for id in "${PROVIDER_IDS[@]}"; do
    if provider_present "$id"; then p=present; else p=missing; fi
    if provider_logged_in "$id"; then l=logged-in; else l=logged-out; fi
    printf '%s %s %s\n' "$id" "$p" "$l"
  done
}
