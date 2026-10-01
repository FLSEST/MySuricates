#!/usr/bin/env bash
# Loest den API-Key auf, ohne die SQLite-DB anzufassen (CLAUDE.md: kein direkter DB-Zugriff, auch
# nicht lesend, wegen Lock-Konkurrenz bei pool-size=1). Reihenfolge: MYSURICATES_API_KEY (Env) ->
# api-key im Datenordner (lib/data-home.sh: ~/.mysuricates, bei aelteren Installationen ~/.autotrigger)
# — dorthin schreibt ihn ApiKeyService bei jedem Backend-Start im Klartext -> ~/.mysuricates/api-key
# (vom Claude-Code-Plugin angelegt). Leere Ausgabe: kein Key gefunden.
#
# Genutzt von start-mysuricates-dev.sh statt einer eigenen Kopie; Skripte mit eigenem
# Env-Override (z. B. AUTOPILOT_API_KEY) pruefen den vor dem Aufruf selbst und rufen
# api_key() nur als Fallback.

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
