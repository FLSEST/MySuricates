# Assistant System

Der Assistant ist ein kontext-sensitiver Chat-Assistent, der als Floating-Panel in der Plattform verfügbar ist. Er kennt die aktuelle Seite, kann Workflows anlegen oder erklären und schlägt kontextsensitive Aktionen vor.

## Überblick

Das Assistant-System besteht aus fünf Bereichen:

1. **Chat** — Freeform-Konversation via `POST /api/v1/assistant/chat`
2. **Sessions** — Persistierte Chat-Verläufe pro Page (V41-Migration: `assistant_sessions`)
3. **Page Suggestions** — Vorberechnete, seitenspezifische Aktions-Vorschläge (V42-Migration: `page_suggestions`)
4. **Pending Tools** — Bestätigungspflichtiger Tool-Mechanismus für destruktive Aktionen (in-memory Registry)
5. **Guardrails** — Backend-erzwungene Sicherheitsschicht vor jeder bestätigten Aktion (`AssistantToolValidator` + Rate-Limit + Audit-Trail):
   - **Payload-Validierung vor DB-Write**: `create_workflow`/`update_workflow`-Blöcke werden gegen die bekannten Block-Typen und Pflicht-Config-Keys geprüft (`command` bei simple/shell/retry/foreach/while, `goal` bei agentic); `create_chain`-Einträge brauchen `workflowId`. Strukturell kaputte Payloads werden abgelehnt, bevor sie die Workflows-Seite crashen können.
   - **Rate-Limit**: max. N bestätigte mutierende Aktionen pro Stunde (rolling window; Setting `assistant-max-actions-per-hour`, Default 30).
   - **Audit-Trail**: jede bestätigte Tool-Ausführung (Erfolg wie Ablehnung) landet als ActivityLog-Eintrag (Kategorie SYSTEM, Quelle `AssistantToolExecutor`).

## Backend-Komponenten

### AssistantService

- Hauptklasse für Chat-Logik (`service/AssistantService.java`) — orchestriert Kontext, Fetch-Runden, Tools und Vorschläge
- Bereitet den Kontext auf (aktuelle Seite, Workflow-Daten, etc.)
- Jeder CLI-Aufruf geht über `AssistantChatCaller` (siehe unten)
- Auto-Fetch-Loop: bis zu **3 Runden** mit je **3 Fetches** pro Runde (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND`)
- Erkennt explizite Mutations-Absicht via Regex (deutsche Verben + Ziel-Nomen) — nur dann werden mutating Actions durchgelassen
- Action-Suggestions werden parallel via `AssistantActionSuggestionService` geholt (35s Timeout)
- Gibt `AssistantChatResponse` zurück (Antwort-Text + Aktionen + optional PendingTools)

### AssistantChatCaller

- Die CLI-Seite des Chats (`service/assistant/AssistantChatCaller.java`), aus `AssistantService` herausgelöst
- Ruft Claude CLI auf dem Host auf (`HostClaudeService`) — interaktiv (`priority`), mit dem Chat-Timeout aus `HostClaudeConfig`; ein Exit-Code ≠ 0 bricht mit Fehler ab, statt den Fehlertext als Antwort auszugeben
- Standard-Modell: `sonnet` (wird beim Start in `app_settings` geseedet, cached in `cachedAssistantModel`), dazu die Basisanweisungen aus `assistant-system-prompt.md`
- Erlaubte CLI-Tools: `Read`, `Grep`, `Glob`, `WebFetch` (`CHAT_ALLOWED_TOOLS`)

### AssistantSessionService

- Persistiert Chat-Verläufe in der `assistant_sessions`-Tabelle (V41) (`service/AssistantSessionService.java`)
- `appendMessages(sessionId, request, response)` — fügt Nutzer- und Assistenten-Nachricht an, speichert PendingTools-Metadaten
- `appendToolResult(sessionId, result)` — hängt ein `ToolExecutionResult` an die letzte Assistenten-Nachricht mit passendem PendingTool
- `listRecentSessions()` — Top **50** Sessions sortiert nach `startedAt` desc
- `getSession(id)` / `deleteSession(id)` — Einzelzugriff / Löschung
- Preview wird auf **100 Zeichen** gekürzt (erste User-Nachricht)

### PageSuggestionService

- Generiert und cached kontextsensitive Vorschläge pro UI-Seite (`service/PageSuggestionService.java`)
- Persistiert in der `page_suggestions`-Tabelle (V42)
- `getSuggestions(page)` — gibt gecachte Vorschläge oder Defaults zurück
- `refreshSuggestions(page)` — generiert via Claude (nutzt letzte 10 Sessions der Seite) und speichert
- `onSessionUpdated(AssistantSessionUpdatedEvent)` — `@Async @EventListener`, löst gedrosselten Refresh aus
- Throttle: **600 Sekunden** (10 Min.) pro Seite, Timeout: **60 Sekunden**, Max. **4** Vorschläge
- Eigenes Modell: `suggestions-model` Setting (Default: `sonnet`)
- Defaults für 13 Seiten: `run`, `workflow`, `workflows`, `runs`, `todos`, `project`, `projects`, `schedules`, `activity`, `insights`, `wishes`, `factory`, `chains`

### AssistantSessionUpdatedEvent

- Spring Application Event (`event/AssistantSessionUpdatedEvent.java`), das nach jedem Chat-Aufruf gefeuert wird
- Record mit einem Feld: `String page`
- Wird von `AssistantController.chat()` nach dem Persistieren gefeuert
- Konsumiert von `PageSuggestionService.onSessionUpdated()` für gedrosselten Suggestion-Refresh

### AssistantActionSuggestionService

- Generiert kontextsensitive Action-Vorschläge parallel zum Chat (`service/assistant/AssistantActionSuggestionService.java`)
- Timeout: **30 Sekunden**, Max. **4** Aktionen
- Modell: `suggestions-model` Setting (Default: `sonnet`)
- Navigations-Typen: `show_run`, `show_workflow`, `start_wish`, `ask_choice`, `run_workflow`
- Propose-Typen (werden zu PendingTools konvertiert): `propose_create_todo`, `propose_cancel_run`, `propose_rerun_workflow`, `propose_update_todo_status`
- Prompt enthält Page-Kontext (max 6000 Zeichen), Workflow-Index (Top 40), letzte 5 Runs

### AssistantChatBroadcaster

- WebSocket-Broadcast via `SimpMessagingTemplate` (`service/assistant/AssistantChatBroadcaster.java`)
- `broadcastStatus(requestId, phase, detail, startNanos)` → Topic `/topic/assistant/{requestId}/status`
- `broadcastEvent(sessionId, eventKind, title, detail, linkTo)` → Topic `/topic/assistant/{sessionId}/events`
- Phasen: `RECEIVED`, `BUILDING_CONTEXT`, `THINKING`, `FETCHING_DATA`, `EXECUTING_TOOLS`, `COMPLETED`, `ERROR`

### PendingToolRegistry

- In-Memory-Registry für bestätigungspflichtige Tool-Aufrufe (`service/assistant/PendingToolRegistry.java`)
- `ConcurrentHashMap<String, PendingTool>`, TTL: **30 Minuten**
- `register(type, description, payloadJson)` — erzeugt UUID, setzt Ablaufzeit
- `take(id)` — atomares Entfernen und Zurückgeben (filtert abgelaufene)
- `cleanupExpired()` — `@Scheduled(fixedDelay = 300000)` (alle 5 Min.)

## API-Endpunkte

### Chat

```
POST /api/v1/assistant/chat
```

Request-Body (`AssistantChatRequest`):
```json
{
  "message": "Erkläre mir den letzten Workflow-Fehler",
  "history": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}],
  "context": {
    "page": "workflows",
    "entityId": "optional",
    "projectId": "optional",
    "workflowId": "abc-123",
    "runId": "optional",
    "pathname": "/workflows/abc-123"
  },
  "provider": "optional-provider-id",
  "language": "de",
  "sessionId": "optional-uuid",
  "requestId": "optional-uuid"
}
```

Response (`AssistantChatResponse`):
```json
{
  "message": "Der Workflow ist fehlgeschlagen weil...",
  "actions": [],
  "model": "sonnet",
  "provider": "CLAUDE",
  "responseTimeMs": 3200,
  "pendingTools": []
}
```

- `message` (Pflicht), `history`, `context`, `provider`, `language`, `sessionId`, `requestId` — alle außer `message` optional
- `context.page` steuert, welche Plattform-Daten in den System-Prompt injiziert werden
- `requestId` wird für WebSocket-Status-Updates verwendet (Topic `/topic/assistant/{requestId}/status`)
- Response-Felder `provider`, `responseTimeMs` nur bei Wert vorhanden (`@JsonInclude(NON_NULL)`), `pendingTools` nur bei nicht-leer (`@JsonInclude(NON_EMPTY)`)
- Ein `pendingTools`-Eintrag: `{"id", "type", "description", "subject", "params", "expiresAt"}` — `subject` ist der aufgelöste Name (Workflow-Name, Workflow des Laufs, Aufgaben-Titel, Ketten-Name; unauflösbar: gekürzte ID mit `params.ref = "id"`), `params` kleine Werte wie `priority`/`status`, `description` der englische Ersatzsatz für ältere Clients

### Page Suggestions

```
GET  /api/v1/assistant/suggestions?page=workflows&locale=en
POST /api/v1/assistant/suggestions/refresh?page=workflows&locale=en
```

- Vorschläge gibt es je Seite **und Sprache** (`locale` = `de` | `en`, Vorgabe `de`, V91). Das Frontend sendet die Sprache der Oberfläche, und der Refresh nach einem Chat nimmt die Sprache des Chats (`AssistantChatRequest.language`).
- `GET` gibt gecachte Vorschläge als `List<String>` zurück (sofort, kein KI-Aufruf)
- `POST /refresh` generiert neue Vorschläge via Claude und gibt sie zurück (blocking)

### Pending Tools

```
POST /api/v1/assistant/pending-tools/{id}/confirm
POST /api/v1/assistant/pending-tools/{id}/reject
```

Request-Body (optional): `{"sessionId": "uuid"}`

- `confirm` führt das Tool aus und gibt `ToolExecutionResult` zurück (`{toolId, type, success, message}`)
- `reject` markiert das Tool als abgelehnt (Message: `"✗ Abgelehnt"`)
- Abgelaufene Tools (TTL 30 Min.) liefern HTTP **410 Gone** (Message: `"✗ Vorschlag abgelaufen"`)
- Bei Angabe von `sessionId` wird das Ergebnis via `AssistantSessionService.appendToolResult()` in die Session geschrieben

## Datenbankschema

### assistant_sessions (V41)

Speichert Chat-Verläufe als JSON-Array pro Session.

| Spalte | Typ | Beschreibung |
|---|---|---|
| `id` | TEXT PK | Session-UUID |
| `page` | TEXT | UI-Seite (z.B. `"workflows"`) |
| `entity_id` | TEXT | Optionale Entity-Referenz (z.B. Workflow-ID) |
| `preview` | TEXT | Erste User-Nachricht (max 100 Zeichen, V58) |
| `messages` | TEXT | JSON-Array der Chat-Nachrichten (volle Laenge) |
| `message_count` | INTEGER | Anzahl der Nachrichten |
| `started_at` | REAL | Erstellungszeitpunkt |
| `ended_at` | REAL | Letzter Update |

### page_suggestions (V42)

Cached Vorschläge pro UI-Seite.

| Spalte | Typ | Beschreibung |
|---|---|---|
| `id` | TEXT PK | UUID |
| `page` | TEXT NOT NULL UNIQUE | UI-Seite (z.B. `"dashboard"`) |
| `suggestions` | TEXT NOT NULL DEFAULT '[]' | JSON-Array der Vorschläge |
| `generated_by` | TEXT NOT NULL DEFAULT 'CLAUDE' | Generator-Kennung |
| `generated_at` | REAL NOT NULL | Zeitpunkt der letzten Generierung |
| `based_on_sessions` | INTEGER NOT NULL DEFAULT 0 | Anzahl berücksichtigter Sessions |

## Controller-Zuständigkeiten

Beide Controller sind unter dem Base-Path `/api/v1/assistant` registriert:

| Controller | Endpunkte | Beschreibung |
|---|---|---|
| `AssistantController` | `POST /chat` | Chat-Anfrage verarbeiten, Session persistieren |
| `AssistantController` | `GET /sessions` | Letzte 50 Sessions mit Preview (ohne Messages) |
| `AssistantController` | `GET /sessions/{id}` | Einzelne Session mit allen Nachrichten |
| `AssistantController` | `DELETE /sessions/{id}` | Session löschen |
| `AssistantController` | `POST /pending-tools/{id}/confirm` | PendingTool bestätigen und ausführen |
| `AssistantController` | `POST /pending-tools/{id}/reject` | PendingTool ablehnen |
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Seitenspezifische Vorschläge |

## Tools (Server-seitige Aktionen)

Der Assistent kann über `<tool type="...">JSON</tool>`-Tags Aktionen vorschlagen. `AssistantToolExecutor` (`service/assistant/AssistantToolExecutor.java`) parst die Tags, registriert sie als **PendingTools** in der `PendingToolRegistry` und ersetzt sie im Antworttext durch einen Platzhalter (`"[Action awaiting confirmation: <beschreibung>]"`). Die Ausführung erfolgt erst nach expliziter Bestätigung durch den User via `POST /pending-tools/{id}/confirm`.

### Verfügbare Tools

| Tool | Pflichtfelder | Beschreibung |
|------|--------------|--------------|
| `create_todo` | `title` | Todo anlegen (optional: description, priority, tags, projectId) |
| `update_todo_status` | `id`, `status` | Todo-Status ändern (OPEN/IN_PROGRESS/DONE/BACKLOG) |
| `create_workflow` | `projectId`, `name` | Workflow anlegen (optional: blocks, instructions, model, defaultContext) |
| `update_workflow` | `workflowId` | Workflow-Felder aktualisieren (nur übergebene Felder werden geändert, auch das Versions-Label `version` wie bei `PUT`) |
| `delete_workflow` | `workflowId` | Workflow soft-deleten |
| `start_workflow` | `workflowId` | Workflow starten (optional: context für Input-Variablen) |
| `cancel_run` | `runId` | Laufenden Run abbrechen |
| `create_chain` | `name`, `entries` | Workflow-Chain anlegen |

Alle 8 Tool-Typen sind in `MUTATING_TOOL_TYPES` definiert und erfordern User-Bestätigung.

### PendingTool-Lebenszyklus

1. Claude generiert `<tool type="...">JSON</tool>` in seiner Antwort
2. `AssistantToolExecutor.extractTools()` parst die Tags, registriert jedes als `PendingTool` (UUID, TTL 30 Min.); `PendingToolDescriber` löst die IDs per kurzer Lese-Abfrage in Namen auf (`subject`, `params`)
3. Frontend zeigt `PendingToolCard` mit Confirm/Reject-Buttons; den Satz baut `pendingToolText` aus `type` + `subject` + `params` in der UI-Sprache (Priorität/Status übersetzt, „LÖSCHEN“ hervorgehoben), unbekannter Typ oder fehlendes `subject` → `description`
4. User bestätigt → `POST /pending-tools/{id}/confirm` → `executeConfirmed()` führt aus
5. User lehnt ab → `POST /pending-tools/{id}/reject` → markiert als abgelehnt
6. Ergebnis (`ToolExecutionResult`) wird via `appendToolResult()` in die Session geschrieben

### JSON-Sanitization

`AssistantToolExecutor.sanitizeJson()` normalisiert 10 Unicode-Fullwidth-Zeichen vor dem JSON-Parse (z.B. `，` → `,`, `：` → `:`, `｛`/`｝` → `{`/`}`, `"` / `"` → `"`).

### Safety-Regeln

- Alle Tool-Tags werden als PendingTools registriert — keine direkte Ausführung ohne User-Bestätigung
- Abgelaufene PendingTools (>30 Min.) werden mit HTTP 410 Gone abgewiesen
- `PendingToolRegistry.cleanupExpired()` räumt alle 5 Minuten auf

## Fetch-Mechanismus (Dynamisches Nachladen)

Der Assistent kann über `<fetch type="..." id="..."/>`-Tags zusätzliche Daten nachladen. `AssistantFetchService` (`service/assistant/AssistantFetchService.java`) löst diese auf und liefert die Daten als Supplemental-Kontext zurück.

- **Max. 3 Fetch-Runden** mit je **3 Fetches** pro Runde (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND` in `AssistantService`)
- Truncation-Marker zeigen Größeninfo: `[... skipped N of M characters ...]` (tail-bewusst via `TruncationUtil.smart`, Kopf + Ende bleiben erhalten) bzw. `[Output truncated: N of M characters]` (Legacy head-first)
- Auto-Fetch ist auf bestimmte Typen beschränkt (`AUTO_FETCH_TYPES`)

### Verfügbare Fetch-Typen (18)

| Typ | Beschreibung | Truncation |
|-----|-------------|------------|
| `run_detail` | Run-Detail mit Steps | Error: smart(200,400), Output: smart(200,600) — tail-bewusst |
| `step_output` | Einzelner Step-Output (`runId/stepNumber`); Paging via `?offset=<n>` / `?tail=<n>` | Default smart(2000,6000); Window/Tail max 8000 |
| `workflow_detail` | Workflow-Überblick; je Block die Schlüssel, die sein Executor liest (`BlockConfigSummaryFormatter`, von `BlockSpecConfigKeysGateTest` geprüft) | Desc: 150, Instructions: 400, Prompt/Goal/Befehl: 200, Shell-Befehl: 300 |
| `workflow-full` | Vollständiger Workflow inkl. Config | Instructions: 600, Config-Values: 300 |
| `workflow-inputs` | Workflow-Input-Variablen | Values: 100 |
| `workflow_runs` | Letzte Runs eines Workflows | Top 5, Error: 100 |
| `find_workflow` | Workflow-Suche nach Name | Max 20 Ergebnisse |
| `wish_detail` | Wish-Detail | Text: 200, Error: 300 |
| `docs` | Dokumentations-Sektion | Voll: 8000, Sektion: 12000 |
| `pool_workflows` | Alle Workflows im Pool | Desc: 120 |
| `pool_workflow` | Einzelner Pool-Workflow, Blöcke wie `workflow_detail` | Instructions: 500 |
| `project_context` | Projekt-Kontext via `ProjectContextService` | — |
| `project_files` | Projekt-Dateiliste | Max 50 Top-Level, Tiefe 1, Max 10 Sub-Einträge |
| `project_file` | Einzelne Projektdatei | Max 500KB, Content: 8000 |
| `skill` | Skill-Detail | Desc: 200, AI-Context: 400, Capability-Desc: 100 |
| `integration` | Integration-Detail | Desc: 200, Account: 80, Action-Desc: 100 |
| `chain` | Chain-Detail | Desc: 200 |
| `block-spec` | Block-Typ-Spezifikation je Typ (`BlockSpecs`, eine für jeden Typ der Registry; `BlockTypeListsGuardTest` hält sie vollständig, `BlockSpecConfigKeysGateTest` prüft jeden genannten Config-Schlüssel gegen den Quelltext des Executors; die Prompts von Factory, Wishes, Dialog-Import, Run-Konvertierung und Workflow-Optimierung rendern dieselben Texte mit `BlockSpecs.reference`) | — |

Doc-Dateien: `workflow-guide` → `docs/WORKFLOW-GUIDE.md`, `architektur` → `docs/architektur.md`, `vision` → `docs/vision.md`

## Kontext-Injektion

`AssistantContextBuilder` (`service/assistant/AssistantContextBuilder.java`) klassifiziert `context.page` in eine `PageCategory` und injiziert abhängig davon unterschiedliche Context-Sections.

### Page-Kategorien

| Seite(n) | Kategorie |
|----------|-----------|
| `run`, `runs` | RUNS |
| `workflow`, `workflows`, `chains` | WORKFLOWS |
| `todos`, `project`, `projects` | TASKS |
| `wishes`, `factory` | WISHES |
| `schedules` | SCHEDULES |
| `debug` | DEBUG |
| alles andere | OVERVIEW |

### Context-Sections pro Kategorie

| Section | RUNS | WORKFLOWS | TASKS | WISHES | SCHEDULES | DEBUG | OVERVIEW |
|---------|------|-----------|-------|--------|-----------|-------|----------|
| Runs | — | — | — | — | — | — | ✓ |
| FailedWorkflows | — | — | — | — | — | ✓ | — |
| Todos | — | — | ✓ | — | — | — | ✓ |
| Wishes | — | — | — | ✓ | — | — | ✓ |
| Schedules | — | — | — | — | ✓ | — | ✓ |
| Projects | — | ✓ | ✓(compact) | — | — | — | ✓ |
| Workflows | — | ✓ | ✓(compact) | — | — | — | ✓ |
| Skills/Integrations/Chains/BlockTypes | — | ✓(compact) | — | — | — | — | ✓ |
| Memory & Sessions | — | — | — | — | — | — | ✓ |

**Sonderfall RUNS**: Wenn ein Run-ID aufgelöst werden kann, wird `buildRunFocusedSystemPrompt()` verwendet — ein schlanker Prompt mit nur dem Run/Workflow-Detail, ohne globale Sections. Der Workflow wird dabei separat per `workflowRepository.findById()` geladen (kein Lazy-Proxy-Zugriff — OSIV ist aus; kein `JOIN FETCH` — `@SQLRestriction` auf `Workflow` würde bei Soft-Delete `FetchNotFoundException` werfen). Bei **FAILED**-Runs wird die Diagnose-Evidenz direkt vorgeladen: Block-Typ + Block-Config des gescheiterten Steps sowie der Step-Output tail-bewusst (`TruncationUtil.smart(output, 500, 4000)`) — eine Diagnose braucht damit in der Regel keinen Fetch-Roundtrip mehr.

### 9 Context-Section-Klassen (Paket `service/assistant/context/`)

Alle implementieren `AssistantContextSection` mit `append(StringBuilder sb, boolean compact)`:

1. **RunsContextSection** — Aktive Runs + letzte 10 Runs
2. **TodosContextSection** — Alle nicht-DONE Todos; compact: Counts nach Priorität, full: Top 10
3. **WishesContextSection** — Aktive Wishes (Limit 5), fehlgeschlagene (Limit 5)
4. **SchedulesContextSection** — Nächste 24h
5. **SkillsIndexSection** — Max 20 Einträge, Desc: 80 Zeichen
6. **IntegrationsIndexSection** — Alle Provider mit Connected/Disconnected-Status
7. **ChainsIndexSection** — Max 15 Einträge, Desc: 80 Zeichen
8. **BlockTypesIndexSection** — 20 Block-Typen (statische Liste; deckungsgleich mit den 20 registrierten Block-Executoren, gepinnt durch `BlockTypeListsGuardTest`; Config-Schlüssel in den Kurzbeschreibungen prüft `BlockSpecConfigKeysGateTest`)
9. **FailedWorkflowsContextSection** — Kaputte Workflows via `WorkflowDebugService`, Error: 160 Zeichen

### Zusätzlich auf **OVERVIEW**-Seiten

- **Workflows** — Max **25** Workflows (fokussiertes Projekt priorisiert), Desc gekürzt auf **60** Zeichen
- **Letzte 5 Sessions** — Erste User-Nachricht (**150** Zeichen) + erste Assistenten-Antwort (**100** Zeichen)
- **Workflow Memory** — Bis zu **8** aktive Memories (**300** Zeichen pro Memory)
- **Projekt-Dateien** — Max **300** Zeichen, nur `docs/`-Unterverzeichnis expandiert (Limit 5 Dateien)

## Modell-Auswahl

- **Chat-Modell** (`assistant-model` Setting): Default `sonnet`, cached in `AssistantChatCaller.cachedAssistantModel`
- **Suggestions-Modell** (`suggestions-model` Setting): Default `sonnet`, verwendet von `PageSuggestionService` und `AssistantActionSuggestionService`
- Beide Modelle werden beim Start in `app_settings` geseedet und können zur Laufzeit via Settings-API geändert werden: `SystemSettingsService` meldet die Änderung als `AppSettingChangedEvent`, `AssistantSettingsListener` ruft `refreshCachedModel()`/`refreshCache()` — nur bei eingeschaltetem Assistenten-Modul; beim Einschalten zur Laufzeit liest er alle drei Werte nach
- Haiku (`haiku`) ist zu ungenau für Assistent-Aufgaben
- Opus (`opus`) ist zu langsam für interaktive Chats

## Frontend-Integration

### AssistantPanel (`components/AssistantPanel.tsx`)

Der Assistent ist als Floating-Panel im Root-Layout (`Layout.tsx`) implementiert:
- Floating-Button (unten rechts) mit `MeerkatIcon` toggled das Panel
- Resizable: Breite und Höhe per Drag-Handle, Default **420×600px** (in Zustand persistiert)
- Mobile-Responsive: Vollbreite, 85vh Höhe auf Screens < 640px
- ESC schließt das Panel
- Header zeigt „MySuricates" + zuletzt verwendetes Modell (ohne `claude-`-Prefix und Datums-Suffix) oder „Online"
- Senden, Warteschlange und Abbruch stecken in `hooks/useAssistantConversation.ts`, geteilt mit dem Startfeld der Simple-Startseite: eine Anfrage zur Zeit (`inFlight` im Store), weitere Nachrichten warten und gehen der Reihe nach raus; Abbruch per `AbortController`, eine wartende Nachricht wird herausgenommen. Die Logik liegt außerhalb von React, damit eine Antwort auch im Store landet, wenn die fragende Seite schon weg ist; Antworten tragen `replyTo` (Id der Nutzernachricht)
- Enter zum Senden, Shift+Enter für Zeilenumbruch, Auto-Growing Textarea (max 240px)

### Zustand Store (`stores/assistantStore.ts`)

Persistence-Key: `'assistant-sessions'` (localStorage)

| State | Typ | Default |
|-------|-----|---------|
| `open` | boolean | false |
| `sessions` | ChatSession[] | [] |
| `activeSessionId` | string \| null | null |
| `showHistory` | boolean | false |
| `panelWidth` | number | 420 |
| `panelHeight` | number | 600 |
| `pendingInput` | string \| null | null |
| `pendingContext` | AssistantPageContext \| null | null |
| `inFlight` | `{ sessionId, requestId, userMsgId }` \| null | null (flüchtig) |
| `lastModel` | string \| null | null (flüchtig) |

- `startNewSession()` — erzeugt neue Session, Limit **20** Sessions (`MAX_SESSIONS`)
- `appendMessage()` — Preview aus erster User-Nachricht (60 Zeichen)
- `hydrateSession()` — Backend-Session in den Store übernehmen (für History-Loading)
- `pendingInput` / `pendingContext` — von externen Seiten gesetzt, beim nächsten Send konsumiert

### Session-Management (`components/assistant/AssistantHistory.tsx`)

- Lokale Store-Sessions haben Vorrang bei gleicher ID
- Backend-Sessions (`GET /sessions`) werden als „backend-only" Einträge ergänzt
- Sortiert nach `startedAt` desc
- Klick auf Backend-only Session → on-demand `GET /sessions/{id}` → `hydrateSession()` in den Store
- Delete entfernt aus Store UND Backend

### Page Suggestions

- `AssistantEmptyState` (`components/assistant/AssistantWelcome.tsx`): Anzeige wenn keine Nachrichten — MeerkatIcon + Suggestion-Buttons
- `ContextChips` (gleiche Datei): Horizontale scrollbare Pill-Bar über Nachrichten (dismissible)
- `SuggestionButton` in Header (`components/layout/Header.tsx`): Sparkles-Icon, Dropdown mit max 4 Vorschlägen
- `CommandPalette` (`components/palette/CommandPalette.tsx`): „Ask the assistant"-Zeile ab 2 Zeichen Query
- Klick auf Suggestion füllt Input vor (kein Auto-Send)

### WebSocket-Integration (`hooks/useAssistantWebSocket.ts`)

- **Protokoll**: STOMP über SockJS, URL `{origin}/ws?ticket={ticket}` — das Einmal-Ticket holt `beforeConnect` per `api.fetchWsTicket()` vor jedem Verbindungsversuch (auch vor jedem Reconnect); der API-Key bleibt im Header
- **Reconnect-Delay**: 5000ms

Zwei STOMP-Subscriptions:

1. **Status-Updates** (per Request): `/topic/assistant/{requestId}/status`
   - Phasen: `RECEIVED` → `BUILDING_CONTEXT` → `THINKING` → `FETCHING_DATA` → `EXECUTING_TOOLS` → `COMPLETED` | `ERROR`
   - Terminal-Phasen (`COMPLETED`, `ERROR`) werden nach 500ms gecleared
   - Treibt `StatusIndicator` (Phase-Label + Elapsed Time)

2. **Push-Events** (per Session): `/topic/assistant/{sessionId}/events`
   - `AssistantPushEvent`: `eventKind`, `title`, `detail`, `linkTo`

### Weitere Komponenten

- **AssistantMessage** (`components/assistant/AssistantMessage.tsx`): Nachrichtenblase mit Collapse ab 300 px Höhe; Markdown-Rendering (react-markdown + remark-gfm) in `AssistantMarkdown.tsx`, `ActionButton.tsx` für `run_workflow`/`show_workflow`/`show_run`/`start_wish`/`ask_choice`, `PendingToolCard.tsx` mit Confirm/Reject (Zustand in `hooks/usePendingToolDecision.ts`)
- **StatusIndicator** (`components/assistant/AssistantWelcome.tsx`): WebSocket-Phasen oder rotierende Thinking-Phrases (2,5s Intervall)
- **assistantPanelUtils** (`components/assistantPanelUtils.ts`): `parsePageContext(pathname)` extrahiert strukturierten Kontext aus URL

### Externe Trigger

| Quelle | Mechanismus |
|--------|------------|
| WorkflowDebug-Seite | Diagnose-Button → `pendingContext` + neue Session + Panel öffnen |
| SimpleHome | fragt selbst (`useHomeAssistant`), Antwort über den Sofort-Treffern; „Im Chat weiterführen“ → `loadSession` + Panel öffnen |
| CommandPalette | „Ask the assistant" → `pendingInput` + Panel öffnen |
| Header SuggestionButton | Dropdown-Auswahl → `pendingInput` + Panel öffnen |
