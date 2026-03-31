# MySuricates Test-Coverage Audit

**Datum:** 2026-03-31
**Version:** v0.6.4
**Erstellt von:** MySuricates AI Coverage Analyzer (claude-opus-4-6)

---

## Executive Summary

| Metrik | Wert |
|---|---|
| **Backend Source Files** | 352 |
| **Backend Test Files** | 120 |
| **Backend @Test-Methoden** | 1.367 |
| **Backend Coverage Ratio (Datei-Ebene)** | 34.1% |
| **Backend Untested Services** | 35 Klassen (~3.600 LOC) |
| **Frontend Source Files** | 156 |
| **Frontend Unit-Tests** | 17 |
| **Frontend E2E-Tests** | 15 Specs |
| **Frontend Coverage Ratio** | 10.9% (Unit) |
| **Integration Tests** | 5 Klassen, 30 Tests |
| **Gesamt-Bewertung** | **4/10** |

**Gate Status: YELLOW** (Coverage 34.1% — zwischen 30% und 60%)

---

## Coverage-Matrix

| Package | Source Files | Test Files | Coverage | Kritischste Luecke |
|---------|-------------|-----------|---------|-------------------|
| controller | 26 | 25 | **96.2%** | TestingController ungetestet |
| service (gesamt) | 98 | 63 | **64.3%** | Assistant-Subsystem (1.322 LOC) komplett blank |
| executor/blocks | 19 | 16 | **84.2%** | DockerContainerManager (103 LOC) |
| security | 4 | 3 | **75.0%** | WebhookRateLimiter ungetestet |
| config | 28 | 4 | **14.3%** | Seeders, ExecutionDefaults |
| mapper | 14 | 0 | **0.0%** | Alle 14 Mapper ungetestet |
| entity | 32 | 1 | **3.1%** | Nur BlockDefinition getestet |
| dto | 72 | 0 | **0.0%** | Kein DTO-Test (geringes Risiko) |
| repository | 22 | 0 | **0.0%** | Via Integration Tests abgedeckt |
| websocket | 5 | 0 | **0.0%** | Terminal-Zugriff security-relevant |
| exception | 12 | 1 | **8.3%** | GlobalExceptionHandler duenn getestet |
| integration | — | 5 | — | 30 Tests fuer 352 Source Files |
| **frontend (unit)** | 156 | 17 | **10.9%** | AssistantPanel (873 LOC) komplett blank |
| **frontend (e2e)** | 24 Pages | 15 Specs | **62.5%** | Chains, ChainExecution, Insights fehlen |

---

## 1. Kritische Luecken (Backend)

### 1.1 CRITICAL — Assistent-Subsystem komplett ungetestet (1.322 LOC)

| Klasse | LOC | Risiko |
|---|---|---|
| `AssistantContextBuilder` | 541 | Baut Kontext fuer Claude-Prompts — falsche Daten = halluzinierte Antworten |
| `AssistantFetchService` | 460 | Fetch-Logik fuer externe URLs — Security-relevant (URL-Validation, SSRF) |
| `AssistantToolExecutor` | 321 | Fuehrt Tools aus Assistenten-Antworten aus — hoechstes Injection-Risiko |

Der `AssistantService` selbst hat Tests, aber die drei Hilfsklassen, die die eigentliche Arbeit machen, haben **null Tests**. Das ist die groesste einzelne Luecke im Projekt.

**Aufwand:** gross (je 10-15 Tests pro Klasse, ~35-45 Tests gesamt)

### 1.2 CRITICAL — GhostRunWatchdogService (95 LOC)

Bereinigt haengengebliebene Workflow-Runs. Fehler hier = unsichtbare Ghost-Runs, die Ressourcen blockieren und die einzige DB-Connection halten koennen (pool-size=1).

**Aufwand:** klein (5-8 Tests)

### 1.3 CRITICAL — DockerContainerManager (103 LOC)

Verwaltet Docker-Container-Lifecycle. Fehler = Container-Leaks, Sicherheitsluecken.

**Aufwand:** mittel (8-10 Tests)

### 1.4 CRITICAL — TemplateVerificationService (127 LOC)

Verwaltet Verifikationsstatus von Templates. Fehler = falsche Qualitaetssignale bei Template-Auswahl.

**Aufwand:** mittel (8-10 Tests)

---

### 1.5 HIGH — Weitere Business-Logik ohne Tests

| Klasse | LOC | Bereich | Risiko |
|---|---|---|---|
| `WorkflowStatsService` | 238 | learning | Falsche Statistiken im Dashboard |
| `AgenticLearningService` | 183 | learning | Learning-Loop liefert falsches Feedback |
| `ContainerShellService` | 158 | cli | Shell-Execution in Containern fehlerhaft |
| `ExecutionInsightsService` | 131 | learning | Insights basieren auf falschen Daten |
| `CodexAiProvider` | 109 | ai | Codex-Integration bricht still |
| `AssistantSessionService` | 100 | service | Session-Management fehlerhaft |
| `ActivityLogLifecycleListener` | 84 | activitylog | Events gehen verloren |
| `HealthAutoTodoService` | 70 | health | Health-Monitoring erzeugt falsche TODOs |
| `ClaudeAiProvider` | 67 | ai | Claude-Integration bricht still |
| `RunCleanupService` | 66 | workflow | Alte Runs werden nicht bereinigt / versehentlich geloescht |

**Aufwand:** mittel bis gross (zusammen ~40-60 Tests)

---

### 1.6 HIGH — WebSocket-Handler ohne Tests (341 LOC)

| Klasse | LOC | Risiko |
|---|---|---|
| `TerminalWebSocketHandler` | 128 | Terminal-Zugriff via WebSocket — Security-relevant |
| `TerminalSession` | 118 | Session-Management fuer Terminal |
| `LogWebSocketHandler` | 95 | Log-Streaming an Frontend |

**Aufwand:** mittel (je 5-8 Tests, ~15-24 gesamt)

---

### 1.7 MEDIUM — Mappers ohne Tests (454 LOC)

| Mapper | LOC |
|---|---|
| `ChainMapper` | 105 |
| `ScheduleMapper` | 86 |
| `WishMapper` | 81 |
| `WorkflowMapper` | 80 |
| `TodoMapper` | 45 |
| `SkillMapper` | 31 |
| `ProjectMapper` | 26 |

MapStruct-generiert, aber komplexere Mappings (verschachtelte Entities, JSON-Felder) koennen subtile Bugs haben.

**Aufwand:** klein (je 3-5 Tests pro Mapper)

### 1.8 MEDIUM — Utilities und Support-Klassen

| Klasse | LOC | Bereich |
|---|---|---|
| `HostProcessUtils` | ~99 | util |
| `AgenticPlan` | ~81 | executor/blocks |
| `ExecutionDefaults` | ~96 | config |
| `MetadataExtractor` | ~60 | service |
| `PathValidator` | ~50 | service |

**Aufwand:** klein (je 3-5 Tests)

### 1.9 LOW — Entities, DTOs, Repositories

Entities (32 Klassen) sind ueberwiegend Lombok/JPA-generiert. DTOs (72 Klassen) sind reine Datentraeger. Repositories (22 Interfaces) sind Spring Data Interfaces. Tests hier haben geringen ROI, sofern die Integration Tests die Queries abdecken.

---

## 2. Duenne Tests (Backend)

Test-Dateien mit verdaechtig wenigen Tests fuer ihre Quellklassen-Komplexitaet:

| Test-Datei | Tests | Bewertung |
|---|---|---|
| `ActivityLogControllerTest` | **2** | CRITICAL — fast kein API-Contract getestet |
| `FlywayMigrationTest` | **3** | HIGH — nur Smoke-Test fuer 47 Migrationen |
| `ConversionControllerTest` | **4** | MEDIUM |
| `LazyLoadingIntegrationTest` | **4** | MEDIUM — bei pool-size=1 kritisch |
| `BlockDefinitionTest` | **4** | LOW |
| `WorkflowTemplateControllerTest` | **5** | MEDIUM |
| `PageSuggestionControllerTest` | **5** | MEDIUM |
| `ApiKeyAuthFilterTest` | **5** | HIGH — Auth-Filter braucht mehr Coverage (Bypass-Szenarien) |

---

## 3. Kritische Luecken (Frontend)

### 3.1 CRITICAL — Komplett ungetestet (kein Unit UND kein E2E)

| Komponente | LOC | Risiko |
|---|---|---|
| `AssistantPanel` | 873 | Zentrales Chat-UI, komplexe State-Logik |
| `Chains` (Page) | 652 | Kein Test fuer Chain-Management CRUD |
| `DialogImport` | 625 | Import-Logik mit Parsing — Fehler = Datenverlust |
| `WorkflowDetailModals` | 542 | Modale Dialoge fuer Workflow-Bearbeitung |
| `WorkflowFactory` | 378 | AI-generierte Workflows — UX-kritisch |
| `SyncSelectModal` | 338 | Staging-Sync — Fehler = falsche Definitionen synced |
| `WorkflowMemoryPanel` | 325 | Memory-Management UI |
| `SimpleWorkflowExecution` | 252 | Simple-Mode Execution |
| `Insights` | 225 | Dashboard-Insights |
| `CompletionTriggerSection` | 215 | Trigger-Konfiguration |
| `TerminalPanel` | 208 | Terminal-Zugriff im Browser |

**Gesamt: ~4.633 LOC komplett ohne Tests**

### 3.2 HIGH — Unit-Tests fehlen, aber E2E vorhanden

| Komponente | LOC | E2E vorhanden |
|---|---|---|
| `Wishes` | 880 | wishes.cy.ts |
| `Todos` | 803 | todos.cy.ts |
| `WorkflowExecution` | 584 | workflow-execution.cy.ts |
| `Runs` | 526 | runs.cy.ts |
| `Workflows` | 514 | workflows.cy.ts |
| `Schedules` | 490 | schedules.cy.ts |
| `ProjectDetail` | 387 | project-detail.cy.ts |
| `Projects` | 335 | projects.cy.ts |
| `Webhooks` | 213 | webhooks.cy.ts |

Diese haben E2E-Abdeckung, aber keine Unit-Tests fuer Edge Cases und Fehlerzustaende.

### 3.3 HIGH — Hooks ohne Tests

15 von 29 Hooks sind ungetestet:

| Hook | LOC | Getestet? |
|---|---|---|
| useWorkflows | 308 | ✅ |
| useSystem | 177 | ❌ |
| useWebSocket | 171 | ✅ |
| useSchedules | 132 | ❌ |
| useChains | 131 | ❌ |
| useProjects | 121 | ✅ |
| useWishes | 99 | ❌ |
| useTodos | 91 | ✅ |
| useWorkflowMemory | 73 | ❌ |
| useCompletionTriggers | 68 | ❌ |
| useStaging | 64 | ❌ |
| useAssistant | 62 | ❌ |
| useTesting | 60 | ❌ |
| useDialog | 60 | ❌ |
| useSkills | 58 | ✅ |
| useDocs | 58 | ❌ |
| useWebhooks | 56 | ❌ |
| useNotifications | 56 | ❌ |
| useWorkflowFactory | 49 | ❌ |
| useTemplates | 49 | ❌ |

Hooks sind TanStack-Query-Wrapper — ein Test-Template wuerde fuer alle funktionieren.

### 3.4 MEDIUM — Fehlende E2E-Tests

Seiten ohne Cypress-Abdeckung:
- **ChainExecution** (161 LOC) — Chain-Ausfuehrung live verfolgen
- **Chains** (652 LOC) — Chain-Verwaltung (CRUD)
- **Insights** (225 LOC) — Analytics/Dashboard-Insights
- **WorkflowDetail** (362 LOC) — Workflow-Detailansicht
- **SimpleWorkflowExecution** (252 LOC) — Simple-Mode Execution
- **Docs** (124 LOC) — Dokumentationsseite
- **SimpleDashboard** (113 LOC) — Simple-Mode Dashboard
- **SimpleWorkflows** (218 LOC) — Simple-Mode Workflows
- **SimpleWorkflowDetail** (167 LOC) — Simple-Mode Detail

---

## 4. Fehlende Test-Typen

| Test-Typ | Status | Bewertung |
|---|---|---|
| Unit Tests (Backend) | 120 Dateien, 1.367 Tests | Solide Basis, aber grosse Luecken im Assistant/Learning-Bereich |
| Integration Tests (Backend) | 5 Klassen, 30 Tests | **Duenn** — nur Flyway, Repository, Controller, LazyLoading, StableSync |
| Unit Tests (Frontend) | 17 Dateien | **Minimal** — nur UI-Primitives, 5 Hooks, 4 Stores, 2 Utils |
| E2E Tests (Frontend) | 15 Specs | Gute Seitenabdeckung, aber Chains/Assistant/Insights fehlen |
| Security Tests | 3 Klassen (~15 Tests) | **Duenn** — Auth-Bypass, SSRF, XSS-Szenarien fehlen |
| Performance/Load Tests | 0 | Nicht vorhanden (bei pool-size=1 relevant) |
| Contract Tests (API) | 0 | Kein OpenAPI/Consumer-Driven Contract Testing |

---

## 5. Top-10 Prioritaeten

| # | Klasse/Bereich | LOC | Schweregrad | Was testen | Aufwand |
|---|---|---|---|---|---|
| 1 | `AssistantContextBuilder` | 541 | CRITICAL | Kontext-Aufbau, Daten-Injection, Edge Cases | 10-15 Tests |
| 2 | `AssistantFetchService` | 460 | CRITICAL | URL-Validierung, SSRF-Schutz, Timeout-Handling | 10-15 Tests |
| 3 | `AssistantToolExecutor` | 321 | CRITICAL | Tool-Dispatch, Input-Validierung, Error-Handling | 10-15 Tests |
| 4 | `GhostRunWatchdogService` | 95 | CRITICAL | Erkennung + Bereinigung haengender Runs | 5-8 Tests |
| 5 | WebSocket-Handler (3 Klassen) | 341 | HIGH | Auth, Message-Handling, Session-Lifecycle | 15-24 Tests |
| 6 | `WorkflowStatsService` | 238 | HIGH | Statistik-Berechnung, Edge Cases bei leeren Daten | 8-12 Tests |
| 7 | `AssistantPanel` (Frontend) | 873 | CRITICAL | State-Logik, Message-Handling, Tool-Rendering | 10-15 Tests |
| 8 | `ActivityLogControllerTest` erweitern | — | HIGH | Alle CRUD-Endpoints + Filter + Pagination | +8-10 Tests |
| 9 | `ApiKeyAuthFilterTest` erweitern | — | HIGH | Bypass-Szenarien, Edge Cases, Rate-Limiting | +5-8 Tests |
| 10 | Mapper-Tests (7 Klassen) | 454 | MEDIUM | Verschachtelte Entities, JSON-Felder, Null-Handling | 20-30 Tests |

---

## 6. Quick Wins

Kleine Klassen, die mit wenig Aufwand getestet werden koennen und sofort Wert liefern:

| Klasse | LOC | Tests noetig | Warum Quick Win |
|---|---|---|---|
| `GhostRunWatchdogService` | 95 | 5-8 | Kritische Funktionalitaet, einfache Logik, gut mockbar |
| `RunCleanupService` | 66 | 4-6 | Einfache Cleanup-Logik, klar definierte Inputs/Outputs |
| `ClaudeAiProvider` | 67 | 4-6 | Provider-Interface, gut isolierbar |
| `HealthAutoTodoService` | 70 | 5-7 | Klare Wenn-Dann-Logik, gut testbar |
| `ActivityLogLifecycleListener` | 84 | 4-6 | Event-Listener, einfach zu triggern |
| `AssistantSessionService` | 100 | 5-8 | Session-CRUD, Standard-Pattern |
| `PathValidator` | ~50 | 3-5 | Pure Function, keine Dependencies |
| `ProjectMapper` | 26 | 2-3 | Kleinstes Mapper, Pilot fuer Mapper-Tests |
| `SkillMapper` | 31 | 2-3 | Klein, einfaches Mapping |
| `TodoMapper` | 45 | 3-4 | Mittelkomplex, gutes Template fuer andere |

**Gesamt Quick Wins: ~37-56 Tests, geschaetzt 2-3 Tage**

---

## 7. Empfohlene Test-Strategie

### Phase 1: Kritische Sicherheitsluecken schliessen (1-2 Wochen)

**Fokus:** Assistant-Subsystem + Security

1. `AssistantToolExecutor` — Input-Validierung, Injection-Schutz
2. `AssistantFetchService` — SSRF-Schutz, URL-Whitelist-Tests
3. `AssistantContextBuilder` — Datenintegritaet, Grenzwerte
4. `ApiKeyAuthFilterTest` erweitern — Bypass-Szenarien
5. WebSocket-Auth-Tests — Unautorisierter Terminal-Zugriff

### Phase 2: Stabilitaet sichern (1-2 Wochen)

**Fokus:** Quick Wins + duenne Tests auffuellen

1. Alle Quick Wins aus der Tabelle oben
2. `ActivityLogControllerTest` auf 10+ Tests bringen
3. `TemplateVerificationService` testen
4. `DockerContainerManager` testen

### Phase 3: Frontend-Grundlage schaffen (1-2 Wochen)

**Fokus:** Hook-Tests als Template + kritische Komponenten

1. Hook-Test-Template erstellen (MSW-basiert, TanStack Query)
2. Alle 15 ungetesteten Hooks mit Template abdecken
3. `AssistantPanel` — mindestens State-Logik Unit-testen
4. `DialogImport` — Parsing-Logik isoliert testen

### Phase 4: Learning/Insights-Bereich (1 Woche)

**Fokus:** Datenqualitaet im Dashboard

1. `WorkflowStatsService`
2. `AgenticLearningService`
3. `ExecutionInsightsService`

### Phase 5: Integration Tests ausbauen (laufend)

**Fokus:** OSIV-off + pool-size=1 Randfaelle

1. Concurrent-Workflow-Execution unter pool-size=1
2. Lazy-Loading-Szenarien fuer alle Entities mit Relationen
3. Chain-Execution End-to-End (DB-Level)

### Langfristig

- **Contract Tests** einfuehren (OpenAPI-basiert) fuer Frontend-Backend-Kompatibilitaet
- **Performance Tests** fuer pool-size=1 Szenarien (Hikari-Timeout unter Last)
- **E2E-Tests** fuer Chains, ChainExecution, Assistant-Chat, Insights

---

## 8. Zusammenfassung

### Staerken
- Controller fast vollstaendig getestet (96.2%)
- Block-Executors gut abgedeckt (84.2%)
- Solide Integration-Test-Basis (Flyway, Repository, Controller, LazyLoading)
- Frontend E2E deckt Kern-Workflows ab

### Schwaechen
- **Assistant-Subsystem ist die groesste Luecke** — 1.322 LOC security-relevanter Code ohne jeden Test
- **WebSocket = 0% Coverage** — Terminal-Zugriff komplett ungetestet
- **Mapper = 0% Coverage** — Daten-Transformation ungetestet
- **Frontend Unit-Tests minimal** — nur 17 Dateien fuer 156 Source Files
- **Keine Performance/Load Tests** trotz pool-size=1 Architektur

### Coverage-Trend

Kein vorheriger Audit-Report vorhanden. Dieser Report setzt die **Baseline**.

| Bereich | Ist | Ziel (3 Monate) | Ziel (6 Monate) |
|---|---|---|---|
| Backend (Datei-Ebene) | 34.1% | 50% | 65% |
| Backend Services | 64.3% | 80% | 90% |
| Frontend Unit | 10.9% | 25% | 40% |
| Frontend E2E | 62.5% | 75% | 85% |
| Integration Tests | 30 Tests | 60 Tests | 100 Tests |

---

gate_status: YELLOW
gate_category: test-coverage
gate_description: Backend-Coverage bei 34.1% (Datei-Ebene), Services bei 64.3%. Kritische Luecken im Assistant-Subsystem (1.322 LOC), WebSocket (341 LOC) und Frontend (4.633 LOC komplett ungetestet). Quick Wins identifiziert.
