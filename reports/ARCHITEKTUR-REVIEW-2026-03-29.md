# Architektur-Review Report — MySuricates v0.6.2

**Datum:** 2026-03-29
**Auditor:** Claude Opus 4.6 (verifizierte Analyse)
**Scope:** Backend (Spring Boot 3.4, Java 21), Frontend (React 18, TypeScript)
**Methodik:** Statische Code-Analyse, Dependency-Graph-Auswertung, LOC-Metriken, DI-Injection-Zaehlung

---

## Executive Summary

**Gesamt-Bewertung: GELB — Solide Basis mit einem kritischen und mehreren hohen Refactoring-Zielen**

Die MySuricates-Plattform zeigt eine grundsaetzlich saubere Architektur mit klarer Schichtentrennung und konsistenten Patterns. Ein kritisches Finding: `WishService` mit 1489 LOC, 62 Methoden und 22 DI-Dependencies vereint 7 Verantwortlichkeiten und ist der zentrale Kopplung-Hub des Backends. Die uebrigen Probleme konzentrieren sich auf zwei weitere God Classes (`AssistantService`, `WorkflowExecutor`), Schichtverletzungen in Controllern, und uebergrosse Page-Komponenten im Frontend.

| Kategorie | CRITICAL | HIGH | MEDIUM | LOW |
|---|---|---|---|---|
| God Classes (Backend) | 1 | 3 | 2 | — |
| Zirkulaere Abhaengigkeiten | — | 1 | 2 | 1 |
| Schichtverletzungen | — | 2 | 3 | 1 |
| Frontend God Components | — | 1 | 2 | — |
| Uebermassige Kopplung | — | 2 | 1 | — |
| **Gesamt** | **1** | **9** | **10** | **2** |

**Top-3 dringendste Massnahmen:**

1. **`WishService` dekomponieren** (22 DI-Injections, 1489 LOC, 62 Methoden) — loest 3 `@Lazy`-Zyklen, eliminiert Dead Dependency, reduziert auf ~650 LOC (4-8h)
2. **`WorkflowExecutionRegistry` extrahieren** aus WorkflowExecutor — loest 4 von 9 `@Lazy`-Annotationen auf (2-3h)
3. **`AssistantService` dekomponieren** (17 DI-Injections, 1345 LOC) — Dead Code entfernen, 3 Services extrahieren (3-5h)

---

## Radar-Uebersicht

| Dimension | Score (1-10) | Findings | Kritischster Fund |
|---|---|---|---|
| God Classes | 5 | 6 | `WishService` — 1489 LOC, 62 Methoden, 22 DI-Deps, 7 Verantwortlichkeiten |
| Zirkulaere Deps | 6 | 4 | `WishService` → 3x `@Lazy` + weitere 6 `@Lazy` im Projekt |
| Schichtverletzungen | 6 | 6 | `ExecutionController` — 5-Branch Query-Dispatch direkt gegen Repository |
| Kopplung | 6 | 3 | `WishService` (22), `AssistantService` (17), `WorkflowExecutor` (17) DI-Deps |
| Package-Struktur | 9 | 0 | Sauber — klare Trennung controller/service/entity/repository/dto/mapper |
| Frontend-Architektur | 7 | 3 | `Schedules.tsx` — 788 LOC mit 300-Zeilen-Modal inline |

**Legende:** 10 = vorbildlich, 7 = solide, 5 = Handlungsbedarf, <4 = dringend

---

## Findings nach Kategorie

### 1. God Classes (Backend)

#### CRITICAL-1: WishService — 1489 LOC, 62 Methoden, 22 DI-Deps

**Datei:** `service/wish/WishService.java`

Die groesste Klasse im Projekt und ein klassischer God-Class-Fall. Vereint 7 Verantwortlichkeiten:

| Bereich | Methoden | LOC (ca.) |
|---|---|---|
| CRUD + State Machine | 8 | 150 |
| Async-Orchestrierung (fulfill-Loop) | 3 | 65 |
| Phase A: Decompose (AI-Call + Workflow-Erstellung) | 5 | 165 |
| Phase B: Execute (Polling) | 4 | 60 |
| Phase C: Evaluate (AI-Call + Parsing) | 2 | 75 |
| Prompt-Bau (System+User) | 8 | **580** |
| JSON/Keyword-Utilities | 6 | 50 |

**Symptome:**

- **Dead Dependency:** `hostClaude` (`HostClaudeService`) wird injiziert aber **nirgends aufgerufen**
- **3x `@Lazy` im Konstruktor:** `WorkflowExecutor`, `WorkflowTemplateService`, `WorkflowFactoryService` — Zeichen fuer zentrales Kopplungsproblem
- **9 direkte Repository-Zugriffe** statt Delegation an Domain-Services

**Refactoring-Vorschlag (3 Extraktionen):**

1. **`WishPromptBuilder`** — alle `build*Prompt`-Methoden + `extractKeywords` + `scoreRelevance` + `formatBlockPipeline` + `buildExecutionIntelligence`. Nimmt 10 Dependencies mit. **~580 LOC entfernt.**
2. **`WishExecutionService`** — `execute()`, `waitFor*Completion()`, `sleep()`. Nimmt `chainExecutor`, `workflowExecutor`, 2 Repositories mit. **~60 LOC entfernt.**
3. **`WishDecomposeService`** — `decompose()`, `verifyGeneratedWorkflows()`, `parseBlocks()`, `applyDefaultTimeouts()`, `inferModelFromBlocks()`. Nimmt 4 Dependencies mit. **~165 LOC entfernt.**

Ergebnis: WishService schrumpft auf ~650 LOC und ~8 Dependencies.

---

#### HIGH-1: AssistantService — 1345 LOC, 43 Methoden, 17 DI-Deps

**Datei:** `service/AssistantService.java`

Kombiniert 4 komplett unterschiedliche Anliegen in einer Klasse:

| Bereich | Methoden | LOC (ca.) |
|---|---|---|
| System-Prompt-Bau (Context Builder) | 12 | ~400 |
| Fetch-System (Lazy Context Loader) | 14 | ~350 |
| Tool-Execution (Mutations) | 7 | ~150 |
| Chat-Orchestrierung + Settings | 4 | ~100 |

**Symptome:**

- 120-Zeilen System-Prompt als Java `sb.append()`-Kette — gehoert in eine Resource-Datei
- `parseToolRequests()` ist **Dead Code** (wird nie aufgerufen)
- `appendTodos()` umgeht `TodoService` und queried Repository direkt
- Tool-Execution (TODO erstellen, Workflow erstellen) sind **stille Mutationen** ohne Audit-Trail

**Refactoring-Vorschlag (3 Extraktionen):**

1. **`AssistantContextBuilder`** — alle `buildSystemPrompt` + `append*`-Methoden. ~400 LOC, 9 Repository-Deps.
2. **`AssistantFetchService`** — alle `format*` + `executeFetch` + `parseFetchRequests`. ~350 LOC.
3. **`AssistantToolExecutor`** — `executeTools` + `executeTool` + `executeCreate*`. ~150 LOC, 4 Deps.

Ergebnis: AssistantService schrumpft auf ~100 LOC und ~5 Dependencies.

---

#### HIGH-2: WorkflowExecutor — 787 LOC, 41 Methoden, 17 DI-Deps

**Datei:** `service/workflow/WorkflowExecutor.java`

Vereint Execution-Engine mit Active-Run-Registry und Lifecycle-Management:

| Bereich | Methoden | LOC (ca.) |
|---|---|---|
| Execution-Engine (Block-Loop) | 5 | ~350 |
| Active-Run-Registry (ConcurrentHashMap) | 8 | ~80 |
| In-Flight-Control (pause/cancel/approve) | 4 | ~80 |
| Zombie-Cleanup | 2 | ~50 |
| Gate/Todo Post-Processing | — | ~40 |

**Kernproblem:** Die `activeExecutions` ConcurrentHashMap ist der Grund fuer 4 der 5 `@Lazy`-Annotationen im Projekt. `SystemTrayService`, `ContainerService`, `ExecutionSettingsService` — alle brauchen nur `getActiveRunCount()` oder `getActiveContainerIds()`, werden aber an den gesamten WorkflowExecutor mit 17 Deps gekoppelt.

**Refactoring-Vorschlag:**

1. **`WorkflowExecutionRegistry`** — `activeExecutions`-Map + alle Query-Methoden (`getActiveRunCount`, `isRunActive`, `getPendingApprovalCount`, `getActiveContainerIds`, `getContainerIdForRun`) + Control-Methoden (`pauseExecution`, `resumeExecution`, `cancelExecution`, `resolveApproval`). **Loest 3 von 5 `@Lazy`-Annotationen auf.**
2. **`ZombieRunReaper`** — `cleanupZombieRuns()` + `failRun()`. Braucht nur 3 Deps.

---

#### HIGH-3: SystemController — 382 LOC, 34 Methoden, 9 DI-Deps

**Datei:** `controller/SystemController.java`

God Controller mit 10 unterschiedlichen Verantwortlichkeiten:

- Rohes `VACUUM`-SQL via `DataSource` direkt im Controller
- `AppSettingsRepository` CRUD mit Side-Effect-Dispatch (`assistantService.refreshCachedModel()`)
- File-System Browse/Open via `ProcessBuilder` mit Pfad-Validierung — Security-relevante Logik im Controller
- `@Lazy @Autowired` Field-Injection auf AssistantService

**Refactoring-Vorschlag:**

1. **`SystemSettingsService`** — AppSettings CRUD + Side-Effect-Dispatch. Eliminiert die `@Lazy` auf AssistantService.
2. **`FileBrowserService`** — `xdg-open`, Pfad-Normalisierung, Blocklist. Security-Logik gehoert nicht in den Controller.
3. VACUUM nach `DatabaseBackupService` verschieben.

---

#### MEDIUM-1: WorkflowFactoryService — 512 LOC, 28 Methoden, 8 DI-Deps

Grosse Klasse mit gemischten Verantwortlichkeiten (Factory + Verify + Fix), aber noch im akzeptablen Bereich. Beobachten.

#### MEDIUM-2: AgenticBlockExecutor — 551 LOC, 18 Methoden, 6 DI-Deps

Groesster Block-Executor, aber inhaltlich koharent (Goal-Reason-Act-Observe Loop). Akzeptabel fuer die Komplexitaet des Features.

---

### 2. Zirkulaere Abhaengigkeiten (@Lazy)

| @Lazy-Stelle | Zyklus | Berechtigt? | Schweregrad |
|---|---|---|---|
| `SubWorkflowBlockExecutor` -> `WorkflowExecutor` | Block braucht Executor | **Ja** (dokumentiert) | — (kein Finding) |
| `ChainExecutor` -> `WorkflowExecutor` | Chain startet Workflows | **Ja** (analog SubWorkflow) | LOW |
| `WishService` -> `WorkflowExecutor/TemplateService/FactoryService` | 3x @Lazy = Kopplung-Spinne | **Nein** — WishService ist zu gross | HIGH |
| `SystemTrayService` -> `WorkflowExecutor` | Braucht nur `getActiveRunCount()` | **Nein** — Registry extrahieren | MEDIUM |
| `ContainerService` -> `WorkflowExecutor` | Braucht nur `getActiveContainerIds()` | **Nein** — Registry extrahieren | MEDIUM |
| `ExecutionSettingsService` -> `WorkflowExecutor + HostClaudeService` | 2x @Lazy, braucht nur Counts | **Nein** — Registry extrahieren | MEDIUM |

**Gemeinsame Loesung:** Die Extraktion von `WorkflowExecutionRegistry` aus WorkflowExecutor wuerde **4 von 9 `@Lazy`-Annotationen** aufloesen (SystemTray, Container, ExecutionSettings + indirekt WishService->WorkflowExecutor).

---

### 3. Schichtverletzungen

#### HIGH-4: ExecutionController queried direkt 5 Repository-Methoden

**Datei:** `controller/ExecutionController.java`

`listExecutions()` enthaelt einen 5-Branch Query-Dispatch (`findByWorkflowId...`, `findByStatusAndProjectId...`, `findByProjectId...`, `findByStatus...`, `findByOrder...`). Das ist Business-Logik.

`POST /{id}/retry` laedt den Run, extrahiert Workflow + Context und delegiert an `workflowExecutor.execute()` — eine Retry-Operation die in einen Service gehoert.

**Fix:** `ExecutionQueryService` oder `WorkflowRunService` fuer Query-Logik und Retry.

#### HIGH-5: WorkflowController enthaelt find-or-create Business-Logik

**Datei:** `controller/WorkflowController.java`

`getOrCreateCollectionProject()` (Zeile ~218) erstellt ein "Sammlung"-Projekt mit Hardcoded-Defaults wenn keins existiert. Das ist eine Business-Operation.

`repairWorkflow()` prueft `existsByWorkflowIdAndStatus(id, FAILED)` als Guard — gehoert in `AutoRepairService`.

**Fix:** `getOrCreateCollectionProject()` -> `ProjectService`, Repair-Guard -> `AutoRepairService`.

#### MEDIUM-3: ScheduleController queried 2 Repositories

**Datei:** `controller/ScheduleController.java`

`GET /{id}/runs` und `GET /calendar` fuehren Queries gegen `WorkflowRunRepository` und `ChainRunRepository` aus.

**Fix:** Methoden in `SchedulerService` verschieben.

#### MEDIUM-4: WorkflowTemplateController baut Project-Name-Lookup

**Datei:** `controller/WorkflowTemplateController.java`

Baut eine `projectId->name` Map via `ProjectRepository.findAll()` fuer Verification-Enrichment.

**Fix:** Enrichment in `TemplateVerificationService` oder Mapper.

#### MEDIUM-5: AssistantController importiert Event direkt

**Datei:** `controller/AssistantController.java`

Importiert `AssistantSessionUpdatedEvent` — ein Spring-Event das eigentlich Service-intern sein sollte.

**Fix:** Geringfuegig, aber Event-Publishing gehoert in den Service.

#### LOW-1: DocsController importiert Config

Import von `DocsConfig` ist akzeptabel — Config-Klassen sind Layer-neutral.

---

### 4. Frontend-Architektur

#### HIGH-6: Schedules.tsx — 788 LOC, 300-Zeilen-Modal inline

**Datei:** `pages/Schedules.tsx`

Die gesamte Create/Edit-Modal-Form (300 LOC) mit 3 Schedule-Typen, 7 Formfeldern und Cron/DateTime/Delay-Pickern ist inline im Hauptkomponent. Die `columns`-Array-Definition (120 LOC) wird bei jedem Render neu erstellt.

**Fix:** `ScheduleFormModal` extrahieren (~300 LOC), `columns` auslagern oder memoizen.

#### MEDIUM-6: useWebSocket.ts — 507 LOC, Types + Reducers + Hook vermischt

**Datei:** `hooks/useWebSocket.ts`

~160 LOC Type-Definitionen und 2 pure Reducer-Funktionen (`handleAgenticMessage` 107 LOC, `handleForEachMessage` 37 LOC) leben im Hook-File.

**Fix:**
1. Message-Types nach `types/websocket.ts` verschieben
2. Reducer-Funktionen in eigene Dateien extrahieren (testbar)
3. Hook selbst schrumpft auf ~200 LOC

#### MEDIUM-7: Todos.tsx — 909 LOC, Edit-Modal inline

**Datei:** `pages/Todos.tsx`

Insgesamt gut strukturiert (6 Komponenten), aber die Edit-Modal (120 LOC) ist inline. Bei 909 LOC gesamt lohnt die Extraktion.

**Fix:** `TodoEditModal` extrahieren.

---

### 5. Uebermassige Kopplung (>7 DI-Deps)

| Klasse | Deps | Schweregrad | Bereits in anderem Finding? |
|---|---|---|---|
| WishService | **22** | CRITICAL | Ja (CRITICAL-1) |
| AssistantService | **17** | HIGH | Ja (HIGH-1) |
| WorkflowExecutor | **17** | HIGH | Ja (HIGH-2) |
| SystemController | **9** | HIGH | Ja (HIGH-3) |
| WorkflowFactoryService | **8** | MEDIUM | Ja (MEDIUM-1) |
| ChainExecutor | **8** | MEDIUM | Akzeptabel — orchestriert Chain-Runs |

---

## Positiv-Befunde (kein Handlungsbedarf)

1. **Konsistente Package-Struktur** — Klare Trennung `controller/service/entity/repository/dto/mapper` ohne Ausnahmen
2. **Block-Executor-Pattern** — Saubere Strategy-Pattern-Implementierung fuer alle 11 Block-Typen
3. **MapStruct-Mapper** — Entity-DTO-Trennung konsequent durchgehalten
4. **Soft-Delete** — Einheitliches Pattern mit `@SQLRestriction` (Workflows) bzw. Repository-Filter (Projects)
5. **WebSocket-Integration** — Saubere Event-basierte Architektur fuer Real-Time-Updates
6. **Flyway-Migrationen** — Lueckenlose Migrationshistorie (V1-V47)
7. **Frontend-State-Management** — Klare Trennung: Zustand fuer Client-State, TanStack Query fuer Server-State
8. **i18n-Konsistenz** — Beide Sprachdateien werden synchron gepflegt
9. **Provider-Routing** — Flexibles System fuer AI-Provider-Auswahl pro Block/Workflow
10. **Frontend Pages < 600 LOC** (Chains, DialogImport, WorkflowDetail, Projects) sind gut strukturiert
11. **AssistantPanel.tsx** — bereits 7 Sub-Komponenten, gut zerlegt
12. **Wishes.tsx** — 8 Sub-Komponenten, vorbildlich dekomponiert

---

## Top-5 Refactoring-Prioritaeten

| # | Klasse/Datei | Problem | Vorgeschlagene Loesung | Aufwand | Risiko |
|---|---|---|---|---|---|
| 1 | `WishService` | 1489 LOC, 62 Methoden, 22 DI-Deps, 7 Verantwortlichkeiten, 3x `@Lazy`, Dead Dependency | Dekomposition: `WishPromptBuilder` + `WishExecutionService` + `WishDecomposeService`, Dead Code entfernen | 4-8h | Mittel — zentrale Business-Logik, gute Testabdeckung noetig |
| 2 | `WorkflowExecutor` | 787 LOC, 17 DI-Deps, `activeExecutions`-Map verursacht 4 `@Lazy`-Zyklen | `WorkflowExecutionRegistry` + `ZombieRunReaper` extrahieren | 2-3h | Niedrig — Interface-Extraktion, keine Verhaltensaenderung |
| 3 | `AssistantService` | 1345 LOC, 17 DI-Deps, Dead Code `parseToolRequests`, 120-Zeilen Inline-Prompt | `AssistantContextBuilder` + `AssistantFetchService` + `AssistantToolExecutor`, Prompt in Resource-Datei | 3-5h | Mittel — Streaming-Logik beachten |
| 4 | `ExecutionController` + `WorkflowController` | 5-Branch Query-Dispatch + find-or-create Business-Logik im Controller | `ExecutionQueryService` + `ProjectService.getOrCreateCollection()` | 2-3h | Niedrig — rein mechanisch |
| 5 | `Schedules.tsx` | 788 LOC, 300-Zeilen-Modal inline, `columns` nicht memoized | `ScheduleFormModal` extrahieren, `columns` memoizen | 1-2h | Niedrig — mechanische Arbeit |

---

## Refactoring-Roadmap

### Phase 1 — Quick Wins (1-2 Tage, isoliert, risikoarm)

| # | Massnahme | Aufwand | Abhaengigkeiten |
|---|---|---|---|
| 1.1 | Dead Dependency `HostClaudeService` aus `WishService` entfernen | 5 Min | Keine |
| 1.2 | Dead Code `parseToolRequests()` aus `AssistantService` entfernen | 5 Min | Keine |
| 1.3 | `ExecutionQueryService` einfuehren, Controller-Query-Logik verschieben | 1-2h | Keine |
| 1.4 | `WorkflowController.getOrCreateCollectionProject()` nach `ProjectService` verschieben | 30 Min | Keine |
| 1.5 | `ZombieRunReaper` aus `WorkflowExecutor` extrahieren | 30 Min | Keine |
| 1.6 | `ScheduleController` Repository-Zugriffe nach `SchedulerService` verschieben | 1h | Keine |
| 1.7 | `SystemController` VACUUM-SQL nach `DatabaseBackupService` verschieben | 30 Min | Keine |

### Phase 2 — Mittelfristig (3-5 Tage, mit Abhaengigkeiten)

| # | Massnahme | Aufwand | Abhaengigkeiten |
|---|---|---|---|
| 2.1 | **`WorkflowExecutionRegistry`** aus WorkflowExecutor extrahieren | 2-3h | Loest 4x `@Lazy` auf — enabler fuer 2.2 |
| 2.2 | **`WishService` Dekomposition:** `WishPromptBuilder`, `WishExecutionService`, `WishDecomposeService` | 4-8h | Nach 2.1 (weniger `@Lazy`-Konflikte), Tests parallel anpassen |
| 2.3 | **`AssistantService` Dekomposition:** `AssistantContextBuilder`, `AssistantFetchService`, `AssistantToolExecutor` | 3-5h | Streaming-Verhalten verifizieren |
| 2.4 | **`SystemSettingsService` + `FileBrowserService`** aus SystemController extrahieren | 2h | Keine — aber nach 2.1-2.3 weniger Merge-Konflikte |
| 2.5 | Frontend: `ScheduleFormModal` aus `Schedules.tsx`, `TodoEditModal` aus `Todos.tsx` extrahieren | 2-3h | UI-Regression-Tests |

### Phase 3 — Langfristig (optional, bei Bedarf)

| # | Massnahme | Aufwand | Trigger |
|---|---|---|---|
| 3.1 | WebSocket Types + Reducer aus `useWebSocket.ts` extrahieren | 1-2h | Bei WebSocket-Erweiterung |
| 3.2 | `WorkflowTemplateController` Project-Enrichment in Service verschieben | 30 Min | Bei Template-Feature-Erweiterung |
| 3.3 | `AssistantService` System-Prompt in Resource-Datei auslagern | 1h | Bei Prompt-Aenderungen |
| 3.4 | `ExecutionSettingsService` verbleibende `@Lazy`-Zyklen aufloesen | 30 Min | Wenn Settings-API refactored wird |

---

## Metriken-Zusammenfassung

| Metrik | Backend | Frontend |
|---|---|---|
| Groesste Datei | `WishService.java` (1489 LOC) | `Todos.tsx` (909 LOC) |
| Max DI-Injections | 22 (`WishService`) | n/a |
| `@Lazy`-Verwendungen | 9 (3 gerechtfertigt, 6 Designproblem) | n/a |
| Schichtverletzungen | 5 (Controller -> Repository) | 0 |
| Dead Code | 2 (WishService.hostClaude, AssistantService.parseToolRequests) | 0 |

---

## Gate-Status

```
gate_status: YELLOW
gate_category: architecture
gate_description: 1 CRITICAL (WishService: 22 DI, 1489 LOC, 62 Methoden), 9 HIGH Findings — gezielte Dekomposition erforderlich, keine systemische Integritaetsgefaehrdung
```

---

*Generiert am 2026-03-29 von Claude Opus 4.6 im Rahmen des MySuricates Architektur-Review Workflows.*
