<!-- last-sync: 2026-06-29 from: docs/onboarding/GLOSSARY.md -->

# Glossary

Project-specific terms, abbreviations, and concepts in alphabetical order.

---

## A

**Agent Container**
Docker container with a language-specific runtime environment (Python, Node.js, Go). Created and managed dynamically by the backend via the Docker API — not via Docker Compose.

**Agentic Block**
Block type that implements an AI-driven Goal→Reason→Act→Observe loop. The Claude CLI analyzes a goal, proposes actions, and executes them iteratively until the goal is reached or a safety limit triggers.

**Approval Gate**
Manual approval mechanism within a workflow. Pauses execution and waits for human confirmation (approve/reject). Implemented via `CompletableFuture<String>`.

**API Key**
Authentication token for the REST API. Passed in the `X-API-Key` header. Stored in the `app_settings` table and in the frontend localStorage.

## B

**BlockDefinition**
JSON object describing a single step in a workflow. Consists of `type` (block type) and `config` (type-specific configuration). Stored as a JSON array in the `blocks` column of the workflow table.

**BlockExecutor**
Interface that every block type must implement. Defines `execute(BlockDefinition, ExecutionContext)` and `supports(String type)`.

**Built-in Skills**
19 predefined skills that are seeded automatically at startup (e.g. `builtin-code-review`, `builtin-test-generator`). Defined as resources under `src/main/resources/skills/builtin/<id>.json` (metadata incl. `sortOrder`) + `<id>.md` (prompt), loaded by `BuiltInSkillLoader`.

**Built-in Templates**
66 predefined workflow templates that are seeded at startup. Defined as JSON under `src/main/resources/templates/builtin/`, read by `BuiltInTemplateLoader` (schema: `BuiltInTemplateDefinition`), seeded by `WorkflowTemplateSeeder`. Cannot be deleted.

## C

**Capability**
A single capability of a skill (e.g. "lint", "OCR"). Registered in the `CapabilityRegistry` and referenceable in block definitions via `requiredCapabilities`.

**Claude CLI**
Anthropic's command-line tool for AI interactions. Runs on the host system (not inside a container). Invoked via `ProcessBuilder` in `HostClaudeService`.

**Container**
Docker container assigned to a project (1:1 relationship). Manages filesystem mounts, resource limits, and lifecycle.

## D

**DTO (Data Transfer Object)**
Objects for communication between controller and client. Split into `request/` (incoming, with validation) and `response/` (outgoing, projection).

## E

**ExecutionContext**
Runtime context passed through all block executors of a workflow execution. Contains container ID, variables, previous output, pause/cancel status, and approval state.

## F

**Flyway**
Database migration tool. Migrations are located under `src/main/resources/db/migration/` using the schema `V{N}__description.sql`. Currently 80 migrations (V1-V80).

## G

**Gate (quality gate, `GateStatus`)**
Result of a quality check at the end of a workflow run: `GREEN`, `YELLOW`, `RED` (`entity/GateStatus.java`). The `GateStatusResolver` reads the value from the run metadata key `gate_status` or derives it from the run status. `AutoTodoService` turns it into TODOs: RED with priority HIGH, YELLOW with MEDIUM, GREEN closes open TODOs.

Not to be confused with the → **Approval Gate**, which is the manual approval step inside a workflow and has nothing to do with `GateStatus`.

## H

**HostClaudeService**
Service class that starts the Claude CLI on the host system. Writes prompts via stdin (not as a CLI argument) to avoid `E2BIG` errors on Linux.

**History Compression**
Mechanism in `AgenticBlockExecutor` that summarizes older conversation entries after 7 rounds to use the context window efficiently.

## I

**i18n (Internationalization)**
Bilingual support (German + English). Frontend: `i18n/de.ts` and `i18n/en.ts`. Workflow templates store names/descriptions as JSON maps: `{"de":"...","en":"..."}`.

## L

**Lazy Loading**
All frontend pages are loaded via `React.lazy()` (code splitting). Reduces the initial bundle size.

## M

**MapStruct**
Code generator for entity-DTO mapping. Generates type-safe mapper classes at compile time (e.g. `WorkflowMapper`).

**Metadata**
Free JSON field on entities (Workflow, WorkflowRun, StepResult). Stored via `JsonMapConverter`. Used for additional information without schema changes.

## P

**previousOutput**
Output of the most recently executed block. Available in the next block via the `{{ previous_output }}` template variable.

**Project**
Top-level organizational unit. Has a type (CODE, RESEARCH, CONTENT, BUSINESS, PERSONAL), a path, and optionally a container.

**PWA (Progressive Web App)**
The frontend application is configured as a PWA (Vite PWA Plugin). Enables installation on desktop/mobile devices.

## R

**Run (WorkflowRun)**
A single execution of a workflow. Status: PENDING → RUNNING → COMPLETED/FAILED/CANCELLED. Contains a list of `StepResult`s.

## S

**Seeder**
Spring components that automatically insert data at application startup. `SkillSeeder` for skills, `WorkflowTemplateSeeder` for templates.

**Skill**
Reusable automation building block with a name, category, AI context, and capabilities. 19 built-in + user-defined skills.

**SkillRegistry**
In-memory `ConcurrentHashMap` holding all registered skills. Populated by the seeder at startup. Provides fast access and aggregated AI context.

**StepResult**
Result of a single block step within an execution. Contains status, output, error, timestamps, and metadata.

**STOMP**
WebSocket subprotocol for messaging. Used for real-time updates between backend and frontend.

## T

**TanStack Query**
Server-state management library (formerly React Query). Manages caching, refetching, and mutations for all API data. Configured with a 30s staleTime.

**Template Variables**
Placeholders in block configurations: `{{ variable_name }}` or `{{variable_name}}`. Resolved at runtime by `ExecutionContext.resolveTemplate()`.

**Todo**
Persistent task in the database. Can be created manually or automatically (by AI agents). Fields: title, description, status, priority, source, tags.

## V

**Virtual Threads (Java 21)**
Lightweight threads for asynchronous execution. Each workflow execution runs in its own virtual thread, managed by an `ExecutorService`.

## W

**WAL (Write-Ahead Logging)**
SQLite journaling mode for better concurrent-read performance. Configured in the JDBC URL: `?journal_mode=WAL&busy_timeout=10000`.

**WebSocket**
Bidirectional real-time communication. `/ws` for workflow updates, `/ws/terminal` for the container terminal. Frontend uses `@stomp/stompjs` + `useWebSocket` hook.

**Workflow**
Sequence of blocks that together describe an automation task. Belongs to a project, can activate skills, and has optional instructions and model selection.

**Workflow Conversion**
Mechanism that converts successful agentic runs into deterministic workflows. Uses rule-based analysis plus optional AI assistance. Implemented in `WorkflowConversionService`.

## Z

**Zustand**
Minimalist state management for React. Used for client-side state (settings, theme, sidebar, toasts). With `persist` middleware for localStorage persistence.
