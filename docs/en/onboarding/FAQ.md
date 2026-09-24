<!-- last-sync: 2026-06-29 from: docs/onboarding/FAQ.md -->

# FAQ - Frequently Asked Questions and Issues

---

## Setup & Development

### How do I start the development environment?

```bash
# Terminal 1: backend (from the repo root)
./scripts/start-autotrigger-dev.sh start

# Terminal 2: frontend
cd ai-automation-frontend
npm run dev
```

> `mvn spring-boot:run` does not work: the `RuntimeClasspathGuard` aborts a start from `target/classes` with exit code 2. The script builds the JAR and starts it from a copy.

The backend runs on port 9090, the frontend on port 3000. Vite automatically proxies `/api` and `/ws` to the backend.

---

### Do I need Docker installed?

**For basic development:** No. Backend and frontend run directly on the host. The Claude CLI is also executed natively.

**For skill execution in containers:** Yes. The agent containers (Python, Node.js, Go) require Docker. Build the images with:

```bash
cd ai-automation-backend
docker compose --profile build-only build
```

---

### Where is the database located?

SQLite file at `ai-automation-backend/data/automation.db`. Created automatically on first start. Flyway runs all migrations.

**Reset the database:** Simply delete `data/automation.db` and restart the backend.

---

### Why do API requests fail with 401?

Security is enabled by default (`AUTOMATION_SECURITY_ENABLED=true`). Solutions:

1. **Enter the API key in the frontend:** Settings → API Key
2. **Disable security:** `export AUTOMATION_SECURITY_ENABLED=false` before starting the backend
3. **API key as environment variable:** `export AUTOMATION_API_KEY=my-key`

---

## Architecture & Code

### Where do I find the REST endpoints?

The REST controllers live under `ai-automation-backend/src/main/java/com/automation/controller/` (as of 2026-09-18: 43 classes with `@RestController`). Each controller has a `@RequestMapping` with the API path; re-measure with `find ai-automation-backend/src/main/java -name '*Controller.java' | wc -l`.

The complete API reference is at `docs/en/api-referenz.md`.

---

### How do I add a new block type?

1. **Entity:** Add the new type to `BlockType` (frontend `types/index.ts`)
2. **Executor:** Create a new class implementing `BlockExecutor`:
   ```java
   @Component
   public class MyBlockExecutor implements BlockExecutor {
       @Override
       public Set<String> supportedTypes() { return Set.of("my-type"); }

       @Override
       public StepResult execute(BlockDefinition block, ExecutionContext ctx) { ... }
   }
   ```
   `supportedTypes()` is the single source of which types exist: `BlockTypeRegistry` collects them from all executors, and saving a workflow with any other type is refused. `supports(String)` is a default method on top of it.
3. **Keep the lists in sync:** The type must appear in all three lists -- `BLOCK_TYPES` in `ai-automation-frontend/src/types/index.ts`, `BlockTypesIndexSection` (assistant context) and `AssistantToolValidator.KNOWN_BLOCK_TYPES`. `BlockTypeListsGuardTest` (backend) and `types/__tests__/blockTypes.test.ts` (frontend) enforce parity with the executors. The type also needs a specification in `BlockSpecs` (assistant, `fetch block-spec`); `BlockSpecConfigKeysGateTest` checks that the executor reads every config key named there.
4. **Editor:** a starting config in `ai-automation-frontend/src/data/blockDefaultConfig.json` and a key schema in `blockConfigSchema.ts` (`blockConfigSchema.test.ts` requires both). Optionally a configuration dialog of its own

Spring automatically detects the new executor via component scan.

---

### How does the AI integration work?

The AI calls of the blocks go to the provider named by the block or the workflow, otherwise to the system default (`AiProviderRegistry`). For Claude they go through `HostClaudeService` (Codex and Pi likewise through `HostCodexService` and `HostPiService`):
- Starts `claude` as a host process via `ProcessBuilder`
- Prompt is written via stdin (not as a CLI argument)
- Removes `CLAUDE_CODE`/`CLAUDECODE` environment variables
- Supports `--model`, `--max-tokens`, `--append-system-prompt` flags

**Does Claude never run in a container?** It does: ad-hoc commands during a run start `claude` via `docker exec` in the project container, and it can be called from that container's terminal too (what it gets from the host is governed by `ProjectContainerClaudeAccess`). The blocks, by contrast, always call the AI CLIs on the host in the project directory, which needs no Docker.

---

### How does the template system work?

- 54 built-in templates as JSON under `src/main/resources/templates/builtin/`, read by `BuiltInTemplateLoader` (schema: `BuiltInTemplateDefinition`)
- Seeded on startup by `WorkflowTemplateSeeder` (idempotent)
- Name/description are i18n JSON maps: `{"de":"...", "en":"..."}`
- Frontend resolves language via `resolveTemplateName()` in `utils/templateUtils.ts`
- Built-in templates (`builtIn=true`) cannot be deleted

---

### How do I add a new database table?

1. Create a new Flyway migration: `src/main/resources/db/migration/V{N}__description.sql`
2. Create a JPA entity under `entity/` (with `@Entity`, `@Table`, Lombok annotations)
3. Create a repository interface under `repository/`
4. Create a service class under `service/`

**Important:** Keep numbering sequential, file format: `V{N}__short_description.sql`. Always determine the next number freshly: `ls ai-automation-backend/src/main/resources/db/migration | sort -V | tail -1`

---

### Where are real-time updates configured?

- **Backend:** `websocket/` package with WebSocket handlers
- **Frontend:** `useWebSocket` hook in `hooks/useWebSocket.ts`
- **Protocol:** STOMP over WebSocket (`/ws`)
- **Terminal:** `/ws/terminal` for container terminal access
- **Library:** `@stomp/stompjs` (frontend), Spring WebSocket (backend)

---

## Frontend

### How do I create a new page?

1. Create a new file under `src/pages/MyPage.tsx`
2. Add a route in `App.tsx`: `{ path: 'my-path', element: <MyPage /> }`
3. Use lazy loading: `const MyPage = lazy(() => import('./pages/MyPage').then(m => ({ default: m.MyPage })));`
4. Add a navigation link in the sidebar (`components/layout/Sidebar.tsx`)

---

### How do I access API data?

Always via TanStack Query hooks:

```typescript
// Use an existing hook
const { data: workflows, isLoading } = useWorkflows();

// Create a new hook (in hooks/)
export function useMyData() {
  return useQuery({
    queryKey: ['my-data'],
    queryFn: () => api.get<MyType[]>('/my-endpoint'),
  });
}
```

**Never** use `fetch()` directly -- always use the `api` client.

---

### How do I change the theme/styling?

- **TailwindCSS classes** for all styles -- prefer the colour tokens (`bg-surface`, `text-accent`, `text-danger`, ...) so themes apply
- **Dark mode / themes** via `themeStore` (`stores/themeStore.ts`), registry in `src/theme/themes.ts`
- **Tailwind configuration:** Tailwind 4 no longer uses a `tailwind.config.*` -- the configuration lives as `@theme inline` in `src/index.css`
- **Global styles** also in `src/index.css`

---

### How does i18n work?

- Translations in `src/i18n/de.ts` (German) and `src/i18n/en.ts` (English)
- **Both files must always be updated at the same time**
- Workflow templates: i18n via JSON maps in the database

---

## Tests

### How do I run backend tests?

```bash
cd ai-automation-backend
mvn test                    # All tests
mvn test -Dtest=WorkflowControllerTest  # Single test class
```

The tests live under `controller/`, `service/`, `executor/`, `integration/` and `security/` (as of 2026-09-18: 715 test classes). Current number: `find ai-automation-backend/src/test/java \( -name '*Test.java' -o -name '*Tests.java' \) | wc -l`

---

### How do I run E2E tests?

```bash
cd ai-automation-frontend

# Prerequisite: Backend must be running!
npm run cy:open     # Interactive (recommended for development)
npm run cy:run      # Headless (CI)
```

Cypress automatically detects the API key from the SQLite database.

Test files under: `cypress/e2e/` (projects, workflows, skills, schedules, settings, ...)

---

## Debugging

### How do I view backend logs?

Log level is configured in `application.yml`:
```yaml
logging:
  level:
    com.automation: DEBUG     # Application logs
    org.hibernate.SQL: WARN   # SQL queries (set to WARN to reduce noise)
```

### How do I debug WebSocket issues?

1. Browser DevTools → Network → WS tab
2. Check STOMP messages on `/ws`
3. Filter backend logs on the `websocket/` package

### How do I debug Claude CLI calls?

`HostClaudeService` logs at DEBUG level:
- Prompt length and working directory
- CLI command line
- Exit code and output length

---

## Deployment

### How do I build a Docker image for the backend?

```bash
cd ai-automation-backend
mvn clean package -DskipTests
docker build -f docker/Dockerfile.backend -t autopilot-backend:latest .
```

The image uses:
- Eclipse Temurin Java 21 (Alpine)
- ZGC (Generational) with 256MB–1GB heap
- Non-root user (`appuser`)
- Health check on `/api/v1/system/health`

### Is there a frontend Docker image?

Not currently. The frontend is served as a static build (`npm run build`). The `dist/` files can be hosted via any web server (nginx, Caddy, etc.). The Vite proxy is no longer needed in that case — the frontend must point directly to the backend.
