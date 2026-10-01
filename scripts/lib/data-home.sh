#!/usr/bin/env bash
# Datenordner einer Instanz ausserhalb der DB (API-Key, Siegel- und Integrations-Schluessel,
# Backups): <home>/.mysuricates. Installationen von vor der Umbenennung behalten <home>/.autotrigger,
# solange es den Ordner gibt — dieselbe Regel wie config/DataHome.java im Backend. AUTOMATION_DATA_HOME
# setzt ihn ausdruecklich (nur fuer das eigene HOME, nicht fuer das einer Instanz).

# data_home [HOME] — gibt den Datenordner fuer HOME (Standard: $HOME) aus.
data_home() {
  local home="${1:-$HOME}"
  if [[ -z "${1:-}" && -n "${AUTOMATION_DATA_HOME:-}" ]]; then
    echo "${AUTOMATION_DATA_HOME}"
  elif [[ -d "${home}/.autotrigger" ]]; then
    echo "${home}/.autotrigger"
  else
    echo "${home}/.mysuricates"
  fi
}

# Dieselbe Regel als sh-Einzeiler fuer `docker exec … sh -c "$DATA_HOME_KEY_SH" sh <home>`:
# gibt den API-Key aus dem Datenordner unter <home> im Container aus.
# shellcheck disable=SC2016
DATA_HOME_KEY_SH='if [ -d "$1/.autotrigger" ]; then cat "$1/.autotrigger/api-key"; else cat "$1/.mysuricates/api-key"; fi'
