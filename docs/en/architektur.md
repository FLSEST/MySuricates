# Architecture

MySuricates is a self-hosted workflow platform: a Spring Boot backend with a web interface, in
which processes consist of blocks — some deterministic (shell, branch, loop), some AI calls.
This page describes its internal structure. For getting started see
[README.md](../../README.md); for building workflows see
[WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md).

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up.

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

**Catalog** (single source: `modules/ModuleCatalog`, 19 feature modules + 4 hardening switches = 23 keys):

| Tier | Modules | Maturity |
|---|---|---|
| `standard` (all presets) | `dashboard-report` | stable |
| `research` | `chains`, `assistant`, `learning` (analytics endpoints; dashboard data stays core), `memory`, `audit`, `bpmn` | chains stable, others experimental |
| `advanced` (outward-facing) | `integrations` (incl. TODO tracker export, calendar adapters), `webhooks`, `remote-access` (Tailscale), `terminal` (run terminal over WebSocket), `staging` | stable / staging experimental |
| `advanced` (low use) | `conversion`, `dialog-import` (requires conversion), `onboarding-profiles`, `skills`, `debug`, `factory` (incl. auto-repair), `wishes` (requires chains) | legacy / experimental |
| `hardening` | `provenance` (on by default), `sealing`, `require-sealed-for-unattended`, `webhook-signatures` | experimental |

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
- Background paths ask `ModuleRegistry` directly: `integration`/`skill-capability` blocks fail with a clear message, `/ws/terminal` refuses the handshake (`ModuleHandshakeGuard`), auto-memory, audit ingestion, staging health checks and chain schedules do not run, auto-repair is bound via `@EventListener(condition = ModuleConditions.FACTORY)`.
- Unknown module names, invalid values and unmet dependencies (`requires`) **fail the startup** — nothing enables itself.
- `ControllerModuleGateTest` enforces that every controller belongs to a module or is consciously listed as core.
- `GET /api/v1/system/capabilities` returns `preset`, `modulesUiEditable` and `modules[]` (with `configured`/`overridden`); the frontend uses it to hide navigation, routes (a "module disabled" page with a snippet) and widgets. **Settings → Modules & hardening** shows everything with YAML/env snippets.

**Switching in the UI (optional)** — With `automation.modules.ui-editable: true` (env `AUTOMATION_MODULES_UI_EDITABLE`) every feature module gets a switch in the settings:

- The change applies **immediately** (`ModuleRegistry` is mutable at runtime; every check runs per call, the terminal WebSocket per handshake, `TailscaleService` per call) and survives restarts (`app_settings` → `modules.ui-overrides`).
- UI values win over the configuration file; a value equal to the configured one is not stored, "Reset to configuration" removes it. Dependencies (`requires`) are checked like at startup (409 naming what is needed first). Stored values that no longer fit after a configuration change are skipped at startup instead of failing it.
- Every change writes a WARN `SECURITY` activity-log entry. With the switch off, `PUT/DELETE /api/v1/system/modules/…` answer **403 `MODULES_NOT_EDITABLE`** and stored UI values are ignored.
- **Hardening switches always stay startup configuration** (400): they protect against exactly the caller who holds the API key.
- Trade-off: with `ui-editable` anyone holding the API key can turn on webhooks (public endpoint), integrations (tokens), remote access or the run terminal (host access) — keep it off on publicly reachable instances. Running runs that reach a block of a module that was just switched off fail with the usual message.

## Workflow Hardening (optional)

Four switches under `automation.hardening.*` — the same mechanism as modules (startup configuration only, independent of the preset, shown read-only in **Settings → Modules & hardening**). Change provenance is not a switch; it is always on.

| Switch | Default | Requires | Effect |
|---|---|---|---|
| `provenance` | on | — | Every run stores `definition_hash`, `definition_version_id` and `trigger_source` |
| `sealing` | off | `provenance` | Seal workflows (`POST /api/v1/workflows/{id}/seal`, `…/unseal`) |
| `require-sealed-for-unattended` | off | `sealing` | Unattended runs only start sealed workflows |
| `require-signed-seal` | off | `sealing` | Sealed workflows need a valid signature |
| `seal-immutable` | off | `sealing` | Unsealing no longer over REST — only via the startup configuration |
| `webhook-signatures` | off | module `webhooks` | Per-webhook signing secret (HMAC-SHA256) |

**Definition hash** — `WorkflowDefinitionHasher` hashes the `WorkflowSnapshot` (blocks, instructions, model, provider, effort, metadata, defaultContext, sorted skill IDs) as canonical JSON (sorted keys, empty values omitted) with its own `ObjectMapper`, so Jackson customizing never changes the hash. Name, description, icon and tags are labels and are not hashed. Old version rows get their hash at startup (`WorkflowVersionHashBackfillService`). Context variables: `{{ workflow_definition_hash }}`, `{{ trigger_source }}`.

**Run admission** — Every run trigger calls `WorkflowExecutor.execute(…, TriggerSource)` and therefore passes `RunAdmission` before a run row exists. Rules in this order: (1) a sealed workflow must match its seal — definition and the content of its skills —, (2) with `require-sealed-for-unattended` an unattended run needs a sealed workflow. A refused run leaves **no run row and no DLQ entry**, only a `SECURITY` entry in the activity log; REST callers get the error code.

**Change provenance (always on)** — Every writer of definition fields goes through `WorkflowService` → `WorkflowMutationGateway`. Each change writes a version row with the state **after** the change (number = MAX+1) and a `changeSource` chosen by the calling server code, never by the request (`user`, `assistant`, `factory`, `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `import`, `bpmn-import`, `restore`). If the newest row's hash does not match the current state (legacy data, a change that bypassed the service), that state is first saved as `untracked`. Label-only changes create no version. `WorkflowWriterAllowlistTest` pins who else may call `WorkflowRepository.save`. The same place refuses unknown block types: `BlockTypeValidator` checks every block type — nested ones in `switch` `cases[].blocks` and `default` included — against the executor registry (`BlockTypeRegistry`, fed by all `BlockExecutor` beans) and throws **400 `INVALID_ARGUMENT`** naming the unknown type and the allowed ones. The check runs inside the caller's transaction, so the save is rolled back.

**Sealing** — `seal` records the current hash in `sealed_hash` (with `sealed_at` and a WARN `SECURITY` entry). Sealed workflows reject definition changes and deletion with **409 `WORKFLOW_SEALED`**; renaming still works. Repair (`POST …/repair`) is refused right away as well, automatic auto-repair skips sealed workflows before any AI call, and the UI disables "Optimize" and "Repair". If the definition no longer matches the seal at start (e.g. after a change made directly in the database) or cannot be verified, the run does not start: **409 `WORKFLOW_TAMPERED`**, ERROR `SECURITY` entry with expected and actual hash. Both checks apply regardless of the switch — `sealing` only gates setting and removing seals, so switching it off never silently unseals.

**Skills under the seal** — The definition hash only contains the skill **IDs**; what a skill brings into a run (name and `aiContext` in the prompt, config schema, per capability name, description, schemas, handler, `async`/`writes`) is not part of it. So `seal` additionally records a content hash for every skill the workflow uses — activated skills and the blocks' `skillIds` — (`SkillContentHasher`, canonical JSON as for the definition hash; labels such as description, icon, category and version do not count) and stores the mapping as JSON in `workflows.sealed_skill_hashes` (`WorkflowSkillPins`). `RunAdmission` compares it before every run: a changed, added or deleted skill yields **409 `WORKFLOW_TAMPERED`** naming the affected skill IDs; if the state cannot be verified, the run is refused as well. The definition hash itself is unchanged, so existing seals and the runs' version mapping stay valid. Seals from before V80 have `sealed_skill_hashes = NULL`: they keep running (with a log warning) but check no skills — sealing again adds the hashes, but only while the definition still matches its seal.

**Write path for skills (always on)** — Every skill writer goes through `SkillMutationGateway` (the counterpart of `WorkflowMutationGateway`): creation and editing (`user`, `PUT /api/v1/skills/{id}`; built-ins stay read-only, otherwise the next release would silently overwrite the edit), import (`import`) and built-in updates at startup (`builtin-seed`). Each change writes a row to `skill_versions` (operation `CREATE`/`UPDATE`/`DELETE`, canonical snapshot, content hash, server-side source; no foreign key, so the history survives deletion) and an activity-log entry — an import is a WARN `SECURITY` event. `GET /api/v1/skills/{id}/versions` lists the history, also for deleted skills. If a sealed workflow uses the skill, the gateway refuses changes and deletion by a person with **409 `SKILL_PINNED_BY_SEAL`**. Built-in updates from the resources cannot be refused; if they change the content under a sealed workflow, the gateway writes a WARN `SECURITY` entry naming the affected workflows, and their runs are refused until they are sealed again. `SkillWriterAllowlistTest` pins that only the gateway calls `skillRepository.save/delete`.

**Signature and history of a seal** — `sealed_hash` sits in the same SQLite file as the definition it protects; whoever writes that file changes both and passes. `WorkflowSealSigner` therefore signs every seal with HMAC-SHA256 over workflow id, definition hash and skill pins, using a key **outside** the database (`automation.seal.key-file`, env `AUTOMATION_SEAL_KEY`, otherwise a 0600 file created on first start; never `app_settings`). A signature that is present is always verified (`SIGNATURE_INVALID`); whether a seal **without** one may run is what `automation.hardening.require-signed-seal` decides (`SIGNATURE_MISSING`), so existing seals do not lose their runs overnight. Without a readable key the instance still starts and seals stay unsigned. `automation.hardening.seal-immutable` takes `POST …/unseal` out of the REST API (**403 `SEAL_IMMUTABLE`**): releasing a seal then needs the startup configuration, and a leaked API key (or an agent holding it) is no longer enough. Every sealing and unsealing is kept with its timestamp in `workflow_seal_events` (`GET /api/v1/workflows/{id}/seal-events`) — the activity log is pruned after 30 days and `sealed_at` disappears on unsealing. Entries are chained across all workflows (`prev_event_hash`); a row changed or deleted afterwards is reported as `chainIntact: false`. Refusals (of a run or of an unseal) write their SECURITY entry through `ActivityLogService.logRefusal` after the caller's transaction has ended, otherwise the rollback takes it along.

**Seals made visible** — `WorkflowSealVerifier` is the one check that `RunAdmission` runs before a start and `GET /api/v1/workflows/seal-status` runs without a run (`INTACT`, `VIOLATED` with reason `DEFINITION_CHANGED`/`SKILLS_CHANGED`/`UNVERIFIABLE` and the affected skills, `NOT_SEALED`). The response also carries the starts refused in the last 7 days from the activity log, because a refused start creates no run row. The endpoint is core, not part of the `sealing` module: a seal stays in force when the switch is off, so it must stay visible. `GET /api/v1/skills/seal-pins` maps every skill to the sealed workflows that use it. Every run records at start whether it ran sealed and verified (`workflow_runs.sealed`, `sealed_skill_pins`); this is never derived from the workflow's current state. The UI shows both states, not only the sealed one: a badge next to the name on the detail page and the list panel (lock "Sealed", open lock "Not sealed", red for a broken seal), a banner on the detail page, a labelled "Seal"/"Unseal" button, an "All / Sealed / Not sealed" filter with counts in the list, a filled lock or grey open lock in workflow rows, the run list, run detail and chain entries (runs from before the record show a dash "Seal state not recorded", never a value guessed from today's state), a lock on skill cards, and a "Starts refused" notice above the run list. The SECURITY entry of a refused start is written after the caller's transaction (webhook, schedule, chain) has ended, on its own virtual thread — otherwise the refusal would roll it back, and `REQUIRES_NEW` would block itself with pool size 1.

**Unattended runs** — `TriggerSource` classifies triggers:

| Attended | Unattended | Inherits from its starter |
|---|---|---|
| `MANUAL`, `RETRY`, `RESUME`, `TODO_RESOLUTION`, `ASSISTANT` | `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `COMPLETION_TRIGGER`, `AUTO_REPAIR`, `WISH` | `CHAIN`, `SUB_WORKFLOW` |

Chains and sub-workflows pass their starter's origin on in the reserved context key `__parent_trigger_source`: `ChainExecutor` sets it for every entry itself (inputs cannot preset it), `SubWorkflowBlockExecutor` hands down the parent run's effective origin, and webhook payloads may not contain `__` keys. A missing or unreadable key counts as unattended. Refusals answer **403 `UNATTENDED_REQUIRES_SEAL`**. With this switch, auto-repair and wishes are effectively off: they run unattended and would have to modify sealed workflows.

**Trust classes of context values** — Every context variable of a run carries one of three classes (`executor/VariableTrust`, bookkeeping in `executor/ExecutionContextTaint`). The class decides whether the value is substituted raw into a shell command line or wrapped in POSIX single quotes — and whether it *taints* the run, i.e. whether everything derived from it is escaped too.

| Class | Source | In shell blocks | In AI prompts | Taints the run |
|---|---|---|---|---|
| **trusted** | workflow defaults, system values (`project_path`, `workflow_run_id`, `workflow_definition_hash`), block-internal values, answers from `approval`/`human-input`, any `setVariable` | substituted raw | raw | no |
| **untrusted external** | webhook payload, restored `__untrusted_keys` (resume, sub-workflow), variables derived from tainted output (`step_N_output`, foreach items, `document` extracts) | always single-quoted | raw | yes, from the moment it is set |
| **platform data** | `previous_report`, `open_todos` — injected by `WorkflowContextDataService` into **every** run | always single-quoted | raw | on two paths: (1) once the placeholder was really substituted into a shell command line; (2) once the output of an AI block whose prompt contained the placeholder is recorded — from that coloured output on, the run is stickily tainted |

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
Controller (REST API, 49 Controllers)
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
├── controller/          49 REST Controllers
├── service/
│   ├── project/         ProjectService, ProjectStatusReconciler, ContainerService (+ DockerContainerGateway, DockerOrphanReconciler), ProjectContextService, PathValidator, OnboardingProfileService, ProjectOnboardingService
│   ├── workflow/        WorkflowExecutor (+ ExecutionContextFactory, BlockSkillContext, BlockStepRunner, RunCompletionHandler, RunFinalizer, WorkflowContextDataService), WorkflowService, ExecutionBroadcaster, TemplateVerificationService, WorkflowOptimizationService (+ WorkflowOptimizationPrompt), GhostRunWatchdogService, RunCleanupService, AgentInstructionsService, WorkflowTemplateService, BlockOutputValidator, WorkflowExecutionRegistry, WorkflowTaggingService, WorkflowVersionService, WorkflowContentChangedEvent
│   ├── skill/           SkillService, SkillImportExportService, SkillRegistry, CapabilityRegistry
│   ├── scheduler/       SchedulerService (+ SchedulerTick, SchedulerMaintenanceMode, UpcomingRunsCalculator, ScheduleSkipPolicy, SchedulerPause, ScheduleDefinitionParser, CatchUpQueue), ScheduledWorkflowExecutor, SuspendState
│   ├── webhook/         WebhookService, GitWebhookPayloadParser
│   ├── learning/        LearningService, AgenticLearningService, ExecutionInsightsService, WorkflowStatsService, WorkflowSimilarityService, BlockTypesBackfillService, MetadataExtractor, PlatformStatsService
│   ├── system/          ApiKeyService, DatabaseBackupService, ExecutionSettingsService (+ ExecutionSettingKeys; setters per topic in `settings/`: ExecutionCapacitySettings, AccessLimitSettings, HousekeepingSettings, FeatureSettings, RequestMetricsSettings), SystemIdentityService, TailscaleService, FileBrowserService, IntegrationSnippetService, SystemSettingsService
│   ├── ai/              HostClaudeService, HostCodexService, HostPiService, AiProviderRegistry, AiProvider, AiProviderCapabilities, AiResponseMetadata, ClaudeAiProvider, CodexAiProvider, PiAiProvider, PiCatalogService
│   ├── cli/             CommandExecutorService, CliSessionManager, CliSession, ClaudeOutputParser, ClaudeJsonResponse, HostExecutionRequest, ShellCommandService, ContainerShellService, TaskCommandService
│   ├── chain/           ChainService, ChainExecutor (+ ChainRunWaiter, ChainZombieRunCleaner), ChainTemplateService, BuiltInChainTemplate
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer, ConversationPreprocessor, SummarizationSetupService, AutoMemoryExtractionListener
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper (+ InstanceHttpClient, SyncPreviewService), DockerContainerManager
│   ├── todo/            TodoService (+ TodoSpecifications, TodoTrackerExportService, TodoResolutionService, TodoResponseAssembler), AutoTodoService (+ AutoTodoTextBuilder, PendingTodoApprovalService, GranularTodoService), GateStatusResolver, GateStatusOutputParser, ProjectPathResolver, TodoHygieneService, TodoOutputParser, TodoOverviewService, TodoResolutionListener, TodoSimilarityService
│   ├── conversion/      WorkflowConversionService (+ WorkflowConversionPrompt), WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService (+ DialogConversionPrompt), ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService (+ FactoryPromptBuilder, FactoryResponseParser, FactoryTopicCatalog), ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService (+ WishFulfillmentLoop, WishEvaluationService), WishWatchdogService, WishDecomposeService, WishDecomposePromptBuilder (+ WishSystemPrompt), WishExecutionService, WishEvaluatePromptBuilder, WishPromptBuilder, WishPromptHelper, WishJsonUtil, WishStatusHelper, WishStringUtil
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationProvider, OAuthConfig, TaskExportCapable
│   ├── integration/providers/ 7 Providers in subdirectories (confluence/, github/, google/, jira/, taskwarrior/, trello/)
│   ├── assistant/       AssistantContextBuilder (+ PageContextRenderer, ProjectFileListing), AssistantChatBroadcaster, AssistantFetchService, AssistantToolExecutor (+ AssistantTodoTools, AssistantWorkflowTools, AssistantChainTools, AssistantToolPayloads), AssistantActionSuggestionService, AssistantErrorClassifier, AssistantFetchPolicy, AssistantMutationGate, AssistantActionMerger, AssistantResponseParser, PendingTool, PendingToolRegistry, ToolExecutionResult
│   ├── assistant/context/ AssistantContextSection, BlockTypesIndexSection, ChainsIndexSection, FailedWorkflowsContextSection, IntegrationsIndexSection, RunsContextSection, SchedulesContextSection, SkillsIndexSection, TodosContextSection, WishesContextSection
│   ├── execution/       ExecutionQueryService (reads + DTO mapping), ExecutionCommandService (retry, resume, notes), WorkflowDebugService
│   ├── dlq/             DeadLetterService, DeadLetterRetryService
│   ├── document/        DocumentExtractionService, DocumentExtractionException
│   ├── export/bpmn/     BpmnModelBuilder (chain/single task/loop) + BpmnNodeFactory (ids, placement, edges, command tasks), BpmnGatewayBuilder (conditional/switch: split/rows/merge), BpmnEdgeRouter (right-angle waypoint routing), DataFlowReferenceScanner (placeholder scan for `block.x.output`/`previous_output`/`step_N_output`/compress `source`) + DataFlowConfigStrings (level rule: direct vs. nested config values) + BpmnBranchBlocks (blocks and id paths of the switch branches) + BpmnDataFlowBuilder (data objects and associations below the task row, the endpoint is always a task — for references from a branch the branch task, never the gateway; the import skips them), BpmnBlockText (labels, docs, config helpers), BpmnSegment (sub-graph), BpmnXmlRenderer, BpmnExportAdapter, BpmnModel, BpmnNames
│   ├── search/          SemanticWorkflowSearchService
│   ├── watchdog/        ConnectionPoolWatchdogService
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat assistant (Claude CLI)
│   ├── AssistantSessionService.java Session persistence
│   └── PageSuggestionService.java   Context-sensitive UI suggestions
├── entity/              JPA Entities + Enums
├── dto/
│   ├── request/         Request Records
│   └── response/        Response Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct Mappers (Entity ↔ DTO)
├── executor/            Workflow Execution Engine
│   └── blocks/          20 Block Executors + Agentic helper classes (AgenticBlockConfig, AgenticPlan, AgenticSafetyGuard, AgenticTokenTracker, AgenticPromptBuilder, AgenticEventBroadcaster, AgenticApprovalGate, AgenticActionRunner, AgenticStopRules)
├── concurrency/         DynamicSemaphore
├── config/              Configuration classes, Seeders (Skill, Template, OnboardingProfile)
├── security/            ApiKeyAuthFilter, RateLimitFilter, ActivityLogSizeLimitFilter, WebhookRateLimiter, WebSocketAuthInterceptor, WebSocketTicketService
├── skills/handlers/     CapabilityHandler, LintHandler, SecurityScanHandler, FactCheckHandler, GrammarCheckHandler, OCRHandler
├── exception/           GlobalExceptionHandler, ErrorCode + Exception classes
├── websocket/           LogWebSocketHandler, TerminalWebSocketHandler, TerminalSession (+ TerminalStdinStream), Message DTOs
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent, TodoCreatedEvent
└── util/                JPA Converters (JSON, BlockDefinitions)
```

### All 49 REST Controllers

| Controller | Base Path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, archiving, container lifecycle, context generation |
| `WorkflowController` | `/api/v1/workflows` | CRUD, execution, runs, export/import, skills, optimize, similar, repair, versions |
| `WorkflowAnalyzerController` | `/api/v1/workflows` | `/{id}/analyze` and `/analyze` — deterministic build-error check of one definition or the whole stock. Reports only, touches no write path (182bd224) |
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

**Controllable from step 1:** before submitting the run, `WorkflowExecutor` registers a provisional `ExecutionContext` in the `WorkflowExecutionRegistry`. A cancel therefore takes effect while the run is still waiting for a semaphore slot or for its container to start — the execution thread carries the cancel/pause flag over when it swaps in the real context. As a result `getActiveRunCount()` also counts queued runs, which stops `GhostRunWatchdogService` from releasing the permit of a starting run as a leak.

**Resume cuts by blocks, not by step index** (`ResumePlan`): `POST /executions/{id}/resume-from-step` picks up after the last COMPLETED step. What counts is that step's **position** in the block list, not its `stepIndex` — wrapper blocks (foreach, while, retry, conditional, switch) draw one more index per item or iteration, so a block's index runs ahead of its position. This rests on the guarantee `BlockStepRunner` gives: exactly one persisted step per top-level block, in block order (inner items are never persisted). The resumed segment's steps continue above every index already persisted (`ExecutionContext.startStepIndicesAt`), so indices stay unique and sortable. Previously the freshly allocated index was compared against the persisted one — after a wrapper block, the resume skipped blocks that had never run (TODO `1be423b4`).

**Block position per step** (`metadata.blockIndex`): `BlockStepRunner.startStep` writes the block's position in the top-level list into the step metadata, `runStep` keeps it over executor metadata. The run page's flow diagram maps steps to its boxes (`b_<position>`) through it (`latestStepByBlock` in `stepMetadata.ts`); runs without the key fall back to the position in the index-ordered list. After a resume the interrupted attempt of a block sits next to the new one — both with the same `blockIndex`, the higher `stepIndex` is the current one (TODO `dcba60da`).

**Container start/stop outside transactions:** `ProjectService.startContainer`/`stopContainer` are deliberately **not** `@Transactional`: the Docker call sits between two short `TransactionTemplate` bursts (STARTING → Docker outside any transaction → RUNNING/ERROR), because `ContainerService` holds the per-project monitor while it runs — an enclosing transaction would pin the single connection for the whole 5–15 s Docker start and push a concurrent workflow run (which takes the monitor first) into the 120 s connection timeout (lock-order inversion between monitor and connection). Because the STARTING burst commits before the Docker start, `ProjectStatusReconciler` picks up every project a crash in that window left in STARTING on `ApplicationReadyEvent` (RUNNING container row ⇒ RUNNING, otherwise STOPPED) — the project-level counterpart of `WorkflowExecutor.cleanupZombieRuns`. `ProjectService.delete` follows the same pattern in reverse order: the archive burst (`archivedAt` + activity log) commits first, the Docker stop runs outside any transaction afterwards — if it fails the project stays archived (ERROR log) and only the orphaned container is left for `DockerOrphanReconciler`/idle cleanup to reap.

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
- `@Lazy` injection on `WorkflowExecutor` only where a caller starts runs itself (sub-workflow, chains, triggers); pure status queries (active runs, container ids, approvals) go straight to `WorkflowExecutionRegistry`

### Human-Input Block

- Pauses workflow execution and waits for user input
- Sends `human_input_required` event via WebSocket to `/topic/executions/{runId}/logs`
- User response is stored as a context variable (`variable_name`)
- Supports text and select inputs, validation patterns, timeout with optional default
- Uses the `ApprovalRequest` mechanism of the approval block

## Gate Status System

Workflow blocks can write quality gate markers into their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of Markdown formatting)
- `RunCompletionHandler` scans all block outputs — highest severity wins (GREEN < YELLOW < RED)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**A gate does not change the run status.** Neither YELLOW nor RED: the gate is a finding about
the result, not a failure of the execution. RED creates a todo via `AutoTodoService` plus a
warning in the activity log, YELLOW only the warning — the run stays `COMPLETED`.

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

Pre-built chain definitions composed from existing workflow templates:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: Creates a new chain (optionally with associated workflows) in a target project

## Completion Triggers (V39)

Automatic workflow chaining based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) with a target workflow (started after completion)
- API: List, Get, Create, Enable/Disable, Delete; Filter by source or target workflow ID

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

## Project Cockpit & TODO Hygiene

Since ~96% of TODOs are machine-generated and scattered across many projects, there is a cross-project overview plus a cleanup mechanism.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): delivers a rollup per project (status counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus a global "Needs You" list (USER source ∨ CRITICAL ∨ overdue). All from lean GROUP BY projections (no TEXT blobs → pool-size=1-safe), frontend page `ProjectCockpit.tsx` (route `/cockpit`).
- **ARCHIVED status**: Storage alongside BACKLOG for "out of sight." Hidden from the default list (`TodoSpecifications.buildSpec` excludes ARCHIVED when no status filter is set), accessible via the archive tab / edit modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` moves stale auto-TODOs (OPEN/BACKLOG, source AI/WORKFLOW, tag `auto:workflow`, `updatedAt` older than threshold) to ARCHIVED — via bulk UPDATE in a short transaction. Cockpit button "Archive stale" (`POST /todos/archive-stale?projectId=`) is the primary, human-in-the-loop path. The `@Scheduled` job (daily 03:30) calls the same path but is **OFF** by default (`todo-hygiene-enabled`). Thresholds are runtime-configurable via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Bulk Status** (`POST /todos/bulk-status`): generic bulk UPDATE for many TODO IDs to a single status.

## Goals — ordering TODOs across projects (V85)

A **goal** (`entity/Goal`, `/api/v1/goals`) collects TODOs from several projects that work towards one outcome – for example the open-source launch, which touches the platform and the website at once. It is deliberately lean: no dependency graphs, sprints or time tracking.

- **Planning lives on the TODO:** `goal_id` (at most one goal), `goal_rank` (1 = first, NULL = not ordered yet), `complexity` (S/M/L/XL), `suggested_effort` (`low`…`xhigh`, the same levels as `workflow.effort` and `claude --effort`), `rating_note`, `rated_by` (USER/AI), `rated_at` and `open_question`. Written via `PATCH /api/v1/todos/{id}/planning` (`TodoPlanningService`); `null` leaves a field alone, an empty string clears it.
- **Order:** rank, then priority, then age (`GoalService.WORK_ORDER`). `PUT /goals/{id}/order` sets rank 1..n for the listed TODOs; all others of the goal lose their rank. Assigning is `POST /goals/{id}/todos` (newly assigned TODOs come last, unranked).
- **Progress:** ARCHIVED does not count; open questions only count for active TODOs. List and detail read lean projections (`GoalStatusCounts`, `GoalTodoRow`) without descriptions – safe under pool-size=1.
- **Who plans:** interactively the plugin skill `/mysuricates:plan-goal`, which sees the repository; unattended a workflow later. A person can override any AI assessment in the TODO dialog.
- **UI:** goal cards in the cockpit (`components/goal/GoalsSection`), goal view `/goals/:goalId` (up next · open questions · done · per project), planning section in a TODO's edit dialog (`TodoPlanningPanel`).

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Context-sensitive chat assistant for the platform. See also: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat logic, context preparation, Claude CLI call
- `AssistantSessionService` — Session persistence in DB (V41 migration: `assistant_sessions`, V58: `preview` column). Endpoints: `GET /sessions` (top 50), `GET /sessions/{id}` (detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injects last 5 sessions (user message + response) and up to 8 active workflow memories into the system prompt — on **all** pages
- `PageSuggestionService` — generates and caches context-sensitive UI suggestions per page (V42 migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`, `POST /pending-tools/{id}/confirm`, `POST /pending-tools/{id}/reject`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
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
- Prompt is written via **stdin** to the process (`writePromptToStdin()`) — avoids Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
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
- **66 built-in templates** are seeded at startup by `WorkflowTemplateSeeder` (idempotent, inserts missing ones, updates on version bump)
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` are updatable
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
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
- `WorkflowFactoryService` — orchestrates the entire factory process
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
- Flow of `POST /run` ("Create & Test"): generate on DEV → `syncDefinitions()` → test run on the Test instance (polling) → on `FAILED` with `autoFix`, AI repair on DEV from the error message (new version), sync and test again, up to `maxRetries` → record the test result on DEV → with `autoPromote` and a passed test, save a template. `POST /generate` ("Create Workflow") runs the first step only.
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
- 7 providers in subdirectories: Confluence, GitHub, GoogleCalendar, GoogleGmail, Jira, Taskwarrior, Trello
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
├── Pages (28 Routes)
│   ├── Dashboard, Projects, ProjectDetail, ProjectCockpit
│   ├── Workflows, WorkflowDetail, WorkflowExecution, WorkflowDebug, WorkflowFactory
│   ├── Skills, Schedules, Calendar, Webhooks, Integrations, Settings
│   ├── Runs, ActivityLog, Todos, DeadLetterQueue
│   ├── Chains, ChainExecution
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

## Architecture Gates

Two JUnit tests in package `com.automation.architecture` pin the measured structure, without any new dependency. Their base measurement is the same jdeps package edges as in `scripts/public-readiness/arch-metrics.sh` (section `## packages`); `LayerRuleTest` additionally checks a second view (see below). `PackageCycleRatchetTest` pins today's 13 mutually dependent package pairs as an allowlist — a new pair fails the build, a resolved one is only reported as a hint — and `LayerRuleTest` forbids the edge classes `service.* → controller.*`, `controller.* → repository.*` and `repository.* → service.*` (allowlist empty today). What gets measured is always the build directory the tests themselves were loaded from (`target/`, or `target-ide/` under the IDE profile), never a stale one sitting next to it; if it or jdeps is missing, both tests are skipped with a clear message instead of passing silently.

The blind spot of the bytecode measurement is a dependency that only appears as a generic type argument and is erased from the bytecode. `LayerRuleTest` therefore checks the same three rules from a **second view**: `SourceImportEdges` reads the `import` lines of the sources under `src/main/java` (column 0, so comments and string literals never count) and evaluates them against the same rules, with its own allowlist (empty today as well). The views do not overlap — the bytecode view sees inheritance, static references and generated code, the source view sees the erased type arguments — and the test additionally asserts that the scan read any classes at all: an empty measurement would otherwise report every rule as kept. **One gap survives both**: a fully qualified name inside a generic type argument (`Page<com.automation.repository.Foo>` written without an import) is erased in the bytecode and has no import line. For projections that travel from the repository up into the controller this means: they belong in `com.automation.dto.response`, not in the repository package.

## Directory Structure

```
autotrigger/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Pointer to docs/vision.md
├── docs/                              ← These documentation files
├── workflow-pool/                     ← Pre-built workflow definitions (JSON)
├── scripts/
│   ├── release.sh                     ← Version bump + git tag
│   ├── staging.sh                     ← Manage stable/test environment
│   └── start-autotrigger-dev.sh       ← Start dev environment
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker-compose.stable.yml      ← Stable container
│   ├── docker-compose.test.yml        ← Test container
│   ├── docker/
│   │   ├── Dockerfile.backend
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
│           └── db/migration/          ← 85 Flyway migrations (V1–V85)
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
        ├── pages/                     ← 37 pages (incl. simple mode variants)
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
