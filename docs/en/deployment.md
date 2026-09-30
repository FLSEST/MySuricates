# Deployment

MySuricates runs on a machine in one of two ways: as an **instance in a container** via `deploy/`
(only Docker needed, the backend serves the UI itself — the way for users, also the only Docker way)
or **from source** ([entwicklung.md](entwicklung.md)). Whoever works on the code often runs two more containers next to
the source instance ("Dev"): **Stable** (port 9091, a frozen state of the code for everyday use) and
**Test** (port 8090, for workflow tests) — described in the
[Three-Environment Setup](#three-environment-setup).

> Short IDs in parentheses such as `(182bd224)` or `TODO 63ecc124` are provenance references:
> commits of this repository or items in the accompanying MySuricates instance. They record when
> and why something was introduced — only the commit can be looked up from outside.

## One instance in a container (`deploy/`)

The machine only needs Docker. `deploy/Dockerfile` (target `runtime`) builds backend and frontend inside Docker
(about eight minutes the first time, afterwards from the layer cache), and the backend serves the
frontend itself — one instance is one container and one port.

```bash
printf 'MYSURICATES_PORT=9090\n' > deploy/instances/main.env
scripts/instance.sh up --name main       # build, start, wait for health
scripts/instance.sh key --name main      # API key for the login
scripts/instance.sh list                 # all instances with their port
scripts/instance.sh down --name main     # stop; data stays in the volumes, running runs are aborted
```

| Value in `deploy/instances/<name>.env` | Meaning |
|---|---|
| `MYSURICATES_PORT` | port on the machine (required) |
| `MYSURICATES_BIND` | address, default `127.0.0.1` — local only |
| `MYSURICATES_PRESET` | `core` (default), `research`, `full` |
| `MYSURICATES_PROJECTS` | projects folder, default: the folder above the clone, never all of `$HOME` |
| `MYSURICATES_DOCKER_SOCKET` | `1` mounts the Docker socket (for shell blocks with `container_image`) — gives the instance full control over Docker on the machine |
| `MYSURICATES_AI_FROM_HOST` | what of the machine's AI providers goes into the container: `login` (default), `programs`, `all` — see [AI providers in the container](#ai-providers-in-the-container) |
| `MYSURICATES_AI_PROVIDERS` | selection from `claude,codex,pi`, default: all present |
| `MYSURICATES_HARDENING_SEALING`, `MYSURICATES_HARDENING_REQUIRE_SEALED` | hardening, `true`/`false` |
| `MYSURICATES_ENVIRONMENT` | display name, e.g. `Prod` |
| `MYSURICATES_APT_REFRESH` | a new value (e.g. the month) makes the next `up` fetch the image's NodeSource and Docker repos afresh instead of taking keys, package lists and packages from the layer cache of the first build; also works as an environment variable (8b7a39b9) |
| `MYSURICATES_PREFIX` | prefix of the Docker names, default `mysuricates`: container and compose project `<prefix>-<name>`, volumes `<prefix>-<name>-data`/`-secrets`, image `<prefix>:local`; also works as an environment variable. The installer probe uses it to keep its instances apart from real ones on the same machine |

The image is built from the tracked files of the clone (`git ls-files`) as they are in the working
tree: changed tracked files go in, whatever exists only locally — such as pool definitions kept out
via `.git/info/exclude` — does not. Your own pool file therefore shows up in the container only once
it is committed or at least added with `git add`.

The instance uses the machine's AI CLIs, so nobody logs in twice. How much of them goes into the
container is set by `MYSURICATES_AI_FROM_HOST` ([AI providers in the container](#ai-providers-in-the-container));
on macOS only logins go in, no programs (a Mac binary does not start in a Linux container).
The user ID comes from `id(1)` at start, so files in mounted folders belong to the right user. Data
lives in the volume `mysuricates-<name>-data`, the integration and seal keys separately in
`mysuricates-<name>-secrets`. Without the Docker socket the instance reports Docker as "not set
up" — that is not an error.

Several instances — say Dev · Test · Prod — are several env files with their own ports. Stable and
Test (`docker-compose.stable.yml`, `docker-compose.test.yml`) stay separate files but take AI providers
and the Docker socket from the same place. Script tests without Docker:
`bash scripts/tests/test-host-mounts.sh`, `bash scripts/tests/test-instance.sh`,
`bash scripts/tests/test-deploy-files.sh`, `bash scripts/tests/test-staging-override.sh`.

## AI providers in the container

What a container gets from the machine is decided in one place: `scripts/lib/host-mounts.sh` (262948a4).
It applies to instances run by `instance.sh`, to Stable and Test run by `staging.sh` — also when they are
started from the UI — and to the dev container. The switches live outside the app and take effect on the
next start: neither a workflow nor anyone with the API key can change them. The "Startup configuration"
card in the settings shows them, read-only — and per provider whether a login is there, by the same rule
as the installer and the "AI providers" card (7296ad45).

| `…AI_FROM_HOST` | From the machine into the container | Setup | What an AI in the container can do with it |
|---|---|---|---|
| `login` (default) | the programs, read-only; exactly one login per provider: Codex `~/.codex/auth.json`, Pi `~/.pi/agent/auth.json`, Claude a token (below) | once `claude setup-token` for Claude | use and read the login. Settings, hooks, plugins, memory and transcripts of the machine stay outside. |
| `programs` | the programs only | log in once per instance: `scripts/instance.sh login --name X claude` or `scripts/staging.sh login stable claude` | the least. Every login lives in the instance's volume and can be revoked on its own. |
| `all` | all of `~/.claude`, `~/.codex`, `~/.pi` writable, plus `~/.local/bin` — the previous behaviour | none | see and change settings, hooks, plugins, memory and the transcripts of every project; changes there take effect on the machine later. Useful when workflows need your own Claude plugins or MCP servers. |

`…AI_PROVIDERS` picks the providers from `claude,codex,pi` (default: all); the others get neither program
nor login. With `login` and `programs` every provider has its own directory on the data volume
(`/app/data/providers/<provider>`, via `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and `PI_CODING_AGENT_DIR`):
whatever the programs write there — sessions, caches — never reaches the machine.

**Login command in the "AI providers" card.** In `programs` mode the starter knows the command that logs
in inside the container — the container itself has no idea how it was started, so the starter passes it
along as `MYSURICATES_LOGIN_COMMAND` (fixed text it already has anyway, never built from any input). The
backend only shows it, never runs it. Tryout: `scripts/tryout.sh login [claude|codex|pi]`. `instance.sh`
in `programs`: `scripts/instance.sh login`. `staging.sh` in `programs`: `scripts/staging.sh login stable`
or `scripts/staging.sh login test`. In the default `login` mode (the login comes from the machine, see
below) and for the dev container, which has no `login` subcommand of its own, it stays at the general
hint, as before this command existed.

**Why Claude needs a token.** Claude replaces `~/.claude/.credentials.json` every time it refreshes the
login (new file, then rename). A file mounted on its own would keep pointing at the old one inside the
container, and after the next refresh on the machine the container would be logged out. Codex and Pi
write their `auth.json` in place, so the file is enough there. `claude setup-token` creates the token
once (valid for a year); it goes into `~/.config/mysuricates/ai.env` as `CLAUDE_CODE_OAUTH_TOKEN=…` with
mode 600, and the installer offers to do that. Compose passes it on as environment at start (see
below). It lands neither in the image nor in the volume, but anyone with Docker access can read it via
`docker inspect`. Only a value that can be a token counts as a login: at least 40 characters, no spaces
and no angle brackets. A placeholder such as `<token>` stays out; the starters, the installer and the
settings report it as a missing login.

**Login via environment variable.** On the machine a set variable such as `OPENAI_API_KEY`,
`ANTHROPIC_API_KEY` or `GEMINI_API_KEY` counts as a login. It does not travel from the shell into the
container — a start from the interface would not even have it. In `login` mode it comes from
`~/.config/mysuricates/ai.env`, the same file as the Claude token:

- **Only with consent.** If codex or pi is logged in only via a variable for a container instance, the
  installer asks in the terminal "Copy it into …/ai.env?" (default no). Without a terminal it copies
  only with `--store-ai-env`; `--yes` alone never writes a secret. It never shows the value, the file
  gets mode 600, its directory 700; other entries stay, an older line of the same variable is replaced.
  By hand it works the same way: add `VARIABLE=value`, `chmod 600`.
- **Only the selected providers.** Which variables belong to a provider is in the backend's provider
  catalog (claude: `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY`; codex: `OPENAI_API_KEY`,
  `CODEX_API_KEY`; pi: `ANTHROPIC_API_KEY`, `ANTHROPIC_OAUTH_TOKEN`, `OPENAI_API_KEY`, `GEMINI_API_KEY`,
  `OPENROUTER_API_KEY`) — the same list the installer and the settings use to judge "logged in".
  At start `scripts/lib/host-mounts.sh` copies exactly the variables of the providers in
  `…AI_PROVIDERS` into `~/.config/mysuricates/container-env/<selection>.env` (600, rewritten at every
  start) and hands that copy to Compose; other lines of `ai.env` never get in. This holds for every way
  through this generator: `instance.sh`, `staging.sh`, a start from the interface and the dev container.
  Tryout deliberately mounts nothing from the machine and logs in inside the container.
- **Shared variables** are read by every selected provider that knows them. `ANTHROPIC_API_KEY` counts
  for pi **and** claude: with claude selected, claude in the container then logs in with the key instead
  of the token — the installer says so when copying.
- **`programs` and `all`** get nothing from `ai.env`: with `programs` every instance logs in itself,
  with `all` the whole directories come in. There the installer keeps naming the command that creates
  the login file (`codex login`, `pi` → `/login`); at start `instance.sh` reports a missing login
  (90b4422c).

**Project containers.** For a run the platform starts one container per project
(`automation-<project-id>`, whenever Docker is reachable — on Stable and Test through the socket).
Claude runs in it for commands during a run and in the terminal. It follows the same rule as its
instance: with `login` it gets the Claude program as a single file and the token (from the instance's
environment or from `~/.config/mysuricates/ai.env`), plus its own config directory on its `/tmp`, which
disappears with the container. With `programs` it gets no token. Only with `all` does it get
`~/.claude` and `~/.claude.json` read-write, as before. Without `claude` in `…AI_PROVIDERS` it gets
nothing of Claude.

| Way | Switches | Port without a switch | Docker socket without a switch |
|---|---|---|---|
| `scripts/instance.sh`, including the installer mode "Dev · Test · Prod" | `MYSURICATES_AI_FROM_HOST`, `MYSURICATES_AI_PROVIDERS`, `MYSURICATES_DOCKER_SOCKET`, `MYSURICATES_BIND` in `deploy/instances/<name>.env` | `127.0.0.1` | off |
| Stable and Test (`scripts/staging.sh`, start from the UI) | `STABLE_…` or `TEST_…` with `AI_FROM_HOST`, `AI_PROVIDERS`, `DOCKER_SOCKET`, `BIND` in `~/.config/mysuricates/staging.env` — read line by line, never executed; an environment variable of the same name wins | `127.0.0.1` | off |
| Dev container (`scripts/start-autotrigger-dev.sh --container`) | `AUTOTRIGGER_DEV_AI_FROM_HOST`, `AUTOTRIGGER_DEV_AI_PROVIDERS`, `AUTOTRIGGER_DEV_DOCKER_SOCKET`, `AUTOTRIGGER_DEV_CONTAINER_BIND` | `127.0.0.1` | off |
| Tryout (`scripts/tryout.sh`) | none | `127.0.0.1` | off |

**Secure by default, on every Docker way:** the port is reachable from this machine only, and the
instance gets no Docker socket. Only an explicit switch changes that.

**Opening the port.** `…BIND=0.0.0.0` makes the instance reachable from the local network (phone, PWA,
other machines) — bypassing the host firewall, because Docker adds its port forwards itself. A single
address, such as the LAN IP, works too; Stable and Test then stay reachable on `127.0.0.1` as well, so
the health check and the UI still find them. For Stable and Test `staging.sh` writes the port into the
override file; `docker-compose.stable.yml` and `docker-compose.test.yml` publish none themselves. For
remote access, put a VPN in front (Tailscale, see [Container path](#container-path)) rather than opening
the port.

**What is missing without the socket.** The instance then reports Docker as "not set up"; workflows
with ordinary shell and AI blocks keep running. Missing are:

- Project containers: a run starts no `automation-<project-id>` container, commands in the middle of a
  run and the terminal do not work.
- Shell blocks with `container_image` (agent containers) fail.
- Shell blocks that call `docker` themselves — say a workflow that builds images or starts
  `scripts/staging.sh` — fail.
- Starting Stable/Test from the UI of a container instance: that calls `docker compose`.

If you need that, set `STABLE_DOCKER_SOCKET=1` (or `TEST_…`, `AUTOTRIGGER_DEV_DOCKER_SOCKET=1`,
`MYSURICATES_DOCKER_SOCKET=1`) and restart the instance. With the socket a workflow can reach the whole
machine via `docker run -v /:/host …`; the boundary above then only guards against mistakes. Until this
change Stable, Test and the dev container had the socket by default and published their port on all
addresses: if you still need that, add the switches before the next `promote` or `test-promote`.

Limits: a Claude installed via npm (instead of the native installer) is not mounted with `login` and
`programs`. On macOS no programs are mounted (3dd32594). Codex's own sandbox (`--sandbox read-only`)
starts neither on the machine nor in the container (`bwrap`), independent of these switches. Tests
without Docker: `bash scripts/tests/test-host-mounts.sh`, `bash scripts/tests/test-staging-override.sh`.

## Tryout image

`deploy/Dockerfile` has two targets. `runtime` is the image of an installed instance (above; tools
and AI logins come from the machine). `tryout` builds on it and brings everything itself, so
MySuricates can be tried without installing it: AI CLIs (pi in the same fixed version as the
installer, [entwicklung.md → pi version](entwicklung.md#pi-version-installer-and-tryout)), `python3-pip`/`-venv`, JDK and Maven from
the build stage (`JAVA_HOME=/opt/java/jdk`), preset `research`. `HOME` lives on the data volume
(`/app/data/home`), so logins and the API key survive a rebuild. Deliberately left out: gcc/make,
Go, Rust, Gradle — if you need more, build `FROM mysuricates:tryout`.

```bash
scripts/tryout.sh build [--ref REF]   # newest vX.Y.Z tag (by version), else REF, e.g. HEAD
scripts/tryout.sh start [--port N]    # container mysuricates-tryout, 127.0.0.1:9095
scripts/tryout.sh login [claude|codex|pi] | key | shell | status | logs | stop
scripts/tryout.sh remove [--data]     # --data also deletes mysuricates-tryout-data/-secrets
scripts/tryout.sh export [FILE]       # docker save of both tags → .tar.gz for testers
```

The build uses `git archive <REF>`, never the working tree: the image matches the tag exactly,
uncommitted changes and the local `config/` never get in. The Dockerfile itself comes from the tag;
tags before v0.7.6 do not know the `tryout` stage yet and are refused. Tags are
`mysuricates:<version>-tryout` and `mysuricates:tryout` (last built), with the labels
`org.opencontainers.image.version`/`.revision`. For a new version: `build`, then `remove` (data
stays) and `start`. `MYSURICATES_APT_REFRESH=$(date +%Y-%m) scripts/tryout.sh build` fetches the
NodeSource and Docker repos afresh instead of from the layer cache — and with them the AI CLIs.

The container mounts nothing from the machine (no project folder, no Docker socket, no login) — a
shell block or `--dangerously-skip-permissions` only affects the container. The instance name
`tryout` is reserved in `instance.sh` so containers and volumes never overlap. A registry image is
a separate step (4c992a25). Tests without Docker: `bash scripts/tests/test-tryout.sh`.

## Docker Compose

`ai-automation-backend/docker-compose.yml` only builds the agent images (profile `build-only`) — no
instance. The only Docker way to run an instance with UI and AI providers is
[`deploy/`](#one-instance-in-a-container-deploy); there is no standalone backend container.

### Building the agent images

```bash
cd ai-automation-backend

# the month in the value refreshes the third-party repos of agent-devops
AGENT_APT_REFRESH=$(date +%Y-%m) docker compose --profile build-only build
```

### Services

| Service | Image | Description |
|---|---|---|
| `agent-node` | `Dockerfile.agent-node` | Node.js 22 Agent Container |
| `agent-python` | `Dockerfile.agent-python` | Python 3.12 Agent Container |
| `agent-devops` | `Dockerfile.agent-devops` | Ubuntu 24.04 Agent Container (gh, trivy, gitleaks, docker-cli) |
| `agent-java` | `Dockerfile.agent-java` | Temurin 21 Agent Container (maven, LibreOffice) |

The agent images are only built (`profiles: build-only`, all four with the command above),
not started directly via Compose. The backend creates and manages agent containers dynamically via
the Docker API. They are needed as follows:

- `agent-node` and `agent-python` are the base of the project container the platform starts per
  project — Node for project type `CODE`/`PERSONAL`, Python for `RESEARCH`/`CONTENT`/`BUSINESS`
  (`DockerContainerGateway.imageFor`).
- `agent-devops` and `agent-java` are used only when a shell block requests them as
  `container_image` — `agent-devops`, for instance, by the bundled templates `secret-scanner`,
  `container-vuln-scan`, `docker-image-build-push` and `github-project-bootstrap`. Without such
  workflows there is no need to build them.

## Dockerfiles

### Agent Node (`docker/Dockerfile.agent-node`)

- **Base:** `node:22-slim`
- **Tools:** git, curl, jq
- **User:** `agent` (UID 1000)
- **Claude CLI:** Directories prepared (binary + config mounted from host)
- **CMD:** `tail -f /dev/null` (container stays active for `docker exec`)

### Agent Python (`docker/Dockerfile.agent-python`)

- **Base:** `python:3.12-slim`
- **Tools:** git, curl, jq
- **Packages:** anthropic, pandas, numpy, requests, beautifulsoup4 — unpinned, behind `ARG PIP_REFRESH`:
  the same `AGENT_APT_REFRESH` as for agent-devops installs the current ones, otherwise they would stay
  at the state of the first build (8b7a39b9)

### Agent DevOps (`docker/Dockerfile.agent-devops`)

- **Base:** `ubuntu:24.04`
- **Tools:** git, curl, jq, bash, python3
- **GitHub CLI:** `gh` from the official APT repository
- **Security Tools:** gitleaks, trivy (binaries with SHA256 verification, multi-arch)
- **Docker:** Docker CE CLI (client only, no daemon)
- **Freshness:** the layers with third-party repos (gh, Docker) come last, behind `ARG APT_REFRESH`. A
  new value (Compose: `AGENT_APT_REFRESH`, e.g. the month) rebuilds them instead of taking keys and
  package lists from an old layer cache — a build once failed on `NO_PUBKEY` that way although the
  current keyring was fine (97e1529d). gitleaks and trivy are pinned.
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

Only builds the agent images (`profiles: build-only`), starts no service.

### `docker-compose.stable.yml`

Stable environment on port 9091:
- Container: `mysuricates-stable`
- Profile: `SPRING_PROFILES_ACTIVE=stable`
- Volumes: `stable-data` (persistent DB), `stable-secrets` (integration and seal keys, kept apart from the DB — 3fb2242c), `stable-frontend/` (read-only; the built UI that `scripts/staging.sh snapshot` copies there from `ai-automation-frontend/dist` — without this folder `staging.sh start` refuses to start), host project directories, backup volume (`~/.autotrigger/backups/stable`); AI providers, Docker socket (off by default) and port (only `127.0.0.1:9091` by default) come from the override file of `scripts/staging.sh` ([AI providers in the container](#ai-providers-in-the-container))
- Named Volume: `stable-data`

### `docker-compose.test.yml`

Test instance on port 8090 (uses `docker/Dockerfile.stable` as build base):
- Container: `mysuricates-test`
- Image: `ai-automation-backend-test`
- Profile: `SPRING_PROFILES_ACTIVE=test`
- Volumes: `test-data` (persistent DB), `test-secrets` (keys as for Stable), `stable-frontend/` (read-only, the same built UI as for Stable), `application-test.yml` (read-only), backup volume (`~/.autotrigger/backups/test`); AI providers, Docker socket and port (`127.0.0.1:8090`) as for Stable
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
| `AUTOMATION_HARDENING_<NAME>` | `provenance`, `private-network-guard`: `true`, others `false` | Hardening flags `PROVENANCE`, `SEALING` (requires provenance), `REQUIRE_SEALED_FOR_UNATTENDED` (requires sealing), `WEBHOOK_SIGNATURES` (requires module webhooks), `PRIVATE_NETWORK_GUARD` (iCal/RSS feeds only to public addresses), `PRIVATE_NETWORK_GUARD_HTTP_API` (the same for http-api, default `false`) — see [Workflow Hardening](architektur.md#workflow-hardening-optional) |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT` | `300` | Global Claude CLI timeout in seconds (declared in `application.yml`) |
| `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` | (empty) | Optional per-call token limit for Claude CLI |
| `AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED` | `true` | Tags and category of a workflow via AI (`suggestions-model`), after every create/change and at startup for untagged workflows — `false` saves these background calls (test and side instances) |
| `AUTOMATION_SSL_ENABLED` | `false` | Enable SSL/TLS |
| `AUTOMATION_SSL_KEYSTORE` | `./certs/dev.p12` | Path to PKCS12 keystore |
| `AUTOMATION_SSL_PASSWORD` | `mysuricates` | Keystore password |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker socket path |
| `AUTOMATION_CORS_ORIGINS` | `localhost:3000`/`:5173`, http and https | Allowed browser origins, replaces the default list — see [Where it listens](#where-it-listens) |
| `AUTOMATION_CLI_SKIP_PERMISSIONS` | `true` | Claude CLI without asking before tool use (`--dangerously-skip-permissions`) — see [What a workflow is allowed to do](#what-a-workflow-is-allowed-to-do) |
| `AUTOMATION_ALLOW_DOCKER_SOCKET_MOUNT` | `false` | Whether a shell block with `mount_docker_socket: true` gets the Docker socket in its agent container — see [Docker privileges](#docker-privileges--two-separate-layers) |
| `AUTOMATION_ALLOW_EXTRA_MOUNTS` | `false` | Whether shell blocks may mount additional host folders (`mounts`) into their agent container |
| `AUTOMATION_ALLOWED_MOUNT_PREFIXES` | (empty) | Comma-separated host path prefixes allowed for these mounts; empty = only the hard-blocked system paths apply |
| `AUTOMATION_ALLOWED_CONTAINER_IMAGES` | (empty = any image) | Allowlist for `container_image` in shell blocks — see [Container Image Allowlist](#container-image-allowlist) |

The table is a selection. All variables with their actual defaults, grouped by topic, are in
`ai-automation-backend/.env.example` ([Template and loading path](#template-and-loading-path)).

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
AUTOMATION_HARDENING_PRIVATE_NETWORK_GUARD=false
AUTOMATION_HARDENING_PRIVATE_NETWORK_GUARD_HTTP_API=false
```

```bash
# Maximal: every module and every hardening switch on (not for publicly reachable instances)
AUTOMATION_MODULES_PRESET=full
AUTOMATION_MODULES_UI_EDITABLE=true
AUTOMATION_HARDENING_PROVENANCE=true
AUTOMATION_HARDENING_SEALING=true
AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED=true   # seal workflows first, see below
AUTOMATION_HARDENING_WEBHOOK_SIGNATURES=true
AUTOMATION_HARDENING_PRIVATE_NETWORK_GUARD=true
AUTOMATION_HARDENING_PRIVATE_NETWORK_GUARD_HTTP_API=true
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
  is empty — it is then in `~/.autotrigger/api-key` and in the `app_settings` table; the log shows
  only its last four characters)
- Rate limiting active (120 req/min, configurable in Settings → Execution)
- SQLite at `./data/automation.db`

### Production

```bash
# deploy/instances/<name>.env, see table above
AUTOMATION_SECURITY_ENABLED=true
AUTOMATION_API_KEY=<secure-key>   # required: the env file ships no default
AUTOMATION_MODULES_PRESET=core       # optional: research | full; remote access: AUTOMATION_MODULES_REMOTE_ACCESS=true
AUTOMATION_RATE_LIMIT_ENABLED=true   # optional
```

- API key authentication active
- Rate limiting recommended
- Docker socket only when needed (`MYSURICATES_DOCKER_SOCKET=1`, see above) — gives the instance full control over Docker on the machine

**Note:** HikariCP pool size is set to 1. SQLite only allows one concurrent writer. Pool size=1 serializes all DB operations — no lock contention. Increasing pool size only makes sense when switching to PostgreSQL.

## Tailscale Remote Access

The `remote-access` module makes the backend reachable from outside the local network via
Tailscale — **for instances running from source on the host only.** None of the shipped images
(`deploy/Dockerfile`, `docker/Dockerfile.stable`) carries the Tailscale CLI, and a container is the
wrong place for it anyway: Tailscale would need its own privileges and its own secret inside the
container, gone again on every `compose down`. An earlier attempt at this
(`docker/Dockerfile.backend`) was removed for that reason.

### Host path

1. A local Tailscale install on the machine the instance runs on
2. Module `remote-access` enabled (`AUTOMATION_MODULES_REMOTE_ACCESS=true` or preset `full`) — off in
   the public default `core`; without it the `/system/remote-access/*` endpoints answer 404
   `MODULE_DISABLED`
3. Call the API endpoint: `POST /api/v1/system/remote-access/enable` — the backend starts Tailscale
   on the host
4. IP and hostname come back in the response; status afterwards via
   `GET /api/v1/system/remote-access/status` → `{ enabled, ip, hostname, inContainer }`. Visible in
   the frontend under Settings → Remote Access

### Container path

When the instance runs in a container (`MYSURICATES_RUNTIME=container`, or a set
`automation.instance.container` id — the same detection as the startup container report and the AI
provider status, no new heuristic), the status reports `inContainer: true` and the UI shows a hint
instead of the button: `POST .../remote-access/enable` fails with a clear error message in that case
instead of attempting a CLI call. Remote access works differently there:

1. Install Tailscale on the machine and log in (not in the container)
2. Expose whatever the container should publish from there, e.g. with
   `tailscale serve --bg --https=443 http://127.0.0.1:<port>` (port = the published container port,
   see `AUTOTRIGGER_DEV_CONTAINER_PORT`/`docker-compose.*.yml`)
3. No Tailscale state, no login and no token inside the container — just an ordinary local port that
   the machine forwards itself

## Healthcheck

`deploy/Dockerfile` defines a healthcheck (Compose keeps it unchanged):

```
GET http://localhost:9090/api/v1/system/health

Interval: 15s
Timeout: 5s
Retries: 3
Start Period: 90s
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

## Reverse Proxy

The UI signs in its WebSocket connection with a one-time ticket that the backend issues just before
(`WebSocketTicketService`). That is why the frontend enforces `transports: ['websocket']` and deliberately disables the SockJS fallback (HTTP polling):
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
| **platform data** | `previous_report` (output of the last successful run of the same workflow) and `open_todos` (open TODOs of the project), injected into **every** run — details in [WORKFLOW-GUIDE.md → System-Injected Variables](WORKFLOW-GUIDE.md#system-injected-variables) | always single-quoted | raw | only once the placeholder really appears in a shell command |

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

Neither backend nor frontend binds to loopback on its own: `server.address` is set nowhere, so the
backend listens on all interfaces; Vite runs with `host: true`. Started from source (Dev), the
instance is therefore reachable on the local network, not just on the machine itself. Every Docker
way instead publishes the port on `127.0.0.1` only by default — `instance.sh` (`MYSURICATES_BIND`, see
[One instance in a container](#one-instance-in-a-container-deploy)), Stable and Test
(`STABLE_BIND`/`TEST_BIND`), the dev container (`AUTOTRIGGER_DEV_CONTAINER_BIND`) and the tryout image
([table](#ai-providers-in-the-container)) — changing that also bypasses the usual host firewall rules
(Docker's port publishing).

**CORS** does not affect an installed instance: the backend serves the UI itself, so page and API
share one origin. The list only matters where the two have different origins — with the Vite dev
server (its proxy rewrites the host, not the `Origin`) and behind a reverse proxy that rewrites the
host. Then these are admitted:

- by default only the dev servers on this machine: `localhost:3000` and `localhost:5173`, each over
  http and https;
- with the module `remote-access`, additionally the instance's **own** Tailscale address — the IPv4
  from `tailscale ip -4` and the MagicDNS name from `tailscale status --json`, on any port, over http
  and https. The instance asks Tailscale at runtime and keeps the answer for a minute; without a
  running Tailscale nothing is added. Foreign tailnets and other `100.*` hosts stay out;
- whatever `AUTOMATION_CORS_ORIGINS` (`automation.cors.allowed-origins`) lists. The variable replaces
  the default list — if you open the dev server via its LAN IP, say, list that address together
  with the localhost entries. Patterns with `*` are possible, but `*` also covers dots:
  `http://100.*:3000` matches `http://100.evil.example:3000`. Exact origins are the safe choice.

The same rule applies to the WebSocket handshakes (`/ws`, `/ws/terminal/**`) and to the target of
the OAuth popup. CORS grants no credentials: authentication uses the `X-API-Key` header, there are
no cookies.

**CORS is not access control** — it only governs which origin a browser accepts and stops nobody
from calling the API directly. Having Tailscale in front does not make the application a secured
system.

### What is reachable without an API key

Authentication uses the `X-API-Key` header and covers `/api/**` and `/actuator/**`. Exempt are:

| Path | Purpose |
|---|---|
| `/api/v1/system/health`, `/system/info`, `/system/auth-status` | Status checks before login |
| `/api/v1/system/jvm-memory` | Heap usage as exactly three numbers (`jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_heap_used_percent`) for the performance audit |
| `/actuator/health` | Docker healthcheck |
| `/ws/**` | WebSocket handshake (the key is checked there as a query parameter) |
| `/api/v1/webhook/trigger/**` | Public webhook entry point |
| `/api/v1/integrations/*/callback` | OAuth callbacks |

The rest of the actuator surface (`/actuator/metrics`, `/actuator/info`) requires the key: metrics
reveal route names, load profile and memory situation to anyone who can reach the port. Measuring
memory without a key therefore gets you only the three numbers from `/api/v1/system/jvm-memory`.

Everything outside `/api/**` and `/actuator/**` is open as well (`anyRequest().permitAll()`) — that
covers the bundled frontend and any unmapped path.

**Important:** if no API key is configured, or `automation.security.enabled=false`, *every* request
counts as authenticated. An empty key therefore switches authentication off entirely rather than
tightening it.

### Host-reaching endpoints behind the API key

Some endpoints reach the host or hand out operational data. They are not in the exemption list
above and require the API key like every other path under `/api/**`:

| Endpoint | What it does | Additional guard |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Lists workflows whose latest run failed (read-only) | Module `debug`, off in the default preset `core` (`@RequiresModule`, `WorkflowDebugController`) |
| `POST /api/v1/system/open-path` | Opens a path via `xdg-open` on the host desktop | Path is normalized, `/etc`, `/proc`, `/sys`, `/dev`, `/var/run` are blocked (`FileBrowserService`) |
| `GET /api/v1/system/browse` | Lists the subdirectories of a path (names only, no hidden ones) | Root and the same system directories blocked (`FileBrowserService`) |
| `GET /api/v1/system/backups/{filename}` | Downloads a database backup | Only file names from the backup directory, `..`, `/` and `\` yield 404 (`DatabaseBackupService.getBackupFile`) |

The boundary here is the API key, not the individual endpoint. Whoever holds the key can already
start shell workflows with the user's rights ([What a workflow is allowed to do](#what-a-workflow-is-allowed-to-do)).
These endpoints do not extend that, they are more convenient routes to things a workflow can do
anyway. A database backup does, however, contain every run, output and setting of the instance and
is as confidential as the key itself.

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
2. **The socket in the backend container** (`/var/run/docker.sock`) is independent of point 1 and gives
   the application full control over Docker on the host. For `instance.sh`, Stable, Test and the dev
   container a switch decides it, off by default ([AI providers in the container](#ai-providers-in-the-container)).

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
- Leave the Docker socket off (the default on every Docker way) and set `…DOCKER_SOCKET=1` only when
  project containers, the terminal or container blocks are needed.
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

- **`automation.scheduler.skip-until`** (`AUTOMATION_SCHEDULER_SKIP_UNTIL`, ISO instant, e.g. `2026-07-30T22:00:00Z`) — for time windows. Due schedules are skipped **and** their `nextRunAt` is advanced past the window, so there is no catch-up stampede at the end. It expires on its own; an unparseable value is ignored (fail open). Read only at backend startup. The UI shows the pause: a banner on the Schedules page, a note on the dashboard, and as “next run” in list, calendar and dashboard the first occurrence after the pause (API: `pausedUntil`, see [API reference](api-referenz.md#get-apiv1schedules)).
- **Maintenance mode** (`POST /api/v1/schedules/maintenance`, pause button on the Schedules page) — runtime switch without an end date, persisted in `app_settings`. Note: `nextRunAt` is *not* advanced, so when you switch it off, every schedule that became due fires at once.

For local, non-committed overrides use `ai-automation-backend/config/application.yml` — Spring Boot loads that directory in addition to the classpath config and with higher precedence (the directory is in `.gitignore`).

### Stable Environment (Port 9091)

The stable environment is a Docker container containing the backend + embedded React frontend.

**Key Files:**
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-based (glibc for Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — data, frontend, projects; AI providers and the socket via `scripts/staging.sh write-override stable`
- `ai-automation-backend/src/main/resources/application-stable.yml` — Port 9091, auto-start disabled, frontend serving enabled

**Keys:** the integration and seal keys live in the volume `stable-secrets`, apart from the database.
Images from before 2026-09-23 still kept the integration key in the database; the first start with a newer
image moves it onto the volume and deletes it from the database. Without the volume it would then sit in
the container layer and be lost at the next `staging.sh stop` or `promote` — and with it every encrypted
integration login (3fb2242c). Test does the same with `test-secrets`; `test-reset` deletes both volumes,
which is intended there.

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
./scripts/staging.sh write-override stable|test   # write the override file (what comes from the machine), print its path
./scripts/staging.sh login stable|test claude     # own login inside the container (only *_AI_FROM_HOST=programs)
```

`MYSURICATES_APT_REFRESH=$(date +%Y-%m) ./scripts/staging.sh promote` (likewise `test-promote`) fetches
the image's NodeSource and Docker repos (`Dockerfile.stable`) afresh instead of taking them from the
layer cache of the first build (8b7a39b9).

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
