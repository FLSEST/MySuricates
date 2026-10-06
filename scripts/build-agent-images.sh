#!/usr/bin/env bash
#
# build-agent-images.sh — builds the agent images (automation-agent-*) and labels each with the
# sha256 of its Dockerfile (org.mysuricates.dockerfile-sha256, TODO 244e5de5).
#
# The container overview (menu "Container", GET /api/v1/container-images) compares that label with
# the catalog ai-automation-backend/src/main/resources/container-images/agent-images.json: a different
# value means the image was built from another Dockerfile than this platform version ships ("outdated"),
# no value means it was built without this script ("unlabeled"). A plain `docker compose build` still
# works, it just leaves the label empty.
#
# Usage:  scripts/build-agent-images.sh [service...]
#         services: agent-node agent-python agent-devops agent-java agent-analysis (default: all)
# Env:    AGENT_APT_REFRESH  passed through to compose, see ai-automation-backend/docker-compose.yml
#                            (a new value refreshes the third-party APT layers and pip packages)
# Exit:   exit code of docker compose; 2 for an unknown service
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND="$ROOT/ai-automation-backend"
SERVICES=(agent-node agent-python agent-devops agent-java agent-analysis)
if [ "$#" -gt 0 ]; then
  SERVICES=("$@")
fi

# sha256sum is GNU coreutils; macOS ships shasum instead.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

for service in "${SERVICES[@]}"; do
  case "$service" in
    agent-node|agent-python|agent-devops|agent-java|agent-analysis) ;;
    *) echo "build-agent-images: unknown service '$service' (agent-node, agent-python, agent-devops, agent-java, agent-analysis)" >&2
       exit 2 ;;
  esac
  name="$(printf '%s' "${service#agent-}" | tr '[:lower:]' '[:upper:]')"
  export "AGENT_${name}_DOCKERFILE_SHA256=$(sha256_of "$BACKEND/docker/Dockerfile.$service")"
done

cd "$BACKEND"
exec docker compose --profile build-only build "${SERVICES[@]}"
