#!/usr/bin/env bash
# Loest den API-Key auf, ohne die SQLite-DB anzufassen (CLAUDE.md: kein direkter DB-Zugriff, auch
# nicht lesend, wegen Lock-Konkurrenz bei pool-size=1). Reihenfolge: MYSURICATES_API_KEY (Env) ->
# ~/.mysuricates/api-key -> ~/.autotrigger/api-key — beide Dateien schreibt ApiKeyService bzw.
# InstanceAddress bei jedem Backend-Start im Klartext. Leere Ausgabe: kein Key gefunden.
#
# Gemeinsam genutzt von start-autotrigger-dev.sh und setup-audit-workflows.sh statt je eigener
# Kopie; Skripte mit eigenem Env-Override (z. B. AUTOPILOT_API_KEY) pruefen den vor dem Aufruf
# selbst und rufen api_key() nur als Fallback.

api_key() {
  if [[ -n "${MYSURICATES_API_KEY:-}" ]]; then
    echo "${MYSURICATES_API_KEY}"
  elif [[ -f "${HOME}/.mysuricates/api-key" ]]; then
    cat "${HOME}/.mysuricates/api-key"
  elif [[ -f "${HOME}/.autotrigger/api-key" ]]; then
    cat "${HOME}/.autotrigger/api-key"
  fi
}
