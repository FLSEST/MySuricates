# Mocking Gap Audit V2 — 2026-04-20

**Scope**: `HEAD~5..HEAD` (5 Commits: progressive disclosure, pending-tool gate, CLI read-only lock, dev-starter fix, version bump 0.7.0)
**Analysiert**: 29 Prod-Dateien, 14 Test-Dateien, 11 Mock-Heavy-Findings, 4 Findings mit Realismus-Signalen

---

## Gesamturteil

**MEDIUM false-confidence risk.** Die Tests decken die neuen Features (Pending-Tool-Gate, Progressive Disclosure, Fetch-Service) auf Unit-Ebene solide ab. Die ContextSection-Tests und PendingToolRegistryTest arbeiten mit echten Objekten — das ist gut. Aber der kritische Pfad `AssistantService.chat()` — insbesondere die Fetch-Loop-Logik (2 Runden, enriched prompt, break-on-blank) — hat **null Testabdeckung** trotz erheblicher Komplexitaet. Die CLI-Exit-Code-Auswertung fehlt komplett. In Summe: Die Einzelbausteine sind getestet, aber die Orchestrierung in `AssistantService` verlaesst sich vollstaendig auf Mocks, die kritische Fehlerpfade unsichtbar machen.

Die **unstaged Changes** (ClaudeJsonResponse.model(), ClaudeOutputParser assistantModel-Extraktion, CommandExecutorService aiRequest-Metadata) sind sauber getestet, aber noch nicht committet.

---

## Confirmed Bugs

### 1. `AssistantToolExecutor.createWorkflow` — `projectId` nicht auf Blank geprueft

**Datei**: `AssistantToolExecutor.java:214`
**Problem**: `projectId` wird nur auf `null` geprueft, nicht auf `isBlank()`. Im selben Guard wird `name` korrekt mit `isBlank()` geprueft — inkonsistente Validierung.
**Warum real**: Ein LLM-generierter JSON-Payload `{"projectId": "   ", "name": "Test"}` passiert die Validierung und erreicht `WorkflowService.create()` mit einem Whitespace-Only-projectId. Das fuehrt entweder zu einem kryptischen JPA-Fehler oder zu einem Workflow ohne gueltige Projektzuordnung.
**Kleinster Fix**: `projectId == null || projectId.isBlank()` in Zeile 214.
**Kleinster Regressionstest**: Unit-Test in `AssistantToolExecutorTest` mit `{"projectId": " ", "name": "x"}` — erwarte `⚠`-Meldung.

### 2. `ClaudeJsonResponse.model()` — nicht-deterministisch bei Multi-Model-Usage (pre-fix)

**Datei**: `ClaudeJsonResponse.java:model()` (vor dem unstaged Fix)
**Problem**: Bei Multi-Model-Runs (z.B. Haiku fuer Tool-Use + Sonnet als Hauptmodell) gab `modelUsage.keySet().stream().findFirst()` das falsche Modell zurueck — abhaengig von HashMap-Iteration-Order.
**Warum real**: Agentic-Blocks mit Tool-Use erzeugen Multi-Model-Eintraege. Das angezeigte Modell in Run-Metadata war zufaellig falsch.
**Status**: Fix existiert in unstaged Changes (`assistantModel`-Feld, Preference-Logik). Test auch vorhanden. **Muss committet werden.**

### 3. Keine weiteren bestaetigten Bugs

Die uebrigen von der Heuristik gemeldeten Kandidaten (unwrap()-Logik, readOnly-Hardwiring, doppeltes sanitizeJson) sind entweder by-design, harmlos-redundant oder betreffen nur nicht-realistische Exception-Chains.

---

## High-Confidence Gaps

### 1. AssistantService Fetch-Loop — null Testabdeckung

**Bereich**: `AssistantService.java:132-160`
**Blinder Fleck**: Die gesamte Fetch-Loop-Logik (2 Runden, enriched prompt Aufbau, break-on-blank-supplemental, "Keine weiteren Fetches"-Hint in Runde 2, stripFetchTags am Ende) ist komplett ungetestet. `fetchService.parseFetchRequests()` wird in keinem Test mit nicht-leerer Rueckgabe gestubbt.
**Plausible Fehlerklasse**: Endlos-Loop bei fehlerhafter Break-Condition; Token-Explosion durch akkumulierte Supplemental-Daten; leere Antwort bei blank-supplemental-break ohne strip.
**Kleinste bessere Testform**: Unit-Test: `commandExecutorService` stub → erste Antwort mit `<fetch>` Tags, `fetchService.parseFetchRequests` stub → nicht-leere Liste, zweiter `executeOnHost` stub → finale Antwort. Verifiziere: 2x `executeOnHost` aufgerufen, `stripFetchTags` am Ende, finale Antwort korrekt.

### 2. AssistantService — CLI Exit-Code wird ignoriert

**Bereich**: `AssistantService.java:118-129` (chat-Methode)
**Blinder Fleck**: `commandExecutorService.executeOnHost()` liefert `EnrichedCommandResult(output, exitCode, parsed)`. Die `chat()`-Methode liest nur `result.output()` — `exitCode` wird nie geprueft. Bei Exit-Code != 0 (z.B. ungueltigem API-Key, CLI-Crash) wird die Fehlermeldung als normale Antwort geparst.
**Plausible Fehlerklasse**: User sieht eine verwirrende Fehlermeldung als "Assistenten-Antwort" statt eines klaren Fehlers.
**Kleinste bessere Testform**: Unit-Test mit `stubClaude("error text", 1)` — erwarte Error-Response, nicht Success.

### 3. CommandExecutorService.writeSkillContextToProject — Filesystem-I/O ohne Test

**Bereich**: `CommandExecutorService.java:218-252`
**Blinder Fleck**: Direkter `Files.createDirectories()` + `Files.writeString()` + `Files.deleteIfExists()` Aufruf. Kein Test deckt: erfolgreiche Datei-Erstellung, Cleanup bei leerem Context, IOException bei readonly Filesystem.
**Plausible Fehlerklasse**: Skill-Context-Datei fehlt zur Laufzeit (silent `log.warn`), Verzeichnis-Reste bleiben nach Cleanup liegen.
**Kleinste bessere Testform**: Unit-Test mit `@TempDir` — echtes Dateisystem, kein Mock. Pruefe: Datei existiert nach write, Datei geloescht nach write-null.

### 4. PendingToolRegistry TTL-Ablauf — Controller-410-Pfad ungetestet

**Bereich**: `PendingToolRegistry.java` (TTL 30min) + `AssistantController.confirmPendingTool()`
**Blinder Fleck**: `PendingToolRegistryTest` prueft `cleanupExpired()` und `take()` isoliert. Aber kein Test prueft den realen Pfad: Tool registriert → TTL abgelaufen → User klickt "Bestaetigen" → Controller gibt 410 zurueck. Die Test-Helper `runAllTools()` in `AssistantToolExecutorTest` umgeht `registry.take()` und ruft `executeConfirmed()` direkt auf.
**Plausible Fehlerklasse**: Frontend zeigt Bestaetigungs-Button fuer abgelaufenes Tool, Klick fuehrt zu unbehandeltem 410.
**Kleinste bessere Testform**: `@WebMvcTest(AssistantController.class)` mit echtem `PendingToolRegistry` — registriere Tool, rufe `cleanupExpired()`, dann POST `/confirm` → erwarte 410.

### 5. `@PostConstruct seedSettings()` nicht in Test-setUp aufgerufen

**Bereich**: `AssistantServiceTest.java`
**Blinder Fleck**: 9 von 10 Tests laufen mit `cachedBaseInstructions = null` und `cachedAssistantModel = null`. Die Mocks verbergen, dass `contextBuilder.buildSystemPrompt(pageContext, null)` mit `null`-Base-Instructions aufgerufen wird. Falls `buildSystemPrompt` jemals eine Null-Guard-Aenderung bekommt, bricht der reale Pfad, aber der Test bleibt gruen.
**Kleinste bessere Testform**: `@BeforeEach` um `service.seedSettings()` erweitern; `appSettingsRepository.get("assistant-system-prompt")` stubben, um den realen PostConstruct-Pfad auszufuehren.

---

## Actionable TODOs

1. **[P0] Fix: `projectId.isBlank()`-Check in `AssistantToolExecutor.createWorkflow`**
   `AssistantToolExecutor.java:214` — `projectId == null` zu `projectId == null || projectId.isBlank()` aendern. Unit-Test mit Blank-projectId hinzufuegen. Verhindert ungueltige Workflows durch LLM-Halluzination.

2. **[P1] Unstaged ClaudeJsonResponse/OutputParser-Fix committen**
   Die `assistantModel`-Logik in `ClaudeJsonResponse.model()`, `ClaudeOutputParser`, und `CommandExecutorService.buildAiRequestMetadata()` inkl. Tests ist fertig aber nicht committet. Ohne Commit geht die Arbeit bei einem `git checkout` verloren.

3. **[P1] Fetch-Loop-Tests in AssistantServiceTest hinzufuegen**
   Mindestens 3 Tests: (a) eine Fetch-Runde mit supplemental, (b) zwei Fetch-Runden mit Limit-Hint, (c) break-on-blank-supplemental. Deckt den kritischsten untesteten Pfad im Assistant-Feature ab.

4. **[P1] Exit-Code-Handling in AssistantService.chat() einfuegen**
   `result.exitCode() != 0` pruefen und als `WorkflowExecutionException` werfen, bevor die Antwort geparst wird. Test: `stubClaude("API key invalid", 1)` → Error-Response.

5. **[P2] writeSkillContextToProject mit @TempDir testen**
   `CommandExecutorServiceTest` — 3 Tests: write-success, write-null-cleanup, IOException-handling. Nutzt echtes Dateisystem, kein Mock.

6. **[P2] PendingTool-Ablauf als WebMvcTest testen**
   `AssistantControllerTest` — Tool registrieren, TTL ablaufen lassen (oder `cleanupExpired()`), dann POST confirm → 410. Verifiziert den User-facing Ablauf-Pfad.

7. **[P2] seedSettings() in AssistantServiceTest.setUp() aufrufen**
   `@BeforeEach` erweitern: `appSettingsRepository.get()` stubben, `service.seedSettings()` aufrufen. Alle Tests laufen dann mit initialisierten cached-Werten.

8. **[P3] Dead-Code `extractCommand()` in 6-arg executeStep entfernen**
   `CommandExecutorService.java:84` — `String command = extractCommand(block);` ist unused. Entweder entfernen oder den Wert tatsaechlich nutzen.

---

## Was bewusst kein TODO ist

- **AssistantContextBuilder (101 Mock-Signale)**: Die hohe Mock-Zahl kommt von 12+ Repository-Stubs, aber die ContextSection-Objekte werden real instantiiert. Fuer eine Klasse, die Daten aus vielen Quellen aggregiert und als String formatiert, ist Unit-Testing mit gemockten Repos die richtige Teststufe. Kein Umbau noetig.

- **Frontend Assistant-Komponenten (P0 laut Heuristik)**: `AssistantPanel.tsx`, `AssistantMessage.tsx`, `useAssistant.ts` haben keine Tests. Aber es gibt keine E2E-Test-Infrastruktur im Projekt, und die Aenderungen sind inkrementelle UI-Updates (Pending-Tool-Buttons, Tool-Confirmation-Flow). Der Backend-Vertrag ist durch `AssistantControllerTest` abgedeckt. E2E wuerde hier mehr Infrastruktur-Investment brauchen als der aktuelle Risk-Level rechtfertigt.

- **readOnly=true in executeOnHost**: Alle aktuellen Aufrufer (Assistant-Chat, Conditional-Evaluator, While-Evaluator) sollen read-only sein. `SkillCapabilityBlockExecutor` nutzt den gleichen Pfad, aber das war schon vor HEAD~5 so — kein neuer Regression-Risk.

- **Doppeltes `sanitizeJson()` in AssistantToolExecutor**: Idempotent, kein Fehlerpotential, nur redundante CPU-Zyklen. Nicht den Aufwand eines Refactorings wert.

- **Broadcaster-Statussequenz (BUILDING_CONTEXT → THINKING → ...)**: WebSocket-Status-Updates sind UX-Enhancement, kein Korrektheitskriterium. Wenn eine Status-Nachricht fehlt, funktioniert der Chat trotzdem. Kein Test noetig.
