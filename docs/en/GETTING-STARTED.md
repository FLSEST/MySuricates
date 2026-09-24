# Getting Started — MySuricates on a new machine

This guide takes MySuricates from `git clone` to "running in the browser" — on a fresh **Linux** or
**macOS** machine that may not have Java, Node or Docker yet. On **Windows** the same way runs in
WSL2 (Ubuntu), see section 5.

> **Before you start — the trust model in three sentences.** Shell blocks and AI calls run with
> *your* permissions (on the machine, or with your mounted folders in the container); the AI CLIs do
> not ask before using tools by default. The instance set up by the installer listens on
> `127.0.0.1` only (only `--bind` opens it to the network; the installer then also names the machine's network address), and some endpoints (health checks,
> WebSocket handshake, webhook inbox, OAuth callbacks) need no API key. MySuricates is built for
> local use by one trusted person — for remote access put a VPN in front, not an open port.
> Full text: [Trust model and network exposure](deployment.md#trust-model-and-network-exposure).

---

## Just trying it out — no installation

If you first want to get to know MySuricates, all you need is Docker: start a container that brings
everything along — AI CLIs (`claude`, `codex`, `pi`), Python with pip/venv, Node, JDK and Maven.
Nothing from the machine is mounted — no project folder, no Docker socket, no existing login.
Workflows only see what lives in the container; create your own projects under `/app/data/home` or
clone them there.

With the repository (builds from the newest release tag):

```bash
scripts/tryout.sh build          # image mysuricates:<version>-tryout
scripts/tryout.sh start          # http://localhost:9095, shows the API key
scripts/tryout.sh login          # AI login inside the container (claude; also: login codex / login pi)
scripts/tryout.sh remove --data  # container, data and logins gone again
```

Without the repository, from an image file (`scripts/tryout.sh export` creates it):

```bash
docker load < mysuricates-<version>-tryout.tar.gz
docker run -d --name mysuricates-tryout -p 127.0.0.1:9095:9090 \
  -e AUTOMATION_CORS_ORIGINS=http://localhost:9095 \
  -v mysuricates-tryout-data:/app/data -v mysuricates-tryout-secrets:/app/secrets mysuricates:tryout
docker exec -u 1000 mysuricates-tryout cat /app/data/home/.autotrigger/api-key   # API key
docker exec -it -u 1000 mysuricates-tryout claude                                  # AI login
```

Instead of logging in you can pass a key as an environment variable (`-e ANTHROPIC_API_KEY=…`).
The start page shows whether a login has arrived.
Without a login only shell and deterministic blocks run — enough for the blueprints outside the
`AI` category. For working on your own projects on the machine, the installer (section 2) is the
right way. Details: [Deployment → Tryout image](deployment.md#tryout-image).

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
logged in to — via its login file or an environment variable (`CLAUDE_CODE_OAUTH_TOKEN`,
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY` …). An instance in a container only sees login files, for Claude a
token ([Deployment → AI providers in the container](deployment.md#ai-providers-in-the-container)).
MySuricates starts without one; the installer shows which it finds,
and so does the start page of the interface — with the command for whatever is missing.

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
  The names say little on the first run — that is fine: the choice is not final (section 4); when
  in doubt take Standard. What the modules do is in [architektur.md](architektur.md) and
  [deployment.md](deployment.md).
  Staged: Dev gets all modules, Test and Prod get Standard with seals ("unattended runs only sealed").
- **AI:** claude, codex and pi side by side with their login state; you pick the default provider.
  If pi is missing, the installer offers to install it via npm (default no, without a terminal
  `--with-pi`); for claude and codex it names the command. Logging in is up to you.
  In the container the instance uses the CLI **and** your login from the machine — no second login
  (on macOS only the login; the program lives in the image).
- **Ports:** default 9090, busy ports are skipped — not the one the running instance itself holds
  when it is set up again. For the container also the projects folder the
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

If Java or Node from the package manager are too old, the installer downloads them to
`~/.local/share/mysuricates`. The instance finds them there on its own, your shell only with a line in
your profile — otherwise `node`, `npm` and whatever `npm install -g` puts there (codex, pi) are missing:

```bash
. ~/.local/share/mysuricates/env.sh      # in ~/.profile (bash with ~/.bash_profile: there; macOS: ~/.zprofile)
```

In the guided run the installer offers to add the line; with `--yes` it only shows it.

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

**After a reboot of the machine:** an instance in a container starts again by itself once the Docker
service runs. An instance on this machine (`--runtime host`) does **not** start by itself — then run
`scripts/instance.sh up --name main`. The installer does not set up an autostart for it.

### What now?

The application is running. A first way in, without building anything:

1. **Create a project** — every workflow belongs to a project; a project points at a directory on
   your machine (in the container: inside the projects folder). To try things out there is the
   **example project**: the dashboard, workflows and projects offer it while no project exists yet.
   One click, and its example workflows run without AI and without any setup. The dashboard gets
   a view of its own that shows only this project.
2. **Take a template** — *Templates* holds 66 ready-made workflows. Pick one, copy it into the
   project, run it. The run shows step by step what happens.
3. **Look at the run** — the detail view shows per block its duration, its status and whether it was
   deterministic or called a model.

Along the way, short **hints** explain the pages and states you meet for the first time, such as a
waiting approval or a failed run. Each can be dismissed and brought back in its place; *Settings →
General* switches them all off.

Build your own workflows with the [workflow guide](WORKFLOW-GUIDE.md) next to you.

---

## 4. Change settings later

Run `./scripts/setup.sh` again → **Change settings** (port, modules, AI provider). With `--yes` and
without `--reconfigure`, an existing instance file stays as it is: the steps then show its values
("from deploy/instances/main.env"), and the installer names flags that do not apply against it;
`--reconfigure` rewrites the file. Or edit `deploy/instances/main.env` and restart the instance
(`scripts/instance.sh down --name main`, then `up --name main`):

- `MYSURICATES_PORT`, `MYSURICATES_BIND`, `MYSURICATES_PRESET`, `MYSURICATES_PROJECTS`
- `MYSURICATES_DOCKER_SOCKET=1` (or `setup.sh --docker-socket`) — allows shell blocks with
  `container_image`, but gives the instance full control over Docker on the machine
- every `AUTOMATION_…` line reaches the instance unchanged, e.g. `AUTOMATION_MODULES_WEBHOOKS=true`
  or `AUTOMATION_AI_DEFAULT_PROVIDER=CODEX`

**Settings → Modules & hardening** lists the modules with maturity and risk. Details on instances:
[deployment.md](deployment.md#one-instance-in-a-container-deploy).

### Update

After `git pull`, stop the instance once and start it again — `up` then builds from the new state:

```bash
git pull
scripts/instance.sh down --name main
scripts/instance.sh up --name main
```

In a container, `up` builds the image anew every time (unchanged layers come from the cache). On the
machine, `instance.sh` notes the commit when it builds and builds anew as soon as the clone is on
another one; `up` does not rebuild a running instance but reports that it still shows the old state.
`scripts/instance.sh built --name main` shows which commit the build comes from. Your data stays in
both cases. Running `./scripts/setup.sh` again also brings the instance to the new state.

A restart aborts the workflow runs that are running at that moment. Before the installer restarts a
running instance, it therefore asks the instance whether any are running (read-only, with the key from
`instance.sh key`). If some are, it asks you — default: do not restart; with `--yes` it leaves the
instance running and names the command for later. If the instance does not answer, it goes ahead as
before and says so. `scripts/instance.sh down` names how many runs it aborts before it stops.

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
| AI block fails, "not logged in" | log in to the CLI: `claude auth login`, `codex login` or `pi` → `/login`; the start page and the settings show per provider whether program and login are found |
| `npm`, `node` or `pi` not found after the installer | Node came as a download: `. ~/.local/share/mysuricates/env.sh`, permanently as a line in your profile (section 2) |
| Instance does not start | `scripts/instance.sh logs --name main`; setup logs in `~/.local/state/mysuricates/setup-logs/` |
| Not reachable after a reboot of the machine | instance on the machine: `scripts/instance.sh up --name main` (no autostart); in a container: starts with the Docker service |
| Settings show "Docker not set up" | not an error — only shell blocks with `container_image` need Docker |

---

## Further Reading

- [Development](entwicklung.md) — Build, tests, conventions
- [Deployment](deployment.md) — Docker, environment variables, staging
- [Architecture](architektur.md) — System overview, block types
- [Workflow Guide](WORKFLOW-GUIDE.md) — Building workflows
