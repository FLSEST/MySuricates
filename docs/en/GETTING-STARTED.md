<!-- last-sync: 2026-06-29 from: docs/GETTING-STARTED.md -->

# Getting Started — MySuricates on a New Linux Machine

This guide takes MySuricates from `git clone` to "running in the browser" — on a fresh
**Linux** machine that may not yet have Java, Node, or Docker installed.

Recommended approach: **directly on the host from source** (not containerized). This is
simpler and more reliable because the `claude` CLI runs on the host anyway, no JAR rebuild
is required, and the database/migrations work without volume mapping. **Docker is optional**
and only needed for container-execution workflow blocks.

> **Before you start — the trust model in three sentences.** Shell blocks and AI calls run on the
> host with *your* privileges, not in a container, and the Claude CLI does not prompt before using a
> tool by default. Backend and frontend listen on all network interfaces, and a few endpoints
> (health checks, the WebSocket handshake, the webhook entry point, OAuth callbacks) need no API
> key. MySuricates is built for local operation by a single trusted person — for remote access put
> a VPN in front rather than opening the port. Full detail:
> [Trust Model and Network Exposure](deployment.md#trust-model-and-network-exposure).

---

## 1. What You Need

| Tool | Version | Required? | Purpose |
|---|---|---|---|
| Java (JDK) | 21+ | ✅ | Build/run the backend |
| Maven | — | ⬜ optional | The wrapper `ai-automation-backend/mvnw` is included and downloads Maven 3.9.9 itself (only Java + curl required) |
| Node.js | 20+ | ✅ | Frontend (version in `ai-automation-frontend/.nvmrc` → `nvm use`) |
| npm | 10+ | ✅ | Frontend dependencies |
| git, curl | current | ✅ | Basics |
| **Claude account + `claude` CLI** | current | ✅ for AI | AI workflows run via `claude` on the host |
| Docker | 24+ | ⬜ optional | only for container-execution blocks |

> **System Maven not required:** The Maven wrapper (`mvnw`) automatically downloads the correct
> Maven version on the first build. `scripts/setup.sh` and the start scripts use it automatically.
> Node users with `nvm` get the correct Node version with `nvm use` (in the frontend directory).

Each person needs their **own Claude account** and logs in themselves with `claude login`.

Much of the setup happens **automatically on the first backend start**: SQLite database +
Flyway migrations, API key, encryption key for integrations, built-in skills/templates.
None of this needs to be created manually.

---

## 2. Setup in One Command

```bash
git clone <repository-url> autotrigger
cd autotrigger
./scripts/setup.sh
```

`scripts/setup.sh` checks all prerequisites and lists the appropriate install command for each
missing or outdated tool (apt/dnf/pacman detected), installs frontend dependencies, and
pre-compiles the backend. It **never** accesses the database or the REST API, and without
`--install` it installs nothing on its own.

Options:

```bash
./scripts/setup.sh --install   # automatically install missing tools via apt (otherwise: print hints)
./scripts/setup.sh --start     # start directly after a successful setup
```

If a required tool is missing, the script aborts with a clear list of missing items and install
commands. Simply run `./scripts/setup.sh` again after installing.

---

## 3. Starting

```bash
./scripts/start-autotrigger-dev.sh
```

Starts the backend (port **9090**) and frontend (port **3000**) in the background and waits until
both are reachable. Then open in a browser:

- **Frontend:** http://localhost:3000
- **Backend API:** http://localhost:9090/api/v1

Logs are written to `~/.local/state/autotrigger-dev/{backend,frontend}.log`.

> Without a dev certificate, the frontend runs on **HTTP**. For HTTPS (e.g. for PWA installation),
> see Section 7.

---

## 4. Entering the API Key

An API key is generated automatically on the first backend start. **`start-autotrigger-dev.sh`
displays it in the terminal after startup** — by default **masked** (only the last 4 characters),
so it does not accidentally end up in terminal scrollback/logs/screen-sharing. Display it in full
with the flag:

```bash
./scripts/start-autotrigger-dev.sh --show-key    # or: ./scripts/setup.sh --show-key --start
```

The key is always stored in plaintext in `~/.autotrigger/api-key` (and in the backend log):

```bash
cat ~/.autotrigger/api-key
```

When you **first open** `localhost:3000`, a **login screen** with an input field appears
automatically (it does not show the key — you paste it there). After that, the key is saved in the
browser and you will not be asked again. It is used as the `X-API-Key` header for all API calls.

To provide your own key instead of letting one be generated:

```bash
export AUTOMATION_API_KEY="your-key"   # set before starting
```

The API key requirement can be disabled for purely local experiments
(`AUTOMATION_SECURITY_ENABLED=false`) — leave it enabled for normal operation.

---

## 5. Setting Up the Claude CLI (for AI Workflows)

AI blocks execute `claude` on the host. One-time setup:

```bash
npm install -g @anthropic-ai/claude-code   # if not already installed
claude login                               # log in with your own Claude account
```

`scripts/setup.sh` checks whether `claude` is present and (heuristically) logged in, and will
point this out if not. Without `claude` the app still starts — only AI workflows will not function.

---

## 6. Optional: Docker (only for Container Blocks)

The app and all non-container workflows (Simple, Shell, Agentic on the host …) run **without**
Docker. Only workflow blocks that execute in isolated containers require a running Docker daemon:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER     # then log in again
```

---

## 7. Optional: HTTPS in the Dev Frontend

For HTTPS at https://localhost:3000 (self-signed, e.g. for PWA installation or Tailscale):

```bash
./scripts/generate-dev-cert.sh
```

Generates `certs/dev.{crt,key,p12}`. The PKCS12 keystore is created with the dev password
`mysuricates` — the same value `application.yml` carries as the default of `AUTOMATION_SSL_PASSWORD`.
That is a known development default for a self-signed certificate, not a secret. If you also want
HTTPS on the backend, set `AUTOMATION_SSL_ENABLED=true` and choose your own value via
`AUTOMATION_SSL_PASSWORD`; the script picks it up from the environment.

Vite then serves HTTPS automatically; the browser warns once
about the self-signed certificate. Without this script it stays on HTTP — perfectly sufficient for
local use.

---

## 8. Troubleshooting

| Problem | Solution |
|---|---|
| `mvn`/`java`/`node` "not found" | Run `./scripts/setup.sh` — it provides the exact install command |
| Java too old (`requires 21+`) | Install JDK 21 (apt: `openjdk-21-jdk`, otherwise https://adoptium.net) |
| `claude: command not found` | `npm install -g @anthropic-ai/claude-code` + `claude login` |
| AI block fails, "not logged in" | Run `claude login` |
| Port 9090 / 3000 in use | Stop the occupying process (`lsof -i :9090`) or set `AUTOMATION_PORT` |
| Frontend starts but script reports "not reachable" | Fixed — the start script now checks both HTTP **and** HTTPS |
| Container block fails | Install/start Docker (Section 6) |
| `permission denied` on Docker socket | `sudo usermod -aG docker $USER`, then log in again |
| Self-signed cert warning in browser | Expected in dev; accept it, use Section 7, or skip the cert |

---

## Further Reading

- [Development](entwicklung.md) — Build, tests, conventions
- [Deployment](deployment.md) — Docker, environment variables, staging
- [Architecture](architektur.md) — System overview, block types
- [Workflow Guide](WORKFLOW-GUIDE.md) — Building workflows
