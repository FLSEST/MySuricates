#!/usr/bin/env bash
# Data folder of an instance outside the DB (API key, seal and integration keys,
# backups): <home>/.mysuricates. Installations from before the rename keep <home>/.autotrigger
# as long as that folder exists — same rule as config/DataHome.java in the backend. AUTOMATION_DATA_HOME
# sets it explicitly (only for one's own HOME, not for that of an instance).

# data_home [HOME] — prints the data folder for HOME (default: $HOME).
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

# The same rule as an sh one-liner for `docker exec … sh -c "$DATA_HOME_KEY_SH" sh <home>`:
# prints the API key from the data folder under <home> inside the container.
# shellcheck disable=SC2016
DATA_HOME_KEY_SH='if [ -d "$1/.autotrigger" ]; then cat "$1/.autotrigger/api-key"; else cat "$1/.mysuricates/api-key"; fi'
