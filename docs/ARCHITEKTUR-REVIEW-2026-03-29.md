# Architektur-Review Report — MySuricates v0.6.2

**Datum:** 2026-03-29
**Auditor:** Claude Opus 4.6 (verifizierte Analyse)
**Scope:** Backend (Spring Boot 3.4, Java 21), Frontend (React 18, TypeScript)
**Methodik:** Statische Code-Analyse, Dependency-Graph-Auswertung, LOC-Metriken, DI-Injection-Zaehlung

---

## Executive Summary

**Gesamt-Bewertung: GELB — Solide Basis mit gezieltem Refactoring-Bedarf**

Die MySuricates-Plattform zeigt eine grundsaetzlich saubere Architektur mit klarer Schichtentrennung und konsistenten Patterns. Keine kritischen Findings. Die identifizierten Probleme konzentrieren sich auf zwei God Classes im Backend (`WishService`, `AssistantService`) und uebergrosse Page-Komponenten im Frontend. Diese sind evolutionaer entstanden und gut lokalisiert — kein Zeichen systemischer Architekturprobleme.

| Schweregrad | Anzahl |
|---|---|
| CRITICAL | 0 |
| HIGH | 9 |
| MEDIUM | 10 |
| LOW | 8 |
| **Gesamt** | **27** |

**Top-3 dringendste Massnahmen:**

1. **`WishService` dekomponieren** (23 DI-Injections, 1489 LOC) — loest 3 `@Lazy`-Zyklen, eliminiert Dead Dependency, reduziert DI auf ~6 (4-8h)
2. **`ExecutionController` Layer-Verletzung beheben** — Query-Routing gehoert in einen Service (1-2h)
3. **`AssistantService` dekomponieren** (13 DI-Injections, 1134 LOC) — 8 Repositories direkt injiziert statt ueber Domain-Services (3-5h)

---

## Radar-Uebersicht

| Dimension | Score (1-10) | Findings | Kritischster Fund |
|---|---|---|---|
| God Classes | 6 | 3 | `WishService` — 1489 LOC, 23 DI-Injections, 8 Verantwortlichkeiten |
| Zirkulaere Deps | 7 | 6 | `WishService` → 3x `@Lazy` (Symptom der God Class) |
| Schichtverletzungen | 6 | 5 | `ExecutionController` — Query-Routing direkt gegen Repository |
| Kopplung | 7 | 3 | `WishService` — 23 Injections, 9 direkte Repository-Zugriffe |
| Package-Struktur | 8 | 0 | Sauber — klare Trennung controller/service/entity/repository |
| Frontend-Architektur | 6 | 10 | `Todos.tsx` — 909 LOC mit 5 Inline-Subcomponents |

**Legende:** 10 = vorbildlich, 7 = solide, 5 = Handlungsbedarf, <4 = dringend

---

## Findings nach Kategorie

### 1. God Classes (Backend)

#### HIGH — `WishService` (1489 LOC, 23 DI-Injections)

Die mit Abstand groesste und am staerksten gekoppelte Klasse. 8 eigenstaendige Verantwortlichkeiten in einer Datei:

| Verantwortlichkeit | LOC (ca.) | Extraktions-Ziel |
|---|---|---|
| Prompt Engineering (Decompose/Evaluate) | ~400 | `WishPromptBuilder` |
| Decompose-Phase (AI → Workflow-JSON) | ~150 | `WishDecomposeService` |
| Evaluate-Phase (Run → Bewertung) | ~80 | `WishEvaluateService` |
| JSON-Parsing / AI-Response-Normalisierung | ~80 | `WishAiResponseParser` |
| Async-Orchestrierung / Cancellation | ~80 | `WishExecutionOrchestrator` |
| CRUD / Entity-Lifecycle | ~50 | bleibt in `WishService` |
| User-Interaktion / State-Transitions | ~120 | bleibt in `WishService` |
| WebSocket-Broadcasting | ~30 | bleibt in `WishService` |

**Symptome:**
- **Dead Dependency**: `HostClaudeService` injiziert aber nie aufgerufen
- **9 direkte Repository-Zugriffe** statt Delegation an Domain-Services
- **3x `@Lazy`** als Symptom zirkulaerer Abhaengigkeiten: `WorkflowExecutor`, `WorkflowTemplateService`, `WorkflowFactoryService`
- Nach Extraktion waere `WishService` eine ~150-LOC-Fassade

#### HIGH — `AssistantService` (1134 LOC, 13 DI-Injections)

6 unabhaengige Verantwortlichkeiten:

| Verantwortlichkeit | LOC (ca.) | Extraktions-Ziel |
|---|---|---|
| System-Prompt-Aufbau (8 `append*()`-Methoden) | ~300 | `AssistantContextBuilder` |
| Dynamic Fetch (Detail-Queries mid-Response) | ~280 | `AssistantFetchService` |
| Tool-Ausfuehrung (`<tool>`-Tags) | ~90 | `AssistantToolExecutor` |
| Chat-Orchestrierung | ~150 | bleibt in `AssistantService` |
| Settings / Modell-Management | ~30 | `AssistantSettingsService` |
| Utility-Helpers | ~50 | verteilen |

**Symptome:**
- 8 Repositories direkt injiziert statt ueber Domain-Services
- `EmptyState`-Namenskollision im Frontend-Pendant (`AssistantPanel.tsx`)

#### LOW-MEDIUM — `WorkflowExecutor` (710 LOC, 16 DI-Injections)

Groesse ist **ueberwiegend gerechtfertigt** — der Executor ist der zentrale Koordinator. Zwei kleinere Extraktionen moeglich:
- `ZombieRunCleaner` — `@EventListener`-Startup-Logik (~20 LOC)
- `ExecutionStateView` — Read-only-Methoden (`getActiveRunCount()`, `isRunActive()`, etc.)

#### LOW — `WorkflowFactoryService` (512 LOC) und `AgenticBlockExecutor` (551 LOC)

Beide gerechtfertigt: Factory ist eine kohaerente Pipeline, AgenticBlockExecutor implementiert einen einzelnen komplexen Algorithmus. **Kein Handlungsbedarf.**

---

### 2. Schichtverletzungen (Controller → Repository)

#### HIGH — `ExecutionController` — Query-Routing im Controller

```java
// Zeile 37-75: Komplettes Filter-Dispatch im Controller
if (workflowId != null) runRepository.findByWorkflowId...
else if (status != null && projectId != null) runRepository.findByStatusAndProjectId...
else if (projectId != null) runRepository.findByProjectId...
```

**Fix:** `ExecutionService.findExecutions(status, workflowId, projectId, page)` einfuehren.

#### HIGH — `WorkflowController` — Paginierte Abfrage + Find-or-Create

- `GET /{id}/runs`: Paginierungslogik direkt im Controller
- `getOrCreateCollectionProject()`: Entity-Erzeugung mit Business-Policy im Controller

**Fix:** `WorkflowService.getRunsPage(id, page, size)` + `ProjectService.getOrCreateCollection()`

#### MEDIUM — `ScheduleController` — Kalender-Aggregation

Calendar-Endpoint aggregiert `WorkflowRuns` + `ChainRuns` direkt aus Repositories.

**Fix:** `ScheduleService.getCalendarData(from, to)`

#### MEDIUM — `WorkflowTemplateController` — Project-Name-Join

Laedt alle Projects fuer Name-Aufloesung in Verification-Responses.

**Fix:** In `TemplateVerificationService` integrieren.

#### MEDIUM — `ExecutionController` — `ApprovalRequest` aus executor-Package

Controller baut direkt `HashMap`-Response aus internem `ApprovalRequest`-Typ.

**Fix:** `ApprovalStatusResponse`-DTO + `WorkflowExecutor.getPendingApprovalStatus(id)`.

---

### 3. Zirkulaere Abhaengigkeiten (@Lazy-Analyse)

| Stelle | Zyklus-Typ | Bewertung |
|---|---|---|
| `SubWorkflowBlockExecutor` → `WorkflowExecutor` | Strukturell unvermeidbar | **Bekannt/Akzeptiert** — kein Finding |
| `ChainExecutor` → `WorkflowExecutor` | Strukturell unvermeidbar | **Bekannt/Akzeptiert** — kein Finding |
| `WishService` → 3x `@Lazy` | Designproblem | **HIGH** — Symptom der God Class, loest sich durch Dekomposition |
| `ContainerService` ↔ `WorkflowExecutor` | Echter bidirektionaler Zyklus | **MEDIUM** — Fix: `ExecutionRegistry`-Interface |
| `SystemTrayService` → `WorkflowExecutor` | Defensiv, kein echter Zyklus | **LOW** — Harmlos, Optional: Event-Listener statt Polling |
| `ExecutionSettingsService` → 2x `@Lazy` | Indirekter Init-Order-Zyklus | **LOW** — `getActiveRunCount()` aus Settings-Response entfernen |

---

### 4. Uebermaessige Kopplung (Top 5)

| Klasse | DI-Injections | Bewertung |
|---|---|---|
| `WishService` | **23** | HIGH — siehe God Class |
| `WorkflowExecutor` | **16** | MEDIUM — teils gerechtfertigt, `ContainerRepository` redundant (bereits ueber `ContainerService` erreichbar) |
| `AssistantService` | **13** | MEDIUM — siehe God Class |
| `ChainExecutor` | 9 | OK fuer Orchestrator |
| `WorkflowFactoryService` | 9 | OK fuer Pipeline |

---

### 5. Frontend-Architektur

#### HIGH — Drei Pages mit umfangreichen Inline-Subcomponents

| Datei | LOC | Problem | Extraktion |
|---|---|---|---|
| `Todos.tsx` | 909 | 5 Inline-Subcomponents + 118-Zeilen Edit-Modal | `StatusDots`, `KanbanCard`, `KanbanBoard`, `TodoListItem`, `TodoEditModal`, `TodoQuickAdd`, `TodoFilterBar` |
| `Wishes.tsx` | 880 | `WishDetail` (310 LOC) macht eigenes Data-Fetching; 6 weitere Inline-Components | `WishDetail`, `ApprovalPanel`, `ClarificationPanel`, `DecomposeApprovalPanel`, `CrystallizeButton` |
| `Schedules.tsx` | 788 | 298-Zeilen Modal inline embedded | `ScheduleFormModal`, `ScheduleRunsPanel`, `ScheduleVariablesEditor` |

#### MEDIUM — Weitere aufteilbare Dateien

| Datei | LOC | Kern-Extraktion |
|---|---|---|
| `AssistantPanel.tsx` | 696 | `AssistantHistoryView`, `AssistantMessageBubble`, `usePanelResize`-Hook |
| `Chains.tsx` | 653 | `ChainDetail`, `ChainTemplateModal`, `ChainFormModal` |
| `DialogImport.tsx` | 625 | `DialogSuggestionPanel` (eigener State + nested Modal) |

#### LOW — Gerechtfertigt oder minor

| Datei | LOC | Bemerkung |
|---|---|---|
| `WorkflowExecution.tsx` | 551 | Dicht aber gerechtfertigt; `StepCard` nach `shared/` verschieben |
| `Runs.tsx` | 526 | **`any`-Props in `WorkflowsTab`/`ChainsTab`** sind ein Type-Safety-Risiko; `Pagination` → `shared/` |
| `Workflows.tsx` | 514 | Bereits gut refactored |
| `useWebSocket.ts` | 507 | Handler-Funktionen + Types auslagern fuer Testbarkeit |
| `WorkflowDetailModals.tsx` | 484 | Gerechtfertigt (8 Modals konsolidiert) |

#### Cross-Cutting: Duplizierte Format-Helpers

`Todos.tsx:formatRelative` und `DialogImport.tsx:formatDuration/formatDate` duplizieren Funktionen aus `utils/formatTime.ts`.

---

## Top-5 Refactoring-Prioritaeten

| # | Klasse/Datei | Problem | Vorgeschlagene Loesung | Aufwand | Risiko |
|---|---|---|---|---|---|
| 1 | `WishService` | 1489 LOC, 23 DI, 8 Verantwortlichkeiten, 3x `@Lazy` | Dekomposition in 4 Services + Fassade | 4-8h | Mittel — zentrale Business-Logik, gute Testabdeckung noetig |
| 2 | `ExecutionController` | Query-Routing direkt gegen Repository | `ExecutionService.findExecutions()` einfuehren | 1-2h | Niedrig — rein mechanisch |
| 3 | `AssistantService` | 1134 LOC, 13 DI, 8 Repositories direkt | `AssistantContextBuilder` + `AssistantFetchService` + `AssistantToolExecutor` | 3-5h | Mittel — Streaming-Logik beachten |
| 4 | `Todos.tsx` / `Wishes.tsx` / `Schedules.tsx` | 788-909 LOC, Inline-Subcomponents | Components in eigene Dateien extrahieren | 2-4h | Niedrig — mechanische Arbeit |
| 5 | `ContainerService` ↔ `WorkflowExecutor` | Bidirektionaler Zyklus via `@Lazy` | `ExecutionRegistry`-Interface extrahieren | 1-2h | Niedrig — Interface-Extraktion |

---

## Refactoring-Roadmap

### Phase 1 — Quick Wins (1-2 Tage, isoliert, risikoarm)

| # | Massnahme | Aufwand | Abhaengigkeiten |
|---|---|---|---|
| 1.1 | `ExecutionService.findExecutions()` einfuehren, Controller bereinigen | 1-2h | Keine |
| 1.2 | `WorkflowController`: `getRunsPage()` + `getOrCreateCollectionProject()` in Services | 1-2h | Keine |
| 1.3 | Dead Dependency `HostClaudeService` aus `WishService` entfernen | 5 Min | Keine |
| 1.4 | `ZombieRunCleaner` aus `WorkflowExecutor` extrahieren | 30 Min | Keine |
| 1.5 | Format-Helper-Duplikate in `Todos.tsx`/`DialogImport.tsx` durch `utils/formatTime.ts` ersetzen | 30 Min | Keine |
| 1.6 | `ScheduleController` Kalender-Aggregation → `ScheduleService` | 1h | Keine |

### Phase 2 — Mittelfristig (3-5 Tage, mit Abhaengigkeiten)

| # | Massnahme | Aufwand | Abhaengigkeiten |
|---|---|---|---|
| 2.1 | `WishService` Dekomposition: `WishPromptBuilder`, `WishDecomposeService`, `WishEvaluateService`, `WishExecutionOrchestrator` | 4-8h | Tests muessen parallel angepasst werden |
| 2.2 | `AssistantService` Dekomposition: `AssistantContextBuilder`, `AssistantFetchService`, `AssistantToolExecutor` | 3-5h | Streaming-Verhalten verifizieren |
| 2.3 | Frontend: `Todos.tsx`, `Wishes.tsx`, `Schedules.tsx` Inline-Components extrahieren | 2-4h | Keine technischen Abhaengigkeiten, aber UI-Regression-Tests |
| 2.4 | `ContainerService` ↔ `WorkflowExecutor` Zyklus → `ExecutionRegistry`-Interface | 1-2h | Abhaengig von 2.1 (weniger Konflikte) |

### Phase 3 — Langfristig (optional, bei Bedarf)

| # | Massnahme | Aufwand | Trigger |
|---|---|---|---|
| 3.1 | Frontend: `AssistantPanel.tsx`, `Chains.tsx`, `DialogImport.tsx` aufteilen | 2-3h | Bei naechster Feature-Erweiterung |
| 3.2 | `Runs.tsx` `any`-Props typisieren | 1h | Bei Type-Safety-Initiative |
| 3.3 | `useWebSocket.ts` Handler-Funktionen modularisieren | 1-2h | Bei WebSocket-Erweiterung |
| 3.4 | `ExecutionSettingsService` `@Lazy`-Zyklen aufloesen | 30 Min | Wenn Settings-API refactored wird |

---

## Architektur-Staerken

Neben den Findings ist festzuhalten, was **gut funktioniert**:

1. **Konsistente Package-Struktur** — Klare Trennung `controller/service/entity/repository/dto/mapper` ohne Ausnahmen
2. **Block-Executor-Pattern** — Saubere Strategy-Pattern-Implementierung fuer alle 11 Block-Typen
3. **MapStruct-Mapper** — Entity-DTO-Trennung konsequent durchgehalten
4. **Soft-Delete** — Einheitliches Pattern mit `@SQLRestriction` (Workflows) bzw. Repository-Filter (Projects)
5. **WebSocket-Integration** — Saubere Event-basierte Architektur fuer Real-Time-Updates
6. **Flyway-Migrationen** — Lueckenlose Migrationshistorie (V1-V47)
7. **Frontend-State-Management** — Klare Trennung: Zustand fuer Client-State, TanStack Query fuer Server-State
8. **i18n-Konsistenz** — Beide Sprachdateien werden synchron gepflegt
9. **Provider-Routing** — Flexibles System fuer AI-Provider-Auswahl pro Block/Workflow

---

## Metriken-Zusammenfassung

| Metrik | Backend | Frontend |
|---|---|---|
| Groesste Datei | `WishService.java` (1489 LOC) | `Todos.tsx` (909 LOC) |
| Max DI-Injections | 23 (`WishService`) | n/a |
| `@Lazy`-Verwendungen | 9 (6 gerechtfertigt, 3 Designproblem) | n/a |
| Schichtverletzungen | 5 (Controller → Repository) | 0 |
| Duplizierter Code | Minimal | 2 Format-Helper-Duplikate |

---

## Gate-Status

```
gate_status: YELLOW
gate_category: architecture
gate_description: 0 CRITICAL, 9 HIGH Findings — WishService (23 DI, 1489 LOC) und ExecutionController Layer-Verletzung als prioritaere Refactoring-Ziele
```

---

*Generiert am 2026-03-29 von Claude Opus 4.6 im Rahmen des MySuricates Architektur-Review Workflows.*
