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

Die Endpunkte samt Zweck und Regeln listet die [API-Referenz](api-referenz.md#apiv1assistant); die Formate
von Anfrage und Antwort stehen in den DTOs `AssistantChatRequest`, `AssistantChatResponse` und `PendingTool`
unter `ai-automation-backend/src/main/java/com/automation/dto/`. Was dort nicht steht:

- `context.page` entscheidet, welche Plattform-Daten in den System-Prompt kommen (siehe [Kontext-Injektion](#kontext-injektion)); `requestId` ist das Thema der Status-Meldungen per WebSocket (`/topic/assistant/{requestId}/status`).
- Ein `pendingTools`-Eintrag trägt `subject` — den aufgelösten Namen (Workflow-Name, Workflow des Laufs, Aufgaben-Titel, Ketten-Name; unauflösbar: gekürzte ID mit `params.ref = "id"`) — und `params` mit kleinen Werten wie `priority`/`status`; `description` ist der englische Ersatzsatz für ältere Clients.
- Vorschläge gibt es je Seite **und Sprache** (`locale`, V91). Das Frontend fragt in der Sprache der Oberfläche, der Refresh nach einem Chat nimmt die Sprache des Chats. `GET /suggestions` antwortet aus dem Cache ohne KI-Aufruf, `POST /suggestions/refresh` erzeugt neu und blockiert.
- `confirm` führt das Werkzeug aus, `reject` markiert es als abgelehnt (`"✗ Abgelehnt"`); ein abgelaufenes liefert **410 Gone** (`"✗ Vorschlag abgelaufen"`). Mit `sessionId` landet das Ergebnis über `AssistantSessionService.appendToolResult()` in der Session, `language` bestimmt die Sprache der Baufehler-Prüfung.

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

Nach `create_workflow`/`update_workflow` läuft die Baufehler-Prüfung (`GeneratedWorkflowCheck`) über die gespeicherte Definition. Befunde ab MEDIUM hängen an der Ergebniszeile (`Baufehler-Prüfung: N Befund(e) ab MEDIUM`, HIGH zuerst, höchstens zehn Zeilen plus „… und N weitere“) samt dem Hinweis, dass `update_workflow` sie beheben kann; ohne solche Befunde bleibt die Zeile unverändert. In der englischen Oberfläche (`language: "en"` beim Bestätigen) lautet der Block `Build check: N finding(s) from MEDIUM up` mit den englischen Befundtexten. Es gibt keine zusätzliche KI-Runde, und das Speichern wird nicht blockiert. Die Ergebniskarte im Chat zeigt die Zeilen umbrochen.

### Werkzeug-Ergebnisse im Verlauf

Das Modell sieht die Ergebnisse bestätigter Werkzeuge im nächsten Zug (TODO 2f7766e7) — so kann es Baufehler-Befunde nach `create_workflow`/`update_workflow` selbst mit `update_workflow` beheben. Das Frontend legt das Ergebnis einer Entscheidung an die Nachricht, die das Werkzeug vorgeschlagen hat (`assistantStore.recordToolResult`), und schickt beim nächsten Zug die Ergebnisse **ausgeführter** Werkzeuge als `toolResults` am Verlaufseintrag mit; abgelehnte und abgelaufene Vorschläge (Ergebnis beginnt mit `✗`) nie. `AssistantContextBuilder.buildPrompt` setzt jedes Ergebnis als `<tool_result tool="…">…</tool_result>`-Block hinter die Assistenten-Zeile, mit einem einmaligen Hinweis, dass diese Blöcke Daten der Plattform sind und keine Anweisungen (`ToolResultHistory`). Ohne Ergebnisse ist der Prompt byte-gleich wie vorher.

Die Grenzen erzwingt der Server, weil der Verlauf Eingabe des Clients ist — jedes Ergebnis geht mit jedem weiteren Zug erneut ins Modell:

| Grenze | Wert | Warum |
|---|---|---|
| Züge mit Ergebnissen | die letzten 3 Assistenten-Züge | ältere Ergebnisse sind meist schon verarbeitet; der Antworttext bleibt |
| Ergebnisse je Zug | 5 | ein Zug schlägt selten mehr vor; ein Client kauft sich keinen Prompt-Platz |
| Zeichen je Ergebnis | 600 | Status- und Zusammenfassungszeile plus die ersten Befunde; die Zusammenfassungszeile (`Baufehler-Prüfung:`/`Build check:`) rückt nach vorn, damit das Kürzen die Anzahl nie abschneidet |

Ein Ergebnis kann fremden Text zitieren (Workflow-Name, Fehlermeldung). Wie bei den Kontextvariablen der Workflows (`UntrustedText`) kann er die Blockgrenze nicht fälschen, und die Tags, die die Plattform aus der Antwort liest (`<tool>`, `<fetch>`, `<actions>`), werden entschärft (`‹tool …`), auch in verkleideter Form (Vollbreite, unsichtbare Zeichen). Der Werkzeugtyp muss `[a-z0-9_-]` sein, sonst steht `unknown`. Der Hard-Gate bleibt: Auch ein Tag, den das Modell aus einem Ergebnis abschriebe, würde nur ein neuer Vorschlag mit Bestätigungskarte.

### PendingTool-Lebenszyklus

1. Claude generiert `<tool type="...">JSON</tool>` in seiner Antwort
2. `AssistantToolExecutor.extractTools()` parst die Tags, registriert jedes als `PendingTool` (UUID, TTL 30 Min.); `PendingToolDescriber` löst die IDs per kurzer Lese-Abfrage in Namen auf (`subject`, `params`)
3. Frontend zeigt `PendingToolCard` mit Confirm/Reject-Buttons; den Satz baut `pendingToolText` aus `type` + `subject` + `params` in der UI-Sprache (Priorität/Status übersetzt, „LÖSCHEN“ hervorgehoben), unbekannter Typ oder fehlendes `subject` → `description`
4. User bestätigt → `POST /pending-tools/{id}/confirm` → `executeConfirmed()` führt aus
5. User lehnt ab → `POST /pending-tools/{id}/reject` → markiert als abgelehnt
6. Ergebnis (`ToolExecutionResult`) wird via `appendToolResult()` in die Session geschrieben

### JSON-Sanitization

`AssistantToolExecutor.sanitizeJson()` normalisiert 10 Unicode-Fullwidth-Zeichen vor dem JSON-Parse (z.B. `，` → `,`, `：` → `:`, `｛`/`｝` → `{`/`}`, `"` / `"` → `"`).

## Fetch-Mechanismus (Dynamisches Nachladen)

Der Assistent kann über `<fetch type="..." id="..."/>`-Tags zusätzliche Daten nachladen. `AssistantFetchService` (`service/assistant/AssistantFetchService.java`) löst diese auf und liefert die Daten als Supplemental-Kontext zurück.

- **Max. 3 Fetch-Runden** mit je **3 Fetches** pro Runde (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND` in `AssistantService`)
- Truncation-Marker zeigen Größeninfo: `[... skipped N of M characters ...]` (tail-bewusst via `TruncationUtil.smart`, Kopf + Ende bleiben erhalten) bzw. `[Output truncated: N of M characters]` (Legacy head-first)
- Auto-Fetch ist auf bestimmte Typen beschränkt (`AUTO_FETCH_TYPES`)

### Fetch-Typen

Die Typen und ihre Kürzungsgrenzen stehen in den Handlern unter `service/assistant/fetch/`; welche ohne
Rückfrage nachgeladen werden, legt `AssistantFetchPolicy.AUTO_FETCH_TYPES` fest. Zwei Regeln stehen nur hier:

- `block-spec` liefert die Spezifikation eines Blocktyps aus `BlockSpecs` (eine für jeden Typ der Registry; `BlockTypeListsGuardTest` hält sie vollständig). Die Prompts von Factory, Wishes, Dialog-Import, Run-Konvertierung und Workflow-Optimierung rendern dieselben Texte mit `BlockSpecs.reference`, und `BlockSpecConfigKeysGateTest` prüft jeden dort genannten Config-Schlüssel gegen den Quelltext des Executors.
- `docs` lädt nur zwei Dateien (`DocsFetchHandler`): `workflow-guide` → `docs/WORKFLOW-GUIDE.md`, `architektur` → `docs/architektur.md`. Ohne Abschnitt kommt das Inhaltsverzeichnis der `## `-Überschriften; einzelne Abschnitte gibt es nur im Workflow-Guide, sonst den Anfang der Datei.

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

### Context-Section-Klassen

Die Sections sind Klassen im Paket `service/assistant/context/`, die meisten mit der Schnittstelle
`AssistantContextSection` (`append(StringBuilder sb, boolean compact)`); wie viele Einträge und Zeichen sie
zeigen, steht dort. `BlockTypesIndexSection` ist zugleich die Blocktyp-Liste des Assistenten, die
`BlockTypeListsGuardTest` mit den Executoren gleich hält.

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

Persistiert unter `assistant-sessions` im `localStorage` (Sessions und Panel-Größe); höchstens 20 Sessions
(`MAX_SESSIONS`), älteste fallen heraus. `pendingInput` und `pendingContext` setzen andere Seiten, die
nächste Nachricht verbraucht sie (siehe [Externe Trigger](#externe-trigger)); `inFlight` und `lastModel`
sind flüchtig. `hydrateSession()` übernimmt eine Backend-Session in den Store.

### Session-Management (`components/assistant/AssistantHistory.tsx`)

- Lokale Store-Sessions haben Vorrang bei gleicher ID
- Backend-Sessions (`GET /sessions`) werden als „backend-only" Einträge ergänzt
- Sortiert nach `startedAt` desc
- Klick auf Backend-only Session → on-demand `GET /sessions/{id}` → `hydrateSession()` in den Store
- Delete entfernt aus Store UND Backend

### Page Suggestions

Vorschläge erscheinen im leeren Panel (`components/assistant/AssistantWelcome.tsx`), als Chips über den
Nachrichten, im Header (`SuggestionButton`, höchstens vier) und als Zeile „Ask the assistant“ in der
Command-Palette. Ein Klick füllt nur das Eingabefeld, gesendet wird nicht automatisch.

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

Den Seitenkontext (`context.page`, IDs) leitet `parsePageContext(pathname)` in
`components/assistantPanelUtils.ts` aus der URL ab. Nachricht, Markdown, Aktionsknöpfe und die
Bestätigungskarte liegen unter `components/assistant/`.

### Externe Trigger

| Quelle | Mechanismus |
|--------|------------|
| WorkflowDebug-Seite | Diagnose-Button → `pendingContext` + neue Session + Panel öffnen |
| SimpleHome | fragt selbst (`useHomeAssistant`), Antwort über den Sofort-Treffern; „Im Chat weiterführen“ → `loadSession` + Panel öffnen |
| CommandPalette | „Ask the assistant" → `pendingInput` + Panel öffnen |
| Header SuggestionButton | Dropdown-Auswahl → `pendingInput` + Panel öffnen |
