#!/bin/bash
set -e

APP_USER="${APP_USER:-appuser}"

# Fix Docker socket permissions if socket is mounted
if [ -S /var/run/docker.sock ]; then
  DOCKER_GID=$(stat -c '%g' /var/run/docker.sock)
  if ! getent group "$DOCKER_GID" >/dev/null 2>&1; then
    groupadd -g "$DOCKER_GID" dockerhost
  fi
  GROUP_NAME=$(getent group "$DOCKER_GID" | cut -d: -f1)
  usermod -aG "$GROUP_NAME" "$APP_USER" 2>/dev/null || true
fi

# Own provider directories on the data volume (scripts/lib/host-mounts.sh sets them for the modes
# programs and login): the directories belong to the app user; login files of the host mounted into
# them keep their owner (262948a4).
for d in "${CLAUDE_CONFIG_DIR:-}" "${CODEX_HOME:-}" "${PI_CODING_AGENT_DIR:-}"; do
  [ -n "$d" ] || continue
  mkdir -p "$d"
  chown "${APP_USER}:${APP_USER}" "$(dirname "$d")" "$d"
done
# Claude starts without questions once its state file exists; it writes the rest itself.
if [ -n "${CLAUDE_CONFIG_DIR:-}" ] && [ ! -e "$CLAUDE_CONFIG_DIR/.claude.json" ]; then
  printf '{"hasCompletedOnboarding":true}\n' > "$CLAUDE_CONFIG_DIR/.claude.json"
  chown "${APP_USER}:${APP_USER}" "$CLAUDE_CONFIG_DIR/.claude.json"
fi

# Mode all only: the Claude CLI needs a WRITABLE ~/.claude.json (startup state:
# numStartups, project trust, cache). The host's file is bind-mounted read-only at
# /seed/.claude.json — copy it to a container-local, writable path on start.
if [ -f /seed/.claude.json ]; then
  cp /seed/.claude.json "${HOME}/.claude.json"
  chown "${APP_USER}:${APP_USER}" "${HOME}/.claude.json"
fi

exec gosu "$APP_USER" "$@"
