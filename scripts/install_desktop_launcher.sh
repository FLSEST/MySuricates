#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
START_SCRIPT="${REPO_ROOT}/scripts/start-autotrigger-dev.sh"
ICON_PATH="${REPO_ROOT}/ai-automation-frontend/public/icons/icon.svg"

DESKTOP_DIR="${XDG_DESKTOP_DIR:-$HOME/Desktop}"
if [[ ! -d "${DESKTOP_DIR}" && -d "$HOME/Schreibtisch" ]]; then
  DESKTOP_DIR="$HOME/Schreibtisch"
fi

if [[ ! -d "${DESKTOP_DIR}" ]]; then
  echo "Desktop-Verzeichnis nicht gefunden." >&2
  exit 1
fi

LAUNCHER_PATH="${DESKTOP_DIR}/autotrigger-dev.desktop"

cat > "${LAUNCHER_PATH}" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Autotrigger Dev
Comment=Startet das lokale Autotrigger-Frontend und -Backend
Exec=bash "${START_SCRIPT}"
Path=${REPO_ROOT}
Icon=${ICON_PATH}
Terminal=false
Categories=Development;
StartupNotify=true
EOF

chmod +x "${LAUNCHER_PATH}"
echo "Desktop-Verknuepfung erstellt: ${LAUNCHER_PATH}"
