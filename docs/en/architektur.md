# Architecture

MySuricates is a self-hosted workflow platform: a Spring Boot backend with a web interface, in
which processes consist of blocks — some deterministic (shell, branch, loop), some AI calls.
This page describes its internal structure. For getting started see
[README.md](../../README.md); for building workflows see
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md).

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up. This page describes the current state; how
> it came about is in Git. Tables and fields: [datenmodell.md](datenmodell.md).

> The flows on this page also exist as diagrams: [docs/en/diagramme.md](diagramme.md)
> — engine, control and states, memory.

## System Overview

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 19 PWA (Vite Dev Server :3000 / static)          │
└──────────┬──────────────────────┬───────────────────────┘
           │ REST /api/v1/*       │ WebSocket /ws
           │ (Vite Proxy)         │ (STOMP/SockJS)
           ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│         Spring Boot Backend Dev (:9090)                 │
│                                                         │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐             │
│  │Controller│→ │ Service  │→ │ Repository │             │
│  └─────────┘  └──────────┘  └──────┬─────┘             │
│       │                            │                    │
│       │  ┌──────────────┐    ┌─────▼─────┐             │
│       └→ │ WebSocket    │    │  SQLite   │             │
│          │ (STOMP)      │    │ (WAL)     │             │
│          └──────────────┘    └───────────┘             │
│                                                         │
│  ┌──────────────────────────────────────┐               │
│  │         Docker Client API            │               │
│  └──────────────┬───────────────────────┘               │
└─────────────────┼───────────────────────────────────────┘
                  │ /var/run/docker.sock
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Docker Engine                          │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │agent-node    │  │agent-python  │                     │
│  │(Node 22)     │  │(Python 3.12) │                     │
│  └──────────────┘  └──────────────┘                     │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │agent-devops  │  │agent-java    │                     │
│  │(Ubuntu 24.04)│  │(Temurin 21)  │                     │
│  └──────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────┘

Stable (:9091) and Test (:8090) run as separate Docker containers.
```

## Three Environments

Next to the dev instance running from source (frontend 3000, backend 9090), **Stable** (9091) and
**Test** (8090) run as Docker containers when needed; neither starts on its own. Setup, start and
definition sync: [deployment.md → Three-Environment Setup](deployment.md#three-environment-setup).

## Module System (startup configuration)

Besides the always-on core (workflows, runs, projects, TODOs with TodoTrigger, schedules, activity/DLQ, gates, pool, templates, docs, settings) there are **optional modules**. They are decided **at startup** and by default **cannot be switched via REST**: enabling a module requires write access to the host configuration and a restart. A leaked API key therefore cannot re-enable network, credential or host-access features. On an instance that is not publicly reachable, switching in the UI can be allowed with `automation.modules.ui-editable: true` (see below).

**Configuration** — preset plus per-module override, the override wins:

```yaml
automation:
  modules:
    preset: core        # core (default) | research | full
    webhooks: true      # additionally enable a single module
    ui-editable: false  # true = feature modules can also be switched in the settings (default false)
  hardening:
    sealing: true       # hardening flags are independent of the preset
```

Environment form: `AUTOMATION_MODULES_PRESET`, `AUTOMATION_MODULES_<NAME>`, `AUTOMATION_HARDENING_<NAME>` (dashes as `_`, e.g. `AUTOMATION_MODULES_REMOTE_ACCESS=true`). The stable and test profiles use `full`; `scripts/setup.sh` writes the chosen preset to `ai-automation-backend/config/application.yml`. Templates "minimal" and "maximal": `ai-automation-backend/config-examples/`, explained in [deployment.md](deployment.md#configurations-minimal-and-maximal). `scripts/setup.sh --lang de|en` only chooses the display language of the module texts in the terminal (default: the `$LANG` prefix, else `en`) — it never changes the outcome (which modules are active).

**Catalog** (single source: `modules/ModuleCatalog` – which also holds defaults, dependencies and descriptions):

| Tier | Modules | Maturity |
|---|---|---|
| `standard` (all presets) | `dashboard-report`, `goals` (goals with projects, work packages, milestones, TODO planning, workflows on a goal, goal guard), `run-tokens` ([run token](#workflow-hardening-optional), startup configuration only) | stable, run-tokens experimental |
| `research` | `chains`, `assistant`, `learning` (analytics endpoints; dashboard data stays core), `memory`, `audit`, `bpmn` | chains stable, others experimental |
| `advanced` (outward-facing) | `integrations` (incl. TODO tracker export, calendar adapters), `http-api` (general HTTP API connection, requires `integrations`), `webhooks`, `remote-access` (Tailscale), `terminal` (run terminal over WebSocket), `staging`, `mcp` ([MCP server](#mcp-server-optional-experimental), startup configuration only) | stable / staging, mcp experimental |
| `advanced` (low use) | `conversion`, `dialog-import` (requires conversion), `onboarding-profiles`, `skills`, `debug`, `factory` (incl. auto-repair), `wishes` (requires chains) | legacy / experimental |
| `hardening` | `provenance` (on by default), `sealing`, `require-sealed-for-unattended`, `require-signed-seal`, `seal-immutable`, `unattended-sandbox`, `unattended-in-container`, `webhook-signatures`, `private-network-guard` (on by default), `private-network-guard-http-api`, `private-network-guard-own-services` | experimental |

The catalog is additionally exported as plain bash arrays to `scripts/lib/module-catalog.generated.sh`, so
that the scripts need no backend — generated, not maintained, see
[entwicklung.md → Getting into the code](entwicklung.md#getting-into-the-code).

**Enforcement**

- `@RequiresModule` on controller classes or single handlers; `ModuleGuardInterceptor` (a `MappedInterceptor` bean so `@WebMvcTest` slices need no registry) answers **404 `MODULE_DISABLED`** for disabled modules — including the public webhook trigger.
- Background paths ask `ModuleRegistry` directly: `integration`/`skill-capability` blocks fail with a clear message, `/ws/terminal` refuses the handshake (`ModuleHandshakeGuard`), auto-memory, audit ingestion, staging health checks and chain schedules do not run, auto-repair is bound via `@EventListener(condition = ModuleConditions.FACTORY)`, and the assistant's cached settings follow a change (`AppSettingChangedEvent`) via `ModuleConditions.ASSISTANT` only while the module is on — `AssistantSettingsListener` re-reads them when it is switched on at runtime.
- Unknown module names, invalid values and unmet dependencies (`requires`) **fail the startup** — nothing enables itself.
- `ControllerModuleGateTest` enforces that every controller belongs to a module or is consciously listed as core.
- The block types of a module are kept in one list, `executor/BlockTypeModules` (`skill-capability` → `skills`, `integration` → `integrations`, `dashboard-report`). `/system/capabilities` offers such a type only while its module is on, and `GET /workflow-templates` names `requiredModules` per template. `BlockTypeModuleGateTest` keeps the list and the executor checks in step both ways: every entry needs an executor that refuses, and every executor that checks a module needs an entry.
- `GET /api/v1/system/capabilities` returns `preset`, `modulesUiEditable` and `modules[]` (with `configured`/`overridden`); the frontend uses it to hide navigation, routes (a "module disabled" page with a snippet) and widgets. **Settings → Modules & hardening** shows everything with YAML/env snippets.

**Switching in the UI (optional)** — With `automation.modules.ui-editable: true` (env `AUTOMATION_MODULES_UI_EDITABLE`) every feature module gets a switch in the settings:

- The change applies **immediately** (`ModuleRegistry` is mutable at runtime; every check runs per call, the terminal WebSocket per handshake, `TailscaleService` per call) and survives restarts (`app_settings` → `modules.ui-overrides`).
- UI values win over the configuration file; a value equal to the configured one is not stored, "Reset to configuration" removes it. Dependencies (`requires`) are checked like at startup (409 naming what is needed first). Stored values that no longer fit after a configuration change are skipped at startup instead of failing it.
- Every change writes a WARN `SECURITY` activity-log entry. With the switch off, `PUT/DELETE /api/v1/system/modules/…` answer **403 `MODULES_NOT_EDITABLE`** and stored UI values are ignored.
- **Hardening switches, `mcp` and `run-tokens` always stay startup configuration** (400): they protect against exactly the caller who holds the API key.
- Trade-off: with `ui-editable` anyone holding the API key can turn on webhooks (public endpoint), integrations (tokens), remote access or the run terminal (host access) — keep it off on publicly reachable instances. Running runs that reach a block of a module that was just switched off fail with the usual message.

## MCP server (optional, experimental)

Module `mcp` (tier `advanced`, experimental): AI clients such as Claude Code, Cursor or VS Code use the instance through the [Model Context Protocol](https://modelcontextprotocol.io) — find templates, build, check and run workflows, answer questions, TODOs. For testers it is the shortest way in: their own AI client talks to the platform directly, without knowing REST paths.

**Switched on only in the startup configuration** — `automation.modules.mcp: true` or preset `full`; `scripts/setup.sh` offers the module in step 4 (`--modules mcp`). Even with `ui-editable: true` it cannot be switched at runtime (`ModuleCatalog.startupOnly()`, 400 on a try; the settings show a lock): a leaked API key must not turn on a new way to control the instance.

**Connecting** — endpoint `POST /api/v1/mcp` (Streamable HTTP, stateless), key in the header like every other request:

```bash
claude mcp add --transport http mysuricates http://localhost:9090/api/v1/mcp --header "X-API-Key: <key>"
```

**Settings → AI clients (MCP)** shows the command and the JSON block for Cursor/VS Code with the stored key.

**What the server offers** — the current list comes from the server itself (`tools/list`, `resources/list`, `prompts/list`); here only the split:

- *Tools* (`service/mcp/Mcp*Tools`, one `McpToolset` per group): projects, workflows, templates/blueprints/pool and installing; create, update and check a workflow (`analyze_workflow`); start it (optionally waiting for the end or a question), read and cancel runs; list and answer open questions; TODOs. Task-shaped instead of 1:1 REST: every tool description costs the client context on every request. The hints `readOnlyHint`/`destructiveHint` let the client ask before starting, changing and cancelling.
- *Resources*: the guides (`mysuricates://docs/{de|en}/{workflow-guide|block-reference|building-blocks}`), plus workflows and runs as URI templates (`mysuricates://workflows/{id}`, `mysuricates://runs/{id}`). `read_guide` reads the same files for clients without resources — first the headings, then one section.
- *Prompts*: `design_workflow`, `review_workflow`, `audit_workflow` — guided procedures that point to the WORKFLOW-GUIDE sections instead of repeating them.
- *Instructions* (`mcp/instructions.md`, sent on `initialize`): a short index of the guiding ideas — deterministic where possible, templates first, a person decides what has consequences, audits measure before they judge.

**Safeguards**

- No public path: API key as everywhere, rate and size limits of the rest of the API. A foreign browser `Origin` gets 403 (protection against DNS rebinding, as the MCP specification requires); clients outside a browser send none.
- Changes go through `WorkflowService` with `ChangeSource.MCP` (version row, seal check), runs through `WorkflowExecutor` with `TriggerSource.MCP` — **attended** like a REST start, because the client asks before the tool call. Every writing tool call lands in the activity log.
- **No self-access from runs:** while the module is on, the platform's Claude calls run with `--strict-mcp-config` — without the MCP servers of the user's configuration (user, project, plugins, claude.ai connectors; checked with the real CLI). Otherwise every AI block could control the platform through a MySuricates server registered there, past the `self-api-call` rule. A `simple` or `agentic` block that needs MCP servers on purpose (Playwright, say) sets `user_mcp_servers: true`. With the module off, nothing about the calls changes.
- What a tool returns (run outputs, TODO texts, workflow contents) goes to the client's model provider.

Not included: event stream (`GET` → 405), sessions, elicitation, OAuth for remote connectors.

## Workflow Hardening (optional)

Switches under `automation.hardening.*` — the same mechanism as modules (startup configuration only, independent of the preset, shown read-only in **Settings → Modules & hardening**). Change provenance is not a switch; it is always on.

| Switch | Default | Requires | Effect |
|---|---|---|---|
| `provenance` | on | — | Every run stores `definition_hash`, `definition_version_id` and `trigger_source` |
| `sealing` | off | `provenance` | Seal workflows (`POST /api/v1/workflows/{id}/seal`, `…/unseal`) |
| `require-sealed-for-unattended` | off | `sealing` | Unattended runs only start sealed workflows |
| `require-signed-seal` | off | `sealing` | Sealed workflows need a valid signature |
| `seal-immutable` | off | `sealing` | Unsealing not over REST — only via the startup configuration |
| `unattended-sandbox` | off | — | AI CLIs and shell blocks outside attended runs cannot reach the key files and the database (Linux Landlock) |
| `unattended-in-container` | off | — | The same processes run in a fresh container of the image `automation-agent-workflow` (Docker) |
| `webhook-signatures` | off | module `webhooks` | Per-webhook signing secret (HMAC-SHA256) |
| `private-network-guard` | on | — | iCal feed and RSS/Atom feed fetch public addresses only (see [Integrations](integrations.md#fetches-into-the-local-network)) |
| `private-network-guard-http-api` | off | — | The same block for http-api (own network reachable otherwise) |
| `private-network-guard-own-services` | off | — | The same block for own services: CalDAV, Gitea, GitLab, ntfy, webhook notifications |

The three `private-network-guard` switches control the **outbound guard** (`OutboundTargetGuard`): where the instance itself connects to. Outbound HTTP clients only come from `OutboundHttp`, which applies it per target kind; which provider uses which switch follows from its target kind — see [Integrations → Target kind of a provider](integrations.md#target-kind-of-a-provider-outbound-guard).

**Definition hash** — `WorkflowDefinitionHasher` hashes the `WorkflowSnapshot` (blocks, instructions, model, provider, effort, metadata, defaultContext, sorted skill IDs) as canonical JSON (sorted keys, empty values omitted) with its own `ObjectMapper`, so Jackson customizing never changes the hash. Name, description, icon and tags are labels and are not hashed. Version rows without a hash get one at startup (`WorkflowVersionHashBackfillService`). Context variables: `{{ workflow_definition_hash }}`, `{{ trigger_source }}`.

**Checksum of imported definitions (always on)** — The definition hash only comes from the stored state; it does not tell whether a file was changed on its way into the instance. So pool files and workflow exports carry a field `"integrity": "sha256:<hex>"` (field name after npm, format after OCI), which `DefinitionIntegrity` computes with the same canonical form (the `WorkflowDefinitionHasher`'s own mapper, sorted keys, empty values omitted) over the content without the field itself — for the pool over the whole file, for the workflow export over everything the import takes over except the name. The pool install and `POST /workflows/import` recompute it: a mismatch → **400** (`POOL_INTEGRITY_MISMATCH` or `IMPORT_INTEGRITY_MISMATCH`, with the declared and the computed value), nothing is written; without the field the definition is taken over with a warning (log, install response `"integrity": "MISSING"`, notice in the import dialog). `PoolIntegrityGateTest` keeps the tracked pool files and their checksums in step. **Limit:** this detects transport errors and accidental changes, **not** deliberate forgery — whoever changes the file simply computes the checksum anew. For the shipped pool the versioning in the repository is the guarantor; details: [Curation → Checksum](workflow-pool-kuratierung.md#checksum-integrity).

**Run admission** — Every run trigger calls `WorkflowExecutor.execute(…, TriggerSource)` and therefore passes `RunAdmission` before a run row exists. Rules in this order: (1) a sealed workflow must match its seal — definition and the content of its skills —, (2) with `require-sealed-for-unattended` an unattended run needs a sealed workflow, (3) an unattended run (schedule, webhook, TODO/completion trigger, auto-repair, wish, auto-resume, a chain or sub-workflow with such or an unknown origin) of a definition that accesses the platform itself is always refused — **403 `UNATTENDED_PLATFORM_ACCESS`** when the analyzer reports `self-api-call` or `direct-db-access` at `HIGH` (`PlatformAccessCheck`, implemented by `WorkflowAnalyzer`, only the pattern matching of these two rules, no AI and no database; TODO e7e93fba). Saving stays allowed (the analyzer only reports), a person can still start the workflow (`MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT`); `MEDIUM` findings (a call only in the AI prompt, `sqlite3` on a project's file) stop nothing. A refused run leaves **no run row and no DLQ entry**, only a `SECURITY` entry in the activity log; REST callers get the error code.

**Which workflows rule (3) hits** — rule (3) has no switch. Which definitions it affects is shown by the analyzer's run over all definitions:

```bash
curl -s --noproxy '*' -H "X-API-Key: $KEY" "http://localhost:9090/api/v1/workflows/analyze?minSeverity=HIGH"
```

If `byRule` lists `self-api-call` or `direct-db-access`, those workflows do not start from a schedule, webhook, trigger or chain (`403 UNATTENDED_PLATFORM_ACCESS`, a SECURITY entry in the activity log). Remedy: data via context variables, TODOs via the todo blocks, external services via the integration block; started by hand they keep running.

**Change provenance (always on)** — Every writer of definition fields goes through `WorkflowService` → `WorkflowMutationGateway`. Each change writes a version row with the state **after** the change (number = MAX+1) and a `changeSource` chosen by the calling server code, never by the request (`user`, `assistant`, `factory`, `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `goal-guard`, `import`, `bpmn-import`, `restore`). If the newest row's hash does not match the current state (legacy data, a change that bypassed the service), that state is first saved as `untracked`. Label-only changes create no version. `WorkflowWriterAllowlistTest` pins who else may call `WorkflowRepository.save`. The same place refuses unknown block types: `BlockTypeValidator` checks every block type — nested ones in `switch` `cases[].blocks` and `default` included — against the executor registry (`BlockTypeRegistry`, fed by all `BlockExecutor` beans) and throws **400 `INVALID_ARGUMENT`** naming the unknown type and the allowed ones. The check runs inside the caller's transaction, so the save is rolled back.

**Sealing** — `seal` records the current hash in `sealed_hash` (with `sealed_at` and a WARN `SECURITY` entry). Sealed workflows reject definition changes and deletion with **409 `WORKFLOW_SEALED`**; renaming still works. Repair (`POST …/repair`) is refused right away as well, automatic auto-repair skips sealed workflows before any AI call, and the UI disables "Optimize" and "Repair". If the definition no longer matches the seal at start (e.g. after a change made directly in the database) or cannot be verified, the run does not start: **409 `WORKFLOW_TAMPERED`**, ERROR `SECURITY` entry with expected and actual hash. Both checks apply regardless of the switch — `sealing` only gates setting and removing seals, so switching it off never silently unseals.

**Skills under the seal** — The definition hash only contains the skill **IDs**; what a skill brings into a run (name and `aiContext` in the prompt, config schema, per capability name, description, schemas, handler, `async`/`writes`) is not part of it. So `seal` additionally records a content hash for every skill the workflow uses — activated skills and the blocks' `skillIds` — (`SkillContentHasher`, canonical JSON as for the definition hash; labels such as description, icon, category and version do not count) and stores the mapping as JSON in `workflows.sealed_skill_hashes` (`WorkflowSkillPins`). `RunAdmission` compares it before every run: a changed, added or deleted skill yields **409 `WORKFLOW_TAMPERED`** naming the affected skill IDs; if the state cannot be verified, the run is refused as well. The skill hashes are not part of the definition hash, so seals and the runs' version mapping depend on the definition alone. A seal without skill hashes (`sealed_skill_hashes = NULL`) keeps running (with a log warning) but checks no skills — sealing again adds the hashes, but only while the definition still matches its seal.

**Write path for skills (always on)** — Every skill writer goes through `SkillMutationGateway` (the counterpart of `WorkflowMutationGateway`): creation and editing (`user`, `PUT /api/v1/skills/{id}`; built-ins stay read-only, otherwise the next release would silently overwrite the edit), import (`import`) and built-in updates at startup (`builtin-seed`). Each change writes a row to `skill_versions` (operation `CREATE`/`UPDATE`/`DELETE`, canonical snapshot, content hash, server-side source; no foreign key, so the history survives deletion) and an activity-log entry — an import is a WARN `SECURITY` event. `GET /api/v1/skills/{id}/versions` lists the history, also for deleted skills. If a sealed workflow uses the skill, the gateway refuses changes and deletion by a person with **409 `SKILL_PINNED_BY_SEAL`**. Built-in updates from the resources cannot be refused; if they change the content under a sealed workflow, the gateway writes a WARN `SECURITY` entry naming the affected workflows, and their runs are refused until they are sealed again. `SkillWriterAllowlistTest` pins that only the gateway calls `skillRepository.save/delete`.

**Signature and history of a seal** — `sealed_hash` sits in the same SQLite file as the definition it protects; whoever writes that file changes both and passes. `WorkflowSealSigner` therefore signs every seal with HMAC-SHA256 over workflow id, definition hash and skill pins, using a key **outside** the database (`automation.seal.key-file`, env `AUTOMATION_SEAL_KEY`, otherwise a 0600 file created on first start; never `app_settings`). A signature that is present is always verified (`SIGNATURE_INVALID`); whether a seal **without** one may run is what `automation.hardening.require-signed-seal` decides (`SIGNATURE_MISSING`), so unsigned seals do not lose their runs overnight. Without a readable key the instance still starts and seals stay unsigned. `automation.hardening.seal-immutable` takes `POST …/unseal` out of the REST API (**403 `SEAL_IMMUTABLE`**): releasing a seal then needs the startup configuration, and a leaked API key (or an agent holding it) is no longer enough. Every sealing and unsealing is kept with its timestamp in `workflow_seal_events` (`GET /api/v1/workflows/{id}/seal-events`) — the activity log is pruned after 30 days and `sealed_at` disappears on unsealing. Entries are chained across all workflows (`prev_event_hash`); a row changed or deleted afterwards is reported as `chainIntact: false`. Refusals (of a run or of an unseal) write their SECURITY entry through `ActivityLogService.logRefusal` after the caller's transaction has ended, otherwise the rollback takes it along.

**Seals made visible** — `WorkflowSealVerifier` is the one check that `RunAdmission` runs before a start and `GET /api/v1/workflows/seal-status` runs without a run (`INTACT`, `VIOLATED` with reason `DEFINITION_CHANGED`/`SKILLS_CHANGED`/`UNVERIFIABLE` and the affected skills, `NOT_SEALED`). The response also carries the starts refused in the last 7 days from the activity log, because a refused start creates no run row. The endpoint is core, not part of the `sealing` module: a seal stays in force when the switch is off, so it must stay visible. `GET /api/v1/skills/seal-pins` maps every skill to the sealed workflows that use it. Every run records at start whether it ran sealed and verified (`workflow_runs.sealed`, `sealed_skill_pins`); this is never derived from the workflow's current state. The UI shows both states, not only the sealed one — on workflow, run, chain entry and skill; runs without a recorded seal state show "Seal state not recorded", never a value guessed from today's state. The SECURITY entry of a refused start is written after the caller's transaction (webhook, schedule, chain) has ended, on its own virtual thread — otherwise the refusal would roll it back, and `REQUIRES_NEW` would block itself with pool size 1.

**Unattended runs** — `TriggerSource` classifies triggers:

| Attended | Unattended | Inherits from its starter |
|---|---|---|
| `MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT` | `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `COMPLETION_TRIGGER`, `AUTO_REPAIR`, `AUTO_RESUME`, `WISH` | `CHAIN`, `SUB_WORKFLOW` |

Chains and sub-workflows pass their starter's origin on in the reserved context key `__parent_trigger_source`: `ChainExecutor` sets it for every entry itself (inputs cannot preset it), `SubWorkflowBlockExecutor` hands down the parent run's effective origin, and webhook payloads may not contain `__` keys. A missing or unreadable key counts as unattended. Refusals answer **403 `UNATTENDED_REQUIRES_SEAL`**. With this switch, auto-repair and wishes are effectively off: they run unattended and would have to modify sealed workflows. `RESUME` means "a person resumed"; when the platform resumes by itself (opt-in `metadata.autoResume`), the run is `AUTO_RESUME` and, with the switch on, needs a seal — a refusal leaves it `INTERRUPTED`.

**Process sandbox (`unattended-sandbox`, TODO 9525820a)** — AI CLIs and shell blocks run on the host as the backend's user. Unprotected, a prompt-injected agent could read the key file (`~/.mysuricates/api-key` or `~/.autotrigger/api-key`), the seal and integration keys or `automation.db`, and use them to drive the whole API, forge seals or decrypt credentials. With the switch, `ChildProcessEnv.scrub` starts every such process behind `util/ProcessSandbox`: `<python3> -I -S -c <sandbox/landlock_exec.py>` uses Linux Landlock (no root) so that the process and everything it starts can neither read nor write, replace or remove these paths. The interpreter runs before Landlock and is therefore never looked up via `PATH` (on Ubuntu `~/.local/bin` comes first): `ProcessSandbox.trustedPython()` fixes it at startup to `/usr/bin`, `/usr/local/bin` or `/bin`, and only when the real file and every directory above it belong to root and are writable by nobody else; without one, these processes do not start (TODO 4c8a0755). `config/ProcessSandboxConfiguration` builds the list per start (known key files of both data directories, other `*.key` files there, configured key files, the database with its journal and neighbouring databases; as a whole the backup directory `automation.backup.directory` and the `backups` folder of every data directory — a backup contains the API key, TODO 75890a40; further database copies are found only by a file name heuristic (`*.db`, `*.sqlite`, `*.sqlite3` with their journals, `mysuricates-backup-*`, up to four levels below the data directories, outside git checkouts); and the JAR the backend runs from if it lies outside the home directory, e.g. under `/app` in the container — inside the home it would turn `~/.local` into a way directory where tools create their state). A hidden directory hides everything below it; a symlink off the way gets no rule of its own, otherwise a link to a hidden file or a way directory would open the protection up again. Only processes of attended runs are exempt (`RunAdmission.attended`, the run thread carries the mark); everything else runs sandboxed, AI calls outside runs included. The helper never lies on disk where a process could change it before the next start. Directories on the way to a hidden path (`$HOME`, the data directories, the database directory and its parents) can only be listed inside the sandbox: their existing entries and subdirectories stay fully usable, but no new file can be created directly in them — otherwise a hidden file could be replaced by a rename or a symlink. Without Landlock (macOS, an old kernel) the helper ends with exit code 126 and a line `landlock_exec: …`; the process then does not start rather than running unprotected. Codex' own sandbox is independent of this (it needs user namespaces, which Ubuntu blocks without an AppArmor profile). **Limit:** Only what the list names is hidden; the sandbox does not prevent persistence. A sandboxed process can still change what later runs unprotected: the repository including `scripts/start-mysuricates-dev.sh` and `ai-automation-backend/config/application.yml` (which can switch the sandbox off for the next start), shell rc files, programs in `~/.local/bin`. Landlock governs file access, not requests to other services of the same user — the Docker socket reaches every path —, and existing hard links to a hidden file stay readable. The switch is therefore a partial protection against the direct path, not a separation like a dedicated system user or a container; real separation is what `unattended-in-container` provides.

**Container separation (`unattended-in-container`, TODO 6ee57b87)** — With the switch, every AI CLI and shell process outside attended runs (the same marker as the process sandbox) starts via `docker run --rm --pull never` in a fresh container of the image `automation-agent-workflow` (Java 21, Maven, Node, python3, git, gh, jq; built with `scripts/build-agent-images.sh agent-workflow`). `ChildProcessEnv.isolate` builds the command through `util/UnattendedContainerCommand`; `ChildProcessEnv.start` removes the container again as soon as the `docker` client ends — also after a timeout or a cancel, which only end the client. Only these go in: the working directory under its own path (paths in prompts, outputs and the database stay valid), the repository's git directory for a worktree, Codex' answer directory and, for an AI CLI, its program (read-only; an npm package as a whole) and exactly its login by the rule of `scripts/lib/host-mounts.sh` in mode `login` (`service/isolation/UnattendedCliAccess`): Claude the token from `claude setup-token` (`~/.config/mysuricates/ai.env`), Codex and Pi their `auth.json` in a config directory of their own, plus login variables the backend itself has — as variable names, the values are not on the command line. Not in: the home directory, the data directories, the Docker socket, the CLIs' settings, hooks and transcripts. A secret inside a mounted directory is covered: a file by an empty read-only one, the database together with its folder by an empty one (journal files and backups appear there later), data directories and hidden directories likewise. git's `config` and `hooks` are read-only, because both run commands on the host at the next git call there; so is the backend's startup configuration (`config/` and `application.*` in its working directory, further file locations from `spring.config.*`) when the instance's own repository is the working directory — otherwise a run could turn the switch off for the next start without git showing it. Every directory on the way to such a path is a mountpoint itself (bound onto itself), because a mountpoint can neither be renamed nor removed — otherwise the protected path could be moved aside and replaced by a writable one. The `.git` file of a worktree is mounted only when its pointers hold up (the git directory it names points back at exactly this file with `gitdir` and lies under `<common>/worktrees` of a real git directory); `.git`, `gitdir` and `commondir` are read-only. The backend reads Codex' answer file only as a plain file (`CodexAnswerFile`), never through a link the process could have left there. A working directory that is or contains the home directory, is a data directory or lies in a hidden directory is refused. The container runs as the backend's user (uid:gid), without capabilities, with `no-new-privileges`, a process limit and its own tmpfs for `/tmp` and `/home/agent`; Maven, npm and pip downloads are shared between unattended runs in a named volume (`automation.unattended-container.cache-volume`, default `mysuricates-unattended-cache`, empty = none). Further settings: `automation.unattended-container.image`, `.memory` (default no limit), `.pids-limit` (4096). At startup `service/isolation/UnattendedContainerConfiguration` checks Docker, the image and Claude's login and removes containers of a backend that is gone. Without Docker or the image the start fails instead of running on the host; with both switches on, the container replaces the sandbox for these processes (integration commands such as Taskwarrior stay with `ChildProcessEnv.scrub` and thus with Landlock). **Limits:** A backend that runs in a container itself cannot use the mode yet (its paths are not the host paths Docker binds). Shell blocks that need tools or paths of the machine outside the project (`docker`, `{{ home_dir }}`, files under `~`) fail in the container; for Docker there are still blocks with `container_image`. Codex' own sandbox does not start in the container either (user namespaces). The container's network is not restricted (Docker bridge; services listening on `0.0.0.0` stay reachable — without a key). If the backend itself dies, a container keeps running until the next backend start, which then removes it. Submodules (`.git/modules/*`) and nested repositories in the working directory are not read-only. Whatever an agent changes in the project is executed by whoever builds the code later — review the changes of unattended runs before taking them over.

**Run token (module `run-tokens`, TODO cfb5d56b)** — The API key may do everything; a workflow that has to read platform data asks for a run token instead (`metadata.permissions: ["read"]`, usage: [WORKFLOW-GUIDE → Read platform data with the run token](WORKFLOW-GUIDE.md#read-platform-data-with-the-run-token)). `security/RunTokenService` issues it when `ExecutionContextFactory.create` builds the run's context (32 random bytes, Base64url, in memory only like the WebSocket tickets, no row in the database) and revokes it first thing in `RunFinalizer.finish`, which runs in the `finally` of every run — before the final state is saved and before the completion listeners; a resume issues a new one, a backend restart drops them all. `util/RunAccess` keeps it as a non-inheritable `ThreadLocal` on the run's thread (like `LiveOutput`), `ChildProcessEnv.isolate` puts `MYSURICATES_RUN_TOKEN`, `MYSURICATES_API` (`RunAccess.localApiBase`: loopback, or the address `server.address` binds to, and the port the backend listens on – never the address for agents from `InstanceAddress`, which in a container names the published port or another host) and `no_proxy` for these hosts into the environment of every AI CLI and shell process of that thread — not in container mode, from where the backend on `127.0.0.1` is out of reach; `scrub` removes an inherited variable. Sub-workflows run on threads of their own and get their own token or none; the token is deliberately no run variable, because those are copied into the input of child runs. `ApiKeyAuthFilter` accepts `X-Run-Token` only for `GET` on the allowlist `security/RunTokenPaths` (role `RUN_TOKEN`) and refuses everything else itself (`403 RUN_TOKEN_SCOPE`, unknown or expired `401 RUN_TOKEN_INVALID`, one `SECURITY` entry each); `SecurityConfig` requires `API_USER` or `RUN_TOKEN` for that list and `API_USER` for everything else behind the key. `BlockStepRunner` (input, output, and with them the run error and later prompts), `RunLiveLogService` and the broadcasts (`ExecutionBroadcaster.broadcastLogMessage`, `AgenticEventBroadcaster`) replace the value of a live token with `***`. `RateLimitFilter` counts requests with a token per run (shortened SHA-256 of the token). The module is tier `standard` (on by default) and only switchable in the startup configuration. Analyzer rule `run-token-not-requested` (MEDIUM).

**Trust classes of context values** — Every context variable of a run carries one of three classes (`executor/VariableTrust`, bookkeeping in `executor/ExecutionContextTaint`). The class decides whether the value is substituted raw into a shell command line or wrapped in POSIX single quotes — and whether it *taints* the run, i.e. whether everything derived from it is escaped too.

| Class | Source | In shell blocks | In AI prompts | Taints the run |
|---|---|---|---|---|
| **trusted** | workflow defaults, system values (`project_path`, `workflow_run_id`, `workflow_definition_hash`), block-internal values, answers from `approval`/`human-input`, any `setVariable` | substituted raw | raw | no |
| **untrusted external** | webhook payload, restored `__untrusted_keys` (resume, sub-workflow), variables derived from tainted output (`step_N_output`, foreach items, `document` extracts) | always single-quoted | raw | yes, from the moment it is set |
| **platform data** | `previous_report`, `open_todos` — injected by `WorkflowContextDataService` into **every** run | always single-quoted | raw | on two paths: (1) once the placeholder was really substituted into a shell command line; (2) once the output of an AI block whose prompt contained the placeholder is recorded — from that coloured output on, the run is stickily tainted |

- **Where platform data comes from:** each platform variable has its own `PlatformVariableSource` bean — `PreviousReportVariable`, `OpenTodosVariable`, `RequestMetricsVariable`, `GoalVariables`, `AuditFindingsVariable`, in this `@Order`. `WorkflowContextDataService` only collects them and gives each its own error path: if a source throws, its variables show the `NICHT GEMESSEN` gap and the others stay untouched (TODO 441a4c46). A new variable is a new bean; it still belongs in `ContextDeltaCalculator.SYSTEM_VAR_KEYS`, otherwise it ends up in every step's context delta. Every source names all the variables it can set in `allNames()`; these names belong to the platform (TODO 2a0695ff): `ExecutionContextFactory` takes none of them from an input context, and a sub-workflow is not handed the parent run's — otherwise they would sit in the child's stored `input_context` and, wherever the child's own source does not apply (`goal_*` without `goal_id`, `audit_findings` without `metadata.audit`), arrive as a trusted variable. Conversely the collector sets only what a source announced for the run (`names()`); any other name from `load()` is dropped and reported in the activity log.
- **Taint propagation:** in a tainted run `recordOutput` escapes every further block output (`previous_output`, `{{ block.<name>.output }}`) and `setDerivedVariable` marks every variable derived from it as untrusted. The taint turns sticky as soon as one output has been recorded or platform data has reached a command line — from then on even a later `setVariable` on the variable that caused it no longer clears it, because a block can pull foreign bytes into its output without naming any placeholder (reading a file, calling an API). Before that point — external input with no recorded output yet — a `setVariable` on exactly that variable still clears the taint. It is persisted in the three reserved context keys `__untrusted_keys`, `__untrusted_outputs` and `__platform_in_shell` (the last one the platform-data usage flag, `true` once it is raised) so it survives resume and sub-workflow hand-over — even when no output has been recorded since the substitution. A clean run exports nothing; its context carries none of the three keys.
- **Why platform data only taints on use:** it is present in every run. Tainting at injection time would taint every run and change the meaning of the output placeholders in the bundled templates. `TemplateResolver.resolveShell`/`resolveShellSafe` therefore report back which placeholders were really substituted into a command line; only that report arms the taint (`ExecutionContextTaint.substitutedIntoShell`). A run that does not use `{{ previous_report }}` at all stays untainted.
- **AI outputs after a platform-data prompt are coloured:** if platform data was in an AI block's prompt, the output of exactly that block counts as untrusted — a following shell block therefore receives `{{ previous_output }}`/`{{ block.<name>.output }}` single-quoted. Built on the same feedback channel: `TemplateResolver.resolvePrompt` reports the placeholders it resolved, `ExecutionContextTaint.substitutedIntoPrompt` remembers platform data, and the next output recorded via `recordOutput` consumes that flag. The **prompt alone** does not colour the run: as long as no output was recorded, `hasUntrustedInput()` is false and `exportTaint()` is empty. Once the output is recorded the run is tainted, though — sticky as in every other case: from then on `recordOutput` escapes **every** further block output, `setDerivedVariable` marks every variable derived from it (`step_N_output`, foreach items, `document` extracts) untrusted, and `exportTaint()` passes both on to sub-workflows and to a resume. So a later AI block with a clean prompt does not get a clean output back — that only holds while nothing coloured has been recorded before it. The prompt path covers exactly the AI calls: `simple`, `skill-capability`, `agentic` (`goal`/`success_criteria`) and the non-shell branches of `foreach`, `while`, `conditional`, `retry` and `switch`; non-prompt resolutions (mounts, document paths, TODO fields, integration parameters, the `switch` expression) stay on `resolveTemplate` and keep reporting nothing.
- **Deliberately trusted:** answers from `approval` and `human-input` blocks arrive through the API-key-protected REST interface from the person who owns the instance — they are not escaped. Likewise `setVariable` (e.g. a workflow variable shadowing a webhook field of the same name) clears the untrusted mark on purpose: the value then comes from the definition, not from the caller. `replaceValue` (workspace path remapping) in contrast only swaps the value and leaves the class in place.
- **Known limits:**
  1. Nothing is escaped in AI prompts — a prompt is not a command line, and the trust model does not defend against prompt injection.
  2. Prompt injection through the AI's answer itself remains possible: what gets quoted is the output, its *content* still comes from a model that read platform data. Quoting prevents shell injection, not manipulation of the content.
  3. A shell block that writes a tainted value into a file itself and later executes that file is outside what quoting can see.

  Colouring the AI output closes the path platform data → AI prompt → shell block (TODO 22e6bec8). **Recommendation: set `"shell_escape_variables": true` in every shell block that consumes an AI block's output** — the block then behaves the same in coloured and in clean runs. For the bundled definitions `PlatformPromptQuotingGateTest` enforces it.

**Webhook signatures** — `POST /api/v1/webhooks/{id}/secret` generates a secret (32 random bytes, hex), shows it exactly once and stores it AES-GCM-encrypted (`IntegrationEncryptionService`, key outside the database) in `webhook_triggers.signing_secret_enc`; `DELETE …/secret` removes it. For a webhook with a secret, `WebhookSignatureVerifier` checks `X-Signature: sha256=<hex>` (or `X-Hub-Signature-256`, the GitHub/Gitea format) as HMAC-SHA256 over the raw request bytes before the JSON is parsed, compared in constant time — otherwise **401 `WEBHOOK_SIGNATURE_INVALID`**. As with seals, a stored secret stays in force if the switch is later turned off. Responses only reveal `signatureRequired`.

## Backend Architecture

### Layer Model

```
Controller (REST API)
    ↓ DTO (Request/Response Records)
Service (Business Logic)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (File: data/automation.db)
```

`LayerRuleTest` pins the allowed edges between the layers, see [Architecture Gates](#architecture-gates).

### Database Maintenance (SQLite, pool-size=1)

- **Run retention** (`RunCleanupService.cleanupTerminalRuns`, daily 03:15): deletes finished runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) including their `step_results` once they are older than `run-retention-days` — oldest first, at most `run-retention-max-per-night` per pass, each run in its own short transaction via `RunDeletionService` (which also clears DLQ, TODO and wish references). Runs referenced by an audit report are kept (query exclusion plus a guard in the service). Runtime settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = off, default 90), `run-retention-max-per-night` [10,5000] (default 200). INTERRUPTED runs have a rule of their own (`cleanupInterruptedRuns`, 03:00).
- **Run deletion via the API can be switched off** (`RunDeletionPolicy`): `automation.executions.allow-delete: false` (startup configuration, default `true`) makes `DELETE /api/v1/executions/{id}` fail with `403 RUN_DELETION_DISABLED` and hides the delete buttons (`runDeletionAllowed` in `/system/capabilities`). Deliberately without a runtime switch. The two cleanup jobs above are operations and keep running; `run-retention-days` stays a runtime setting, but with deletion switched off it can only be **extended** at runtime (`0` = off counts as the longest); a shorter retention fails with `403 RUN_DELETION_DISABLED`, so a leaked key cannot have the runs deleted overnight.
- **Backup** (`DatabaseBackupService`, daily 02:00, `automation.backup.retain-count`): deliberately runs before retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` is the **only** statement in the Hikari init SQL — sqlite-jdbc runs just the first statement of a multi-statement string (`foreign_keys`/`busy_timeout` therefore live in the JDBC URL, guard: `DataSourceConfigGuardTest`). The pragma only takes effect on an empty database or after a full `VACUUM`. The startup check `checkAutoVacuumMode` writes an ActivityLog WARNING while the file is still at NONE — run `POST /api/v1/system/vacuum` once, after which the nightly `incremental_vacuum` (03:00) reclaims space. Direct `sqlite3` access stays off-limits (Hibernate cache, single writer).

### Packages

The packages under `com.automation` follow the layers: `controller/`, `service/` (split into
sub-packages per domain), `repository/`, `entity/`, `dto/` and `mapper/`; plus, among others,
`executor/` (engine and block executors), `security/`, `websocket/`, `modules/`, `metrics/`, `config/`,
`event/` and `util/`. Which class lives
where is shown by the directory itself. Mutual dependencies between packages and forbidden layer edges
are caught by the [Architecture Gates](#architecture-gates); a few leaf packages
(`service/runoutput/`, `service/cli/claude/`, `service/assistant/format/`) exist only so that no cycle
arises.

### Workflow Execution

The workflow engine executes blocks sequentially. The AI CLIs (Claude, Codex, Pi) always run **on the host** in the project directory, not in the project container; so do shell blocks, unless a step names a `container_image`.

**Flow:**
1. `WorkflowExecutor` has `RunAdmission` check the start (refused: no run), creates the `WorkflowRun` directly with status RUNNING and answers at once; the blocks run on a virtual thread as soon as a slot is free (`automation.execution.max-concurrent`)
2. Before the first block the run finds or starts the project container, provided Docker is set up and the run is not isolated (if the start fails, it carries on without a container), and writes skills, project context and the active workflow memory to `.claude/rules/`
3. For each `BlockDefinition`, the appropriate `BlockExecutor` is selected; `BlockStepRunner` creates the step and runs it
4. AI calls go through the `CommandExecutorService` to the provider named by the block or workflow, otherwise to the system default (`AiProviderRegistry`), and start its CLI on the host (`claude -p`, `codex exec`, `pi -p`; prompt via stdin)
5. Shell commands run via `/bin/bash -c` in the project directory on the host, with `container_image` in a one-off container (`docker run --rm`); only ad-hoc commands during a run and the terminal use the project container
6. Results are stored as `StepResult`
7. Live logs are sent to the frontend via WebSocket (STOMP, `/topic/executions/{runId}/logs`)
8. After completion, `RunCompletionHandler` evaluates gate status and TODO markers from all block outputs (the copies passed on by `compress`, `compress-ai` and `sub-workflow` do not count again) and records template verification, activity log and DLQ entry
9. After the final save, `RunFinalizer` fires the `WorkflowRunCompletedEvent` → `AutoTodoService`, among others, reacts

**Controllable from step 1:** before submitting the run, `WorkflowExecutor` registers a provisional `ExecutionContext` in the `WorkflowExecutionRegistry`. A cancel therefore takes effect while the run is still waiting for a semaphore slot or for its container to start — the execution thread carries the cancel/pause flag over when it swaps in the real context. As a result `getActiveRunCount()` also counts queued runs, so `GhostRunWatchdogService` does not release the permit of a starting run as a leak. Pause, resume, cancel, approvals and ad-hoc commands go through `RunControlService`: it works on the registry only and starts nothing itself — new runs and `resume-from-step` stay in `WorkflowExecutor` and therefore behind `RunAdmission`.

**Resume cuts by blocks, not by step index** (`ResumePlan`): `POST /executions/{id}/resume-from-step` picks up after the last COMPLETED step. What counts is that step's **position** in the block list, not its `stepIndex` — wrapper blocks (foreach, while, retry, conditional, switch) draw one more index per item or iteration, so a block's index runs ahead of its position. This rests on the guarantee `BlockStepRunner` gives: exactly one persisted step per top-level block, in block order (inner items are never persisted). The resumed segment's steps continue above every index already persisted (`ExecutionContext.startStepIndicesAt`), so indices stay unique and sortable. Cutting by index would, after a wrapper block, skip blocks that never ran (TODO `1be423b4`).

**Automatic resume (opt-in, TODO 90d10ba1)** — `RunControlService.cleanupZombieRuns` (startup) and the standby branch of `GhostRunWatchdogService` record in the run metadata what the run was doing (`interruptedFrom`: `RUNNING`/`PAUSED`) and what interrupted it (`interruptedBy`: `RESTART`/`STANDBY`), and then publish `RunInterruption.Event` with the marked runs — the order follows from that call, not from listener order. `RunAutoResumer` therefore only sees runs of the current JVM lifetime and resumes those that are `INTERRUPTED` and were `RUNNING`, are neither a chain entry nor a sub-workflow (source `CHAIN`/`SUB_WORKFLOW`, `chain_run_id` or `__parent_trigger_source` in the context), whose workflow exists and has `metadata.autoResume.enabled`, and that had no attempt yet. The counter `autoResumeAttempts` is saved in a short transaction of its own **before** the start, then `WorkflowExecutor.resumeFromStep(runId, TriggerSource.AUTO_RESUME)` runs through `RunAdmission`. Runs start one after another on a thread of their own, with `automation.execution.auto-resume.delay-seconds` before each (runtime setting `run-auto-resume-delay-seconds`, switch `run-auto-resume-enabled`); neither startup nor the watchdog tick waits for them. Every resume, refusal and failure is in the activity log (source `RunAutoResumer`). Chains (`ChainZombieRunCleaner`) are left out. Against the schedule (TODO 070ccc3d): during the wait the interrupted run is `INTERRUPTED`; the SKIP check (`RUNNING`/`PAUSED`) alone would not see it, and a catch-up run would start next to it. `ScheduleConflictCheck` therefore asks `PendingAutoResumes.forWorkflow` before the status check: a suitable run of the workflow interrupted in this lifetime that `RunAutoResumer` has not handled yet, or the attempt under way. It reads the saved state, not the queue — a run counts from its marking, even before the event arrives; runs passed over (not eligible, switch off, refused) block nothing. The order "pending first, then `RUNNING`" leaves no gap, because the resumed run is `RUNNING` before its attempt counts as done.

**Block position per step** (`metadata.blockIndex`): `BlockStepRunner.startStep` writes the block's position in the top-level list into the step metadata, `runStep` keeps it over executor metadata. The run page's flow diagram maps steps to its boxes (`b_<position>`) through it (`latestStepByBlock` in `stepMetadata.ts`); runs without the key fall back to the position in the index-ordered list. After a resume the interrupted attempt of a block sits next to the new one — both with the same `blockIndex`, the higher `stepIndex` is the current one (TODO `dcba60da`).

**Container start/stop outside transactions:** `ProjectService.startContainer`/`stopContainer` are deliberately **not** `@Transactional`: the Docker call sits between two short `TransactionTemplate` bursts (STARTING → Docker outside any transaction → RUNNING/ERROR), because `ContainerService` allows only one start per project at a time — an enclosing transaction would pin the single connection for the whole 5–15 s Docker start and push a concurrent workflow run (which takes over the start first) into the 120 s connection timeout (lock-order inversion between the per-project start and the connection). Because the STARTING burst commits before the Docker start, `ProjectStatusReconciler` picks up every project a crash in that window left in STARTING on `ApplicationReadyEvent` (RUNNING container row ⇒ RUNNING, otherwise STOPPED) — the project-level counterpart of `RunControlService.cleanupZombieRuns`. `ProjectService.delete` follows the same pattern in reverse order: the archive burst (`archivedAt` + activity log) commits first, the Docker stop runs outside any transaction afterwards — also when only the container row is RUNNING, because a workflow run starts the container itself without setting the project RUNNING — if it fails the project stays archived (ERROR log) and only the orphaned container is left for `DockerOrphanReconciler`/idle cleanup to reap.

The same pattern applies to `WorkflowMemoryService.extractKnowledge`/`extractAndActivate` (security finding 4, same shape as `ebd508c5`): the AI summarization call (`ClaudeSummarizer`/`CodexSummarizer`, seconds to minutes) runs outside any transaction, between a read `TransactionTemplate` burst (load runs, materialize the lazy `steps` collection into a new list — OSIV is off —, collect run ids) and a write burst (save the `WorkflowMemory`, or `deactivateAllForWorkflow` + activation). `activate`/`update`/`delete` stay short `@Transactional` methods.

**Disconnecting an integration outside transactions:** `IntegrationOAuthService.disconnect`/`deleteCredentials` follow the same burst shape: a short `TransactionTemplate` burst (clear the token fields, or delete the row) → token revocation at the provider outside any transaction. That HTTP call has a 10 s timeout and inside a `@Transactional` method would hold the single connection for all of it; "persist first" additionally rules out the state "revoked at the provider but still `CONNECTED` locally".

**Connecting an integration outside transactions:** `saveCredentials` and `handleCallback` call provider HTTP (PAT validation `fetchAccountInfo`/`fetchGrantedScopes`, or the code exchange `exchangeCodeForTokens` + account info, 10 s timeout each). The flow lives in `IntegrationOAuthConnectFlow` (a class of its own because of the ratchet ceiling): a short read `TransactionTemplate` burst (decrypt client id/secret) → provider HTTP outside any transaction → a short write burst (load-or-create the row, set tokens/status, save). `IntegrationOAuthService` only delegates; `IntegrationOAuthConnectIntegrationTest` proves via `TransactionSynchronizationManager.isActualTransactionActive()` in the mocked client/provider that no transaction is open during the HTTP calls. A side effect of the pattern: if the PAT validation fails, the row is still saved with credentials and status `DISCONNECTED`.

**Pool watchdog sees virtual threads:** `ConnectionPoolWatchdogService` logs a pool exhaustion together with a filtered thread dump only once two consecutive samples (15 s apart) show waiting threads – a single one almost always resolves by the next; the ERROR message after 60 s counts from the first sample. `Thread.getAllStackTraces()` only knows platform threads — but workflow runs live on `virtual-*` threads, so such a dump would show only the waiting thread, never the holder of the single connection. `ThreadDumpCollector` therefore uses `HotSpotDiagnosticMXBean.dumpThreads(…, TEXT_PLAIN)` (JDK 21, no dependency): dump into a temp file under `java.io.tmpdir` (deleted right away), parse, filter to frames with `com.automation`/Hibernate/SQLite/Hikari, likely holders (`org.sqlite.`/Hikari proxy frames) first, waiting threads (`HikariPool.getConnection`) last, at most 20 threads of 30 frames each. If the HotSpot dump fails, the collector falls back to `getAllStackTraces()` (visible in the log as `source=…`).

**Container row:** `ContainerService.createAndStartContainer` updates an existing `containers` row (upsert) instead of deleting and re-inserting it — the `Project.container` cascade would un-schedule the delete during flush, and the following INSERT would fail on `UNIQUE(containers.project_id)`. Concurrent starts for the same project run as one (`concurrency/SingleFlight`): a caller that arrives during a start waits without a monitor (virtual threads would otherwise pin their carrier thread) and gets the same container or the same failure; the entry goes away when the start ends. `lastActivityAt` is stamped by a JPQL update (`ContainerRepository.touchActivity`) rather than a detached merge.

### Block Types

Which block types exist is decided by the executor registry: `BlockTypeRegistry` collects
`supportedTypes()` of every `BlockExecutor` bean, and `BlockTypeListsGuardTest` keeps the other lists
(frontend, assistant) in step. Configuration and behaviour per type are in
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section "Block Type Reference"). Placeholders (`{{ variable }}`,
`${varName}`) are resolved at runtime by the `ExecutionContext`; `{{ previous_output }}` contains the
output of the previous block.

### Agentic Block (Goal→Reason→Act→Observe)

The REASON → ACT → OBSERVE loop with its safety limits (iterations, timeouts, token budget, repeated
actions, approval) and its pitfalls is described in [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section
"Agentic Block Deep Dive"), why the budgets are set as they are in
[konzepte/agentische-budgets.md](konzepte/agentische-budgets.md). In the code: `AgenticBlockExecutor` with
`AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker` and `AgenticPlan`.

### Sub-Workflow Block

Behaviour and configuration: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section `sub-workflow`). In the code:

- Start, guards (circular references via `__parent_workflow_ids`, nesting depth) and waiting live in `SubWorkflowLauncher`; `foreach` with `workflow_id` takes the same path per item (TODO a5c9fbbf). `workflow_id` may be a placeholder from a trusted context variable, never from webhook data or block outputs
- A cancelled parent run cancels the child run via `RunControlService.cancelExecution`
- Waiting goes through `RunCompletionWaiter`, the same loop as for chain entries (`ChainRunWaiter`): any status other than `PENDING`/`RUNNING`/`PAUSED` ends it – a paused child run is still waited for (TODO 802a516e)
- `@Lazy` injection on `WorkflowExecutor` only where a caller starts runs itself (sub-workflow, chains, triggers); pure status queries (active runs, container ids, approvals) go straight to `WorkflowExecutionRegistry`, controlling a running run goes to `RunControlService`, which does not need `WorkflowExecutor`

## Gate Status System

A quality gate is the verdict a checking workflow passes on its result (GREEN, YELLOW, RED), written
as the markers `gate_status:`/`gate_category:`/`gate_description:` into a block output. The rules —
which gate wins, a missing verdict (`gate-missing`), an unreadable one (`gate-invalid`),
`checked_command` — are in [WORKFLOW-GUIDE.md → Quality Gate Markers](WORKFLOW-GUIDE.md#quality-gate-markers), the
idea behind it in [konzepte/gates.md](konzepte/gates.md). A gate does not change the run status; it is a
finding about the result, not a failure of the execution.

In the code: `GateStatusOutputParser` reads the markers, `RunCompletionHandler` evaluates all block
outputs after the run and writes the verdict into `WorkflowRun.metadata`, `GateExpectation` recognises
whether the definition orders a verdict, and `GateStatusResolver` derives one from the run status when no
marker came. With auto-TODO switched on, `AutoTodoService` creates a TODO from RED (in mode `auto` also
from YELLOW); after a repair run a GREEN closes the TODO that triggered it
(`TodoFeedbackListener`, see [Todo Triggers](#todo-triggers--self-improvement-loop)).

## Workflow Chains

Chains run several workflows one after another, across projects too; every entry receives the
`{{ previous_chain_output }}` of the last completed workflow. Properties, structure and limits:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section "Chains"). In the code, `ChainExecutor` runs the entries on
virtual threads and polls their status; the status goes out over WebSocket to
`/topic/chains/{chainRunId}/status`.

### Chain Templates

Pre-built chain definitions composed from existing workflow templates. Every entry has to name a shipped built-in template (`ChainTemplateServiceTest`); there are no chains over pool entries:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: Creates a new chain (optionally with associated workflows) in a target project

## Scheduler: missed occurrences (standby and cold start)

The scheduler is a 60-s tick in the backend (`SchedulerTick`); without a running backend nothing starts. For occurrences that fell due during a gap, **one** rule applies, regardless of whether the JVM was frozen (standby, detected as a tick gap > `automation.scheduler.resume-grace-minutes`) or the process was not running at all (cold start, detected on the first tick; the reference is the backend's start time, occurrences after it are due normally):

- **Without opt-in** the occurrence is skipped and the schedule is set to its next occurrence (`ScheduleSkipPolicy`, activity log "Schedule skipped (system suspend/resume)" or "(backend was stopped)").
- **With opt-in** — per schedule `catchUpOnResume` or globally `scheduler-catch-up-on-resume` (settings UI) — the occurrence goes into the `CatchUpQueue`. It starts the entries **one after another** in the order they originally fell due: the next one only once the previous catch-up run has reached a final state (`ScheduledWorkflowExecutor.isFinished`). Waiting entries keep their old `nextRunAt` but are left out by the normal tick. An entry the executor rejects (target already running, schedule disabled in the meantime) does not block the queue.
- An active `skip-until` pause wins: then nothing is caught up, even with opt-in.
- The queue is deliberately not persistent: after a restart the same occurrences are due again and the cold-start path queues them again — nothing is lost, nothing runs twice.
- Visibility: one WARN line in the activity log per event ("Cold start detected … N missed schedule(s): X skipped forward, Y queued for sequential catch-up", on errors "…, Z failed"), `GET /api/v1/schedules/catch-up` (running and waiting entries) and a banner on the schedules page.
- `resume-grace-minutes: 0` switches both detections off (then everything due starts at once).
- The tick only starts once Spring Boot reports `ACCEPTING_TRAFFIC` — that is, after the startup cleanup that sets leftover RUNNING/PAUSED runs to INTERRUPTED (`ApplicationReadyEvent`). Spring fires `@Scheduled` already on `ContextRefreshedEvent`, that is before this cleanup: a catch-up run started there would be marked INTERRUPTED by it in mid-run, and a stale run in state RUNNING would make the target look busy, so the catch-up occurrence would be dropped (TODO aa51d221). Skipped ticks do not count; the first real tick is the cold start and comes about one tick period (60 s) after startup, and catch-up runs start correspondingly later.

Further rules of the tick:

- **An error only affects its own schedule** (TODO 68a3d91e): every schedule of a tick is started, skipped or queued on its own (`SchedulerTick.runIsolated`). If one fails — say, on a failed write — the activity log shows "Schedule execution failed: …" or "Schedule skip failed: …", and the tick moves on to the next one.
- **Invalid cron expression** (TODO 565e853b): create and update refuse one; if one is in the database anyway (by hand, from an old version), `ScheduleSkipPolicy.endIfCronInvalid` disables the schedule on first contact — when it falls due in the executor as well as when it is skipped (`skip-until`, suspend, cold start) — without starting it, and reports it exactly once (activity log ERROR "Schedule disabled (invalid cron expression '…'): …", `reason: invalid-cron`). Until it falls due, the dashboard (`UpcomingRunsCalculator`) leaves it out instead of failing on the parse exception.
- **Conflict `SKIP`** (default, TODO ade3fef8): if the target still has a run in `RUNNING` or `PAUSED` when the occurrence falls due, the occurrence is dropped — `nextRunAt` jumps to the next occurrence, `ONCE`/`DELAYED` expire, `lastRunAt` and `runCount` stay (`ScheduledWorkflowExecutor.skipOccurrence`). `OVERLAP` starts alongside.
- **`maxRuns`** counts every start, including failed or rejected ones, but not occurrences dropped by `SKIP`. Re-enabling does not reset `runCount`.
- **No run left** (TODO 1e801952, `ScheduleEndConditions`): create, update and enable refuse an enabled `CRON` schedule with `400` when its next occurrence lies at or after the end date or its `maxRuns` ≤ `runCount` (translated: `SCHEDULE_NEXT_RUN_AFTER_END_DATE`, `SCHEDULE_MAX_RUNS_REACHED`). A disabled one stays editable (the form always sends `maxRuns`), and `update` checks only when `cronExpression`, `scheduledAt`, `endDate` or `maxRuns` are in the request — changing only the description of old data gives no `400`; the bundle import creates schedules disabled through `SchedulerService.createDisabled`, so an end date already passed does not fail it. The skip policy (`skip-until`, suspend, cold start) does not set `nextRunAt` past the end date but ends the schedule, and `effectiveNextRun` shows no occurrence at or after the end date.
- **Cron without a future occurrence, one-off in the past** (TODO 1e801952): a valid expression that never matches again (say, 30 February) is refused with `400` on create, enable and a new expression (`SCHEDULE_NO_NEXT_RUN`); when `advance` finds no further occurrence after a run, it ends the schedule as at the end date and reports it once (activity log "Schedule ended (no further occurrence)", `reason: no-next-run`). A `ONCE` schedule whose `scheduledAt` lies in the past is refused on create, enable and when its time is moved (`SCHEDULE_ONCE_IN_PAST`) instead of starting at once — with a 60-s tolerance: a time in the running minute starts regularly with the next tick; `DELAYED` counts from now and is not checked.
- **End date** (`endDate`, `CRON` only, TODO 9a3e6c91): an occurrence on or after the end date does not start — measured by the occurrence, not by the time of the tick, so the last occurrence before it does not depend on where the 60-s tick lands. After a run or `SKIP`, `ScheduledWorkflowExecutor.advance` sets no next occurrence past the end date but disables the schedule; an occurrence already stored past it (old data, end date moved forward) ends the schedule without a start when it falls due (activity log "Schedule ended (end date reached)").
- **`skip-until` in the views** (TODO a776fd2a): the tick only winds an occurrence past the window once it falls due. Until then the read paths recalculate — `ScheduleSkipPolicy.effectiveNextRun` (the same rule as `skipScheduleUntil`, without writing): `ScheduleMapper` returns the first occurrence after the pause as `nextRunAt`, plus `pausedUntil`; `UpcomingRunsCalculator` leaves occurrences inside the pause out of the dashboard, calendar, cron preview and assistant context; `GET /schedules/maintenance` names the end of the pause. The read paths parse `skip-until` without an activity log entry; the tick reports an invalid value.
- **Maintenance mode** (`scheduler.maintenance-mode`, pause switch on the schedules page): the tick keeps running but starts nothing; `nextRunAt` stays put. When it is switched off, every overdue schedule starts once immediately, all at the same time — neither standby/cold-start detection nor the catch-up queue applies, the ticks during the pause have already used up the gap.
- **Maintenance mode in the views** (TODO 7704b1e9): the same rule as for `skip-until`, not a second one. `ScheduleSkipPolicy.activePause` reads both pauses once per view (`SchedulerPause`); for a schedule held by maintenance mode, `effectiveNextRun` returns no next run — nobody knows when the switch goes off. `ScheduleMapper` then reports `nextRunAt: null` plus `maintenance: true`, `UpcomingRunsCalculator` leaves all occurrences out of the dashboard (`maintenance: true`), and calendar and assistant context show none. The dashboard card and the schedule list say "Maintenance mode – nothing starts".

## Completion Triggers

A completion trigger starts a target workflow when a source workflow completes
(`CompletionTriggerService`, `/api/v1/completion-triggers`, endpoints in the
[API reference](api-referenz.md)).

## Audit Center — Findings, Acceptance and Expiry

Audit workflows (`metadata.audit`) deliver an envelope with the complete snapshot of findings in their
report block. `AuditIngestionService` (called by `AuditIngestionListener` on `WorkflowRunCompletedEvent`)
stores one `AuditReport` per run and reconciles the findings by fingerprint (`AuditSnapshotApplier`).
Format, identity of a finding, lifecycle, managed TODOs, risk acceptance and the context variable
`audit_findings`: [audit-workflow-protocol.md](audit-workflow-protocol.md).

## Todo Triggers — Self-Improvement Loop

A TodoTrigger starts a fix workflow as soon as a matching TODO is created, closing the
self-improvement loop. Setup, filters, context variables and the three layers of loop prevention:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section "TodoTrigger — Self-Improvement Loop"), the idea behind
it: [konzepte/selbstverbesserung.md](konzepte/selbstverbesserung.md).

- `TodoTriggerService` — `@TransactionalEventListener` on `TodoCreatedEvent`, matches TODOs against the configured triggers (tag pattern with AND semantics, priority, project) and starts the fix workflow with the TODO data as context variables
- `TodoFeedbackListener` — `@EventListener` on `WorkflowRunCompletedEvent`, closes the TODO on GREEN (when `autoClose=true`), logs an escalation when fix attempts are exhausted
- **Distinction — manual todo-workflow link**: The `TodoTrigger` starts workflows rule-based and automatically. Separately, a single todo can optionally be linked to a **resolver workflow** (`linkedWorkflowId`, set in the todo view) — the workflow meant to complete this one todo. The link is not global and starts nothing by itself: only the manual `Resolve` action starts the run, the todo then moves to `IN_PROGRESS` with `resolutionState=RUNNING`, is set to `DONE` automatically on success, and falls back to its previous status on a failed run (`resolutionState=FAILED`).
- **Pause** (`automation.todo-trigger.skip-until`, ISO instant, empty = off): stops the whole mechanism until that time — backlog poll **and** event path. It applies from the first tick after startup, so setting it needs no running backend. Together with `automation.scheduler.skip-until` (+ `catch-up-on-resume: false`) a few config lines pause every trigger, e.g. in the git-ignored `ai-automation-backend/config/application.yml`. Deliberately a date rather than a flag: it expires on its own, whereas a forgotten `enabled=false` on the individual triggers silently stops the loop (no error, no red status — the TODOs just pile up). An unparseable value ⇒ fail open (ERROR log, triggers fire normally). An active pause is logged as a WARNING at startup.

## TODO batch — work through at night, approve in the morning

A chain of two pool workflows (`scope: repo`, TODO cc0eb3ba) works through open TODOs of the own project – one after another in **one** isolated clone, with one approval for the whole batch.

- **Triage** (`mysuricates-todo-batch-einordnen`): closes handed-over TODOs whose commit (trailer `Todo: <uuid>`) has reached `main`; releases orphaned ones; selects at most `batch_max` TODOs from **one** area with one AI call (opus, effort high) and sets `batch:selected`. The triage also gets the files at most 15 lines below their ceiling from `SourceSizeRatchetTest` (`near_limit`; ratchet file and source root as `size_ratchet_file`/`size_ratchet_root` in the `defaultContext`; a repository without a size ratchet leaves them empty or out = no list) and does not pick a TODO that would grow one of them unless it brings the split. Without candidates or while a batch is running, the AI call is skipped (`run_if`).
- **Work through** (`mysuricates-todo-batch-abarbeiten`): baseline green? → implement each TODO (foreach, one commit with trailer) → mapping, guards (`forbidden_paths`, line limit) and gates (pre-push gate without build, backend/frontend suite by area), on red a linear re-check → review per TODO (Codex, read-only; a blocker takes the TODO out, a verdict other than `ok`/`findings` – such as `not_reviewed` – only lets it count "with a note", traffic light yellow) → overall review (hints only) → report → approval → hand-over as `refs/heads/mysuricates/batch-<run8>` into the source repository. Merging stays manual; the next triage closes the TODOs. Only TODOs whose start update (`todo-update`) is confirmed are worked on; skipped ones are named in the report. Empty commits do not reach the branch. The traffic light also turns yellow when a gate only passes on the second attempt or the batch is discarded. If a TODO drops out at a gate, its note names the first meaningful error line; the merge preview in the hand-over note is taken at hand-over time, and repeating the hand-over of the same run (ref already points to this state) counts as done.
- **State on the TODO:** `batch:selected` → IN_PROGRESS + `batch:started` → `batch:handed-over` → DONE; `batch:failed` / `batch:rejected` with a note keep a TODO out of further batches until a person removes the tag. A handed-over TODO whose branch is neither merged nor present goes back as OPEN. A `batch:selected` set by hand stays and counts against `batch_max`. If a run stops before the check, its TODOs go back with `batch:failed`; if it never reaches the hand-over, the next triage releases started TODOs unchanged for 30 h – a stuck run does not block further batches.
- **No auto-TODO:** both workflows set `metadata.autoTodo.enabled: false`. The chain keeps its TODOs itself through tags and notes; a yellow gate (for instance after "Discard", a deliberate decision) only colours the traffic light and the tile.
- **Platform building blocks:** `{{ todo_details }}`, `todo-update` with `updates`, `run_if`, `timeoutMinutes` per chain entry (see WORKFLOW-GUIDE).
- **Installation:** `python3 scripts/todo-batch/install.py` (schedule off at first; `--enable-schedule` turns it on). The scripts live in `scripts/todo-batch/`; every run copies them from the base commit to `.git/todo-batch/bin/` and only calls the copy – whatever the AI changes in the tree does not affect the check. Their state lives in the clone under `.git/todo-batch/`. Only committed work is checked: leftovers in the working tree are discarded first and named in the report. Without an isolated clone the scripts refuse to work.

## Project Cockpit & TODO Hygiene

Since ~96% of TODOs are machine-generated and scattered across many projects, there is a cross-project overview plus a cleanup mechanism.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): delivers a rollup per project (status counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus a global "Needs You" list (USER source ∨ CRITICAL ∨ overdue). All from lean GROUP BY projections (no TEXT blobs → pool-size=1-safe), frontend page `ProjectCockpit.tsx` (route `/cockpit`).
- **ARCHIVED status**: Storage alongside BACKLOG for "out of sight." Hidden from the default list (`TodoSpecifications.buildSpec` excludes ARCHIVED when no status filter is set), accessible via the archive tab / edit modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` moves stale auto-TODOs (OPEN/BACKLOG, source AI/WORKFLOW, tag `auto:workflow`, `updatedAt` older than threshold) to ARCHIVED — via bulk UPDATE in a short transaction. Cockpit button "Archive stale" (`POST /todos/archive-stale?projectId=`) is the primary, human-in-the-loop path. The `@Scheduled` job (daily 03:30) calls the same path but is **OFF** by default (`todo-hygiene-enabled`). Thresholds are runtime-configurable via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Ghost watchdog** (`TodoGhostWatchdogService`): resets orphaned IN_PROGRESS TODOs with a machine source (AI/WORKFLOW) to OPEN when the CLI session they belong to died without a closing status PATCH (`updatedAt` older than the threshold). Polls every 5 min (`automation.todo-watchdog.poll-ms`), **ON** by default; human TODOs (source USER) are never touched. One ActivityLog WARNING per run. Runtime settings: `todo-watchdog-enabled` [0,1], `todo-watchdog-stale-hours` [1,720] (default 24h).
- **Bulk Status** (`POST /todos/bulk-status`): generic bulk UPDATE for many TODO IDs to a single status.

## Project bundle

A project can be handed on as **one JSON file** and created again on another machine — also with different
folders (`ProjectBundleController`, core; services in `service/project/bundle/`). Endpoints:
[api-referenz.md](api-referenz.md) → `GET /projects/{id}/export`, `POST /projects/import/preview`,
`POST /projects/import`.

**Format** (`format: mysuricates-project-bundle`, `formatVersion: 1`, file `<project-slug>.mysuricates-bundle.json`):
project master data without path, container, status and ids; the project's workflows (not deleted ones) with their
definition as in the single-workflow export, but without id, project, seal fields and hash; the chains whose entries
only name workflows of this project; the schedules on those workflows and chains — without `enabled`.

**References without UUIDs.** Workflows and chains carry a `key` (slug of the name, made unique with `-2`, `-3` …).
Chain entries and schedules name `workflowKey`/`chainKey`. `sub-workflow` blocks — nested ones too, wherever
`NestedBlocks` looks, keys `workflow_id` and the legacy `workflowId` — point to `@@workflow:<key>@@`; a reference
to a workflow outside the project stays a UUID and shows up under `warnings`. Skills travel by name
(`activatedSkillNames`, in blocks `@@skill:<name>@@`); if a skill is missing on the target machine, the workflow is
created without it (warning).

**Path adjustment.** On export, the strings of the blocks (nested ones too), `instructions`, `metadata`,
`defaultContext` and the `inputContext` of chain entries and schedules are searched:

- The project path becomes `{{ project_path }}` in blocks and `instructions` — that is where the executor resolves
  placeholders. Context values and metadata are not resolved again; there it becomes a token like any other path.
  A project path with a single segment (`/tmp`) never becomes the placeholder.
- Every other absolute path under `/home/ /Users/ /tmp/ /opt/ /srv/ /mnt/ /var/ /data/ ~/` becomes `@@path:N@@`;
  `metadata.workspace.source_path` counts as a path as a whole. A path ends at whitespace, quotes,
  `; | ( ) { } < > , :` and the end of the line. A path that starts with a collected path plus `/` is written as a
  sub path of that token (`@@path:1@@/tests/.`); the shortest wins, the boundary is the `/` (`/tmp/app-venv` is no
  sub path of `/tmp/app`).
- `paths[]` lists `original`, the number of occurrences and the first one as a hint per token. The exporting
  user's home path stays in `original` — it is the suggestion on import; a bundle is therefore no pool artefact.

**Import.** The preview validates (format, version, required fields, unique keys, every reference resolves inside
the bundle — otherwise 400 with all problems) and shows block types, providers, models, the shell commands (the
first 200 characters per field), name conflicts and warnings. The import needs a mapping for every token and writes
in **one** transaction — database work only, nothing slow: project → workflows through `WorkflowService` with
`ChangeSource.IMPORT` (version row, unsealed) → references to the new ids (second step, same path) → chains →
schedules, **always disabled**. Workflows whose name already exists in the target project are skipped (references
then point to the existing one) or renamed (`Name (Import)`, `Name (Import 2)` …). If a step fails, nothing is
left behind; the activity entry follows only after the commit. With module `chains` off, the export leaves chains
out and the import skips them — both with a warning.

**Security.** A bundle brings executable shell commands along. The UI shows them in the preview and offers the
import only afterwards; the API does not require the preview on top — it is protected by the API key like every
other write. Seals are never taken over, schedules never created active; webhooks and integration credentials are
not part of a bundle.

**Limits of v1.** Not included: TODO and completion triggers, webhooks, memory, runs, TODOs, the skills
themselves. No `replace` on conflict. Request at most 5 MB.

## Goals — ordering TODOs across projects

A **goal** (`entity/Goal`, `/api/v1/goals`, TODO dc0566f8) collects TODOs from several projects that work towards one outcome – for example the open-source launch, which touches the platform and the website at once. It is deliberately lean: no dependency graphs, sprints or time tracking. Everything around goals is the standard module `goals` (on in every preset, `automation.modules.goals=false` switches it off, TODO c60bf914): then `/goals/**` and `PATCH /todos/{id}/planning` answer 404 `MODULE_DISABLED`, TODO responses carry no `planning`, `GET /todos?goalId=` is a 400 (silently ignoring it would return the whole list), `milestonesDue` in the cockpit is empty, goal-bound schedules (input context with `goal_id`, such as the goal guard) are skipped and move on, and a run with `goal_id` gets no `goal_*` variables. The data is kept; after switching the module back on, everything is there. In the frontend the "Goals" menu entry, the goal cards and due milestones in the cockpit and the planning section of the TODO dialog are then gone; every goal query is switched off (`useModuleEnabled('goals')`), and `/goals` and `/goals/:goalId` show the module hint (`ModuleRoute`).

- **Planning lives on the TODO:** `goal_id` (at most one goal), `goal_rank` (1 = first, NULL = not ordered yet), `complexity` (S/M/L/XL), `suggested_effort` (`low`…`xhigh`, the same levels as `workflow.effort` and `claude --effort`), `rating_note`, `rated_by` (USER/AI), `rated_at` and `open_question`. Written via `PATCH /api/v1/todos/{id}/planning` (`TodoPlanningService`); `null` leaves a field alone, an empty string clears it.
- **Order:** rank, then priority, then age (`GoalService.WORK_ORDER`). `PUT /goals/{id}/order` sets rank 1..n for the listed TODOs; all others of the goal lose their rank. Assigning is `POST /goals/{id}/todos` (newly assigned TODOs come last, unranked).
- **Progress:** ARCHIVED does not count; open questions only count for active TODOs. List and detail read lean projections (`GoalStatusCounts`, `GoalTodoRow`, `GoalProjectRow`) without descriptions – safe under pool-size=1.
- **Projects of the goal** (`entity/GoalProject`, `GoalProjectService`, TODO c60bf914): a goal is explicitly assigned to one or more projects, in an order (`goal_projects`, n:m). `POST /goals/{id}/projects` appends one (idempotent), `PUT /goals/{id}/projects/order` reorders, `DELETE /goals/{id}/projects/{pid}` removes – with 409 and counts while work packages or TODOs of the goal still hang on it; `?cascade=true` deletes the work packages (milestones lose the link) and releases the TODOs from the goal. When a write path brings a TODO from a new project into the goal (`GoalMembershipService.assign`, `TodoPlanningService`, a project change in `TodoService.update`), the project is added automatically (`GoalProjectRepository.linkIfAbsent`, a native `INSERT OR IGNORE`). The project change goes through `service/todo/TodoGoalConsistency`, which only uses repositories – `service.goal` already depends on `service.todo`. `GET /goals?projectId=` filters the cockpit; `GoalResponse.projects` and `GoalDetailResponse.projects` (with counts) come from one query each; alongside, `byProject` derives the projects from the TODOs.
- **Who plans:** interactively the plugin skill `/mysuricates:plan-goal`, which sees the repository (TODO 9ed0687d); unattended a workflow later (TODO 473f47e0). A person can override any AI assessment in the TODO dialog.
- **Relation map** (`GET /goals/{id}/relations`, `GoalRelationsService`): a goal has no link of its own to workflows, only through its TODOs. For every TODO the endpoint returns the **origin** (`source`, creating run `workflow_run_id`, reference `workflow_run`/`audit_finding`/`health_check`, `audit_finding_todos`), the **processing** (fix workflow `linked_workflow_id` and every matching active TodoTrigger) and the **result** (`last_fix_run_id`, `fix_attempts`, `resolution_state`). It shares the trigger matching with `TodoTriggerService` (`service/trigger/TodoTriggerMatcher`, pure). Everything is batched: one lean projection of the TODOs, then one query per kind. `include` brings up to 20 candidates without a goal along (`GET /todos?goalId=none` finds them).
- **"Might belong here"** (`GET /goals/{id}/candidates`, `GoalCandidateService`, TODO c60bf914): open TODOs (OPEN, IN_PROGRESS, BACKLOG) without a goal, by default only from the goal's projects (a sub-query on `goal_projects`, `TodoSpecifications.inProjectsOfGoal`), with `allProjects=true` from all of them; a page like `GET /todos` (order, batched names). A goal without projects has no candidates unless `allProjects` is set.
- **Work packages** (`entity/GoalWorkPackage`, `GoalWorkPackageService`, TODO c60bf914 and 259f4f66): structure a goal per project (`/goals/{id}/work-packages`). A package belongs to exactly one project of the goal (`project_id`), has an optional period (`start_date`/`end_date` only together, start ≤ end, else 400) and an order per project (`position`, `PUT /goals/{id}/work-packages/order`, only within one project). TODOs are assigned via `PATCH /todos/{id}/planning` with `workPackageId` (alias `milestoneId`) – only packages of the TODO's own goal and project (400 on write, legacy data is still read); a TODO that changes or leaves its goal loses its package, and a TODO changing its project leaves a package of the old project. A package changes its project only while it is empty. The **state is computed on read** (`GoalDueState`, based on the end): `REACHED` (completed) once all assigned TODOs (without ARCHIVED, at least one) are done, else `OVERDUE`, `DUE_SOON` (at most 3 days) or `OPEN`; without a period only `OPEN`/`REACHED`. `completed_at` keeps the first detection and stays; if an open TODO joins later, the response says `reopened: true`. A bare date means "from the start" for a start and "until the end of that day" for an end (UTC). The package list loads goal and packages with their project positions in **one** query (`GoalRepository.findWithWorkPackages`) and counts from the `GoalTodoRow` rows – two statements (`RequestCostRatchetTest`).
- **Milestones** (`entity/GoalMilestone`, `GoalMilestoneService`, TODO c60bf914): dates of the whole goal (`/goals/{id}/milestones`), no project, date required, optionally linked to work packages of the same goal (`goal_milestone_work_packages`, `workPackageIds`). With packages a milestone is reached once all of them are completed (`MilestoneProgress`; `reached_at` = first detection, then `reopened`), without packages a person marks it (`reached`; with packages that is a 400); otherwise the date decides. Milestones and their links are read in **one** query per goal (`GoalMilestoneRepository.findWithLinks`), their state comes from the package states computed anyway. The goal list names each goal's next milestone not yet reached (`GoalResponse.nextMilestone`) – for all goals from one query that counts linked and completed packages in sub-queries (`findSummaries`). The goal view (`GoalDetailAssembler`) reads goal with packages, TODO rows, goal projects and milestones with links – four statements.
- **"Needs you":** `GET /todos/overview` also returns `milestonesDue` – with `kind` `MILESTONE` the milestones of active goals that are `DUE_SOON` or `OVERDUE` (not reached), with `kind` `WORK_PACKAGE` the work packages whose end has passed while they still have open TODOs; each with `goalId`/`goalTitle` for the link, by date. The cockpit lists them ahead of the TODOs, each row leading to the goal page (`components/goal/MilestoneDueRow`).
- **Decided** (TODO 68a5e24e): besides the open question a TODO keeps the decision that answered it (`decision`, `decided_at`, `decided_by` USER/AI) – written via `PATCH /todos/{id}/planning` (`decision`; clears `openQuestion`, `""` takes it back, a new question and a decision at once → 400). The state `decisionState` (`QUESTION`/`DECIDED`/`NONE`) is computed on read: a later new question leaves the decision in place, the state is then `QUESTION`. `decided` counts the non-archived TODOs with `DECIDED` in the goal's progress (from the GROUP BY that runs anyway), per goal project and per work package (from the `GoalTodoRow` rows read anyway) – no extra statement.
- **Workflows on the goal** (`entity/GoalWorkflow`, `GoalWorkflowService`, `GoalWorkflowController`, TODO 52bc1e8c): a workflow hangs on the whole goal or on a work package, role `WORKER` ("works on it") or `STATUS` ("reports the status"); `GET/POST/PATCH/DELETE /goals/{id}/workflows`. The list reads attachments with workflow name, project and package order in one query; soft-deleted workflows drop out; an enabled goal guard is a synthetic first entry `guard` (cannot be detached, 400). Starting goes through the existing `POST /workflows/{id}/execute` with `goal_id` (optionally `work_package_id`, `goal_locale`) in the context – `TriggerSource` MANUAL, `goal_id` provides `goal_status`/`goal_guard_*`; the status comes from `GET /dashboard/workflow-results`. The goal view carries no workflows.
- **Goal guard** (`service/goal/guard/GoalGuardService`, `POST /goals/{id}/guard`): one workflow per goal from the shipped template `goal-guard`, named "Goal guard: <title>" (German: "Ziel-Wächter: …"), `defaultContext.goal_id` and `goal_locale`, created through `WorkflowService` → `WorkflowMutationGateway` with `ChangeSource` `goal-guard`; plus a CRON schedule (default daily 08:00, `inputContext.goal_id`, conflict `SKIP`). Project: the given one, else the first non-archived goal project, else the one with most of the goal's TODOs, else the oldest active one. `goals.guard_workflow_id` remembers the workflow. Switching on again only re-enables the schedule (a new `cron` replaces the old one); switching off and deleting the goal pause it – the workflow stays. If the workflow was deleted, the next switch-on creates a new one. Needs the module `dashboard-report`, else 409. `GoalResponse.guard` = `{enabled, workflowId, scheduleId, nextRunAt}` from one batched query (`GoalGuardLookup`). Why one workflow per goal: the dashboard tile is kept per workflow (latest run with `dashboard_widget`).
- **Context variables `goal_status` and `goal_guard_*`:** when a run's merged context carries `goal_id`, `WorkflowContextDataService` injects the goal's state through the interface `GoalContextSource` (implemented by `service.goal.GoalStatusContext`, so `service.workflow` does not depend on `service.goal`) – milestones and work packages listed separately, marked as data, sanitised like `open_todos`. Without `goal_id` they do not exist; an unknown id sets each of them to the `NICHT GEMESSEN` gap and warns in the activity log. Details: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) → "Goal context".
- **UI:** The goal page — outline, list, timeline and workflow mapping, side panel, "Guard" switch, deciding — lives under `components/goal/`; its pure logic (`goalOutline.ts`, `timelineLayout.ts`, `goalRelationLayout.ts`, `panelGeometry.ts` …) is tested there.

## Notification System

Notification configurations (webhook or desktop) for workflow events are managed by
`NotificationService` under `/api/v1/notifications`. A webhook target counts as an own service: targets in
the local network are refused only with the hardening switch `private-network-guard-own-services` (off by default).

## Assistant System

Context-sensitive chat assistant of the UI (module `assistant`): chat, sessions, page suggestions and
tools that need confirmation. Structure, limits and context injection:
[assistant-system.md](assistant-system.md).

## Workflow Memory

Workflows accumulate knowledge across runs (module `memory`). Usage and API:
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (section "Workflow Memory"), the flows as diagrams:
[workflow-memory-diagrams.md](workflow-memory-diagrams.md), purpose and limits:
[konzepte/workflow-memory.md](konzepte/workflow-memory.md).

- **Extracting:** `WorkflowMemoryService.extractKnowledge()` reads the selected runs or the last 10, `ConversationPreprocessor` condenses their steps, and a `KnowledgeSummarizer` (`ClaudeSummarizer` or `CodexSummarizer`, chosen in `getSummarizer`) summarises them. `POST …/memory/extract` only answers after the AI call, with `201` and a memory that is not active yet (without `provider`: `CLAUDE`). The AI call runs outside any transaction, between a short read and a short write.
- **Summarizers:** Both start their CLI on the host in a working directory of their own under `automation.summarization.base-dir` (`…/summarization/claude` or `/codex`), whose `CLAUDE.md` or `AGENTS.md` `SummarizationSetupService` copies from `src/main/resources/summarization/` at every start. Claude follows `automation.host-claude.timeout-seconds`, Codex `automation.summarization.timeout-seconds` (at runtime `summarization-timeout-seconds`). Unlike workflow blocks, Pi is not wired up here.
- **Using it:** Before the first block, `writeActiveMemoryToProject` writes the active memory to `.claude/rules/project-memory.md` and deletes an old file when none is active; Codex and Pi get it appended to the system prompt through `RunContextFiles`. At most one memory per workflow is active.
- **Automatically:** `AutoMemoryExtractionListener` (`@Async`, only with the module switched on) counts, after every `COMPLETED` run, the successful runs since the last extraction and calls `extractAndActivate` once `metadata.autoMemoryExtraction.interval` is reached (default 5; `autoMemoryExtraction: true` is enough) — always with Claude. If the CLI call fails, no memory is created: the previously active one stays active, `project-memory.md` stays unchanged, the error is in the log.

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — per workflow, editable in the UI (Monaco editor)

3. **Working preferences** (opt-in) — only when a workflow requests them via
   `metadata.userPreferences: true`. `UserPreferencesService` then renders exactly three runtime
   settings into a `## Arbeitspräferenzen` section: `user-pref-language`, `user-pref-style`,
   `user-pref-stack` (answer language, answer style, tech stack). Nothing else is possible: the key
   list lives in the code, an additional key is a deliberate change. **No names, contact details,
   CVs or professional profiles.** With nothing set, or without the flag, the prompt gets no such
   section; a checking or measuring workflow should depend on the facts alone. Values are maintained via
   `PUT /api/v1/system/settings/{key}`.

Injection paths:
- **Block execution:** Instructions via `--append-system-prompt` on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System prompt via `--system-prompt` to `HostClaudeService.reasonStructured()`, including workflow instructions in `## Workflow Instructions`

## Host Claude Service

All Claude CLI calls of the blocks are executed on the host (not in containers; only ad-hoc commands during a run go through `docker exec` into the project container):
- `HostClaudeService.executeAction()` — ACT phase, uses `--append-system-prompt`, `--model` flag, project working directory
- `HostClaudeService.reasonStructured()` — REASON phase, uses `--system-prompt`, `--max-turns 3`; `--model` is set when the workflow configures a model (`ReasoningRequest.model`)
- If the CLI ends a `reasonStructured()` call with a non-zero exit code (limit, missing login, crash), the call throws an `IOException` with exit code and message (`CliExit`, likewise `HostPiService.reason()`; Codex throws on its own). A reasoning result is therefore always an answer; otherwise the summarizer and the context generator would store the error message as memory or project context. `executeAction()` returns the exit code instead, and the block decides.
- Exit code 0 alone does not mean success: if the `stream-json` result line reports `is_error: true` (API error, limit) or an `error_*` subtype (e.g. `error_max_turns`), the call counts as failed – `ClaudeCliOutcome` then sets exit code 1 and replaces the output with a message naming subtype and turns (e.g. "Claude CLI reported an error (error_max_turns) after 3 turns"), never with the raw NDJSON stream; `reasonStructured()` throws it as it is after "Host Claude reasoning failed:", without a made-up exit code. This applies to `HostClaudeService` and `CliSession` (ad-hoc commands in the container); pi returns plain text and Codex throws on its own, so neither has such a field.
- Prompt is written via **stdin** to the process (`writePromptToStdin()`) — avoids Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
- The backend's own secrets (`AUTOMATION_API_KEY`, `AUTOMATION_SSL_PASSWORD`) are stripped by `util/ChildProcessEnv` from the environment of every AI CLI, shell block and integration process — even when the backend itself was started with them (Docker). A run with foreign input must not be able to drive the API with the instance key; `ChildProcessEnvGateTest` enforces this for new spawn sites. The key file in the home directory stays readable for processes of the same user (open: TODO 9525820a)
- `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — all overloads use the `HostExecutionRequest` builder API
- Skill context is injected file-based: `writeSkillContextToProject()` writes `.claude/rules/skills-context.md` into the project directory

## Provider Routing

The AI-capable block executors (simple, retry, conditional, foreach, while, switch, skill-capability, compress-ai, agentic) determine the provider with `context.providerFor(block)`: `provider` on the block before the workflow's `provider`, without either the system default. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every AI block without its own `provider`; `compress-ai` only runs with `CLAUDE` (otherwise the step fails). On the `integration` block, `provider` names the integration provider, not the AI provider.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is passed.

Whether a provider is ready is reported by `GET /api/v1/system/ai-providers/status` (`service/ai/status/AiProviderStatusService`): program on the `PATH` and a login via login file or environment variable — the same rule as the installer (`scripts/lib/providers.sh`), from `AiProvider#setup()` (`ProviderSetup`). Container instances move the login directories (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`), and the check follows them (`ProviderSetup.loginDir`/`loginDirEnv`). No CLI call, cached (`automation.ai-provider-status.ttl-seconds`, default 60 s), `POST …/status/refresh` checks at once. The answer holds only names and commands, never a value. The start page shows the status while there is no project or the default provider is not ready (`AiProviderStartCard`), the settings always (`AiProviderStatusCard`).

## Per-Block Skills

Blocks can bring their own skills via the `skillIds` field, which apply additively to the workflow-level skills:

- `BlockDefinition.skillIds` — optional list of skill IDs that are activated only for this block
- `BlockSkillContext` merges workflow-level skills + block-level skills before each block execution and restores the workflow baseline afterwards
- After block execution, the baseline skill state is restored
- Skill context is provided file-based via `.claude/rules/skills-context.md` in the project directory (`CommandExecutorService.writeSkillContextToProject()`); the Claude CLI reads `.claude/rules/` itself, pi (`--no-context-files`) and Codex get project context, skills and workflow memory appended to the system prompt via `RunContextFiles`
- Frontend: Skill picker toggles in the add/edit block modals, zap icon indicator on `SortableBlock`

## Model Selection

- `workflow.model` field: CLI alias (`opus`, `sonnet`, `haiku`, `fable` — always the newest model) or a concrete id (`claude-opus-5`); pi `<backend>/<model>`, codex slug
- Suggestions: `GET /api/v1/system/ai-providers/{id}/models` — `ModelCatalogService` with one `ModelCatalogSource` per provider (Claude: aliases, as long as the `claude` program is on the `PATH` — otherwise `available: false`; PI: `pi --offline --list-models`, CODEX: `~/.codex/models_cache.json` or `codex debug models --bundled`), TTL cache (setting `model-catalog-ttl-seconds`), at most one probe per provider at a time (concurrent requests share it), `POST .../models/refresh`
- Passed via `--model` flag to the Claude CLI on the host
- REASON and ACT of an agentic run use the same workflow model (`ReasoningRequest.model` or `ActionRequest.model`); without a configured model the provider falls back to its default

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional output token limit per call
- Applied via `--max-tokens` flag in `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in the block config
- Overrides the global `automation.host-claude.timeout-seconds` value (default: 300s) for this block
- Without `timeout_seconds` in the config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both the shell and Claude CLI timeout
- Applies to `reasonStructured()` and `executeAction()` in agentic blocks

## Workflow Conversion

Successful agentic runs can be converted into deterministic workflows:
- `WorkflowConversionService` — analyzes the run and proposes a structured workflow (rule-based + AI); `WorkflowConversionPrompt` builds the AI request
- `ConversionController` — API endpoints for preview and creation
- Frontend: Conversion dialog in the execution view

### Dialog-to-Workflow Conversion

Claude Code sessions can also serve as templates for workflows:
- `DialogSessionScanner` — scans `~/.claude/projects/` for JSONL session files
- `DialogConversionService` — analyzes a session with Claude and generates a workflow suggestion; `DialogConversionPrompt` builds the AI request
- `ClaudeJsonlParser` — parses JSONL session files
- `DialogController` — `/api/v1/dialog` with session list, analysis and workflow creation
- Path traversal protection: only paths under `~/.claude/projects/` with `.jsonl` extension are allowed

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table, not hardcoded
- **54 built-in templates** are seeded at startup by `WorkflowTemplateSeeder` (idempotent, inserts missing ones, updates on version bump). Built-in rows whose file no longer ships (moved to the pool, renamed, left-overs with a random id) it removes first; the file names decide (`BuiltInTemplateLoader.shippedIds`), so a file that fails to parse does not lose its row. Verifications go along via `ON DELETE CASCADE`, workflows made from such templates stay and only lose the back-reference (`ON DELETE SET NULL`). User templates are left alone
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted (409). Fields changed via PUT stay until a release ships a higher `version` – then `WorkflowTemplateSeeder` overwrites them at startup. A PUT with `blocks` or `instructions` makes the template single-language (the second language variant is dropped)
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- Every response carries `requiredModules` (the modules its blocks need, nested ones included; `BlockTypeModules.requiredBy`) and `needsAi` (whether a run can call a model; `executor/ModelUse`, the same rule as the AI badge in the frontend). The template picker hides templates and blueprints whose module is off and shows a counter instead ("N more need a module – show"). Once shown, the card names the module and how to switch it on. On top come the "Runs without AI" filter and translated categories
- CRUD API at `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- **19 built-in skills** are seeded at startup by `SkillSeeder` (idempotent)
- **Location:** `src/main/resources/skills/builtin/<id>.json` (metadata: `sortOrder`, `version`, `name`, `icon`, `description`, `category`, `capabilities` incl. `writes`/`async`/`handlerClass`/schemas) plus `<id>.md` holding the prompt text. Loaded by `BuiltInSkillLoader` (modelled on `BuiltInTemplateLoader`)
- The `.md` is taken **byte-for-byte** (no trim, no appended newline). `BuiltInSkillsSnapshotTest` compares all skills canonically against `src/test/resources/skills/builtin-snapshot.json` and fails on any unintended change; rewrite intended changes with `./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true`
- **Version rule:** `SkillSeeder` seeds version-based (semver comparison in `SeedVersions`, like `WorkflowTemplateSeeder`) — missing skills are inserted, existing ones are only overwritten when the resource `version` is higher (capabilities included). So a content change to a built-in needs a higher `version` in its JSON, otherwise it never reaches existing instances. User skills (`builtin=false`) and built-ins no longer present in the resources are left untouched
- **Gate for the version rule:** `BuiltInSkillVersionGateTest` hashes each skill's `<id>.json` (canonically) plus its `<id>.md` (byte-for-byte) against the checked-in list `src/test/resources/skills/builtin-versions.json` and fails when the hash changed without a higher `version` or when the list is stale — after an intended bump, rewrite and commit it with `./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true`
- Loader failures are hard: a missing `.md`, a filename that differs from the `id`, a missing or duplicate `sortOrder`, a missing/unknown `category` or an empty directory abort startup instead of seeding an incomplete skill set
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (subdirectories `code/`, `document/`)
- Users can create custom skills via import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (instead of `@GeneratedValue`) for explicit IDs on built-ins

## Template Verification

- `TemplateVerification` entity — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification status per template+project pair
- Workflows created from templates can be test-started to verify template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic. The block schema in the prompts of the factory, wishes, dialog import, run conversion and workflow optimization comes from `BlockSpecs` (the same texts as the assistant's `fetch block-spec`); `PromptBlockSchemaGateTest` checks every block type and config key named there against the executors, so that no prompt names keys no executor reads (such as `forEach`, `then_blocks`, `if_true`). An example of retry, foreach, while or conditional with a command must also name `execution_mode`: without it the command goes to the AI as a prompt, and the AI builds what the example shows.
- **Build check after generation** (`service/workflow/analyzer/GeneratedWorkflowCheck`, shared by the factory, wishes, dialog import, run conversion and the assistant): after the AI answers, the `WorkflowAnalyzer` runs over the blocks. On HIGH findings the same AI (same provider, model and system prompt) gets **exactly one** correction round with its block list and the findings; if the answer is unusable, changes the structure (a different block count, a different block type at a position, a different number of nested blocks in a container — typically the model returns only the repaired block) or the corrected version has more HIGH findings, the original stays. If the correction omits a block's `name`, `skillIds` or `requiredCapabilities`, they are taken from the original. MEDIUM/LOW never cause an extra call. The workflow is then saved; the check changes nothing itself and never blocks saving. Remaining findings from MEDIUM up appear in the result's `buildFindings` (German; the same lines in English in `buildFindingsEn` — factory `/run`, suggestions of dialog import and run conversion — the UI shows them as "Build check" in its language) and are logged as WARN in the activity log on the workflow when it is saved. In the factory the check runs in `generateWorkflow`, i.e. before any test run; rule-based suggestions (no AI) are only reported. The assistant gets no correction round: `create_workflow`/`update_workflow` append the findings to their result line (in the UI language), and that line goes, shortened, into the model's history with the next turn ([assistant-system.md](assistant-system.md) → "Tool results in the history") so it can fix them with `update_workflow`. Every finding (`AnalyzerFinding`) carries its text in German (`detail`) and English (`detailEn`); `AnalyzerFindingLanguagesTest` triggers every rule and requires both.
- `WorkflowFactoryService` — orchestrates the entire factory process
- `FactoryVerificationService` — verify/fix loop: run on the test instance, repair the blocks via AI on failure, report the result to Dev (also used by auto-repair and wishes)
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-based workflow generation
- `WorkflowTextGenerator` — text-based workflow generation
- `FactorySetupService` — configuration and setup of the factory environment
- `FactoryPromptBuilder` — architect, generator and auto-fix prompt; the Codex `AGENTS.md` refers to the prompt for the block types
- `WorkflowFactoryController` — `/api/v1/factory` with endpoints:
  - `POST /generate` — generate workflow from topic
  - `POST /run` — run generated workflow on test instance
  - `POST /verify/{id}` — verify workflow
  - `POST /promote/{id}` — promote workflow to production
  - `GET /topics` — list available topics
- Uses the test instance (port 8090) for safe execution
- Flow of `POST /run` ("Create & Test"): generate on DEV (including the build check, see above) → `syncDefinitions()` → test run on the Test instance (polling) → on `FAILED` with `autoFix`, AI repair on DEV from the error message (new version), sync and test again, up to `maxRetries` → record the test result on DEV → with `autoPromote` and a passed test, save a template. `POST /generate` ("Create Workflow") runs the first step only.
- **Build check after every auto-fix repair** (`FactoryVerificationService.autoFixWorkflow`; used by `/run`, by `verifyAndFix` for wishes and auto-repair, and by the local auto-repair): before a repair is saved, `GeneratedWorkflowCheck.reviewRepair` applies the same rules as to the correction round — without another AI call. If the repair changes the structure (a different block count, a different top-level type at a position, a different number of nested blocks in a container) or has more HIGH findings than the current version, it is discarded with a WARN and the workflow stays unchanged; an omitted `name`, `skillIds` or `requiredCapabilities` is taken from the current version. In `/run`, `verifyAndFix` and the local auto-repair a discarded attempt counts as a failed attempt without another run of the unchanged workflow; `/run` names the reason in `retryErrors`, the auto-repair in its failure message in the activity log. The factory, the correction round, wishes, dialog import and run conversion read AI-generated blocks through the same mapping (`util/BlockJsonReader`), so `name`, `skillIds` and `requiredCapabilities` are kept and `{{ block.<name>.output }}` references resolve.
- The flow diagram on the factory page (`components/factory/FactoryFlowDiagram`) is an explanation, not a live display: `/run` is a blocking call without intermediate events.

## Workflow Pool

The pool is the set of shipped workflow definitions under `workflow-pool/` that can be installed into
any instance. `WorkflowPoolService` reads them, `WorkflowPoolController` (`/api/v1/pool`) lists, installs
and exports. What belongs in the pool and how it is checked:
[workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

## Wishes (Wish System)

Natural language wishes are automatically converted into workflows/chains:
- `WishService` — creates wishes, handles user responses (`respond`), crystallizes into templates
- `WishFulfillmentLoop` — asynchronous meta-loop (decompose → execute → evaluate) incl. cancel flags and total timeout
- `WishEvaluationService` — evaluation phase (AI verdict → AWAITING_APPROVAL / REFINING / FAILED)
- `WishWatchdogService` — monitors running wishes for stale/timeout states
- `WishDecomposePromptBuilder` + `WishSystemPrompt` — decomposition prompt; its timeout guidelines are the `automation.wish.*` values `WishDecomposeService` fills in on creation
- Iterative process: Decomposition → Workflow/Chain generation → Execution → Feedback
- Supports up to `max_iterations` iterations for improvements
- `crystallize()` — promotes successful wish as template

## Integration System

External services are providers: one `IntegrationProvider` bean each, found by `IntegrationRegistry`,
with an OAuth or token flow (`IntegrationOAuthService`), encrypted credentials
(`IntegrationEncryptionService`, AES-GCM) and actions the `integration` block runs. Providers, target
kinds and how to add a new one: [integrations.md](integrations.md).

## Dead Letter Queue

Failed asynchronous operations are stored in a dead letter queue:
- `DeadLetterService` — records an entry for every FAILED run via `@EventListener` on `WorkflowRunCompletedEvent` (not called from `service.workflow` — that would form a package cycle); management of dead letter entries (CRUD, retry, discard)
- `DeadLetterController` — `/api/v1/dlq`
- `DeadLetterEntry` entity with status: OPEN, RETRIED, DISCARDED

## Workflow Search

Aggregated search across workflows with multiple search strategies:
- `SemanticWorkflowSearchService` — substring/fuzzy search + semantic ranking via AI
- `SearchController` — `/api/v1/search`

## Docs System

Backend reads Markdown documentation files and serves them as an API:
- `DocsController` — `/api/v1/docs`, reads `.md` files from the configured `docs/` directory
- Path traversal protection built in
- Frontend displays docs in split layout with Markdown rendering

## Frontend Architecture

Structure and rules of the UI: [frontend.md](frontend.md).

### Data Flow

```
User Interaction
    ↓
React Component
    ↓
Hook (useMutation / useQuery)
    ↓
API Client (fetch + X-API-Key Header)
    ↓
Vite Proxy (:3000 → :9090)
    ↓
Spring Boot Controller
    ↓
Service → Repository → SQLite
    ↓
Response (DTO) → React Query Cache → UI Update
```

## WebSocket (Live Logs + Status)

- **Protocol:** STOMP over SockJS
- **Endpoint:** `/ws` (SockJS handshake, authenticated by ticket — see [WebSocket Authentication](#websocket-authentication)); the terminal uses `/ws/terminal/**`, at most three sessions per container (`TerminalWebSocketHandler`)
- **Broker:** Simple Message Broker on `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — Workflow execution logs
  - `/topic/chains/{chainRunId}/status` — Chain execution status
  - `/topic/activity-log` — Live activity log updates
  - `/topic/wishes/{wishId}/status` — Wish status updates
  - `/topic/assistant/{requestId}/status` — Assistant chat status (per request)
  - `/topic/assistant/{sessionId}/events` — Assistant push events (per session)
- **Messages on `/topic/executions/{runId}/logs`:** JSON objects distinguished by `type` —
  step messages (no `type`: `stepIndex`, `status`, `input`, `output`, `durationMs`, `blockType`, `blockName`),
  `status`, `approval_*`, `human_input_*`, `agentic_*`, `foreach_item_*`, `container_*`, `command` and `live_output`
- **Live output of running blocks:** shell, container, pi and codex processes deliver their output line by line;
  Claude `stream-json` is condensed into short lines (tool call, truncated text, tool result size, closing summary
  with turns/cost/duration).
  - **Mechanism:** for the duration of a step (including validation retries and nested blocks) `BlockStepRunner` binds a
    sink via `ThreadLocal` (`service/live/LiveOutput`, deliberately not inheritable — sub-workflows stream into their own
    run). The process runners read it on the calling thread and split the pipe into lines via `util/ProcessOutputTee`;
    the collected step output stays byte-identical. Callers outside workflows (assistant, tagging, search) are unbound
    and stream nothing.
  - **Transport:** `RunLiveLogService` keeps a ring per run (default 1000 lines) and sends at most every 250 ms one batch
    `{ type: "live_output", runId, bufferId, lines: [{ seq, stepIndex, kind, text, ts }], skipped }`; it flushes
    synchronously before the final step message. `kind`: `line`, `text`, `tool`, `tool_result`, `tool_error`, `result`,
    `system`. Line and character caps apply per batch (newest lines win, the rest counts as `skipped`).
    `setPreservePublishOrder(true)` keeps the order per session.
  - **Replay:** `GET /api/v1/executions/{id}/live-log?afterSeq=-1` returns the buffer; the frontend loads it on
    (re)connect and de-duplicates by `bufferId` + `seq`. Buffers live in memory and expire 10 minutes after the run
    ends (also without a completion event, via the check against `WorkflowExecutionRegistry`), at most 20 runs.
  - **Keeping:** on `WorkflowRunCompletedEvent`, `RunLiveLogArchive` writes the buffer as one row into
    `run_live_logs` (gzip JSON of the lines and step names, FK to the run with `ON DELETE CASCADE` – run retention and
    `DELETE /executions/{id}` remove it). Whether it does is decided by `workflows.live_log_persist` (`on`/`off`, NULL =
    system default `automation.live-log.persist-default`, runtime key `live-log-persist-default`, on by default).
    The switch is outside `WorkflowSnapshot`: no hash, no version, changeable on sealed workflows. Once the buffer has
    expired, the replay returns the stored row (`stored: true`, same `bufferId`/`seq`). A resumed run from a new buffer
    is appended and renumbered. Runs without a completion event (ghost reaper, zombie marking at startup) keep no log.
  - **Configuration:** `automation.live-log.*` (`enabled`, `max-line-length`, `buffer-lines`, `flush-interval-ms`,
    `max-lines-per-flush`, `max-chars-per-flush`, `retention-minutes`, `max-buffered-runs`); read at startup only,
    except `persist-default`.
- **Terminal WebSocket:** `/ws/terminal/**` for real-time container access (raw WebSocket via `RawWebSocketConfig`, handler: `TerminalWebSocketHandler`)

## Security

### API Key Authentication

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (before Spring Security)
- **Comparison:** Constant-time (`MessageDigest.isEqual`)
- **Auto-generation:** Key is stored in `app_settings` on first startup
- **Disableable:** `automation.security.enabled=false` (development)
- **Rights:** The key may do everything — read, write, start, change settings; whoever holds it controls the instance. The only restricted credential is the [run token](#workflow-hardening-optional) (`X-Run-Token`, only `GET` on `security/RunTokenPaths`, only while its run is running).

### Public Endpoints (no API key required)

Which paths are reachable without a key lives once in `security/PublicPaths` (exact paths, patterns, the protected areas `/api/**` and `/actuator/**`); the list with the purpose of each path: [deployment.md → What is reachable without an API key](deployment.md#what-is-reachable-without-an-api-key). `SecurityConfig` builds its rules from it, and `ApiKeyAuthFilter` uses the same matchers to decide whether a missing key is logged as a SECURITY warning — only where the chain also rejects it. Exact paths match only themselves (no `/health/`, no `/health/extra`); everything outside the protected areas (the shipped frontend) is open.

### Rate Limiting

- **Filter:** `RateLimitFilter` (reads from `RateLimitConfig` bean)
- **Algorithm:** Sliding window (1 minute)
- **Default:** 120 requests/minute per client IP; requests with a run token per run
- **Response header:** `X-RateLimit-Remaining`; on a 429 also `Retry-After` — seconds until the bucket's minute window turns over (at least 1), the same value as `retryAfterSeconds` in the JSON body
- **Runtime-configurable:** Settings → Execution → API rate limit (toggle + RPM value)
- **Env override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`
- **Client backoff budget:** `automation.security.rate-limit.client-global-backoff-seconds` (2) and `client-default-retry-after-seconds` (10) — see below
- **Webhook rate limiter:** `WebhookRateLimiter` — separate per-IP limiter for `/api/v1/webhook/trigger/**`, always active
- **Client IP (`ClientIpResolver`):** `X-Forwarded-For` only counts when the direct peer is listed in `automation.security.trusted-proxies` (default `127.0.0.1,::1`), and is then read **from the right**: the client id is the right-most entry that is not a trusted proxy (all header lines form one chain). The left part comes from the client itself and would otherwise give every request a bucket of its own.
- **Bucket store (`ClientBucketStore`):** both limiters sweep expired buckets on access (at most once a minute) and cap the map at 10,000 clients — beyond that, the buckets with the oldest window go first.
- **Webhook signatures (optional):** per-webhook HMAC-SHA256, see [Workflow Hardening](#workflow-hardening-optional)

**Global server bucket, per-path client lock — the asymmetry is deliberate.** The server counts in
*one* bucket per client IP across all endpoints, so a 429 means "you as a whole are too fast". The
client (`api/client.ts`) in contrast remembers the lock **per endpoint pattern** (the path without
its query and with UUIDs replaced by `{id}`, so `/executions/{id}` does not get one key per run).

On top of that comes a **short lock over the remaining paths: at most 2 s, capped by
`Retry-After`** — with `Retry-After: 0` no global lock is created at all. Two endpoints are exempt from it (`GLOBAL_LOCK_EXEMPT`): `POST /ws-ticket` (a blocked
ticket request costs the WebSocket one reconnect attempt and doubles its exponential delay, turning
2 s into a much longer outage) and `GET /system/auth-status` (the `ApiKeyGuard` renders nothing until
it answers — the app would stay empty instead of showing the rate-limit hint). Their own path lock
still applies to them; they are only exempt from *somebody else's* 429.

**Where those two numbers come from: the instance, not the bundle.** The 2 s of the short global
lock and the 10 s the client assumes without a usable `Retry-After` remain in the client as
constants (`GLOBAL_BACKOFF_SECONDS`, `DEFAULT_RETRY_AFTER_SECONDS`) only as a *fallback*. The values
actually in force come from `GET /api/v1/system/info` under `rateLimit` (`enabled`,
`requestsPerMinute`, `globalBackoffSeconds`, `defaultRetryAfterSeconds`); `useSystemInfo` hands them
to the client via `applyServerRateLimitConfig()` — the endpoint is loaded at startup anyway
(sidebar) and needs no API key, so this costs no request of its own. They are set through
`automation.security.rate-limit.client-global-backoff-seconds` and
`client-default-retry-after-seconds`; missing or non-positive values are ignored and the client
keeps its constants. Background: an instance with a different `requests-per-minute` turns
hard-wired client numbers into guesswork (37b98c97).

The `Retry-After` header comes **from the `RateLimitFilter` only**. The two other sources of a 429
do not send it: the webhook limiter (`WebhookController` → `WebhookRateLimiter`, an empty 429) and
`TOKEN_BUDGET_EXCEEDED` (`ErrorCode`/`GlobalExceptionHandler` — a budget problem, not a timing one,
where waiting does not help at all). For the browser client that means: a 429 on `/api/v1` almost
always carries the header, and the `defaultRetryAfterSeconds` value only applies in those special
cases or when a proxy swallows the header.

The consequence: after a 429 storm the client releases individual patterns again after at most 2 s,
and each pattern collects its own 429 until its `Retry-After` lock is in place. The short global lock
takes the peak off the storm without closing the two ways out. Recovery needs no reload: a timer on
the last deadline (`scheduleExpiry`) reports the expiry, and `installRateLimitRecovery` invalidates
**every query in an error state once** on the active → inactive transition — `retry` is off for 429
and `refetchOnMount` only fires on a mount, so permanently mounted queries would otherwise stay red
until a reload. What it picks up is exactly what really is in `error`. Queries with `initialData`
(`useSystemModules`, `useBlockTypes`) stay on `success` even after a 429 and are therefore never
touched by the recovery — deliberately: the last known module and block-type state should stay put
instead of making navigation and widgets flicker (b13509f3). They pick the server state up on their
next mount, because `initialDataUpdatedAt: 0` makes the seeded state stale at once.

**An empty `?ticket=` in the handshake is the client fallback.** If `POST /ws-ticket` runs into a
lock (or fails otherwise), `useWebSocket` sets `wsTicket = ''` in `beforeConnect` and the handshake
goes out with an empty `?ticket=`. The interceptor refuses it, stompjs schedules the next attempt
with a growing delay — that is the intended path, rather than an unhandled promise rejection that
would tear the reconnect cycle apart. An empty ticket in the server logs is therefore not an attack
but a client whose ticket request did not get through.

### Request Size Limit

- **Filter:** `ActivityLogSizeLimitFilter`
- **Max:** 64 KB (configurable via `automation.activity-log.max-request-bytes`)
- Only for `/api/**` paths

### WebSocket Authentication

Neither SockJS nor a raw browser WebSocket can send its own headers during the handshake, so authentication has to travel in the URL. The API key must not end up there (browser history, proxy logs), so a **short-lived single-use ticket** goes first:

1. The client calls `POST /api/v1/ws-ticket` — **with the API key in the header**, like every other endpoint (subject to the normal API rate limit). Response: `{ "ticket": "<43 chars>", "expiresInSeconds": 30 }`
2. The client appends the ticket as `?ticket=…` to the handshake URL (`/ws` and `/ws/terminal/{runId}`)
3. `WebSocketAuthInterceptor` redeems the ticket and lets the handshake through — **exactly once and only before it expires**. An `?apiKey=` is refused (401, handshake aborted) and logged with its own message.

- **Ticket store:** `WebSocketTicketService` — purely in memory (insertion-ordered `LinkedHashMap` with `synchronized` access instead of a `ConcurrentHashMap`, no DB access during a handshake), 32 random bytes URL-safe Base64, expired entries are swept on every `issue()`/`consume()` (no scheduler). On top of that the store is hard-capped via `removeEldestEntry` at **`MAX_OPEN_TICKETS` = 1000 outstanding tickets**; beyond that the oldest one is dropped FIFO-style (the affected client simply fetches a new one on its next attempt). Tickets do not survive a restart — exactly their intended lifetime.
- **Lifetime:** `automation.websocket.ticket-ttl-seconds` (default 30, env `AUTOMATION_WS_TICKET_TTL_SECONDS`)
- **Frontend:** `api.fetchWsTicket()`; `useWebSocket`/`useAssistantWebSocket` fetch the ticket in stompjs' `beforeConnect` (runs before every connect attempt including reconnects), the run terminal before `new WebSocket(...)`. A guard test forbids `apiKey=` anywhere in `src/`.
- **No SockJS fallback:** the clients run with `transports: ['websocket']`. SockJS opens a separate session for every fallback transport (xhr-streaming, xhr-polling) and would need a second ticket — the first one is spent after the WebSocket attempt. **The instance therefore needs a WebSocket-capable path** (any proxy in front must pass upgrades through); there is no HTTP fallback.
- **Reconnect:** `reconnectDelay: 5000` with `ReconnectionTimeMode.EXPONENTIAL` and `maxReconnectDelay: 60000` — every attempt costs a `POST /ws-ticket` and must not run into the API rate limit while the backend is down.
- **Disabled:** With `automation.security.enabled=false` or no configured key the interceptor lets every handshake through (like `ApiKeyAuthFilter`).

### CORS

- One answer for the CORS filter, WebSocket handshakes and OAuth popup: `security/AllowedOrigins`
- Configured list `automation.cors.allowed-origins` (default: `http(s)://localhost:3000` and `:5173`)
- With the module `remote-access`, additionally the instance's own Tailscale IPv4 and MagicDNS name, any port (`service/system/OwnTailnetAddresses`: `tailscale ip -4` / `tailscale status --json`, kept for 60 s)
- WebSocket: `AllowedOriginHandshakeInterceptor` checks before the ticket; Spring's own check gets `*` because it only knows a fixed startup list
- All HTTP methods (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- No credentials (auth via `X-API-Key`, no cookies)

## Architecture Gates

Two JUnit tests in package `com.automation.architecture` pin the measured structure, without any new dependency. Their base measurement is the same jdeps package edges as in `scripts/public-readiness/arch-metrics.sh` (section `## packages`); `LayerRuleTest` additionally checks a second view (see below). `PackageCycleRatchetTest` pins today's mutually dependent package pairs as an allowlist — a new pair fails the build, a resolved one is only reported as a hint. Because pairs alone do not see every cycle (a single new edge can pull a package into a large cycle component without forming a new pair), it also caps the strongly connected components, i.e. the `cycle` lines of `arch-metrics.sh`: the largest at `MAX_CYCLE_COMPONENT_SIZE`, and every component with more than two packages is pinned with its members in `CYCLE_COMPONENTS` — a package newly pulled in fails the build, and the message names the edges that tie it in; when a component shrinks, the test fails as well until both values are lowered in the same commit. `LayerRuleTest` forbids the edge classes `service.* → controller.*`, `controller.* → repository.*` and `repository.* → service.*` (allowlist empty today). What gets measured is always the build directory the tests themselves were loaded from (`target/`, or `target-ide/` under the IDE profile), never a stale one sitting next to it; if it or jdeps is missing, both tests are skipped with a clear message instead of passing silently.

The blind spot of the bytecode measurement is a dependency that only appears as a generic type argument and is erased from the bytecode. `LayerRuleTest` therefore checks the same three rules from a **second view**: `SourceImportEdges` reads the `import` lines of the sources under `src/main/java` (column 0, so comments and string literals never count) and evaluates them against the same rules, with its own allowlist (empty today as well). The views do not overlap — the bytecode view sees inheritance, static references and generated code, the source view sees the erased type arguments — and the test additionally asserts that the scan read any classes at all: an empty measurement would otherwise report every rule as kept. **One gap survives both**: a fully qualified name inside a generic type argument (`Page<com.automation.repository.Foo>` written without an import) is erased in the bytecode and has no import line. For projections that travel from the repository up into the controller this means: they belong in `com.automation.dto.response`, not in the repository package.
