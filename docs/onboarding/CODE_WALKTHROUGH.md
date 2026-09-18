# Code Walkthrough - Die 10 wichtigsten Dateien

Dieses Dokument erklaert die 10 zentralen Dateien des Projekts. Das Verstaendnis dieser Dateien gibt dir einen soliden Einstieg in die Codebasis.

---

## Backend

### 1. WorkflowExecutor.java

**Pfad:** `ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java`

**Zweck:** Die zentrale Ausfuehrungsengine fuer Workflows. Verwaltet den gesamten Lebenszyklus einer Workflow-Ausfuehrung.

**Kernfunktionalitaet:**
- `execute()` — Erstellt einen `WorkflowRun` mit Status `RUNNING` und gibt ihn sofort zurueck (asynchrone Ausfuehrung)
- `executeBlocks()` — Laeuft in einem Virtual Thread, iteriert sequenziell ueber alle Bloecke
- Nutzt einen `Semaphore` zur Begrenzung gleichzeitiger Ausfuehrungen (`AUTOMATION_MAX_CONCURRENT_RUNS`)
- Verwaltet aktive Ausfuehrungen in einer `ConcurrentHashMap<String, ExecutionContext>`
- Sendet WebSocket-Updates nach jedem Schritt
- Publiziert `WorkflowRunCompletedEvent` nach Abschluss

**Wichtige Abhaengigkeiten:**
```java
private final List<BlockExecutor> blockExecutors;       // Alle Block-Typen
private final ContainerService containerService;        // Docker-Management
private final CommandExecutorService commandExecutorService;
private final SimpMessagingTemplate messagingTemplate;  // WebSocket
private final ExecutorService virtualThreadExecutor;    // Java 21 Virtual Threads
private final Semaphore executionSemaphore;             // Concurrency-Limit
```

---

### 2. HostClaudeService.java

**Pfad:** `ai-automation-backend/src/main/java/com/automation/service/ai/HostClaudeService.java`

**Zweck:** Schnittstelle zur Claude CLI auf dem Host-System. Alle KI-Aufrufe laufen ueber diesen Service.

**Kernfunktionalitaet:**
- `reason()` / `reasonStructured()` — Sendet einen Reasoning-Prompt an Claude, gibt Text + strukturierte Metadaten zurueck
- `executeAction()` — Fuehrt eine Aktion mit Claude CLI aus (mit Arbeitsverzeichnis)
- Baut CLI-Befehle mit optionalen Flags: `--model`, `--max-tokens`, `--append-system-prompt`

**Kritische Details:**
```java
// Prompt wird ueber stdin geschrieben (nicht CLI-Argument!)
// Verhindert E2BIG Fehler bei grossen Prompts auf Linux
writePromptToStdin(proc, prompt);

// Nested-Session-Erkennung verhindern
pb.environment().remove("CLAUDECODE");
pb.environment().remove("CLAUDE_CODE");
```

**Record-Typen:**
- `Turn(role, content)` — Einzelne Konversationsrunde
- `ReasoningResult(text, parsed)` — Reasoning-Ergebnis mit Metadaten
- `ActionResult(text, exitCode, parsed)` — Aktions-Ergebnis mit Exit-Code

---

### 3. AgenticBlockExecutor.java

**Pfad:** `ai-automation-backend/src/main/java/com/automation/executor/blocks/AgenticBlockExecutor.java`

**Zweck:** Implementiert die Goal→Reason→Act→Observe Schleife fuer KI-gesteuerte Bloecke.

**Ausfuehrungsablauf:**
```
1. Ziel aus Block-Config lesen
2. Fuer jede Iteration (max N):
   a. REASON: Claude analysiert Ziel + bisherige Ergebnisse → JSON {action, parameters, reasoning}
   b. ACT: Fuehrt vorgeschlagene Aktion aus (HostClaudeService.executeAction)
   c. OBSERVE: Ergebnis pruefen
   d. Ziel erreicht? → Schleife beenden
3. StepResult mit allen Iterationen speichern
```

**6 Sicherheitslimits:**
1. `max_iterations` (Standard: 10) — Harte Iterationsgrenze
2. `iteration_timeout_seconds` (Standard: 300) — Pro-Iterations-Timeout
3. `total_timeout_seconds` (Standard: 1800) — Gesamt-Timeout
4. `max_total_tokens` (Standard: 200000) — Token-Budget
5. `max_repeated_actions` (Standard: 3) — Abbruch bei identischen Aktionen
6. `require_approval` (Standard: false) — Freigabe vor jeder Aktion

**Zusaetzliche Sicherheit:** History-Compression nach 7 Turns (aeltere werden zusammengefasst)

---

### 4. ExecutionContext.java

**Pfad:** `ai-automation-backend/src/main/java/com/automation/executor/ExecutionContext.java`

**Zweck:** Laufzeit-Kontext, der durch alle Block-Executors weitergereicht wird.

**Wichtige Felder:**
```java
containerId          // Docker-Container fuer diesen Run
workflowRunId        // ID der aktuellen Ausfuehrung
projectPath          // Arbeitsverzeichnis
previousOutput       // Ausgabe des letzten Blocks
variables            // Template-Variablen (Map<String, Object>)
stepCounter          // Schrittzaehler (AtomicInteger)
paused               // Pause-Flag (AtomicBoolean)
cancelled            // Abbruch-Flag (AtomicBoolean)
pendingApproval      // CompletableFuture fuer Approval Gates
approvalMemory       // Gespeicherte Approval-Entscheidungen
```

**Template-Aufloesung:**
```java
// Ersetzt {{ variable_name }} und {{variable_name}} im Template-String
context.resolveTemplate("Ergebnis: {{ previous_output }}");
```

---

### 5. Workflow.java (Entity)

**Pfad:** `ai-automation-backend/src/main/java/com/automation/entity/Workflow.java`

**Zweck:** JPA-Entity fuer Workflow-Definitionen. Bildet die zentrale Datenstruktur der Anwendung ab.

**Beziehungen:**
```
Workflow (N) → (1) Project           // @ManyToOne
Workflow (N) ↔ (N) Skill            // @ManyToMany (workflow_skills)
Workflow (1) → (N) WorkflowRun      // @OneToMany
```

**JSON-serialisierte Felder:**
- `blocks` — Liste von `BlockDefinition` (via `BlockDefinitionListConverter`)
- `metadata` — Freies JSON (via `JsonMapConverter`)

**Wichtige Felder:**
```java
String instructions;    // Workflow-spezifische Instruktionen (Monaco-Editor)
String model;           // KI-Modell -- CLI-Alias, z.B. "opus" / "sonnet" / "haiku"
String templateId;      // Rueckverweis auf Template
```

---

## Frontend

### 6. client.ts (API Client)

**Pfad:** `ai-automation-frontend/src/api/client.ts`

**Zweck:** Singleton HTTP-Client fuer alle API-Aufrufe. Wird von allen TanStack Query Hooks verwendet.

**Kernfunktionalitaet:**
```typescript
class ApiClient {
  private getHeaders(): HeadersInit {
    // API-Key automatisch aus settingsStore einfuegen
    const apiKey = useSettingsStore.getState().apiKey;
    if (apiKey) headers['X-API-Key'] = apiKey;
  }

  private async request<T>(path, options): Promise<T> {
    // 30-Sekunden Timeout
    // Automatische Error-Behandlung
    // Base-URL: /api/v1
  }

  get<T>(path)           // GET Request
  post<T>(path, body)    // POST Request
  put<T>(path, body)     // PUT Request
  patch<T>(path, body)   // PATCH Request
  delete<T>(path)        // DELETE Request
}

export const api = new ApiClient();
```

**Verwendung in Hooks:**
```typescript
// In useWorkflows.ts
const { data } = useQuery({
  queryKey: ['workflows'],
  queryFn: () => api.get<Workflow[]>('/workflows'),
});
```

---

### 7. types/index.ts

**Pfad:** `ai-automation-frontend/src/types/index.ts`

**Zweck:** Zentrale TypeScript-Typdefinitionen fuer die gesamte Frontend-Anwendung. `BLOCK_TYPES` ist dabei die fuehrende Frontend-Liste der Block-Typen -- `types/__tests__/blockTypes.test.ts` und das Backend-Pendant `BlockTypeListsGuardTest` halten sie mit den Executoren gleich.

**Wichtige Typen:**
```typescript
// Projekt-Typen
type ProjectType = 'CODE' | 'RESEARCH' | 'CONTENT' | 'BUSINESS' | 'PERSONAL';
type ProjectStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR';

// Block-Typen (19 Varianten, Quelle: BLOCK_TYPES)
export const BLOCK_TYPES = [
  'simple', 'shell', 'retry', 'foreach', 'while', 'conditional', 'switch',
  'skill-capability', 'approval', 'agentic', 'sub-workflow', 'human-input',
  'integration', 'document', 'todo-create', 'todo-update', 'todo-close',
  'compress', 'compress-ai',
] as const;
export type BlockType = (typeof BLOCK_TYPES)[number];

// Ausfuehrungs-Status
type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS'
  | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
type StepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'WAITING_APPROVAL';

// Kern-Interfaces
interface Workflow { id, projectId, name, blocks, instructions, model, ... }
interface WorkflowRun { id, workflowId, status, steps, startedAt, ... }
interface BlockDefinition { type: BlockType, config: Record<string, unknown> }
interface Skill { id, name, category, aiContext, capabilities, ... }
```

Alle API-Hooks und Komponenten verwenden diese Typen fuer Typsicherheit.

---

### 8. useWorkflows.ts (TanStack Query Hook)

**Pfad:** `ai-automation-frontend/src/hooks/useWorkflows.ts`

**Zweck:** Zentralisierte Server-State Verwaltung fuer Workflows via TanStack Query.

**Typisches Pattern:**
```typescript
// Abfrage (cached, 30s stale time)
export function useWorkflows() {
  return useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.get<Workflow[]>('/workflows'),
  });
}

// Mutation mit automatischem Cache-Invalidierung
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

Dieses Pattern wird in allen Hook-Dateien unter `src/hooks/` konsistent angewendet (Stand 2026-09-18: 52 Dateien).

---

### 9. App.tsx (Router)

**Pfad:** `ai-automation-frontend/src/App.tsx`

**Zweck:** Root-Komponente mit React Router v6 Konfiguration und Code-Splitting.

**Routing-Struktur:**
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
      // ... weitere Routen
    ],
  },
]);
```

Alle Seiten werden mit `lazy()` geladen (dynamischer Import), was die initiale Bundle-Groesse reduziert.

---

### 10. settingsStore.ts (Zustand Store)

**Pfad:** `ai-automation-frontend/src/stores/settingsStore.ts`

**Zweck:** Persistierter Client-State fuer API-Key und Einstellungen.

```typescript
export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
    }),
    { name: 'app-settings' }  // localStorage Key
  )
);
```

**Warum wichtig:** Der `apiKey` wird von `client.ts` bei **jedem** API-Request automatisch als `X-API-Key` Header eingefuegt. Ohne korrekten API-Key schlagen alle Requests fehl (bei aktivierter Security).

---

## Zusammenfassung der Datenfluesse

### Workflow erstellen und ausfuehren

```
1. Frontend: WorkflowDetail.tsx → Block-Editor (Drag & Drop)
2. Frontend: useCreateWorkflow() → api.post('/workflows', data)
3. Backend:  WorkflowController.createWorkflow() → WorkflowService.create()
4. Backend:  WorkflowMapper → Entity speichern (JPA/SQLite)
5. Frontend: "Execute" Button → useExecuteWorkflow()
6. Backend:  WorkflowExecutor.execute() → WorkflowRun erstellen
7. Backend:  Virtual Thread → executeBlocks() → BlockExecutor fuer jeden Block
8. Backend:  WebSocket → Step-Updates an Frontend
9. Frontend: WorkflowExecution.tsx → ExecutionTimeline (Live-Anzeige)
```
