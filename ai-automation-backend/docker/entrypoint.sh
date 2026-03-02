#!/bin/bash
set -e

APP_USER="${APP_USER:-flo}"

# Fix Docker socket permissions if socket is mounted
if [ -S /var/run/docker.sock ]; then
  DOCKER_GID=$(stat -c '%g' /var/run/docker.sock)
  if ! getent group "$DOCKER_GID" >/dev/null 2>&1; then
    groupadd -g "$DOCKER_GID" dockerhost
  fi
  GROUP_NAME=$(getent group "$DOCKER_GID" | cut -d: -f1)
  usermod -aG "$GROUP_NAME" "$APP_USER" 2>/dev/null || true
fi

exec gosu "$APP_USER" "$@"
