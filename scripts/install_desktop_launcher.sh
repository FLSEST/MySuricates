#!/usr/bin/env bash
set -euo pipefail

# Legt Desktop-Verknuepfungen fuer die drei Umgebungen an:
#   - Dev     (Frontend :3000 + Backend :9090 aus dem Quellcode)
#   - Stable  (Container mysuricates-stable, :9091)
#   - Test    (Container mysuricates-test, :8090)
# Die beiden Container starten NICHT mehr automatisch beim Boot
# (restart: "no" in den Compose-Dateien, automation.staging.auto-start=false),
# sondern bewusst ueber diese Icons.

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
START_SCRIPT="${REPO_ROOT}/scripts/start-autotrigger-dev.sh"
STAGING_SCRIPT="${REPO_ROOT}/scripts/staging.sh"
ICON_PATH="${REPO_ROOT}/ai-automation-frontend/public/icons/icon.svg"

DESKTOP_DIR="${XDG_DESKTOP_DIR:-$(xdg-user-dir DESKTOP 2>/dev/null || true)}"
if [[ -z "${DESKTOP_DIR}" || ! -d "${DESKTOP_DIR}" ]]; then
  DESKTOP_DIR="$HOME/Desktop"
fi
if [[ ! -d "${DESKTOP_DIR}" && -d "$HOME/Schreibtisch" ]]; then
  DESKTOP_DIR="$HOME/Schreibtisch"
fi

if [[ ! -d "${DESKTOP_DIR}" ]]; then
  echo "Desktop-Verzeichnis nicht gefunden." >&2
  exit 1
fi

# Auch ins Anwendungsmenue legen, damit die Eintraege ueber die Suche auffindbar sind.
APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "${APPS_DIR}"

# write_launcher <dateiname> <name> <kommentar> <exec> <terminal>
write_launcher() {
  local file_name="$1" name="$2" comment="$3" exec_line="$4" terminal="$5"
  local target

  for target in "${DESKTOP_DIR}/${file_name}" "${APPS_DIR}/${file_name}"; do
    cat > "${target}" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=${name}
Comment=${comment}
Exec=${exec_line}
Path=${REPO_ROOT}
Icon=${ICON_PATH}
Terminal=${terminal}
Categories=Development;
StartupNotify=true
EOF
    chmod +x "${target}"
    echo "Verknuepfung erstellt: ${target}"
  done
}

write_launcher \
  "mysuricates-dev.desktop" \
  "MySuricates Dev" \
  "Startet Dev-Backend (:9090) und Dev-Frontend (:3000) aus dem Quellcode" \
  "bash \"${START_SCRIPT}\"" \
  "false"

# Terminal=true bei den Containern: staging.sh wartet auf den Healthcheck und
# bricht mit einer Meldung ab, wenn kein Snapshot (stable-frontend/) existiert.
# Ohne Terminal-Fenster bliebe dieser Fehler unsichtbar.
write_launcher \
  "mysuricates-stable.desktop" \
  "MySuricates Stable" \
  "Startet den Stable-Container auf http://localhost:9091" \
  "bash \"${STAGING_SCRIPT}\" start" \
  "true"

write_launcher \
  "mysuricates-test.desktop" \
  "MySuricates Test" \
  "Startet den Test-Container auf http://localhost:8090" \
  "bash \"${STAGING_SCRIPT}\" test-start" \
  "true"

# Alte Einzel-Verknuepfung aus der Zeit vor den drei Icons entfernen.
LEGACY_LAUNCHER="${DESKTOP_DIR}/autotrigger-dev.desktop"
if [[ -f "${LEGACY_LAUNCHER}" ]]; then
  rm -f "${LEGACY_LAUNCHER}"
  echo "Alte Verknuepfung entfernt: ${LEGACY_LAUNCHER}"
fi
