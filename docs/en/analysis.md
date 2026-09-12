<!-- last-sync: 2026-05-12 from: docs/analysis.md -->

> *Last updated: [auto-generated]*

# Documentation Gap Analysis — All Six Docs

> Generated: 2026-03-28, based on actual codebase state (v0.6.2)

---

## 1. vision.md (docs/)

### Version Outdated
- **Documented:** "Was schon gebaut ist (v0.6.0)" with 22 features
- **Actual:** Version is v0.6.2; VISION.md (project root) has 26 features

### Missing Features in "Was schon gebaut ist"
The docs/vision.md is missing 4 features that already exist in VISION.md (root):
1. **Run Resumption** (resume from last step after interruption) | Done
2. **Session-to-Workflow Import** (Claude sessions as workflows) | Done
3. **Extended Schedule Control** (end conditions, conflict behavior) | Done
4. **In-App Documentation** (DocsController, contextual help) | Done

### Sync Issue
- docs/vision.md and VISION.md (root) should have identical content but they've drifted apart
- Root VISION.md is more up-to-date

**Resolved on 2026-09-12:** The triple maintenance is gone — `docs/vision.md` is the source,
root `VISION.md` is now only a pointer to it.

---

## 2. frontend.md

### Routes Table (6 issues)

| Issue | Documented | Actual (App.tsx) |
|---|---|---|
| Wrong path | `/activity-log` | `/activity` |
| Missing route | — | `/dialogs` → DialogImport |
| Missing route | — | `/insights` → Insights |
| Missing info | — | Simple mode routing (SimpleDashboard, SimpleWorkflows, SimpleWorkflowDetail, SimpleWorkflowExecution) |
| Stale React version | "React 18" | React 19 (`"react": "^19.2.4"` in package.json) |

### Missing Page Documentation
These pages exist but are not documented in frontend.md:
- **DialogImport** (`/dialogs`) — Claude session import as workflows
- **Insights** (`/insights`) — Analytics/insights page
- **SimpleDashboard** — Simple mode variant of Dashboard
- **SimpleWorkflows** — Simple mode variant of Workflows
- **SimpleWorkflowDetail** — Simple mode variant of WorkflowDetail
- **SimpleWorkflowExecution** — Simple mode variant of WorkflowExecution

### Missing Hook Documentation (8 hooks)
The following hooks exist in `src/hooks/` but are not documented:
1. `useAssistant.ts` — Assistant chat integration
2. `useChainTemplates.ts` — Chain template management
3. `useCompletionTriggers.ts` — Completion trigger config
4. `useDialog.ts` — Dialog/session import
5. `useInsights.ts` — Insights data
6. `useNotifications.ts` — Notification config
7. `usePersistedState.ts` — Generic persisted state hook
8. `useWorkflowFactory.ts` — Workflow factory operations

### Missing UI Components (4 components)
These exist in `src/components/ui/` but are not in the docs table:
- `CronPicker` — Cron expression picker
- `DirectoryPicker` — Directory selection
- `ModalFooter` — Standardized modal footer
- `TagInput` — Tag input component

### Missing Store
- `assistantStore.ts` — not documented in Zustand stores table

### Missing Component Directory
- `AssistantPanel.tsx` — standalone component at top level of `src/components/`

---

## 3. entwicklung.md

### Controller Count Wrong
- **Documented:** "20 REST controllers"
- **Actual:** 26 REST controllers

### Missing Controllers (6 new controllers not listed)
1. `AssistantController`
2. `ChainTemplateController`
3. `CompletionTriggerController`
4. `DialogController`
5. `NotificationController`
6. `PageSuggestionController`

### Flyway Migrations Outdated
- **Documented (in CLAUDE.md project structure):** "Flyway migrations (V1-V24, SQLite)"
- **Actual:** Migrations go up to **V47** (`V47__add_context_delta_to_step_results.sql`)

### Dockerfile Description Wrong
- Agent Node described as `node:21-alpine` in deployment docs
- **Actual:** `node:21-slim` (Debian-based, not Alpine)

---

## 4. deployment.md

### Missing Dockerfile
- `Dockerfile.agent-devops` exists but is not documented in the Services table
  - Based on Ubuntu 24.04 with git, curl, jq, python3, gh (GitHub CLI), gitleaks, trivy, docker-ce-cli

### Missing Docker Compose File
- `docker-compose.test.yml` exists but is not documented
  - Test instance on port 8090, uses `application-test.yml`

### Agent-Node Base Image Wrong
- **Documented:** `node:21-alpine`
- **Actual:** `node:21-slim` (Debian-based)

### Security Default Changed
- **Documented:** `AUTOMATION_SECURITY_ENABLED` default `false`
- **Actual in application.yml:** default `true` (`${AUTOMATION_SECURITY_ENABLED:true}`)

### Missing Environment Variables / Config (many new sections)
These config sections in `application.yml` have no documentation in deployment.md:

| Config Section | Key Properties |
|---|---|
| `automation.cli` | `host-home`, `host-uid`, `skip-permissions`, `allowed-tools` |
| `automation.ai` | `default-provider` (default: CLAUDE) |
| `automation.host-claude` | `inter-call-delay-ms`, `max-concurrent-calls` |
| `automation.chain` | `workflow-timeout-minutes` (default: 180) |
| `automation.execution` | `max-concurrent` (default: 10) |
| `automation.backup` | `enabled`, `cron`, `retain-count`, `directory` |
| `automation.factory` | `provider`, `base-dir` |
| `automation.docs` | `directory` |
| `automation.pool` | `directory`, `enabled` |
| `automation.wish` | `total-timeout-minutes`, `workflow-wait-minutes`, watchdog settings, default timeouts |
| `automation.tray` | `enabled`, `poll-interval-ms`, `web-ui-url`, `stable-url` |

### Missing Application Profiles Documentation
- `application-dev.yml` and `application-prod.yml` exist but aren't mentioned in the Profiles section

---

## 5. workflow-memory-architecture.md

### Mostly Accurate
The architecture doc is largely correct. Minor potential updates:

- The `WorkflowMemoryService` constructor now takes explicit parameters (not `@RequiredArgsConstructor`-style autowiring implied in the diagram)
- The `ConversationPreprocessor` is correctly referenced
- Provider routing (`getSummarizer()`) matches actual code

### No Critical Gaps Found

---

## 6. assistant-system.md

### AssistantService Dependencies Incomplete
**Documented dependencies:** CommandExecutorService, HostClaudeService
**Actual dependencies (from signatures):**
- `CommandExecutorService`
- `WorkflowRunRepository`
- `TodoRepository`
- `TodoService`
- `ScheduledWorkflowRepository`
- `ProjectRepository`
- `WishRepository`
- `WorkflowRepository`
- `WorkflowMemoryRepository`
- `AssistantSessionRepository`
- `SystemIdentityService`
- `ObjectMapper`
- `AppSettingsRepository`

### Missing Internal Architecture
Not documented:
- `PageCategory` enum: `RUNS, WORKFLOWS, TASKS, WISHES, SCHEDULES, OVERVIEW`
- `classifyPage()` method for routing context injection
- Model caching mechanism (`cachedAssistantModel`, `refreshCachedModel()`)
- Settings seeding on startup (`seedSettings()`)

### Context Injection Section Incomplete
Documented injection points are vague. Actual method landscape:
- `appendPlatformState()` — overall platform stats
- `appendTodos()` — open TODOs
- `appendUpcomingSchedules()` — scheduled workflows
- `appendProjects()` — project list
- `appendWorkflows()` — workflow list with details
- `appendWorkflowMemory()` — active workflow memories
- `appendRecentSessions()` — recent assistant sessions
- `appendWishes()` — active wishes
- `appendPageContext()` — page-specific context

### Thinking Phrases
`DEFAULT_THINKING_PHRASES` constant exists but isn't documented (UX feature for loading states)

---

## Cross-Cutting: CLAUDE.md

### Project Structure Outdated
- Flyway migrations: says "(V1-V24, SQLite)" — should be **(V1-V47, SQLite)**
- Controller list in `controller/` comment is missing: Assistant, ChainTemplate, CompletionTrigger, Dialog, Notification, PageSuggestion
- Pages list in `pages/` comment is missing: Wishes, WorkflowFactory, Docs, Insights, DialogImport
- Hooks list is missing: useAssistant, useChainTemplates, useCompletionTriggers, useDialog, useInsights, useNotifications, usePersistedState, useWorkflowFactory

---

## Priority Summary

| Doc | Severity | Key Issue |
|---|---|---|
| **frontend.md** | HIGH | 6 missing/wrong routes, 8 undocumented hooks, React version wrong |
| **entwicklung.md** | HIGH | Controller count wrong (20→26), migration count wrong (V24→V47) |
| **deployment.md** | HIGH | Missing Dockerfile, security default changed, many missing config vars |
| **vision.md** | MEDIUM | Version outdated (v0.6.0→v0.6.2), 4 missing features |
| **assistant-system.md** | MEDIUM | Incomplete dependency list, missing context injection details |
| **workflow-memory-architecture.md** | LOW | Largely accurate, minor updates only |
| **CLAUDE.md** | HIGH | Stale project structure (migrations, controllers, pages, hooks) |
