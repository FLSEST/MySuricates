# Architecture

MySuricates is a self-hosted workflow platform: a Spring Boot backend with a web interface, in
which processes consist of blocks — some deterministic (shell, branch, loop), some AI calls.
This page describes its internal structure. For getting started see
[README.md](../../README.md); for building workflows see
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md).

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up. Numbers such as `(V39)` in headings, in
> contrast, name the database migration (Flyway script `V39__….sql`) that brought a
> feature — list under [Database Migrations](#database-migrations-flyway).

> The flows on this page also exist as diagrams: [docs/en/diagramme.md](diagramme.md)
> — engine, scheduler, skills, memory, request path, frontend, deployment.

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

| Environment | Port | Description |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Development server, from source code |
| **Stable** | 9091 | Docker container, backend + embedded frontend |
| **Test** | 8090 | Docker container, dedicated test instance |

Dev automatically starts Stable and Test on boot if a snapshot is available (configurable via `automation.staging.auto-start` and `automation.staging.test-auto-start`).

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

**Catalog** (single source: `modules/ModuleCatalog`, 21 feature modules + 8 hardening switches = 29 keys):

| Tier | Modules | Maturity |
|---|---|---|
| `standard` (all presets) | `dashboard-report`, `goals` (goals with projects, work packages, milestones, TODO planning, workflows on a goal, goal guard) | stable |
| `research` | `chains`, `assistant`, `learning` (analytics endpoints; dashboard data stays core), `memory`, `audit`, `bpmn` | chains stable, others experimental |
| `advanced` (outward-facing) | `integrations` (incl. TODO tracker export, calendar adapters), `http-api` (general HTTP API connection, requires `integrations`), `webhooks`, `remote-access` (Tailscale), `terminal` (run terminal over WebSocket), `staging` | stable / staging experimental |
| `advanced` (low use) | `conversion`, `dialog-import` (requires conversion), `onboarding-profiles`, `skills`, `debug`, `factory` (incl. auto-repair), `wishes` (requires chains) | legacy / experimental |
| `hardening` | `provenance` (on by default), `sealing`, `require-sealed-for-unattended`, `webhook-signatures`, `private-network-guard` (on by default), `private-network-guard-http-api` | experimental |

The catalog is additionally exported as plain bash arrays to `scripts/lib/module-catalog.generated.sh`
— read by `scripts/lib/module-preset.sh`, which runs before every build and therefore must not
require a backend or `jq`. The file is not hand-maintained: after a change to the catalog or to the
display texts (`ai-automation-frontend/src/data/modules.<lang>.json`), regenerate it with:

```bash
cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
```

Without regeneration, `ModuleCatalogTextsTest` fails red as soon as the generated file no longer
matches the catalog — whoever adds a module finds out at the latest there.

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
- **Hardening switches always stay startup configuration** (400): they protect against exactly the caller who holds the API key.
- Trade-off: with `ui-editable` anyone holding the API key can turn on webhooks (public endpoint), integrations (tokens), remote access or the run terminal (host access) — keep it off on publicly reachable instances. Running runs that reach a block of a module that was just switched off fail with the usual message.

## Workflow Hardening (optional)

Switches under `automation.hardening.*` — the same mechanism as modules (startup configuration only, independent of the preset, shown read-only in **Settings → Modules & hardening**). Change provenance is not a switch; it is always on.

| Switch | Default | Requires | Effect |
|---|---|---|---|
| `provenance` | on | — | Every run stores `definition_hash`, `definition_version_id` and `trigger_source` |
| `sealing` | off | `provenance` | Seal workflows (`POST /api/v1/workflows/{id}/seal`, `…/unseal`) |
| `require-sealed-for-unattended` | off | `sealing` | Unattended runs only start sealed workflows |
| `require-signed-seal` | off | `sealing` | Sealed workflows need a valid signature |
| `seal-immutable` | off | `sealing` | Unsealing no longer over REST — only via the startup configuration |
| `webhook-signatures` | off | module `webhooks` | Per-webhook signing secret (HMAC-SHA256) |
| `private-network-guard` | on | — | iCal feed and RSS/Atom feed fetch public addresses only (see [Integrations](integrations.md#fetches-into-the-local-network)) |
| `private-network-guard-http-api` | off | — | The same block for http-api (own network reachable otherwise) |

The two `private-network-guard` switches control the **outbound guard** (`OutboundTargetGuard`): where the instance itself connects to. Which provider uses which switch follows from its target kind — see [Integrations → Target kind of a provider](integrations.md#target-kind-of-a-provider-outbound-guard).

**Definition hash** — `WorkflowDefinitionHasher` hashes the `WorkflowSnapshot` (blocks, instructions, model, provider, effort, metadata, defaultContext, sorted skill IDs) as canonical JSON (sorted keys, empty values omitted) with its own `ObjectMapper`, so Jackson customizing never changes the hash. Name, description, icon and tags are labels and are not hashed. Old version rows get their hash at startup (`WorkflowVersionHashBackfillService`). Context variables: `{{ workflow_definition_hash }}`, `{{ trigger_source }}`.

**Run admission** — Every run trigger calls `WorkflowExecutor.execute(…, TriggerSource)` and therefore passes `RunAdmission` before a run row exists. Rules in this order: (1) a sealed workflow must match its seal — definition and the content of its skills —, (2) with `require-sealed-for-unattended` an unattended run needs a sealed workflow. A refused run leaves **no run row and no DLQ entry**, only a `SECURITY` entry in the activity log; REST callers get the error code.

**Change provenance (always on)** — Every writer of definition fields goes through `WorkflowService` → `WorkflowMutationGateway`. Each change writes a version row with the state **after** the change (number = MAX+1) and a `changeSource` chosen by the calling server code, never by the request (`user`, `assistant`, `factory`, `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `goal-guard`, `import`, `bpmn-import`, `restore`). If the newest row's hash does not match the current state (legacy data, a change that bypassed the service), that state is first saved as `untracked`. Label-only changes create no version. `WorkflowWriterAllowlistTest` pins who else may call `WorkflowRepository.save`. The same place refuses unknown block types: `BlockTypeValidator` checks every block type — nested ones in `switch` `cases[].blocks` and `default` included — against the executor registry (`BlockTypeRegistry`, fed by all `BlockExecutor` beans) and throws **400 `INVALID_ARGUMENT`** naming the unknown type and the allowed ones. The check runs inside the caller's transaction, so the save is rolled back.

**Sealing** — `seal` records the current hash in `sealed_hash` (with `sealed_at` and a WARN `SECURITY` entry). Sealed workflows reject definition changes and deletion with **409 `WORKFLOW_SEALED`**; renaming still works. Repair (`POST …/repair`) is refused right away as well, automatic auto-repair skips sealed workflows before any AI call, and the UI disables "Optimize" and "Repair". If the definition no longer matches the seal at start (e.g. after a change made directly in the database) or cannot be verified, the run does not start: **409 `WORKFLOW_TAMPERED`**, ERROR `SECURITY` entry with expected and actual hash. Both checks apply regardless of the switch — `sealing` only gates setting and removing seals, so switching it off never silently unseals.

**Skills under the seal** — The definition hash only contains the skill **IDs**; what a skill brings into a run (name and `aiContext` in the prompt, config schema, per capability name, description, schemas, handler, `async`/`writes`) is not part of it. So `seal` additionally records a content hash for every skill the workflow uses — activated skills and the blocks' `skillIds` — (`SkillContentHasher`, canonical JSON as for the definition hash; labels such as description, icon, category and version do not count) and stores the mapping as JSON in `workflows.sealed_skill_hashes` (`WorkflowSkillPins`). `RunAdmission` compares it before every run: a changed, added or deleted skill yields **409 `WORKFLOW_TAMPERED`** naming the affected skill IDs; if the state cannot be verified, the run is refused as well. The definition hash itself is unchanged, so existing seals and the runs' version mapping stay valid. Seals from before V80 have `sealed_skill_hashes = NULL`: they keep running (with a log warning) but check no skills — sealing again adds the hashes, but only while the definition still matches its seal.

**Write path for skills (always on)** — Every skill writer goes through `SkillMutationGateway` (the counterpart of `WorkflowMutationGateway`): creation and editing (`user`, `PUT /api/v1/skills/{id}`; built-ins stay read-only, otherwise the next release would silently overwrite the edit), import (`import`) and built-in updates at startup (`builtin-seed`). Each change writes a row to `skill_versions` (operation `CREATE`/`UPDATE`/`DELETE`, canonical snapshot, content hash, server-side source; no foreign key, so the history survives deletion) and an activity-log entry — an import is a WARN `SECURITY` event. `GET /api/v1/skills/{id}/versions` lists the history, also for deleted skills. If a sealed workflow uses the skill, the gateway refuses changes and deletion by a person with **409 `SKILL_PINNED_BY_SEAL`**. Built-in updates from the resources cannot be refused; if they change the content under a sealed workflow, the gateway writes a WARN `SECURITY` entry naming the affected workflows, and their runs are refused until they are sealed again. `SkillWriterAllowlistTest` pins that only the gateway calls `skillRepository.save/delete`.

**Signature and history of a seal** — `sealed_hash` sits in the same SQLite file as the definition it protects; whoever writes that file changes both and passes. `WorkflowSealSigner` therefore signs every seal with HMAC-SHA256 over workflow id, definition hash and skill pins, using a key **outside** the database (`automation.seal.key-file`, env `AUTOMATION_SEAL_KEY`, otherwise a 0600 file created on first start; never `app_settings`). A signature that is present is always verified (`SIGNATURE_INVALID`); whether a seal **without** one may run is what `automation.hardening.require-signed-seal` decides (`SIGNATURE_MISSING`), so existing seals do not lose their runs overnight. Without a readable key the instance still starts and seals stay unsigned. `automation.hardening.seal-immutable` takes `POST …/unseal` out of the REST API (**403 `SEAL_IMMUTABLE`**): releasing a seal then needs the startup configuration, and a leaked API key (or an agent holding it) is no longer enough. Every sealing and unsealing is kept with its timestamp in `workflow_seal_events` (`GET /api/v1/workflows/{id}/seal-events`) — the activity log is pruned after 30 days and `sealed_at` disappears on unsealing. Entries are chained across all workflows (`prev_event_hash`); a row changed or deleted afterwards is reported as `chainIntact: false`. Refusals (of a run or of an unseal) write their SECURITY entry through `ActivityLogService.logRefusal` after the caller's transaction has ended, otherwise the rollback takes it along.

**Seals made visible** — `WorkflowSealVerifier` is the one check that `RunAdmission` runs before a start and `GET /api/v1/workflows/seal-status` runs without a run (`INTACT`, `VIOLATED` with reason `DEFINITION_CHANGED`/`SKILLS_CHANGED`/`UNVERIFIABLE` and the affected skills, `NOT_SEALED`). The response also carries the starts refused in the last 7 days from the activity log, because a refused start creates no run row. The endpoint is core, not part of the `sealing` module: a seal stays in force when the switch is off, so it must stay visible. `GET /api/v1/skills/seal-pins` maps every skill to the sealed workflows that use it. Every run records at start whether it ran sealed and verified (`workflow_runs.sealed`, `sealed_skill_pins`); this is never derived from the workflow's current state. The UI shows both states, not only the sealed one: a badge next to the name on the detail page and the list panel (lock "Sealed", open lock "Not sealed", red for a broken seal), a banner on the detail page, a labelled "Seal"/"Unseal" button, an "All / Sealed / Not sealed" filter with counts in the list, a filled lock or grey open lock in workflow rows, the run list, run detail and chain entries (runs from before the record show a dash "Seal state not recorded", never a value guessed from today's state), a lock on skill cards, and a "Starts refused" notice above the run list. The SECURITY entry of a refused start is written after the caller's transaction (webhook, schedule, chain) has ended, on its own virtual thread — otherwise the refusal would roll it back, and `REQUIRES_NEW` would block itself with pool size 1.

**Unattended runs** — `TriggerSource` classifies triggers:

| Attended | Unattended | Inherits from its starter |
|---|---|---|
| `MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT` | `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `COMPLETION_TRIGGER`, `AUTO_REPAIR`, `AUTO_RESUME`, `WISH` | `CHAIN`, `SUB_WORKFLOW` |

Chains and sub-workflows pass their starter's origin on in the reserved context key `__parent_trigger_source`: `ChainExecutor` sets it for every entry itself (inputs cannot preset it), `SubWorkflowBlockExecutor` hands down the parent run's effective origin, and webhook payloads may not contain `__` keys. A missing or unreadable key counts as unattended. Refusals answer **403 `UNATTENDED_REQUIRES_SEAL`**. With this switch, auto-repair and wishes are effectively off: they run unattended and would have to modify sealed workflows. `RESUME` means "a person resumed"; when the platform resumes by itself (opt-in `metadata.autoResume`), the run is `AUTO_RESUME` and, with the switch on, needs a seal — a refusal leaves it `INTERRUPTED`.

**Trust classes of context values** — Every context variable of a run carries one of three classes (`executor/VariableTrust`, bookkeeping in `executor/ExecutionContextTaint`). The class decides whether the value is substituted raw into a shell command line or wrapped in POSIX single quotes — and whether it *taints* the run, i.e. whether everything derived from it is escaped too.

| Class | Source | In shell blocks | In AI prompts | Taints the run |
|---|---|---|---|---|
| **trusted** | workflow defaults, system values (`project_path`, `workflow_run_id`, `workflow_definition_hash`), block-internal values, answers from `approval`/`human-input`, any `setVariable` | substituted raw | raw | no |
| **untrusted external** | webhook payload, restored `__untrusted_keys` (resume, sub-workflow), variables derived from tainted output (`step_N_output`, foreach items, `document` extracts) | always single-quoted | raw | yes, from the moment it is set |
| **platform data** | `previous_report`, `open_todos` — injected by `WorkflowContextDataService` into **every** run | always single-quoted | raw | on two paths: (1) once the placeholder was really substituted into a shell command line; (2) once the output of an AI block whose prompt contained the placeholder is recorded — from that coloured output on, the run is stickily tainted |

- **Where platform data comes from:** each platform variable has its own `PlatformVariableSource` bean — `PreviousReportVariable`, `OpenTodosVariable`, `RequestMetricsVariable`, `GoalVariables`, `AuditFindingsVariable`, in this `@Order`. `WorkflowContextDataService` only collects them and gives each its own error path: if a source throws, its variables show the `NICHT GEMESSEN` gap and the others stay untouched (TODO 441a4c46). A new variable is a new bean; it still belongs in `ContextDeltaCalculator.SYSTEM_VAR_KEYS`, otherwise it ends up in every step's context delta. Every source names all the variables it can set in `allNames()`; these names belong to the platform (TODO 2a0695ff): `ExecutionContextFactory` takes none of them from an input context, and a sub-workflow is not handed the parent run's — otherwise they would sit in the child's stored `input_context` and, wherever the child's own source does not apply (`goal_*` without `goal_id`, `audit_findings` without `metadata.audit`), arrive as a trusted variable. Conversely the collector sets only what a source announced for the run (`names()`); any other name from `load()` is dropped and reported in the activity log.
- **Taint propagation:** in a tainted run `recordOutput` escapes every further block output (`previous_output`, `{{ block.<name>.output }}`) and `setDerivedVariable` marks every variable derived from it as untrusted. The taint turns sticky as soon as one output has been recorded or platform data has reached a command line — from then on even a later `setVariable` on the variable that caused it no longer clears it, because a block can pull foreign bytes into its output without naming any placeholder (reading a file, calling an API). Before that point — external input with no recorded output yet — a `setVariable` on exactly that variable still clears the taint. It is persisted in the three reserved context keys `__untrusted_keys`, `__untrusted_outputs` and `__platform_in_shell` (the last one the platform-data usage flag, `true` once it is raised) so it survives resume and sub-workflow hand-over — even when no output has been recorded since the substitution. A clean run exports nothing and stays byte-identical to the behaviour from before taint tracking.
- **Why platform data only taints on use:** it is present in every run. Tainting at injection time would taint every run and change the meaning of the output placeholders in the bundled templates. `TemplateResolver.resolveShell`/`resolveShellSafe` therefore report back which placeholders were really substituted into a command line; only that report arms the taint (`ExecutionContextTaint.substitutedIntoShell`). A run that does not use `{{ previous_report }}` at all stays untainted.
- **AI outputs after a platform-data prompt are coloured:** if platform data was in an AI block's prompt, the output of exactly that block counts as untrusted — a following shell block therefore receives `{{ previous_output }}`/`{{ block.<name>.output }}` single-quoted. Built on the same feedback channel: `TemplateResolver.resolvePrompt` reports the placeholders it resolved, `ExecutionContextTaint.substitutedIntoPrompt` remembers platform data, and the next output recorded via `recordOutput` consumes that flag. The **prompt alone** does not colour the run: as long as no output was recorded, `hasUntrustedInput()` is false and `exportTaint()` is empty. Once the output is recorded the run is tainted, though — sticky as in every other case: from then on `recordOutput` escapes **every** further block output, `setDerivedVariable` marks every variable derived from it (`step_N_output`, foreach items, `document` extracts) untrusted, and `exportTaint()` passes both on to sub-workflows and to a resume. So a later AI block with a clean prompt does not get a clean output back — that only holds while nothing coloured has been recorded before it. The prompt path covers exactly the AI calls: `simple`, `skill-capability`, `agentic` (`goal`/`success_criteria`) and the non-shell branches of `foreach`, `while`, `conditional`, `retry` and `switch`; non-prompt resolutions (mounts, document paths, TODO fields, integration parameters, the `switch` expression) stay on `resolveTemplate` and keep reporting nothing.
- **Deliberately trusted:** answers from `approval` and `human-input` blocks arrive through the API-key-protected REST interface from the person who owns the instance — they are not escaped. Likewise `setVariable` (e.g. a workflow variable shadowing a webhook field of the same name) clears the untrusted mark on purpose: the value then comes from the definition, not from the caller. `replaceValue` (workspace path remapping) in contrast only swaps the value and leaves the class in place.
- **Known limits:**
  1. Nothing is escaped in AI prompts — a prompt is not a command line, and the trust model does not defend against prompt injection.
  2. Prompt injection through the AI's answer itself remains possible: what gets quoted is the output, its *content* still comes from a model that read platform data. Quoting prevents shell injection, not manipulation of the content.
  3. A shell block that writes a tainted value into a file itself and later executes that file is outside what quoting can see.

  The path platform data → AI prompt → shell block was the fourth limit until TODO 22e6bec8; colouring the AI output closed it. **The recommendation still stands: set `"shell_escape_variables": true` in every shell block that consumes an AI block's output** — the block then behaves the same in coloured and in clean runs. For the bundled definitions `PlatformPromptQuotingGateTest` enforces it.

**Webhook signatures** — `POST /api/v1/webhooks/{id}/secret` generates a secret (32 random bytes, hex), shows it exactly once and stores it AES-GCM-encrypted (`IntegrationEncryptionService`, key outside the database) in `webhook_triggers.signing_secret_enc`; `DELETE …/secret` removes it. For a webhook with a secret, `WebhookSignatureVerifier` checks `X-Signature: sha256=<hex>` (or `X-Hub-Signature-256`, the GitHub/Gitea format) as HMAC-SHA256 over the raw request bytes before the JSON is parsed, compared in constant time — otherwise **401 `WEBHOOK_SIGNATURE_INVALID`**. As with seals, a stored secret stays in force if the switch is later turned off. Responses only reveal `signatureRequired`.

## Backend Architecture

### Layer Model

```
Controller (REST API, 53 Controllers)
    ↓ DTO (Request/Response Records)
Service (Business Logic)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (File: data/automation.db)
```

### Database Maintenance (SQLite, pool-size=1)

- **Run retention** (`RunCleanupService.cleanupTerminalRuns`, daily 03:15): deletes finished runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) including their `step_results` once they are older than `run-retention-days` — oldest first, at most `run-retention-max-per-night` per pass, each run in its own short transaction via `RunDeletionService` (which also clears DLQ, TODO and wish references). Runs referenced by an audit report are kept (query exclusion plus a guard in the service). Runtime settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = off, default 90), `run-retention-max-per-night` [10,5000] (default 200). INTERRUPTED runs keep their older rule (`cleanupInterruptedRuns`, 03:00).
- **Run deletion via the API can be switched off** (`RunDeletionPolicy`): `automation.executions.allow-delete: false` (startup configuration, default `true`) makes `DELETE /api/v1/executions/{id}` fail with `403 RUN_DELETION_DISABLED` and hides the delete buttons (`runDeletionAllowed` in `/system/capabilities`). Deliberately without a runtime switch. The two cleanup jobs above are operations and keep running; `run-retention-days` stays a runtime setting, but with deletion switched off it can only be **extended** at runtime (`0` = off counts as the longest); a shorter retention fails with `403 RUN_DELETION_DISABLED`, so a leaked key cannot have the runs deleted overnight.
- **Backup** (`DatabaseBackupService`, daily 02:00, `automation.backup.retain-count`): deliberately runs before retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` is the **only** statement in the Hikari init SQL — sqlite-jdbc runs just the first statement of a multi-statement string (`foreign_keys`/`busy_timeout` therefore live in the JDBC URL, guard: `DataSourceConfigGuardTest`). The pragma only takes effect on an empty database or after a full `VACUUM`. The startup check `checkAutoVacuumMode` writes an ActivityLog WARNING while the file is still at NONE — run `POST /api/v1/system/vacuum` once, after which the nightly `incremental_vacuum` (03:00) reclaims space. Direct `sqlite3` access stays off-limits (Hibernate cache, single writer).

### Packages

```
com.automation
├── controller/          53 REST Controllers
├── service/
│   ├── project/         ProjectService, ProjectStatusReconciler, ContainerService (+ DockerContainerGateway, DockerOrphanReconciler), ProjectContextService, PathValidator, OnboardingProfileService (+ OnboardingProfileSeeder), ProjectOnboardingService
│   ├── workflow/        WorkflowExecutor (+ ExecutionContextFactory, BlockSkillContext, BlockStepRunner, RunCompletionHandler, RunFinalizer, WorkflowContextDataService + PlatformVariableSource beans), RunControlService, WorkflowService (+ WorkflowRequestFields), ExecutionBroadcaster, TemplateVerificationService, WorkflowOptimizationService (+ WorkflowOptimizationPrompt), GhostRunWatchdogService, RunCleanupService, AgentInstructionsService, WorkflowTemplateService, BlockOutputValidator, WorkflowExecutionRegistry, IdleContainerReaper, WorkflowTaggingService, WorkflowVersionService, WorkflowContentChangedEvent
│   ├── skill/           SkillService, SkillImportExportService, SkillRegistry, CapabilityRegistry
│   ├── scheduler/       SchedulerService (+ SchedulerTick, SchedulerMaintenanceMode, UpcomingRunsCalculator, ScheduleSkipPolicy, SchedulerPause, ScheduleDefinitionParser, CatchUpQueue), ScheduledWorkflowExecutor, SuspendState
│   ├── webhook/         WebhookService, GitWebhookPayloadParser
│   ├── learning/        LearningService, AgenticLearningService, ExecutionInsightsService, WorkflowStatsService, WorkflowSimilarityService, BlockTypesBackfillService, MetadataExtractor, PlatformStatsService
│   ├── system/          ApiKeyService, DatabaseBackupService, ExecutionSettingsService (+ ExecutionSettingKeys; setters per topic in `settings/`: ExecutionCapacitySettings, AccessLimitSettings, HousekeepingSettings, FeatureSettings, RequestMetricsSettings), SystemIdentityService, TailscaleService, FileBrowserService, IntegrationSnippetService, SystemSettingsService (+ AppSettingChangedEvent: services with a cached setting re-read it on this event instead of being called directly)
│   ├── ai/              HostClaudeService, HostCodexService, HostPiService, AiProviderRegistry, AiProvider, AiProviderCapabilities, AiResponseMetadata, ClaudeAiProvider, CodexAiProvider, PiAiProvider, PiCatalogService
│   ├── cli/             CommandExecutorService (+ HostRequestAiMetadata), CliSessionManager, CliSession, HostExecutionRequest, ShellCommandService, ContainerShellService, TaskCommandService
│   │   └── claude/      ClaudeOutputParser, ClaudeJsonResponse, ClaudeStreamEventFormatter — read the Claude CLI's output (a leaf package for ai/ and cli/, so ai/ does not point back to cli/)
│   ├── chain/           ChainService, ChainExecutor (+ ChainRunWaiter, ChainZombieRunCleaner), ChainTemplateService, BuiltInChainTemplate
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer, ConversationPreprocessor, SummarizationSetupService, AutoMemoryExtractionListener
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper (+ InstanceHttpClient, SyncPreviewService), DockerContainerManager
│   ├── todo/            TodoService (+ TodoSpecifications, TodoTrackerExportService, TodoResolutionService, TodoResponseAssembler), AutoTodoService (+ AutoTodoTextBuilder, PendingTodoApprovalService, GranularTodoService), GateStatusResolver, ProjectPathResolver, TodoHygieneService, TodoOverviewService, TodoResolutionListener, TodoSimilarityService
│   ├── runoutput/       GateStatusOutputParser, GateExpectation, TodoOutputParser — read markers from block outputs (a leaf package for workflow/ and executor/, so workflow/ does not point back to todo/)
│   ├── conversion/      WorkflowConversionService (+ WorkflowConversionPrompt), WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService (+ DialogConversionPrompt), ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService (+ FactoryPromptBuilder, FactoryResponseParser, FactoryTopicCatalog, FactoryGeneratorResolver), FactoryVerificationService, ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService (+ WishFulfillmentLoop, WishEvaluationService), WishWatchdogService, WishDecomposeService, WishDecomposePromptBuilder (+ WishSystemPrompt), WishExecutionService, WishEvaluatePromptBuilder, WishPromptBuilder, WishPromptHelper, WishJsonUtil, WishStatusHelper, WishStringUtil
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── audit/           AuditResultParser, AuditIngestionService (+ AuditSnapshotApplier), AuditIngestionListener, AuditCenterService (+ AuditFindingTodoLinker)
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationProvider, OAuthConfig, TaskExportCapable
│   ├── integration/providers/ 18 Providers in 17 subdirectories — google/ holds two, Calendar and Gmail (caldav/, confluence/, feed/, gitea/, github/, gitlab/, google/, httpapi/, icalfeed/, jira/, ntfy/, openmeteo/, slack/, taskwarrior/, telegram/, todoist/, trello/; shared: ical/)
│   ├── assistant/       AssistantContextBuilder (+ PageContextRenderer, ProjectFileListing), AssistantChatCaller, AssistantChatBroadcaster, AssistantFetchService, AssistantToolExecutor (+ AssistantTodoTools, AssistantWorkflowTools, AssistantChainTools, AssistantToolPayloads), AssistantActionSuggestionService, AssistantErrorClassifier, AssistantFetchPolicy, AssistantMutationGate, AssistantActionMerger, AssistantResponseParser, PendingToolRegistry, ToolExecutionResult
│   ├── assistant/context/ AssistantContextSection, BlockTypesIndexSection, ChainsIndexSection, FailedWorkflowsContextSection, IntegrationsIndexSection, RunsContextSection, SchedulesContextSection, SkillsIndexSection, TodosContextSection, WishesContextSection
│   ├── assistant/format/  AssistantFormatUtils (formatting helpers for prompt parts; a leaf package of its own, so context/ and fetch/ do not point back to assistant/)
│   ├── execution/       ExecutionQueryService (reads + DTO mapping), ExecutionCommandService (retry, resume, notes), WorkflowDebugService
│   ├── dlq/             DeadLetterService, DeadLetterRetryService
│   ├── document/        DocumentExtractionService, DocumentExtractionException
│   ├── export/bpmn/     BpmnModelBuilder (chain/single task/loop) + BpmnNodeFactory (ids, placement, edges, command tasks), BpmnGatewayBuilder (conditional/switch: split/rows/merge), BpmnEdgeRouter (right-angle waypoint routing), DataFlowReferenceScanner (placeholder scan for `block.x.output`/`previous_output`/`step_N_output`/compress `source`) + DataFlowConfigStrings (level rule: direct vs. nested config values) + BpmnBranchBlocks (blocks and id paths of the switch branches) + BpmnDataFlowBuilder (data objects and associations below the task row, the endpoint is always a task — for references from a branch the branch task, never the gateway; the import skips them), BpmnBlockText (labels, docs, config helpers), BpmnSegment (sub-graph), BpmnXmlRenderer, BpmnExportAdapter, BpmnModel, BpmnNames
│   ├── search/          SemanticWorkflowSearchService
│   ├── watchdog/        ConnectionPoolWatchdogService
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat assistant (orchestration; CLI call in assistant/AssistantChatCaller)
│   ├── AssistantSessionService.java Session persistence
│   ├── AssistantSettingsListener.java keeps the assistant's cached settings current (only while the module is on)
│   └── PageSuggestionService.java   Context-sensitive UI suggestions
├── entity/              JPA Entities + Enums
├── dto/                 assistant DTOs directly in the package (AssistantChatRequest/-Response, PendingTool, …)
│   ├── request/         Request Records
│   └── response/        Response Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct Mappers (Entity ↔ DTO)
├── executor/            Workflow Execution Engine
│   └── blocks/          20 Block Executors + Agentic helper classes (AgenticBlockConfig, AgenticPlan, AgenticSafetyGuard, AgenticTokenTracker, AgenticPromptBuilder, AgenticEventBroadcaster, AgenticApprovalGate, AgenticActionRunner, AgenticStopRules)
├── concurrency/         DynamicSemaphore
├── config/              Configuration classes, Seeders (Skill, Template)
├── security/            ApiKeyAuthFilter, RateLimitFilter (+ RateLimitConfig), ActivityLogSizeLimitFilter, WebhookRateLimiter, WebSocketAuthInterceptor, WebSocketTicketService
├── skills/handlers/     CapabilityHandler, LintHandler, SecurityScanHandler, FactCheckHandler, GrammarCheckHandler, OCRHandler
├── exception/           GlobalExceptionHandler, ErrorCode + Exception classes
├── websocket/           LogWebSocketHandler, TerminalWebSocketHandler, TerminalSession (+ TerminalStdinStream), Message DTOs
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent, TodoCreatedEvent, ProjectWorkflowsChangedEvent
└── util/                JPA Converters (JSON, BlockDefinitions), RuntimeClasspathGuard (refuses a start from `target/classes`)
```

### All 53 REST Controllers

| Controller | Base Path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, archiving, container lifecycle, context generation |
| `ProjectBundleController` | `/api/v1/projects` | Project bundle: `/{id}/export` (file download), `/import/preview`, `/import` — see "Project bundle" (core; chains inside only with module `chains`) |
| `WorkflowController` | `/api/v1/workflows` | CRUD, execution, runs, export/import, skills |
| `WorkflowVersionController` | `/api/v1/workflows` | `/{id}/versions` — version list, single version, restore |
| `WorkflowAssistController` | `/api/v1/workflows` | `/{id}/optimize`, `/{id}/similar`, `/{id}/repair` (repair only with module `factory`) |
| `WorkflowAnalyzerController` | `/api/v1/workflows` | `/{id}/analyze` and `/analyze` — deterministic build-error check of one definition or the whole stock. Reports only, touches no write path (182bd224); AI-generated definitions are additionally checked by `GeneratedWorkflowCheck` (see Workflow Factory) |
| `WorkflowImportController` | `/api/v1/workflows/import` | BPMN import (`POST /bpmn?projectId=…`, body = BPMN XML) → new workflow (201); foreign BPMN/broken XML/too large → 400 |
| `ExecutionController` | `/api/v1/executions` | List, detail, pause, resume, resume-from-step, cancel, retry, approve, approval-check, todos-approve, notes, command |
| `SkillController` | `/api/v1/skills` | CRUD, capabilities, export/import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, enable/disable, runs, calendar, preview, remaining-stats, chain-schedules, maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, enable/disable; trigger via `/api/v1/webhook/trigger/{token}` (public, token-only — payload fields are flagged as `__untrusted_keys` and always escaped in shell blocks; block outputs of such runs inherit the taint via `__untrusted_outputs`, including sub-workflows and resume, see `ExecutionContext.recordOutput`/`resolveShellTemplate`) |
| `ChainController` | `/api/v1/chains` | CRUD, execution, runs, cancel |
| `ChainTemplateController` | `/api/v1/chain-templates` | List, get, install as chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, create from workflow |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, extract, activate |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, run, verify, promote, topics |
| `WorkflowPoolController` | `/api/v1/pool` | List, get, install, export |
| `ConversionController` | `/api/v1/conversions` | Suggest, create workflow from run |
| `DialogController` | `/api/v1/dialog` | Sessions, projects, Claude session analysis, workflow creation |
| `LearningController` | `/api/v1/learning` | Dashboard, stats, step-analytics, suggestions, similar, agentic-history, agentic-analytics, tool-patterns, execution-insights, run-insights, rate |
| `TodoController` | `/api/v1/todos` | CRUD, status-patch, resolve, export, count, overview (cockpit), bulk-status, archive-stale, similar |
| `GoalController` | `/api/v1/goals` | Goals: CRUD, assign and order TODOs, relation map, candidates, projects, work packages, milestones, guard |
| `GoalWorkflowController` | `/api/v1/goals/{id}/workflows` | Workflows on a goal or work package: list (with the guard entry), attach, role, detach |
| `ActivityLogController` | `/api/v1/activity-log` | List, create |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`), sessions, pending-tool confirmation/rejection |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per page (get/refresh) |
| `DeadLetterController` | `/api/v1/dlq` | Dead letter queue: list, retry, delete |
| `SearchController` | `/api/v1/search` | Aggregated search across workflows (substring/fuzzy + semantic ranking) |
| `WorkflowDebugController` | `/api/v1/debug` | Debug page: workflows with failed last run |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, enable/disable, filter by workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, enable/disable, filter by target workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, enable/disable |
| `WishController` | `/api/v1/wishes` | CRUD, cancel, respond, crystallize |
| `StagingController` | `/api/v1/staging` | Status, start, stop, promote, promote-cancel, sync-preview, sync-definitions |
| `TestingController` | `/api/v1/testing` | Status, start, stop, reset, sync-preview, sync-definitions, report-result, pull-changes |
| `SystemController` | `/api/v1/system` | Health, auth-status, info, jvm-memory, settings, execution-config, AI-providers, AI-provider-catalog, capabilities, backup, backups, remote-access, vacuum, browse, open-path, repair, client-errors, integration-snippet |
| `IntegrationController` | `/api/v1/integrations` | OAuth flow, credentials, actions, provider list, task export targets |
| `OnboardingProfileController` | `/api/v1/onboarding-profiles` | Onboarding profiles CRUD, reset, history |
| `ReportsController` | `/api/v1/reports` | Report generation and retrieval |
| `AuditController` | `/api/v1/audits`, `/api/v1/audit-*` | Audit overview, report history, findings and TODO links |
| `DocsController` | `/api/v1/docs` | Read Markdown documentation |
| `SystemSettingsController` | `/api/v1/system` | Runtime settings: app settings (`GET`/`PUT /settings/{key}`), execution config |
| `SystemAiProviderController` | `/api/v1/system` | AI provider and capability information: provider list, capabilities, Pi catalog, models per provider (+ refresh) |
| `SystemModulesController` | `/api/v1/system/modules` | Runtime module switches for the settings UI (only when `automation.modules.ui-editable=true`; hardening switches never switchable) |
| `SystemAccessController` | `/api/v1/system` | Access surface: remote access (Tailscale) enable/disable/status, local filesystem (browse, open-path, integration snippet) |
| `SystemMaintenanceController` | `/api/v1/system` | Maintenance: auto-repair, VACUUM, database backups (list, create, download) |
| `DashboardLayoutController` | `/api/v1/dashboard/layouts` | Version history of the dashboard layout (core feature, no module gate) |
| `DashboardWorkflowController` | `/api/v1/dashboard/workflow-results` | Returns the results of all workflow widgets of a view in **one** request (marker `dashboard_widget`), so the dashboard does not load per widget |
| `DashboardReportAssistController` | `/api/v1/dashboard/report-assist` | Proposes a `dashboard-report` configuration from an existing run via AI, for the editor (module `dashboard-report`; `POST`, because the call costs tokens and no browser may prefetch it) |
| `SystemRequestMetricsController` | `/api/v1/system/request-metrics` | Measured cost per route: the running snapshot (`GET`) and daily values (`GET /daily`, optionally per route) |
| `WorkflowSealController` | `/api/v1/workflows/{id}` | Seal/unseal a workflow (only when `automation.hardening.sealing=true`; existing seals stay effective even if the switch is turned off) |
| `WorkflowSealStatusController` | `/api/v1/workflows/seal-status` | Seal state of every sealed workflow and starts refused in the last 7 days (read-only, core — also with `sealing` off) |
| `WebSocketTicketController` | `/api/v1/ws-ticket` | Issues short-lived, single-use tickets for the WebSocket handshake (no module gate, ordinary API rate limit applies) |

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

**Controllable from step 1:** before submitting the run, `WorkflowExecutor` registers a provisional `ExecutionContext` in the `WorkflowExecutionRegistry`. A cancel therefore takes effect while the run is still waiting for a semaphore slot or for its container to start — the execution thread carries the cancel/pause flag over when it swaps in the real context. As a result `getActiveRunCount()` also counts queued runs, which stops `GhostRunWatchdogService` from releasing the permit of a starting run as a leak. Pause, resume, cancel, approvals and ad-hoc commands go through `RunControlService` (split out of `WorkflowExecutor`): it works on the registry only and starts nothing itself — new runs and `resume-from-step` stay in `WorkflowExecutor` and therefore behind `RunAdmission`.

**Resume cuts by blocks, not by step index** (`ResumePlan`): `POST /executions/{id}/resume-from-step` picks up after the last COMPLETED step. What counts is that step's **position** in the block list, not its `stepIndex` — wrapper blocks (foreach, while, retry, conditional, switch) draw one more index per item or iteration, so a block's index runs ahead of its position. This rests on the guarantee `BlockStepRunner` gives: exactly one persisted step per top-level block, in block order (inner items are never persisted). The resumed segment's steps continue above every index already persisted (`ExecutionContext.startStepIndicesAt`), so indices stay unique and sortable. Previously the freshly allocated index was compared against the persisted one — after a wrapper block, the resume skipped blocks that had never run (TODO `1be423b4`).

**Automatic resume (opt-in, TODO 90d10ba1)** — `RunControlService.cleanupZombieRuns` (startup) and the standby branch of `GhostRunWatchdogService` record in the run metadata what the run was doing (`interruptedFrom`: `RUNNING`/`PAUSED`) and what interrupted it (`interruptedBy`: `RESTART`/`STANDBY`), and then publish `RunInterruption.Event` with the marked runs — the order follows from that call, not from listener order. `RunAutoResumer` therefore only sees runs of the current JVM lifetime and resumes those that are `INTERRUPTED` and were `RUNNING`, are neither a chain entry nor a sub-workflow (source `CHAIN`/`SUB_WORKFLOW`, `chain_run_id` or `__parent_trigger_source` in the context), whose workflow exists and has `metadata.autoResume.enabled`, and that had no attempt yet. The counter `autoResumeAttempts` is saved in a short transaction of its own **before** the start, then `WorkflowExecutor.resumeFromStep(runId, TriggerSource.AUTO_RESUME)` runs through `RunAdmission`. Runs start one after another on a thread of their own, with `automation.execution.auto-resume.delay-seconds` before each (runtime setting `run-auto-resume-delay-seconds`, switch `run-auto-resume-enabled`); neither startup nor the watchdog tick waits for them. Every resume, refusal and failure is in the activity log (source `RunAutoResumer`). Chains (`ChainZombieRunCleaner`) are left out. Against the schedule (TODO 070ccc3d): during the wait the interrupted run is `INTERRUPTED`, the SKIP check (`RUNNING`/`PAUSED`) did not see it, and a catch-up run started next to it. `ScheduleConflictCheck` therefore asks `PendingAutoResumes.forWorkflow` before the status check: a suitable run of the workflow interrupted in this lifetime that `RunAutoResumer` has not handled yet, or the attempt under way. It reads the saved state, not the queue — a run counts from its marking, even before the event arrives; runs passed over (not eligible, switch off, refused) block nothing. The order "pending first, then `RUNNING`" leaves no gap, because the resumed run is `RUNNING` before its attempt counts as done.

**Block position per step** (`metadata.blockIndex`): `BlockStepRunner.startStep` writes the block's position in the top-level list into the step metadata, `runStep` keeps it over executor metadata. The run page's flow diagram maps steps to its boxes (`b_<position>`) through it (`latestStepByBlock` in `stepMetadata.ts`); runs without the key fall back to the position in the index-ordered list. After a resume the interrupted attempt of a block sits next to the new one — both with the same `blockIndex`, the higher `stepIndex` is the current one (TODO `dcba60da`).

**Container start/stop outside transactions:** `ProjectService.startContainer`/`stopContainer` are deliberately **not** `@Transactional`: the Docker call sits between two short `TransactionTemplate` bursts (STARTING → Docker outside any transaction → RUNNING/ERROR), because `ContainerService` holds the per-project monitor while it runs — an enclosing transaction would pin the single connection for the whole 5–15 s Docker start and push a concurrent workflow run (which takes the monitor first) into the 120 s connection timeout (lock-order inversion between monitor and connection). Because the STARTING burst commits before the Docker start, `ProjectStatusReconciler` picks up every project a crash in that window left in STARTING on `ApplicationReadyEvent` (RUNNING container row ⇒ RUNNING, otherwise STOPPED) — the project-level counterpart of `RunControlService.cleanupZombieRuns`. `ProjectService.delete` follows the same pattern in reverse order: the archive burst (`archivedAt` + activity log) commits first, the Docker stop runs outside any transaction afterwards — also when only the container row is RUNNING, because a workflow run starts the container itself without setting the project RUNNING — if it fails the project stays archived (ERROR log) and only the orphaned container is left for `DockerOrphanReconciler`/idle cleanup to reap.

The same pattern applies to `WorkflowMemoryService.extractKnowledge`/`extractAndActivate` (security finding 4, same shape as `ebd508c5`): the AI summarization call (`ClaudeSummarizer`/`CodexSummarizer`, seconds to minutes) runs outside any transaction, between a read `TransactionTemplate` burst (load runs, materialize the lazy `steps` collection into a new list — OSIV is off —, collect run ids) and a write burst (save the `WorkflowMemory`, or `deactivateAllForWorkflow` + activation). `activate`/`update`/`delete` stay short `@Transactional` methods.

**Disconnecting an integration outside transactions:** `IntegrationOAuthService.disconnect`/`deleteCredentials` follow the same burst shape: a short `TransactionTemplate` burst (clear the token fields, or delete the row) → token revocation at the provider outside any transaction. That HTTP call has a 10 s timeout and inside a `@Transactional` method would hold the single connection for all of it; "persist first" additionally rules out the state "revoked at the provider but still `CONNECTED` locally".

**Container row:** `ContainerService.createAndStartContainer` updates an existing `containers` row (upsert) instead of deleting and re-inserting it — the delete was un-scheduled during flush by the `Project.container` cascade, so the following INSERT failed on `UNIQUE(containers.project_id)`. The per-project lock now stays in the map (removing it under contention creates a second monitor), and `lastActivityAt` is stamped by a JPQL update (`ContainerRepository.touchActivity`) rather than a detached merge.

### Block Types

Complete list; the authoritative source is the registry `BlockTypesIndexSection.BLOCK_TYPES`, against which `BlockTypeListsGuardTest` pins the existing executor classes.

| Type | Executor | Description |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Single Claude CLI call with prompt |
| `shell` | `ShellBlockExecutor` | Shell script, optionally via Docker container |
| `retry` | `RetryBlockExecutor` | Prompt with configurable retry attempts |
| `conditional` | `ConditionalBlockExecutor` | If/else branching based on condition |
| `foreach` | `ForEachBlockExecutor` | Iteration over a list of elements |
| `while` | `WhileBlockExecutor` | Repetition with exit condition and max iterations |
| `switch` | `SwitchBlockExecutor` | Multi-way branching per expression with cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe loop, up to 10 iterations |
| `approval` | `ApprovalBlockExecutor` | Pauses execution, waits for user confirmation |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Executes another workflow as a subprocess |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Invokes a named skill capability |
| `human-input` | `HumanInputBlockExecutor` | Collects user input via WebSocket, stores as context variable |
| `integration` | `IntegrationBlockExecutor` | Executes an action on an external integration provider |
| `document` | `DocumentBlockExecutor` | Extracts text from PDF/DOCX/XLSX/HTML/TXT via Apache Tika |
| `compress` | `CompressBlockExecutor` | Rule-based compression of an earlier block output (stage chain, 0 tokens) |
| `compress-ai` | `CompressAiBlockExecutor` | Tool-less AI compression of an earlier block output with input/output limits |
| `todo-create` | `TodoCreateBlockExecutor` | Creates a todo, directly via the service rather than the REST API |
| `todo-update` | `TodoUpdateBlockExecutor` | Updates an existing todo |
| `todo-close` | `TodoCloseBlockExecutor` | Sets a todo to DONE |
| `dashboard-report` | `DashboardReportBlockExecutor` | Writes a run's counts and status as a dashboard widget |

**Template variables** (`{{ variable }}`, `${varName}`) are resolved at runtime in the `ExecutionContext`. `{{ previous_output }}` contains the output of the previous block.

### Agentic Block (Goal→Reason→Act→Observe)

The agentic block implements an autonomous execution loop:

1. **REASON:** Claude analyzes the goal and previous observations → returns structured JSON action
2. **ACT:** Action is executed (shell or Claude CLI)
3. **OBSERVE:** Output is collected and added to the history
4. Loop runs until `done: true` or abort conditions are met

**Safety Mechanisms:**

| Config | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Maximum number of iterations |
| `iteration_timeout_seconds` | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | 1800 | Total block timeout (30 min.) |
| `max_total_tokens` | 200000 | Token budget (input+output, all phases) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Approval gate before each action |

Additionally: history compression after 7 turns, action compression at >100,000 characters, token tracking warnings in the log. All 6 safety fields are pre-populated as defaults in the frontend "Add Block" dialog. Helper classes: `AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker`, `AgenticPlan`.

### Switch Block

The switch block evaluates an expression against multiple cases:
- Supports `${varName}` and `{{ varName }}` for variable interpolation
- Cases are checked in order — first match wins
- Pipe-separated alternatives: `"error|critical"` matches both
- `default` case when no other case matches
- Each case contains a list of nested blocks (shell or claude)
- Returns `COMPLETED` with `matchedCase: "none"` if no match and no default

### Sub-Workflow Block

- Executes another workflow as a nested block
- Circular references are detected via `__parent_workflow_ids`
- Maximum nesting depth: 5 levels
- Polling-based completion, 30-minute timeout
- `@Lazy` injection on `WorkflowExecutor` only where a caller starts runs itself (sub-workflow, chains, triggers); pure status queries (active runs, container ids, approvals) go straight to `WorkflowExecutionRegistry`, controlling a running run goes to `RunControlService`, which does not need `WorkflowExecutor`

### Human-Input Block

- Pauses workflow execution and waits for user input
- Sends `human_input_required` event via WebSocket to `/topic/executions/{runId}/logs`
- User response is stored as a context variable (`variable_name`)
- Supports text and select inputs, validation patterns, timeout with optional default
- Uses the `ApprovalRequest` mechanism of the approval block

## Gate Status System

A quality gate is the verdict a checking workflow (audit, test, review) passes on its result:
GREEN = fine, YELLOW = non-blocking findings, RED = critical. The platform evaluates it: RED (in
mode `auto` also YELLOW) creates a TODO when auto-TODO is on for the workflow (off by default), and
after a repair run a GREEN closes the TODO that triggered it
(`TodoFeedbackListener`, see [Todo Triggers](#todo-triggers--self-improvement-loop-v51v52)).
To do so, workflow blocks write markers into their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of Markdown formatting)
- `RunCompletionHandler` scans all block outputs — highest severity wins (GREEN < YELLOW < RED); on a tie RED/YELLOW keep the first gate (the cause, the auto-TODO's category), GREEN the last (the closing verdict; a synthetic `Command exit N` or a GREEN without description does not replace a description, and without a category of its own the current one stays, TODO 2aa9ddd5)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**A gate does not change the run status.** Neither YELLOW nor RED: the gate is a finding about
the result, not a failure of the execution. RED and YELLOW write a warning to the activity log
(`RunCompletionHandler`). When auto-TODO is on for the workflow (workflow metadata
`autoTodo.enabled`, otherwise the setting `auto-todo-default`, off by default), `AutoTodoService`
creates a TODO with priority HIGH on RED and, in mode `auto`, one with MEDIUM on YELLOW — the run
stays `COMPLETED`.

The run status is decided by something independent: `RunCompletionHandler` sets
`COMPLETED_WITH_ERRORS` when a step carrying `checkedCommand` metadata has failed, and
`COMPLETED` otherwise. Both can coincide — `RunCompletionHandlerTest` pins exactly that case: a
failed `checked_command` yields `COMPLETED_WITH_ERRORS` **and** `gate_status: RED`.

The direction that does exist is the reverse one: `GateStatusResolver` derives a gate verdict
from the run status when no marker was written (`FAILED` → RED, `COMPLETED` → GREEN,
`COMPLETED_WITH_ERRORS` → YELLOW).

A shell block does not have to write these markers itself: `checked_command`/`check_category`
derive the gate automatically from the exit code, giving precedence to the script's own marker when
it is at least as severe — details and examples:
[WORKFLOW-GUIDE.md → `checked_command` / `check_category`](WORKFLOW-GUIDE.md#checked_command--check_category--gate-derived-automatically-from-the-exit-code).

**AutoTodoService:**
- Reacts to `WorkflowRunCompletedEvent`
- RED gate → TODO with `HIGH` priority
- YELLOW gate → TODO with `MEDIUM` priority
- Source: `WORKFLOW`, linked to the run

## Workflow Chains

Chains group multiple workflows for sequential execution:

- **Cross-project:** A chain belongs to no project and can reference workflows from different projects
- **Sequential:** `ChainExecutor` executes entries in `sortOrder` order (virtual threads, polling every 2s, 180-min. timeout)
- **Context propagation:** Each entry receives chain-level context + entry-level context + `previous_chain_output` from the last completed workflow
- **Stop-on-Failure:** Flag at chain and entry level — chain stops only when both are `true`
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status broadcasts to `/topic/chains/{chainRunId}/status`

### Chain Templates

Pre-built chain definitions composed from existing workflow templates. Every entry has to name a shipped built-in template (`ChainTemplateServiceTest`); there are no chains over pool entries:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: Creates a new chain (optionally with associated workflows) in a target project

## Scheduler: missed occurrences (standby and cold start)

The scheduler is a 60-s tick in the backend (`SchedulerTick`); without a running backend nothing starts. For occurrences that fell due during a gap, **one** rule applies since 2026-09-14, regardless of whether the JVM was frozen (standby, detected as a tick gap > `automation.scheduler.resume-grace-minutes`) or the process was not running at all (cold start, detected on the first tick; the reference is the backend's start time, occurrences after it are due normally):

- **Without opt-in** the occurrence is skipped and the schedule is set to its next occurrence (`ScheduleSkipPolicy`, activity log "Schedule skipped (system suspend/resume)" or "(backend was stopped)").
- **With opt-in** — per schedule `catchUpOnResume` or globally `scheduler-catch-up-on-resume` (settings UI) — the occurrence goes into the `CatchUpQueue`. It starts the entries **one after another** in the order they originally fell due: the next one only once the previous catch-up run has reached a final state (`ScheduledWorkflowExecutor.isFinished`). Waiting entries keep their old `nextRunAt` but are left out by the normal tick. An entry the executor rejects (target already running, schedule disabled in the meantime) does not block the queue.
- An active `skip-until` pause wins: then nothing is caught up, even with opt-in.
- The queue is deliberately not persistent: after a restart the same occurrences are due again and the cold-start path queues them again — nothing is lost, nothing runs twice.
- Visibility: one WARN line in the activity log per event ("Cold start detected … N missed schedule(s): X skipped forward, Y queued for sequential catch-up", on errors "…, Z failed"), `GET /api/v1/schedules/catch-up` (running and waiting entries) and a banner on the schedules page.
- `resume-grace-minutes: 0` switches both detections off (legacy behaviour: everything due starts at once).
- The tick only starts once Spring Boot reports `ACCEPTING_TRAFFIC` — that is, after the startup cleanup that sets leftover RUNNING/PAUSED runs to INTERRUPTED (`ApplicationReadyEvent`). Spring fires `@Scheduled` already on `ContextRefreshedEvent`, 0.3–1.4 s earlier: a catch-up run started there was marked INTERRUPTED by the cleanup in mid-run, and a stale run in state RUNNING made the target look busy, so the catch-up occurrence was dropped (TODO aa51d221). Skipped ticks do not count; the first real tick is the cold start and therefore comes about one tick period (60 s) after startup — the immediate tick on `ContextRefreshedEvent` is gone, and catch-up runs start correspondingly later.

Further rules of the tick:

- **An error only affects its own schedule** (TODO 68a3d91e): every schedule of a tick is started, skipped or queued on its own (`SchedulerTick.runIsolated`). If one fails — say, on a failed write — the activity log shows "Schedule execution failed: …" or "Schedule skip failed: …", and the tick moves on to the next one. Until 2026-09-24 the exception while skipping (`skip-until`) ended the loop: the remaining due schedules of the tick were neither started nor skipped.
- **Invalid cron expression** (TODO 565e853b): create and update refuse one; if one is in the database anyway (by hand, from an old version), `ScheduleSkipPolicy.endIfCronInvalid` disables the schedule on first contact — when it falls due in the executor as well as when it is skipped (`skip-until`, suspend, cold start) — without starting it, and reports it exactly once (activity log ERROR "Schedule disabled (invalid cron expression '…'): …", `reason: invalid-cron`). Until 2026-10-03 it started again after every run (its next occurrence could not be computed, `nextRunAt` stayed in the past), and during a `skip-until` pause every tick wrote an ERROR. Until it falls due, the dashboard (`UpcomingRunsCalculator`) leaves it out instead of failing on the parse exception.
- **Conflict `SKIP`** (default): if the target still has a run in `RUNNING` or `PAUSED` when the occurrence falls due, the occurrence is dropped — `nextRunAt` jumps to the next occurrence, `ONCE`/`DELAYED` expire, `lastRunAt` and `runCount` stay (`ScheduledWorkflowExecutor.skipOccurrence`). Until 2026-09-24 `nextRunAt` stayed in the past, the tick retried every minute and started the occurrence late as soon as the other run ended (TODO ade3fef8). `OVERLAP` starts alongside.
- **`maxRuns`** counts every start, including failed or rejected ones, but not occurrences dropped by `SKIP`. Re-enabling does not reset `runCount`.
- **No run left** (TODO 1e801952, `ScheduleEndConditions`): create, update and enable refuse an enabled `CRON` schedule with `400` when its next occurrence lies at or after the end date or its `maxRuns` ≤ `runCount` (translated: `SCHEDULE_NEXT_RUN_AFTER_END_DATE`, `SCHEDULE_MAX_RUNS_REACHED`). A disabled one stays editable (the form always sends `maxRuns`); the bundle import creates schedules disabled through `SchedulerService.createDisabled`, so an end date already passed does not fail it. The skip policy (`skip-until`, suspend, cold start) does not set `nextRunAt` past the end date but ends the schedule, and `effectiveNextRun` no longer shows an occurrence at or after the end date. Until 2026-10-03 such a schedule looked active and ended silently when it fell due, or started once more after `maxRuns` was lowered.
- **Cron without a future occurrence, one-off in the past** (TODO 1e801952): a valid expression that never matches again (say, 30 February) is refused with `400` on create, enable and a new expression (`SCHEDULE_NO_NEXT_RUN`); when `advance` finds no further occurrence after a run, it ends the schedule as at the end date and reports it once (activity log "Schedule ended (no further occurrence)", `reason: no-next-run`) — before, it stayed enabled without a next run. A `ONCE` schedule whose `scheduledAt` lies in the past is refused on create, enable and when its time is moved (`SCHEDULE_ONCE_IN_PAST`) instead of starting at once; `DELAYED` counts from now and is not checked.
- **End date** (`endDate`, `CRON` only, TODO 9a3e6c91): an occurrence on or after the end date does not start — measured by the occurrence, not by the time of the tick, so the last occurrence before it does not depend on where the 60-s tick lands. After a run or `SKIP`, `ScheduledWorkflowExecutor.advance` sets no next occurrence past the end date but disables the schedule; an occurrence already stored past it (old data, end date moved forward) ends the schedule without a start when it falls due (activity log "Schedule ended (end date reached)"). Until 2026-09-24 the first occurrence after the end date still ran once.
- **`skip-until` in the views** (TODO a776fd2a): the tick only winds an occurrence past the window once it falls due. Until then the read paths recalculate — `ScheduleSkipPolicy.effectiveNextRun` (the same rule as `skipScheduleUntil`, without writing): `ScheduleMapper` returns the first occurrence after the pause as `nextRunAt`, plus `pausedUntil`; `UpcomingRunsCalculator` leaves occurrences inside the pause out of the dashboard, calendar, cron preview and assistant context; `GET /schedules/maintenance` names the end of the pause. The read paths parse `skip-until` without an activity log entry; the tick reports an invalid value.
- **Maintenance mode** (`scheduler.maintenance-mode`, pause switch on the schedules page): the tick keeps running but starts nothing; `nextRunAt` stays put. When it is switched off, every overdue schedule starts once immediately, all at the same time — neither standby/cold-start detection nor the catch-up queue applies, the ticks during the pause have already used up the gap.
- **Maintenance mode in the views** (TODO 7704b1e9): the same rule as for `skip-until`, not a second one. `ScheduleSkipPolicy.activePause` reads both pauses once per view (`SchedulerPause`); for a schedule held by maintenance mode, `effectiveNextRun` returns no next run — nobody knows when the switch goes off. `ScheduleMapper` then reports `nextRunAt: null` plus `maintenance: true`, `UpcomingRunsCalculator` leaves all occurrences out of the dashboard (`maintenance: true`), and calendar and assistant context show none. The dashboard card and the schedule list say "Maintenance mode – nothing starts".

## Completion Triggers (V39)

Automatic workflow chaining based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) with a target workflow (started after completion)
- API: List, Get, Create, Enable/Disable, Delete; Filter by source or target workflow ID

## Audit Center — Findings, Acceptance and Expiry (V68/V69)

Audit workflows (metadata `audit.enabled=true`, `key`, `category`, `reportBlock`) emit an envelope `<!-- mysuricates:audit-result:v1 {…} -->` in their report block, which `AuditResultParser` takes apart (with several, the last one counts, so a quoted earlier run does not; TODO 1cf996df). `AuditIngestionService.ingest` (called by `AuditIngestionListener` on `WorkflowRunCompletedEvent`) checks and stores one `AuditReport` per run (VALID/INVALID); `AuditSnapshotApplier` matches the findings of a valid report in the same transaction by fingerprint (SHA-256 over projectId/auditKey/ruleKey/**normalised** subject):

- **Normalisation** (`AuditSubjectNormalizer`): if a lower-case kind word stands before the first `:` (`package:…`, `keyword:…`, `safe-inventory:…`), `<kind>:<value>` stays whole — it is the identity. Otherwise everything from the first `:` and a lower-case method suffix behind a class name are dropped (`ContainerShellService.execute` → `ContainerShellService`, `application.yml:key-store-password` → `application.yml`); paths with `/` and file names without a class prefix stay whole. Otherwise the model-generated `subject` drifts between runs and creates revenants (old finding `RESOLVED`, new one `OPEN`, managed TODO wrongly `DONE`). The stored `subject` is a display attribute only: on a match the newest value wins. `AuditFingerprintMigrationJob` (startup, idempotent) recomputes old rows and merges revenants into the oldest finding through `AuditFindingMerger` (counters added up, TODO links and occurrences move along). A human disposition (`ACCEPTED_RISK`, `DISMISSED`) always wins — whether it sits on the target or on the revenant; status and disposition (`dispositionNote`/`-Evidence`/`-At`, `resolvedAt`) move together, and a finding is only reopened through `AuditAcceptancePolicy` (proven deterioration). Every merge is logged before the delete with both IDs, both raw subjects and the `ruleKey`; a failing scope is logged and blocks neither the others nor the startup. If two findings of the **same** report yield the same identity, only the first is taken; the ingestion reports the others with their subjects in the activity log (they used to vanish silently, TODO 94fda6ef).

- **New** → `OPEN`; **back again** after `RESOLVED` → `OPEN` + `regressionCount`; `AWAITING_VERIFICATION` → `OPEN`; **no longer in the snapshot** → `RESOLVED` (only OPEN/AWAITING_VERIFICATION). The terminal states `ACCEPTED_RISK` and `DISMISSED` are left alone when a finding is absent.
- **Managed TODOs** (`ManagedTodoWriter`): only for `CRITICAL`/`HIGH` findings in status `OPEN` whose evidence names a measurement of the run (`ensureAll`, one query per report for links and TODOs; tag `audit`, `auto:workflow`, `TodoCreatedEvent` → TodoTrigger). Accepted/dismissed risks, re-reviews and unverified findings start no fix loop. After every ingest, `ManagedTodoWriter.reconcile` closes the TODO as soon as all linked findings are terminal and reopens it when one comes back; `ManagedTodoReconciler` does the same after a disposition change in the Audit Center and after a merge. A TODO on demand (`POST /api/v1/audit-findings/{id}/todos`, `AuditFindingTodoLinker`) takes the same path (`ManagedTodoWriter.create`). If none of the open linked findings is above the threshold any more (say after changed criteria HIGH → MEDIUM), the TODO is released: link removed, status `ARCHIVED` (not `DONE` — nothing was fixed); an `IN_PROGRESS` TODO stays untouched (TODO ba0dcb3a).
- **Gate status** comes from the workflow's run metadata (`gate_status`), not from the findings; `AuditSummary.openFindingsBySeverity` counts only non-terminal findings, `findingsByStatus` returns accepted ones separately (Audit Center: "open X · accepted Y"; the findings tab filters on open ones by default).

**Risk acceptance** (`PATCH /api/v1/audit-findings/{id}` with `status=ACCEPTED_RISK`, `dispositionNote`) — rules in `AuditAcceptancePolicy`, number parser `EvidenceMetrics`:

| Rule | Behaviour |
|---|---|
| Evidence snapshot | On acceptance, `AuditCenterService.updateFinding` freezes the current `evidence` text as `disposition_evidence` and the time as `disposition_at` (V69). Both stay through later status changes for traceability; accepting again sets both anew. |
| Deterioration | If the finding shows up again, the ingestion compares measurable values of the new evidence with the snapshot — `EvidenceMetrics.parse` recognises `number + unit` (`LOC`/`lines`/`Zeilen`, `NCLOC` as a unit of its own, never as `LOC`, `deps`, `KB`/`MB`/`GB`, `ms`, `%`, `files`, `classes`, `methods`, `tests`, `warnings`, `errors`, `issues`, `vulnerabilities`; synonyms are merged, per unit the largest value counts) and named counts from fact lines (`FAKT <name>_count: <number>`, the name is the key). If a shared key lies > 10 % above the snapshot, the finding goes back to `OPEN` and the note gains `Verschlechtert: 320 LOC → 400 LOC`; the snapshot stays. The change rate (`6 Aenderungen in 90 Tagen (Hotspot ab 5)`, `Backend churn: 30`) fluctuates and is not compared by percentage: it reopens only if it was below the hotspot threshold in the snapshot and reaches it now (threshold from `Hotspot ab N` in the current evidence, otherwise 5 as in `arch-findings.py`; note `Verschlechtert: 0 Änderungen → 6 Änderungen (Hotspot ab 5)`). Without comparable numbers (one-sided or none at all) there is no reopening. |
| Expiry | If the acceptance is older than 180 days when the finding reappears (`disposition_at`, fallback `resolved_at` for old acceptances), the finding goes to `AWAITING_VERIFICATION` with the note `Wiedervorlage nach 180 Tagen` — without a managed TODO. This happens exactly once: afterwards the normal rules apply (next ingest: present → `OPEN`, gone → `RESOLVED`); only a new acceptance starts a new period. Deterioration takes precedence over expiry. |

Per finding, the Audit Center shows the acceptance date, the re-review date (+180 days), the disposition note and the snapshot (expandable). Tests: `AuditAcceptancePolicyTest`, `EvidenceMetricsTest`, `AuditIngestionServiceTest`, `AuditSnapshotApplierTest`, `AuditControllerTest`, frontend `AuditCenter.test.tsx`.

**Context variable `audit_findings`** (TODO 3643beac): on every run, an audit workflow receives the known findings of its audit (`OPEN`, `AWAITING_VERIFICATION`, `ACCEPTED_RISK`, `DISMISSED`; without `RESOLVED` and without `ruleKey`s the catalogue does not know, marked or not). `WorkflowContextDataService` fetches them through the interface `AuditContextSource`, implemented by `service.audit.AuditFindingsContext` (so that `service.workflow` does not depend on `service.audit`), from a lean projection without TEXT blobs – marked as data and sanitised like `open_todos`, with the same gap path. The block has at most 250 lines and 60,000 bytes (a shell command is a single `bash -c` argument); when space runs short, the triaged findings stay ahead of the open ones. `ExecutionContextFactory` discards a passed-in value (sub-workflows inherit all variables), so the variable always describes the running workflow. That lets a report prompt carry triaged findings forward with the same identity, and deterioration and expiry take effect. Details: [audit-workflow-protocol.md](audit-workflow-protocol.md) → "Known findings in the prompt".

## Todo Triggers — Self-Improvement Loop (V51/V52)

Automatic execution of fix workflows when TODOs are created — closes the self-improvement loop:

```
Audit Workflow → AutoTodoService → TODO (OPEN)
                                     ↓ TodoCreatedEvent
                             TodoTriggerService → Fix Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

- `TodoTriggerService` — `@TransactionalEventListener` on `TodoCreatedEvent`, matches TODOs against configured triggers (tag pattern AND semantics, priority filter, project filter), starts fix workflow with TODO data as context variables
- `TodoFeedbackListener` — `@EventListener` on `WorkflowRunCompletedEvent`, closes TODO on GREEN gate (when `autoClose=true`), logs escalation when fix attempts are exhausted
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, Get, Create, Enable/Disable, Delete
- **Loop Prevention**: 3 layers — metadata flag (`todoTriggerRun`), max attempts counter (`todo.fixAttempts`), running check (`todo.lastFixRunId`)
- **Context variables** for fix workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`
- **Distinction — manual todo-workflow link**: The `TodoTrigger` starts workflows rule-based and automatically. Separately, a single todo can optionally be linked to a **resolver workflow** (`linkedWorkflowId`, set in the todo view) — the workflow meant to complete this one todo. The link is not global and starts nothing by itself: only the manual `Resolve` action starts the run, the todo then moves to `IN_PROGRESS` with `resolutionState=RUNNING`, is set to `DONE` automatically on success, and falls back to its previous status on a failed run (`resolutionState=FAILED`).

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

## Goals — ordering TODOs across projects (V85)

A **goal** (`entity/Goal`, `/api/v1/goals`) collects TODOs from several projects that work towards one outcome – for example the open-source launch, which touches the platform and the website at once. It is deliberately lean: no dependency graphs, sprints or time tracking. Everything around goals is the standard module `goals` (on in every preset, `automation.modules.goals=false` switches it off, TODO c60bf914): then `/goals/**` and `PATCH /todos/{id}/planning` answer 404 `MODULE_DISABLED`, TODO responses carry no `planning`, `GET /todos?goalId=` is a 400 (silently ignoring it would return the whole list), `milestonesDue` in the cockpit is empty, goal-bound schedules (input context with `goal_id`, such as the goal guard) are skipped and move on, and a run with `goal_id` gets no `goal_*` variables. The data is kept; after switching the module back on, everything is there. In the frontend the "Goals" menu entry, the goal cards and due milestones in the cockpit and the planning section of the TODO dialog are then gone; every goal query is switched off (`useModuleEnabled('goals')`), and `/goals` and `/goals/:goalId` show the module hint (`ModuleRoute`).

- **Planning lives on the TODO:** `goal_id` (at most one goal), `goal_rank` (1 = first, NULL = not ordered yet), `complexity` (S/M/L/XL), `suggested_effort` (`low`…`xhigh`, the same levels as `workflow.effort` and `claude --effort`), `rating_note`, `rated_by` (USER/AI), `rated_at` and `open_question`. Written via `PATCH /api/v1/todos/{id}/planning` (`TodoPlanningService`); `null` leaves a field alone, an empty string clears it.
- **Order:** rank, then priority, then age (`GoalService.WORK_ORDER`). `PUT /goals/{id}/order` sets rank 1..n for the listed TODOs; all others of the goal lose their rank. Assigning is `POST /goals/{id}/todos` (newly assigned TODOs come last, unranked).
- **Progress:** ARCHIVED does not count; open questions only count for active TODOs. List and detail read lean projections (`GoalStatusCounts`, `GoalTodoRow`, `GoalProjectRow`) without descriptions – safe under pool-size=1.
- **Projects of the goal** (V92, `entity/GoalProject`, `GoalProjectService`, TODO c60bf914): a goal is explicitly assigned to one or more projects, in an order (`goal_projects`, n:m). `POST /goals/{id}/projects` appends one (idempotent), `PUT /goals/{id}/projects/order` reorders, `DELETE /goals/{id}/projects/{pid}` removes – with 409 and counts while work packages or TODOs of the goal still hang on it; `?cascade=true` deletes the work packages (milestones lose the link) and releases the TODOs from the goal. When a write path brings a TODO from a new project into the goal (`GoalMembershipService.assign`, `TodoPlanningService`, a project change in `TodoService.update`), the project is added automatically (`GoalProjectRepository.linkIfAbsent`, a native `INSERT OR IGNORE`). The project change goes through `service/todo/TodoGoalConsistency`, which only uses repositories – `service.goal` already depends on `service.todo`. `GET /goals?projectId=` filters the cockpit; `GoalResponse.projects` and `GoalDetailResponse.projects` (with counts) come from one query each, `byProject` stays.
- **Who plans:** interactively the plugin skill `/mysuricates:plan-goal`, which sees the repository; unattended a workflow later. A person can override any AI assessment in the TODO dialog.
- **Relation map** (`GET /goals/{id}/relations`, `GoalRelationsService`): a goal has no link of its own to workflows, only through its TODOs. For every TODO the endpoint returns the **origin** (`source`, creating run `workflow_run_id`, reference `workflow_run`/`audit_finding`/`health_check`, `audit_finding_todos`), the **processing** (fix workflow `linked_workflow_id` and every matching active TodoTrigger) and the **result** (`last_fix_run_id`, `fix_attempts`, `resolution_state`). It shares the trigger matching with `TodoTriggerService` (`service/trigger/TodoTriggerMatcher`, pure). Everything is batched: one lean projection of the TODOs, then one query per kind. `include` brings up to 20 candidates without a goal along (`GET /todos?goalId=none` finds them).
- **"Might belong here"** (`GET /goals/{id}/candidates`, `GoalCandidateService`, TODO c60bf914): open TODOs (OPEN, IN_PROGRESS, BACKLOG) without a goal, by default only from the goal's projects (a sub-query on `goal_projects`, `TodoSpecifications.inProjectsOfGoal`), with `allProjects=true` from all of them; a page like `GET /todos` (order, batched names). A goal without projects has no candidates unless `allProjects` is set.
- **UI:** menu entry "Goals" with the overview `/goals` (active · done · archived), compact goal cards there and in the cockpit (`components/goal/GoalCard`, `GoalsSection`) naming the next milestone, goal view `/goals/:goalId` with an outline | list | timeline | workflow map switch (outline by default, the browser remembers the choice; the stored value `map` stays valid): the list (up next · open questions with "Decide" and a collapsible "Decided" section · done · per project; due date and work package as chips), the map as SVG in four columns origin · TODOs · processing · result (`goalRelationLayout.ts`, `GoalRelationMap`) – the same workflow is one node, and a dotted stub marks where no workflow docks on yet; rows are grouped per project by work package (in the goal's order), then "No work package". **Assigning workflows by dragging** (52bc1e8c, `WorkflowMapDnd`, `WorkflowMapPanel`, `workflowMap.ts`): on the right a workflow panel with the same dock/width/window mechanics as the side panel (`SidePanelFrame`, its own storage key in `panelGeometry`), listing the workflows of the goal's projects with search and "Show all workflows"; each can be dragged with the mouse or the keyboard (Space picks up, arrows jump from target to target, Enter drops, Escape cancels). Onto a TODO → `PUT /todos/{id}` with only `linkedWorkflowId` (the server applies only fields that are set), shown in the map at once; an existing fix workflow is only replaced after confirmation. Onto a work package or the goal band → `POST /goals/{id}/workflows` with role `WORKER`; an assignment that already exists sends nothing and shows a hint. Only fitting targets light up, a line under the dragged workflow says where it goes (a workflow from another project on a TODO is allowed, the line names the project; candidates accept nothing), every assignment offers "Undo" in its toast. A backend without `/goals/{id}/workflows` (404) only links TODOs. Below 1024 px there is no panel (a modal sheet cannot be dragged out of onto the map). Below, "Might belong here" (`GoalCandidates`) assigns with one click, by default only from the goal's projects (`GET /goals/{id}/candidates`; the "Show all projects" switch marks TODOs from other projects; a backend without the endpoint answers 404, then `/todos?goalId=none` as before, without the switch); the candidates shown appear dashed in the map. **Adding with a work package** (85889063, `hooks/useGoalAddTodo.ts`): when a candidate's project has packages in the goal, "+" opens a menu "No work package" plus the packages in their order; one click is one step (`PATCH /todos/{id}/planning` with `goalId` and `workPackageId`, the backend links the project to the goal; without a package `POST /goals/{id}/todos` as before). "+ TODO" sits right at the package (outline row, side panel; `PackageTodoAdder`): a search over the candidates, narrowed to the package's project, and "Create new: <title>" (`POST /todos` with `projectId`, then `planning`); Enter takes the first hit or creates – while the search is still loading, only titles containing what was typed count. Every addition reports "Added: project · package" with "Show" and "Undo" (`planning` with `goalId: ''`; a TODO just created is also archived via `PUT /todos/{id}`, never deleted – even if a TodoTrigger ran in the meantime); toasts carry several buttons for this (`toastStore`: `actions`, "Show" with `keepOpen` leaves the toast open so it can still be undone afterwards). "Show" (`goalReveal.ts`, a store because the toast outlives the page) switches from the list and the workflow map to the outline; outline and timeline open whatever hides the TODO, scroll to it (`data-todo-id`) and light it up for about two seconds (`[data-goal-revealed]` in `index.css`, without gliding or pulsing under reduced motion). Planning section in a TODO's edit dialog (`TodoPlanningPanel`); its "Work package" field only offers packages from the TODO's project, and the "Decision" field appears once the backend knows decisions (a new question and a decision at once are already refused by the form).
- **Work packages** (V92, `entity/GoalWorkPackage`, `GoalWorkPackageService`, TODO c60bf914; until V92 the milestones of TODO 259f4f66): structure a goal per project (`/goals/{id}/work-packages`). A package belongs to exactly one project of the goal (`project_id`), has an optional period (`start_date`/`end_date` only together, start ≤ end, else 400) and an order per project (`position`, `PUT /goals/{id}/work-packages/order`, only within one project). TODOs are assigned via `PATCH /todos/{id}/planning` with `workPackageId` (alias `milestoneId`) – only packages of the TODO's own goal and project (400 on write, legacy data is still read); a TODO that changes or leaves its goal loses its package, and a TODO changing its project leaves a package of the old project. A package changes its project only while it is empty. The **state is computed on read** (`GoalDueState`, based on the end): `REACHED` (completed) once all assigned TODOs (without ARCHIVED, at least one) are done, else `OVERDUE`, `DUE_SOON` (at most 3 days) or `OPEN`; without a period only `OPEN`/`REACHED`. `completed_at` keeps the first detection and stays; if an open TODO joins later, the response says `reopened: true`. A bare date means "from the start" for a start and "until the end of that day" for an end (UTC). The package list loads goal and packages with their project positions in **one** query (`GoalRepository.findWithWorkPackages`) and counts from the `GoalTodoRow` rows – two statements (`RequestCostRatchetTest`). V92 took over the old milestones (date → start and end) and split mixed packages per minority project.
- **Milestones** (V92, `entity/GoalMilestone`, `GoalMilestoneService`, TODO c60bf914): dates of the whole goal (`/goals/{id}/milestones`), no project, date required, optionally linked to work packages of the same goal (`goal_milestone_work_packages`, `workPackageIds`). With packages a milestone is reached once all of them are completed (`MilestoneProgress`; `reached_at` = first detection, then `reopened`), without packages a person marks it (`reached`; with packages that is a 400); otherwise the date decides. Milestones and their links are read in **one** query per goal (`GoalMilestoneRepository.findWithLinks`), their state comes from the package states computed anyway. The goal list names each goal's next milestone not yet reached (`GoalResponse.nextMilestone`) – for all goals from one query that counts linked and completed packages in sub-queries (`findSummaries`). The goal view (`GoalDetailAssembler`) reads goal with packages, TODO rows, goal projects and milestones with links – four statements.
- **"Needs you":** `GET /todos/overview` also returns `milestonesDue` – with `kind` `MILESTONE` the milestones of active goals that are `DUE_SOON` or `OVERDUE` (not reached), with `kind` `WORK_PACKAGE` the work packages whose end has passed while they still have open TODOs; each with `goalId`/`goalTitle` for the link, by date. The cockpit lists them ahead of the TODOs, each row leading to the goal page (`components/goal/MilestoneDueRow`).
- **Decided** (V93, TODO 68a5e24e): besides the open question a TODO keeps the decision that answered it (`decision`, `decided_at`, `decided_by` USER/AI) – written via `PATCH /todos/{id}/planning` (`decision`; clears `openQuestion`, `""` takes it back, a new question and a decision at once → 400). The state `decisionState` (`QUESTION`/`DECIDED`/`NONE`) is computed on read: a later new question leaves the decision in place, the state is then `QUESTION`. `decided` counts the non-archived TODOs with `DECIDED` in the goal's progress (from the GROUP BY that runs anyway), per goal project and per work package (from the `GoalTodoRow` rows read anyway) – no extra statement.
- **Workflows on the goal** (V94, `entity/GoalWorkflow`, `GoalWorkflowService`, `GoalWorkflowController`, TODO 52bc1e8c): a workflow hangs on the whole goal or on a work package, role `WORKER` ("works on it") or `STATUS` ("reports the status"); `GET/POST/PATCH/DELETE /goals/{id}/workflows`. The list reads attachments with workflow name, project and package order in one query; soft-deleted workflows drop out; an enabled goal guard is a synthetic first entry `guard` (cannot be detached, 400). Starting goes through the existing `POST /workflows/{id}/execute` with `goal_id` (optionally `work_package_id`, `goal_locale`) in the context – `TriggerSource` MANUAL, `goal_id` provides `goal_status`/`goal_guard_*`; the status comes from `GET /dashboard/workflow-results`. The goal view carries no workflows, its cost stays.
- **Outline** (default view of the goal page, TODO c60bf914; `components/goal/GoalOutline`, pure logic in `goalOutline.ts`): at the top the "Milestones" block (date, state, "2/3 packages"; add, edit, delete), below it, for each project of the goal in the goal's order, its work packages with their time span or "no time span" and progress, holding their TODOs. "No work package" and "No project" only appear when needed; a project that only TODOs come from shows as "not assigned" with a button to add it. Projects and packages can be moved (`PUT /goals/{id}/projects/order`, `PUT /goals/{id}/work-packages/order`); removing a project asks with counters and offers `?cascade=true`. The rows form a tree (↑/↓ change the row, →/← expand and collapse); the browser remembers per goal what is expanded.
- **Timeline** (fourth view of the goal page, TODO e192df18, rebuilt with c60bf914; `components/goal/GoalTimeline`, pure logic in `timelineLayout.ts`, `timelineDrag.ts` and `dragLabel.ts`): at the top the axis with "today" and a flag for the target date, spanning work packages, milestones, due TODOs and the target date (with no date at all: today plus four weeks), the grid by day, week or month depending on the width. Below it the **Milestones** row: a diamond per milestone (colour by state) whose guide line runs through all bands; at the right edge the title folds to the left of its diamond. While a diamond is dragged, every row with days lights up. A click into the row opens a title field there (Enter adds it, `POST /goals/{id}/milestones`), dragging a diamond changes the date (`PUT /goals/{id}/milestones/{mid}`), and a click on it opens the milestone in the side panel (see below). Then **one band per project**: every work package with a time span is a bar in a row of its own (fill = done/total, frame = state), with its due TODOs as chips below and undated ones behind a clear button "4 TODOs without a date ▾" – up to five stand open, more are folded; at the top of the timeline "TODOs without a date: show all / collapse" opens or folds them all, remembered per goal (`timelineUndated.ts`, 85889063); a "No work package" row only when TODOs of the project have none. Packages without a time span wait in the band's "No time span" tray. Dragging (`@dnd-kit/core`): a bar moves the time span, its handles change start or end (`PUT /goals/{id}/work-packages/{wid}`); dragged from the tray onto the axis, a package starts on that day and ends six days later, and a bar dropped into the tray loses its time span. A TODO dropped on a day gets the due date (`PUT /todos/{id}`, 23:59:59 local time as in the TODO dialog), in another package row or on a package in the tray it gets the package (`PATCH /todos/{id}/planning` with `workPackageId`), and on the "without a date" counter it loses the date – all only within its own project. While dragging, a second line under the dragged item shows the outcome: for a TODO the package and day ("→ Repo clean · Mon, 10/12", else "No work package" or "no date"), for a bar the time span ("10/12 – 10/18", also at a handle), for a diamond the day, and over another project the refusal; formatted with `Intl` in the UI language (`utils/localeFormat`). A click into a band's header row opens a title field for a one-week package starting that day, "+ Work package" adds one without a time span. Keyboard: bars and diamonds ←/→ by one day (Shift+←/→ changes a bar's end, Alt+←/→ its start), Enter opens the side panel; the click areas are sliders (arrow keys pick the day, Enter adds); Space lifts a TODO. A click (or Enter) on a TODO opens its dialog – not the click that ends a drag –, and its menu (⋯ or a right click) plans without dragging. Menus and the "New milestone" card float in a portal (`floating.ts`); a bar, row label or tray entry opens the package in the side panel.
- **Side panel of the goal page** (TODO c60bf914; `components/goal/GoalSidePanel`, `SidePanelFrame`, pure logic in `panelGeometry.ts` and `goalSelection.ts`): a milestone (diamond, line in the "Milestones" block) or a work package (bar, tray, row label, title in the outline) opens its details on the right – for a milestone date, state, "2/3 packages", "Reached" and, per linked package by project, progress, TODOs and workflows; for a package project, time span, progress, TODOs, workflows with "Assign workflow" and its milestones; plus edit and delete. The selection is in the URL (`?item=milestone:<id>` or `workpackage:<id>`), the open item is highlighted, × and Escape close and give the focus back to what opened it. From 1024 px docked: it pushes the content aside, sticks at the top while scrolling, always reaches the bottom of the view and scrolls within itself; a handle on its left edge changes the width (mouse, ←/→, `role="separator"`). "Detach as a window" turns it into a floating window – moved by its title bar (also with the arrow keys), resized at edges and corner, always inside the view; the browser remembers mode, width, place and size. Below that a sheet over the content. Every TODO – outline, list, side panel, timeline – opens the TODO dialog. If a backend from before c60bf914 answers without `workPackages`, `normalizeGoalDetail` (`hooks/useGoals.ts`) turns its milestones into one-day work packages, reads `milestoneId` as the package and hides the milestones behind a hint.
- **"Guard" switch** in the header of the goal page (`components/goal/GoalGuardControl`): the first time it is switched on, a dialog asks for the project (preset: the one with the most TODOs of the goal) and the time of day; after that the line shows the next run, the workflow and "Check now" (`POST /workflows/{id}/execute`). Switching on puts the guard workflow's result tile on the dashboard view shown, unless some view already shows it – the same path as "add widget" in the catalog (`components/dashboard/workflowResultTile.ts`), saved with the next version of the layout. Switching off leaves the tile in place. With the `dashboard-report` module off, the switch is only offered for a guard that already exists. An info symbol next to it (`InfoTip`, by mouse, keyboard focus and tap) explains what it checks and that it changes nothing, without AI; switched on, "Last result" links the last run with its traffic light (from `/dashboard/workflow-results`).
- **Deciding and workflows on the goal page** (TODO 68a5e24e, 52bc1e8c; `components/goal/DecisionMark`, `decision.ts`, `GoalWorkflowsProvider`, `GoalWorkflowChip`): ❓ (`text-human`) and ✔ (`text-success`) sit on every TODO in the outline, the list and the timeline chips; the tooltip gives wording, date and who. On the goal page ❓ opens the "Decide" card (`PATCH /todos/{id}/planning` with `decision`, the question closes), ✔ "Change" and "Remove" (`decision: ""`); the display follows at once and goes back on an error. Counters ❓ n · ✔ m per work package, project, in the progress and on the goal card (`DecisionCounts`). Workflows: in the header next to the guard those on the whole goal (the switched-on guard first), compact at every work package in the outline, the timeline row and the package card – role, dot of the last run (for "reports the state" the traffic light), time, and ▶ starts via `POST /workflows/{id}/execute` with `goal_id`, at a package `work_package_id`, and `goal_locale`; the toast leads to the run. A workflow's card shows the result for "reports the state" (`WorkflowResultContent`), switches the role and removes it (the guard only via its switch). "Assign workflow" offers the goal's projects' workflows first. The last run comes from `/dashboard/workflow-results`, every 5 s while one runs. If the backend does not know this yet (no `decided`, 404 on `/workflows`), the marks stay a display and only the switched-on guard shows.
- **Goal guard** (`service/goal/guard/GoalGuardService`, `POST /goals/{id}/guard`): one workflow per goal from the shipped template `goal-guard`, named "Goal guard: <title>" (German: "Ziel-Wächter: …"), `defaultContext.goal_id` and `goal_locale`, created through `WorkflowService` → `WorkflowMutationGateway` with `ChangeSource` `goal-guard`; plus a CRON schedule (default daily 08:00, `inputContext.goal_id`, conflict `SKIP`). Project: the given one, else the first non-archived goal project (V92), else the one with most of the goal's TODOs, else the oldest active one. `goals.guard_workflow_id` remembers the workflow. Switching on again only re-enables the schedule (a new `cron` replaces the old one); switching off and deleting the goal pause it – the workflow stays. If the workflow was deleted, the next switch-on creates a new one. Needs the module `dashboard-report`, else 409. `GoalResponse.guard` = `{enabled, workflowId, scheduleId, nextRunAt}` from one batched query (`GoalGuardLookup`). Why one workflow per goal: the dashboard tile is kept per workflow (latest run with `dashboard_widget`).
- **Context variables `goal_status` and `goal_guard_*`:** when a run's merged context carries `goal_id`, `WorkflowContextDataService` injects the goal's state through the interface `GoalContextSource` (implemented by `service.goal.GoalStatusContext`, so `service.workflow` does not depend on `service.goal`) – milestones and work packages listed separately, marked as data, sanitised like `open_todos`. Without `goal_id` they do not exist; an unknown id sets each of them to the `NICHT GEMESSEN` gap and warns in the activity log. Details: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) → "Goal context".

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Context-sensitive chat assistant for the platform. See also: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat logic and context preparation; `AssistantChatCaller` makes the Claude CLI call and holds the chat model and the base instructions
- `AssistantSessionService` — Session persistence in DB (V41 migration: `assistant_sessions`, V58: `preview` column). Endpoints: `GET /sessions` (top 50), `GET /sessions/{id}` (detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injects last 5 sessions (user message + response) and up to 8 active workflow memories into the system prompt — on **all** pages
- `PageSuggestionService` — generates and caches context-sensitive UI suggestions per page and language (`page_suggestions`, V42; unique over `page` + `locale` since V91)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`, `POST /pending-tools/{id}/confirm`, `POST /pending-tools/{id}/reject`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...&locale=de|en`, `POST /api/v1/assistant/suggestions/refresh?page=...&locale=...`
- Sessions are persisted after each chat call; `AssistantSessionUpdatedEvent` triggers page suggestion refresh
- Default model: `sonnet` (CLI alias, newest Sonnet)

## Workflow Memory

Workflows can accumulate knowledge across runs:
- `WorkflowMemoryService` — extracts knowledge from completed runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-based summarization
- `KnowledgeSummarizer` — Interface for summarization backends
- `HostCodexService` — runs OpenAI Codex CLI on the host (analogous to `HostClaudeService`)
- `SummarizationSetupService` — Configuration and setup of summarization backends
- `ConversationPreprocessor` — Preprocessing of conversation data for summarization
- Active memory entries are injected as context into subsequent runs
- `AutoMemoryExtractionListener` — automatic extraction after successful runs, configurable per workflow metadata (`autoMemoryExtraction.enabled`, `autoMemoryExtraction.interval`)
- `WorkflowMemory` entity (V16 migration)

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — per workflow, editable in the UI (Monaco editor)

3. **Working preferences** (opt-in) — only when a workflow requests them via
   `metadata.userPreferences: true`. `UserPreferencesService` then renders exactly three runtime
   settings into a `## Arbeitspräferenzen` section: `user-pref-language`, `user-pref-style`,
   `user-pref-stack` (answer language, answer style, tech stack). Nothing else is possible: the key
   list lives in the code, an additional key is a deliberate change. **No names, contact details,
   CVs or professional profiles** — decision of 2026-09-15. With nothing set, or without the flag,
   the prompt is byte-identical to the state before the feature; a checking or measuring workflow
   should depend on the facts alone. Values are maintained via
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
- Skill context is injected file-based: `writeSkillContextToProject()` writes `.claude/rules/skills-context.md` into the project directory (replaces the old CLI process-based method `injectSkillContextOnHost()`)

## Provider Routing

The AI-capable block executors (simple, retry, conditional, foreach, while, switch, skill-capability, compress-ai, agentic) determine the provider with `context.providerFor(block)`: `provider` on the block before the workflow's `provider`, without either the system default. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every AI block without its own `provider`; `compress-ai` only runs with `CLAUDE` (otherwise the step fails). On the `integration` block, `provider` names the integration provider, not the AI provider.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is passed.

Whether a provider is ready is reported by `GET /api/v1/system/ai-providers/status` (`service/ai/status/AiProviderStatusService`): program on the `PATH` and a login via login file or environment variable — the same rule as the installer (`scripts/lib/providers.sh`), from `AiProvider#setup()` (`ProviderSetup`). Container instances move the login directories (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`), and the check follows them (`ProviderSetup.loginDir`/`loginDirEnv`). No CLI call, cached (`automation.ai-provider-status.ttl-seconds`, default 60 s), `POST …/status/refresh` checks at once. The answer holds only names and commands, never a value. The start page shows the status while there is no project or the default provider is not ready (`AiProviderStartCard`), the settings always (`AiProviderStatusCard`).

## Per-Block Skills

Blocks can bring their own skills via the `skillIds` field, which apply additively to the workflow-level skills:

- `BlockDefinition.skillIds` — optional list of skill IDs that are activated only for this block
- `BlockSkillContext` (extracted from `WorkflowExecutor`) merges workflow-level skills + block-level skills before each block execution and restores the workflow baseline afterwards
- After block execution, the baseline skill state is restored
- Skill context is provided file-based via `.claude/rules/skills-context.md` in the project directory (`CommandExecutorService.writeSkillContextToProject()`); the Claude CLI reads `.claude/rules/` itself, pi (`--no-context-files`) and Codex get project context, skills and workflow memory appended to the system prompt via `RunContextFiles`
- Frontend: Skill picker toggles in the add/edit block modals, zap icon indicator on `SortableBlock`

## Model Selection

- `workflow.model` field: CLI alias (`opus`, `sonnet`, `haiku`, `fable` — always the newest model) or a concrete id (`claude-opus-5`); pi `<backend>/<model>`, codex slug
- Suggestions: `GET /api/v1/system/ai-providers/{id}/models` — `ModelCatalogService` with one `ModelCatalogSource` per provider (Claude: aliases, as long as the `claude` program is on the `PATH` — otherwise `available: false`; PI: `pi --offline --list-models`, CODEX: `~/.codex/models_cache.json` or `codex debug models --bundled`), TTL cache (setting `model-catalog-ttl-seconds`), `POST .../models/refresh`
- Passed via `--model` flag to the Claude CLI on the host
- The agentic REASON phase uses the locally configured default model

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

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- **54 built-in templates** are seeded at startup by `WorkflowTemplateSeeder` (idempotent, inserts missing ones, updates on version bump). Built-in rows whose file no longer ships (moved to the pool, renamed, left-overs with a random id) it removes first; the file names decide (`BuiltInTemplateLoader.shippedIds`), so a file that fails to parse does not lose its row. Verifications go along via `ON DELETE CASCADE`, workflows made from such templates stay and only lose the back-reference (`ON DELETE SET NULL`). User templates are left alone
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` are updatable
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- Every response carries `requiredModules` (the modules its blocks need, nested ones included; `BlockTypeModules.requiredBy`) and `needsAi` (whether a run can call a model; `executor/ModelUse`, the same rule as the AI badge in the frontend). The template picker hides templates and blueprints whose module is off and shows a counter instead ("N more need a module – show"). Once shown, the card names the module and how to switch it on. On top come the "Runs without AI" filter and translated categories
- CRUD API at `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- **19 built-in skills** are seeded at startup by `SkillSeeder` (idempotent)
- **Location:** `src/main/resources/skills/builtin/<id>.json` (metadata: `sortOrder`, `version`, `name`, `icon`, `description`, `category`, `capabilities` incl. `writes`/`async`/`handlerClass`/schemas) plus `<id>.md` holding the prompt text. Loaded by `BuiltInSkillLoader` (modelled on `BuiltInTemplateLoader`); the Java classes `BuiltInSkills`/`BuiltIn<Category>Skills` are gone
- The `.md` is taken **byte-for-byte** (no trim, no appended newline). `BuiltInSkillsSnapshotTest` compares all skills canonically against `src/test/resources/skills/builtin-snapshot.json` and fails on any unintended change; rewrite intended changes with `./mvnw test -Dtest=BuiltInSkillsSnapshotTest -Dskills.snapshot.write=true`
- **Version rule:** `SkillSeeder` seeds version-based (semver comparison in `SeedVersions`, like `WorkflowTemplateSeeder`) — missing skills are inserted, existing ones are only overwritten when the resource `version` is higher (capabilities included). So a content change to a built-in needs a higher `version` in its JSON, otherwise it never reaches existing instances. User skills (`builtin=false`) and built-ins no longer present in the resources are left untouched
- **Gate for the version rule:** `BuiltInSkillVersionGateTest` hashes each skill's `<id>.json` (canonically) plus its `<id>.md` (byte-for-byte) against the checked-in list `src/test/resources/skills/builtin-versions.json` and fails when the hash changed without a higher `version` or when the list is stale — after an intended bump, rewrite and commit it with `./mvnw test -Dtest=BuiltInSkillVersionGateTest -Dskills.versions.write=true`
- Loader failures are hard: a missing `.md`, a filename that differs from the `id`, a missing or duplicate `sortOrder`, a missing/unknown `category` or an empty directory abort startup instead of seeding an incomplete skill set
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — the seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (subdirectories `code/`, `document/`)
- Users can create custom skills via import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (instead of `@GeneratedValue`) for explicit IDs on built-ins

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification status per template+project pair
- Workflows created from templates can be test-started to verify template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic. The block schema in the prompts of the factory, wishes, dialog import, run conversion and workflow optimization comes from `BlockSpecs` (the same texts as the assistant's `fetch block-spec`); `PromptBlockSchemaGateTest` checks every block type and config key named there against the executors — the old prompts named `forEach`, `then_blocks`, `if_true` and other keys no executor reads. An example of retry, foreach, while or conditional with a command must also name `execution_mode`: without it the command goes to the AI as a prompt, and the AI builds what the example shows (the conversion prompt showed such a retry with `npm test`).
- **Build check after generation** (`service/workflow/analyzer/GeneratedWorkflowCheck`, shared by the factory, wishes, dialog import, run conversion and the assistant): after the AI answers, the `WorkflowAnalyzer` runs over the blocks. On HIGH findings the same AI (same provider, model and system prompt) gets **exactly one** correction round with its block list and the findings; if the answer is unusable, changes the structure (a different block count, a different block type at a position, a different number of nested blocks in a container — typically the model returns only the repaired block) or the corrected version has more HIGH findings, the original stays. If the correction omits a block's `name`, `skillIds` or `requiredCapabilities`, they are taken from the original. MEDIUM/LOW never cause an extra call. The workflow is then saved as before; the check changes nothing itself and never blocks saving. Remaining findings from MEDIUM up appear in the result's `buildFindings` (German; the same lines in English in `buildFindingsEn` — factory `/run`, suggestions of dialog import and run conversion — the UI shows them as "Build check" in its language) and are logged as WARN in the activity log on the workflow when it is saved. In the factory the check runs in `generateWorkflow`, i.e. before any test run; rule-based suggestions (no AI) are only reported. The assistant gets no correction round: `create_workflow`/`update_workflow` append the findings to their result line (in the UI language), and that line goes, shortened, into the model's history with the next turn ([assistant-system.md](assistant-system.md) → "Tool results in the history") so it can fix them with `update_workflow`. Every finding (`AnalyzerFinding`) carries its text in German (`detail`) and English (`detailEn`); `AnalyzerFindingLanguagesTest` triggers every rule and requires both.
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
- The UI explains this with an animated flow diagram (`components/factory/FactoryFlowDiagram`, geometry and states in `factoryFlow.ts`). It follows the auto-fix/auto-promote switches (also switchable right in the card, same state as the form), shows the one-step flow ending in "End · no test" for "Create Workflow", highlights the current station and its step in the list (play/pause; under `prefers-reduced-motion` only on click, a single pass) and is an explanation, not live progress: `/run` is one blocking call without intermediate events.

## Workflow Pool

Pre-built workflow definitions from the `workflow-pool/` directory:
- `WorkflowPoolService` — reads JSON files from the configured pool directory
- `WorkflowPoolController` — `/api/v1/pool` with endpoints:
  - `GET /` — list all pool workflows
  - `GET /{id}` — get a single pool workflow
  - `POST /{id}/install` — import workflow from pool into target project
  - `POST /export/{workflowId}` — export workflow to the pool

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

## Integration System (V55/V56)

Plugin framework for external services:
- `IntegrationRegistry` — auto-discovery of all `IntegrationProvider` beans
- `IntegrationOAuthService` — OAuth2 flow (Authorization Code Grant)
- `IntegrationEncryptionService` — credential encryption (AES-GCM)
- `IntegrationConnection` entity — connection status (CONNECTED, DISCONNECTED, EXPIRED)
- `IntegrationController` — `/api/v1/integrations` with OAuth callback, credentials, actions
- 18 providers in subdirectories: CalDav, Confluence, Feed, Gitea, GitHub, GitLab, GoogleCalendar, GoogleGmail, HttpApi, ICalFeed, Jira, Ntfy, OpenMeteo, Slack, Taskwarrior, Telegram, Todoist, Trello
- `IntegrationBlockExecutor` — executes provider actions as workflow blocks
- `TaskExportCapable` — interface for providers that support TODO export

## Dead Letter Queue (V63)

Failed asynchronous operations are stored in a dead letter queue:
- `DeadLetterService` — records an entry for every FAILED run via `@EventListener` on `WorkflowRunCompletedEvent` (not called from `service.workflow`, which kept a package cycle); management of dead letter entries (CRUD, retry, discard)
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

## Entities

All JPA entities and enums in the `com.automation.entity` package:

| Entity | Table | Description |
|---|---|---|
| `ActivityLog` | `activity_log` | System/execution log entries with level, category, metadata |
| `AssistantSession` | `assistant_sessions` | Persisted chat sessions of the assistant |
| `BlockDefinition` | *(embedded)* | Block definition with type, config, required capabilities (value object) |
| `Capability` | `capabilities` | Skill capabilities with schema and handler reference |
| `ChainEntry` | *(embedded)* | Entry in a chain with workflow reference and context (value object) |
| `ChainRun` | `chain_runs` | Execution instance of a chain with entry runs |
| `ChainRunEntry` | *(embedded)* | Status of a single chain entry during execution (value object) |
| `CompletionTrigger` | `completion_triggers` | Source→target workflow link on completion |
| `DeadLetterEntry` | `dead_letter_entries` | Failed messages in the dead letter queue |
| `IntegrationConnection` | `integration_connections` | Connection to external service (OAuth, credentials) |
| `TodoTrigger` | `todo_triggers` | Automatic fix workflow execution on TODO creation (self-improvement loop) |
| `Container` | `containers` | Docker container per project with status and mounts |
| `NotificationConfig` | `notification_configs` | Notification configuration (webhook/desktop) |
| `OnboardingProfile` | `onboarding_profiles` | Onboarding profile with type (BASE/PROFILE) |
| `OnboardingProfileHistory` | `onboarding_profile_history` | Change history of an onboarding profile |
| `PageSuggestion` | `page_suggestions` | Cached UI suggestions per page |
| `Project` | `projects` | Project with type, path, status, AI context |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron/once/delayed schedule for workflows and chains |
| `Skill` | `skills` | Skill with category, capabilities, configuration schema |
| `StepResult` | `step_results` | Result of a single workflow step with context delta |
| `TemplateVerification` | `template_verifications` | Quality status of a template per project |
| `Todo` | `todos` | Task with status, priority, source, tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook trigger for workflows with secret token |
| `Wish` | `wishes` | Natural language wish with decomposition and iteration history |
| `Workflow` | `workflows` | Workflow with blocks, instructions, model, provider (soft delete) |
| `WorkflowChain` | `workflow_chains` | Chain with sorted entries and stop-on-failure |
| `WorkflowMemory` | `workflow_memory` | Extracted knowledge from workflow runs |
| `WorkflowRun` | `workflow_runs` | Execution instance of a workflow with steps and metadata |
| `WorkflowTemplate` | `workflow_templates` | Template with i18n name/description and blocks |
| `WorkflowVersion` | `workflow_versions` | Versioned workflow snapshots |

**Enums:**

| Enum | Values |
|---|---|
| `ContainerStatus` | CREATED, RUNNING, STOPPED, ERROR |
| `GateStatus` | GREEN, YELLOW, RED |
| `ProjectStatus` | STOPPED, STARTING, RUNNING, ERROR |
| `ProjectType` | CODE, CONTENT, RESEARCH, BUSINESS, PERSONAL |
| `RunStatus` | PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED |
| `SkillCategory` | DOCUMENT, CODE, DATA, CONTENT, RESEARCH, BUSINESS, PRODUCTIVITY |
| `StepStatus` | PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, WAITING_APPROVAL |
| `WishStatus` | RECEIVED, DECOMPOSING, AWAITING_DECOMPOSE_APPROVAL, EXECUTING, EVALUATING, REFINING, AWAITING_CLARIFICATION, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED |
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED (ARCHIVED = "out of sight" — hidden from the default list, dedicated archive tab; target of hygiene/bulk archiving) |
| `Todo.Priority` | LOW, MEDIUM, HIGH, CRITICAL |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER, PLUGIN |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |
| `OnboardingProfileType` | BASE, PROFILE |
| `Todo.ResolutionState` | IDLE, RUNNING, FAILED, SUCCEEDED |
| `IntegrationConnection.ConnectionStatus` | CONNECTED, DISCONNECTED, EXPIRED |
| `DeadLetterEntry.Status` | OPEN, RETRIED, DISCARDED |

## Frontend Architecture

### Component Structure

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Language, Theme)
│
├── Pages (one page per route in App.tsx; in Simple Mode — the simplified UI, switchable in
│   │   the settings — nine routes swap their page for a Simple* variant)
│   ├── Dashboard, Projects, ProjectDetail, ProjectCockpit
│   ├── Workflows, WorkflowDetail, WorkflowExecution, WorkflowDebug, WorkflowFactory
│   ├── Skills, Schedules, Calendar, Webhooks, Integrations, Settings
│   ├── Runs, Approvals, ActivityLog, Todos, DeadLetterQueue, AuditCenter
│   ├── Goals, GoalDetail, Chains, ChainExecution
│   ├── Wishes, Docs, Reports, Insights, OnboardingProfiles, DialogImport
│   ├── Simple* variants (SimpleHome, SimpleWorkflows, etc.)
│
├── Hooks (TanStack Query + Custom, 40+)
│   ├── useProjects, useWorkflows, useSkills, useSchedules, ...
│   ├── useChains, useChainTemplates, useTodos, useTodoTriggers, useTodoOverview
│   ├── useActivityLog, useWorkflowMemory, useWishes, useWorkflowFactory
│   ├── useStaging, useTesting, usePool, useDocs, useReports, useInsights
│   ├── useIntegrations, useDeadLetters, useCompletionTriggers, useWorkflowVersions
│   ├── useAssistant, useAssistantWebSocket, useCalendarAggregated
│   ├── useWebSocket, useToast
│   └── calendar-adapters/ (Google, Runs, Schedules, Taskwarrior, Trello)
│
├── Stores (Zustand)
│   ├── sidebarStore, themeStore
│   ├── settingsStore, toastStore, assistantStore
│
└── API Client
    └── /api/v1/* → Vite Proxy → Backend :9090
```

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
- **Endpoint:** `/ws` (SockJS handshake, authenticated by ticket — see [WebSocket Authentication](#websocket-authentication))
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
    (re)connect and de-duplicates by `bufferId` + `seq`. Buffers live in memory only and expire 10 minutes after the run
    ends (also without a completion event, via the check against `WorkflowExecutionRegistry`), at most 20 runs.
  - **Configuration:** `automation.live-log.*` (`enabled`, `max-line-length`, `buffer-lines`, `flush-interval-ms`,
    `max-lines-per-flush`, `max-chars-per-flush`, `retention-minutes`, `max-buffered-runs`); read at startup only.
- **Terminal WebSocket:** `/ws/terminal/**` for real-time container access (raw WebSocket via `RawWebSocketConfig`, handler: `TerminalWebSocketHandler`)

## Security

### API Key Authentication

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (before Spring Security)
- **Comparison:** Constant-time (`MessageDigest.isEqual`)
- **Auto-generation:** Key is stored in `app_settings` on first startup
- **Disableable:** `automation.security.enabled=false` (development)

### Public Endpoints (no API key required)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `GET /api/v1/system/auth-status`
- `GET /api/v1/system/jvm-memory` (only the three heap numbers; `/actuator/metrics` requires the key)
- `POST /api/v1/webhook/trigger/{token}`
- `/api/v1/integrations/*/callback` (OAuth2 Callbacks)
- `/ws/**` (WebSocket)
- `/actuator/health`

The list lives once in `security/PublicPaths` (exact paths, patterns, the protected areas `/api/**` and `/actuator/**`); `SecurityConfig` builds its rules from it, and `ApiKeyAuthFilter` uses the same matchers to decide whether a missing key is logged as a SECURITY warning — only where the chain also rejects it. Exact paths match only themselves (no `/health/`, no `/health/extra`); everything outside the protected areas (the shipped frontend) is open.

### Rate Limiting

- **Filter:** `RateLimitFilter` (reads from `RateLimitConfig` bean)
- **Algorithm:** Sliding window (1 minute)
- **Default:** 120 requests/minute per client IP
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
- **No SockJS fallback:** the clients run with `transports: ['websocket']`. SockJS opens a separate session for every fallback transport (xhr-streaming, xhr-polling) and would need a second ticket — the first one is spent after the WebSocket attempt. **The instance therefore needs a WebSocket-capable path** (any proxy in front must pass upgrades through); there is no HTTP fallback any more.
- **Reconnect:** `reconnectDelay: 5000` with `ReconnectionTimeMode.EXPONENTIAL` and `maxReconnectDelay: 60000` — every attempt costs a `POST /ws-ticket` and must not run into the API rate limit while the backend is down.
- **Disabled:** With `automation.security.enabled=false` or no configured key the interceptor lets every handshake through (like `ApiKeyAuthFilter`).

### CORS

- One answer for the CORS filter, WebSocket handshakes and OAuth popup: `security/AllowedOrigins`
- Configured list `automation.cors.allowed-origins` (default: `http(s)://localhost:3000` and `:5173`)
- With the module `remote-access`, additionally the instance's own Tailscale IPv4 and MagicDNS name, any port (`service/system/OwnTailnetAddresses`: `tailscale ip -4` / `tailscale status --json`, kept for 60 s)
- WebSocket: `AllowedOriginHandshakeInterceptor` checks before the ticket; Spring's own check gets `*` because it only knows a fixed startup list
- All HTTP methods (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- No credentials (auth via `X-API-Key`, no cookies)

## Database Migrations (Flyway)

The DB migrations (`src/main/resources/db/migration/`) document the feature timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Base tables: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance indexes, container last activity |
| V12 | App settings |
| V13 | Workflow model + instructions |
| V14 | Schedule types + scheduled-at |
| V15 | Activity log table |
| V16 | Workflow memory table |
| V17 | Todos table |
| V18 | Project subtitle + description |
| V19 | Workflow templates table |
| V20 | Input context in workflow runs |
| V21 | Workflow chains |
| V22 | Template quality status |
| V23 | Default context in workflows |
| V24 | Activity log composite index |
| V25 | Workflow test status |
| V26 | Block types column |
| V27 | Chain support for schedules |
| V28–V29 | Wishes + wish reference workflow IDs |
| V30 | Project AI context |
| V31 | Workflow ID in activity log |
| V32 | Workflow soft delete (deleted_at) |
| V33 | Wish iteration history |
| V34 | Workflow provider |
| V35 | Project archiving (archived_at) |
| V36 | Todo session ID |
| V37 | Delayed schedule type |
| V38 | Scheduled workflow ID nullable |
| V39 | Completion triggers table |
| V40 | Notification configs table |
| V41 | Assistant sessions table |
| V42 | Page suggestions table |
| V43 | Triggered-by schedule ID |
| V44 | Schedule end conditions |
| V45 | Schedule conflict behavior |
| V46 | Stable synced-at |
| V47 | Context delta in step results |
| V48 | Related todo ID |
| V49 | Onboarding profiles |
| V50 | Workflow versions |
| V51 | Todo triggers table |
| V52 | Fix attempts + last fix run ID on TODOs |
| V53 | Process backlog flag on todo triggers |
| V54 | Composite indexes on todos |
| V55 | Integration connections table |
| V56 | Client credentials on connections |
| V57 | Due date on todos |
| V58 | Assistant session preview column |
| V59 | Linked workflow on todos |
| V60 | Workflow name in runs |
| V61 | Workflow tags and category |
| V62 | Auto-refine on wishes |
| V63 | Dead letter entries table |
| V64 | Writes flag on capabilities |
| V65 | Catch-up-on-resume on schedules |
| V66 | Todo export link |
| V67 | Reasoning effort on workflows |
| V68 | Audit reports, findings, occurrences and TODO links |
| V69 | Evidence snapshot and acceptance timestamp on audit findings (`disposition_evidence`, `disposition_at`) |
| V70 | Run provenance: definition hash on versions and runs, version reference and `trigger_source` |
| V71 | Workflow sealing: `sealed`, `sealed_hash`, `sealed_at` on workflows |
| V72 | Encrypted HMAC signing secret on webhook triggers |
| V73 | Versioned dashboard layout snapshots (`dashboard_layout_versions`) |
| V74 | Daily aggregate of the measured request cost per route (`request_metrics_daily`) |
| V75 | Marker for findings whose ruleKey is outside the catalog (`unknown_rule_key`) |
| V76 | Agent session provenance on the todo (`agent_session_ref`) |
| V77 | Evidence check on the audit finding (`unverified_evidence`) |
| V78 | Bilingual steps in templates (`workflow_templates.i18n_source`) |
| V79 | Blueprints as a kind of their own next to templates (`workflow_templates.kind`) |
| V80 | Skills under the seal: `workflows.sealed_skill_hashes`, change history `skill_versions` |
| V81 | Bilingual onboarding profiles: content as a `{de, en}` map |
| V82 | Seal state on runs: `workflow_runs.sealed`, `sealed_skill_pins` |
| V83 | Edited onboarding profiles: `customized`, `shipped_version` |
| V84 | Seal signature and seal history: `workflows.sealed_signature`, `workflow_seal_events` |
| V85 | Goals: table `goals`, ranking and rating on `todos` |
| V86 | Several named connections per integration provider: `integration_connections.connection_name` |
| V87 | Indexes for runs per schedule (`workflow_runs`, `chain_runs`) and TODOs per run (`todos.last_fix_run_id`, `todos.workflow_run_id`) |
| V88 | Goals: table `goal_milestones`, `todos.milestone_id`, `goals.guard_workflow_id` (goal guard) |
| V89 | A run's chain: `workflow_runs.chain_run_id` + index |
| V90 | Language-neutral account names for RSS/Atom and Open-Meteo (`integration_connections.account_info`, existing rows updated) |
| V91 | Assistant page suggestions per page and language: `page_suggestions.locale`, `UNIQUE(page, locale)` (table rebuild) |
| V92 | Goals stage 1: table `goal_projects`; the former milestones become `goal_work_packages` (project, position, period, mixed packages split, `todos.milestone_id` → `work_package_id`); new `goal_milestones` (dates of the goal) and `goal_milestone_work_packages` |
| V93 | Goals stage 2: decision on the TODO (`todos.decision`, `decided_at`, `decided_by`) |
| V94 | Goals stage 3: table `goal_workflows` (workflows on a goal or work package) |
| V95 | Indexes for derived queries on hot paths (`workflows.project_id`, `scheduled_workflows.workflow_id`/`chain_id`, triggers per workflow, `capabilities.skill_id`, `dead_letter_entries.workflow_run_id`, `workflow_memory(active, created_at)`) |
| V96 | What started a chain: `chain_runs.trigger_source` |
| V97 | Where a wish stopped: `wishes.stopped_phase` |

## Architecture Gates

Two JUnit tests in package `com.automation.architecture` pin the measured structure, without any new dependency. Their base measurement is the same jdeps package edges as in `scripts/public-readiness/arch-metrics.sh` (section `## packages`); `LayerRuleTest` additionally checks a second view (see below). `PackageCycleRatchetTest` pins today's mutually dependent package pairs as an allowlist — a new pair fails the build, a resolved one is only reported as a hint. Because pairs alone do not see every cycle (a single edge `service.workflow → service.goal` once grew the large cycle component from 17 to 24 packages unnoticed), it also caps the strongly connected components, i.e. the `cycle` lines of `arch-metrics.sh`: the largest at `MAX_CYCLE_COMPONENT_SIZE` (17 packages as of 2026-09-25), and every component with more than two packages is pinned with its members in `CYCLE_COMPONENTS` — a package newly pulled in fails the build, and the message names the edges that tie it in; when a component shrinks, the test fails as well until both values are lowered in the same commit. `LayerRuleTest` forbids the edge classes `service.* → controller.*`, `controller.* → repository.*` and `repository.* → service.*` (allowlist empty today). What gets measured is always the build directory the tests themselves were loaded from (`target/`, or `target-ide/` under the IDE profile), never a stale one sitting next to it; if it or jdeps is missing, both tests are skipped with a clear message instead of passing silently.

The blind spot of the bytecode measurement is a dependency that only appears as a generic type argument and is erased from the bytecode. `LayerRuleTest` therefore checks the same three rules from a **second view**: `SourceImportEdges` reads the `import` lines of the sources under `src/main/java` (column 0, so comments and string literals never count) and evaluates them against the same rules, with its own allowlist (empty today as well). The views do not overlap — the bytecode view sees inheritance, static references and generated code, the source view sees the erased type arguments — and the test additionally asserts that the scan read any classes at all: an empty measurement would otherwise report every rule as kept. **One gap survives both**: a fully qualified name inside a generic type argument (`Page<com.automation.repository.Foo>` written without an import) is erased in the bytecode and has no import line. For projections that travel from the repository up into the controller this means: they belong in `com.automation.dto.response`, not in the repository package.

## Directory Structure

```
MySuricates/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Pointer to docs/vision.md
├── docs/                              ← These documentation files
├── workflow-pool/                     ← Pre-built workflow definitions (JSON)
├── scripts/
│   ├── release.sh                     ← Version bump + git tag
│   ├── staging.sh                     ← Manage stable/test environment
│   └── start-mysuricates-dev.sh       ← Start dev environment
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker-compose.stable.yml      ← Stable container
│   ├── docker-compose.test.yml        ← Test container
│   ├── docker/
│   │   ├── Dockerfile.stable          ← Ubuntu-based (glibc for Claude CLI)
│   │   ├── Dockerfile.agent-node
│   │   ├── Dockerfile.agent-python
│   │   ├── Dockerfile.agent-devops
│   │   └── Dockerfile.agent-java
│   ├── data/                          ← SQLite DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java source files
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable profile
│           ├── application-prod.yml   ← Production profile
│           ├── application-test.yml   ← Test profile
│           ├── application-dev.yml    ← Dev profile
│           ├── agent-instructions.md  ← Base agent instructions
│           ├── templates/builtin/     ← Built-in template JSON files
│           └── db/migration/          ← 97 Flyway migrations (V1–V97)
│
└── ai-automation-frontend/
    ├── package.json
    ├── vite.config.ts
    ├── eslint.config.js               ← Flat config (ESLint 9+)
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry point
        ├── index.css                  ← Global styles + Tailwind 4 configuration (@theme inline)
        ├── api/client.ts              ← HTTP client (singleton)
        ├── types/index.ts             ← TypeScript types
        ├── pages/                     ← all pages incl. simple mode variants
        ├── components/
        │   ├── ui/                    ← Base UI primitives
        │   ├── layout/                ← App shell, sidebar, navigation
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Dashboard widgets
        │   ├── workflow/              ← Block editor, templates
        │   ├── assistant/             ← AI assistant panel
        │   ├── calendar/              ← Calendar view
        │   ├── factory/               ← Workflow factory builder
        │   ├── insights/              ← Analytics/insights
        │   ├── simple/                ← Simple mode components
        │   ├── todo/                  ← TODO management
        │   └── wishes/ etc.           ← Additional feature components
        ├── hooks/                     ← TanStack Query hooks
        ├── stores/                    ← Zustand stores
        ├── i18n/                      ← DE/EN translations
        ├── theme/                     ← Theme tokens
        ├── data/                      ← Skill constants
        ├── utils/                     ← Utility functions
        └── test/                      ← MSW handlers, test setup
```
