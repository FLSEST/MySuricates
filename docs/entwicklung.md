> *Zuletzt aktualisiert: [auto-generated]*

# Entwicklung

## Voraussetzungen

| Tool | Version | Prüfung |
|---|---|---|
| Java (JDK) | 21+ | `java --version` |
| Maven | optional (Wrapper `mvnw` dabei) | `./mvnw --version` |
| Node.js | 20+ (`.nvmrc`) | `node --version` |
| npm | 10+ | `npm --version` |
| Docker | 24+ (optional) | `docker --version` |

Docker wird nur für Container-Execution-Workflow-Blöcke benötigt — das Backend bootet auch ohne
laufenden Docker-Daemon (der Docker-Client wird lazy aufgebaut). Für die Erstinstallation auf einem
neuen Rechner siehe **[GETTING-STARTED.md](GETTING-STARTED.md)** (inkl. `scripts/setup.sh`).

## Lokales Setup

### Backend starten

```bash
cd ai-automation-backend

# Dependencies laden und kompilieren
mvn clean compile

# Starten (Port 9090)
mvn spring-boot:run

# Alternativ: JAR bauen und ausführen
mvn clean package -DskipTests
java -jar target/ai-automation-backend-0.7.3-SNAPSHOT.jar
```

Das Backend erstellt automatisch `data/automation.db` beim ersten Start und führt Flyway-Migrationen aus.

### Frontend starten

```bash
cd ai-automation-frontend

# Dependencies installieren
npm install

# Dev-Server starten (Port 3000)
npm run dev
```

Der Vite-Dev-Server proxied automatisch `/api/*` und `/ws/*` an `localhost:9090`.

### Beide parallel starten

Terminal 1 (Backend):
```bash
cd ai-automation-backend && mvn spring-boot:run
```

Terminal 2 (Frontend):
```bash
cd ai-automation-frontend && npm run dev
```

Öffnen: `http://localhost:3000`

## Build

### Backend

```bash
cd ai-automation-backend

# Kompilieren
mvn clean compile

# JAR bauen (ohne Tests)
mvn clean package -DskipTests

# JAR bauen (mit Tests)
mvn clean package

# Ergebnis: target/ai-automation-backend-0.7.3-SNAPSHOT.jar
```

**Docs im Classpath:** `src/main/resources/docs/` (architektur, WORKFLOW-GUIDE, audit-workflow-protocol,
assistant-system, vision) ist ein Build-Artefakt — das `maven-resources-plugin` kopiert die Dateien in
der Phase `generate-resources` aus dem Repo-Root `docs/`. Das Verzeichnis ist git-ignoriert; Änderungen
gehören ausschließlich nach `docs/`. Der Assistent liest die Kopie zur Laufzeit per `ClassPathResource`.

### Frontend

```bash
cd ai-automation-frontend

# TypeScript-Check + Vite-Build
npm run build

# Ergebnis: dist/ (statische Dateien)
```

### Docker-Images

```bash
cd ai-automation-backend

# Backend-Image
mvn clean package -DskipTests
docker compose build backend

# Alle Agent-Images
docker compose --profile build-only build
```

## Builds neben dem laufenden Dev-Backend

Das Dev-Backend (`mvn spring-boot:run`) läuft mit `-cp target/classes` — einem **Verzeichnis**,
nicht einer JAR. Die JVM lädt Klassen über ihre gesamte Lebensdauer lazy von Platte nach.

Ein zweiter Maven-Lauf im selben Checkout schreibt `target/classes` neu und zieht der laufenden
JVM die Klassen unter den Füßen weg → `NoClassDefFoundError`. Der Fehler ist **sticky** (der
Classloader cached das Load-Failure), nur ein Neustart heilt ihn. Am 2026-07-27 sind so elf
Workflow-Runs gestorben (siehe `docs/plans/2026-07-27-ghost-run-root-cause-fixes.md`).

**Regel:** Kein `mvn` und kein `npm run build` im Haupt-Checkout, solange das Dev-Backend läuft.
Stattdessen:

```bash
WT=$(scripts/qa-worktree.sh prepare)   # legt/synct ~/.mysuricates/qa-worktree/autotrigger auf HEAD
cd "$WT/ai-automation-backend" && mvn test
```

Der Worktree ist detached auf `HEAD` des Haupt-Checkouts und bleibt zwischen Läufen bestehen
(`target/` und `node_modules/` werden wiederverwendet). **Uncommittete Änderungen sind darin nicht
enthalten** — was dort läuft, testet den committeten Stand. Der Workflow „AutoPilot Backend Quality
Gate" nutzt genau diesen Worktree. Dieselbe Regel gilt für `./scripts/staging.sh test-promote`,
das im Haupt-Checkout ein `mvn clean` ausführt.

## Tests

### Backend-Tests

```bash
cd ai-automation-backend

# Alle Tests ausführen
mvn test

# Einzelne Testklasse
mvn test -Dtest=ProjectControllerTest

# Tests mit Coverage
mvn test jacoco:report
```

Die Tests nutzen `spring-boot-starter-test` (JUnit 5, MockMvc) und `spring-security-test`. Jeder Controller hat eine eigene Testklasse in `src/test/java/com/automation/controller/`.

**Klassengrößen-Ratchet:** `architecture/SourceSizeRatchetTest` zählt die Zeilen aller Dateien unter `src/main/java` und schlägt fehl, sobald eine Datei über 300 Zeilen liegt, die nicht in der Allowlist des Tests steht — oder eine gelistete Datei über ihre eingetragene Obergrenze wächst. Die Liste kennt nur eine Richtung: Wer eine gelistete Klasse verkleinert, senkt ihre Obergrenze; fällt sie auf ≤300 Zeilen, fliegt der Eintrag raus. Neue Dateien über 300 Zeilen werden gesplittet, nicht eingetragen (Audit-TODO 1e248ab9). Keine externe Dependency, reiner JUnit-Test.

**Kein Scheduling im Integrationstest-Profil:** `@EnableScheduling` steht in `config/SchedulingConfig` mit `@Profile("!integration")`. Alle `@SpringBootTest`-Klassen laufen mit `@ActiveProfiles("integration")` und damit ohne Hintergrund-Jobs. Grund: Die Jobs schreiben sonst mitten im Testlauf in dieselbe SQLite-Datei — `HealthAutoTodoService` legt z. B. ein TODO an, sobald Stable oder Test nicht erreichbar ist. Solche Zeilen überleben den `@Transactional`-Rollback (eigener Thread, eigene Transaktion) und lassen global zählende Assertions davon abhängen, ob gerade ein Container läuft. Scheduled-Methoden werden in Tests direkt aufgerufen, ihre Logik bleibt abgedeckt. Wer einen neuen `@Scheduled`-Job baut, testet ihn also per direktem Methodenaufruf — nicht, indem er auf den Tick wartet.

### Frontend-Tests

```bash
cd ai-automation-frontend

# TypeScript-Typprüfung
npx tsc --noEmit

# Linting
npm run lint
```

### E2E-Tests (Cypress)

Die E2E-Tests laufen mit [Cypress](https://www.cypress.io/) gegen das **echte Backend** (kein Mocking). Testdaten werden per `cy.request()` direkt ueber die API angelegt und geloescht.

Es gibt zwei Zielmodi:
- **Dev**: Frontend-Proxy und Cypress-Helper zeigen auf `localhost:9090`
- **Test**: Frontend-Proxy und Cypress-Helper zeigen auf `localhost:8090`

**Wichtig:** Frontend und Cypress muessen immer mit demselben `CYPRESS_TARGET` gestartet werden, sonst erzeugt das UI Daten auf einer anderen Instanz als das Setup/Cleanup.

### Dev-Target (Standard)

**Voraussetzung:** Dev-Backend (Port 9090) und Frontend (Port 3000) laufen.

```bash
cd ai-automation-frontend

# Frontend startet mit Proxy auf Dev (:9090)
npm run dev

# Cypress interaktiv oeffnen (Browser-UI)
npm run cy:open

# Cypress headless ausfuehren (CI-Modus)
npm run cy:run

# Alias
npm run test:e2e
```

Der Dev-API-Key wird automatisch aus `ai-automation-backend/data/automation.db` gelesen, falls vorhanden.

### Test-Target (isoliert, keine fachlichen Testdaten in Dev)

**Voraussetzung:** Test-Instanz auf Port `8090` laeuft.

```bash
# Beispiel: Test-Instanz mit festem API-Key starten
cd ai-automation-backend
AUTOMATION_API_KEY=<test-api-key> docker compose -f docker-compose.test.yml up -d

# Optional: aktuelle Definitionen von Dev nach Test synchronisieren
curl -s -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{}'

cd ../ai-automation-frontend

# Frontend mit Proxy auf Test (:8090)
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run dev:e2e:test

# Cypress gegen dieselbe Test-Instanz
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run test:e2e:test
```

Optional koennen die Ziele auch direkt ueberschrieben werden:

```bash
CYPRESS_API_BASE_URL=http://localhost:8090/api/v1
CYPRESS_WS_BASE_URL=ws://localhost:8090
```

### Testdaten und Cleanup

- **Dev-Target:** erzeugte Cypress-Projekte, Workflows, Chains und Runs landen auf Dev
- **Test-Target:** dieselben Daten landen auf der Test-Instanz
- `cy.apiCleanup()` raeumt nur Cypress-Testdaten mit dem Prefix `Cypress` auf
- Fuer die Test-Instanz kann zusaetzlich `POST /api/v1/testing/reset` verwendet werden, wenn ein kompletter Reset gewuenscht ist

**Teststruktur:**

```
cypress/
  support/
    e2e.ts          ← Globaler beforeEach (Locale auf Englisch setzen)
    commands.ts     ← Custom Commands (apiCreateProject, shouldShowToast, navTo, ...)
    api.ts          ← HTTP-Helpers für Testdaten-Setup (CRUD gegen Backend-API)
  e2e/
    activity-log.cy.ts       ← Activity-Log-Ansicht
    assistant-panel.cy.ts    ← Assistent-Panel
    chains.cy.ts             ← Chains CRUD und Ausführung
    dashboard.cy.ts          ← Stat-Cards, Charts, Quick Actions
    navigation.cy.ts         ← Sidebar-Navigation, Routing, Collapse/Expand
    project-detail.cy.ts     ← Projekt-Detailseite
    projects.cy.ts           ← Projekte CRUD (Create, Search, Detail, Delete)
    responsive-layout.cy.ts  ← Responsive Layout
    runs.cy.ts               ← Workflow-Runs-Ansicht
    schedules.cy.ts          ← Schedules CRUD, Toggle, Tabelle
    settings.cy.ts           ← Theme, Sprache, API-Key, System Health
    skills.cy.ts             ← Skill-Katalog, Filter, Detail-Slide-Over
    staging.cy.ts            ← Staging-Management
    todos.cy.ts              ← Todos CRUD
    webhooks.cy.ts           ← Webhooks CRUD, Toggle, Copy-Button
    wishes.cy.ts             ← Wishes
    workflow-execution.cy.ts ← Workflow-Ausführung
    workflows.cy.ts          ← Workflows CRUD, Template-Auswahl, Detail, Blocks, Skills
```

**Konventionen:**
- Englische Locale wird per `localStorage` vor jedem Test erzwungen
- Selektoren nutzen `aria-label`, Button-Text, `h1`-Text, `placeholder`, `role="switch"` (keine `data-testid`)
- `before()` / `after()` Hooks sorgen für saubere Testdaten via `cy.apiCleanup()`

`scripts/cleanup-runs.sh` prüft vor jedem Zugriff die Datei mit `PRAGMA quick_check` und bricht bei
Befund ab (2026-08-04 hat ein DELETE auf einer bereits korrupten DB den Schaden vergrößert). Mit
`--db <pfad>` lässt sich das Skript gegen eine andere Datei richten. Skript-Tests liegen unter
`scripts/tests/` und laufen ohne Backend: `bash scripts/tests/test-cleanup-runs.sh`.

## Versionierung & Releases

### Wo die Version definiert ist

| Datei | Format | Beispiel |
|---|---|---|
| `ai-automation-backend/pom.xml` | `X.Y.Z-SNAPSHOT` | `0.7.3-SNAPSHOT` |
| `ai-automation-frontend/package.json` | `X.Y.Z` | `0.7.3` |
| `application.yml` | via `@project.version@` (Maven-Filtering) | automatisch |

Das Dashboard zeigt die Version über den `/api/v1/system/info`-Endpunkt an.

### Release-Script

```bash
./scripts/release.sh           # Patch: 0.1.0 → 0.1.1 (Standard)
./scripts/release.sh minor     # Minor: 0.1.1 → 0.2.0
./scripts/release.sh major     # Major: 0.2.0 → 1.0.0
```

Das Script:
1. Liest die aktuelle Version aus `pom.xml` (ohne `-SNAPSHOT`)
2. Berechnet die neue Version (Segment hochzählen)
3. Aktualisiert `pom.xml` (`X.Y.Z-SNAPSHOT`) und `package.json` (`X.Y.Z`)
4. Erstellt Git-Commit: `Release vX.Y.Z`
5. Setzt Git-Tag: `vX.Y.Z`

**Voraussetzung:** Sauberer Working Tree (keine uncommitteten Änderungen).

### Zusammenspiel mit Staging

Nach einem Release kann `./scripts/staging.sh promote` ausgeführt werden, um die neue Version in die Stable-Umgebung zu deployen. Die Settings-Seite zeigt dann einen Versionsvergleich (Dev vs. Stable) und empfiehlt ein Promote, wenn die Versionen abweichen.

### Git-Tags

Es gibt zwei Arten von Git-Tags:

| Tag-Format | Erstellt durch | Zweck |
|---|---|---|
| `v0.1.1` | `release.sh` | Semver-Release-Markierung |
| `stable-20250225-143022` | `staging.sh promote` | Deployment-Zeitstempel |

## Projektkonventionen

### Backend

| Konvention | Beschreibung |
|---|---|
| **Entities** | JPA-Entities mit Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **DTOs** | Java Records (`record` statt Klassen) für Request- und Response-Objekte |
| **Mapper** | MapStruct für Entity ↔ DTO Konvertierung (automatische Codegenerierung) |
| **IDs** | UUID als String (`@GeneratedValue(strategy = GenerationType.UUID)`) |
| **JSON-Felder** | `@Convert(converter = JsonMapConverter.class)` für `Map<String, Object>` in TEXT-Spalten |
| **Auditing** | `@CreatedDate`, `@LastModifiedDate` über Spring Data JPA Auditing |
| **Validierung** | Jakarta Validation (`@NotBlank`, `@NotNull`, `@NotEmpty`) auf DTOs |
| **Exceptions** | `GlobalExceptionHandler` mit `@RestControllerAdvice`, einheitliches Fehlerformat |
| **Logging** | SLF4J mit `@Slf4j` (Lombok) |
| **Transaktionen** | `@Transactional` auf Service-Methoden |

### Frontend

| Konvention | Beschreibung |
|---|---|
| **Styling** | Tailwind CSS mit CSS-Variablen, keine CSS-Module oder styled-components |
| **State** | Server-State via TanStack Query, Client-State via Zustand |
| **Komponenten** | Funktionskomponenten mit benannten Exports |
| **Hooks** | Custom Hooks für jede API-Ressource |
| **Typen** | Zentral in `src/types/index.ts` definiert |
| **i18n** | Typsichere Übersetzungsobjekte (kein Runtime-Key-Lookup) |
| **Icons** | Lucide React (konsistentes Icon-Set) |
| **Forms** | Kontrollierte Komponenten mit React State |

### Annotation-Prozessoren

Das Backend nutzt drei Annotation-Prozessoren, die in der Compiler-Konfiguration (`pom.xml`) korrekt geordnet sein müssen:

1. **Lombok** — Generiert Getter, Setter, Builder, etc.
2. **MapStruct** — Generiert Mapper-Implementierungen
3. **lombok-mapstruct-binding** — Brücke zwischen Lombok und MapStruct

## Architektur-Notizen für Entwickler

### Backend-Struktur

Das Backend umfasst **38 REST-Controller** und **17 Block-Typen**:

**Controller:** ActivityLog, Assistant, Audit, Chain, ChainTemplate, CompletionTrigger, Conversion, DeadLetter, Dialog, Docs, Execution, Integration, Learning, Notification, OnboardingProfile, PageSuggestion, Project, Reports, Schedule, Search, Skill, Staging, System, SystemAccess, SystemAiProvider, SystemMaintenance, SystemSettings, Testing, Todo, TodoTrigger, Webhook, Wish, Workflow, WorkflowDebug, WorkflowFactory, WorkflowMemory, WorkflowPool, WorkflowTemplate

**Block-Typen:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`, `todo-create`, `todo-update`, `todo-close`

**Flyway-Migrationen:** V1–V68 (SQLite)

### Gate Status System

Workflow-Blöcke können Quality-Gate-Marker ausgeben:
```
gate_status: GREEN|YELLOW|RED
gate_category: <kategorie>
gate_description: <beschreibung>
```
- YELLOW → RunStatus `COMPLETED_WITH_ERRORS`
- RED → `AutoTodoService` erstellt ein TODO mit HIGH-Priorität
- `GateStatusOutputParser` parst die Marker aus Block-Ausgaben

### Chains

Chains verketten mehrere Workflows sequenziell:
- `ChainService` / `ChainExecutor` für CRUD und Ausführung
- Cross-Project: Chain gehört keinem Projekt
- `previous_chain_output` als Context-Variable zwischen Entries
- WebSocket-Status-Broadcasts an `/topic/chains/{chainRunId}/status`

### Workflow Memory

Workflows können Wissen über Runs hinweg akkumulieren:
- `WorkflowMemoryService.extractKnowledge()` — AI-basierte Extraktion via `KnowledgeSummarizer`
- Aktive Einträge werden in nachfolgende Runs injiziert
- Provider: `CLAUDE` (Standard) oder `CODEX`

### Soft-Delete Pattern

- **Workflows:** `@SQLRestriction("deleted_at IS NULL")` auf der Entity-Klasse — automatisch aus allen Queries ausgeblendet
- **Projekte:** `archived_at IS NOT NULL` = archiviert — Filterung via Repository-Methoden (kein `@SQLRestriction`, da sonst eager-loaded `Workflow.project`-Assoziationen brechen würden)

### Seeder

- **SkillSeeder:** Legt 19 Built-in-Skills beim Start an (idempotent) und registriert sie im `SkillRegistry`
- **WorkflowTemplateSeeder:** Legt 66 Built-in-Templates beim Start an (idempotent, Update bei Version-Bump)
- Template-Definitionen als JSON unter `src/main/resources/templates/builtin/`

## Abhaengigkeits-Meldungen (Frontend)

`npm audit` im Frontend meldete am 2026-09-12 zwanzig Schwachstellen. Nach dem Update sind es
sieben, alle ohne verfuegbaren Fix innerhalb der deklarierten Versionsbereiche.

**Abgetragen:** die Laufzeit-Kette zuerst. `dompurify` kam ueber
`@monaco-editor/react` → `monaco-editor` und stand auf 3.4.12, dem oberen Rand des
verwundbaren Bereichs (GHSA-55q2-fjhq-7xh7, XSS ueber einen abgehaengten Teilbaum). Ein blankes
Update bewegt es nicht, weil ein `overrides`-Eintrag die Version pinnt — der Eintrag steht jetzt
auf `^3.4.13`, installiert ist 3.4.15. Danach `npm audit fix` ohne `--force` fuer alles Weitere
in Range.

**Bewusst offen:**

| Paket | Warum es bleibt |
|---|---|
| `cypress` und seine Transitiven (`@cypress/request`, `uuid`, `extract-zip`, `qs`) | Der Fix ist Cypress 15, ein Major-Sprung. Reines Testwerkzeug, laeuft nicht im ausgelieferten Frontend und nicht in der CI. Eigener Vorgang. |
| `react-router-dom` / `react-router` | Open Redirect ueber Backslash in `<Link>` und `useNavigate` (bis 7.17.0). Der Fix ist react-router-dom 7, eine Framework-Migration. **Der erreichbare Pfad ist stattdessen an der Quelle geschlossen:** der Assistent rendert LLM-Text als Markdown und reichte bisher jedes `href`, das mit `/` beginnt, an `navigate()` weiter — `/\evil.example` haette die App verlassen. `isInternalAppPath()` in `src/components/assistantPanelUtils.ts` weist protokollrelative und Backslash-Pfade ab, mit Test. |

Nachprüfen mit `npm audit`; nach dem Abtragen sind `npm run build`, `npm test` und
`npx tsc --noEmit` gruen und `npm run lint` unveraendert bei 0 Fehlern.

## Bekannte Einschränkungen

### SQLite

- **Concurrent Writes:** SQLite erlaubt nur einen Schreibzugriff gleichzeitig. HikariCP ist auf Pool-Size=1 konfiguriert, um alle DB-Operationen zu serialisieren und `SQLITE_BUSY`-Fehler unter parallelen Workflow-Runs zu vermeiden.
- **Keine nativen Typen:** Booleans als INTEGER, Timestamps als TEXT, JSON als TEXT.
- **Kein ALTER COLUMN:** Schema-Änderungen sind eingeschränkt (nur ADD COLUMN).
- **NIEMALS direkt in die DB schreiben** (via `sqlite3` CLI oder SQL). Immer REST-API oder Service/Repository-Layer verwenden — direkte Schreibzugriffe umgehen den Hibernate-Cache.

### Docker-Abhängigkeit

- Das Backend bootet auch ohne laufenden Docker-Daemon (der Docker-Client wird lazy aufgebaut).
- Ohne Docker können keine Container-Execution-Blöcke in Workflows ausgeführt werden.
- Agent-Images müssen vorher gebaut sein (`docker compose --profile build-only build`).
- **Claude CLI läuft immer auf dem Host**, nicht in Containern (vermeidet Nested-Session-Erkennung).

### Sicherheit

- Der Docker-Socket-Mount gibt dem Backend Host-Level Docker-Zugriff. In Produktionsumgebungen sollte mindestens API-Key-Auth aktiviert sein.
- CORS ist standardmäßig offen (`*`). Für Produktion sollten spezifische Origins konfiguriert werden.
- Rate Limiting ist standardmäßig aktiv (120 req/min). Konfigurierbar zur Laufzeit in Settings → Execution.

### Frontend

- Kein Offline-Modus (PWA-Shell wird gecacht, aber API-Daten nicht).
- Monaco Editor lädt externe Bundles (erfordert Internetverbindung beim ersten Laden).
