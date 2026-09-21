# Getting Started — MySuricates on a new machine

This guide takes MySuricates from `git clone` to "running in the browser" — on a fresh **Linux** or
**macOS** machine that may not have Java, Node or Docker yet. On **Windows** the same way runs in
WSL2 (Ubuntu), see section 5.

> **Before you start — the trust model in three sentences.** Shell blocks and AI calls run with
> *your* permissions (on the machine, or with your mounted folders in the container); the AI CLIs do
> not ask before using tools by default. The instance set up by the installer listens on
> `127.0.0.1` only (only `--bind` opens it to the network), and some endpoints (health checks,
> WebSocket handshake, webhook inbox, OAuth callbacks) need no API key. MySuricates is built for
> local use by one trusted person — for remote access put a VPN in front, not an open port.
> Full text: [Trust model and network exposure](deployment.md#trust-model-and-network-exposure).

---

## 1. What you need

Almost nothing — the installer checks what is missing, shows the commands and installs it after
asking, in the same run (apt, dnf, pacman, zypper or Homebrew; Java and Node as a verified download
without sudo when the package manager is too old).

| Way | Needed on the machine |
|---|---|
| **One instance in a container** (suggested) | Docker |
| **One instance on this machine** | Java 21, Node 20.19+/22.12+/24+ (build only), git, curl |
| **Staged — Dev · Test · Prod** | Docker |

For AI workflows you need **an AI CLI of your choice** — `claude`, `codex` or `pi` — that you are
logged in to. MySuricates starts without one; the installer shows which it finds.

---

## 2. Install

```bash
git clone <repository-url> mysuricates
cd mysuricates
./scripts/setup.sh              # English; in German: ./scripts/setup.sh --lang de
```

The installer guides you through eight steps: Welcome · Mode · Checks · Modules · AI · Ports · Build ·
Done. `b` goes one step back, `q` quits.

- **Mode:** one instance in a container (only Docker needed), one instance directly on this machine,
  or staged **Dev · Test · Prod** — plan and build workflows in Dev (Factory, assistant), check them
  in Test, run them reliably in Prod. For now workflows move by export and import in the interface;
  an automatic sync between the three is not set up yet.
- **Modules:** Standard (smallest surface), Research, Full or your own selection incl. hardening.
  Staged: Dev gets all modules, Test and Prod get Standard with seals ("unattended runs only sealed").
- **AI:** claude, codex and pi side by side with their login state; you pick the default provider.
  In the container the instance uses the CLI **and** your login from the machine — no second login
  (on macOS only the login; the program lives in the image).
- **Ports:** default 9090, busy ports are skipped. For the container also the projects folder the
  instance sees (default: the folder above the clone, never your whole home).

Without questions, e.g. for scripts: the choices of the steps exist as flags (`--help` lists them);
hardening, ui-editable and other settings go into the instance file (section 4).

```bash
./scripts/setup.sh --yes                                   # defaults: container, Standard, port 9090
./scripts/setup.sh --yes --runtime host --port 9190        # on this machine, another port
./scripts/setup.sh --yes --mode staged                     # Dev · Test · Prod
./scripts/setup.sh --check                                 # only check and show the plan
./scripts/setup.sh --help                                  # all flags
```

Without a terminal (pipe, CI) and without `--yes` the installer only checks.

---

## 3. Log in and use it

At the end the installer shows the address (e.g. http://localhost:9090) and the last characters of
the API key. The full key:

```bash
scripts/instance.sh key --name main
```

On **first visit** a **login screen** with an input field appears — paste the key, the browser
remembers it. The key is **not** in the backend log (only masked there).

Start, stop and inspect — the same command for every way:

```bash
scripts/instance.sh up|down|status|logs|key --name main
scripts/instance.sh list                         # all instances with their port
```

### What now?

The application is running. A first way in, without building anything:

1. **Create a project** — every workflow belongs to a project; a project points at a directory on
   your machine (in the container: inside the projects folder).
2. **Take a template** — *Templates* holds 66 ready-made workflows. Pick one, copy it into the
   project, run it. The run shows step by step what happens.
3. **Look at the run** — the detail view shows per block its duration, its status and whether it was
   deterministic or called a model.

Build your own workflows with the [workflow guide](WORKFLOW-GUIDE.md) next to you.

### What Now?

The application is running. One way in without building anything:

1. **Create a project** — every workflow belongs to a project, and a project points at a
   directory on your machine.
2. **Take a template** — under *Templates* there are 66 ready-made workflows. Pick one, copy it
   into the project, start it. The run shows step by step what happens.
3. **Look at the run** — the detail view shows, per block, duration, status and whether it was
   deterministic or called a model.

For building your own workflows afterwards, keep the
[workflow guide](WORKFLOW-GUIDE.md) open alongside.

---

## 4. Change settings later

Run `./scripts/setup.sh` again → **Change settings** (port, modules, AI provider). Or edit
`deploy/instances/main.env` and run `scripts/instance.sh up --name main`:

- `MYSURICATES_PORT`, `MYSURICATES_BIND`, `MYSURICATES_PRESET`, `MYSURICATES_PROJECTS`
- `MYSURICATES_DOCKER_SOCKET=1` (or `setup.sh --docker-socket`) — allows shell blocks with
  `container_image`, but gives the instance full control over Docker on the machine
- every `AUTOMATION_…` line reaches the instance unchanged, e.g. `AUTOMATION_MODULES_WEBHOOKS=true`
  or `AUTOMATION_AI_DEFAULT_PROVIDER=CODEX`

**Settings → Modules & hardening** lists the modules with maturity and risk. Details on instances:
[deployment.md](deployment.md#one-instance-in-a-container-deploy).

---

## 5. Windows

On Windows MySuricates runs in **WSL2**: `wsl --install -d Ubuntu`, then section 2 in the Ubuntu
shell. The browser on Windows reaches `http://localhost:9090` directly. Native Windows is not
supported yet (shell blocks need bash).

---

## 6. For contributors: source setup

Working on MySuricates itself needs Vite with hot reload instead of a built interface:

```bash
./scripts/setup.sh --from-source --start     # tools, npm ci, runtime JAR, module preset, start
./scripts/start-autotrigger-dev.sh           # later: backend :9090 + Vite :3000
```

The dev starter shows the API key masked after start (`--show-key` shows it in full); it lives in
`~/.autotrigger/api-key`. Details: [entwicklung.md](entwicklung.md).

---

## 7. Optional: HTTPS in the Dev Frontend (source setup)

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

`config/SslPasswordGuard` checks this at backend startup: if `AUTOMATION_SSL_ENABLED=true` is set
and `AUTOMATION_SSL_PASSWORD` is still the dev default `mysuricates`, boot aborts with an
`IllegalStateException`. This ensures an HTTPS server never runs with the password anyone can read
in the repo — SSL off with the default password remains allowed.

---

## 8. Troubleshooting

| Problem | Fix |
|---|---|
| A tool is missing or too old | `./scripts/setup.sh --check` shows what is missing and the exact command |
| `permission denied` on the Docker socket | the installer added you to the `docker` group — log out and back in once |
| Docker installed but not running | Linux: `sudo systemctl start docker`, macOS: open Docker Desktop |
| Port in use | the installer takes the next free one; change it in `deploy/instances/main.env` |
| AI block fails, "not logged in" | log in to the CLI: `claude auth login`, `codex login` or `pi` → `/login` |
| Instance does not start | `scripts/instance.sh logs --name main`; setup logs in `~/.local/state/mysuricates/setup-logs/` |
| Settings show "Docker not set up" | not an error — only shell blocks with `container_image` need Docker |

---

## Further Reading

- [Development](entwicklung.md) — Build, tests, conventions
- [Deployment](deployment.md) — Docker, environment variables, staging
- [Architecture](architektur.md) — System overview, block types
- [Workflow Guide](WORKFLOW-GUIDE.md) — Building workflows
