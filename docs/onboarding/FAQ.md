# FAQ - Haeufige Fragen und Probleme

---

## Setup & Entwicklung

### Wie starte ich die Entwicklungsumgebung?

```bash
# Terminal 1: Backend (aus dem Repo-Root)
./scripts/start-autotrigger-dev.sh start

# Terminal 2: Frontend
cd ai-automation-frontend
npm run dev
```

> `mvn spring-boot:run` funktioniert nicht: der `RuntimeClasspathGuard` beendet einen Start aus `target/classes` mit Exit-Code 2. Das Skript baut das JAR und startet es aus einer Kopie.

Backend laeuft auf Port 9090, Frontend auf Port 3000. Vite proxied `/api` und `/ws` automatisch zum Backend.

---

### Muss ich Docker installiert haben?

**Fuer die Grundentwicklung:** Nein. Backend und Frontend laufen direkt auf dem Host. Die Claude CLI wird ebenfalls nativ ausgefuehrt.

**Fuer Skill-Ausfuehrung in Containern:** Ja. Die Agent-Container (Python, Node.js, Go) benoetigen Docker. Baue die Images mit:

```bash
cd ai-automation-backend
docker compose --profile build-only build
```

---

### Wo liegt die Datenbank?

SQLite-Datei unter `ai-automation-backend/data/automation.db`. Wird beim ersten Start automatisch erstellt. Flyway fuehrt alle Migrationen aus.

**Datenbank zuruecksetzen:** Einfach `data/automation.db` loeschen und Backend neu starten.

---

### Warum schlagen API-Requests mit 401 fehl?

Security ist standardmaessig aktiviert (`AUTOMATION_SECURITY_ENABLED=true`). Loesungen:

1. **API-Key im Frontend eintragen:** Settings → API Key
2. **Security deaktivieren:** `export AUTOMATION_SECURITY_ENABLED=false` vor dem Backend-Start
3. **API-Key als Umgebungsvariable:** `export AUTOMATION_API_KEY=mein-key`

---

## Architektur & Code

### Wo finde ich die REST-Endpunkte?

Die REST-Controller liegen unter `ai-automation-backend/src/main/java/com/automation/controller/` (Stand 2026-09-18: 43 Klassen mit `@RestController`). Jeder Controller hat ein `@RequestMapping` mit dem API-Pfad; die Zahl frisch erheben mit `find ai-automation-backend/src/main/java -name '*Controller.java' | wc -l`.

Die vollstaendige API-Referenz liegt unter `docs/api-referenz.md`.

---

### Wie fuege ich einen neuen Block-Typ hinzu?

1. **Entity:** Neuen Typ zu `BlockType` (Frontend `types/index.ts`) hinzufuegen
2. **Executor:** Neue Klasse erstellen die `BlockExecutor` implementiert:
   ```java
   @Component
   public class MeinBlockExecutor implements BlockExecutor {
       @Override
       public boolean supports(String type) { return "mein-typ".equals(type); }

       @Override
       public StepResult execute(BlockDefinition block, ExecutionContext ctx) { ... }
   }
   ```
3. **Listen pflegen:** Der Typ muss in allen drei Listen stehen -- `BLOCK_TYPES` in `ai-automation-frontend/src/types/index.ts`, `BlockTypesIndexSection` (Assistent-Kontext) und `AssistantToolValidator.KNOWN_BLOCK_TYPES`. `BlockTypeListsGuardTest` (Backend) und `types/__tests__/blockTypes.test.ts` (Frontend) erzwingen den Gleichstand mit den Executoren.
4. **Optional:** Konfigurations-Dialog erstellen

Spring erkennt den neuen Executor automatisch (Component Scan).

---

### Wie funktioniert die KI-Integration?

Alle KI-Aufrufe laufen ueber `HostClaudeService`:
- Startet `claude` als Host-Prozess via `ProcessBuilder`
- Prompt wird ueber stdin geschrieben (nicht als CLI-Argument)
- Entfernt `CLAUDE_CODE`/`CLAUDECODE` Umgebungsvariablen
- Unterstuetzt `--model`, `--max-tokens`, `--append-system-prompt` Flags

**Warum Host statt Docker?** Claude CLI erkennt verschachtelte Sessions und verweigert die Ausfuehrung innerhalb von Containern.

---

### Wie funktioniert das Template-System?

- 67 Built-in Templates als JSON unter `src/main/resources/templates/builtin/`, eingelesen von `BuiltInTemplateLoader` (Schema: `BuiltInTemplateDefinition`)
- Werden beim Start durch `WorkflowTemplateSeeder` geseedet (idempotent)
- Name/Description sind i18n JSON-Maps: `{"de":"...", "en":"..."}`
- Frontend loest Sprache ueber `resolveTemplateName()` in `utils/templateUtils.ts` auf
- Built-in Templates (`builtIn=true`) sind nicht loeschbar

---

### Wie fuege ich eine neue Datenbank-Tabelle hinzu?

1. Neue Flyway-Migration erstellen: `src/main/resources/db/migration/V{N}__beschreibung.sql`
2. JPA-Entity unter `entity/` erstellen (mit `@Entity`, `@Table`, Lombok Annotations)
3. Repository Interface unter `repository/` erstellen
4. Service-Klasse unter `service/` erstellen

**Wichtig:** Nummerierung fortlaufend (hoechste vorhandene Migration: V73), Dateiformat: `V{N}__kurze_beschreibung.sql`. Naechste Nummer immer frisch ermitteln: `ls ai-automation-backend/src/main/resources/db/migration | sort -V | tail -1`

---

### Wo werden Echtzeit-Updates konfiguriert?

- **Backend:** `websocket/` Package mit WebSocket-Handlern
- **Frontend:** `useWebSocket` Hook in `hooks/useWebSocket.ts`
- **Protokoll:** STOMP ueber WebSocket (`/ws`)
- **Terminal:** `/ws/terminal` fuer Container-Terminal-Zugriff
- **Library:** `@stomp/stompjs` (Frontend), Spring WebSocket (Backend)

---

## Frontend

### Wie erstelle ich eine neue Seite?

1. Neue Datei unter `src/pages/MeineSeite.tsx` erstellen
2. Route in `App.tsx` hinzufuegen: `{ path: 'mein-pfad', element: <MeineSeite /> }`
3. Lazy Loading nutzen: `const MeineSeite = lazy(() => import('./pages/MeineSeite').then(m => ({ default: m.MeineSeite })));`
4. Navigation-Link in der Sidebar hinzufuegen (`components/layout/Sidebar.tsx`)

---

### Wie greife ich auf API-Daten zu?

Immer ueber TanStack Query Hooks:

```typescript
// Existierenden Hook nutzen
const { data: workflows, isLoading } = useWorkflows();

// Neuen Hook erstellen (in hooks/)
export function useMeineDaten() {
  return useQuery({
    queryKey: ['meine-daten'],
    queryFn: () => api.get<MeinTyp[]>('/mein-endpunkt'),
  });
}
```

**Niemals** `fetch()` direkt verwenden -- immer ueber den `api` Client.

---

### Wie aendere ich das Theme/Styling?

- **TailwindCSS Klassen** fuer alle Styles -- bevorzugt die Farb-Token (`bg-surface`, `text-accent`, `text-danger`, ...), damit Themes greifen
- **Dark Mode / Themes** ueber `themeStore` (`stores/themeStore.ts`), Registry in `src/theme/themes.ts`
- **Tailwind-Konfiguration:** Tailwind 4 hat keine `tailwind.config.*` mehr -- die Konfiguration steht als `@theme inline` in `src/index.css`
- **Globale Styles** ebenfalls in `src/index.css`

---

### Wie funktioniert i18n?

- Uebersetzungen in `src/i18n/de.ts` (Deutsch) und `src/i18n/en.ts` (Englisch)
- **Beide Dateien muessen immer gleichzeitig aktualisiert werden**
- Workflow-Templates: i18n ueber JSON-Maps in der Datenbank

---

## Tests

### Wie fuehre ich Backend-Tests aus?

```bash
cd ai-automation-backend
mvn test                    # Alle Tests
mvn test -Dtest=WorkflowControllerTest  # Einzelne Testklasse
```

Die Tests liegen unter `controller/`, `service/`, `executor/`, `integration/` und `security/` (Stand 2026-09-18: 715 Testklassen). Aktuelle Zahl: `find ai-automation-backend/src/test/java \( -name '*Test.java' -o -name '*Tests.java' \) | wc -l`

---

### Wie fuehre ich E2E-Tests aus?

```bash
cd ai-automation-frontend

# Voraussetzung: Backend muss laufen!
npm run cy:open     # Interaktiv (empfohlen fuer Entwicklung)
npm run cy:run      # Headless (CI)
```

Cypress erkennt automatisch den API-Key aus der SQLite-Datenbank.

Test-Dateien unter: `cypress/e2e/` (projects, workflows, skills, schedules, settings, ...)

---

## Debugging

### Wie sehe ich Backend-Logs?

Log-Level ist in `application.yml` konfiguriert:
```yaml
logging:
  level:
    com.automation: DEBUG     # Anwendungslogs
    org.hibernate.SQL: WARN   # SQL-Queries (auf WARN fuer weniger Rauschen)
```

### Wie debugge ich WebSocket-Probleme?

1. Browser DevTools → Network → WS Tab
2. STOMP-Nachrichten pruefen auf `/ws`
3. Backend-Logs auf `websocket/` Package filtern

### Wie debugge ich Claude CLI Aufrufe?

`HostClaudeService` loggt auf DEBUG-Level:
- Prompt-Laenge und Arbeitsverzeichnis
- CLI-Befehlszeile
- Exit-Code und Ausgabe-Laenge

---

## Deployment

### Wie baue ich ein Docker-Image fuer das Backend?

```bash
cd ai-automation-backend
mvn clean package -DskipTests
docker build -f docker/Dockerfile.backend -t autopilot-backend:latest .
```

Das Image nutzt:
- Eclipse Temurin Java 21 (Alpine)
- ZGC (Generational) mit 256MB-1GB Heap
- Non-Root User (`appuser`)
- Health-Check auf `/api/v1/system/health`

### Gibt es ein Frontend-Docker-Image?

Aktuell nicht. Das Frontend wird als statisches Build (`npm run build`) ausgeliefert. Die `dist/`-Dateien koennen ueber einen beliebigen Webserver (nginx, Caddy, etc.) gehostet werden. Der Vite-Proxy entfaellt dann -- das Frontend muss direkt auf das Backend zeigen.
