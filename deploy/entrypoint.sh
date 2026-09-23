#!/bin/bash
# Entrypoint fuer deploy/Dockerfile.
#
# Der Benutzer entsteht erst beim Start aus HOST_UID/HOST_GID/HOST_USER/HOST_HOME (scripts/instance.sh
# setzt sie aus id(1)). So gehoeren Dateien in eingebundenen Ordnern dem richtigen Benutzer — auch
# wenn er nicht UID 1000 hat (bash exportiert UID nicht; Stable/Test nahmen deshalb immer 1000,
# fc9de67b). HOME zeigt auf denselben Pfad wie auf dem Host, damit eingebundene Anmeldungen
# (~/.claude, ~/.codex, ~/.pi) und Projektpfade aus der DB stimmen.
set -e
uid="${HOST_UID:-1000}"
gid="${HOST_GID:-1000}"
user="${HOST_USER:-mysuricates}"
home="${HOST_HOME:-/home/mysuricates}"

getent group "$gid" >/dev/null || groupadd -g "$gid" "$user"
existing="$(getent passwd "$uid" | cut -d: -f1 || true)"
if [ -n "$existing" ]; then
  # Das Basis-Image bringt fuer UID 1000 bereits einen Benutzer mit (ubuntu) — den nehmen wir.
  user="$existing"
  usermod -d "$home" "$user" >/dev/null 2>&1 || true
else
  useradd -u "$uid" -g "$gid" -d "$home" -M -s /bin/bash "$user"
fi
mkdir -p "$home/.local/bin"
chown "$uid:$gid" "$home" "$home/.local" "$home/.local/bin" 2>/dev/null || true
chown -R "$uid:$gid" /app/secrets
# Unter /app/data/providers koennen Dateien des Hosts eingebunden sein (Modus login: die auth.json
# von Codex und Pi). Die bleiben unangetastet — sonst schriebe ein Start mit falscher UID (etwa per
# sudo) den Besitzer der Anmeldung auf dem Host um (262948a4).
find /app/data -path /app/data/providers -prune -o -exec chown "$uid:$gid" {} +

# Eigene Provider-Verzeichnisse (scripts/lib/host-mounts.sh setzt sie fuer programs und login): die
# Verzeichnisse gehoeren dem Benutzer, eingebundene Anmeldedateien darin bleiben, wie sie sind.
for d in "${CLAUDE_CONFIG_DIR:-}" "${CODEX_HOME:-}" "${PI_CODING_AGENT_DIR:-}"; do
  [ -n "$d" ] || continue
  mkdir -p "$d"
  chown "$uid:$gid" "$(dirname "$d")" "$d"
done
# Claude startet ohne Rueckfragen, wenn seine Zustandsdatei da ist; den Rest schreibt es selbst.
if [ -n "${CLAUDE_CONFIG_DIR:-}" ] && [ ! -e "$CLAUDE_CONFIG_DIR/.claude.json" ]; then
  printf '{"hasCompletedOnboarding":true}\n' > "$CLAUDE_CONFIG_DIR/.claude.json"
  chown "$uid:$gid" "$CLAUDE_CONFIG_DIR/.claude.json"
fi

# Docker-Socket nur, wenn eingebunden: dessen Gruppe dem Benutzer geben
if [ -S /var/run/docker.sock ]; then
  sock_gid="$(stat -c '%g' /var/run/docker.sock)"
  getent group "$sock_gid" >/dev/null || groupadd -g "$sock_gid" dockerhost
  usermod -aG "$(getent group "$sock_gid" | cut -d: -f1)" "$user" >/dev/null 2>&1 || true
fi

# Nur im Modus all: Claude braucht eine BESCHREIBBARE ~/.claude.json, die des Hosts kommt read-only
# nach /seed
if [ -f /seed/.claude.json ]; then
  cp /seed/.claude.json "$home/.claude.json"
  chown "$uid:$gid" "$home/.claude.json"
fi

export HOME="$home" PATH="$home/.local/bin:$PATH"
exec gosu "$uid:$gid" "$@"
