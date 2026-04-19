## Mocking Gap Audit V2

**Scope**: `HEAD~5..HEAD` — 4 Prod-Dateien, 6 Test-Dateien, 337 Zeilen hinzugefuegt
**Datum**: 2026-04-13
**Commits**: 80ede03..7499051 (5 Fix-Commits aus vorherigem Audit)

### Gesamturteil

**LOW** false-confidence risk. Die 5 Commits haben die wesentlichen Luecken aus dem V1-Audit geschlossen: HealthAutoTodoService hat jetzt korrekte State-Mutation nach Persistenz, InstanceSyncHelper hat null-Guards fuer httpGet, RateLimitFilter hat Body-Assertions und einen Concurrency-Test. Die verbleibenden Gaps sind entweder Defense-in-Depth-Verbesserungen oder betreffen Pfade, die im Fehlerfall nur zu Log-Warnungen fuehren, nicht zu Datenverlust.

### Confirmed Bugs

Kein bestaetigter Bug. Die letzte Commit-Serie hat die vorherigen latenten Defekte korrekt behoben.

### High-Confidence Gaps

1. **RateLimitFilter: `X-Internal-Sync` Header-Bypass vor Authentifizierung** (`RateLimitFilter.java:42`, neu in 7391a8d)
   - **Blinder Fleck**: `shouldNotFilter()` gibt `true` zurueck, wenn `X-Internal-Sync: true` gesetzt ist. RateLimitFilter ist ein `@Component` und laeuft *vor* dem Spring Security Filter Chain (ApiKeyAuthFilter). Jeder externe Client — auch unauthentifiziert — kann diesen Header setzen und Rate-Limiting komplett umgehen.
   - **Fehlerklasse**: Rate-Limit-Bypass → erleichtert Brute-Force/DoS gegen den Auth-Layer.
   - **Kleinste Testform**: Unit-Test, der verifiziert, dass `shouldNotFilter` fuer nicht-vertrauenswuerdige RemoteAddr trotz Header `false` liefert. Oder im Prod-Code: Bypass nur wenn `remoteAddr` in `trustedProxies` liegt.

2. **InstanceSyncHelper: `markStableSynced()` ist in allen Tests stillschweigend kaputt** (`InstanceSyncHelper.java:512-522`)
   - **Blinder Fleck**: `DataSource` ist gemockt → `getConnection()` gibt `null` zurueck → `NullPointerException` wird im catch-Block (Zeile 519) verschluckt. `markStableSynced` wird nach jedem Workflow-Sync aufgerufen, aber kein Test verifiziert, dass `stable_synced_at` tatsaechlich gesetzt wird. Wenn das SQL falsch waere, wuerde es kein Test bemerken.
   - **Fehlerklasse**: Stilles Scheitern einer DB-Operation → `stable_synced_at` koennte in Produktion nie gesetzt werden, Sync-UI zeigt falsche Timestamps.
   - **Kleinste Testform**: `verify(dataSource).getConnection()` im bestehenden Test oder dedizierter Test mit gemocktem `Connection`/`PreparedStatement`, der den SQL-Aufruf verifiziert.

3. **InstanceSyncHelper: Kombinierter `projectIds + workflowIds` Filter ungetestet** (`InstanceSyncHelper.java:190-196`)
   - **Blinder Fleck**: Wenn sowohl `projectIds` als auch `workflowIds` gesetzt sind, werden die Projekte der ausgewaehlten Workflows automatisch zu `selectedProjectIds` hinzugefuegt (Auto-Include-Logik). Kein Test deckt diesen Pfad ab. Bestehende Tests testen nur projectIds-only oder workflowIds-only.
   - **Fehlerklasse**: Falsche Workflow-Selektion bei gemischtem Filter → zu viele oder zu wenige Workflows gesynct.
   - **Kleinste Testform**: Test mit `SyncDefinitionsRequest(List.of("p1"), List.of("wf2"), null)` wobei wf2 in p2 liegt. Erwartung: wf2 wird gesynct (p2 wird auto-included), aber wf1 (in p1) nicht.

4. **InstanceSyncHelper: Projektlose Workflows bei projectIds-Filter stillschweigend uebersprungen** (`InstanceSyncHelper.java:271-275`)
   - **Blinder Fleck**: Wenn `selectedProjectIds != null`, werden Workflows ohne Projekt uebersprungen (`wfProjectId == null`). Ob das beabsichtigt ist, ist unklar — kein Test deckt diesen Edge-Case explizit ab.
   - **Fehlerklasse**: Projektlose Workflows werden bei selektivem Sync nie gesynct, auch wenn sie explizit per workflowIds ausgewaehlt sind (da der projectIds-Filter danach greift).
   - **Kleinste Testform**: Test mit `SyncDefinitionsRequest(List.of("p1"), List.of("wf3"), null)` wobei wf3 kein Projekt hat. Erwartung: wf3 wird trotzdem gesynct.

5. **RateLimitFilter: `trustedProxies`-Parsing erstellt neues Set pro Request, trimmt nicht** (`RateLimitFilter.java:74`)
   - **Blinder Fleck**: `Set.of(trustedProxies.split(","))` erzeugt pro Request ein neues Set und trimmt keine Whitespace. Config-Wert `"127.0.0.1, ::1"` (mit Leerzeichen) fuehrt dazu, dass IPv6-Localhost nie matcht → X-Forwarded-For wird ignoriert → falsche Client-Zuordnung.
   - **Fehlerklasse**: Latente Config-Parsing-Fragiliaet + Performance (Set-Allokation pro Request).
   - **Kleinste Testform**: Unit-Test mit `trustedProxies = "127.0.0.1, ::1"` (mit Leerzeichen) und `remoteAddr = "::1"`. Erwartung: Forwarded-For wird trotzdem beruecksichtigt.

### Actionable TODOs

1. **[P1] RateLimitFilter: `X-Internal-Sync`-Bypass an RemoteAddr binden**
   `RateLimitFilter.java:42` — Bypass nur wenn `remoteAddr` in `trustedProxies` liegt: `return trustedProxies.contains(request.getRemoteAddr()) && "true".equals(request.getHeader("X-Internal-Sync"))`. Neuen Test: externer Client mit Header wird trotzdem rate-limited.

2. **[P1] InstanceSyncHelper: `markStableSynced()` verifizieren**
   `InstanceSyncHelperTest.java` — In `sync_existingWorkflow_updatesOnTarget` und `sync_newWorkflow_importsOnTarget`: Mock-Chain fuer `DataSource → Connection → PreparedStatement` aufbauen. Verifizieren: `ps.setString(1, ...)` wird mit korrektem Workflow-ID aufgerufen. Damit ist das raw-JDBC-SQL abgesichert.

3. **[P2] InstanceSyncHelper: Kombinierten projectIds+workflowIds-Filter testen**
   `InstanceSyncHelperTest.java` — Neuer Test: `SyncDefinitionsRequest(List.of("p1"), List.of("wf2"), null)` wobei wf2.project=p2. Assertion: p2 wird auto-included, wf2 wird gesynct, wf1 (in p1 aber nicht in workflowIds) wird nicht gesynct.

4. **[P2] InstanceSyncHelper: Edge-Case projektloser Workflow bei projectIds-Filter testen**
   `InstanceSyncHelperTest.java` — Neuer Test: Workflow ohne Projekt + selektiver projectIds-Filter. Klaeren und dokumentieren, ob Skip-Verhalten beabsichtigt ist.

5. **[P2] RateLimitFilter: trustedProxies-Parsing robuster machen**
   `RateLimitFilter.java:74` — `Set.of(trustedProxies.split(","))` ersetzen durch ein im Konstruktor initialisiertes `Set<String>` mit `Arrays.stream(trustedProxies.split(",")).map(String::trim).collect(...)`. Eliminiert Whitespace-Problem und Set-Allokation pro Request.

### Was bewusst kein TODO ist

- **HealthAutoTodoService Mock-Heavy Style**: Die State-Mutation wurde in Commit `700c2af` korrekt nach die Persistenz verschoben. Der Test `saveFails_retriesTransitionOnNextTick` verifiziert Retry-Verhalten. Kein weiterer Handlungsbedarf.
- **InstanceSyncHelper `httpGet=null`-Guard**: Wurde in Commit `7391a8d` gefixt. Tests `sync_targetUnreachable_collectsErrors` und `sync_httpGetReturnsNull_skipsSyncAndCollectsErrors` verifizieren korrekt `projectsSynced == 0` und `never().httpPost()`.
- **RateLimitFilter JSON-Body-Assertion**: Wurde in Commit `7499051` ergaenzt — `assertThat(stringWriter.toString()).contains("retryAfterSeconds")` und `.contains("Rate limit exceeded")` sind vorhanden.
- **RateLimitFilter Concurrency-Test**: Wurde in Commit `7499051` hinzugefuegt — 20 Threads, limit=10, exakt 10 pass / 10 blocked. Das `synchronized + AtomicInteger`-Pattern ist korrekt; die theoretische ±1-Abweichung an Window-Grenzen ist ein akzeptabler Trade-off.
- **TestingService Docker-Orchestrierung**: `start()`, `stop()`, `readContainerApiKey()` sind korrekte Mock-Grenzen. Ein Docker-Integrationstest waere teuer und fragil.
- **E2E-Tests**: Kein Frontend im Scope dieser Commits.
