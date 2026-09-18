# Entwicklung

## Voraussetzungen

| Tool | Version | Prüfung |
|---|---|---|
| Java (JDK) | 21+ | `java --version` |
| Maven | optional (Wrapper `mvnw` dabei) | `./mvnw --version` |
| Node.js | 20.19+, 22.12+ oder 24+ (`.nvmrc`) | `node --version` |
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

> **Nicht** `mvn spring-boot:run` verwenden — der Befehl ist **absichtlich blockiert**: Das Backend
> bricht beim Start aus `target/classes` mit einer Meldung ab (`RuntimeClasspathGuard`), siehe
> [Builds neben dem laufenden Dev-Backend](#builds-neben-dem-laufenden-dev-backend).
> Escape-Hatch für Sonderfälle: `-Dautomation.runtime.allow-classes-dir=true`.

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

**Gezielte Testauswahl und Architektur-Ratchets:** Eine themenbezogene `-Dtest`-Auswahl (z. B.
`*Todo*,ControllerModuleGateTest`) deckt `PackageCycleRatchetTest`, `LayerRuleTest` und
`SourceSizeRatchetTest` nicht automatisch ab — diese drei messen über jdeps bzw. Dateigrößen die
kompilierten Klassen und schlagen nur an, wenn sie tatsächlich mitlaufen. Wer eine neue Klasse
anlegt, eine Klasse zwischen Paketen verschiebt oder ein Paket umbenennt, führt sie zusätzlich aus —
entweder gezielt (`mvn test -Dtest='PackageCycleRatchetTest,LayerRuleTest,SourceSizeRatchetTest'`)
oder über `bash scripts/tests/arch-guard.sh` (führt genau diese drei aus und weist die Laufzeit
aus). Ohne diesen zusätzlichen Lauf fällt ein neuer Paketzyklus oder eine Schichtverletzung erst
beim vollen Integrations-Checkpoint auf, nicht schon im Feature-Branch (gesehen 2026-09-18: ein
neuer Zyklus zwischen `com.automation.event` und `com.automation.modules` musste nachträglich auf
main behoben werden, weil die gezielte Testauswahl des Implementers die drei Klassen nicht
enthielt).

**Gezielter Lauf gegen eine Klasse mit `@Nested`-Tests:** 73 Testklassen im Repo (Stand 2026-09-18,
`grep -rl "@Nested" --include="*.java" . | grep -v .claude/worktrees | grep /src/test/ | wc -l`)
nutzen `@Nested`. Konvention: `-Dtest='Klasse*'` statt `-Dtest='Klasse'` — die Wildcard-Form
matcht garantiert auch alle `@Nested`-Inneren-Klassen und bleibt unabhängig von
Surefire-Version/-Konfiguration verlässlich. **Warnung:** Egal welche Form man wählt — der
Text-Report der äußeren Klasse (`target/surefire-reports/<Klasse>.txt`) meldet immer
`Tests run: 0` für die äußere Klasse selbst, weil dort keine eigenen `@Test`-Methoden liegen; das
ist normal, nicht leer. Ein Lauf, dessen `@Nested`-Tests aus irgendeinem Grund wirklich nicht
mitgelaufen sind, sieht wegen dieser Zeile trotzdem "grün" aus, wenn man nur diese eine Zeile oder
einen abgeschnittenen `tail` prüft. Maßgeblich ist immer die abschließende
`Results: Tests run: N`-Zeile des gesamten Maven-Laufs bzw. dessen Exit-Code.

**Klassengrößen-Ratchet:** `architecture/SourceSizeRatchetTest` zählt die Zeilen aller Dateien unter `src/main/java` und schlägt fehl, sobald eine Datei über 300 Zeilen liegt, die nicht in der Allowlist des Tests steht — oder eine gelistete Datei über ihre eingetragene Obergrenze wächst. Die Liste kennt nur eine Richtung: Wer eine gelistete Klasse verkleinert, senkt ihre Obergrenze; fällt sie auf ≤300 Zeilen, fliegt der Eintrag raus. Neue Dateien über 300 Zeilen werden gesplittet, nicht eingetragen (Audit-TODO 1e248ab9). Keine externe Dependency, reiner JUnit-Test.

**Request-Kosten-Ratchet:** `architecture/RequestCostRatchetTest` misst für die Routen, die die UI bei jedem Seitenaufruf trifft (`/todos`, `/workflows`, `/projects`, `/executions`, `/learning/dashboard`, `/search`), zwei **deterministische** Kosten und vergleicht sie mit der `BASELINE`-Map im Test: die Zahl der SQL-Statements und die Antwortgröße in Bytes. Keine Millisekunden — Wanduhrzeit hängt auf einem Entwicklerrechner von Hostlast, JIT und davon ab, wer gerade die einzige SQLite-Connection hält, und würde flackern. Die beiden gepinnten Kosten sind dagegen reproduzierbar und genau die, die in der Vergangenheit ausgerissen sind: ein N+1 macht aus 3 Statements 33 (`1e465a9e perf(api)`), ein mitgeladener TEXT-Blob aus einer kleinen Antwort eine riesige (`443fa57e perf(dashboard)`). Gezählt wird über einen Hibernate-`StatementInspector` (`metrics/CountingStatementInspector` → `metrics/SqlStatementCounter`, ThreadLocal, in `config/StatementInspectorConfig` registriert); vor jeder Messung leert der Test den Persistence-Context, sonst fände der Request die geseedeten Entities im First-Level-Cache und ein Lazy-N+1 bliebe unsichtbar. Wie beim Klassengrößen-Ratchet kennt die Liste nur eine Richtung: Obergrenzen sinken, wenn eine Route billiger wird (der Hinweis-Test nennt die neue Zahl). **Mehrere Obergrenzen schreiben derzeit einen bekannten Mangel fest, kein Ziel** — der erste Lauf fand drei N+1-Muster und eine unbegrenzt wachsende Antwort (TODOs 13d1e2a2, 9db34f3b, a05c9504); sie sind gepinnt, damit das Gate grün ist und nichts *schlimmer* wird, während sie behoben werden. Wer eine Obergrenze anhebt, begründet das in der Commit-Message.

**Request-Kosten im Betrieb messen:** `RequestCostFilter` (Paket `metrics`, ganz außen einsortiert, misst also Auth und Rate-Limit mit) nimmt für jede `/api/v1`-Anfrage vier Zahlen auf: Gesamtdauer, **Wartezeit auf die DB-Connection**, SQL-Statements und Antwort-Bytes. Der Schlüssel ist das Routen-Template (`GET /api/v1/todos/{id}`), nie die URL mit der ID — sonst würde jede ID zur eigenen Metrik. Der Wartezeit-Anteil ist bei `maximum-pool-size: 1` die wichtigste Zahl: Ist eine Ansicht langsam, weil ihre eigene Abfrage teuer ist, oder weil ein fremder Request oder ein Workflow-Step die einzige Connection hält? Gemessen wird das in `TimingDataSource`, einem `DelegatingDataSource` um `getConnection()` (registriert per `DataSourceTimingPostProcessor`; `unwrap`/`isWrapperFor` bleiben durchlässig, sonst verlöre `ConnectionPoolWatchdogService` seinen Pool-Griff). `RequestMetricsCollector` hält je Route ein beschränktes Fenster und liefert **Perzentile, nie Mittelwerte** — ein Mittelwert versteckt genau den einen Request, über den sich jemand beschwert. `RequestMetricsSnapshotService` schreibt kurz vor Mitternacht eine Zeile je Route nach `request_metrics_daily` (V74) und räumt nach `retention-days` auf; die Messung selbst fasst die DB nie an. Abrufbar unter `GET /api/v1/system/request-metrics` (Live-Fenster) und `/daily?route=…&days=…` (Historie).

Zur Diagnose beim Entwickeln: `Server-Timing: wait;dur=… work;dur=… total;dur=… sql;desc="n=…"` steht im DevTools-Network-Tab jeder Antwort (`automation.request-metrics.server-timing-enabled`, **Default `true`**, in `application-stable/prod/test.yml` ausdrücklich `false`). Der Default ist bewusst an und nicht ans Dev-Profil gebunden: Die Dev-Instanz startet ohne aktives Profil (`No active profile set, falling back to "default"`), ein Dev-Profil-Eintrag hätte den Header also ausgerechnet auf der Instanz abgeschaltet, für die er gedacht ist. Nicht zur Laufzeit schaltbar, damit ein geleakter API-Key ihn nicht einschalten kann. Dazu loggt Hibernate jede Abfrage über 200 ms (`hibernate.log_slow_query`, Startup-Konfiguration). Zur Laufzeit über die Settings schaltbar sind `request-metrics-enabled`, `request-metrics-retention-days` und `request-metrics-slow-request-ms`. Ein einzelner langsamer Request geht als WARN ins Log, **nicht** in den ActivityLog: Bei pool-size=1 würde ein zusätzlicher DB-Schreibvorgang auf dem Thread eines ohnehin langsamen Requests um genau die Connection konkurrieren, auf die er wartet.

**Drift-Watchdog:** `RequestMetricsDriftWatchdogService` vergleicht stündlich das laufende Fenster mit der eigenen Vergangenheit einer Route und meldet Verschlechterungen — das ist die Hälfte der Messung, die nicht darauf wartet, gefragt zu werden. Die Regel steckt in `RequestMetricsDriftDetector` (reiner Vergleich, keine I/O, deshalb testbar): gemeldet wird nur, wenn die Route heute mindestens `drift-min-calls` Aufrufe hatte, eine Baseline existiert, diese Baseline nicht selbst nahe null liegt (1 ms gegen 3 ms ist Faktor drei und bedeutet nichts), und das heutige p95 die Baseline um `drift-factor` übersteigt. Die Baseline ist der **Median** der gespeicherten Tage, nicht ihr Mittel: Ein schlechter Tag (großer Import, laufendes Backup) würde ein Mittel anheben und damit die nächste echte Regression verstecken. Die Meldung nennt beide Kosten (`/todos p95 120 -> 900 ms (x7.5), SQL 3.0 -> 47.0`) und benennt, wo die Zahlen es hergeben, die wahrscheinliche Ursache: Wächst die Statement-Zahl mit, ist es ein N+1; überwiegt der Wartezeit-Anteil, liegt die Ursache eher woanders als in dieser Route. Jede Route wird höchstens **einmal pro Tag** gemeldet — ein stündlich wiederholter Alarm erzieht zum Wegsehen. Geschrieben wird in ActivityLog (WARN) und per WebSocket, immer vom Watchdog-Thread. Laufzeit-Schalter: `request-metrics-drift-detection-enabled`, `-drift-factor`, `-drift-min-calls`, `-baseline-days`.

Dieselben Werte gehen als `FAKT`-Zeilen ins Faktenblatt des Workflows `mysuricates-performance-audit.json` — über die Kontextvariable `{{ request_metrics }}` (`RequestMetricsFactSheet` → `WorkflowContextDataService`), **nicht** über die REST-API: Shell-Blöcke rufen sie nicht auf. Damit ist die Lücke geschlossen, die das Audit bis dahin ausdrücklich als „fachliche /api/v1-Endpunkte werden bewusst nicht aufgerufen" ausgewiesen hat. Geprüft von `scripts/tests/test-audit-facts.sh`.

**Dashboard-Widget „API-Latenz":** `components/dashboard/ApiLatencyCard.tsx` (präsentational) + `ApiLatencyWidget` in `DashboardDataWidgets.tsx` (bindet `useRequestMetrics`), registriert unter der Id `api-latency` in `widgetRegistry.ts`. Zeigt die teuersten Routen nach p95 mit Aufrufzahl und SQL-Statements, darüber die Kachel **Wartezeit-Anteil**: der über die Aufrufzahl gewichtete Mittelwert von `waitShare` — gewichtet, weil eine einzelne langsame Anfrage sonst denselben Einfluss hätte wie neunhundert schnelle. Ab 50 % färbt sich die Kachel `danger` und sagt, dass die Ursache eher bei dem liegt, was die Connection hält, als bei den gelisteten Routen.

**Tiefer bohren mit JFR:** Wenn Widget oder Watchdog eine heiße Route zeigen, die Kosten aber nicht erklären, liefert der Java Flight Recorder das Profil — im JDK eingebaut, kein async-profiler, kein Agent, kein Overhead im Normalbetrieb:

```bash
PID=$(cat ~/.local/state/autotrigger-dev/backend.pid)
jcmd "$PID" JFR.start duration=60s filename=/tmp/hot.jfr settings=profile name=diag
jcmd "$PID" JFR.check                      # laeuft die Aufnahme noch?
jfr summary /tmp/hot.jfr                   # welche Ereignisse kamen zusammen
jfr print --events jdk.ThreadPark /tmp/hot.jfr | head -60
```

**Wichtig für diese Plattform:** `jdk.ExecutionSample` (CPU) ist hier meist die falsche Spur. Bei `maximum-pool-size: 1` wird nicht gerechnet, sondern gewartet — das Ereignis dazu ist `jdk.ThreadPark` (Hikari parkt den Thread über `LockSupport`). Eine Probeaufnahme am Dev-Backend lieferte 22 CPU-Samples gegenüber 22 Park-Ereignissen mit zusammen 832 ms Wartezeit; die Wartezeit war das Ganze. Zweitens: **Last muss während der Aufnahme laufen.** Auf einer im Leerlauf stehenden Instanz enthält das Profil nichts — erst die Anfragen auslösen, die langsam sind, dann aufnehmen.

Welches Werkzeug wann:

| Symptom | Erstes Werkzeug |
|---|---|
| Eine Ansicht ist langsam | `Server-Timing`-Header im DevTools-Network-Tab |
| Viele SQL-Statements | SQL-Zahl im Header bzw. `RequestCostRatchetTest` |
| Hohe Wartezeit statt Arbeit | Wartezeit-Kachel im Dashboard, dann der Thread-Dump des Pool-Watchdogs |
| Einzelne langsame Abfrage | `hibernate.log_slow_query` (>200 ms) im Backend-Log |
| Alles langsam, CPU hoch | JFR `jdk.ExecutionSample` |
| Alles langsam, CPU niedrig | JFR `jdk.ThreadPark` |
| Verdacht auf Leck oder GC | JFR `jdk.GCPhasePause`, dazu `/actuator/metrics/jvm.memory.used` |

**Kein Scheduling im Integrationstest-Profil:** `@EnableScheduling` steht in `config/SchedulingConfig` mit `@Profile("!integration")`. Alle `@SpringBootTest`-Klassen laufen mit `@ActiveProfiles("integration")` und damit ohne Hintergrund-Jobs. Grund: Die Jobs schreiben sonst mitten im Testlauf in dieselbe SQLite-Datei — `HealthAutoTodoService` legt z. B. ein TODO an, sobald Stable oder Test nicht erreichbar ist. Solche Zeilen überleben den `@Transactional`-Rollback (eigener Thread, eigene Transaktion) und lassen global zählende Assertions davon abhängen, ob gerade ein Container läuft. Scheduled-Methoden werden in Tests direkt aufgerufen, ihre Logik bleibt abgedeckt. Wer einen neuen `@Scheduled`-Job baut, testet ihn also per direktem Methodenaufruf — nicht, indem er auf den Tick wartet.

**Integration-Encryption-Key im Testprofil auf Temp-Pfad gepinnt:** Genau wie `integration.db.path` setzt `IntegrationTestDbSessionListener` seit 2026-09-12 zusätzlich `integration.key.path` (per-JVM-Temp-Datei, im Teardown gelöscht), auf das `application-integration.yml` `automation.integration.key-file` verweist — ohne diesen Pin generiert `IntegrationEncryptionService.init()` bei jedem `@SpringBootTest` mit leeren `app_settings` sonst einen frischen AES-Key ins echte `~/.autotrigger/` des Entwicklers (geschehen 2026-08-06, hat den echten Key monatelang überschattet).

**Backups im Testprofil abgeschaltet und auf Temp-Verzeichnis gepinnt:** Dieselbe Falle ein drittes Mal (2026-09-13): `automation.backup` hat als Default `enabled: true` und `directory: ${user.home}/.autotrigger/backups/dev`, und `application-integration.yml` überschrieb beides nicht — jeder Spring-Kontext eines Integrationstests legte beim Start ein Backup seiner Wegwerf-Test-DB ins echte Dev-Backup-Verzeichnis und rotierte damit (retain-count 30) echte Backups weg. Seitdem setzt das Testprofil `automation.backup.enabled: false` und `directory` auf `integration.backup.dir`, das `IntegrationTestDbSessionListener` pro Test-JVM auf ein Temp-Verzeichnis zeigt und beim Session-Ende rekursiv löscht. Zusätzlich ist die Rotation in `DatabaseBackupService` abgesichert: Sie zählt und löscht nur Dateien im eigenen Namensschema `mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db` (`BackupFileNaming.isOwnBackupFile`); manuelle Kopien oder fremde `.db`-Dateien im selben Verzeichnis bleiben sichtbar, werden aber nie rotiert. Prüfen: `ls ~/.autotrigger/backups/dev | wc -l` vor und nach `mvn test -Dtest=ContainerServiceStaleRowIntegrationTest` muss gleich bleiben.

### Frontend-Tests

```bash
cd ai-automation-frontend

# TypeScript-Typprüfung
npx tsc --noEmit

# Linting
npm run lint
```

### E2E-Tests (Cypress)

Die E2E-Tests laufen mit [Cypress](https://www.cypress.io/) gegen das **echte Backend** (kein Mocking). Testdaten werden per `cy.request()` direkt über die API angelegt und gelöscht.

Es gibt zwei Zielmodi:
- **Dev**: Frontend-Proxy und Cypress-Helper zeigen auf `localhost:9090`
- **Test**: Frontend-Proxy und Cypress-Helper zeigen auf `localhost:8090`

**Wichtig:** Frontend und Cypress müssen immer mit demselben `CYPRESS_TARGET` gestartet werden, sonst erzeugt das UI Daten auf einer anderen Instanz als das Setup/Cleanup.

### Dev-Target (Standard)

**Voraussetzung:** Dev-Backend (Port 9090) und Frontend (Port 3000) laufen.

```bash
cd ai-automation-frontend

# Frontend startet mit Proxy auf Dev (:9090)
npm run dev

# Cypress interaktiv öffnen (Browser-UI)
npm run cy:open

# Cypress headless ausführen (CI-Modus)
npm run cy:run

# Alias
npm run test:e2e
```

Der Dev-API-Key wird automatisch aus `ai-automation-backend/data/automation.db` gelesen, falls vorhanden.

### Test-Target (isoliert, keine fachlichen Testdaten in Dev)

**Voraussetzung:** Test-Instanz auf Port `8090` läuft.

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

Optional können die Ziele auch direkt überschrieben werden:

```bash
CYPRESS_API_BASE_URL=http://localhost:8090/api/v1
CYPRESS_WS_BASE_URL=ws://localhost:8090
```

### Testdaten und Cleanup

- **Dev-Target:** erzeugte Cypress-Projekte, Workflows, Chains und Runs landen auf Dev
- **Test-Target:** dieselben Daten landen auf der Test-Instanz
- `cy.apiCleanup()` räumt nur Cypress-Testdaten mit dem Prefix `Cypress` auf
- Für die Test-Instanz kann zusätzlich `POST /api/v1/testing/reset` verwendet werden, wenn ein kompletter Reset gewünscht ist

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

### Verhaltensänderungen

Änderungen, die das Laufzeitverhalten bestehender, selbst betriebener Workflows beeinflussen
können — nicht nur neue Features. Vor einem Release lesen, wer eigene Workflow-Definitionen pflegt.

**Seit 2026-09-18 (Commit afa0cdfc): Plattform-Daten in Shell-Feldern färben den Lauf.**
Wer `{{ previous_report }}` oder `{{ open_todos }}` in einem Shell-Feld eines Workflows verwendet
(z. B. `command`, `items_command`), sorgt ab dieser Version dafür, dass **danach** im selben Lauf
auch Blockausgaben (`{{ previous_output }}`, `{{ block.<name>.output }}`, `{{ step_N_output }}`,
`foreach`-Items) automatisch in POSIX-Single-Quotes gesetzt werden — genau wie bei einem
webhook-ausgelösten Lauf. Wer eine solche Ausgabe bisher unquoted an einen Folgebefehl übergeben hat
und sich dabei auf die Wortauftrennung der Shell verlassen hat (`some_cmd {{ previous_output }}`
wurde zu mehreren Argumenten, eines je Wort), bekommt ab jetzt stattdessen **ein einzelnes
gequotetes Argument**.

- **Woran erkennbar:** Ein Folgebefehl, der vorher mehrere Wörter/Argumente aus einer Blockausgabe
  gelesen hat (z. B. `$1`, `$2`, … oder eine `for word in {{ previous_output }}`-Schleife), sieht
  nach dem Update nur noch ein zusammenhängendes Argument bzw. eine Schleife mit genau einem
  Durchlauf.
- **Warum:** `previous_report` und `open_todos` gelten als nicht vertrauenswürdig (sie können Inhalte
  aus früheren, ggf. webhook-verarbeiteten Läufen bzw. aus importierten Issues enthalten) und werden
  deshalb immer gequotet. Sobald ein Shell-Feld einen dieser Platzhalter tatsächlich einsetzt, gilt
  der gesamte Lauf ab diesem Punkt als „gefärbt" — dieselbe Regel wie bei Webhook-Payloads.
- **Altes Verhalten wiederherstellen:**
  - Plattform-Daten vor der Nutzung in eine eigene Variable schreiben/zwischenspeichern
    (`setVariable` bzw. eine Datei), statt sie direkt im selben Shell-Feld zu verwenden — ein Wert,
    der aus der Definition bzw. einer selbst gesetzten Variable stammt, gilt wieder als
    vertrauenswürdig und färbt den Lauf nicht.
  - Oder bewusst ohne Quoting arbeiten: den Folgebefehl so umbauen, dass ein einzelnes gequotetes
    Argument ausreicht (z. B. das Argument in Anführungszeichen erwarten, statt es selbst
    aufzuteilen).
  - In den ausgelieferten Definitionen ist ein Built-in betroffen (`saas-continuous-improve.json`);
    der Folgeblock dort ist ein KI-Prompt, für den sich am Verhalten nichts ändert.

**Seit 2026-09-18 (TODO 22e6bec8): Die Ausgabe eines KI-Blocks mit Plattform-Daten im Prompt wird
gefärbt.** Wer `{{ previous_report }}` oder `{{ open_todos }}` im Prompt eines KI-Blocks verwendet
(`simple`, `agentic`, `skill-capability` sowie die Nicht-Shell-Zweige von `foreach`, `while`,
`conditional`, `retry`, `switch`), bekommt die Ausgabe **genau dieses Blocks** in späteren
Shell-Feldern als **ein einzelnes gequotetes Argument** statt roh. Sobald diese Ausgabe verbucht ist,
gilt der Lauf als gefärbt, und die Färbung ist klebrig: Auch alle **weiteren** Blockausgaben und die
daraus abgeleiteten Variablen (`step_N_output`, `foreach`-Items, `document`-Extrakte) werden ab da
gequotet, und `exportTaint()` reicht das an Sub-Workflows und an ein Resume weiter. Praktisch heisst
das: Fast jeder Audit- oder Report-Workflow, der `previous_report` in einem Prompt liest, läuft ab
diesem Block im gequoteten Modus.

- **Woran erkennbar:** Ein Shell-Block, der `{{ previous_output }}`/`{{ block.<name>.output }}` eines
  solchen KI-Blocks — oder irgendeine spätere Blockausgabe desselben Laufs — bisher wortweise gelesen
  hat (`$1`, `$2`, `for wort in …`), sieht nach dem Update genau ein Argument.
- **Warum:** Der Weg Plattform-Daten → KI-Prompt → Shell-Block umging das Quoting bisher vollständig;
  eine KI-Antwort, die Plattform-Bytes enthält, landete roh in einer Kommandozeile.
- **Sichere Form (empfohlen, wirkt in beiden Welten):** Im Folgeblock
  `"shell_escape_variables": true` setzen und den Platzhalter unquoted über `set --` in eine Variable
  holen: `set -- {{ previous_output }}` und danach `ANTWORT="${1:-}"`. Damit ist der Block
  unabhängig davon, ob der Lauf gefärbt ist.
- **Altes Verhalten wiederherstellen:** Die Plattform-Daten nicht im Prompt verwenden, sondern vorher
  in eine eigene, selbst gesetzte Variable schreiben — oder den Folgebefehl so bauen, dass ein
  einzelnes Argument reicht.
- In den ausgelieferten Definitionen tragen alle betroffenen Shell-Blöcke das Opt-in bereits;
  `PlatformPromptQuotingGateTest` hält das fest.

Details zu den drei Vertrauensklassen und zur Taint-Propagation:
[deployment.md → Vertrauensmodell und Netzwerk-Exposition](deployment.md#vertrauensmodell-und-netzwerk-exposition).

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

Das Backend umfasst **43 REST-Controller** und **19 Block-Typen**:

**Controller:** ActivityLog, Assistant, Audit, Chain, ChainTemplate, CompletionTrigger, Conversion, DashboardLayout, DeadLetter, Dialog, Docs, Execution, Integration, Learning, Notification, OnboardingProfile, PageSuggestion, Project, Reports, Schedule, Search, Skill, Staging, System, SystemAccess, SystemAiProvider, SystemMaintenance, SystemModules, SystemSettings, Testing, Todo, TodoTrigger, WebSocketTicket, Webhook, Wish, Workflow, WorkflowDebug, WorkflowFactory, WorkflowImport, WorkflowMemory, WorkflowPool, WorkflowSeal, WorkflowTemplate

**Block-Typen:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`, `todo-create`, `todo-update`, `todo-close`, `compress`, `compress-ai`

**Flyway-Migrationen:** V1–V73 (SQLite)

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

- **SkillSeeder:** Legt 19 Built-in-Skills beim Start an (idempotent, Update bei Version-Bump) und registriert sie im `SkillRegistry`
- Skill-Definitionen als JSON + Prompt-`.md` unter `src/main/resources/skills/builtin/`
- **WorkflowTemplateSeeder:** Legt 66 Built-in-Templates beim Start an (idempotent, Update bei Version-Bump)
- Template-Definitionen als JSON unter `src/main/resources/templates/builtin/`

### Modul-Katalog

Wer ein Modul zu `modules/ModuleCatalog` hinzufügt oder ändert, muss `scripts/lib/module-catalog.generated.sh`
neu erzeugen — die Datei wird nicht von Hand gepflegt, sondern von `ModuleCatalogTextsTest` aus dem
Katalog und den Anzeigetexten (`ai-automation-frontend/src/data/modules.<lang>.json`) abgeleitet:

```bash
cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
```

Ohne Regenerierung schlägt der Test rot fehl, sobald die generierte Datei nicht mehr zum Katalog
passt. `scripts/lib/module-preset.sh` liest die generierte Datei vor jedem Build ein — bewusst ohne
Backend oder `jq` vorauszusetzen. `scripts/setup.sh --lang de|en` wählt nur die Anzeigesprache der
Modultexte im Terminal (Default: `$LANG`-Präfix, sonst `en`); das Ergebnis (welche Module aktiv
sind) ändert sich dadurch nicht. Details zum Katalog selbst: [architektur.md](architektur.md#module-system-startkonfiguration).

## Hygiene-Gate

`scripts/ci/public-hygiene.sh` prüft, was in ein öffentliches Repository geriete. Die Regeln —
verbotene Literale, Sicherungsdatei-Muster, Allowlist-Pfad, Schwellwerte — stehen in
`scripts/lib/hygiene-rules.sh` und werden von der CI und vom pre-commit-Hook gemeinsam gelesen.

| Modus | Wer | Prüft | Verhalten |
|---|---|---|---|
| `--tracked` (Default) | CI | getrackter Bestand | Fehler |
| `--worktree` | lokal | zusätzlich Dateien, die weder getrackt noch ignoriert sind | Warnung |
| `--staged` | pre-commit-Hook | nur gestagte Aenderungen | Fehler |

Geprüft wird:

1. **Getrackt, aber ignoriert.** Früher eine handgepflegte Liste von zehn Verzeichnisnamen, jetzt
   `git ls-files --cached --ignored --exclude-standard`. Damit ist jede künftige Ignore-Regel
   automatisch abgedeckt. Bewusste Ausnahmen stehen mit Begründung in
   `scripts/ci/hygiene-allowlist.txt` — nur Pfade, nie Musterliterale, damit die Allowlist selbst
   nichts preisgibt.
2. **Verbotene Literale** in getrackten Dateien bzw. in hinzugefügten Zeilen.
3. **Pool-Neutralität** über `scripts/ci/pool-neutrality.sh`. Dasselbe Skript nutzt das
   Built-in-Template `pool-neutrality-audit` (dort mit `--all`, weil es den Pool einer laufenden
   Instanz prüft und nicht nur den getrackten Release-Bestand).
4. **Repo-Gewicht**: zwei getrackte Pfade mit identischer Blob-Prüfsumme ab 256 KiB sind ein
   Fehler, eine einzelne getrackte Datei über 5 MiB eine Warnung.
5. **Streunende Sicherungsdateien** (nur `--worktree`): weder getrackt noch ignoriert. Beim Stagen
   ist derselbe Fund ein Fehler.
6. **`.gitignore` verrät keine privaten Dateinamen.** Die Datei ist getrackt und geht mit der
   Veröffentlichung mit — auch ihre Kommentare. Nennt sie einen Namen, der in `.git/info/exclude`
   steht, ist das ein Verstoss. In der CI gibt es die exclude-Datei nicht, dort fällt die Prüfung
   still aus.
7. **Symlinks, die aus dem Repository hinausführen.** Getrackte (bzw. gestagte) Links mit absolutem
   Ziel oder mit einem relativen Ziel oberhalb der Repo-Wurzel sind ein Fehler. `git grep` liest keine
   Symlink-Blobs, deshalb fängt Check 2 einen Entwickler-Pfad im Linkziel nicht; ausserdem zeigt so ein
   Link in jedem Clone, Worktree und Run-Snapshot auf den Original-Checkout statt in den eigenen Baum.
   Beispiel: `ai-automation-backend/workflow-pool` muss auf `../workflow-pool` zeigen, weil das Backend
   mit cwd `ai-automation-backend/` läuft und den Pool relativ auflöst.

Den Hook installiert `scripts/install-hooks.sh` über `core.hooksPath` (läuft in `setup.sh` mit).

### Wenn das Gate anschlägt

Kein Bypass per Reflex. `git commit --no-verify` existiert, aber ein Treffer ist fast immer echt:
ein pauschales `git add` hat etwas mitgenommen, das nicht ins Repository gehört. Erst schauen,
was gestaged ist.

## Abhängigkeits-Meldungen (Frontend)

`npm audit` im Frontend meldet nach den Updates vom 2026-09-12 **0 Schwachstellen**.
Nach einer ersten Reduktion von zwanzig auf sieben wurden auch die beiden notwendigen
Major-Upgrades durchgeführt.

**Abgetragen:** die Laufzeit-Kette zuerst. `dompurify` kam über
`@monaco-editor/react` → `monaco-editor` und stand auf 3.4.12, dem oberen Rand des
verwundbaren Bereichs (GHSA-55q2-fjhq-7xh7, XSS über einen abgehängten Teilbaum). Ein blankes
Update bewegt es nicht, weil ein `overrides`-Eintrag die Version pinnt — der Eintrag steht jetzt
auf `^3.4.13`, installiert ist 3.4.15. Danach `npm audit fix` ohne `--force` für alles Weitere
in Range.

**Weitere Updates:**

| Paket | Aktualisierung |
|---|---|
| `cypress` und seine Transitiven | Cypress 15.21.1 ersetzt die verwundbaren Abhängigkeiten von Cypress 13; auch `qs` ist aktualisiert. |
| `react-router-dom` / `react-router` | Version 7.18.3 behebt die gemeldeten Router-Befunde. Der bestehende Schutz `isInternalAppPath()` gegen protokollrelative und Backslash-Pfade bleibt bestehen. |

Nachprüfen mit `npm audit`, `npm test` und `npm run build` (einschliesslich TypeScript-Prüfung).
Für Cypress 15 gelten Node.js 20/22/24+ und unter Linux glibc >= 2.31;
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
