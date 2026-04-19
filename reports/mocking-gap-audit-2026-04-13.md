## Mocking Gap Audit

**Scope:** HEAD~5..HEAD (4 Prod-Dateien, 5 Test-Dateien)
**Datum:** 2026-04-13

### Gesamturteil

**HIGH false-confidence risk.** Alle vier geaenderten Bereiche sind ausschliesslich mit Mockito-Unit-Tests abgedeckt (386 Mock-Hits, 0 Realism-Hits). Das waere bei reiner Geschaeftslogik akzeptabel, aber hier liegen die Aenderungen an **Systemgrenzen**: HTTP-Servlet-Filter (Security), JPQL-Queries (Datenbank), HTTP-Aufrufe zu Staging-Instanzen (Netzwerk) und Docker-Container-Orchestrierung. An jeder dieser Grenzen koennen Mocks reale Fehlerklassen nicht aufdecken — von Race Conditions im RateLimitFilter ueber nie gegen eine echte DB validierte JPQL-Queries bis hin zu einem konkreten **Infinite-Loop-Bug** in `pollTestRun`. Der gefundene Bug (Integer-Division `intervalMs/1000 = 0` bei Werten < 1000ms) ist ein Paradebeispiel: Die Tests nutzen immer `intervalMs=1000`, der Bug tritt erst bei anderen Werten auf und wurde nie getestet.

---

### Kritische Luecken

#### 1. RateLimitFilter: Race Condition im ClientBucket (Confidence: 92)

- **Bereich:** `RateLimitFilter.ClientBucket.tryConsume()` — `count.incrementAndGet()` liegt **ausserhalb** des `synchronized(windowLock)`-Blocks
- **Blinder Fleck:** Unter Concurrent Load koennen mehrere Threads gleichzeitig das Limit ueberschreiten (TOCTOU: Fenster-Reset im Lock, Increment ausserhalb). Tests rufen `doFilterInternal` sequentiell auf einem einzigen Thread auf.
- **Fehlerklasse:** Rate-Limit wird um die Anzahl gleichzeitiger Threads ueberschritten — Denial-of-Service-Schutz versagt genau dann, wenn er gebraucht wird
- **Kleinste Testform:** Plain JUnit mit `CountDownLatch` + 20 Threads auf einer `ClientBucket`-Instanz — kein Spring-Context noetig

#### 2. RateLimitFilter: Kein Integrationstest mit echtem Servlet-Filter-Stack (Confidence: 88)

- **Bereich:** `shouldNotFilter()`, `X-Forwarded-For`-Spoofing-Schutz, 429-Response-Body, Filter-Ordering relativ zu `ApiKeyAuthFilter`
- **Blinder Fleck:** Tests rufen `doFilterInternal` direkt auf — `OncePerRequestFilter.doFilter()` wird nie durchlaufen. `shouldNotFilter` wird als Boolean-Praedikat geprueft, nicht als tatsaechlicher Bypass. Der 429-Response-Body (Text-Block mit fuehrendem `\n`) wird nie ausgelesen. Unauthentifizierte Requests verbrauchen Rate-Limit-Quota, weil der Filter vor `ApiKeyAuthFilter` liegt — kein Test deckt diese Ordering-Interaktion ab.
- **Fehlerklasse:** IP-Spoofing ueber `X-Forwarded-For` von nicht-vertrauenswuerdigen Proxies; Quota-Exhaustion-Angriff durch unauthentifizierte Requests; malformed JSON-Body bei 429
- **Kleinste Testform:** `@WebMvcTest`-Slice mit `MockMvc` (importiert `RateLimitFilter`, `SecurityConfig`, `ApiKeyAuthFilter`) — testet den echten Filter-Stack inkl. Header, Body und Ordering

#### 3. HealthAutoTodoService: JPQL-Query nie gegen echte DB validiert (Confidence: 95)

- **Bereich:** `TodoRepository.findOpenByReferenceTypeAndId()` — JPQL `status <> 'DONE'` auf `@Enumerated(EnumType.STRING)` Spalte
- **Blinder Fleck:** Mock gibt `Optional.empty()` oder ein hartcodiertes Todo zurueck. Ob die JPQL-Query gegen das reale SQLite-Schema korrekt aufloest (Enum-Mapping, Case-Sensitivity, NOT-NULL-Constraints bei `@Builder.Default`) ist nie geprueft.
- **Fehlerklasse:** Duplizierte Health-Todos bei jedem 60s-Zyklus, wenn Enum-Mapping falsch ist; `DataIntegrityViolationException` wenn `status` durch Lombok-Edge-Case `null` wird; User-erstellte Todos mit gleicher `referenceType` werden beim Recovery stillschweigend auf DONE gesetzt
- **Kleinste Testform:** `@DataJpaTest` — testet Query-Ergebnisse, NOT-NULL-Constraints und Builder-Defaults gegen die echte Schema-Migration

#### 4. InstanceSyncHelper: HTTP-Body-Inhalt nie verifiziert (Confidence: 95)

- **Bereich:** `httpPost`/`httpPut`-Aufrufe in `doSyncDefinitionsTo()` — Projekt-, Workflow- und Skill-Sync
- **Blinder Fleck:** Tests nutzen `any()` als Body-Matcher. Die `projectId`-Mapping-Logik (Dev-ID → Target-ID) wird nie gegen den tatsaechlichen POST-Body geprueft. Wenn `targetProjectId` null ist, fehlt das Feld stillschweigend — kein Test bemerkt das.
- **Fehlerklasse:** Workflows landen ohne Projekt-Zuordnung auf der Zielinstanz; fehlende oder falsche Felder im Import-JSON
- **Kleinste Testform:** `ArgumentCaptor` auf bestehenden Spy-Tests (Zero-Cost-Addition) oder WireMock fuer den gesamten HTTP-Stack

#### 5. InstanceSyncHelper: `markStableSynced()` JDBC ist No-Op (Confidence: 92)

- **Bereich:** Raw-JDBC-Update `UPDATE workflows SET stable_synced_at = ? WHERE id = ?` via gemocktem `DataSource`
- **Blinder Fleck:** SQL-String, Parameter-Binding-Reihenfolge und Connection-Handling (pool-size=1 Deadlock-Risiko) werden nie getestet
- **Fehlerklasse:** Spaltenname-Typo, Parameter 1/2 vertauscht, Deadlock bei pool-size=1 wenn JDBC-Call innerhalb einer bestehenden Transaktion liegt
- **Kleinste Testform:** `@DataJpaTest` oder `@JdbcTest`-Slice mit echtem SQLite/H2

#### 6. TestingService: Infinite-Loop-Bug in `pollTestRun` (Confidence: 95)

- **Bereich:** `pollTestRun()` — `waited += intervalMs / 1000` (Integer-Division)
- **Blinder Fleck:** Bei `intervalMs < 1000` (z.B. 500ms) ergibt `500 / 1000 = 0` — `waited` wird nie inkrementiert, die Schleife laeuft endlos. Tests nutzen immer `intervalMs=1000`, wodurch der Bug verborgen bleibt.
- **Fehlerklasse:** **Konkreter Bug**, kein theoretisches Risiko. Jeder Aufrufer mit Sub-Sekunden-Intervall haengt den Thread dauerhaft.
- **Kleinste Testform:** Bestehender Unit-Test mit `intervalMs=500` und `maxWaitSeconds=1` — muss `IllegalStateException` oder Timeout werfen statt endlos zu laufen

#### 7. TestingService: API-Key-Propagation nie am Draht verifiziert (Confidence: 90)

- **Bereich:** `withTestApiKey()` → `readContainerApiKey()` → `syncHelper.setTargetApiKeyOverride()` → HTTP-Aufrufe
- **Blinder Fleck:** `InstanceSyncHelper` ist ein `@Mock` — `setTargetApiKeyOverride` ist ein No-Op. `readContainerApiKey` fuehrt echtes `docker exec` aus (nicht gespied in Poll-Tests), gibt im CI `null` zurueck, und der Null-Key wird stillschweigend akzeptiert. Kein Test prueft, ob der `X-API-Key`-Header jemals auf einem HTTP-Request landet.
- **Fehlerklasse:** 401-Fehler von der Test-Instanz werden als "Could not fetch workflows" angezeigt — verwirrende Fehlermeldung, echte Ursache unsichtbar
- **Kleinste Testform:** WireMock-basierter Test, der den `X-API-Key`-Header auf ausgehenden Requests verifiziert

---

### Empfohlene Teststrategie

1. **`ClientBucket` Concurrency-Test (Unit, kein Spring):**
   `CountDownLatch` + 20 Threads auf `tryConsume(limit=10)`. Assertion: Gesamtzahl erlaubter Calls ≤ `limit`. Deckt Race-Condition ab und ist in <1s ausgefuehrt. Gleichzeitig den Bug fixen: `count.incrementAndGet()` in den `synchronized`-Block verschieben.

2. **`@WebMvcTest`-Slice fuer RateLimitFilter (Security-Grenze):**
   Import: `RateLimitFilter`, `RateLimitConfig`, `SecurityConfig`, `ApiKeyAuthFilter`. Testfaelle: IP-Spoofing-Abwehr, 429-Body-Validierung, Filter-Ordering mit Auth, `/ws`-Bypass. Deckt 4 Luecken in einer Testklasse ab.

3. **`@DataJpaTest` fuer TodoRepository Health-Queries (DB-Grenze):**
   Testen: `findOpenByReferenceTypeAndId` mit DONE/OPEN/IN_PROGRESS-Status, Builder-Default-Validierung (NOT-NULL), Detached-Entity-Merge fuer Status-Update. Deckt JPQL-Korrektheit und Constraint-Violations ab.

4. **`ArgumentCaptor` auf bestehende InstanceSyncHelper-Spy-Tests (Zero-Cost):**
   `any()` durch `argThat(body -> body.containsKey("projectId"))` ersetzen oder `ArgumentCaptor<Map>` nutzen. Faengt fehlende/falsche Felder im Sync-Payload ab, ohne neue Testklasse. Sofortige Verbesserung bei minimalem Aufwand.

5. **`pollTestRun`-Bug fixen + Regression-Test (Unit):**
   Integer-Division durch Millisekunden-Tracking ersetzen (`waited += intervalMs`, Vergleich gegen `maxWaitSeconds * 1000`). Test mit `intervalMs=500` hinzufuegen. Deckt den konkreten Infinite-Loop-Bug ab.

6. **WireMock fuer InstanceSyncHelper HTTP-Stack (optional, hoher Hebel):**
   Ersetzt den Spy-auf-`httpGet/Post/Put`-Ansatz. Verifiziert Headers (`X-API-Key`, `X-Internal-Sync`), Request-Bodies und Error-Handling fuer 4xx/5xx. Wuerde 4 der 7 Luecken in InstanceSyncHelper + TestingService auf einmal abdecken. Hoehere Investition, aber groesster Einzelhebel.

---

### Was nicht uebertrieben werden sollte

- **HealthAutoTodoService Scheduling:** Die `@Scheduled`-Annotation und 60s-Taktung muessen nicht integrativ getestet werden — Spring's Scheduler-Mechanik ist zuverlaessig, und die Logik ist im Service-Layer isoliert.
- **TestingService Docker-Lifecycle (`start`/`stop`/`resetDatabase`):** Diese Methoden delegieren an `DockerContainerManager.runCommand()`. Ein Test mit echtem Docker-Daemon waere ein E2E-Test mit hohem Infrastruktur-Aufwand. Die Delegations-Logik ist trivial; der Wert liegt eher in einem Smoke-Test der gesamten Pipeline (manuell oder CI mit Docker).
- **`applyWorkflowChanges` Scope-Limitierung:** Dass nur `instructions` und `model` gepullt werden (nicht `definition`/Blocks) ist eine bewusste Designentscheidung, kein Bug. Ein dokumentierender Test waere nett, aber kein Risiko-Mitigator.
- **Frontend/E2E:** Kein Frontend-Code ist betroffen. E2E-Tests sind fuer keine der gefundenen Luecken die richtige Testform — alle lassen sich mit Unit-, Slice- oder Integrationstests billiger und praeziser abdecken.
