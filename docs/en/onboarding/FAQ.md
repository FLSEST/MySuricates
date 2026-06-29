<!-- last-sync: 2026-06-29 from: docs/onboarding/FAQ.md -->

# FAQ - Frequently Asked Questions and Issues

---

## Setup & Development

### How do I start the development environment?

```bash
# Terminal 1: Backend
cd ai-automation-backend
mvn spring-boot:run

# Terminal 2: Frontend
cd ai-automation-frontend
npm run dev
```

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

All 13 controllers are located under `ai-automation-backend/src/main/java/com/automation/controller/`. Each controller has a `@RequestMapping` with the API path.

The complete API reference is at `docs/api-referenz.md`.

---

### How do I add a new block type?

1. **Entity:** Add the new type to `BlockType` (frontend `types/index.ts`)
2. **Executor:** Create a new class implementing `BlockExecutor`:
   ```java
   @Component
   public class MyBlockExecutor implements BlockExecutor {
       @Override
       public boolean supports(String type) { return "my-type".equals(type); }

       @Override
       public StepResult execute(BlockDefinition block, ExecutionContext ctx) { ... }
   }
   ```
3. **Frontend:** Add the block type in `BlockTypeSelector.tsx`
4. **Optional:** Create a configuration dialog

Spring automatically detects the new executor via component scan.

---

### How does the AI integration work?

All AI calls go through `HostClaudeService`:
- Starts `claude` as a host process via `ProcessBuilder`
- Prompt is written via stdin (not as a CLI argument)
- Removes `CLAUDE_CODE`/`CLAUDECODE` environment variables
- Supports `--model`, `--max-tokens`, `--append-system-prompt` flags

**Why host instead of Docker?** The Claude CLI detects nested sessions and refuses to run inside containers.

---

### How does the template system work?

- 28 built-in templates in `BuiltInTemplates.java`
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

**Important:** Keep numbering sequential (currently V19), file format: `V{N}__short_description.sql`

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

- **TailwindCSS classes** for all styles (no custom CSS)
- **Dark mode** via `themeStore` (`stores/themeStore.ts`)
- **Tailwind configuration** in `tailwind.config.ts`
- **Global styles** in `src/index.css`

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

49 test files in: `controller/`, `service/`, `executor/blocks/`, `integration/`, `security/`

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
