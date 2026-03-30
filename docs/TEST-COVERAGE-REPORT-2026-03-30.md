# MySuricates Test Coverage Audit — 2026-03-30

## Executive Summary

| Metrik | Wert |
|---|---|
| Backend Source Files | 348 |
| Backend Test Files | 117 |
| Backend @Test Methoden | 1.303 |
| **Backend Coverage Ratio** | **33,6% (Dateien)** |
| Frontend Source Files | ~165 |
| Frontend Unit-Test-Dateien | 17 |
| Frontend Unit-Test-Cases | 137 |
| Cypress E2E Specs | 15 (170 Cases) |
| **Frontend Coverage Ratio** | **~10% (Unit), ~58% Seiten via E2E** |
| **Gesamt-Bewertung** | **5/10** |
| **Vergleich mit letztem Run** | Kein vorheriger Report — Baseline |

**Gate Status: YELLOW** — Backend bei 33,6%, Frontend-Unit bei ~10%. Solide Basis bei Block-Executors und Core-Services, aber kritische Lücken bei Security-Oberflächen und AI-Provider-Layer.

---

## Coverage-Matrix

### Backend

| Package | Source Files | Test Files | Coverage | Kritischste Lücke |
|---------|-------------|-----------|---------|-------------------|
| controller/ | 26 | 25 | **96%** | Nur `TestingController` fehlt (thin) |
| service/ (gesamt) | ~86 | ~58 | **67%** | `AutoRepairService`, `GhostRunWatchdogService`, `DialogSessionScanner` |
| service/workflow/ | 9 | 5 | 56% | `GhostRunWatchdogService`, `TemplateVerificationService` |
| service/learning/ | 7 | 3 | 43% | `AgenticLearningService`, `WorkflowStatsService`, `ExecutionInsightsService` |
| service/ai/ | 8 | 3 | 38% | `CodexAiProvider`, `ClaudeAiProvider` |
| service/staging/ | 4 | 3 | 75% | `DockerContainerManager` |
| service/cli/ | 8 | 6 | 75% | `ContainerShellService` |
| service/dialog/ | 3 | 2 | 67% | `DialogSessionScanner` (348 LOC) |
| service/repair/ | 1 | 0 | **0%** | `AutoRepairService` (199 LOC) |
| service/health/ | 1 | 0 | **0%** | `HealthAutoTodoService` (70 LOC) |
| executor/blocks/ | 16 | 13 | **81%** | Nur Interfaces/Records fehlen |
| security/ | 4 | 3 | 75% | `WebhookRateLimiter` |
| config/ | 28 | 5 | 18% | Akzeptabel — fast alle sind pure Config-Beans |
| websocket/ | 5 | 0 | **0%** | `TerminalWebSocketHandler`, `LogWebSocketHandler` |
| entity/ | 32 | 1 | 3% | Akzeptabel — JPA-Datenklassen, via Integration-Tests abgedeckt |
| dto/ | ~45 | 0 | 0% | Akzeptabel — reine Records |
| mapper/ | 14 | 0 | 0% | Akzeptabel — MapStruct-generiert |
| repository/ | 22 | 0 | 0% | Via `RepositoryIntegrationTest` abgedeckt |
| skills/handlers/ | 6 | 0 | **0%** | 5 Capability-Handler komplett ungetestet |
| exception/ | 12 | 1 | 8% | Akzeptabel — nur `GlobalExceptionHandler` hat Logik |
| util/ | 10 | 1 | 10% | `HostProcessUtils` fehlt |

### Frontend

| Kategorie | Source Files | Test Files | Coverage | Kritischste Lücke |
|-----------|-------------|-----------|---------|-------------------|
| pages/ | 24 | 0 | **0% Unit** | Alle Seiten ohne Unit-Tests; 14/24 via Cypress |
| components/ui/ | 12 | 6 | 50% | `CronPicker`, `DirectoryPicker`, `TagInput` |
| components/ (rest) | 69 | 0 | **0%** | `AssistantPanel` (793 LOC), `WorkflowDetailModals` (510 LOC) |
| hooks/ | 30 | 5 | **17%** | `useSchedules`, `useChains`, `useWishes`, `useSystem` |
| stores/ | 5 | 4 | **80%** | Nur `assistantStore` fehlt (110 LOC) |
| utils/ | 5 | 2 | 40% | `errorReporter`, `workflowIcon` |
| api/ | 1 | 0 | **0%** | `client.ts` — kritischer Pfad für alle Requests |
| Cypress E2E | — | 15 | 58% Seiten | Chains, Insights, WorkflowFactory, Simple Mode |

---

## Top-10 Prioritäten

| # | Klasse/Bereich | LOC | Schweregrad | Was testen | Aufwand |
|---|---|---|---|---|---|
| 1 | `TerminalWebSocketHandler` + `TerminalSession` | 208 | **CRITICAL** | WebSocket-Session-Lifecycle, Input-Sanitizing, Docker-exec-Sicherheit | mittel |
| 2 | `ContainerShellService` | 158 | **CRITICAL** | Shell-Zugang zu Docker-Containern, Security-Policy-Enforcement | mittel |
| 3 | `DialogSessionScanner` | 348 | **HIGH** | JSONL-Parsing, Filesystem-Traversal, Path-Encoding-Edge-Cases | groß |
| 4 | `AutoRepairService` | 199 | **HIGH** | Event-Listener, Loop-Protection, Batch-Repair, `isAutoRepairEnabled()` Prioritäts-Kette | mittel |
| 5 | `GhostRunWatchdogService` | 95 | **HIGH** | Threshold-Logik, Semaphore-Reconciliation (`reconcilePermits()`) | klein |
| 6 | `WorkflowStatsService` | 238 | **HIGH** | Statistische Aggregation, Optimization-Suggestions, Threshold-Vergleiche | mittel |
| 7 | `AgenticLearningService` | 183 | **HIGH** | Tool-Pattern-Extraction, N-Gram-Analyse, `findCommonPatterns()` | mittel |
| 8 | `CodexAiProvider` | 109 | **HIGH** | System-Prompt-Merging, Execution-Routing-Logik | klein |
| 9 | `api/client.ts` (Frontend) | 64 | **HIGH** | Auth-Header-Injection, Error-Handling, AbortSignal-Forwarding | klein |
| 10 | `assistantStore.ts` (Frontend) | 110 | **HIGH** | MAX_SESSIONS-Eviction, `appendMessage`, `updateMessageStatus` | klein |

---

## Quick Wins (hoher Wert, geringer Aufwand)

| Klasse | LOC | Aufwand | Warum Quick Win |
|---|---|---|---|
| `GhostRunWatchdogService` | 95 | ~30 Min | Kleine Klasse, klare Logik, scheduled Task |
| `HealthAutoTodoService` | 70 | ~20 Min | Einfache State-Transitions (healthy↔unhealthy) |
| `RunCleanupService` | 66 | ~20 Min | Repository-Aufrufe + Logging, kaum Branches |
| `WebhookRateLimiter` | 52 | ~30 Min | Sliding-Window-Logik, gut isolierbar |
| `PathValidator` | 37 | ~15 Min | Security-kritisch, pure Function, trivial zu testen |
| `ExecutionDefaults` | 20 | ~10 Min | `@ConfigurationProperties` mit Berechnungslogik |
| `assistantStore.ts` | 110 | ~30 Min | Zustand-Store, Pattern von 4 existierenden Tests kopierbar |
| `api/client.ts` | 64 | ~30 Min | Fetch-Wrapper, gut mockbar |
| `errorReporter.ts` | 64 | ~20 Min | Buffer-Management + Flush, isolierte Logik |
| `HostProcessUtils` | 60 | ~20 Min | Env-Manipulation, pure Functions |

**Geschätzter Gesamt-Aufwand Quick Wins: ~4 Stunden für 10 Klassen**

---

## Kritische Lücken im Detail

### 1. Security-Oberflächen ohne Tests (CRITICAL)

#### `TerminalWebSocketHandler` (128 LOC) + `TerminalSession` (~80 LOC)
- **Risiko**: Direkter Container-Terminal-Zugang via WebSocket. Kein Test für Input-Sanitizing, Session-Hijacking via URI, Connection-Lifecycle.
- **Was testen**: `afterConnectionEstablished()`, `handleTextMessage()`, `afterConnectionClosed()`, Session-Isolation, ungültige Container-IDs.

#### `ContainerShellService` (158 LOC)
- **Risiko**: Shell-Zugang zu Docker-Containern. Prozess-Execution und Cleanup ungetestet.
- **Was testen**: Security-Policy-Enforcement (welche Befehle erlaubt/verboten), Timeout-Verhalten, Stream-Cleanup.

#### `DialogSessionScanner` (348 LOC)
- **Risiko**: Liest Dateien aus `~/.claude/projects/`. Path-Encoding/Decoding kann anfällig für Traversal-Angriffe sein.
- **Was testen**: Malformed JSONL, Path-Traversal-Attempts (`../`), leere Verzeichnisse, große Dateien.

### 2. Automatische Zustandsänderungen ohne Tests (HIGH)

#### `AutoRepairService` (199 LOC)
- **Risiko**: Event-Listener löst automatische Workflow-Re-Executions aus. Loop-Protection ist die einzige Sicherung — ungetestet.
- **Was testen**: `@EventListener` für `WorkflowRunCompletedEvent`, Loop-Detection, `isAutoRepairEnabled()` Prioritäts-Kette (workflow metadata > global default).

#### `GhostRunWatchdogService` (95 LOC)
- **Risiko**: Scheduled Task (alle 120s) markiert Runs als FAILED. Kann legitime langsame Runs terminieren.
- **Was testen**: Threshold-Berechnung, `reconcilePermits()`, Edge-Case bei genau-auf-der-Grenze.

### 3. Analytics ohne Tests (HIGH)

#### `WorkflowStatsService` (238 LOC), `AgenticLearningService` (183 LOC), `ExecutionInsightsService` (131 LOC)
- **Risiko**: 552 LOC Algorithmic Code (Statistik, N-Gram-Analyse, Token-Aggregation) komplett ungetestet. Bugs produzieren falsche Dashboard-Zahlen und Optimization-Suggestions.
- **Was testen**: Aggregation mit 0 Runs, mit 1 Run, mit vielen Runs. Edge-Cases bei Duration-Variance-Berechnung. Tool-Pattern-Extraction mit ungewöhnlichen Inputs.

### 4. AI-Provider-Layer (HIGH)

#### `CodexAiProvider` (109 LOC), `ClaudeAiProvider` (67 LOC)
- **Risiko**: System-Prompt-Merging und Execution-Routing sind der Kern des Multi-Provider-Systems.
- **Was testen**: Prompt-Concatenation, Provider-Fallback, Timeout-Handling.

---

## Dünne Tests (existieren, aber unzureichend)

| Test | Methoden | Problem | Empfehlung |
|---|---|---|---|
| `HostCodexServiceTest` | 5 | Nur `buildCommand()`. `execute()` (Prozess, Timeout, Streams, Temp-Files) ungetestet. | execute() mocken mit ProcessBuilder |
| `ActivityLogControllerTest` | 2 | Nur Happy-Path + Filter. Pagination, ungültige Enums fehlen. | +4 Edge-Case-Tests |
| `ConversionControllerTest` | 4 | Minimal für 2 Endpoints. Fehler-Szenarien fehlen. | +3 Error-Tests |
| `AssistantControllerTest` | 7 | Für 1.337 LOC Service-Code zu wenig. Streaming, Abort ungetestet. | +10 Tests für Streaming-Edge-Cases |
| `ClaudeSummarizerTest` | 4 | Nur Basis. Timeout, CLI-Fehler, große Inputs fehlen. | +3 Failure-Tests |

---

## Fehlende Test-Typen

### WebSocket-Integrationstests
- **Status**: Kein einziger Test prüft `/ws` oder `/ws/terminal` end-to-end
- **Impact**: Real-Time-Features (Log-Streaming, Terminal, Execution-Updates) sind nur manuell verifiziert
- **Empfehlung**: Spring WebSocket Test Support (`WebSocketStompClient`) für `/ws`, raw WebSocket-Client für `/ws/terminal`

### Workflow-Execution-Integrationstest
- **Status**: Kein Test führt einen kompletten Workflow durch (Block-Kette → StepResults → RunStatus)
- **Impact**: Block-Interaktion (Context-Passing, Error-Propagation) nur via Unit-Tests der einzelnen Executors
- **Empfehlung**: Ein `@SpringBootTest` der einen 3-Block-Workflow mit gemocktem Claude CLI durchläuft

### Chain-Execution-Integrationstest
- **Status**: Kein Test prüft sequentielle Chain-Ausführung end-to-end
- **Empfehlung**: Test mit 2 Workflows in einer Chain, `stopOnFailure` Varianten

### API-Contract-Tests
- **Status**: Kein Contract-Testing (Spring Cloud Contract, Pact)
- **Impact**: Frontend-Backend-Drift nur durch Cypress E2E aufgefangen
- **Empfehlung**: Langfristig — OpenAPI-Spec generieren und gegen Frontend-Types prüfen

### Frontend MSW-Coverage
- **Status**: Nur 9 von ~80+ API-Endpoints gemockt
- **Impact**: Hook-Tests fallen auf `onUnhandledRequest: 'bypass'` zurück — keine echte Isolation
- **Empfehlung**: MSW-Handlers für mindestens schedules, chains, executions, assistant erweitern

---

## Positives — Bereits gut abgedeckt

| Bereich | Tests | Bewertung |
|---|---|---|
| **Block-Executors** | 13/16, 139 @Test | Exzellent — alle 12 Block-Typen mit Logic getestet |
| **WorkflowExecutor** | 38 Tests | Sehr gut — zentraler Orchestrator gründlich geprüft |
| **Todo-System** | `AutoTodoService`, `TodoOutputParser`, `GateStatusOutputParser`, `GateStatusResolver`, `TodoSimilarityService` | Sehr gut — Gate-Status-Pipeline vollständig |
| **CLI-Layer** | `ClaudeOutputParser`, `CommandExecutorService`, `HostClaudeService` | Gut — 47 Tests |
| **Security-Filter** | `ApiKeyAuthFilter`, `RateLimitFilter`, `WebSocketAuthInterceptor` | Gut — 15 Tests |
| **Controller-Layer** | 25/26 Controller | Exzellent — 270 @Test Methoden |
| **Staging/Testing** | `StagingService` (16), `TestingService` (52), `InstanceSyncHelper` (11) | Sehr gut — 79 Tests |
| **Frontend Stores** | 4/5 getestet, 34 Cases | Gut — nur `assistantStore` fehlt |
| **useWebSocket** | 17 Cases | Exzellent — gründlichster Frontend-Unit-Test |
| **Cypress E2E** | 15 Specs, 170 Cases | Gut — 14/24 Seiten abgedeckt |

---

## Frontend-Spezifisch: Seiten ohne jegliche Test-Abdeckung

| Seite | LOC | Weder Unit noch E2E |
|---|---|---|
| `Chains.tsx` | 604 | Kein Test auf irgendeiner Ebene |
| `ChainExecution.tsx` | 148 | Kein Test auf irgendeiner Ebene |
| `Insights.tsx` | 213 | Kein Test auf irgendeiner Ebene |
| `WorkflowFactory.tsx` | 357 | Kein Test auf irgendeiner Ebene |
| `DialogImport.tsx` | 567 | Kein Test auf irgendeiner Ebene |
| `SimpleDashboard.tsx` | 105 | Kein Test auf irgendeiner Ebene |
| `SimpleWorkflows.tsx` | 202 | Kein Test auf irgendeiner Ebene |
| `SimpleWorkflowDetail.tsx` | 153 | Kein Test auf irgendeiner Ebene |
| `SimpleWorkflowExecution.tsx` | 237 | Kein Test auf irgendeiner Ebene |
| `Docs.tsx` | 116 | Kein Test auf irgendeiner Ebene |

---

## Empfohlene Test-Strategie

### Phase 1: Security & Safety (1-2 Wochen)
- WebSocket-Handler testen (Terminal + Log)
- `ContainerShellService`, `PathValidator`
- `AutoRepairService` Loop-Protection
- `GhostRunWatchdogService` Threshold-Logik

### Phase 2: AI-Provider & Analytics (1-2 Wochen)
- `CodexAiProvider`, `ClaudeAiProvider` — Contract-Tests
- `WorkflowStatsService`, `AgenticLearningService`, `ExecutionInsightsService`
- `HostCodexServiceTest` erweitern um `execute()`-Pfad
- Skills-Handler (5 Capability-Handler)

### Phase 3: Frontend-Fundament (2-3 Wochen)
- `api/client.ts` — Basis für alle API-Calls
- `assistantStore.ts` — letzter ungetesteter Store
- MSW-Handler erweitern (chains, schedules, executions, assistant)
- Cypress für Chains, Insights, WorkflowFactory

### Phase 4: Integration & Depth (laufend)
- WebSocket-Integrationstests
- Workflow-Execution-Integrationstest (3-Block-Chain mit Mock-CLI)
- Dünne Tests erweitern (HostCodexService, AssistantController)
- Frontend-Seiten-Unit-Tests für Top-5 (Workflows, Todos, Wishes, Chains, WorkflowExecution)

---

## Metriken für nächsten Report

| Metrik | Baseline (heute) | Ziel Phase 1 | Ziel Phase 2 |
|---|---|---|---|
| Backend Test Files | 117 / 348 (33,6%) | 125 / 348 (35,9%) | 135 / 348 (38,8%) |
| Backend @Test Methoden | 1.303 | 1.400 | 1.500 |
| Frontend Unit Tests | 17 / 165 (10,3%) | 20 / 165 (12,1%) | 25 / 165 (15,2%) |
| Cypress E2E Specs | 15 (14/24 Seiten) | 17 (16/24 Seiten) | 19 (18/24 Seiten) |
| CRITICAL untested | 3 Klassen | 0 | 0 |
| MSW Endpoints | 9 / 80+ | 20 / 80+ | 35 / 80+ |

---

gate_status: YELLOW
gate_category: test-coverage
gate_description: Backend 33,6% File-Coverage, Frontend 10% Unit-Coverage. Solide Block-Executor- und Controller-Tests, aber kritische Luecken bei WebSocket-Handlern, AI-Providern und Analytics-Services.

todo_item: TerminalWebSocketHandler und TerminalSession testen — Container-Shell-Security | CRITICAL | backend,testing,security
todo_item: ContainerShellService testen — Docker-Shell-Zugang | CRITICAL | backend,testing,security
todo_item: DialogSessionScanner testen — Filesystem-Traversal-Sicherheit | HIGH | backend,testing,security
todo_item: AutoRepairService testen — Loop-Protection verifizieren | HIGH | backend,testing
todo_item: GhostRunWatchdogService testen — Scheduled Zombie-Run-Detection | HIGH | backend,testing
todo_item: WorkflowStatsService und AgenticLearningService testen — Analytics-Layer | HIGH | backend,testing
todo_item: CodexAiProvider und ClaudeAiProvider testen — AI-Provider-Kern | HIGH | backend,testing
todo_item: HostCodexServiceTest erweitern — execute()-Pfad abdecken | HIGH | backend,testing
todo_item: api/client.ts testen — kritischer Frontend-Request-Pfad | HIGH | frontend,testing
todo_item: assistantStore.ts testen — letzter ungetesteter Zustand-Store | HIGH | frontend,testing
todo_item: MSW-Handler erweitern — nur 9 von 80+ Endpoints gemockt | MEDIUM | frontend,testing
todo_item: Cypress E2E fuer Chains und ChainExecution schreiben | MEDIUM | frontend,testing
todo_item: WebSocket-Integrationstests einfuehren | MEDIUM | backend,testing,integration
todo_item: Quick Wins testen — PathValidator, HealthAutoTodoService, RunCleanupService, WebhookRateLimiter | MEDIUM | backend,testing
