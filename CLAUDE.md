# AI Automation Platform (AutoPilot)

AI-powered workflow automation platform with a skill system, Docker-based execution, and a React frontend.

## Project Structure

```
ai-automation-backend/     Spring Boot 3.2 (Java 21) REST API
  src/main/java/com/automation/
    controller/            REST controllers (Workflow, Skill, Execution, Learning, Schedule, Webhook, System, Project)
    entity/                JPA entities (Workflow, Skill, Project, Container, ScheduledWorkflow, WebhookTrigger, WorkflowRun, ...)
    dto/                   Request/response DTOs
    mapper/                MapStruct mappers (Entity <-> DTO)
    repository/            Spring Data JPA repositories
    service/               Business logic
    executor/              Docker-based skill execution
    security/              API key auth, rate limiting, Tailscale
    config/                Spring configuration
    websocket/             WebSocket for real-time updates
  src/main/resources/
    db/migration/          Flyway migrations (V1-V9, SQLite)
    application.yml        App config
  docker/                  Dockerfiles for agent containers (Python, Node, Go)

ai-automation-frontend/    React 18 SPA (TypeScript)
  src/
    api/client.ts          Shared API client (fetch-based, /api/v1 prefix)
    pages/                 Route pages (Dashboard, Projects, Workflows, Skills, Schedules, Webhooks, Settings, ...)
    components/            UI components (ui/, layout/, dashboard/, workflow/, shared/)
    hooks/                 TanStack Query hooks (useWorkflows, useSkills, useProjects, useSchedules, useWebhooks, ...)
    stores/                Zustand stores (settings, sidebar, theme, toast)
    i18n/                  Translations (de.ts, en.ts)
    types/index.ts         Shared TypeScript types
  cypress/                 E2E tests (Cypress 13)
```

## Build & Run

```bash
# Backend
cd ai-automation-backend
mvn clean package                        # Build JAR
mvn test                                 # Run tests
mvn spring-boot:run                      # Start dev server (port 9090)

# Frontend
cd ai-automation-frontend
npm install                              # Install dependencies
npm run dev                              # Start dev server (port 3000, proxies /api to :9090)
npm run build                            # Production build (tsc + vite)
npm run lint                             # ESLint

# E2E Tests
cd ai-automation-frontend
npm run test:e2e                         # Run Cypress headless
npm run cy:open                          # Open Cypress UI
```

## Key Conventions

- **Backend ORM**: Lombok for boilerplate, MapStruct for entity-DTO mapping
- **Database**: SQLite with Flyway migrations. Naming: `V{N}__description.sql`
- **API prefix**: All endpoints under `/api/v1`
- **Frontend state**: Zustand for client state (settings, theme, sidebar), TanStack Query for server state
- **API client**: Singleton `api` instance in `src/api/client.ts` — all hooks use this
- **i18n**: German (`de.ts`) and English (`en.ts`), both must be updated together
- **Styling**: TailwindCSS utility classes, dark mode support via theme store
- **PWA**: Vite PWA plugin configured, app name "AutoPilot"
- **WebSocket**: Backend sends real-time updates via `/ws`, frontend connects via `useWebSocket` hook

## Architecture Notes

- Workflows consist of ordered steps; each step executes a Skill inside a Docker container
- Skills are categorized (see `SkillCategory` enum) and run in isolated containers (Python/Node/Go)
- Docker containers have resource limits: 4GB memory, CPU quota, 100 PIDs, 60min idle timeout
- Security is optional (disabled by default): API key auth via `X-API-Key` header, rate limiting
