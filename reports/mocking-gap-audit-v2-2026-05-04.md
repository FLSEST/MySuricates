# Mocking Gap Audit V2

**Scope:** HEAD~5..HEAD (32 changed files, 26 prod, 6 test)  
**Datum:** 2026-05-04

## Gesamturteil

**MEDIUM false-confidence risk.** Die neuen Features (DLQ, Chain-Sync, Multi-Field HumanInput) wurden mit Tests ausgeliefert, die ausschließlich den Happy Path mocken. Zwei echte Bugs existieren im Produktionscode (fehlender DLQ-Eintrag bei Exceptions, hardcodierter Google-Revocation-Endpoint). Die Mock-Abdeckung suggeriert Sicherheit, die nicht vorhanden ist — besonders im InstanceSyncHelper (203 Mocks, 0 Chain-Tests) und WorkflowExecutor (DLQ nie verifiziert).

---

## Confirmed Bugs

### 1. WorkflowExecutor: DLQ-Eintrag fehlt bei Exception-Path

**Problem:** `WorkflowExecutor.java:512-520` — Wenn eine unerwartete Exception außerhalb der Block-Schleife fliegt (z.B. NPE bei null-Workflow, DB-Timeout beim Steps-Laden), wird `run.setStatus(FAILED)` gesetzt und gespeichert, aber `deadLetterService.recordFailure()` wird NIE aufgerufen.

**Warum real:** Der DLQ-Aufruf steht nur im `else if (run.getStatus() == FAILED)`-Block (Zeile 497-505), der INNERHALB des try-Blocks liegt. Der äußere catch-Block (Zeile 512) setzt FAILED, speichert im finally, aber überspringt die DLQ-Aufnahme. Ergebnis: Silently lost failures die nie in der DLQ auftauchen.

**Kleinster Fix:** Im finally-Block, nach `runRepository.save(run)`, prüfen:
```java
if (run.getStatus() == RunStatus.FAILED) {
    try { deadLetterService.recordFailure(run); }
    catch (Exception ex) { log.warn("DLQ write failed: {}", ex.getMessage()); }
}
```

**Regressionstest:** Unit-Test der `WorkflowExecutionException` wirft und danach `verify(deadLetterService).recordFailure(any())` assertiert.

---

### 2. IntegrationOAuthService: Hardcoded Google-Revocation für alle Provider

**Problem:** `IntegrationOAuthService.java:354-365` — `revokeToken()` sendet JEDEN Token (Trello, Slack, GitHub) an `https://oauth2.googleapis.com/revoke`. Nicht-Google-Provider werden nie wirklich revoked.

**Warum real:** Es gibt aktuell Google Calendar, Google Drive, Gmail UND Trello-Integration. Bei Trello-Disconnect wird der Token an Google geschickt — Google ignoriert ihn, der Trello-Token bleibt gültig. Kein Security-Breach (Token werden lokal gelöscht), aber false sense of security.

**Kleinster Fix:** `OAuthConfig` um optionales `revocationEndpoint`-Feld erweitern. `revokeToken(String token, String revocationUrl)` — wenn null, skip.

**Regressionstest:** Unit-Test mit MockWebServer der prüft, dass für Provider X der korrekte Endpoint aufgerufen wird.

---

### 3. Kein dritter bestätigter Bug.

Die restlichen Findings (NPE bei null-Feldern in HumanInput, fehlende DB-Constraints) sind defensive-coding-Gaps, keine bestätigten Runtime-Bugs unter normaler Nutzung.

---

## High-Confidence Gaps

### 1. InstanceSyncHelper: Chain-Sync komplett ungetestet

**Blinder Fleck:** 126 Zeilen neuer Chain-Sync-Code (commit `db68bdd`), 0 dedizierte Tests. `chainRepository.findAll()` wird im Test-Setup als `List.of()` gemockt — der gesamte Chain-Sync-Pfad wird nie betreten.

**Plausible Fehlerklasse:** Partial-Sync hinterlässt Workflows auf dem Target ohne zugehörige Chains. Ein Abbruch nach Workflow-Sync aber vor Chain-Sync erzeugt inkonsistenten Zustand.

**Kleinste bessere Testform:** Unit-Test mit gemocktem HTTP-Client der Chain-Sync-Pfad durchspielt: neue Chain erstellen, existierende updaten, fehlende WorkflowId-Mapping → Error. Kein Slice-Test nötig.

---

### 2. DeadLetterController: Kein Controller-Test

**Blinder Fleck:** Neuer REST-Controller mit 4 Endpoints, kein einziger `@WebMvcTest`. Service-Test existiert, aber HTTP-Schicht (Routing, Serialisierung, Fehler-Mapping) ist ungeprüft.

**Plausible Fehlerklasse:** `IllegalArgumentException` wird als 400 statt 404 gemappt; Page-Serialisierung könnte mit Spring Boot-Version brechen; Status-Enum-Deserialisierung aus Query-Param nicht getestet.

**Kleinste bessere Testform:** `@WebMvcTest(DeadLetterController.class)` mit gemocktem Service — 3 Tests: list, retry-success, retry-not-found.

---

### 3. WorkflowExecutorTest: DLQ-Integration nie verifiziert

**Blinder Fleck:** `deadLetterService` wird als `@Mock` injiziert (Zeile 66), aber kein einziger Test ruft `verify(deadLetterService)` auf. Die DLQ-Anbindung könnte komplett kaputt sein ohne Test-Failure.

**Plausible Fehlerklasse:** Refactoring verschiebt den DLQ-Aufruf in eine Methode die nie aufgerufen wird, oder ändert die Bedingung — kein Test bricht.

**Kleinste bessere Testform:** Ergänzung im existierenden `execute_blockFails_withStopOnFailure_savesFailedRun` Test: `verify(deadLetterService).recordFailure(any(WorkflowRun.class))`.

---

### 4. HumanInputBlockExecutor: Malformed-Fields Crash-Path

**Blinder Fleck:** Test deckt Multi-Field Happy Path und JSON-Parse-Failure ab, aber nicht: null-Element in fields-Liste, fehlendes `variable_name` Feld, nicht-String variable_name.

**Plausible Fehlerklasse:** NPE wenn ein Workflow-Template-Import ein null-Element in der fields-Liste erzeugt (z.B. durch kaputten JSON-Merge im SyncHelper).

**Kleinste bessere Testform:** 2 Unit-Tests: (1) fields-Liste mit null-Element → graceful skip, (2) field ohne variable_name → skip mit Logging.

---

### 5. DeadLetterRepository: Keine Slice-Tests für Custom Queries

**Blinder Fleck:** Repository mit `findByStatusOrderByLastFailureAtDesc` — funktioniert dieser Sort auf TEXT-Timestamps korrekt? SQLite sortiert TEXT lexikographisch, was bei ISO-8601 stimmt, aber nie verifiziert.

**Plausible Fehlerklasse:** Timestamp-Format ändert sich (z.B. durch Library-Update), Sort bricht.

**Kleinste bessere Testform:** `@DataJpaTest` mit 3 Einträgen, verschiedene Timestamps, assert Sort-Order. Alternativ: als zu niedrige Priorität bewusst akzeptieren (Pattern ist konsistent im Projekt).

---

## Actionable TODOs

### [P0] DLQ-Eintrag im Exception-Path des WorkflowExecutors nachrüsten
`WorkflowExecutor.java:512-520` — DLQ-Aufruf in finally-Block verschieben oder im catch-Block ergänzen. Ohne Fix werden unerwartete Fehler nie in der DLQ sichtbar. Unit-Test: Exception werfen, `verify(deadLetterService).recordFailure()`.

### [P1] Chain-Sync-Tests für InstanceSyncHelper schreiben
`InstanceSyncHelperTest.java` — Mindestens 3 Tests: neue Chain synct, existierende updatet, fehlende Workflow-Mapping erzeugt Error. 126 Zeilen Prod-Code ohne jede Testabdeckung.

### [P1] WebMvcTest für DeadLetterController erstellen
Neuer Test `DeadLetterControllerTest.java` — GET /dlq (mit/ohne Status-Filter), POST /dlq/{id}/retry (found/not-found), DELETE /dlq/{id}. Verifiziert HTTP-Routing, Status-Codes, JSON-Serialisierung.

### [P1] WorkflowExecutorTest: DLQ-Verify ergänzen
In `execute_blockFails_withStopOnFailure_savesFailedRun` und neuem Exception-Test: `verify(deadLetterService).recordFailure(argThat(run -> run.getStatus() == RunStatus.FAILED))`.

### [P2] Revocation-Endpoint pro Provider konfigurierbar machen
`IntegrationOAuthService.java:354` — `OAuthConfig` um `revocationEndpoint` erweitern. In `disconnect()` nur aufrufen wenn vorhanden. Unit-Test mit MockWebServer.

### [P2] HumanInputBlockExecutor: Null-Safety für fields-Liste
`HumanInputBlockExecutor.java:154` — Null-Check vor `field.get()` ergänzen. 2 Tests für malformed configs.

### [P2] SyncDefinitionsRequest: chainIds validieren
Null/Empty-Guard in Controller oder Service bevor sync startet. Verhindert silent no-ops bei fehlerhaften Frontend-Requests.

---

## Was bewusst kein TODO ist

- **DeadLetterEntry ohne FK-Constraints:** Konsistent mit dem Rest des Schemas (SQLite, keine FKs). Aufwand/Nutzen-Verhältnis zu gering.
- **ExecutionContext.variables als ConcurrentHashMap:** Jeder Run läuft single-threaded in seinem Virtual Thread. Theoretisches Race, aber kein realer Zugriff von außen.
- **Frontend-Komponenten (DeadLetterQueue.tsx, SyncSelectModal.tsx, HumanInputDialog.tsx) ohne Component-Tests:** Reine Darstellungsschicht, keine Business-Logik. E2E nur sinnvoll wenn User-Flows instabil werden.
- **i18n-Dateien (de.ts, en.ts):** Keine Testlücke — Schlüssel sind typsicher, fehlende Keys erzeugen TypeScript-Fehler.
- **@DataJpaTest für DeadLetterRepository:** Low-Risk weil nur Standard-JPA-Methoden + eine einfache derived query. Sortierung auf ISO-Timestamps ist im Projekt etabliert.
