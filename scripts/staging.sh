#!/usr/bin/env bash
# staging.sh — Stable (:9091) and Test (:8090) as Docker containers. Commands: see usage below.
#
# What comes from the machine into the containers is decided by scripts/lib/host-mounts.sh — the same place
# as for instance.sh and the dev container (262948a4). The switches are in
# ~/.config/mysuricates/staging.env (line by line KEY=VALUE, never executed); an environment
# variable of the same name wins:
#   STABLE_AI_FROM_HOST / TEST_AI_FROM_HOST    programs | login (default) | all
#   STABLE_AI_PROVIDERS / TEST_AI_PROVIDERS    selection from claude,codex,pi (default: all)
#   STABLE_DOCKER_SOCKET / TEST_DOCKER_SOCKET  0 (default) | 1 = full control over Docker on the
#                                              machine (project container, terminal, container_image) — set deliberately
#   STABLE_BIND / TEST_BIND                    address for the port, default 127.0.0.1 (this machine only);
#                                              0.0.0.0 = also from the local network — set deliberately
# Every compose call takes the override file generated from this with it — also the ones from the backend
# (StagingService/TestingService fetch it via `staging.sh write-override`).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/host-mounts.sh
. "$SCRIPT_DIR/lib/host-mounts.sh"
# shellcheck source=lib/provider-login.sh
. "$SCRIPT_DIR/lib/provider-login.sh"
# shellcheck source=lib/local-curl.sh
. "$SCRIPT_DIR/lib/local-curl.sh"   # health without the proxy from the environment (bb4f60a5)
# shellcheck source=lib/data-home.sh
. "$SCRIPT_DIR/lib/data-home.sh"
# Host backups of stable/test live in the data folder (the compose files read AUTOMATION_DATA_HOME,
# StagingService/TestingService set it likewise when started from the UI).
AUTOMATION_DATA_HOME="$(data_home)"; export AUTOMATION_DATA_HOME
BACKEND_DIR="$PROJECT_ROOT/ai-automation-backend"
FRONTEND_DIR="$PROJECT_ROOT/ai-automation-frontend"
COMPOSE_FILE="$BACKEND_DIR/docker-compose.stable.yml"
STABLE_FRONTEND_DIR="$BACKEND_DIR/stable-frontend"
IMAGE_NAME="mysuricates-stable"
CONTAINER_NAME="mysuricates-stable"
HEALTH_URL="http://localhost:9091/api/v1/system/health"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[staging]${NC} $*"; }
warn() { echo -e "${YELLOW}[staging]${NC} $*"; }
err()  { echo -e "${RED}[staging]${NC} $*" >&2; }

STAGING_ENV="${MYSURICATES_STAGING_ENV:-${XDG_CONFIG_HOME:-$HOME/.config}/mysuricates/staging.env}"
OVERRIDE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mysuricates/staging"

# staging_setting stable|test KEY DEFAULT — STABLE_KEY / TEST_KEY from the environment, otherwise from
# staging.env, otherwise DEFAULT.
staging_setting() {
    local prefix v
    case "$1" in stable) prefix=STABLE_ ;; *) prefix=TEST_ ;; esac
    v="$(printenv "$prefix$2" 2>/dev/null || true)"
    if [ -z "$v" ] && [ -f "$STAGING_ENV" ]; then
        v="$( { grep -E "^$prefix$2=" "$STAGING_ENV" || true; } | tail -n 1 | cut -d= -f2-)"
    fi
    printf '%s' "${v:-$3}"
}

# staging_ports_yaml PORT BIND — the ports block of the override file (service level). Default is
# 127.0.0.1: only this machine reaches the instance, as with deploy/ (MYSURICATES_BIND). For every
# other address except 0.0.0.0 (includes 127.0.0.1) 127.0.0.1 is added, so that health check
# and UI keep reaching the instance via localhost.
staging_ports_yaml() {
    local port="$1" addrs="$2" a
    case "$2" in 127.0.0.1|0.0.0.0) ;; *) addrs="$2 127.0.0.1" ;; esac
    echo "    ports:"
    for a in $addrs; do
        printf '      - target: %s\n        published: "%s"\n        host_ip: "%s"\n' "$port" "$port" "$a"
    done
}

# write_override stable|test — writes the override file and prints its path (only that: the
# backend reads it). Hints about missing providers go to stderr.
write_override() {
    local env="$1" service prefix port bind file missing login_command=""
    case "$env" in
        stable) service=stable; prefix=STABLE_; port=9091 ;;
        test)   service=test; prefix=TEST_; port=8090 ;;
        *) err "write-override: stable|test"; return 2 ;;
    esac
    bind="$(staging_setting "$env" BIND 127.0.0.1)"
    case "$bind" in
        *[!0-9a-fA-F.:]*) err "Ungueltiges ${prefix}BIND '$bind' (eine IP-Adresse, z. B. 127.0.0.1 oder 0.0.0.0) — $STAGING_ENV"
                          return 2 ;;
    esac
    mkdir -p "$OVERRIDE_DIR"
    file="$OVERRIDE_DIR/$env.override.yml"
    # Only in programs mode the container logs in itself (cmd_login) — the status card then shows
    # the command that does so (fixed text, never executed).
    [ "$(staging_setting "$env" AI_FROM_HOST login)" = programs ] && login_command="scripts/staging.sh login $env"
    if ! host_mounts_yaml "$service" "" "$(staging_setting "$env" DOCKER_SOCKET 0)" \
            "$(staging_setting "$env" AI_FROM_HOST login)" "$(staging_setting "$env" AI_PROVIDERS "")" \
            "$STAGING_ENV" "$prefix" "$login_command" "staging-$env" > "$file.tmp"; then
        rm -f "$file.tmp"
        err "Ungueltige ${prefix}AI_*-Werte (programs|login|all, claude,codex,pi) — $STAGING_ENV"
        return 2
    fi
    staging_ports_yaml "$port" "$bind" >> "$file.tmp"
    mv "$file.tmp" "$file"
    missing="$(host_mounts_missing_text)"
    [ -z "$missing" ] || printf 'KI-Anbieter, die %s nicht nutzen kann:\n%s\n' "$env" "$missing" >&2
    printf '%s\n' "$file"
}

stable_compose() { local ovr; ovr="$(write_override stable)" || exit 2; docker compose -f "$COMPOSE_FILE" -f "$ovr" "$@"; }
test_compose()   { local ovr; ovr="$(write_override test)" || exit 2; docker compose -f "$TEST_COMPOSE_FILE" -f "$ovr" "$@"; }

wait_healthy() {
    local max_wait=60
    local waited=0
    log "Waiting for health check at $HEALTH_URL ..."
    while [ $waited -lt $max_wait ]; do
        if local_curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
            log "Stable environment is healthy!"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    err "Health check failed after ${max_wait}s"
    return 1
}

# Build backend JAR + frontend and copy the frontend into stable-frontend/.
# Shared by the stable snapshot and the test snapshot — both the stable and
# the test container COPY target/*.jar at image build and mount ./stable-frontend.
build_artifacts() {
    # Build backend JAR
    log "Building backend JAR..."
    cd "$BACKEND_DIR"
    mvn clean package -DskipTests -q
    log "Backend JAR built."

    # Build frontend
    log "Building frontend..."
    cd "$FRONTEND_DIR"
    npm run build --silent
    log "Frontend built."

    # Copy frontend dist to stable-frontend
    rm -rf "$STABLE_FRONTEND_DIR"
    cp -r "$FRONTEND_DIR/dist" "$STABLE_FRONTEND_DIR"
    log "Frontend copied to $STABLE_FRONTEND_DIR"
}

cmd_snapshot() {
    log "Building snapshot from current code..."

    # Tag current commit
    local tag="stable-$(date +%Y%m%d-%H%M%S)"
    cd "$PROJECT_ROOT"
    git tag "$tag" 2>/dev/null && log "Tagged: $tag" || warn "Git tag failed (dirty tree?), continuing..."

    build_artifacts

    # Build Docker image
    log "Building Docker image..."
    cd "$BACKEND_DIR"
    stable_compose build
    log "Docker image built."

    log "Snapshot complete: $tag"
}

cmd_start() {
    if [ ! -d "$STABLE_FRONTEND_DIR" ]; then
        err "No stable-frontend/ found. Run 'staging.sh snapshot' first."
        exit 1
    fi

    log "Starting stable environment..."
    cd "$BACKEND_DIR"
    stable_compose up -d

    wait_healthy
    log "Stable environment running at http://localhost:9091"
}

cmd_stop() {
    log "Stopping stable environment..."
    cd "$BACKEND_DIR"
    stable_compose down
    log "Stable environment stopped."
}

cmd_status() {
    echo ""
    log "=== Stable Environment Status ==="
    echo ""

    # Container status
    if docker ps --filter "name=$CONTAINER_NAME" --format '{{.Status}}' | grep -q .; then
        local status
        status=$(docker ps --filter "name=$CONTAINER_NAME" --format '{{.Status}}')
        echo -e "  Container: ${GREEN}Running${NC} ($status)"
    else
        echo -e "  Container: ${RED}Stopped${NC}"
    fi

    # Health check
    if local_curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
        echo -e "  Health:    ${GREEN}OK${NC}"
    else
        echo -e "  Health:    ${RED}Unreachable${NC}"
    fi

    # Image info
    if docker images "$IMAGE_NAME" --format '{{.Size}} ({{.CreatedSince}})' | head -1 | grep -q .; then
        local img_info
        img_info=$(docker images "$IMAGE_NAME" --format '{{.Size}} ({{.CreatedSince}})' | head -1)
        echo -e "  Image:     $img_info"
    else
        echo -e "  Image:     ${YELLOW}Not built${NC}"
    fi

    # Git tag
    local latest_tag
    latest_tag=$(git tag -l 'stable-*' --sort=-version:refname 2>/dev/null | head -1)
    if [ -n "$latest_tag" ]; then
        echo -e "  Last tag:  $latest_tag"
    fi

    echo ""
}

cmd_log() {
    cd "$BACKEND_DIR"
    stable_compose logs -f
}

cmd_db_seed() {
    local dev_db="$BACKEND_DIR/data/automation.db"
    if [ ! -f "$dev_db" ]; then
        err "Dev database not found at $dev_db"
        exit 1
    fi

    # Get the volume mount path
    local volume_name="ai-automation-backend_stable-data"
    local volume_path
    volume_path=$(docker volume inspect "$volume_name" --format '{{.Mountpoint}}' 2>/dev/null || true)

    if [ -z "$volume_path" ]; then
        # Create volume first
        log "Creating stable-data volume..."
        docker volume create "$volume_name"
        volume_path=$(docker volume inspect "$volume_name" --format '{{.Mountpoint}}')
    fi

    # Stop container if running — ensures SQLite file is in a consistent state before backup.
    if docker ps --filter "name=$CONTAINER_NAME" --format '{{.ID}}' | grep -q .; then
        warn "Stopping container before DB seed..."
        cmd_stop
    fi

    # Pre-seed rollback point: copy current stable DB to host-backup dir before overwrite.
    local backup_dir="${AUTOMATION_DATA_HOME}/backups/stable"
    mkdir -p "$backup_dir"
    local timestamp
    timestamp=$(date +%Y-%m-%d_%H-%M-%S)
    local backup_file="$backup_dir/mysuricates-backup-${timestamp}-pre-seed.db"
    if [ -f "$volume_path/automation.db" ]; then
        log "Creating pre-seed backup: $(basename "$backup_file")"
        cp "$volume_path/automation.db" "$backup_file"
        [ -f "$volume_path/automation.db-wal" ] && cp "$volume_path/automation.db-wal" "${backup_file}-wal"
        [ -f "$volume_path/automation.db-shm" ] && cp "$volume_path/automation.db-shm" "${backup_file}-shm"
        log "Pre-seed backup saved. Rollback: docker run --rm -v $volume_name:/data -v $backup_file:/src/db:ro alpine cp /src/db /data/automation.db"
    else
        warn "No existing DB in stable volume — nothing to back up."
    fi

    log "Copying dev DB to stable volume..."
    # Use a temp container to copy into the volume
    docker run --rm -v "$volume_name:/data" -v "$dev_db:/src/automation.db:ro" \
        alpine cp /src/automation.db /data/automation.db
    log "Database seeded from dev."
}

cmd_clean() {
    log "Cleaning up stable environment completely..."
    cd "$BACKEND_DIR"
    stable_compose down -v --rmi local 2>/dev/null || true
    rm -rf "$STABLE_FRONTEND_DIR"
    log "Cleaned: container, volume, image, and stable-frontend removed."
}

cmd_promote() {
    log "Promoting current code to stable..."
    cmd_stop 2>/dev/null || true
    cmd_snapshot
    cmd_start
    log "Promotion complete!"
}

# ── Test instance commands ─────────────────────────────

TEST_COMPOSE_FILE="$BACKEND_DIR/docker-compose.test.yml"
TEST_CONTAINER_NAME="mysuricates-test"
TEST_HEALTH_URL="http://localhost:8090/api/v1/system/health"

test_wait_healthy() {
    local max_wait=60
    local waited=0
    log "Waiting for health check at $TEST_HEALTH_URL ..."
    while [ $waited -lt $max_wait ]; do
        if local_curl -sf "$TEST_HEALTH_URL" > /dev/null 2>&1; then
            log "Test environment is healthy!"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    err "Health check failed after ${max_wait}s"
    return 1
}

cmd_test_start() {
    if [ ! -d "$STABLE_FRONTEND_DIR" ]; then
        err "No stable-frontend/ found. Run 'staging.sh snapshot' first."
        exit 1
    fi

    log "Starting test environment..."
    cd "$BACKEND_DIR"
    test_compose up -d

    test_wait_healthy
    log "Test environment running at http://localhost:8090"
}

cmd_test_stop() {
    log "Stopping test environment..."
    cd "$BACKEND_DIR"
    test_compose down
    log "Test environment stopped."
}

cmd_test_status() {
    echo ""
    log "=== Test Environment Status ==="
    echo ""

    if docker ps --filter "name=$TEST_CONTAINER_NAME" --format '{{.Status}}' | grep -q .; then
        local status
        status=$(docker ps --filter "name=$TEST_CONTAINER_NAME" --format '{{.Status}}')
        echo -e "  Container: ${GREEN}Running${NC} ($status)"
    else
        echo -e "  Container: ${RED}Stopped${NC}"
    fi

    if local_curl -sf "$TEST_HEALTH_URL" > /dev/null 2>&1; then
        echo -e "  Health:    ${GREEN}OK${NC}"
    else
        echo -e "  Health:    ${RED}Unreachable${NC}"
    fi

    echo ""
}

cmd_test_reset() {
    echo ""
    echo "WARNING: This will DELETE all test data (workflows, runs, results)."
    echo "         The test volume will be destroyed and recreated."
    echo ""
    read -p "Continue? [y/N] " confirm
    [[ "$confirm" =~ ^[yY]$ ]] || { echo "Aborted."; exit 1; }

    log "Resetting test database..."
    cd "$BACKEND_DIR"
    test_compose down -v 2>/dev/null || true
    log "Volume removed. Starting fresh..."
    cmd_test_start
    log "Test database reset complete."
}

cmd_test_log() {
    cd "$BACKEND_DIR"
    test_compose logs -f
}

cmd_test_clean() {
    echo ""
    echo "WARNING: This will DELETE all test data and remove the Docker image."
    echo "         The test volume, container, and image will be destroyed."
    echo ""
    read -p "Continue? [y/N] " confirm
    [[ "$confirm" =~ ^[yY]$ ]] || { echo "Aborted."; exit 1; }

    log "Cleaning up test environment completely..."
    cd "$BACKEND_DIR"
    test_compose down -v --rmi local 2>/dev/null || true
    log "Cleaned: test container, volume, and image removed."
}

cmd_test_snapshot() {
    log "Building test snapshot from current code..."

    build_artifacts

    # Rebuild the test Docker image so it picks up the fresh JAR. Without this,
    # `test-start` reuses the existing image and the test backend goes stale —
    # an outdated backend + current frontend means new endpoints 404 and the UI
    # hangs on "loading".
    log "Building test Docker image..."
    cd "$BACKEND_DIR"
    test_compose build
    log "Test Docker image built."

    log "Test snapshot complete."
}

cmd_test_promote() {
    log "Promoting current code to test..."
    cmd_test_stop 2>/dev/null || true
    cmd_test_snapshot
    cmd_test_start
    log "Test promotion complete!"
}

# login stable|test [claude|codex|pi] — own login in the container. Only with AI_FROM_HOST=programs:
# in login mode the login comes from the machine, a second one in the container would overwrite the
# mounted auth.json (Codex, Pi) or bypass the token (Claude).
cmd_login() {
    local env="${1:-}" tool="${2:-claude}" container prefix mode user
    case "$env" in
        stable) container="$CONTAINER_NAME"; prefix=STABLE_ ;;
        test)   container="$TEST_CONTAINER_NAME"; prefix=TEST_ ;;
        *) err "login: stable|test [claude|codex|pi]"; exit 2 ;;
    esac
    case "$tool" in claude|codex|pi) ;; *) err "login kennt claude, codex, pi"; exit 2 ;; esac
    if ! docker ps --filter "name=^${container}\$" --format '{{.ID}}' | grep -q .; then
        err "$container laeuft nicht."; exit 2
    fi
    mode="$(docker exec "$container" printenv MYSURICATES_AI_FROM_HOST 2>/dev/null || true)"
    if [ "$mode" != programs ]; then
        err "$container nutzt die Anmeldung dieses Rechners (${prefix}AI_FROM_HOST=${mode:-login})."
        err "Eigene Anmeldung nur mit ${prefix}AI_FROM_HOST=programs in $STAGING_ENV und Neustart."
        exit 2
    fi
    user="$(docker exec "$container" printenv APP_USER)"
    provider_login_argv "$tool"
    docker exec -it -u "$user" "$container" "${PROVIDER_LOGIN_ARGV[@]}"
}

usage() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Stable commands:"
    echo "  snapshot     Build JAR + Frontend, tag Git commit, build Docker image"
    echo "  start        Start the stable environment container"
    echo "  stop         Stop the stable environment container"
    echo "  status       Show container status, health, and version info"
    echo "  log          Tail container logs"
    echo "  db-seed      Copy current dev DB into the stable Docker volume"
    echo "  clean        Remove container, volume, image, and stable-frontend"
    echo "  promote      Stop → snapshot → start (one-liner upgrade)"
    echo ""
    echo "Test commands:"
    echo "  test-snapshot  Build JAR + Frontend, rebuild the test Docker image"
    echo "  test-promote   Stop → test-snapshot → test-start (one-liner upgrade)"
    echo "  test-start     Start the test environment container (no rebuild)"
    echo "  test-stop      Stop the test environment container"
    echo "  test-status    Show test container status and health"
    echo "  test-reset     Reset test DB (delete volume + restart)"
    echo "  test-log       Tail test container logs"
    echo "  test-clean     Remove test container, volume, and image"
    echo ""
    echo "Both:"
    echo "  write-override stable|test    Write the compose override (what comes from the host), print its path"
    echo "  login stable|test [provider]  Own login inside the container (only with *_AI_FROM_HOST=programs)"
    echo ""
    echo "Switches: $STAGING_ENV (STABLE_/TEST_ + AI_FROM_HOST, AI_PROVIDERS, DOCKER_SOCKET=0|1, BIND=127.0.0.1)"
}

case "${1:-}" in
    snapshot)     cmd_snapshot ;;
    start)        cmd_start ;;
    stop)         cmd_stop ;;
    status)       cmd_status ;;
    log)          cmd_log ;;
    db-seed)      cmd_db_seed ;;
    clean)        cmd_clean ;;
    promote)      cmd_promote ;;
    test-snapshot) cmd_test_snapshot ;;
    test-promote) cmd_test_promote ;;
    test-start)   cmd_test_start ;;
    test-stop)    cmd_test_stop ;;
    test-status)  cmd_test_status ;;
    test-reset)   cmd_test_reset ;;
    test-log)     cmd_test_log ;;
    test-clean)   cmd_test_clean ;;
    write-override) write_override "${2:-}" ;;
    login)        cmd_login "${2:-}" "${3:-}" ;;
    *)            usage; exit 1 ;;
esac
