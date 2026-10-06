#!/bin/bash
# Entrypoint for deploy/Dockerfile.
#
# The user is only created at start from HOST_UID/HOST_GID/HOST_USER/HOST_HOME (scripts/instance.sh
# sets them from id(1)). That way files in mounted folders belong to the right user — even
# if they do not have UID 1000 (bash does not export UID; Stable/Test therefore always took 1000,
# fc9de67b). HOME points to the same path as on the host so that mounted logins
# (~/.claude, ~/.codex, ~/.pi) and project paths from the DB match.
set -e
uid="${HOST_UID:-1000}"
gid="${HOST_GID:-1000}"
user="${HOST_USER:-mysuricates}"
home="${HOST_HOME:-/home/mysuricates}"

getent group "$gid" >/dev/null || groupadd -g "$gid" "$user"
existing="$(getent passwd "$uid" | cut -d: -f1 || true)"
if [ -n "$existing" ]; then
  # The base image already brings a user for UID 1000 (ubuntu) — we use that one.
  user="$existing"
  usermod -d "$home" "$user" >/dev/null 2>&1 || true
else
  useradd -u "$uid" -g "$gid" -d "$home" -M -s /bin/bash "$user"
fi
mkdir -p "$home/.local/bin"
chown "$uid:$gid" "$home" "$home/.local" "$home/.local/bin" 2>/dev/null || true
chown -R "$uid:$gid" /app/secrets
# Files of the host may be mounted under /app/data/providers (mode login: the auth.json
# of Codex and Pi). They stay untouched — otherwise a start with the wrong UID (e.g. via
# sudo) would rewrite the owner of the login on the host (262948a4).
find /app/data -path /app/data/providers -prune -o -exec chown "$uid:$gid" {} +

# Own provider directories (scripts/lib/host-mounts.sh sets them for programs and login): the
# directories belong to the user, mounted login files in them stay as they are.
for d in "${CLAUDE_CONFIG_DIR:-}" "${CODEX_HOME:-}" "${PI_CODING_AGENT_DIR:-}"; do
  [ -n "$d" ] || continue
  mkdir -p "$d"
  chown "$uid:$gid" "$(dirname "$d")" "$d"
done
# Claude starts without questions if its state file is there; it writes the rest itself.
if [ -n "${CLAUDE_CONFIG_DIR:-}" ] && [ ! -e "$CLAUDE_CONFIG_DIR/.claude.json" ]; then
  printf '{"hasCompletedOnboarding":true}\n' > "$CLAUDE_CONFIG_DIR/.claude.json"
  chown "$uid:$gid" "$CLAUDE_CONFIG_DIR/.claude.json"
fi

# Docker socket only if mounted: give its group to the user
if [ -S /var/run/docker.sock ]; then
  sock_gid="$(stat -c '%g' /var/run/docker.sock)"
  getent group "$sock_gid" >/dev/null || groupadd -g "$sock_gid" dockerhost
  usermod -aG "$(getent group "$sock_gid" | cut -d: -f1)" "$user" >/dev/null 2>&1 || true
fi

# Only in mode all: Claude needs a WRITABLE ~/.claude.json, the host's one comes read-only
# to /seed
if [ -f /seed/.claude.json ]; then
  cp /seed/.claude.json "$home/.claude.json"
  chown "$uid:$gid" "$home/.claude.json"
fi

export HOME="$home" PATH="$home/.local/bin:$PATH"
exec gosu "$uid:$gid" "$@"
