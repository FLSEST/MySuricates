# Test-Coverage-Report MySuricates v0.6.2

**Datum:** 2026-03-29 | **Version:** v0.6.2 | **Analysiert von:** Claude Opus 4.6

---

## Executive Summary

| Metrik | Wert |
|---|---|
| Backend Source Files | 345 |
| Backend Test Files | 99 |
| Backend Coverage Ratio (Datei-basiert) | 28.7% |
| Frontend Components/Pages/Hooks | 133 |
| Frontend Unit Tests | 16 |
| Frontend Unit Coverage | 12.0% |
| Frontend E2E Tests (Cypress) | 15 Specs |
| Kritisch untestete Klassen | 8 (P0) |
| **Test-Qualitaet (1-10)** | **5/10** — Vorhandene Tests solide, aber zu viele kritische Pfade ungeschuetzt |

**Gesamt-Bewertung:** Die Test-Infrastruktur ist professionell aufgesetzt (JUnit 5, Mockito, MockMvc, Vitest, MSW, Cypress). Staerken: Security (100%), Block-Executors (84%), Controller (69%). Kritische Luecken: Provider-Routing (AiProviderRegistry), Ghost-Run-Safety (GhostRunWatchdogService), Exception-Handling (GlobalExceptionHandler), Real-Time-Kanal (useWebSocket 507 LOC), und 8 Controller ohne Tests.

---

## Coverage-Matrix

### Backend

| Package | Source Files | Test Files | Coverage | Kritischste Luecke |
|---------|-------------|-----------|---------|-------------------|
| controller | 26 | 18 | **69%** | WorkflowFactoryController, DialogController, NotificationController |
| service | 92 | 52 | **57%** | AiProviderRegistry, GhostRunWatchdogService, AutoRepairService, InstanceSyncHelper |
| executor/blocks | 19 | 16 | **84%** | HumanInputBlockExecutor (147 LOC) |
| security | 3 | 3 | **100%** | — (vollstaendig) |
| config | 28 | 4 | **14%** | ExecutionDefaults, SecurityConfig, StaticFrontendConfig |
| entity | 32 | 1 | **3%** | Workflow, ScheduledWorkflow, Wish |
| dto | 72 | 0 | **0%** | Gesamtes Package (reine Datenklassen — niedriger ROI) |
| mapper | 14 | 0 | **0%** | Gesamtes Package (MapStruct-generiert — niedriger ROI) |
| repository | 22 | 0 | **0%** | Gesamtes Package (via Integration Tests teilweise abgedeckt) |
| websocket | 5 | 0 | **0%** | TerminalWebSocketHandler (128 LOC), LogWebSocketHandler (95 LOC) |
| exception | 12 | 0 | **0%** | GlobalExceptionHandler (89 LOC) |
| event | 2 | 0 | **0%** | WorkflowRunCompletedEvent |
| util | 10 | 1 | **10%** | HostProcessUtils (99 LOC) |
| skills/handlers | 6 | 0 | **0%** | Alle 6 Handler |
| Integration | — | 3 | — | Keine E2E-Execution-Pipeline-Tests |
| **GESAMT** | **345** | **99** | **28.7%** | |

### Frontend

| Bereich | Source Files | Test Files | Coverage | Kritischste Luecke |
|---------|-------------|-----------|---------|-------------------|
| Hooks | 29 | 4 | **13.8%** | useWebSocket (507 LOC), useChains, useAssistant |
| UI Components | 16 | 6 | **37.5%** | CronPicker, DirectoryPicker, TagInput |
| Pages | 24 | 0 (Unit) | **0%** | Alle Pages — nur via E2E teilweise abgedeckt |
| Feature Components | 64 | 0 | **0%** | AssistantPanel (696 LOC), WorkflowDetailPanel |
| Stores | 4 | 4 | **100%** | — (vollstaendig) |
| Utils | 4 | 2 | **50%** | templateUtils, workflowIcon |
| E2E (Cypress) | 24 Pages | 15 Specs | **62.5%** | Chains, ChainExecution, DialogImport, WorkflowFactory, Docs |
| MSW Handlers | — | 8 Endpoints | — | Nur Projects, Workflows, Skills, Todos gemockt |

---

## Top-10 Prioritaeten

| # | Klasse/Modul | LOC | Schweregrad | Was testen | Aufwand |
|---|---|---|---|---|---|
| 1 | **AiProviderRegistry** | 92 | CRITICAL | resolve() mit bekanntem/unbekanntem Provider, getDefault() Cascading (workflow→DB→config→CLAUDE), Cache-Refresh | 1-2h |
| 2 | **GlobalExceptionHandler** | 89 | CRITICAL | Jede Exception → korrekter HTTP-Status, ErrorResponse-Format, Validation-Fehler-Mapping | 1-2h |
| 3 | **GhostRunWatchdogService** | 95 | CRITICAL | Stale-Run-Erkennung (>10min), Semaphore-Permit-Reconciliation, RUNNING→FAILED Transition, kein False-Positive | 2h |
| 4 | **WorkflowFactoryController** | 69 | CRITICAL | generate/run/verify/promote Endpoints, Fehlerbehandlung, API-Contract | 1h |
| 5 | **InstanceSyncHelper** | 491 | CRITICAL | Multi-System-Sync (Dev→Stable/Test), Projekt-ID-Mapping, selektive Filterung, Fehler = Datenverlust | 3-4h |
| 6 | **AutoRepairService** | 199 | HIGH | Event-Listener, Loop-Protection (max 3 Repairs), async Repair-Trigger, Endlos-Schleifen-Gefahr | 2h |
| 7 | **DialogConversionService** | 329 | HIGH | JSONL-Parsing, AI+Rule-based Extraction, Block-Dedup, JSON-Repair, Token-Limit | 3-4h |
| 8 | **useWebSocket** (Frontend) | 507 | HIGH | STOMP-Subscriptions, 9+ Message-Type-Guards, Log-Rotation (500 cap), Reconnection (5s), Query-Invalidation | 4-5h |
| 9 | **CompletionTriggerController + Service** | 65+160 | HIGH | 8 Endpoints, DFS-Zyklen-Erkennung, Trigger-Conditions — Fehler = Endlos-Kaskaden | 2-3h |
| 10 | **HumanInputBlockExecutor** | 147 | HIGH | validation_pattern wird gespeichert aber NICHT validiert, required-Flag ignoriert, WebSocket/CompletableFuture | 2h |

**Gesamtaufwand Top-10: ~22-30h**

---

## Quick Wins (kleiner Aufwand, hoher Wert)

| # | Klasse | LOC | Aufwand | Warum Quick Win |
|---|---|---|---|---|
| 1 | GlobalExceptionHandlerTest | 89 | ~1h | 9 Exception-Typen → 9 Tests, reines Input/Output |
| 2 | AiProviderRegistryTest | 92 | ~1h | Provider-Resolution + Fallback-Chain, wenige Dependencies |
| 3 | GhostRunWatchdogServiceTest | 95 | ~1h | NPE-Risiko bei startedAt=null, einfach zu mocken |
| 4 | PageSuggestionControllerTest | 25 | ~30min | 2 Endpoints, trivial |
| 5 | WorkflowPoolControllerTest | 43 | ~30min | 3 simple Endpoints, MockMvc-Boilerplate existiert |
| 6 | ChainTemplateControllerTest | 56 | ~45min | Standard-CRUD, 3 Endpoints |
| 7 | WorkflowSimilarityServiceTest | 96 | ~1h | Reine Algorithmus-Logik, keine External-Dependencies |
| 8 | ExecutionInsightsServiceTest | 131 | ~1h | Aggregations-Logik, gut isolierbar |
| 9 | AssistantSessionServiceTest | 87 | ~45min | In-Memory Session-Verwaltung |
| 10 | HostProcessUtilsTest | 99 | ~1h | Utility-Methoden, keine Dependencies |

**Gesamt Quick Wins: ~8-9h** → 10 neue Test-Dateien, hebt Backend-Coverage um ~3-4%

---

## Staerken

| Bereich | Details |
|---|---|
| **Security** | 3/3 getestet (100%) — ApiKeyAuthFilter, RateLimitFilter, WebSocketAuthInterceptor |
| **Block-Executors** | 16/19 getestet (84%) — inkl. komplexe Executors: Agentic, Switch, ForEach, While, SubWorkflow |
| **Controller** | 18/26 getestet (69%) — solide API-Contract-Absicherung fuer Kern-Endpoints |
| **Frontend Stores** | 4/4 getestet (100%) — settings, sidebar, theme, toast |
| **Cypress E2E** | 15 Specs decken 62.5% der Pages ab |
| **Test-Infrastruktur** | JUnit 5, MockMvc, Mockito, Vitest, MSW, Cypress — professionell aufgesetzt |

---

## Empfohlene Test-Strategie

### Phase 1: Safety Guards (Woche 1) — P0 CRITICAL

**Ziel:** Kritische Infrastruktur absichern, die bei Regression die gesamte Plattform betrifft.

| Modul | Test-Typ | Fokus | Aufwand |
|---|---|---|---|
| AiProviderRegistry | Unit | Provider-Routing, Fallback-Kaskade, Cache | 1-2h |
| GlobalExceptionHandler | Unit (MockMvc) | HTTP-Status-Codes, Error-Format | 1-2h |
| GhostRunWatchdogService | Unit | Time-based Erkennung, Semaphore-Safety | 2h |
| WorkflowFactoryController | Unit (MockMvc) | API-Endpoints, Error-Responses | 1h |

**Aufwand Phase 1: ~5-7h**

### Phase 2: Business-Logik (Woche 2-3) — P1 HIGH

**Ziel:** Kerngeschaeftslogik mit den meisten Codepfaden absichern.

| Modul | Test-Typ | Fokus | Aufwand |
|---|---|---|---|
| InstanceSyncHelper | Unit | Projekt-ID-Mapping, selektive Sync | 3-4h |
| AutoRepairService | Unit | Loop-Protection, Event-Handling | 2h |
| DialogConversionService | Unit | Parsing, AI/Rule-Extraction, Dedup | 3-4h |
| CompletionTrigger (Controller+Service) | Unit (MockMvc) | API + Zyklen-Erkennung | 2-3h |
| HumanInputBlockExecutor | Unit | Validation, Required-Flag, Timeout | 2h |
| NotificationController + Service | Unit | CRUD, Delivery-Logik | 2-3h |
| useWebSocket (Frontend) | Unit (Vitest) | STOMP-Mock, Message-Types, Reconnection | 4-5h |
| Quick Wins (10 Stueck) | Unit | Siehe Quick-Win-Tabelle | 8-9h |

**Aufwand Phase 2: ~27-34h**

### Phase 3: Breite erhoehen (Woche 4+) — P2

**Ziel:** Coverage-Ratio signifikant anheben.

| Modul | Test-Typ | Fokus | Aufwand |
|---|---|---|---|
| Duenne Tests auffuellen | Unit | Edge Cases: ChainExecutor (+15), ExecutionController (+15), SimpleBlockExecutor | 3-4h |
| 5 fehlende E2E-Specs | Cypress | Chains, ChainExecution, DialogImport, WorkflowFactory, Docs | 4-5h |
| MSW-Handler erweitern | Test-Infra | Chains, Schedules, Webhooks, Executions, System | 2-3h |
| 10 weitere Services | Unit | WorkflowStatsService, WishWatchdogService, AgenticLearningService, etc. | 6-8h |
| 3 fehlende Controller | Unit (MockMvc) | DialogController, WishController, TestingController | 3h |

**Aufwand Phase 3: ~18-23h**

### Phase 4: Integration (langfristig) — P3

| Test-Typ | Was | Aufwand |
|---|---|---|
| Integration | WorkflowExecutor → BlockExecutor → HostClaudeService → StepResult → GateStatus → AutoTodo | 8-10h |
| Integration | Chain-Execution End-to-End | 4-5h |
| Integration | WebSocket Real-Time-Update Pipeline | 3-4h |

### Nicht priorisieren (niedriger ROI)

| Package | Warum |
|---|---|
| dto (72 Files) | Reine Datenklassen ohne Logik — nur testen wenn Custom-Validation |
| entity (32 Files) | Hibernate-managed — nur @PrePersist/@PreUpdate-Logik relevant |
| mapper (14 Files) | MapStruct-generiert — Compiler-verifiziert |
| repository (22 Files) | Spring Data JPA — nur Custom Queries testen (3 Integration-Tests vorhanden) |
| config (24 Files) | Konfiguration — nur testen wenn komplexe Conditional-Logik |

---

## Detaillierte Luecken

### Backend — Controller ohne Tests (8)

| Controller | LOC | Endpoints | Schweregrad |
|---|---|---|---|
| WorkflowFactoryController | 69 | 4 (generate, run, verify, promote) | CRITICAL |
| NotificationController | 55 | 6 (CRUD + enable/disable) | HIGH |
| DialogController | 104 | ~4 | HIGH |
| WorkflowPoolController | 43 | 3 (list, get, install) | HIGH |
| TestingController | ~60 | ~5 | MEDIUM |
| ChainTemplateController | 56 | 3 (list, get, install) | MEDIUM |
| DocsController | 120 | ~3 | LOW |
| PageSuggestionController | 25 | 2 | LOW |

### Backend — Top 15 untestete Services

| Service | LOC | Schweregrad |
|---|---|---|
| InstanceSyncHelper | 491 | CRITICAL |
| DialogSessionScanner | 348 | HIGH |
| DialogConversionService | 329 | HIGH |
| GitWebhookPayloadParser | 239 | HIGH |
| WorkflowStatsService | 238 | MEDIUM |
| PageSuggestionService | 222 | MEDIUM |
| AutoRepairService | 199 | HIGH |
| NotificationService | 196 | MEDIUM |
| AgenticLearningService | 183 | MEDIUM |
| ChainTemplateService | 165 | MEDIUM |
| ProjectContextService | 164 | MEDIUM |
| WishWatchdogService | 161 | MEDIUM |
| CompletionTriggerService | 160 | HIGH |
| ContainerShellService | 138 | LOW |
| ExecutionInsightsService | 131 | LOW |

### Backend — Duenne Tests (existierend aber unzureichend)

| Test-Datei | Tests | Quell-Komplexitaet | Geschaetzte Abdeckung |
|---|---|---|---|
| ChainExecutorTest | 5 | HOCH (~300 LOC) | ~15% |
| ExecutionControllerTest | 5 | HOCH (13 Endpoints) | ~38% |
| HostCodexServiceTest | 2 | MITTEL | ~33% |
| SimpleBlockExecutorTest | 4 | MITTEL | ~50% |
| ActivityLogControllerTest | 2 | NIEDRIG | ~50% |
| ClaudeSummarizerTest | 4 | MITTEL | ~40% |

### Frontend — Hooks ohne Tests (25 von 29)

**Kritisch/Hoch:**
- useWebSocket (507 LOC) — CRITICAL
- useChains (131 LOC) — HIGH
- useAssistant (57 LOC) — HIGH
- useSchedules (132 LOC) — HIGH
- useSystem (154 LOC) — HIGH

**Mittel:**
- useCompletionTriggers, useDialog, useStaging, useTesting, useWishes, useWorkflowMemory, useWorkflowFactory

**Niedrig (Standard TanStack-Query-Wrapper):**
- useActivityLog, useChainTemplates, useConversion, useDocs, useInsights, useIsMobile, useLearning, useNotifications, usePersistedState, usePool, useTemplates, useToast, useWebhooks

### Frontend — Pages ohne E2E-Tests (9 von 24)

| Page | LOC | Schweregrad |
|---|---|---|
| Chains | 653 | HIGH |
| DialogImport | 543 | HIGH |
| WorkflowFactory | 378 | HIGH |
| ChainExecution | 162 | MEDIUM |
| Insights | 225 | MEDIUM |
| SimpleWorkflowExecution | 252 | LOW |
| SimpleWorkflows | 218 | LOW |
| SimpleWorkflowDetail | 167 | LOW |
| SimpleDashboard | 113 | LOW |

---

## Coverage-Ziele

| Metrik | Ist (v0.6.2) | Ziel Q2 | Ziel Q3 |
|---|---|---|---|
| Backend File Coverage | 28.7% | 45% | 60% |
| Controller Coverage | 69% | 85% | 95% |
| Service Coverage | 57% | 70% | 80% |
| Executor Coverage | 84% | 95% | 100% |
| Frontend Hook Coverage | 13.8% | 40% | 60% |
| Frontend E2E Page Coverage | 62.5% | 80% | 90% |

---

## Gate Status

```
gate_status: YELLOW
gate_category: test-coverage
gate_description: 28.7% Backend-Ratio, 8 Controller ohne Tests, AiProviderRegistry/GhostRunWatchdog/GlobalExceptionHandler ungetestet, useWebSocket (507 LOC) ohne Test. P0-Luecken in Provider-Routing und Safety-Guards. Staerken: Security 100%, Executors 84%.
```
