#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
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

wait_healthy() {
    local max_wait=60
    local waited=0
    log "Waiting for health check at $HEALTH_URL ..."
    while [ $waited -lt $max_wait ]; do
        if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
            log "Stable environment is healthy!"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    err "Health check failed after ${max_wait}s"
    return 1
}

cmd_snapshot() {
    log "Building snapshot from current code..."

    # Tag current commit
    local tag="stable-$(date +%Y%m%d-%H%M%S)"
    cd "$PROJECT_ROOT"
    git tag "$tag" 2>/dev/null && log "Tagged: $tag" || warn "Git tag failed (dirty tree?), continuing..."

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

    # Build Docker image
    log "Building Docker image..."
    cd "$BACKEND_DIR"
    docker compose -f "$COMPOSE_FILE" build
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
    docker compose -f "$COMPOSE_FILE" up -d

    wait_healthy
    log "Stable environment running at http://localhost:9091"
}

cmd_stop() {
    log "Stopping stable environment..."
    cd "$BACKEND_DIR"
    docker compose -f "$COMPOSE_FILE" down
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
    if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
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
    docker compose -f "$COMPOSE_FILE" logs -f
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

    # Stop container if running
    if docker ps --filter "name=$CONTAINER_NAME" --format '{{.ID}}' | grep -q .; then
        warn "Stopping container before DB seed..."
        cmd_stop
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
    docker compose -f "$COMPOSE_FILE" down -v --rmi local 2>/dev/null || true
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
        if curl -sf "$TEST_HEALTH_URL" > /dev/null 2>&1; then
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
    docker compose -f "$TEST_COMPOSE_FILE" up -d

    test_wait_healthy
    log "Test environment running at http://localhost:8090"
}

cmd_test_stop() {
    log "Stopping test environment..."
    cd "$BACKEND_DIR"
    docker compose -f "$TEST_COMPOSE_FILE" down
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

    if curl -sf "$TEST_HEALTH_URL" > /dev/null 2>&1; then
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
    docker compose -f "$TEST_COMPOSE_FILE" down -v 2>/dev/null || true
    log "Volume removed. Starting fresh..."
    cmd_test_start
    log "Test database reset complete."
}

cmd_test_log() {
    cd "$BACKEND_DIR"
    docker compose -f "$TEST_COMPOSE_FILE" logs -f
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
    docker compose -f "$TEST_COMPOSE_FILE" down -v --rmi local 2>/dev/null || true
    log "Cleaned: test container, volume, and image removed."
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
    echo "  test-start   Start the test environment container"
    echo "  test-stop    Stop the test environment container"
    echo "  test-status  Show test container status and health"
    echo "  test-reset   Reset test DB (delete volume + restart)"
    echo "  test-log     Tail test container logs"
    echo "  test-clean   Remove test container, volume, and image"
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
    test-start)   cmd_test_start ;;
    test-stop)    cmd_test_stop ;;
    test-status)  cmd_test_status ;;
    test-reset)   cmd_test_reset ;;
    test-log)     cmd_test_log ;;
    test-clean)   cmd_test_clean ;;
    *)            usage; exit 1 ;;
esac
