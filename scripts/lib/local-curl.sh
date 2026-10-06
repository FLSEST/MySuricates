#!/usr/bin/env bash
# curl against a MySuricates instance on this machine — health, readiness, REST API (bb4f60a5).
# Every call to the own instance from scripts/ goes through here; test-instance.sh pins bare calls.
#
# --noproxy '*': curl also sends 127.0.0.1 and localhost through http_proxy, https_proxy and all_proxy
# from the environment (curl 8.5). Behind a proxy this broke the health check — instance.sh up
# waited 300 s and reported "not healthy" —, and the proxy received the API key.
# The key goes to curl via stdin (-H @-), never in the arguments: every user of the machine sees those
# in the process list.
# Not for downloads from the network (scripts/lib/deps.sh): those need the proxy. bash 3.2 (macOS).

# local_curl CURL-ARGS… — curl, without proxy
local_curl() { curl --noproxy '*' "$@"; }

# local_curl_key KEY CURL-ARGS… — plus the X-API-Key header; empty KEY: no header. Whitespace is dropped
# (a key file ends with a newline). stdin belongs to the header: data with -d, never via @-.
# Here-string instead of printf | curl: it is in stdin before the start, and the exit code is curl's —
# a pipe would return 141 under pipefail if curl ends before it reads stdin. (Bash before 5.1 puts
# it briefly in a file in TMPDIR: readable by the owner only, deleted before curl starts.)
local_curl_key() {
  local key
  key="$(printf '%s' "${1:-}" | tr -d '[:space:]')"; shift
  if [ -z "$key" ]; then local_curl "$@"; else local_curl -H @- "$@" <<< "X-API-Key: $key"; fi
}
