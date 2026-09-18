#!/usr/bin/env bash
#
# MySuricates — Fresh-Machine-Bootstrap (Linux)
#
# Prüft alle Voraussetzungen für den From-Source-Betrieb auf dem Host,
# nennt für Fehlendes die passenden Install-Befehle und bereitet das Repo
# zum Start vor (Git-Hooks, Frontend-Deps, Backend-Vorkompilierung).
#
# Bewusst KEINE Magie: ohne --install wird nichts installiert, nur geprüft
# und angeleitet. Greift nie auf die SQLite-DB oder die REST-API zu.
#
# Usage:
#   ./scripts/setup.sh                # nur prüfen + vorbereiten
#   ./scripts/setup.sh --install      # fehlende Tools per Paketmanager installieren (apt: voll, sonst Hinweise)
#   ./scripts/setup.sh --start        # nach erfolgreichem Setup direkt starten
#   ./scripts/setup.sh --show-key     # API-Key voll anzeigen (Default: maskiert)
#   ./scripts/setup.sh --preset core  # Modul-Preset ohne Rückfrage: core | research | full
#   ./scripts/setup.sh --lang de      # Sprache der Modultexte: de | en (Default: $LANG, sonst en)
#   ./scripts/setup.sh --install --start
#   ./scripts/setup.sh --help
#
# Das Modul-Preset landet in ai-automation-backend/config/application.yml — nur wenn die
# Datei noch nicht existiert. Ohne --preset wird im Terminal gefragt, sonst gilt core.
# --lang ändert nur die Anzeige der Modultexte, nie das Ergebnis; unbekannte Sprache → en.
#
# set -e bewusst NICHT: der Checker probiert absichtlich evtl. fehlende Tools.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/ai-automation-backend"
FRONTEND_DIR="${REPO_ROOT}/ai-automation-frontend"
# shellcheck source=lib/module-preset.sh
source "${SCRIPT_DIR}/lib/module-preset.sh"

DO_INSTALL=0
DO_START=0
SHOW_KEY=0
PRESET=""
LANG_OPT=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --start)   DO_START=1 ;;
    --show-key) SHOW_KEY=1 ;;
    --preset)  PRESET="${2:-}"; shift ;;
    --preset=*) PRESET="${1#--preset=}" ;;
    --lang)    LANG_OPT="${2:-}"; shift ;;
    --lang=*)  LANG_OPT="${1#--lang=}" ;;
    -h|--help)
      sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Unbekannte Option: $1 (siehe --help)" >&2; exit 2 ;;
  esac
  shift
done
if [ -n "$PRESET" ] && ! module_preset_valid "$PRESET"; then
  echo "Unbekanntes Preset: $PRESET (erlaubt: ${MODULE_PRESETS})" >&2
  exit 2
fi
# Anzeigesprache der Modultexte: --lang, sonst $LANG-Präfix, sonst en. Kein Fehlerfall —
# eine Sprache ohne eigene JSON-Datei zeigt einfach die englischen Texte.
TEXT_LANG="$(module_text_lang "$LANG_OPT")"

# ---------------------------------------------------------------------------
# Ausgabe-Helfer (Farben nur im TTY)
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLU=$'\033[36m'; C_BLD=$'\033[1m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_BLD=""; C_RST=""
fi
ok()    { echo "  ${C_GRN}✓${C_RST} $*"; }
warn()  { echo "  ${C_YEL}!${C_RST} $*"; }
fail()  { echo "  ${C_RED}✗${C_RST} $*"; }
head2() { echo; echo "${C_BLD}$*${C_RST}"; }

# Gesammelte Probleme (Build-blockierend) und KI-/optionale Hinweise
MISSING_BUILD=()   # blockiert Build/Start
AI_NOTES=()        # claude / KI
OPT_NOTES=()       # docker / optional
INSTALL_CMDS=()    # für --install (apt)

# ---------------------------------------------------------------------------
# Paketmanager erkennen
# ---------------------------------------------------------------------------
PKG=""
if   command -v apt-get >/dev/null 2>&1; then PKG="apt"
elif command -v dnf     >/dev/null 2>&1; then PKG="dnf"
elif command -v pacman  >/dev/null 2>&1; then PKG="pacman"
elif command -v zypper  >/dev/null 2>&1; then PKG="zypper"
fi

# Install-Hinweis pro Tool, abhängig vom Paketmanager
hint_for() {
  case "$1:$PKG" in
    java:apt)    echo "sudo apt-get install -y openjdk-21-jdk   # ältere Ubuntu: Adoptium-Repo (siehe https://adoptium.net)" ;;
    java:dnf)    echo "sudo dnf install -y java-21-openjdk-devel" ;;
    java:pacman) echo "sudo pacman -S --noconfirm jdk21-openjdk" ;;
    java:zypper) echo "sudo zypper install -y java-21-openjdk-devel" ;;
    java:*)      echo "Java 21 (JDK) installieren — z. B. via https://adoptium.net" ;;

    maven:apt)    echo "sudo apt-get install -y maven   # Distro-Maven (3.6+) reicht; 3.9+ optional von https://maven.apache.org/download.cgi" ;;
    maven:dnf)    echo "sudo dnf install -y maven" ;;
    maven:pacman) echo "sudo pacman -S --noconfirm maven" ;;
    maven:zypper) echo "sudo zypper install -y maven" ;;
    maven:*)      echo "Maven 3.9+ installieren — https://maven.apache.org/download.cgi" ;;

    node:apt)    echo "curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash - && sudo apt-get install -y nodejs" ;;
    node:dnf)    echo "sudo dnf install -y nodejs npm   # bei <20.19: NodeSource-Repo nutzen" ;;
    node:pacman) echo "sudo pacman -S --noconfirm nodejs npm" ;;
    node:zypper) echo "sudo zypper install -y nodejs20 npm20" ;;
    node:*)      echo "Node.js 24 (oder 20.19+/22.12+) installieren — https://nodejs.org (oder nvm)" ;;

    git:apt)    echo "sudo apt-get install -y git" ;;
    git:dnf)    echo "sudo dnf install -y git" ;;
    git:pacman) echo "sudo pacman -S --noconfirm git" ;;
    git:*)      echo "git installieren" ;;

    curl:apt)    echo "sudo apt-get install -y curl" ;;
    curl:dnf)    echo "sudo dnf install -y curl" ;;
    curl:pacman) echo "sudo pacman -S --noconfirm curl" ;;
    curl:*)      echo "curl installieren" ;;

    docker:apt)    echo "curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker \$USER   # danach neu einloggen" ;;
    docker:dnf)    echo "sudo dnf install -y docker && sudo systemctl enable --now docker && sudo usermod -aG docker \$USER" ;;
    docker:pacman) echo "sudo pacman -S --noconfirm docker && sudo systemctl enable --now docker && sudo usermod -aG docker \$USER" ;;
    docker:*)      echo "Docker 24+ installieren — https://docs.docker.com/engine/install/" ;;

    claude:*) echo "npm install -g @anthropic-ai/claude-code   &&   claude login" ;;
    *) echo "" ;;
  esac
}

# apt-Install-Kommando pro Tool (nur apt automatisierbar)
apt_install_for() {
  case "$1" in
    java)   echo "sudo apt-get install -y openjdk-21-jdk" ;;
    maven)  echo "sudo apt-get install -y maven" ;;
    node)   echo "curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash - && sudo apt-get install -y nodejs" ;;
    git)    echo "sudo apt-get install -y git" ;;
    curl)   echo "sudo apt-get install -y curl" ;;
    *) echo "" ;;
  esac
}

# ---------------------------------------------------------------------------
# Versions-Helfer
# ---------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

# $1 >= $2 (semantischer Vergleich via sort -V)
version_ge() {
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)" = "$2" ]
}

# Build-blockierendes Problem registrieren
need_build() { # tool, human-message
  MISSING_BUILD+=("$1")
  fail "$2"
  local h; h="$(hint_for "$1")"
  [ -n "$h" ] && echo "      → ${C_BLU}${h}${C_RST}"
  local c; c="$(apt_install_for "$1")"
  [ -n "$c" ] && INSTALL_CMDS+=("$c")
}

# ---------------------------------------------------------------------------
head2 "MySuricates Setup — Voraussetzungen prüfen (Linux, Paketmanager: ${PKG:-unbekannt})"

# --- git ---
if have git; then ok "git $(git --version | awk '{print $3}')"; else need_build git "git fehlt"; fi

# --- curl ---
if have curl; then ok "curl vorhanden"; else need_build curl "curl fehlt"; fi

# --- Java 21+ ---
if have java; then
  JAVA_VER="$(java -version 2>&1 | awk -F'"' '/version/{print $2; exit}')"
  JAVA_MAJOR="${JAVA_VER%%.*}"
  [ "$JAVA_MAJOR" = "1" ] && JAVA_MAJOR="$(echo "$JAVA_VER" | awk -F. '{print $2}')"
  if [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && [ "$JAVA_MAJOR" -ge 21 ]; then
    ok "Java ${JAVA_VER}"
  else
    need_build java "Java ${JAVA_VER:-?} gefunden, benötigt wird 21+"
  fi
else
  need_build java "Java (JDK 21+) fehlt"
fi

# --- Maven: Wrapper (mvnw) bevorzugt, sonst System-Maven 3.6.3+ ---
# Mit Wrapper ist System-Maven NICHT erforderlich (mvnw lädt Maven selbst, nur Java + curl nötig).
# -B + ANSI-Strip beim Parsen: Maven 3.8+ färbt die Versionsausgabe ein.
if [ -x "$BACKEND_DIR/mvnw" ]; then
  MVN_CMD="$BACKEND_DIR/mvnw"
  ok "Maven-Wrapper (mvnw) vorhanden — lädt Maven selbst, System-Maven optional"
  if have mvn; then
    MVN_VER="$(mvn -B -version 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | awk '/Apache Maven/{print $3; exit}')"
    [ -n "$MVN_VER" ] && ok "System-Maven ${MVN_VER} (zusätzlich vorhanden)"
  fi
elif have mvn; then
  MVN_CMD="mvn"
  MVN_VER="$(mvn -B -version 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | awk '/Apache Maven/{print $3; exit}')"
  if [ -n "$MVN_VER" ] && version_ge "$MVN_VER" "3.6.3"; then
    if version_ge "$MVN_VER" "3.9"; then ok "Maven ${MVN_VER}"; else ok "Maven ${MVN_VER} (3.9+ empfohlen)"; fi
  else
    need_build maven "Maven ${MVN_VER:-?} gefunden, benötigt wird 3.6.3+"
  fi
else
  MVN_CMD="mvn"
  need_build maven "Maven (3.6.3+, 3.9+ empfohlen) fehlt — oder den Wrapper ai-automation-backend/mvnw nutzen"
fi

# --- Node 20.19+ / 22.12+ / 24+ (Vite 7 und jsdom verlangen das; 21 und 23 sind ausgeschlossen) ---
node_supported() {  # $1 = version ohne v
  local major minor
  major="${1%%.*}"; minor="${1#*.}"; minor="${minor%%.*}"
  [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]] || return 1
  { [ "$major" -eq 20 ] && [ "$minor" -ge 19 ]; } \
    || { [ "$major" -eq 22 ] && [ "$minor" -ge 12 ]; } \
    || [ "$major" -ge 24 ]
}
if have node; then
  NODE_VER="$(node -v 2>/dev/null | tr -d 'v')"
  if node_supported "$NODE_VER"; then
    ok "Node ${NODE_VER}"
  else
    need_build node "Node ${NODE_VER:-?} gefunden, benötigt wird 20.19+, 22.12+ oder 24+"
  fi
else
  need_build node "Node.js (20.19+, 22.12+ oder 24+) fehlt"
fi

# --- npm 10+ ---
if have npm; then
  NPM_VER="$(npm -v 2>/dev/null)"
  NPM_MAJOR="${NPM_VER%%.*}"
  if [[ "$NPM_MAJOR" =~ ^[0-9]+$ ]] && [ "$NPM_MAJOR" -ge 10 ]; then
    ok "npm ${NPM_VER}"
  else
    warn "npm ${NPM_VER:-?} gefunden, empfohlen 10+ (kommt i. d. R. mit Node 20+)"
  fi
else
  : # npm fehlt -> wird mit Node mitgemeldet
fi

# ---------------------------------------------------------------------------
head2 "KI-Anbindung (für AI-Workflows erforderlich)"
if have claude; then
  CLAUDE_VER="$(claude --version 2>/dev/null | head -n1)"
  ok "claude CLI vorhanden (${CLAUDE_VER:-Version unbekannt})"
  if [ -f "$HOME/.claude/.credentials.json" ] || { [ -f "$HOME/.claude.json" ] && grep -q '"oauthAccount"\|"account"\|accessToken' "$HOME/.claude.json" 2>/dev/null; }; then
    ok "claude scheint eingeloggt"
  else
    warn "claude ist evtl. NICHT eingeloggt — bitte ausführen: ${C_BLU}claude login${C_RST}"
    AI_NOTES+=("claude login ausführen")
  fi
else
  warn "claude CLI fehlt — AI-Workflows funktionieren erst nach Installation + Login"
  echo "      → ${C_BLU}$(hint_for claude)${C_RST}"
  AI_NOTES+=("claude CLI installieren + claude login")
fi

# ---------------------------------------------------------------------------
head2 "Docker (optional — nur für Container-Execution-Workflow-Blöcke)"
if have docker; then
  DOCKER_VER="$(docker --version 2>/dev/null | awk '{print $3}' | tr -d ',')"
  ok "docker ${DOCKER_VER:-vorhanden}"
  if docker info >/dev/null 2>&1; then
    ok "Docker-Daemon erreichbar"
  else
    warn "Docker installiert, aber Daemon nicht erreichbar (Rechte? gestartet?) — ${C_BLU}sudo usermod -aG docker \$USER${C_RST}, dann neu einloggen"
    OPT_NOTES+=("Docker-Daemon erreichbar machen (nur für Container-Blöcke)")
  fi
else
  warn "docker fehlt — App + Nicht-Container-Workflows laufen trotzdem; nur Container-Execution-Blöcke brauchen Docker"
  echo "      → ${C_BLU}$(hint_for docker)${C_RST}"
  OPT_NOTES+=("Docker installieren (nur für Container-Blöcke nötig)")
fi

# ---------------------------------------------------------------------------
# Optional: Auto-Install (nur apt) für Build-blockierende Tools
# ---------------------------------------------------------------------------
if [ "${#MISSING_BUILD[@]}" -gt 0 ] && [ "$DO_INSTALL" -eq 1 ]; then
  if [ "$PKG" = "apt" ]; then
    head2 "--install: fehlende Build-Tools per apt installieren"
    sudo apt-get update
    for cmd in "${INSTALL_CMDS[@]}"; do
      echo "  ${C_BLU}\$ ${cmd}${C_RST}"
      bash -c "$cmd" || warn "Befehl fehlgeschlagen: $cmd"
    done
    echo
    warn "Bitte ${C_BLD}./scripts/setup.sh${C_RST} erneut ausführen, um die Installation zu verifizieren."
    exit 1
  else
    head2 "--install"
    warn "Automatische Installation ist nur für apt umgesetzt. Bitte die Hinweise oben (→) manuell ausführen."
  fi
fi

# ---------------------------------------------------------------------------
# Abbruch, wenn Build-blockierende Tools fehlen
# ---------------------------------------------------------------------------
if [ "${#MISSING_BUILD[@]}" -gt 0 ]; then
  head2 "${C_RED}Setup unvollständig${C_RST}"
  echo "  Fehlende/zu alte Pflicht-Tools: ${C_BLD}${MISSING_BUILD[*]}${C_RST}"
  echo "  Bitte oben genannte Befehle ausführen (oder: ${C_BLD}./scripts/setup.sh --install${C_RST} auf apt-Systemen),"
  echo "  danach ${C_BLD}./scripts/setup.sh${C_RST} erneut starten."
  exit 1
fi

# ---------------------------------------------------------------------------
# Repo vorbereiten
# ---------------------------------------------------------------------------
head2 "Repo vorbereiten"

echo "  Git-Hooks installieren ..."
if bash "${SCRIPT_DIR}/install-hooks.sh" >/dev/null 2>&1; then
  ok "Git-Hooks installiert"
else
  warn "Git-Hooks-Installation übersprungen (kein Git-Repo?)"
fi

echo "  Frontend-Abhängigkeiten installieren (npm install) ..."
if ( cd "$FRONTEND_DIR" && npm install ); then
  ok "Frontend-Deps installiert"
else
  fail "npm install fehlgeschlagen — siehe Ausgabe oben"
  exit 1
fi

echo "  Backend vorkompilieren (${MVN_CMD##*/} test-compile) ..."
if ( cd "$BACKEND_DIR" && "$MVN_CMD" -q -DskipTests test-compile ); then
  ok "Backend kompiliert"
else
  fail "Backend-Kompilierung fehlgeschlagen — siehe Ausgabe oben"
  exit 1
fi

# ---------------------------------------------------------------------------
# Module (nur beim Start gelesen, nie per API umschaltbar)
# ---------------------------------------------------------------------------
head2 "Module"
LOCAL_CONFIG="${BACKEND_DIR}/config/application.yml"
if [ -e "$LOCAL_CONFIG" ]; then
  ok "config/application.yml vorhanden — Modul-Konfiguration bleibt unverändert"
  [ -n "$PRESET" ] && warn "--preset ${PRESET} ignoriert; Preset dort unter automation.modules.preset anpassen"
else
  if [ -z "$PRESET" ]; then
    if [[ -t 0 && -t 1 ]]; then
      PRESET="$(module_preset_prompt core "$TEXT_LANG")"
    else
      PRESET="core"
    fi
  fi
  if module_preset_write "$LOCAL_CONFIG" "$PRESET"; then
    ok "Modul-Preset ${C_BLD}${PRESET}${C_RST} → ai-automation-backend/config/application.yml"
    echo "      Später ändern: Datei bearbeiten und Backend neu starten (Übersicht: Settings → Module & Härtung)"
  else
    warn "config/application.yml konnte nicht geschrieben werden — Backend startet mit Preset core"
  fi
fi

# ---------------------------------------------------------------------------
# Zusammenfassung
# ---------------------------------------------------------------------------
head2 "${C_GRN}Setup abgeschlossen${C_RST}"
echo "  Starten:   ${C_BLD}./scripts/start-autotrigger-dev.sh${C_RST}"
echo "  Frontend:  http://localhost:3000   (Backend-API: http://localhost:9090/api/v1)"
KEY_FILE="$HOME/.autotrigger/api-key"
if [ "${AUTOMATION_SECURITY_ENABLED:-true}" = "false" ]; then
  echo "  API-Key:   nicht nötig (AUTOMATION_SECURITY_ENABLED=false)"
elif [ -n "${AUTOMATION_API_KEY:-}" ]; then
  echo "  API-Key:   eigener via ${C_BLD}AUTOMATION_API_KEY${C_RST} gesetzt"
elif [ -f "$KEY_FILE" ]; then
  k="$(cat "$KEY_FILE")"
  if [ "$SHOW_KEY" -eq 1 ]; then
    echo "  API-Key:   ${C_BLD}${k}${C_RST}"
  else
    echo "  API-Key:   …${k: -4}   (maskiert — voll mit ${C_BLD}--show-key${C_RST})"
  fi
  echo "             (aus ${KEY_FILE} — beim ersten Öffnen ins Browser-Login einfügen)"
else
  echo "  API-Key:   wird beim ersten Backend-Start erzeugt → ${C_BLD}~/.autotrigger/api-key${C_RST}"
  echo "             (erscheint dann automatisch beim Start; auch im Backend-Log)"
fi
echo "  HTTPS:     optional via ${C_BLD}./scripts/generate-dev-cert.sh${C_RST} (selbst-signiert → Browser-Warnung)"
echo "  Anleitung: ${C_BLD}docs/GETTING-STARTED.md${C_RST}"

if [ "${#AI_NOTES[@]}" -gt 0 ]; then
  echo
  echo "  ${C_YEL}Für AI-Workflows noch offen:${C_RST}"
  for n in "${AI_NOTES[@]}"; do echo "    - $n"; done
fi
if [ "${#OPT_NOTES[@]}" -gt 0 ]; then
  echo
  echo "  ${C_YEL}Optional:${C_RST}"
  for n in "${OPT_NOTES[@]}"; do echo "    - $n"; done
fi

if [ "$DO_START" -eq 1 ]; then
  head2 "Starte Dev-Umgebung ..."
  if [ "$SHOW_KEY" -eq 1 ]; then
    exec bash "${SCRIPT_DIR}/start-autotrigger-dev.sh" --show-key
  else
    exec bash "${SCRIPT_DIR}/start-autotrigger-dev.sh"
  fi
fi
