# Entwicklung

Diese Anleitung gilt der Arbeit **am** MySuricates-Code — bauen, testen, ausliefern. Wer die
Plattform nur benutzen will, findet den Einstieg in
[GETTING-STARTED.md](GETTING-STARTED.md).

> Kuerzel in Klammern wie `(182bd224)` oder `TODO 63ecc124` sind Herkunftsnachweise:
> Commits dieses Repositories bzw. Vorgaenge in der begleitenden MySuricates-Instanz.
> Sie belegen, wann und warum etwas entstanden ist — von aussen nachschlagen laesst
> sich davon nur der Commit.

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
scripts/start-autotrigger-dev.sh restart    # Code-Änderungen übernehmen: stop → JAR bauen → start;
                                            # startet Vite mit, falls er fehlt (etwa nach einem Terminal-Absturz)
scripts/start-autotrigger-dev.sh stop

# Betriebsart: Host (Default) oder Container — dieselben Kommandos (TODO a1236a64)
scripts/start-autotrigger-dev.sh start --container
AUTOTRIGGER_DEV_RUNTIME=container scripts/start-autotrigger-dev.sh   # dauerhaft
```

**Host oder Container?** Der Host-Weg ist der Default und unverändert. `--container` startet das
Backend stattdessen per `docker compose` aus `ai-automation-backend/docker-compose.dev-container.yml`
(Image `Dockerfile.stable`, also mit Node — die Dev-`docker-compose.yml` baut aus einem
JRE-Alpine-Image **ohne** Node, dort läuft keine KI-CLI). Die Provider-Zugänge mountet der Starter
zur Laufzeit, und zwar nur die, die es auf diesem Rechner gibt; findet er keinen, sagt er das.
Der Vite-Dev-Server bleibt in beiden Fällen auf dem Host. Parametrierbar über
`AUTOTRIGGER_DEV_CONTAINER_PORT`, `AUTOTRIGGER_DEV_CONTAINER_NAME`,
`AUTOTRIGGER_DEV_COMPOSE_PROJECT`, `AUTOTRIGGER_DEV_PROJECT_ROOT`, `AUTOTRIGGER_DEV_PRESET` —
damit eine Probeinstanz neben der echten laufen kann. Die Prüfungen des Starters (läuft schon ein
Backend?, Bereitschaft, Run-Check vor `restart`) folgen im Container-Betrieb dem Port aus
`AUTOTRIGGER_DEV_CONTAINER_PORT`; `MYSURICATES_API_BASE` überschreibt ihn weiterhin.

Das Image (`Dockerfile.stable`) kompiliert nichts, es kopiert das JAR aus
`ai-automation-backend/target/`. Deshalb baut `restart --container` das JAR vorher
(`mvn clean package -DskipTests`, erst danach wird der Container angehalten), `start --container`
nur mit `--rebuild` oder wenn noch keins liegt, und `build --container` baut nur das JAR (3bfa4e10).

Was von den KI-Anbietern des Rechners in den Container kommt, entscheidet
`AUTOTRIGGER_DEV_AI_FROM_HOST` (`login` = Programme und je Anbieter nur die Anmeldung, der Standard;
`programs`; `all` = ganze Anbieter-Verzeichnisse wie früher), dazu `AUTOTRIGGER_DEV_AI_PROVIDERS`
(Auswahl aus `claude,codex,pi`) und `AUTOTRIGGER_DEV_DOCKER_SOCKET` (Standard `1`). Claude braucht
dafür einmal `claude setup-token` — Hintergrund in
[deployment.md → KI-Anbieter im Container](deployment.md#ki-anbieter-im-container). Die Karte
„Startkonfiguration“ in den Einstellungen zeigt, womit der Container läuft.

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

# Alle Agent-Images (ein neuer AGENT_APT_REFRESH holt gh- und Docker-Repo frisch, 97e1529d)
AGENT_APT_REFRESH=$(date +%Y-%m) docker compose --profile build-only build
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
einen abgeschnittenen `tail` prüft. Maßgeblich ist der Exit-Code des gesamten Maven-Laufs;
gezählt wird über die `<testcase>`-, `<failure>`- und `<error>`-Elemente in
`target/surefire-reports/TEST-<Klasse>.xml` (dort landen auch alle `@Nested`-Klassen, eine
eigene Datei je innerer Klasse gibt es nicht) oder über die Klassenzeilen der Konsole.

**Vorbehalt bei gleichnamigen Methoden in `@Nested`-Klassen:** Die abschließende
`Results: Tests run: N`-Zeile fasst Ergebnisse unter dem Schlüssel äußere Klasse +
Methodenname zusammen. Heißen Methoden in zwei `@Nested`-Klassen derselben äußeren Klasse
gleich, zählen sie dort nur einmal; mehrere Fehlschläge desselben Namens erscheinen unter
`Failures:` als `Run 1`, `Run 2`, … wie Wiederholungen. Der `<testsuite tests=… failures=…>`-Kopf
der XML taugt ebenfalls nicht zum Zählen, und was er zeigt, hängt von der Surefire-Version ab —
die kommt ungepinnt aus dem Spring-Boot-Parent (Stand dieses Repos: 3.5.6). Unter 3.5.4 trägt er
nur die Zahlen der äußeren Klasse selbst wie deren `.txt`, also meist 0; unter 3.5.6 fasst er wie
die `Results`-Zeile nach Methodenname zusammen und hat zusätzlich ein `flakes`-Attribut.
Gezielte Fehlprobe (2026-09-25, dieselbe Testklasse unter beiden Versionen): drei
`@Nested`-Klassen mit einem roten `sameName()` in jeder, je einer eindeutigen roten Methode in
zweien und einem grünen `passesEverywhere()` in zweien.

| Quelle (Läufe / Fehlschläge) | Surefire 3.5.4 | Surefire 3.5.6 |
|---|---|---|
| Klassenzeilen der Konsole, summiert | 7 / 5 | 7 / 5 |
| `Results: Tests run:` | 5 / 3 | 5 / 3 |
| `<testsuite>`-Kopf der XML | 0 / 0 | 4 / 3 |
| `<testcase>`- / `<failure>`-Elemente der XML | 7 / 5 | 7 / 5 |
| `.txt` der äußeren Klasse | 0 / 0 | 0 / 0 |
| Exit-Code | 1 | 1 |

Gefährlich wird es mit `-Dsurefire.rerunFailingTestsCount` > 0 (geprüft unter 3.5.6): Dann gilt
ein roter Test, dessen Namensvetter in einer Nachbar-`@Nested`-Klasse grün ist, als „Flake“, und
der Build endet mit `BUILD SUCCESS` und Exit-Code 0, obwohl der Test in beiden Durchgängen
scheiterte. Die `pom.xml` schaltet keine Wiederholungen ein; dabei soll es bleiben.

**`ERROR`-Zeilen in einem grünen Lauf:** Viele Tests prüfen Fehlerpfade — ein Mock wirft, der
Code fängt ab und loggt das korrekt auf `ERROR`, samt Stacktrace. Ein grüner Lauf schreibt so
rund hundert `ERROR`-Zeilen und mehrere tausend Stacktrace-Zeilen; das sind keine Fehlschläge.
Die CI leitet deshalb die Ausgabe je Testklasse nach
`target/surefire-reports/<Klasse>-output.txt` um (`-Dmaven.test.redirectTestOutputToFile=true`)
und lädt das Verzeichnis nur bei einem roten Lauf als Artefakt `backend-surefire-reports` hoch.
Lokal geht dasselbe mit derselben Option; als Default in der `pom.xml` steht sie bewusst nicht,
weil der Workflow `mysuricates-health-check` `target/surefire-reports/*.txt` nach `ERROR` durchsucht.

**Klassengrößen-Ratchet:** `architecture/SourceSizeRatchetTest` zählt die Zeilen aller Dateien unter `src/main/java` und schlägt fehl, sobald eine Datei über 300 Zeilen liegt, die nicht in der Allowlist des Tests steht — oder eine gelistete Datei über ihre eingetragene Obergrenze wächst. Die Liste kennt nur eine Richtung: Wer eine gelistete Klasse verkleinert, senkt ihre Obergrenze; fällt sie auf ≤300 Zeilen, fliegt der Eintrag raus. Neue Dateien über 300 Zeilen werden gesplittet, nicht eingetragen (Audit-TODO 1e248ab9). Keine externe Dependency, reiner JUnit-Test.

**Request-Kosten-Ratchet:** `architecture/RequestCostRatchetTest` misst für die Routen, die die UI bei jedem Seitenaufruf trifft (`/todos`, `/workflows`, `/projects`, `/executions`, `/workflows/{id}/runs`, `/learning/dashboard`, `/search`, `/schedules`, `/schedules/{id}/runs`, `/schedules/calendar`), zwei **deterministische** Kosten und vergleicht sie mit der `BASELINE`-Map im Test: die Zahl der SQL-Statements und die Antwortgröße in Bytes. Keine Millisekunden — Wanduhrzeit hängt auf einem Entwicklerrechner von Hostlast, JIT und davon ab, wer gerade die einzige SQLite-Connection hält, und würde flackern. Die beiden gepinnten Kosten sind dagegen reproduzierbar und genau die, die in der Vergangenheit ausgerissen sind: ein N+1 macht aus 3 Statements 33 (`1e465a9e perf(api)`), ein mitgeladener TEXT-Blob aus einer kleinen Antwort eine riesige (`443fa57e perf(dashboard)`). Gezählt wird über einen Hibernate-`StatementInspector` (`metrics/CountingStatementInspector` → `metrics/SqlStatementCounter`, ThreadLocal, in `config/StatementInspectorConfig` registriert); vor jeder Messung leert der Test den Persistence-Context, sonst fände der Request die geseedeten Entities im First-Level-Cache und ein Lazy-N+1 bliebe unsichtbar. Der Test misst gegen eine eigene, frisch migrierte Datenbankdatei statt gegen die gemeinsame der Test-JVM: Zeilen, die ein anderer Integrationstest ohne Rollback hinterlässt, verschöben sonst jede Obergrenze — in einer `-Dtest`-Auswahl reichten zwei Projekte und drei Workflows für einen roten Lauf (TODO 5695d482); vor jeder Saat prüft er, dass die gesäten Tabellen leer sind. Wie beim Klassengrößen-Ratchet kennt die Liste nur eine Richtung: Obergrenzen sinken, wenn eine Route billiger wird (der Hinweis-Test nennt die neue Zahl). **Eine Obergrenze darf vorübergehend einen bekannten Mangel festschreiben, kein Ziel** — der erste Lauf fand drei N+1-Muster und eine unbegrenzt wachsende Antwort (TODOs 13d1e2a2, 9db34f3b, a05c9504); sie waren gepinnt, damit das Gate grün blieb und nichts *schlimmer* wurde, und sind inzwischen alle behoben. Seit 13d1e2a2 gilt für jede gemessene Route zusätzlich das Wachstums-Kriterium: Ihre Statement-Zahl darf bei doppelter Saat nicht steigen. Wer eine Obergrenze anhebt, begründet das in der Commit-Message.

**Kein KI-Aufruf in einer Transaktion:** `architecture/SlowCallInTransactionRatchetTest` liest den kompilierten Bytecode (mit dem ASM aus spring-core, ohne neue Abhängigkeit) und schlägt fehl, sobald eine Transaktion einen Aufruf umschließt, der einen Prozess startet: jedes `execute*` von `CommandExecutorService` (KI-CLI, Shell, Container), die CLI-Wrapper `HostClaudeService`/`HostCodexService`/`HostPiService`, `ShellCommandService`/`ContainerShellService` sowie `AiProvider.reason`/`executeAction` – auch über eine implementierende Klasse oder einen Provider aus der `AiProviderRegistry`. Ebenso jeder HTTP-Aufruf, der auf einen fremden Server wartet: `HttpClient.send`/`sendAsync` sowie Öffnen, Streams und Lesen der Antwort bei `URLConnection`, `HttpURLConnection` und `HttpsURLConnection` (`connect`, `getInputStream`, `getOutputStream`, `getResponseCode`, `getHeaderField`, `getErrorStream` …; der Scan vergleicht den deklarierten Typ, deshalb steht jeder einzeln in `HTTP_CALLS`) – ein Webhook-Ziel, das in sein Timeout läuft, hielte die Connection genauso lange fest. Als Transaktion zählen `@Transactional`-Methoden (auch über die Klassen-Annotation, nicht bei `NOT_SUPPORTED`/`NEVER`) und die Callbacks von `TransactionTemplate.execute`; verfolgt werden alle Aufrufe am selben Objekt – private Helfer, Lambdas, Methodenreferenzen. Bei pool-size 1 hielte so ein Aufruf die einzige Connection für seine ganze Dauer fest. Aufrufe in andere Beans sieht der Scan nicht: Er hält die direkte Form fest und ist kein Beweis über den ganzen Aufrufgraphen. Die Ausnahmeliste `KNOWN_EXCEPTIONS` ist leer und wird nur kürzer. Die Lösung hat immer dieselbe Form – kurzer Lese-Abschnitt per `TransactionTemplate`, KI-Aufruf ohne Transaktion, kurzer Schreib-Abschnitt (`WorkflowMemoryService#extractKnowledge`, `PageSuggestionService#refreshSuggestions`). Unit-Tests prüfen die Grenze mit `testsupport/FakeTransactions`: ein Transaktionsmanager ohne Datenbank und ein Proxy, der `@Transactional` wie der Anwendungskontext anwendet; im gemockten Aufruf fragt `FakeTransactions.active()`, ob gerade eine Transaktion offen ist.

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
| Verdacht auf Leck oder GC | JFR `jdk.GCPhasePause`, dazu `/actuator/metrics/jvm.memory.used` (mit API-Key) oder ohne Key die Heap-Zahlen von `/api/v1/system/jvm-memory` |

**Kein Scheduling im Integrationstest-Profil:** `@EnableScheduling` steht in `config/SchedulingConfig` mit `@Profile("!integration")`. Alle `@SpringBootTest`-Klassen laufen mit `@ActiveProfiles("integration")` und damit ohne Hintergrund-Jobs. Grund: Die Jobs schreiben sonst mitten im Testlauf in dieselbe SQLite-Datei — `HealthAutoTodoService` legt z. B. ein TODO an, sobald Stable oder Test nicht erreichbar ist. Solche Zeilen überleben den `@Transactional`-Rollback (eigener Thread, eigene Transaktion) und lassen global zählende Assertions davon abhängen, ob gerade ein Container läuft. Scheduled-Methoden werden in Tests direkt aufgerufen, ihre Logik bleibt abgedeckt. Wer einen neuen `@Scheduled`-Job baut, testet ihn also per direktem Methodenaufruf — nicht, indem er auf den Tick wartet.

**Integration-Encryption-Key im Testprofil auf Temp-Pfad gepinnt:** Genau wie `integration.db.path` setzt `IntegrationTestDbSessionListener` seit 2026-09-12 zusätzlich `integration.key.path` (per-JVM-Temp-Datei, im Teardown gelöscht), auf das `application-integration.yml` `automation.integration.key-file` verweist — ohne diesen Pin generiert `IntegrationEncryptionService.init()` bei jedem `@SpringBootTest` mit leeren `app_settings` sonst einen frischen AES-Key ins echte `~/.autotrigger/` des Entwicklers (geschehen 2026-08-06, hat den echten Key monatelang überschattet).

**Backups im Testprofil abgeschaltet und auf Temp-Verzeichnis gepinnt:** Dieselbe Falle ein drittes Mal (2026-09-13): `automation.backup` hat als Default `enabled: true` und `directory: ${user.home}/.autotrigger/backups/dev`, und `application-integration.yml` überschrieb beides nicht — jeder Spring-Kontext eines Integrationstests legte beim Start ein Backup seiner Wegwerf-Test-DB ins echte Dev-Backup-Verzeichnis und rotierte damit (retain-count 30) echte Backups weg. Seitdem setzt das Testprofil `automation.backup.enabled: false` und `directory` auf `integration.backup.dir`, das `IntegrationTestDbSessionListener` pro Test-JVM auf ein Temp-Verzeichnis zeigt und beim Session-Ende rekursiv löscht. Zusätzlich ist die Rotation in `DatabaseBackupService` abgesichert: Sie zählt und löscht nur Dateien im eigenen Namensschema `mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db` (`BackupFileNaming.isOwnBackupFile`); manuelle Kopien oder fremde `.db`-Dateien im selben Verzeichnis bleiben sichtbar, werden aber nie rotiert. Prüfen: `ls ~/.autotrigger/backups/dev | wc -l` vor und nach `mvn test -Dtest=ContainerServiceStaleRowIntegrationTest` muss gleich bleiben.

**Setup-Verzeichnisse von Zusammenfassung und Factory im Testprofil auf Temp-Verzeichnis gepinnt:** Die Falle ein viertes Mal (2026-09-24): `SummarizationSetupService` und `FactorySetupService` schreiben bei jedem Kontextstart (`@PostConstruct`) ihre Anweisungsdateien nach `automation.summarization.base-dir` bzw. `automation.factory.base-dir`, Default `${user.home}/.autotrigger/summarization` bzw. `.../factory` — jeder Spring-Kontext eines Integrationstests schrieb `summarization/claude/CLAUDE.md`, `summarization/codex/AGENTS.md` und `factory/codex/AGENTS.md` ins echte Home. Seitdem zeigen beide auf `integration.setup.dir`, das `IntegrationTestDbSessionListener` pro Test-JVM auf ein Temp-Verzeichnis setzt und beim Session-Ende rekursiv löscht. `config/IntegrationProfileHomeIsolationTest` hält das fest und prüft zusätzlich jeden Schlüssel der `application*.yml`: Zeigt einer im Testprofil aufgelöst ins echte `~/.autotrigger`, schlägt der Test fehl. Das fängt den nächsten Schreiber dieser Art aber nur, wenn sein Pfad als Schlüssel in einer `application*.yml` steht; Pfade, die der Code selbst aus `user.home` baut (etwa die API-Key-Datei über `InstanceAddress.apiKeyFile()`), sieht der Test nicht.

**Szenario-Tests ohne KI-CLI:** `ScenarioIntegrationTest` fährt Workflow-Definitionen aus `src/test/resources/scenarios/*.json` durch den echten Executor. Jeder KI-Aufruf geht dabei an einen `ScriptedAiProvider` (Provider-IDs `SCRIPTED`, `SCRIPTED_B`) mit festen Antworten, der zugleich jeden Aufruf mitschreibt. So lassen sich Prompt, Modell, Timeout und Provider vor dem Aufruf prüfen, und danach Gate, Auto-TODO und Laufstatus, alles ohne `claude`/`codex`/`pi` und ohne Display. Ein Szenario kann statt eines Laufs auch mehrere Läufe desselben Workflows (`runs`, etwa für den Audit-Lebenszyklus) oder eine Kette (`chain`) fahren und auf Freigaben antworten (`answers`). Ein neuer Fall ist eine neue JSON-Datei. Jede Datei nennt ihre Spec-Quelle und den Vorfall, aus dem sie abgeleitet ist. Katalog und Format stehen in `scenarios/README.md`.

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

Gegen eine Nebeninstanz (eigener Port, eigener Key) kommt die Frontend-Adresse dazu, weil `baseUrl`
fest auf `:3000` steht — etwa Vite auf `5173` gegen ein Backend auf `19090`:

```bash
CYPRESS_API_KEY=<key> CYPRESS_API_BASE_URL=http://localhost:19090/api/v1 CYPRESS_WS_BASE_URL=ws://localhost:19090 \
  npx cypress run --config baseUrl=http://localhost:5173
```

Seit Cypress 16 gibt es `Cypress.env()` nicht mehr: `API_BASE_URL`, `WS_BASE_URL` und `E2E_TARGET` stehen
in `cypress.config.ts` unter `expose` (`Cypress.expose()`), der API-Key unter `env` und ist nur über den
Befehl `cy.env()` lesbar — `cypress/support/e2e.ts` holt ihn einmal je Spec.

### Nebeninstanz für E2E starten

Eine Wegwerf-Instanz (etwa aus einem Worktree-JAR) bekommt ein eigenes Verzeichnis `$S` und läuft
vollständig darin — auch für die KI-CLIs, die sie startet:

```bash
S=$(mktemp -d); cp ai-automation-backend/target/ai-automation-backend-*.jar "$S/app.jar"
mkdir -p "$S/home" "$S/config" "$S/data"
HOME="$S/home" XDG_CONFIG_HOME="$S/config" \
AUTOMATION_PORT=19090 AUTOMATION_DB_PATH="$S/data/automation.db" AUTOMATION_API_KEY=<key> \
AUTOMATION_BACKUP_DIR="$S/backups" AUTOMATION_TRAY_ENABLED=false AUTOMATION_MODULES_PRESET=full \
AUTOMATION_RATE_LIMIT_RPM=6000 AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false \
  java -Duser.home="$S/home" -jar "$S/app.jar"
```

- **`-Duser.home`**: Java liest `user.home` aus dem Benutzerkonto, nicht aus `$HOME`. Ohne die Angabe
  liest und schreibt die Instanz die Dateien der Dev-Instanz unter `~/.autotrigger/` (API-Key-Datei,
  Schlüssel für Integrationen und Siegel, Backups).
- **`HOME` und `XDG_CONFIG_HOME` im Scratch-Verzeichnis**: Die KI-CLIs, die das Backend startet, erben `HOME` —
  mit dem echten nutzen sie deine Anmeldung, also echte Tokens. Unter `XDG_CONFIG_HOME` sucht das Backend
  `mysuricates/ai.env` (Claude-Token für Projekt-Container). Aus demselben Grund dürfen
  `CLAUDE_CODE_OAUTH_TOKEN` und `ANTHROPIC_API_KEY` in der startenden Shell nicht gesetzt sein.
- **`AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false`**: Sonst startet jeder angelegte oder geänderte Workflow
  — und viele Specs legen welche an — im Hintergrund einen `claude`-Aufruf für Tags und Kategorie
  (`WorkflowTaggingService`); beim Start dazu einen je Workflow ohne Tags. Mit `HOME` im Scratch läuft er
  ohne Anmeldung ins Leere, mit dem echten kostet er Tokens. Die Integrationstests setzen denselben
  Schalter (`application-integration.yml`, bewacht von `IntegrationProfileAiIsolationTest`).
- **`AUTOMATION_RATE_LIMIT_RPM=6000`**: Das Limit von 120 Anfragen je Minute bricht einen Cypress-Lauf sonst ab.
- Vite für die Oberfläche auf Port `5173` (CORS lässt nur 3000/5173 zu):
  `VITE_HTTPS=false VITE_API_BASE_URL=http://localhost:19090/api/v1 VITE_WS_BASE_URL=ws://localhost:19090 npx vite --port 5173`.

### Testdaten und Cleanup

- **Dev-Target:** erzeugte Cypress-Projekte, Workflows, Chains und Runs landen auf Dev
- **Test-Target:** dieselben Daten landen auf der Test-Instanz
- `cy.apiCleanup()` räumt nur Cypress-Testdaten mit dem Prefix `Cypress` auf
- Für die Test-Instanz kann zusätzlich `POST /api/v1/testing/reset` verwendet werden, wenn ein kompletter Reset gewünscht ist

**Teststruktur:**

```
cypress/
  support/
    e2e.ts          ← API-Key per cy.env(); vor jedem Laden Key, englische Locale und gesehene Begrüßungstour
    target.ts       ← E2E-Ziel: API-Adresse (Cypress.expose) und der geladene API-Key
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
- Englische Locale und die gesehene Begrüßungstour werden per `localStorage` vor jedem Laden gesetzt (sonst deckt die Tour jede Seite ab)
- Selektoren nutzen `aria-label`, Button-Text, `h1`-Text, `placeholder`, `role="switch"` (keine `data-testid`)
- `before()` / `after()` Hooks sorgen für saubere Testdaten via `cy.apiCleanup()`

`scripts/cleanup-runs.sh` prüft vor jedem Zugriff die Datei mit `PRAGMA quick_check` und bricht bei
Befund ab (2026-08-04 hat ein DELETE auf einer bereits korrupten DB den Schaden vergrößert). Mit
`--db <pfad>` lässt sich das Skript gegen eine andere Datei richten. Skript-Tests liegen unter
`scripts/tests/` und laufen ohne Backend: `bash scripts/tests/test-cleanup-runs.sh`.

### Installer auf frischer Maschine (Installer-Probe)

`scripts/installer-probe.sh` prüft `scripts/setup.sh` so, wie ein neuer Nutzer ihn erlebt: in
einem frischen Container (Default `ubuntu:24.04`, mit `--image rockylinux:9` auch ein dnf-System),
auf dem nur git, sudo und tzdata liegen. Geprüft wird immer ein committeter Stand (Default `HEAD`,
sonst `--ref`), nie der Arbeitsbaum. Braucht Docker.

```bash
./scripts/installer-probe.sh --interactive          # frische Maschine als Shell — selbst durchklicken
./scripts/installer-probe.sh --ref main             # automatischer Lauf mit Report
./scripts/installer-probe.sh --image rockylinux:9   # dasselbe auf einem dnf-System
./scripts/installer-probe.sh --ref main --keep      # danach die frische Instanz im Browser ansehen
```

Die interaktive Maschine teilt das Netz des Rechners (`--network host`): Die Adresse, die
`setup.sh` am Ende nennt, öffnet man direkt im Browser, belegte Ports des Rechners umgeht der
Installer selbst. Im Modus-Schritt „auf diesem Rechner“ wählen — Docker gibt es in der Probe nicht.

Der automatische Lauf nimmt den Weg „eine Instanz auf diesem Rechner“ aus
[GETTING-STARTED.md](GETTING-STARTED.md) in zwei Phasen, jede unter einem Pseudo-Terminal:
`setup.sh --check` (nur prüfen) und `setup.sh --yes --runtime host --bind 0.0.0.0` (ein Durchgang
bis zur laufenden Instanz). Den Exit-Code schreibt jede Phase selbst in eine Datei im Container —
über `script` und `docker exec` ging ein Abbruch schon als 0 durch. Rot wird der Lauf, wenn Phase 1
fehlende Tools nicht nennt, Phase 2 nicht mit Exit 0 endet, Health oder Oberfläche auf dem einen
Port nicht antworten, der Login mit dem erzeugten Key scheitert, der Key in einem Log steht, das
Backend nicht genau einmal gebaut wird oder der Klon danach nicht mehr sauber ist (`git status`).
Das Ergebnis ist eine einzelne HTML-Seite mit Terminal-Mitschnitt und Screenshots unter
`~/.local/state/mysuricates/installer-probe/<zeit>-<sha>/report.html` (`latest` zeigt auf den letzten
Lauf). Ein Lauf lädt JDK, Node, npm-Pakete und Maven-Abhängigkeiten jedes Mal frisch. Der
Container-Weg (Docker im Container) ist noch nicht Teil der Probe.
Die Screenshots macht Playwright aus `ai-automation-frontend/node_modules` auf dem Rechner. Fehlt
der Browser zur installierten Playwright-Version (typisch nach einem Update), wird die Login-Prüfung
rot mit „Browser fehlt — im Frontend `npm run pw:install` ausführen“; die Probe lädt nichts nach.

Vor einem Release läuft er als Workflow **Installer-Probe** (Pool-Eintrag
`mysuricates-installer-probe`, Variable `ref`, Default `main`). Der Workflow holt das Probe-Skript
aus dem geprüften Stand, nicht aus dem gerade ausgecheckten Branch. Die Skript-Tests laufen ohne
Docker: `bash scripts/tests/test-installer-probe.sh` und
`python3 -m unittest discover -s scripts/tests -p 'test_installer_probe_report.py'`.

## Versionierung & Releases

### Wo die Version definiert ist

| Datei | Format | Beispiel |
|---|---|---|
| `ai-automation-backend/pom.xml` | im Tag `X.Y.Z`, danach `X.Y.Z-SNAPSHOT` | `0.7.7` bzw. `0.7.8-SNAPSHOT` |
| `ai-automation-frontend/package.json` | wie die pom.xml: im Tag `X.Y.Z`, danach `X.Y.Z-SNAPSHOT` | `0.7.7` bzw. `0.7.8-SNAPSHOT` |
| `application.yml` | via `@project.version@` (Maven-Filtering) | automatisch |

Das Dashboard zeigt die Version über den `/api/v1/system/info`-Endpunkt an. Den **Bautyp** leitet
`SystemIdentityService` aus dem Suffix ab: ohne Suffix `RELEASE`, mit `-SNAPSHOT` eben SNAPSHOT.
Deshalb trägt der getaggte Commit die nackte Nummer — bis v0.7.6 stand dort `-SNAPSHOT`, und jede
Installation eines Releases meldete SNAPSHOT (TODO dd82775b).

Backend und Frontend tragen **dieselbe** Nummer, auch zwischen zwei Releases: die Versions-Kachel
im Dashboard stellt beide nebeneinander (`BE … · FE …`) und warnt, wenn sie auseinanderlaufen —
der Fall „neues Frontend, altes Backend-JAR", der im Test-Container 404er und hängende Seiten
erzeugt. Liefe `package.json` auf der Release-Nummer weiter, während die `pom.xml` schon auf der
nächsten `-SNAPSHOT` steht, stünde diese Warnung dauerhaft im Bild.

### Release-Script

```bash
./scripts/release.sh           # Patch: 0.1.0 → 0.1.1 (Standard)
./scripts/release.sh minor     # Minor: 0.1.1 → 0.2.0
./scripts/release.sh major     # Major: 0.2.0 → 1.0.0
```

Das Script:
1. Liest die letzte veröffentlichte Version aus dem jüngsten Git-Tag (ohne Tags: aus `pom.xml`)
2. Berechnet die neue Version (Segment hochzählen) und bricht ab, wenn es den Tag schon gibt
3. Schreibt sie **ohne Suffix** in `pom.xml` sowie in `package.json` und `package-lock.json` (über `npm version`) — ein Lockfile auf der alten Version macht jeden frischen Klon nach dem ersten `npm install` dirty; `packageLock.guard.test.ts` hält beide synchron
4. Erstellt Git-Commit `Release vX.Y.Z` und setzt Git-Tag `vX.Y.Z`
5. Setzt `pom.xml` **und** `package.json` auf die nächste Entwicklungsversion `X.Y.(Z+1)-SNAPSHOT` und committet `Weiter an vX.Y.(Z+1)` — sonst trüge jeder Commit nach dem Tag dieselbe Release-Nummer, und die Versions-Kachel meldete dauerhaft Drift

**Voraussetzung:** Sauberer Working Tree (keine uncommitteten Änderungen).
**Test:** `bash scripts/tests/test-release.sh` fährt den Ablauf in einem Wegwerf-Repo durch.

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
  - Welche der ausgelieferten Definitionen Plattform-Daten überhaupt in ein Shell-Feld einsetzen,
    verschiebt sich mit jedem neuen Pool-Eintrag; eine feste Zahl steht hier deshalb nicht. Dass
    sie dabei die sichere, unquotete Form verwenden, hält `TemplateHygieneGateTest` fest.

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
| **Hinweise am Ort** | `<Hint id="…">` steht in der Komponente, die er erklärt, nie in einer zentralen Liste — fehlt die Seite (Preset, Simple Mode, Modul aus), fehlt auch der Hinweis. Registry mit Version in `src/data/hints.ts` (Version nur bei wesentlicher Inhaltsänderung erhöhen, dann erscheint er wieder), Texte in `src/data/hints.<lang>.json`: was und warum, nie wo man klickt. `i18n/__tests__/hints.test.ts` hält Registry, Texte und Verwendung gleich. Umgekehrt kann ein Hinweis gerade dann erscheinen, wenn ein Modul fehlt: `build-from-description` in der Vorlagen-Auswahl nennt auf core Assistent, Factory und Wünsche und wie man sie zuschaltet |
| **Forms** | Kontrollierte Komponenten mit React State |

### Annotation-Prozessoren

Das Backend nutzt drei Annotation-Prozessoren, die in der Compiler-Konfiguration (`pom.xml`) korrekt geordnet sein müssen:

1. **Lombok** — Generiert Getter, Setter, Builder, etc.
2. **MapStruct** — Generiert Mapper-Implementierungen
3. **lombok-mapstruct-binding** — Brücke zwischen Lombok und MapStruct

## Architektur-Notizen für Entwickler

### Backend-Struktur

Das Backend umfasst **51 REST-Controller** und **20 Block-Typen**:

**Controller:** ActivityLog, Assistant, Audit, Chain, ChainTemplate, CompletionTrigger, Conversion, DashboardLayout, DashboardReportAssist, DashboardWorkflow, DeadLetter, Dialog, Docs, Execution, Integration, Learning, Notification, OnboardingProfile, PageSuggestion, Project, Reports, Schedule, Search, Skill, Staging, System, SystemAccess, SystemAiProvider, SystemMaintenance, SystemModules, SystemRequestMetrics, SystemSettings, Testing, Todo, TodoTrigger, WebSocketTicket, Webhook, Wish, Workflow, WorkflowAnalyzer, WorkflowDebug, WorkflowFactory, WorkflowImport, WorkflowMemory, WorkflowPool, WorkflowSeal, WorkflowTemplate

**Block-Typen:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`, `todo-create`, `todo-update`, `todo-close`, `compress`, `compress-ai`, `dashboard-report`

**Flyway-Migrationen:** V1–V91 (SQLite)

### Gate Status System

Workflow-Blöcke können Quality-Gate-Marker ausgeben:
```
gate_status: GREEN|YELLOW|RED
gate_category: <kategorie>
gate_description: <beschreibung>
```
- Das Gate ändert den Run-Status nicht; `COMPLETED_WITH_ERRORS` entsteht nur durch einen fehlgeschlagenen `checked_command`-Schritt
- Mit AutoTodo am Workflow: RED → TODO mit HIGH-, YELLOW → mit MEDIUM-Priorität (`AutoTodoService`)
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
- **WorkflowTemplateSeeder:** Legt 55 Built-in-Templates beim Start an (idempotent, Update bei Version-Bump) und entfernt Built-in-Zeilen, deren Datei nicht mehr ausgeliefert wird
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
| `--range A..B` | Pre-Push-Gate | nur die Aenderungen eines Commit-Bereichs, dieselben Checks wie `--staged` | Fehler |

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
8. **Private Begriffe** — im Bestand bei `--tracked`/`--worktree`, in hinzugefügten Zeilen bei
   `--staged`/`--range`. Namen privater Projekte, Arbeitgeber oder
   Personen kann Check 2 nicht führen, ohne sie im Repository zu veröffentlichen. Sie stehen deshalb
   in einer Liste außerhalb des Bestands: `scripts/ci/hygiene-private-terms.txt` (untrackt, in
   `.git/info/exclude`), `$HYGIENE_PRIVATE_TERMS` oder
   `~/.config/mysuricates/hygiene-private-terms.txt` — letzteres erreichen auch Klone und die
   isolierten Run-Workspaces der Evidenz-Workflows. Gesucht wird wörtlich, ohne Groß-/Kleinschreibung;
   die Meldung nennt die Listenzeile, nicht den Begriff. Fehlt die Liste (CI, fremder Clone), wird
   der Check hörbar übersprungen.
   Eine Zeile mit dem Präfix `low:` (`low: <Begriff>`) markiert einen **schwachen** Begriff: Er
   bleibt auf der Liste, ein Treffer ist aber nur eine Warnung („privater Begriff, schwach (Zeile N
   …)“) — kein Verstoss, kein Exit 1, weder im Bestand noch bei Commit oder Push; der
   Evidenz-Workflow wertet ihn als LOW. Gedacht für Begriffe, die sichtbar bleiben, aber keinen
   Release aufhalten sollen. Alle Leser der Liste (Bestand, hinzugefügte Zeilen, Commit-Nachrichten
   im Pre-Push-Gate) parsen sie über denselben Helfer `hygiene_private_terms_load` in
   `scripts/lib/hygiene-rules.sh`.

Die Hooks (`pre-commit`, `commit-msg`, `pre-push`) installiert `scripts/install-hooks.sh` über
`core.hooksPath` (läuft in `setup.sh` mit).

### Wenn das Gate anschlägt

Kein Bypass per Reflex. `git commit --no-verify` existiert, aber ein Treffer ist fast immer echt:
ein pauschales `git add` hat etwas mitgenommen, das nicht ins Repository gehört. Erst schauen,
was gestaged ist.

## Pre-Push-Gate

`scripts/ci/pre-push-checks.sh` prüft, was gleich das Repository verlässt — lokal, deterministisch,
in Sekunden bis zu einer Minute. Zwei Hooks rufen es auf: `commit-msg` (nur Nachricht und
Autor-Adresse, beim Schreiben — dort ist der Befund billig) und `pre-push` (der ganze Push-Bereich,
das letzte Netz: fängt auch Commits aus Worktrees ohne Hooks, aus Run-Workspaces und mit
`--no-verify`). Die CI fährt dieselben Bestands-Gates (`--tree`) und dieselbe Bereichsprüfung auf
die Commits jedes Push-Ereignisses; die Regeln stehen in `scripts/lib/hygiene-rules.sh`.

Geprüft wird je Push-Bereich (Remote-Stand..lokal; bei einem neuen Branch alles, was noch kein
Remote-Ref kennt):

1. **Commit-Metadaten.** Autor und Committer tragen die GitHub-noreply-Form
   (`<id>+<login>@users.noreply.github.com`, `HYGIENE_COMMIT_EMAIL_REGEX`); die Nachricht enthält
   keine verbotenen Literale, keine privaten Begriffe (schwache, Präfix `low:`, nur als Warnung)
   und keine Mailadresse außerhalb eines Trailers (`Co-Authored-By`, `Generated-By`,
   `Signed-off-by`, …). Die Adresse selbst steht nirgends im Repository, nur ihre Form.
2. **Hygiene-Gate auf den Bereich** (`public-hygiene.sh --range`): was neu dazukommt. Seine
   Warnungen erscheinen auch dann, wenn der Bereich sauber ist.
3. **Bestands-Gates der CI, die vom Code abhängen:** Doku-Zahlen, Doku-Verweise mit
   DE/EN-Gleichstand, Diagramm-Drift. Fehlt eines der Skripte, ist das ein harter Fehler.
4. **gitleaks auf den Bereich** — nur wenn installiert, sonst hörbar übersprungen. Es wird nichts
   nachinstalliert; die CI scannt ohnehin.
5. **Bauprüfung nach Scope.** Frontend berührt → `tsc --noEmit` und `npm run lint` (etwa eine
   Minute); Backend berührt → `mvn -q test-compile` (Sekunden). Reine Doku-Pushes bleiben bei
   Sekunden.

Nicht hier: die volle Testsuite (CI und die Commit-Regel in CLAUDE.md), der getrackte Bestand
(`--tracked` in der CI und in der Kette „Public Readiness") und alles, was ein Urteil braucht.

Auslassen, wenn es sein muss: `MYSURICATES_PREPUSH_SKIP=tree,build git push` — jede Auslassung
wird gemeldet, ein Gate, das nicht misst, ist nicht grün. `git push --no-verify` bleibt der Bypass;
kein Bypass per Reflex. Einen Befund an Nachricht oder Adresse heilt `git commit --amend`
(nach `git config user.email …` mit `--reset-author`), ältere Commits `git rebase -i`.
Selbsttest: `bash scripts/tests/test-pre-push-checks.sh`.

## Diagramm-Seiten

Die Diagramm-Dokumente unter `docs/` gibt es zweimal: als Markdown (`<name>-diagrams.md`) und als
HTML (`<name>-diagrams.html`). **Quelle ist die Markdown-Datei** — GitHub rendert ```mermaid-Zäune
nativ, HTML-Dateien im Repository dagegen gar nicht. Die HTML-Fassung ist für GitHub Pages gedacht
und wird erzeugt, ebenso das Verzeichnis [docs/diagramme.md](diagramme.md):

```bash
python3 scripts/docs-diagrams.py                # HTML, englische Seiten und Verzeichnisse neu erzeugen
python3 scripts/docs-diagrams.py --check        # meldet Drift und Übersetzungslücken, Exit-Code 1
python3 scripts/docs-diagrams.py --add-missing  # fehlende Übersetzungsschlüssel leer eintragen
python3 -m unittest discover -s scripts/tests -p 'test_docs_diagrams.py'
```

Erzeugt heisst: vollständig, nicht nur die Diagramme. Prosa, Tabellen, Karten und Dateibäume der
HTML-Seite stammen aus derselben Markdown-Datei, sonst wäre der Rest der Seite eine zweite, von Hand
gepflegte Fassung. Eine HTML-Datei von Hand zu ändern hat deshalb keinen Bestand — der nächste Lauf
überschreibt sie, und `--check` meldet es vorher in der CI.

Die HTML-Hülle (Farben, Layout, Mermaid-Einbindung) steht als Vorlage im Skript, für alle Seiten
dieselbe. Welche Markdown-Mittel die Vorlage kennt, steht im Kopfkommentar von
`scripts/docs-diagrams.py`; gebraucht werden Überschriften, Absätze, Listen, Tabellen, Codezäune
und die üblichen Inline-Auszeichnungen.

**Englische Fassung.** Die deutsche Seite ist die Quelle. `docs/en/<name>-diagrams.md` und `.html`
sowie `docs/en/diagramme.md` entstehen aus ihr — eine zweite, von Hand gepflegte Fassung gibt es
nicht. Aufbau, Diagrammgerüst, Codeblöcke und Dateibäume kommen aus der deutschen Seite, die Texte
aus einer Übersetzungstabelle je Seite, `docs/i18n/<name>-diagrams.en.json` (Muster wie `de.ts`/`en.ts`
der Oberfläche): Schlüssel ist das deutsche Textstück, Wert das englische. Textstücke sind Titel,
Untertitel, Überschriften samt Beiwort, Absätze, Listenpunkte, Tabellenzellen und die
Mermaid-Beschriftungen — Knotentexte, Kantenlabels, `subgraph`-Titel, Notizen, Sequenz-Nachrichten,
`alt`/`loop`-Beschriftungen; die Regeln je Diagrammtyp stehen in `scripts/lib/mermaid_labels.py`.
Knoten-IDs werden nie übersetzt, auch wo Mermaid sie anzeigt (`PENDING --> RUNNING`,
`subgraph Compose`): Was in beiden Sprachen verschieden heißen soll, braucht eine Beschriftung
(`ID["Text"]`). Ein Textstück ohne Buchstaben außerhalb von Inline-Code braucht keinen Eintrag, ein
Wert gleich dem Schlüssel ist erlaubt (Klassennamen, schon englische Beschriftungen).

`--check` wird rot, wenn ein Textstück keine Übersetzung hat (Schlüssel fehlt oder Wert leer), wenn
ein Schlüssel verwaist ist, wenn ein Wert Inline-Code oder Zahlen anders nennt als das Original oder
wenn die Übersetzung den Seitenaufbau bzw. das Gerüst eines Diagramms verändert. Wer eine deutsche
Diagramm-Seite ändert, zieht die Tabelle deshalb im selben Commit nach: `--add-missing` trägt neue
Schlüssel mit leerem Wert in Lesereihenfolge ein; verwaiste bleiben stehen, bis sie jemand löscht —
oft ist ihr Wert, leicht angepasst, die Übersetzung des neuen Schlüssels. Unbekannte Mermaid-Syntax
(ein neuer Diagrammtyp, eine Zeile ohne Regel) bricht mit Exit-Code 2 ab, statt deutschen Text still
durchzulassen.

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
- **KI-CLIs (Claude, Codex, Pi) laufen in Workflow-Blöcken immer auf dem Host**, nicht im Projekt-Container; dafür braucht es kein Docker. Den Projekt-Container nutzen nur Ad-hoc-Befehle während eines Runs (`claude` per `docker exec`) und das Terminal.

### Sicherheit

- Der Docker-Socket-Mount gibt dem Backend Host-Level Docker-Zugriff. In Produktionsumgebungen sollte mindestens API-Key-Auth aktiviert sein.
- CORS lässt per Default nur die Dev-Server auf `localhost` (3000/5173) zu, mit `remote-access` zusätzlich die eigene Tailscale-Adresse; weitere Ursprünge über `AUTOMATION_CORS_ORIGINS` ([Wo gelauscht wird](deployment.md#wo-gelauscht-wird)).
- Rate Limiting ist standardmäßig aktiv (120 req/min). Konfigurierbar zur Laufzeit in Settings → Execution.

### Frontend

- Kein Offline-Modus (PWA-Shell wird gecacht, aber API-Daten nicht).
- Monaco Editor kommt aus dem eigenen Bundle (kein CDN, die CSP erlaubt nur `'self'`), wird aber nicht von der PWA vorab gecacht — offline öffnet er sich nur, wenn der Browser den `monaco`-Chunk schon zwischengespeichert hat.
- Die Lazy-Chunks laden erst bei Bedarf: `monaco` und die Monaco-Worker mit dem ersten Editor, `TerminalPanel` beim Öffnen des Terminals eines laufenden Runs, `BpmnDiagramView` sobald ein Workflow-Diagramm angezeigt wird. `npm run build` endet mit `scripts/check-lazy-chunks.mjs`: Der Build scheitert, sobald einer davon per `modulepreload` in `dist/index.html` steht oder statisch vom Einstieg importiert wird — etwa weil Rolldown ein geteiltes Modul wie Vites Preload-Helper in den Chunk legt (Gruppen und Prioritäten: `codeSplitting` in `vite.config.ts`).
