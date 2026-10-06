#!/usr/bin/env bash
# Running workflow runs of an instance (29820f58). A restart aborts them, afterwards they show up as
# ghost runs in the history. Before scripts/setup.sh restarts a running instance and before
# scripts/instance.sh stops it, both therefore ask — read-only:
# GET /api/v1/executions?status=RUNNING, the count is in the page as totalElements.
#
# Asked via lib/local-curl.sh: never through a proxy from the environment, the key via stdin instead of
# in the arguments (bb4f60a5). Without python and jq, bash 3.2 (macOS).
# shellcheck source=local-curl.sh
. "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/local-curl.sh"

# instance_connect_addr BIND — where this machine reaches the instance: if it listens on 127.0.0.1 or all
# interfaces, there; otherwise at the bound address, also another loopback address — it then does not
# listen on 127.0.0.1 (3d002903). IPv6 without brackets, for /dev/tcp.
instance_connect_addr() {
  local a="${1:-}"
  a="${a#\[}"; a="${a%\]}"
  case "$a" in
    ""|localhost|127.0.0.1|0.0.0.0|::) printf '127.0.0.1' ;;
    *) printf '%s' "$a" ;;
  esac
}

# instance_api_base BIND PORT — the same as a base URL for curl
instance_api_base() {
  local a
  a="$(instance_connect_addr "${1:-}")"
  case "$a" in
    *:*) printf 'http://[%s]:%s' "$a" "$2" ;;
    *) printf 'http://%s:%s' "$a" "$2" ;;
  esac
}

# instance_running_runs BASE KEY — number of running runs; empty if it cannot be determined
# (no key, no answer, rejected, answer without a number). What "unknown" means is up to the caller.
instance_running_runs() {
  local base="${1:-}" key body
  key="$(printf '%s' "${2:-}" | tr -d '[:space:]')"
  [ -n "$base" ] && [ -n "$key" ] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  body="$(local_curl_key "$key" -fsS --max-time "${INSTANCE_RUNS_TIMEOUT:-5}" \
        "$base/api/v1/executions?status=RUNNING&size=1" 2>/dev/null)" || return 0
  # The last occurrence: the page ends with totalElements and totalPages; a name in content that
  # looks like that is there with escaped quotation marks and does not match
  printf '%s' "$body" | tr -d '\r\n' | sed -n 's/.*"totalElements"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p'
}

# instance_file_addr FILE — "PORT,BIND" from an instance file (empty BIND = 127.0.0.1)
instance_file_addr() {
  printf '%s,%s' "$(grep '^MYSURICATES_PORT=' "$1" 2>/dev/null | tail -n 1 | cut -d= -f2)" \
    "$(grep '^MYSURICATES_BIND=' "$1" 2>/dev/null | tail -n 1 | cut -d= -f2)"
}

# For scripts/setup.sh: remember the address before it rewrites the file — until the restart the
# instance listens there, even after --reconfigure with another port. First state per name counts.
INSTANCE_PREV_ADDRS=""
instance_remember_addr() {  # NAME DATEI
  case " $INSTANCE_PREV_ADDRS " in *" $1="*) return 0 ;; esac
  INSTANCE_PREV_ADDRS="$INSTANCE_PREV_ADDRS $1=$(instance_file_addr "$2")"
}
instance_prev_addr() { printf '%s\n' $INSTANCE_PREV_ADDRS | sed -n "s/^$1=//p" | head -n 1; }   # NAME → PORT,BIND
