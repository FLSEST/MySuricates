#!/usr/bin/env bash
# Abhaengigkeiten des Installers (504e5038): welche Werkzeuge ein Weg braucht, ob die vorhandenen
# reichen, wie der Paketmanager sie installiert — und wo er es nicht kann, ein geprueftes Download.
#
# Quelle fuer Java/Node „gemischt“ (Spec, Abschnitt 6): Paketmanager, wenn dessen Version passt;
# sonst das offizielle Archiv mit fest eingetragener SHA-256 nach ~/.local/share/mysuricates/, ohne
# sudo, auf Linux und macOS gleich. Befehle werden hier nur zusammengesetzt — ausgefuehrt und vorher
# angezeigt werden sie in setup.sh. bash 3.2 (macOS): keine assoziativen Arrays.

DEPS_DATA="${XDG_DATA_HOME:-$HOME/.local/share}/mysuricates"

# Fest eingetragene Downloads — beim Anheben Version, URL-Muster und alle vier Pruefsummen tauschen
# (Quellen: nodejs.org/dist/v<version>/SHASUMS256.txt, api.adoptium.net/v3/assets/latest/21/hotspot).
DEPS_NODE_VERSION="24.21.0"
DEPS_NODE_SHA256_linux_x64="6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff"
DEPS_NODE_SHA256_linux_arm64="724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5"
DEPS_NODE_SHA256_darwin_x64="1462cb3b3046b815cf8ea436d3da450ec1a9f11dac7e5a46b0ada5305d7e8097"
DEPS_NODE_SHA256_darwin_arm64="bed7eea5325e1108f32ce5228ddd6a5f0f08a499ee42aa7442aea583702f6057"
DEPS_JDK_RELEASE="21.0.12.1+1"
DEPS_JDK_SHA256_linux_x64="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"
DEPS_JDK_SHA256_linux_arm64="23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223"
DEPS_JDK_SHA256_darwin_x64="44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce"
DEPS_JDK_SHA256_darwin_arm64="3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998"

# --- System -----------------------------------------------------------------------------------------

deps_os() { case "$(uname -s)" in Darwin) echo darwin ;; *) echo linux ;; esac; }
deps_arch() { case "$(uname -m)" in x86_64|amd64) echo x64 ;; aarch64|arm64) echo arm64 ;; *) uname -m ;; esac; }

deps_pm() {
  if [ "$(deps_os)" = darwin ]; then command -v brew >/dev/null 2>&1 && echo brew || echo none; return; fi
  if command -v apt-get >/dev/null 2>&1; then echo apt
  elif command -v dnf >/dev/null 2>&1; then echo dnf
  elif command -v pacman >/dev/null 2>&1; then echo pacman
  elif command -v zypper >/dev/null 2>&1; then echo zypper
  elif command -v brew >/dev/null 2>&1; then echo brew
  else echo none; fi
}

# --- Versionen --------------------------------------------------------------------------------------

deps_version() {
  case "$1" in
    git)     git --version 2>/dev/null | awk '{print $3}' ;;
    curl)    curl --version 2>/dev/null | awk 'NR==1{print $2}' ;;
    java)    java -version 2>&1 | awk -F'"' '/version/{print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }' ;;
    node)    node -v 2>/dev/null | tr -d v ;;
    npm)     npm -v 2>/dev/null ;;
    python3) python3 -c 'import platform; print(platform.python_version())' 2>/dev/null ;;
    docker)  docker --version 2>/dev/null | awk '{print $3}' | tr -d , ;;
  esac
}

# Node: 20.19+, 22.12+ oder ab 24 (Vite 7/jsdom); 21 und 23 sind ausgeschlossen
deps_node_ok() {
  local major minor
  major="${1%%.*}"; minor="${1#*.}"; minor="${minor%%.*}"
  case "$major$minor" in *[!0-9]*|"") return 1 ;; esac
  { [ "$major" -eq 20 ] && [ "$minor" -ge 19 ]; } || { [ "$major" -eq 22 ] && [ "$minor" -ge 12 ]; } || [ "$major" -ge 24 ]
}

# deps_check TOOL → "ok VERSION" | "old VERSION" | "missing"
deps_check() {
  local v
  command -v "$1" >/dev/null 2>&1 || { echo missing; return; }
  v="$(deps_version "$1")"
  case "$1" in
    java) case "$v" in ''|*[!0-9]*) echo "old ${v:-?}"; return ;; esac
          [ "$v" -ge 21 ] && echo "ok $v" || echo "old $v" ;;
    node) deps_node_ok "$v" && echo "ok $v" || echo "old ${v:-?}" ;;
    npm)  case "${v%%.*}" in ''|*[!0-9]*) echo "old ${v:-?}"; return ;; esac
          [ "${v%%.*}" -ge 10 ] && echo "ok $v" || echo "old $v" ;;
    *)    echo "ok ${v:-?}" ;;
  esac
}

# running | no-permission | not-running | missing
deps_docker_state() {
  local err
  command -v docker >/dev/null 2>&1 || { echo missing; return; }
  if err="$(docker info 2>&1 >/dev/null)"; then echo running; return; fi
  case "$err" in *[Pp]ermission\ denied*) echo no-permission ;; *) echo not-running ;; esac
}

deps_required() {
  case "$1" in
    container|staged) echo "git curl docker" ;;
    host)             echo "git curl java node npm" ;;
    source)           echo "git curl java node npm python3" ;;
  esac
}

# --- Paketmanager -----------------------------------------------------------------------------------

deps_pkg() {  # WERKZEUG MANAGER → Paketname(n)
  case "$1:$2" in
    git:*) echo git ;;
    curl:*) echo curl ;;
    python3:pacman) echo python ;;
    python3:brew) echo python@3 ;;
    python3:*) echo python3 ;;
    java:apt) echo openjdk-21-jdk ;;
    java:dnf|java:zypper) echo java-21-openjdk-devel ;;
    java:pacman) echo jdk21-openjdk ;;
    java:brew) echo openjdk@21 ;;
    node:brew) echo node ;;
    node:*|npm:*) echo "nodejs npm" ;;
    docker:pacman|docker:zypper) echo "docker docker-compose" ;;
  esac
}

deps_install_cmd() {  # MANAGER PAKET… → Befehl
  local pm="$1"; shift
  case "$pm" in
    apt)    echo "sudo apt-get install -y $*" ;;
    dnf)    echo "sudo dnf install -y $*" ;;
    pacman) echo "sudo pacman -S --needed --noconfirm $*" ;;
    zypper) echo "sudo zypper install -y $*" ;;
    brew)   echo "brew install $*" ;;
  esac
}

# Version, die der Paketmanager installieren wuerde (leer = keine / unbekannt)
deps_candidate() {  # PAKET MANAGER
  case "$2" in
    apt)    apt-cache policy "$1" 2>/dev/null | awk '/Candidate:/{print $2; exit}' | grep -v '(none)' ;;
    dnf)    dnf -q info "$1" 2>/dev/null | awk -F': *' '/^Version/{print $2; exit}' ;;
    pacman) pacman -Si "$1" 2>/dev/null | awk -F': *' '/^Version/{print $2; exit}' ;;
    zypper) zypper -q info "$1" 2>/dev/null | awk -F': *' '/^Version/{print $2; exit}' ;;
    brew)   echo current ;;
  esac | sed 's/^[0-9]*://'
}

# Wie kommt WERKZEUG auf diesen Rechner? → "pm BEFEHL" | "download BESCHREIBUNG" | "manual HINWEIS"
deps_method() {
  local tool="$1" pm os cand pkg
  pm="$(deps_pm)"; os="$(deps_os)"
  case "$tool" in
    docker)
      if [ "$os" = darwin ]; then echo "manual brew install --cask docker   (then start Docker Desktop)"; return; fi
      case "$pm" in
        apt|dnf) echo "pm curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $(id -un)" ;;
        pacman|zypper) echo "pm $(deps_install_cmd "$pm" $(deps_pkg docker "$pm")) && sudo systemctl enable --now docker && sudo usermod -aG docker $(id -un)" ;;
        *) echo "manual https://docs.docker.com/engine/install/" ;;
      esac
      return ;;
    java)
      pkg="$(deps_pkg java "$pm")"
      if [ -n "$pkg" ] && [ -n "$(deps_candidate "$pkg" "$pm")" ]; then echo "pm $(deps_install_cmd "$pm" "$pkg")"
      else echo "download Java $DEPS_JDK_RELEASE → $DEPS_DATA/jdk"; fi
      return ;;
    node|npm)
      if [ "$pm" = brew ]; then echo "pm $(deps_install_cmd brew node)"; return; fi
      cand="$(deps_candidate nodejs "$pm")"
      if [ -n "$pm" ] && [ "$pm" != none ] && deps_node_ok "${cand%%[-+~]*}"; then echo "pm $(deps_install_cmd "$pm" $(deps_pkg node "$pm"))"
      else echo "download Node $DEPS_NODE_VERSION → $DEPS_DATA/node"; fi
      return ;;
  esac
  pkg="$(deps_pkg "$tool" "$pm")"
  if [ -n "$pkg" ] && [ "$pm" != none ]; then echo "pm $(deps_install_cmd "$pm" "$pkg")"; else echo "manual install $tool"; fi
}

# deps_plan MODUS → je fehlendem/zu altem Werkzeug eine Zeile "werkzeug methode befehl"
deps_plan() {
  local tool state method node_download=0
  for tool in $(deps_required "$1"); do
    if [ "$tool" = docker ]; then
      [ "$(deps_docker_state)" = missing ] || continue
    else
      state="$(deps_check "$tool")"
      [ "${state%% *}" = ok ] && continue
    fi
    method="$(deps_method "$tool")"
    # npm kommt mit Node — kein eigener Eintrag, wenn Node ohnehin installiert wird
    if [ "$tool" = node ]; then node_download=1; fi
    if [ "$tool" = npm ] && [ "$node_download" = 1 ]; then continue; fi
    printf '%-7s %-8s %s\n' "$tool" "${method%% *}" "${method#* }"
  done
}

# --- Geprueftes Download ----------------------------------------------------------------------------

_deps_sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# deps_download node|java — laedt, prueft die SHA-256, entpackt nach $DEPS_DATA/<node|jdk>, schreibt
# env.sh. Test-Overrides: DEPS_NODE_URL/DEPS_NODE_SHA256, DEPS_JDK_URL/DEPS_JDK_SHA256.
deps_download() {
  local tool="$1" os arch url sum name dir tmp file got
  os="$(deps_os)"; arch="$(deps_arch)"
  case "$tool" in
    node)
      name="DEPS_NODE_SHA256_${os}_${arch}"
      url="${DEPS_NODE_URL:-https://nodejs.org/dist/v$DEPS_NODE_VERSION/node-v$DEPS_NODE_VERSION-$os-$arch.tar.gz}"
      sum="${DEPS_NODE_SHA256:-${!name-}}"; dir="$DEPS_DATA/node" ;;
    java)
      name="DEPS_JDK_SHA256_${os}_${arch}"
      local aos="$os" aarch="$arch" rel
      [ "$os" = darwin ] && aos=mac; [ "$arch" = arm64 ] && aarch=aarch64
      rel="$(printf '%s' "$DEPS_JDK_RELEASE" | tr '+' '_')"
      url="${DEPS_JDK_URL:-https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${DEPS_JDK_RELEASE%+*}%2B${DEPS_JDK_RELEASE#*+}/OpenJDK21U-jdk_${aarch}_${aos}_hotspot_${rel}.tar.gz}"
      sum="${DEPS_JDK_SHA256:-${!name-}}"; dir="$DEPS_DATA/jdk" ;;
    *) echo "deps_download: unbekannt: $tool" >&2; return 2 ;;
  esac
  [ -n "$sum" ] || { echo "deps_download: keine Pruefsumme fuer $tool auf $os/$arch" >&2; return 2; }
  mkdir -p "$DEPS_DATA" || return 1
  tmp="$(mktemp -d "$DEPS_DATA/.download.XXXXXX")" || return 1
  file="$tmp/archive.tar.gz"
  if ! curl -fsSL --retry 2 -o "$file" "$url"; then echo "deps_download: $url nicht ladbar" >&2; rm -rf "$tmp"; return 1; fi
  got="$(_deps_sha256 "$file")"
  if [ "$got" != "$sum" ]; then
    echo "deps_download: Pruefsumme stimmt nicht fuer $url (erwartet $sum, bekommen $got) — nichts installiert" >&2
    rm -rf "$tmp"; return 1
  fi
  mkdir -p "$tmp/x" && tar -xzf "$file" -C "$tmp/x" || { rm -rf "$tmp"; return 1; }
  rm -rf "$dir" && mv "$tmp/x/"* "$dir" && rm -rf "$tmp" || return 1
  deps_write_env
}

# env.sh: bringt heruntergeladene Werkzeuge auf den PATH (setup.sh und instance.sh lesen sie)
deps_write_env() {
  local out="$DEPS_DATA/env.sh" home=""
  {
    echo "# Erzeugt vom Installer (scripts/lib/deps.sh): heruntergeladene Werkzeuge auf den PATH."
    if [ -d "$DEPS_DATA/node/bin" ]; then echo "export PATH=\"$DEPS_DATA/node/bin:\$PATH\""; fi
    if [ -d "$DEPS_DATA/jdk/Contents/Home" ]; then home="$DEPS_DATA/jdk/Contents/Home"; elif [ -d "$DEPS_DATA/jdk/bin" ]; then home="$DEPS_DATA/jdk"; fi
    if [ -n "$home" ]; then echo "export JAVA_HOME=\"$home\""; echo "export PATH=\"$home/bin:\$PATH\""; fi
  } > "$out"
}

# --- Shell des Nutzers (2605ed1f) -------------------------------------------------------------------
# setup.sh und instance.sh laden env.sh selbst, die Shell des Nutzers nicht: ohne Eintrag im Profil
# fehlen dort das heruntergeladene node/npm und alles, was npm -g dorthin legt (codex, pi). Eingetragen
# wird in das Profil, das eine Login-Shell wirklich liest — bash nimmt ~/.bash_profile, wenn es die
# gibt (Rocky, Fedora), und ignoriert dann ~/.profile; zsh (macOS) liest ~/.zprofile.

deps_tilde() { case "$1" in "$HOME"/*) printf '~%s' "${1#"$HOME"}" ;; *) printf '%s' "$1" ;; esac; }

# Hat der Download etwas auf den PATH gelegt? (env.sh ohne export-Zeile bringt nichts)
deps_env_active() { grep -q '^export PATH=' "$DEPS_DATA/env.sh" 2>/dev/null; }

deps_login_shell() {
  local sh="${SHELL:-}"
  [ -n "$sh" ] || sh="$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f7)"
  [ -n "$sh" ] || { if [ "$(deps_os)" = darwin ]; then sh=zsh; else sh=bash; fi; }
  printf '%s' "${sh##*/}"
}

deps_profile_file() {
  case "$(deps_login_shell)" in
    zsh)  printf '%s' "${ZDOTDIR:-$HOME}/.zprofile" ;;
    bash) if [ -f "$HOME/.bash_profile" ]; then printf '%s' "$HOME/.bash_profile"
          elif [ -f "$HOME/.bash_login" ]; then printf '%s' "$HOME/.bash_login"
          else printf '%s' "$HOME/.profile"; fi ;;
    *)    printf '%s' "$HOME/.profile" ;;
  esac
}

# Laedt das Profil env.sh schon — in welcher Form auch immer?
deps_profile_has_env() { grep -qF "mysuricates/env.sh" "${1:-$(deps_profile_file)}" 2>/dev/null; }

# Die Zeile zum Abschreiben, und die, die der Installer eintraegt: sie bricht die Anmeldung nicht, wenn
# ~/.local/share/mysuricates spaeter geloescht wird.
deps_profile_hint() { printf '. %s' "$(deps_tilde "$DEPS_DATA/env.sh")"; }
deps_profile_line() {
  local f="$DEPS_DATA/env.sh"
  case "$f" in "$HOME"/*) f="\$HOME${f#"$HOME"}" ;; esac
  printf '[ -r "%s" ] && . "%s"' "$f" "$f"
}

# deps_profile_add [PROFIL] — traegt die Zeile ein, wenn das Profil env.sh noch nicht laedt (idempotent)
deps_profile_add() {
  local p="${1:-$(deps_profile_file)}"
  deps_profile_has_env "$p" && return 0
  {
    if [ -s "$p" ] && [ -n "$(tail -c 1 "$p")" ]; then echo; fi   # letzte Zeile ohne Zeilenende
    echo
    echo "# MySuricates: vom Installer heruntergeladene Werkzeuge (Node, npm, Java) und npm-Programme wie pi"
    deps_profile_line; echo
  } >> "$p"
}

# --- npm -g -----------------------------------------------------------------------------------------

_deps_can_write() {  # VERZEICHNIS — schreibbar, oder im naechsten vorhandenen Elternordner anlegbar
  local d="$1"
  while [ -n "$d" ] && [ "$d" != / ] && [ ! -e "$d" ]; do d="$(dirname "$d")"; done
  [ -d "$d" ] && [ -w "$d" ]
}

# Braucht `npm install -g` sudo? Nein beim heruntergeladenen Node ($DEPS_DATA/node) und bei Homebrew,
# ja beim Node aus apt/dnf — dessen globales Verzeichnis gehoert root (0a078449).
deps_npm_needs_sudo() {
  local prefix
  prefix="$(npm prefix -g 2>/dev/null)"
  [ -n "$prefix" ] || return 1
  if _deps_can_write "$prefix/lib/node_modules" && _deps_can_write "$prefix/bin"; then return 1; fi
  return 0
}
