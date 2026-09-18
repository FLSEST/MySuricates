<!-- last-sync: 2026-06-29 from: docs/onboarding/CODE_WALKTHROUGH.md -->

# Code Walkthrough - The 10 Most Important Files

This document explains the 10 central files of the project. Understanding these files gives you a solid entry point into the codebase.

---

## Backend

### 1. WorkflowExecutor.java

**Path:** `ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java`

**Purpose:** The central execution engine for workflows. Manages the entire lifecycle of a workflow execution.

**Core functionality:**
- `execute()` — Creates a `WorkflowRun` with status `RUNNING` and returns it immediately (asynchronous execution)
- `executeBlocks()` — Runs in a virtual thread, iterates sequentially over all blocks
- Uses a `Semaphore` to limit concurrent executions (`AUTOMATION_MAX_CONCURRENT_RUNS`)
- Manages active executions in a `ConcurrentHashMap<String, ExecutionContext>`
- Sends WebSocket updates after each step
- Publishes `WorkflowRunCompletedEvent` upon completion

**Key dependencies:**
```java
private final List<BlockExecutor> blockExecutors;       // All block types
private final ContainerService containerService;        // Docker management
private final CommandExecutorService commandExecutorService;
private final SimpMessagingTemplate messagingTemplate;  // WebSocket
private final ExecutorService virtualThreadExecutor;    // Java 21 Virtual Threads
private final Semaphore executionSemaphore;             // Concurrency limit
```

---

### 2. HostClaudeService.java

**Path:** `ai-automation-backend/src/main/java/com/automation/service/ai/HostClaudeService.java`

**Purpose:** Interface to the Claude CLI on the host system. All AI calls go through this service.

**Core functionality:**
- `reason()` / `reasonStructured()` — Sends a reasoning prompt to Claude, returns text + structured metadata
- `executeAction()` — Executes an action with the Claude CLI (with working directory)
- Builds CLI commands with optional flags: `--model`, `--max-tokens`, `--append-system-prompt`

**Critical details:**
```java
// Prompt is written via stdin (not CLI argument!)
// Prevents E2BIG errors with large prompts on Linux
writePromptToStdin(proc, prompt);

// Prevent nested-session detection
pb.environment().remove("CLAUDECODE");
pb.environment().remove("CLAUDE_CODE");
```

**Record types:**
- `Turn(role, content)` — Single conversation turn
- `ReasoningResult(text, parsed)` — Reasoning result with metadata
- `ActionResult(text, exitCode, parsed)` — Action result with exit code

---

### 3. AgenticBlockExecutor.java

**Path:** `ai-automation-backend/src/main/java/com/automation/executor/blocks/AgenticBlockExecutor.java`

**Purpose:** Implements the Goal→Reason→Act→Observe loop for AI-driven blocks.

**Execution flow:**
```
1. Read goal from block config
2. For each iteration (max N):
   a. REASON: Claude analyzes goal + previous results → JSON {action, parameters, reasoning}
   b. ACT: Executes the proposed action (HostClaudeService.executeAction)
   c. OBSERVE: Check result
   d. Goal reached? → Exit loop
3. Save StepResult with all iterations
```

**6 safety limits:**
1. `max_iterations` (default: 10) — Hard iteration limit
2. `iteration_timeout_seconds` (default: 300) — Per-iteration timeout
3. `total_timeout_seconds` (default: 1800) — Overall timeout
4. `max_total_tokens` (default: 200000) — Token budget
5. `max_repeated_actions` (default: 3) — Abort on identical repeated actions
6. `require_approval` (default: false) — Require approval before each action

**Additional safety:** History compression after 7 turns (older turns are summarized)

---

### 4. ExecutionContext.java

**Path:** `ai-automation-backend/src/main/java/com/automation/executor/ExecutionContext.java`

**Purpose:** Runtime context passed through all block executors.

**Key fields:**
```java
containerId          // Docker container for this run
workflowRunId        // ID of the current execution
projectPath          // Working directory
previousOutput       // Output of the last block
variables            // Template variables (Map<String, Object>)
stepCounter          // Step counter (AtomicInteger)
paused               // Pause flag (AtomicBoolean)
cancelled            // Cancellation flag (AtomicBoolean)
pendingApproval      // CompletableFuture for approval gates
approvalMemory       // Stored approval decisions
```

**Template resolution:**
```java
// Replaces {{ variable_name }} and {{variable_name}} in the template string
context.resolveTemplate("Result: {{ previous_output }}");
```

---

### 5. Workflow.java (Entity)

**Path:** `ai-automation-backend/src/main/java/com/automation/entity/Workflow.java`

**Purpose:** JPA entity for workflow definitions. Represents the central data structure of the application.

**Relationships:**
```
Workflow (N) → (1) Project           // @ManyToOne
Workflow (N) ↔ (N) Skill            // @ManyToMany (workflow_skills)
Workflow (1) → (N) WorkflowRun      // @OneToMany
```

**JSON-serialized fields:**
- `blocks` — List of `BlockDefinition` (via `BlockDefinitionListConverter`)
- `metadata` — Free JSON (via `JsonMapConverter`)

**Key fields:**
```java
String instructions;    // Workflow-specific instructions (Monaco editor)
String model;           // AI model -- CLI alias, e.g. "opus" / "sonnet" / "haiku"
String templateId;      // Back-reference to template
```

---

## Frontend

### 6. client.ts (API Client)

**Path:** `ai-automation-frontend/src/api/client.ts`

**Purpose:** Singleton HTTP client for all API calls. Used by all TanStack Query hooks.

**Core functionality:**
```typescript
class ApiClient {
  private getHeaders(): HeadersInit {
    // Automatically inject API key from settingsStore
    const apiKey = useSettingsStore.getState().apiKey;
    if (apiKey) headers['X-API-Key'] = apiKey;
  }

  private async request<T>(path, options): Promise<T> {
    // 30-second timeout
    // Automatic error handling
    // Base URL: /api/v1
  }

  get<T>(path)           // GET request
  post<T>(path, body)    // POST request
  put<T>(path, body)     // PUT request
  patch<T>(path, body)   // PATCH request
  delete<T>(path)        // DELETE request
}

export const api = new ApiClient();
```

**Usage in hooks:**
```typescript
// In useWorkflows.ts
const { data } = useQuery({
  queryKey: ['workflows'],
  queryFn: () => api.get<Workflow[]>('/workflows'),
});
```

---

### 7. types/index.ts

**Path:** `ai-automation-frontend/src/types/index.ts`

**Purpose:** Central TypeScript type definitions for the entire frontend application. `BLOCK_TYPES` is the authoritative frontend list of block types -- `types/__tests__/blockTypes.test.ts` and its backend counterpart `BlockTypeListsGuardTest` keep it in sync with the executors.

**Key types:**
```typescript
// Project types
type ProjectType = 'CODE' | 'RESEARCH' | 'CONTENT' | 'BUSINESS' | 'PERSONAL';
type ProjectStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR';

// Block types (19 variants, source: BLOCK_TYPES)
export const BLOCK_TYPES = [
  'simple', 'shell', 'retry', 'foreach', 'while', 'conditional', 'switch',
  'skill-capability', 'approval', 'agentic', 'sub-workflow', 'human-input',
  'integration', 'document', 'todo-create', 'todo-update', 'todo-close',
  'compress', 'compress-ai',
] as const;
export type BlockType = (typeof BLOCK_TYPES)[number];

// Execution status
type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS'
  | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
type StepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'WAITING_APPROVAL';

// Core interfaces
interface Workflow { id, projectId, name, blocks, instructions, model, ... }
interface WorkflowRun { id, workflowId, status, steps, startedAt, ... }
interface BlockDefinition { type: BlockType, config: Record<string, unknown> }
interface Skill { id, name, category, aiContext, capabilities, ... }
```

All API hooks and components use these types for type safety.

---

### 8. useWorkflows.ts (TanStack Query Hook)

**Path:** `ai-automation-frontend/src/hooks/useWorkflows.ts`

**Purpose:** Centralized server-state management for workflows via TanStack Query.

**Typical pattern:**
```typescript
// Query (cached, 30s stale time)
export function useWorkflows() {
  return useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.get<Workflow[]>('/workflows'),
  });
}

// Mutation with automatic cache invalidation
export function useCreateWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateWorkflowRequest) => api.post('/workflows', data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useExecuteWorkflow() { ... }
export function useUpdateWorkflow() { ... }
export function useDeleteWorkflow() { ... }
```

This pattern is applied consistently across all hook files under `src/hooks/` (as of 2026-09-18: 52 files).

---

### 9. App.tsx (Router)

**Path:** `ai-automation-frontend/src/App.tsx`

**Purpose:** Root component with React Router v7 configuration and code splitting.

**Routing structure:**
```typescript
const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,           // Sidebar + Header
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'projects', element: <Projects /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'workflows', element: <Workflows /> },
      { path: 'workflows/:id', element: <WorkflowDetail /> },
      { path: 'workflows/:id/run/:runId', element: <WorkflowExecution /> },
      // ... further routes
    ],
  },
]);
```

All pages are loaded with `lazy()` (dynamic import), which reduces the initial bundle size.

---

### 10. settingsStore.ts (Zustand Store)

**Path:** `ai-automation-frontend/src/stores/settingsStore.ts`

**Purpose:** Persisted client state for API key and settings.

```typescript
export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
    }),
    { name: 'app-settings' }  // localStorage key
  )
);
```

**Why this matters:** The `apiKey` is automatically injected by `client.ts` as the `X-API-Key` header on **every** API request. Without a correct API key, all requests will fail (when security is enabled).

---

## Summary of Data Flows

### Creating and executing a workflow

```
1. Frontend: WorkflowDetail.tsx → Block editor (drag & drop)
2. Frontend: useCreateWorkflow() → api.post('/workflows', data)
3. Backend:  WorkflowController.createWorkflow() → WorkflowService.create()
4. Backend:  WorkflowMapper → save entity (JPA/SQLite)
5. Frontend: "Execute" button → useExecuteWorkflow()
6. Backend:  WorkflowExecutor.execute() → create WorkflowRun
7. Backend:  Virtual Thread → executeBlocks() → BlockExecutor for each block
8. Backend:  WebSocket → step updates to frontend
9. Frontend: WorkflowExecution.tsx → ExecutionTimeline (live display)
```
