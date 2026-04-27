## Mocking Gap Audit V2

**Scope:** HEAD~5..HEAD (19 geaenderte Dateien, 14 Prod, 5 Test)
**Datum:** 2026-04-27

### Gesamturteil

**MEDIUM** false-confidence risk. Ein konkreter Security-Bug in der Mount-Gating-Logik (`ContainerShellService`) und eine semantische Inkonsistenz im Dashboard (`LearningService`) sind durch rein mock-basierte Tests nicht sichtbar. Die restlichen Findings (ExecutionSettingsService, workflow-pool JSONs) sind Low-Risk — korrekt implementiert, aber mit lueckenhafter Testabdeckung fuer die neu hinzugefuegten Settings-Keys. Die 10 workflow-pool JSONs sind reine Konfigurationsdateien ohne testbare Logik.

### Confirmed Bugs

**1. Security: Parent-Path-Bypass in `ContainerShellService.findForbiddenSegment`**
`ContainerShellService.java:205-210` — `FORBIDDEN_MOUNT_PATHS` enthaelt `/var/run/docker.sock`, aber `matchesForbidden` prueft nur `equals` und `startsWith(forbidden + "/")`. Ein Mount wie `/var/run:/data:rw` passiert alle Checks, weil `/var/run` weder gleich `/var/run/docker.sock` ist noch mit `/var/run/docker.sock/` beginnt. Im Container ist dann `docker.sock` unter `/data/docker.sock` erreichbar. Der Test `filterExtraMounts_dockerSocketTarget_isRejectedEvenWhenAllowed` (Zeile 173) testet nur den exakten Socket-Pfad, nicht Parent-Verzeichnisse.
- **Warum real:** Die defensive Absicht (Javadoc Zeile 30-33) ist klar formuliert, aber die Implementierung deckt Parent-Pfade nicht ab. Docker mountet das gesamte Verzeichnis.
- **Kleinster Fix:** `matchesForbidden` erweitern: zusaetzlich pruefen ob ein `forbidden`-Pfad mit `candidate + "/"` beginnt (d.h. der Mount ist ein Parent des verbotenen Pfads). Alternativ `/var/run` in `FORBIDDEN_MOUNT_PATHS` aufnehmen.
- **Kleinster Regressionstest:** Unit-Test fuer `findForbiddenSegment("/var/run:/data:rw")` — muss den Forbidden-Match zurueckgeben. Kein Docker noetig, reine Java-Logik.

**2. Dashboard: `runSuccess` ignoriert `COMPLETED_WITH_ERRORS` im Provider-Aggregat**
`LearningService.java:195` — `boolean runSuccess = run.getStatus() == RunStatus.COMPLETED` zaehlt `COMPLETED_WITH_ERRORS` nicht als Erfolg. Aber das globale `successfulRuns` (Zeile 110-111) zaehlt beide Status. Ergebnis: Die Provider-`successRate` ist systematisch pessimistischer als die globale `successRate` fuer denselben Datensatz. Kein Test ueberprueft diesen Fall — `getDashboard_computesOverview` stubbt `countByStatus(COMPLETED_WITH_ERRORS)` mit 0.
- **Warum real:** Semantische Inkonsistenz, die im UI als widerspruechliche Prozentwerte sichtbar wird.
- **Kleinster Fix:** Zeile 195 aendern zu `run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS`.
- **Kleinster Regressionstest:** Unit-Test in `LearningServiceTest.FacadeTests`: Run mit Status `COMPLETED_WITH_ERRORS` einfuegen, pruefen dass `providerStats.successRate` > 0.

**Kein dritter bestaetigter Bug.** Die uebrigen Findings sind Gaps, keine Bugs.

### High-Confidence Gaps

**1. Path-Traversal in `findForbiddenSegment` nicht normalisiert** (Confidence: 82)
`ContainerShellService.java:198-211` — `/etc/../etc/passwd:/data:ro` passiert den Check, weil `/etc/../etc/passwd` weder `equals("/etc")` noch `startsWith("/etc/")` matched. Docker normalisiert den Pfad selbst, also kein tatsaechlicher Container-Escape — aber die Java-Audit-Logik (ActivityLog-WARN) wird nicht getriggert. Kein Test deckt `..`-Segmente ab.
- **Plausible Fehlerklasse:** Lueckenhaftes Security-Audit-Log, falsches Sicherheitsgefuehl bei Log-Analyse.
- **Kleinste Testform:** Unit-Test fuer `findForbiddenSegment("/etc/../etc/passwd:/data:ro")` mit Erwartung != null.

**2. `buildDockerCommand`-Test injiziert verbotene Mounts direkt** (Confidence: 85)
`ContainerShellServiceTest.java:125` — `buildDockerCommand_withExtraMounts_addsAllBindMounts` uebergibt `/etc/timezone:/etc/timezone:ro` direkt an `buildDockerCommand`. In Produktion wuerde `filterExtraMounts` diesen Mount vorher ablehnen (`/etc` ist forbidden). Der Test suggeriert, dass verbotene Pfade Docker erreichen koennen — was im echten Flow nicht passiert.
- **Plausible Fehlerklasse:** Falsche Annahmen bei Refactoring, wenn jemand denkt der Test bilde den Prod-Flow ab.
- **Kleinste Testform:** Einen End-to-End-Test fuer `execute()` mit `extraMounts` inkl. Forbidden-Pfad hinzufuegen; assert dass der Mount nicht in der Docker-Command auftaucht.

**3. `execute()` mit `extraMounts`-Liste nie getestet** (Confidence: 88)
Kein Test ruft die 6-Argument-`execute()`-Methode mit nicht-leerer `extraMounts`-Liste auf. Die Verdrahtung `execute()` -> `filterExtraMounts()` -> `buildDockerCommand()` ist nur indirekt (ueber separate Tests der Einzelmethoden) abgedeckt.
- **Plausible Fehlerklasse:** Wiring-Bug bei Refactoring (z.B. falscher Methodenaufruf, Parameter-Reihenfolge).
- **Kleinste Testform:** Unit-Test mit gemocktem `ProcessBuilder`, der `execute()` mit 2 Mounts (1 erlaubt, 1 forbidden) aufruft und verifiziert, dass nur der erlaubte Mount in der Docker-Command landet.

**4. Project-Filter in `getDashboard(int, String)` ungetestet** (Confidence: 87)
`LearningService.java:122-131` — Der optionale `projectId`-Filter ist nie mit einem non-null Wert getestet. `safeGetWorkflow()` faengt Exceptions ab und gibt null zurueck; `getProject()` kann null sein (Guard vorhanden). Korrektheit ist plausibel, aber unbewiesen.
- **Plausible Fehlerklasse:** Stille Regression bei Refactoring von `safeGetWorkflow`.
- **Kleinste Testform:** Unit-Test in `FacadeTests` mit 2 Runs (verschiedene Projekte), `getDashboard(30, "project-A")` aufrufen, pruefen dass nur Runs von Project-A aggregiert werden.

**5. `getCurrentSettings()`-Test fehlt `assistantChatTimeoutSeconds`** (Confidence: 85)
`ExecutionSettingsServiceTest.java:167` — `containsKeys(...)` Assertion listet den neuen Key nicht auf. Die Methode liefert ihn (Service Zeile 159), aber der Test wuerde nicht brechen wenn er entfernt wird.
- **Plausible Fehlerklasse:** Stiller Verlust des Settings-Keys bei Refactoring.
- **Kleinste Testform:** `"assistantChatTimeoutSeconds"` zur bestehenden `containsKeys`-Liste hinzufuegen.

### Actionable TODOs

- **[P0] Parent-Path-Bypass in Mount-Gating fixen** — `ContainerShellService.matchesForbidden`: Zusaetzlich pruefen ob `forbidden.startsWith(normalized + "/")` (Candidate ist Parent eines verbotenen Pfads). Unit-Test fuer `/var/run:/data:rw`. Security-relevant, jetzt fixen.

- **[P1] `runSuccess`-Inkonsistenz im Dashboard beheben** — `LearningService.java:195`: `COMPLETED_WITH_ERRORS` als Erfolg werten (wie global). Unit-Test mit diesem Status in Provider-Aggregation. Sichtbar im UI als widerspruechliche Prozentwerte.

- **[P1] Path-Traversal-Normalisierung in `findForbiddenSegment`** — `ContainerShellService.java:198`: `Path.of(segment).normalize().toString()` auf Host- und Container-Seite anwenden. Unit-Tests fuer `../`, `./`, doppelte Slashes. Haertet das Security-Audit-Log.

- **[P1] End-to-End-Test fuer `execute()` mit Extra-Mounts** — `ContainerShellServiceTest`: Neuen Test der die 6-arg `execute()` mit gemischten Mounts (erlaubt + forbidden) aufruft. Verifiziert die Verdrahtung `execute` -> `filterExtraMounts` -> `buildDockerCommand`. Kein Docker noetig (ProcessBuilder gemockt).

- **[P2] `IntegrationStats.provider` zu `integrationId` umbenennen** — `DashboardResponse.java:53`: Feld semantisch korrekt benennen um Verwechslung mit AI-Provider zu vermeiden. Frontend-Anpassung noetig. Verhindert falsche Labels im UI.

- **[P2] `getCurrentSettings()`-Test um neue Keys erweitern** — `ExecutionSettingsServiceTest.java:167`: `"assistantChatTimeoutSeconds"` zur `containsKeys`-Assertion hinzufuegen. Einzeiler-Fix.

- **[P2] `init()`-DB-Override-Tests fuer neue Settings** — `ExecutionSettingsServiceTest.java:64-82`: `init_withDbOverrides_appliesThem` und `init_ignoresOutOfBoundsDbValue` um `allow-extra-mounts` und `assistant-chat-timeout-seconds` erweitern. Besonders `allow-extra-mounts` ist security-relevant — ein korrupter DB-Wert darf das Gate nicht oeffnen.

- **[P2] Project-Filter-Test in LearningService** — `LearningServiceTest.FacadeTests`: Test mit non-null `projectId` und 2 Runs verschiedener Projekte. Verifiziert korrekte Filterung und Robustheit von `safeGetWorkflow`.

### Was bewusst kein TODO ist

- **Workflow-Pool JSONs (10 Dateien):** Reine Konfigurationsdateien ohne testbare Logik. Werden durch den Workflow-Executor validiert; separate Unit-Tests waeren Scheinsicherheit.
- **Mock-Reduktion in LearningServiceTest (68 Mocks):** Die Architektur (4 Sub-Services mit geteilten Mock-Repos, Facade-Test fuer Wiring) ist angemessen. Die Mock-Anzahl reflektiert die Breite, nicht die Tiefe — kein Refactoring noetig.
- **Mock-Reduktion in ExecutionSettingsServiceTest (35 Mocks):** Duenne Fassade ueber Config-Objekte und Repository. Mocks sind hier das richtige Werkzeug. Reale Config-Objekte (HostClaudeConfig etc.) werden direkt verwendet — State-Roundtrip ist abgedeckt.
- **`allowDockerSocketMount` runtime-mutable machen:** Design-Asymmetrie (nur per Restart aenderbar vs. `allowExtraMounts` live). Ist ein bewusstes Defense-in-Depth: Die gefaehrlichere Einstellung soll schwerer zu aendern sein. Kein Bug, kein TODO.
- **Frontend-E2E-Tests:** Kein Frontend-Code im Diff-Scope betroffen, der nicht durch Backend-Tests abgedeckt waere. E2E waere Overhead ohne Erkenntnisgewinn.
