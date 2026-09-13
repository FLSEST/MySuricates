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
# Aus dem Repo-Root — baut ein Fat-JAR, kopiert es nach ~/.local/state/autotrigger-dev/runtime/
# und startet es von dort (Port 9090). Idempotent; startet auch das Frontend.
scripts/start-autotrigger-dev.sh            # start
scripts/start-autotrigger-dev.sh restart    # Code-Änderungen übernehmen: stop → JAR bauen → start
scripts/start-autotrigger-dev.sh stop
```

`mvn spring-boot:run` ist **absichtlich blockiert**: Das Backend bricht beim Start aus `target/classes`
mit einer Meldung ab (`RuntimeClasspathGuard`), siehe [Builds neben dem laufenden Dev-Backend](#builds-neben-dem-laufenden-dev-backend).
Escape-Hatch für Sonderfälle: `-Dautomation.runtime.allow-classes-dir=true`.

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
scripts/start-autotrigger-dev.sh
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

Das Dev-Backend läuft aus einer **JAR-Kopie** unter `~/.local/state/autotrigger-dev/runtime/current.jar`
(`scripts/start-autotrigger-dev.sh`), nicht aus `target/classes`. Grund: Eine JVM mit `-cp target/classes`
lädt Klassen über ihre gesamte Lebensdauer lazy von Platte. Jeder Build, der dieses Verzeichnis umschreibt,
zieht ihr die Klassen unter den Füßen weg → `NoClassDefFoundError`, sticky (die JVM cached den
Resolution-Fehler), nur ein Neustart heilt ihn. Schreiber gab es drei: Maven aus Workflows/Sessions
(2026-07-27: elf tote Runs), `staging.sh` und die **IDE** — VS Codes Java-Erweiterung (Eclipse-Compiler)
hat am 2026-09-13 über Nacht die javac-Hilfsklassen für Enum-Switches gelöscht (drei tote Scheduled Runs)
und morgens MapStruct-Implementierungen durch Fehler-Stubs ersetzt (498 falsche Test-Errors).

Drei Schutzschichten, alle mechanisch:

1. **JAR-Kopie** — `restart` baut `mvn clean package -DskipTests`, kopiert das JAR in den State-Ordner
   (Name `backend-<zeit>-<sha>[-dirty].jar`, die letzten zwei bleiben) und startet `java -jar` mit
   cwd `ai-automation-backend` (`./data/automation.db` bleibt). `restart` prüft vorher über die API,
   ob Workflow-Runs laufen, und bricht dann ab (`--force` übersteuert).
2. **IDE-Profil** — `pom.xml`-Profil `ide-eclipse-output` (aktiv nur, wenn m2e die Property
   `m2e.version` setzt) legt den IDE-Build nach `target-ide/`. VS Code/Eclipse schreiben damit nie in
   `target/`. Prüfen: `mvn help:evaluate -Dexpression=project.build.directory -DforceStdout -Dm2e.version=1.0`.
3. **Fail-fast** — `RuntimeClasspathGuard` in `AiAutomationApplication.main` beendet einen Start aus
   `target/classes` mit Exit 2. `/api/v1/system/info` zeigt `runtimeMode` (`jar` | `classes-dir`);
   im Escape-Hatch-Fall (`-Dautomation.runtime.allow-classes-dir=true` bzw.
   `AUTOMATION_ALLOW_CLASSES_DIR=true`) warnt das ActivityLog beim Start.

Damit sind `mvn test`, `mvn clean` und `npm run build` im Haupt-Checkout für das laufende Backend
harmlos. **Für Workflows bleibt der QA-Worktree der richtige Ort**, weil zwei gleichzeitige Maven-Läufe
im selben `target/` (z. B. Scheduled Run + Claude-Session) einander falsche Ergebnisse produzieren:

```bash
WT=$(scripts/qa-worktree.sh prepare)   # legt/synct ~/.mysuricates/qa-worktree/autotrigger auf HEAD
cd "$WT/ai-automation-backend" && mvn test
```

Der Worktree ist detached auf `HEAD` des Haupt-Checkouts und bleibt zwischen Läufen bestehen
(`target/` und `node_modules/` werden wiederverwendet). **Uncommittete Änderungen sind darin nicht
enthalten** — was dort läuft, testet den committeten Stand. Die Workflows „AutoPilot Backend Quality
Gate" und „self-care-platform-health" nutzen genau diesen Worktree.

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

**Integration-Encryption-Key im Testprofil auf Temp-Pfad gepinnt:** Genau wie `integration.db.path` setzt `IntegrationTestDbSessionListener` seit 2026-09-12 zusätzlich `integration.key.path` (per-JVM-Temp-Datei, im Teardown gelöscht), auf das `application-integration.yml` `automation.integration.key-file` verweist — ohne diesen Pin generiert `IntegrationEncryptionService.init()` bei jedem `@SpringBootTest` mit leeren `app_settings` sonst einen frischen AES-Key ins echte `~/.autotrigger/` des Entwicklers (geschehen 2026-08-06, hat den echten Key monatelang überschattet).

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

**Flyway-Migrationen:** V1–V69 (SQLite)

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

## Hygiene-Gate

`scripts/ci/public-hygiene.sh` prueft, was in ein oeffentliches Repository geriete. Die Regeln —
verbotene Literale, Sicherungsdatei-Muster, Allowlist-Pfad, Schwellwerte — stehen in
`scripts/lib/hygiene-rules.sh` und werden von der CI und vom pre-commit-Hook gemeinsam gelesen.

| Modus | Wer | Prueft | Verhalten |
|---|---|---|---|
| `--tracked` (Default) | CI | getrackter Bestand | Fehler |
| `--worktree` | lokal | zusaetzlich Dateien, die weder getrackt noch ignoriert sind | Warnung |
| `--staged` | pre-commit-Hook | nur gestagte Aenderungen | Fehler |

Geprueft wird:

1. **Getrackt, aber ignoriert.** Frueher eine handgepflegte Liste von zehn Verzeichnisnamen, jetzt
   `git ls-files --cached --ignored --exclude-standard`. Damit ist jede kuenftige Ignore-Regel
   automatisch abgedeckt. Bewusste Ausnahmen stehen mit Begruendung in
   `scripts/ci/hygiene-allowlist.txt` — nur Pfade, nie Musterliterale, damit die Allowlist selbst
   nichts preisgibt.
2. **Verbotene Literale** in getrackten Dateien bzw. in hinzugefuegten Zeilen.
3. **Pool-Neutralitaet** ueber `scripts/ci/pool-neutrality.sh`. Dasselbe Skript nutzt das
   Built-in-Template `pool-neutrality-audit` (dort mit `--all`, weil es den Pool einer laufenden
   Instanz prueft und nicht nur den getrackten Release-Bestand).
4. **Repo-Gewicht**: zwei getrackte Pfade mit identischer Blob-Pruefsumme ab 256 KiB sind ein
   Fehler, eine einzelne getrackte Datei ueber 5 MiB eine Warnung.
5. **Streunende Sicherungsdateien** (nur `--worktree`): weder getrackt noch ignoriert. Beim Stagen
   ist derselbe Fund ein Fehler.
6. **`.gitignore` verraet keine privaten Dateinamen.** Die Datei ist getrackt und geht mit der
   Veroeffentlichung mit — auch ihre Kommentare. Nennt sie einen Namen, der in `.git/info/exclude`
   steht, ist das ein Verstoss. In der CI gibt es die exclude-Datei nicht, dort faellt die Pruefung
   still aus.

Den Hook installiert `scripts/install-hooks.sh` ueber `core.hooksPath` (laeuft in `setup.sh` mit).

### Wenn das Gate anschlaegt

Kein Bypass per Reflex. `git commit --no-verify` existiert, aber ein Treffer ist fast immer echt:
ein pauschales `git add` hat etwas mitgenommen, das nicht ins Repository gehoert. Erst schauen,
was gestaged ist.

## Abhaengigkeits-Meldungen (Frontend)

`npm audit` im Frontend meldet nach den Updates vom 2026-09-12 **0 Schwachstellen**.
Nach einer ersten Reduktion von zwanzig auf sieben wurden auch die beiden notwendigen
Major-Upgrades durchgefuehrt.

**Abgetragen:** die Laufzeit-Kette zuerst. `dompurify` kam ueber
`@monaco-editor/react` → `monaco-editor` und stand auf 3.4.12, dem oberen Rand des
verwundbaren Bereichs (GHSA-55q2-fjhq-7xh7, XSS ueber einen abgehaengten Teilbaum). Ein blankes
Update bewegt es nicht, weil ein `overrides`-Eintrag die Version pinnt — der Eintrag steht jetzt
auf `^3.4.13`, installiert ist 3.4.15. Danach `npm audit fix` ohne `--force` fuer alles Weitere
in Range.

**Weitere Updates:**

| Paket | Aktualisierung |
|---|---|
| `cypress` und seine Transitiven | Cypress 15.21.1 ersetzt die verwundbaren Abhaengigkeiten von Cypress 13; auch `qs` ist aktualisiert. |
| `react-router-dom` / `react-router` | Version 7.18.3 behebt die gemeldeten Router-Befunde. Der bestehende Schutz `isInternalAppPath()` gegen protokollrelative und Backslash-Pfade bleibt bestehen. |

Nachpruefen mit `npm audit`, `npm test` und `npm run build` (einschliesslich TypeScript-Pruefung).
Fuer Cypress 15 gelten Node.js 20/22/24+ und unter Linux glibc >= 2.31;
siehe [Cypress-Migrationshinweise](https://www.cypress.io/blog/cypress-15-made-simple-upgrade-paths-from-13-and-14).

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
