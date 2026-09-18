# Deployment

## Docker Compose

The project is deployed via Docker Compose. The configuration is located in `ai-automation-backend/docker-compose.yml`.

### Starting

```bash
cd ai-automation-backend

# 1. Build JAR
mvn clean package -DskipTests

# 2. Start backend
docker compose up --build -d

# 3. Build agent images (one-time)
docker compose --profile build-only build
```

The backend is then accessible at `http://localhost:9090`.

### Services

| Service | Image | Port | Description |
|---|---|---|---|
| `backend` | `Dockerfile.backend` | 9090 | Spring Boot Backend |
| `agent-node` | `Dockerfile.agent-node` | - | Node.js 22 Agent Container |
| `agent-python` | `Dockerfile.agent-python` | - | Python 3.12 Agent Container |
| `agent-go` | `Dockerfile.agent-go` | - | Go 1.24 Agent Container |

The agent images are only built (`profiles: build-only`), not started directly via Compose. The backend creates and manages agent containers dynamically via the Docker API.

## Dockerfiles

### Backend (`docker/Dockerfile.backend`)

- **Base:** `eclipse-temurin:21-jre-alpine`
- **User:** `appuser` (UID 1000, non-root)
- **JVM:** ZGC (Generational), 256MB–1GB Heap
- **Extras:** Tailscale CLI (pinned version + SHA256 verification, multi-arch amd64/arm64), sudo
- **Healthcheck:** `curl -f http://localhost:9090/api/v1/system/health`

### Agent Node (`docker/Dockerfile.agent-node`)

- **Base:** `node:22-slim`
- **Tools:** git, curl, jq
- **User:** `agent` (UID 1000)
- **Claude CLI:** Directories prepared (binary + config mounted from host)
- **CMD:** `tail -f /dev/null` (container stays active for `docker exec`)

### Agent Python (`docker/Dockerfile.agent-python`)

- **Base:** `python:3.12-slim`
- **Tools:** git, curl, jq
- **Packages:** anthropic, pandas, numpy, requests, beautifulsoup4

### Agent Go (`docker/Dockerfile.agent-go`)

- **Base:** `golang:1.24-alpine`
- **Tools:** git, curl, bash, jq
- **User:** `aiuser` (UID 1000)

### Agent DevOps (`docker/Dockerfile.agent-devops`)

- **Base:** `ubuntu:24.04`
- **Tools:** git, curl, jq, bash, python3
- **GitHub CLI:** `gh` from the official APT repository
- **Security Tools:** gitleaks, trivy (binaries with SHA256 verification, multi-arch)
- **Docker:** Docker CE CLI (client only, no daemon)
- **User:** `agent` (UID 1000)
- **CMD:** `tail -f /dev/null`

### Agent Java (`docker/Dockerfile.agent-java`)

- **Base:** `eclipse-temurin:21-jdk-jammy`
- **Tools:** maven, git, curl, jq, bash, python3, libreoffice-writer (headless DOCX→PDF)
- **Fonts:** fonts-liberation, fonts-dejavu
- **User:** `agent` (UID 1000)
- **CMD:** `tail -f /dev/null`

### Stable (`docker/Dockerfile.stable`)

- **Base:** `eclipse-temurin:21-jre-jammy` (Ubuntu-based, glibc for Claude CLI)
- **ARGs:** `HOST_USER`, `HOST_UID`, `HOST_GID`, `HOST_HOME` — for user mapping with the host
- **Tools:** curl, git, gosu, python3, jq, nodejs, npm, wget, unzip, Docker CE CLI
- **Entrypoint:** `entrypoint.sh` (user switching via gosu, Docker socket permissions)
- **Port:** 9091
- **Healthcheck:** `curl -f http://localhost:9091/api/v1/system/health`
- **JVM:** ZGC (Generational), 256MB–1GB Heap, headless AWT

## Docker Compose Files

### `docker-compose.yml` (Main)

Backend + agent images (build-only). Network: `automation` (bridge).

### `docker-compose.stable.yml`

Stable environment on port 9091:
- Container: `mysuricates-stable`
- Profile: `SPRING_PROFILES_ACTIVE=stable`
- Volumes: `stable-data` (persistent DB), `stable-frontend/` (read-only), Docker socket, Claude CLI directories from host, host project directories, backup volume (`~/.autotrigger/backups/stable`)
- Named Volume: `stable-data`

### `docker-compose.test.yml`

Test instance on port 8090 (uses `docker/Dockerfile.stable` as build base):
- Container: `mysuricates-test`
- Image: `ai-automation-backend-test`
- Profile: `SPRING_PROFILES_ACTIVE=test`
- Volumes: `test-data` (persistent DB), `stable-frontend/` (read-only), `application-test.yml` (read-only), Docker socket, Claude CLI directories from host, backup volume (`~/.autotrigger/backups/test`)
- Healthcheck: `curl -f http://localhost:8090/api/v1/system/health`
- Named Volume: `test-data`

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `AUTOMATION_DB_PATH` | `./data/automation.db` | Path to the SQLite database |
| `AUTOMATION_PORT` | `9090` | Server port |
| `AUTOMATION_SECURITY_ENABLED` | `true` | API key authentication |
| `AUTOMATION_API_KEY` | (empty) | API key for authentication |
| `AUTOMATION_RATE_LIMIT_ENABLED` | `true` | Enable rate limiting (also configurable at runtime in Settings → Execution) |
| `AUTOMATION_RATE_LIMIT_RPM` | `120` | Requests per minute (also configurable at runtime, 10–10,000) |
| `AUTOMATION_MODULES_PRESET` | `core` | Optional modules at startup: `core`, `research` or `full` (see [Module System](architektur.md#module-system-startup-configuration)) |
| `AUTOMATION_MODULES_<NAME>` | (preset) | Switch a single module, e.g. `AUTOMATION_MODULES_REMOTE_ACCESS=true` — wins over the preset |
| `AUTOMATION_MODULES_UI_EDITABLE` | `false` | `true` = feature modules can also be switched in the settings (applies immediately). Anyone with the API key can then turn on e.g. webhooks or the terminal — keep it off on publicly reachable instances |
| `AUTOMATION_HARDENING_<NAME>` | `provenance`: `true`, others `false` | Hardening flags `PROVENANCE`, `SEALING` (requires provenance), `REQUIRE_SEALED_FOR_UNATTENDED` (requires sealing), `WEBHOOK_SIGNATURES` (requires module webhooks) — see [Workflow Hardening](architektur.md#workflow-hardening-optional) |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT` | `300` | Global Claude CLI timeout in seconds (declared in `application.yml`) |
| `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` | (empty) | Optional per-call token limit for Claude CLI |
| `AUTOMATION_SSL_ENABLED` | `false` | Enable SSL/TLS |
| `AUTOMATION_SSL_KEYSTORE` | `./certs/dev.p12` | Path to PKCS12 keystore |
| `AUTOMATION_SSL_PASSWORD` | `mysuricates` | Keystore password |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker socket path |

> **Why some variables do not appear in `application.yml`:** The `automation.*` configuration is bound
> via `@ConfigurationProperties`. Spring additionally binds every property straight from the matching
> environment variable — so `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` sets `automation.host-claude.max-tokens`
> even though no `${...}` placeholder exists for it. Environment variables take precedence over the
> values from `application.yml`.

### Configurations: minimal and maximal

Two commented templates for modules and hardening live in `ai-automation-backend/config-examples/`. Copy one to `ai-automation-backend/config/application.yml` (or merge its blocks into it) and restart the backend; `ConfigExamplesTest` keeps both valid as the catalog grows.

| Template | Modules | Settings switches | Hardening |
|---|---|---|---|
| [`application-minimal.yml`](../../ai-automation-backend/config-examples/application-minimal.yml) | core only (`core`) | off | all off |
| [`application-maximal.yml`](../../ai-automation-backend/config-examples/application-maximal.yml) | all (`full`) | on | all on |

The public default sits in between: `core`, settings switches off, only `provenance` on.

On an instance that is already running, `./scripts/setup.sh --preset <core|research|full> --force` switches the preset: `--force` replaces just the `automation.modules.preset:` line in the existing `config/application.yml` and leaves everything else (comments, order, individual module overrides) untouched — it takes effect after a backend restart. Without `--force`, `setup.sh` writes nothing into an existing file. If the line cannot be placed unambiguously (several `automation:` blocks), the script aborts with a message instead of guessing.

For Docker/Compose, the same values as environment variables:

```bash
# Minimal: platform core only, every option off
AUTOMATION_MODULES_PRESET=core
AUTOMATION_MODULES_UI_EDITABLE=false
AUTOMATION_HARDENING_PROVENANCE=false
AUTOMATION_HARDENING_SEALING=false
AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED=false
AUTOMATION_HARDENING_WEBHOOK_SIGNATURES=false
```

```bash
# Maximal: every module and every hardening switch on (not for publicly reachable instances)
AUTOMATION_MODULES_PRESET=full
AUTOMATION_MODULES_UI_EDITABLE=true
AUTOMATION_HARDENING_PROVENANCE=true
AUTOMATION_HARDENING_SEALING=true
AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED=true   # seal workflows first, see below
AUTOMATION_HARDENING_WEBHOOK_SIGNATURES=true
```

> **Before "maximal":** `require-sealed-for-unattended` refuses every unattended run of an unsealed workflow (schedules, webhooks, TODO/completion triggers, auto-repair, wishes — also through chains and sub-workflows). Seal the affected workflows first, or keep this one switch `false`. With `full` and `ui-editable`, anyone holding the API key can also turn on modules with network, credential and host access — only on instances that are not publicly reachable.

### Template and loading path

`ai-automation-backend/.env.example` lists every variable with its actual default, grouped by topic.
**Spring Boot does not load a `.env` on its own** — there is no dotenv dependency in the project and
no `env_file:` in the compose files. The template is a reference, not auto-configuration.

| Start | How the values arrive |
|---|---|
| Host | `cp .env.example .env`, adjust, then `set -a; . ./.env; set +a` before starting — or export the variables directly |
| Compose | A `.env` next to the compose file only substitutes `${VAR}` **inside** the compose file. Only what is listed under `environment:` reaches the container |
| Spring override | Alternatively `ai-automation-backend/config/application.yml` (git-ignored) — Spring picks up a `config/` directory next to the working directory automatically |

### Container Limits (application.yml)

| Property | Default | Description |
|---|---|---|
| `docker.container.cpu-quota` | `200000` | CPU quota (2 cores) |
| `docker.container.memory-limit` | `4000000000` | RAM limit (~4GB) |
| `docker.container.pids-limit` | `100` | Max processes |
| `docker.container.idle-timeout-minutes` | `60` | Auto-stop after inactivity |

## Profiles

### Development (Default)

```yaml
# application.yml defaults
automation.security.enabled: true
automation.security.rate-limit.enabled: true
automation.modules.preset: core   # optional modules, see Module System
```

- API key authentication active (key is auto-generated on first start if `AUTOMATION_API_KEY`
  is empty — it is then in the log, in `~/.autotrigger/api-key` and in the `app_settings` table)
- Rate limiting active (120 req/min, configurable in Settings → Execution)
- SQLite at `./data/automation.db`

### Production

```bash
# docker-compose.yml environment
AUTOMATION_SECURITY_ENABLED=true
AUTOMATION_API_KEY=<secure-key>   # required: the compose file ships no default
AUTOMATION_MODULES_PRESET=core       # optional: research | full; remote access: AUTOMATION_MODULES_REMOTE_ACCESS=true
AUTOMATION_RATE_LIMIT_ENABLED=true   # optional
```

- API key authentication active
- Rate limiting recommended
- Docker socket mounted (`/var/run/docker.sock`)

## Volumes

```yaml
volumes:
  - ./data:/app/data                    # SQLite database
  - /var/run/docker.sock:/var/run/docker.sock  # Docker socket
```

**Important:** The Docker socket mount gives the backend full control over host Docker. In production environments, API key auth should be enabled.

**Note:** HikariCP pool size is set to 1. SQLite only allows one concurrent writer. Pool size=1 serializes all DB operations — no lock contention. Increasing pool size only makes sense when switching to PostgreSQL.

## Tailscale Remote Access

The backend can be made accessible from outside the local network via Tailscale.

### Prerequisites

- Tailscale CLI installed in the container (Dockerfile.backend includes this)
- Module `remote-access` enabled (`AUTOMATION_MODULES_REMOTE_ACCESS=true` or preset `full`) — off in the public default `core`; without it the `/system/remote-access/*` endpoints answer 404 `MODULE_DISABLED`

### Activation

1. Call the API endpoint: `POST /api/v1/system/remote-access/enable`
2. Backend starts Tailscale in the container
3. IP and hostname are returned in the response

### Status

- `GET /api/v1/system/remote-access/status` → `{ enabled, ip, hostname }`
- Visible in the frontend under Settings → Remote Access

## Healthcheck

Docker Compose and the Dockerfile define a healthcheck:

```
GET http://localhost:9090/api/v1/system/health

Interval: 30s
Timeout: 5s
Retries: 3
Start Period: 15s
```

Response on success:

```json
{
  "status": "UP",
  "timestamp": "...",
  "docker": "CONNECTED",
  "components": {
    "database": { "status": "UP", "responseTimeMs": 1 },
    "docker": { "status": "UP" }
  }
}
```

Possible `status` values: `UP` (DB + Docker ok), `DEGRADED` (DB ok, Docker not reachable), `DOWN` (DB not reachable).

## Database Backup

The SQLite database is a single file:

```bash
# Create backup
cp data/automation.db data/automation.db.backup

# Or while the system is running (WAL mode)
sqlite3 data/automation.db ".backup 'data/automation.db.backup'"
```

**Location:** `ai-automation-backend/data/automation.db` (local) or `/app/data/automation.db` (Docker)

The file contains all projects, workflows, runs, skills, schedules, webhooks, chains, todos, integrations, templates, and other entities. A backup of this file preserves the entire platform state.

## Network

Docker Compose creates an `automation` bridge network. The backend container and dynamically created agent containers can communicate through it.

```yaml
networks:
  automation:
    driver: bridge
```

## Reverse Proxy

Since the switch to one-time tickets for the WebSocket handshake (TODO 05b759f6), the frontend
enforces `transports: ['websocket']` and deliberately disables the SockJS fallback (HTTP polling):
a one-time ticket is only valid for the first connection attempt and does not survive a transport
switch. A reverse proxy in front of MySuricates therefore **must** pass WebSocket connections
through via an HTTP upgrade — without upgrade support, the app stops connecting.

On `localhost`, over Tailscale, and in the Stable/Test containers this holds (direct access, no
proxy in between); the break only shows up with custom reverse-proxy deployments.

### nginx

```nginx
location /ws/ {
    proxy_pass http://127.0.0.1:9090;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}

location / {
    proxy_pass http://127.0.0.1:9090;
    proxy_set_header Host $host;
}
```

### Caddy

Caddy detects WebSocket upgrades automatically — no extra configuration is needed:

```caddyfile
example.com {
    reverse_proxy 127.0.0.1:9090
}
```

### Failure Pattern

If the upgrade is not passed through, the `ticket` query parameter still reaches the backend (it
lives in the URL, not a header) — `WebSocketAuthInterceptor.beforeHandshake` runs before Spring's
own upgrade check, so it consumes an otherwise valid ticket even when the protocol switch itself
fails right after due to the missing headers. Tickets are deliberately short-lived and single-use
(TTL 30s, `WebSocketTicketService`): reproducing the problem (`curl`, repeated reloads, a ticket
copied from the browser devtools) shows one of two lines from `WebSocketAuthInterceptor` in the
backend log:

```
WebSocket handshake rejected: invalid ticket (expired, already used or forged) from {}
```

— a presented ticket is expired, already used, or forged; or

```
WebSocket handshake rejected: no ticket presented by {}
```

— no `ticket` parameter arrived at all (e.g. because a test tool or a proxy drops the query
string). This is not a ticket bug. Check whether the proxy responds to the `/ws/**` path with
status 101 (Switching Protocols).

## Trust Model and Network Exposure

MySuricates is built for **local operation by a single trusted person**. It is not a hardened
multi-user system and should not be exposed publicly as-is. This section documents the existing
model so you know what is reachable before you start it.

### What a workflow is allowed to do

Shell blocks and AI calls run **on the host with the privileges of the user who started the
backend** — not in a container. Container execution is opt-in per shell block (`container_image`).
A workflow can therefore do anything that user can: read and write files, start processes, reach
the network.

The Claude CLI is also invoked with `automation.cli.skip-permissions` = **`true`**
(`AUTOMATION_CLI_SKIP_PERMISSIONS`), so it does not prompt before using a tool. The exception: when
a call carries an explicit tool whitelist, the flag is dropped and the CLI enforces the list — this
is how `skill-capability` blocks without `allow_writes` stay read-only.

### Three trust classes for context values

Not every value a workflow substitutes into a shell command comes from the definition. Every context
variable therefore carries one of three classes; the class decides whether the value goes into the
command line raw or wrapped in POSIX single quotes.

| Class | Where the value comes from | In shell blocks | In AI prompts | Taints the run |
|---|---|---|---|---|
| **trusted** | workflow defaults, system values (`project_path`, `workflow_run_id`), block-internal values, answers from `approval`/`human-input` | substituted raw | raw | no |
| **untrusted external** | webhook payload and everything derived from it (block outputs, `step_N_output`, foreach items) — across resume and sub-workflows too | always single-quoted | raw | yes, from the moment it is set |
| **platform data** | `previous_report` and `open_todos`, injected into **every** run | always single-quoted | raw | only once the placeholder really appears in a shell command |

"Taints the run" means: from that moment every further output of the run is quoted too, because a
block can pull foreign bytes into its output without naming any placeholder. The taint is sticky and
survives resume and sub-workflow hand-over. Platform data deliberately taints only on use: it is
present in every run, and tainting at injection time would taint every run and change the meaning of
the output placeholders in the bundled templates.

Deliberately **not** escaped are answers from `approval` and `human-input` blocks: they arrive
through the API-key-protected interface from the person who owns the instance. And when a workflow
sets a variable itself (`setVariable`), it counts as trusted again — the value then comes from the
definition, not from the caller.

Limits worth knowing:

- **Nothing is escaped in AI prompts.** A prompt is not a command line; the model does not defend
  against prompt injection.
- **An AI answer that followed a prompt with platform data is coloured itself.** If platform data is
  in the prompt of a `simple`, `agentic` or `skill-capability` block (nested forms included), the
  output of exactly that block counts as untrusted: a following shell block receives
  `{{ previous_output }}` **single-quoted**. Once that coloured output is recorded, the run is
  coloured — and the colouring is sticky as always: every **further** block output and the variables
  derived from it (`step_N_output`, `foreach` items, `document` extracts) are quoted from then on
  too, and `exportTaint()` passes that on to sub-workflows and to a resume. In practice: nearly every
  audit or report workflow that reads `previous_report` in a prompt runs in quoted mode from that
  block on. **In your own workflows, set `"shell_escape_variables": true` in every shell block that
  consumes an AI block's output** — the block then behaves the same in coloured and in uncoloured
  runs.
- **What quoting does not defend against:** what is quoted is the text of the AI answer, not how it
  came about. The trust model does not help against manipulation of the content (prompt injection).
- **Quoting cannot see files you write yourself.** A shell block that writes a tainted value into a
  file and later executes it is outside what quoting can reach.

Technical version with class names and reserved context keys:
[Workflow Hardening](architektur.md#workflow-hardening-optional).

### Where it listens

Neither backend nor frontend binds to loopback. `server.address` is set nowhere, so the backend
listens on all interfaces; Vite runs with `host: true`. The compose service publishes `9090:9090`
without a host-IP prefix, i.e. on every address — and Docker's port publishing bypasses most host
firewall rules while doing so.

By default CORS allows Tailscale patterns (`100.*` and `*.tail*.ts.net`) next to `localhost`.
**CORS is not access control** — it only governs which origin a browser accepts and stops nobody
from calling the API directly. Having Tailscale in front does not make the application a secured
system.

### What is reachable without an API key

Authentication uses the `X-API-Key` header and covers `/api/**` and `/actuator/**`. Exempt are:

| Path | Purpose |
|---|---|
| `/api/v1/system/health`, `/system/info`, `/system/auth-status` | Status checks before login |
| `/actuator/health` | Docker healthcheck |
| `/ws/**` | WebSocket handshake (the key is checked there as a query parameter) |
| `/api/v1/webhook/trigger/**` | Public webhook entry point |
| `/api/v1/integrations/*/callback` | OAuth callbacks |

Everything outside `/api/**` and `/actuator/**` is open as well (`anyRequest().permitAll()`) — that
covers the bundled frontend and any unmapped path.

**Important:** if no API key is configured, or `automation.security.enabled=false`, *every* request
counts as authenticated. An empty key therefore switches authentication off entirely rather than
tightening it.

### Webhooks

The trigger endpoint is guarded solely by a token in the path (32 random bytes from `SecureRandom`).
There is no signature verification and no replay protection. As a counterweight, a hard-wired limit
of 10 requests per minute and IP applies — independent of the global rate-limit switch — the payload
is capped at 50 fields, and every caller-supplied value is marked untrusted and always escaped in
shell blocks.

### Docker privileges — two separate layers

These two are easily confused:

1. **`AUTOMATION_ALLOW_DOCKER_SOCKET_MOUNT`** (default `false`) is a server-side switch for whether a
   shell block with `mount_docker_socket: true` gets the socket inside a **spawned agent container**.
   A denial is written to the activity log as a SECURITY entry.
2. **The shipped `docker-compose.yml`** instead mounts the host Docker socket straight into the
   **backend container** (`/var/run/docker.sock`). This is independent of point 1 and gives the
   application full control over Docker on the host.

### Container Image Allowlist

`AUTOMATION_ALLOWED_CONTAINER_IMAGES` (default empty = any image allowed, logged once as an INFO
message at startup) restricts which `container_image` a shell block may request. The check sits
centrally in `ContainerShellService.execute` — both callers (`ShellBlockExecutor`,
`CommandExecutorService`) get it automatically. An image that is not listed fails with exit code
125 and a SECURITY entry in the activity log instead of simply starting. An entry matches by exact
equality, as a registry/namespace prefix (ends with `/`), or, when it has no tag, against any tag
of the image. An image starting with `-` is always rejected (regardless of the allowlist) —
`docker run` would otherwise read it as a flag. Same pattern as `AUTOMATION_ALLOWED_MOUNT_PREFIXES`.

### What follows from this

- Only run it on a network you trust; for remote access put a VPN in front instead of opening the port.
- Set your own API key and verify it is not empty.
- Remove the Docker socket mount from the compose file if you do not need container blocks.
- In production, set `AUTOMATION_ALLOWED_CONTAINER_IMAGES` to the images you actually need instead
  of staying on the empty default (= all images allowed).
- Do not run workflows from an untrusted source unread — they run with your privileges.

## Three-Environment Setup

The project supports three parallel environments:

| Environment | Port | Description |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | From source code, Vite dev server |
| **Stable** | 9091 | Docker container, backend + embedded frontend |
| **Test** | 8090 | Docker container, dedicated test instance for workflow tests |

### Start (no auto-start)

Stable and Test do **not** start automatically — neither on boot nor with the dev backend. They are started deliberately via the desktop icons or `scripts/staging.sh`:

| Environment | Icon | Command |
|---|---|---|
| Dev | "MySuricates Dev" | `scripts/start-autotrigger-dev.sh` |
| Stable | "MySuricates Stable" | `scripts/staging.sh start` |
| Test | "MySuricates Test" | `scripts/staging.sh test-start` |

Create or refresh the icons (desktop + application menu): `scripts/install_desktop_launcher.sh`.

Two mechanisms were turned off for this:
- **Docker restart policy** — `restart: "no"` in `docker-compose.stable.yml` / `docker-compose.test.yml`. Previously (`unless-stopped`) the Docker daemon brought both containers back up on every boot.
- **Dev auto-start** — `automation.staging.auto-start` and `automation.staging.test-auto-start` are `false`. Previously the dev backend started both containers on `ApplicationReadyEvent` whenever a snapshot (`stable-frontend/`) existed and the container was not already running.

Re-enable without a code change: set `AUTOMATION_STAGING_AUTO_START=true` / `AUTOMATION_TEST_AUTO_START=true` (and restore the restart policy in the compose file if desired).

### Pausing automatic triggers

There are two ways to silence the scheduler for a while:

- **`automation.scheduler.skip-until`** (`AUTOMATION_SCHEDULER_SKIP_UNTIL`, ISO instant, e.g. `2026-07-30T22:00:00Z`) — for time windows. Due schedules are skipped **and** their `nextRunAt` is advanced past the window, so there is no catch-up stampede at the end. It expires on its own; an unparseable value is ignored (fail open). Read only at backend startup.
- **Maintenance mode** (`POST /api/v1/schedules/maintenance`, pause button on the Schedules page) — runtime switch without an end date, persisted in `app_settings`. Note: `nextRunAt` is *not* advanced, so when you switch it off, every schedule that became due fires at once.

For local, non-committed overrides use `ai-automation-backend/config/application.yml` — Spring Boot loads that directory in addition to the classpath config and with higher precedence (the directory is in `.gitignore`).

### Stable Environment (Port 9091)

The stable environment is a Docker container containing the backend + embedded React frontend.

**Key Files:**
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-based (glibc for Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — Volume mounts for Claude CLI + projects
- `ai-automation-backend/src/main/resources/application-stable.yml` — Port 9091, auto-start disabled, frontend serving enabled

### Test Instance (Port 8090)

The test instance is a dedicated Docker container for automated workflow tests:

- `TestingService` — manages the test Docker container
- No auto-start on boot (`automation.staging.test-auto-start=false`) — start via the "MySuricates Test" icon or `staging.sh test-start`
- No auto-sync of definitions on boot (only on `reset`)
- Database can be reset via API (`POST /api/v1/testing/reset`)

**Key Files:**
- `ai-automation-backend/docker-compose.test.yml` — Container `mysuricates-test`, port 8090, profile `test`

### Staging CLI Management

```bash
./scripts/staging.sh snapshot    # Build JAR + frontend + Docker image, tag git commit
./scripts/staging.sh start       # Start stable container on :9091
./scripts/staging.sh stop        # Stop stable container
./scripts/staging.sh status      # Container status + health check
./scripts/staging.sh log         # Show container logs (tail)
./scripts/staging.sh db-seed     # Raw DB copy (emergency only, overwrites everything)
./scripts/staging.sh clean       # Remove container + volume + image
./scripts/staging.sh promote     # stop → snapshot → start (one-liner)
```

### Definition Sync (Dev → Stable / Dev → Test)

`POST /api/v1/staging/sync-definitions` and `POST /api/v1/testing/sync-definitions` synchronize **definitions only**:

| Synced | Matching | Behavior |
|---|---|---|
| Projects | Name + Path | Skip if exists, create if new |
| Workflows | Name + Project name | Update if exists, import if new |
| Custom Skills | Name | Skip if exists, import if new |
| Chains | Name | Update if exists, create if new |

Additionally, the app settings `auto-todo-default` and `auto-repair-default` are synchronized.

**Not synced** (preserved on Stable/Test): workflow_runs, step_results, scheduled_workflows, webhook_triggers, activity_log, todos, built-in Skills/Templates. Archived projects and soft-deleted workflows are automatically excluded.

### Rollback After Sync

Before each definition sync, the backend automatically calls `POST /api/v1/system/backup?reason=pre-sync-definitions` on the **target**. The returned filename is included in `SyncResult.preSyncBackup` and displayed in the frontend toast. Backup success does not block the sync — if the backup fails, the sync proceeds anyway, and the warning ends up in `SyncResult.errors`.

Additionally, `./scripts/staging.sh db-seed` creates a file backup (`~/.autotrigger/backups/stable/mysuricates-backup-<timestamp>-pre-seed.db`) before overwriting the stable DB with the dev DB.

**Rollback Paths:**

| Snapshot Source | Location | Restore Method |
|---|---|---|
| Pre-Sync (Definition Sync) | Target backup directory (`~/.autotrigger/backups/<env>/`) | UI on the target: Settings → Backups → Download; restore manually. |
| Pre-Seed (`staging.sh db-seed`) | `~/.autotrigger/backups/stable/…-pre-seed.db` | `docker compose -f ai-automation-backend/docker-compose.stable.yml down && docker run --rm -v ai-automation-backend_stable-data:/data -v <backup>:/src/db:ro alpine cp /src/db /data/automation.db && ./scripts/staging.sh start` |

Pre-sync backups count towards the regular retention (`automation.backup.retain-count`, default 30) and may be displaced by newer backups.

### Version Display

In Settings → Stable, a version comparison is shown:
- **Dev**: Version from `/api/v1/system/info`
- **Stable**: Version from the stable container's `/api/v1/system/info`

When versions differ (e.g., after a release), a promote is recommended.

### Release + Promote Workflow

```bash
# 1. Bump version
./scripts/release.sh           # e.g. 0.3.2 → 0.3.3

# 2. Deploy new version to Stable
./scripts/staging.sh promote   # Builds and starts Stable with the new version
```

### Git Tags

| Tag Format | Created By | Purpose |
|---|---|---|
| `v0.3.2` | `release.sh` | Semver release marker |
| `stable-20250225-143022` | `staging.sh promote` | Deployment timestamp |
