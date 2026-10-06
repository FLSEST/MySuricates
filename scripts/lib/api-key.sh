#!/usr/bin/env bash
# Resolves the API key without touching the SQLite DB (CLAUDE.md: no direct DB access, even
# read-only, because of lock contention at pool-size=1). Order: MYSURICATES_API_KEY (env) ->
# api-key in the data folder (lib/data-home.sh: ~/.mysuricates, ~/.autotrigger for older installations)
# — ApiKeyService writes it there in plain text on every backend start -> ~/.mysuricates/api-key
# (created by the Claude Code plugin). Empty output: no key found.
#
# Used by start-mysuricates-dev.sh instead of its own copy; scripts with their own
# env override (e.g. AUTOPILOT_API_KEY) check that themselves before the call and call
# api_key() only as a fallback.

# shellcheck source=data-home.sh
. "$(dirname "${BASH_SOURCE[0]}")/data-home.sh"

api_key() {
  local home_key
  home_key="$(data_home)/api-key"
  if [[ -n "${MYSURICATES_API_KEY:-}" ]]; then
    echo "${MYSURICATES_API_KEY}"
  elif [[ -f "${home_key}" ]]; then
    cat "${home_key}"
  elif [[ -f "${HOME}/.mysuricates/api-key" ]]; then
    cat "${HOME}/.mysuricates/api-key"
  fi
}
