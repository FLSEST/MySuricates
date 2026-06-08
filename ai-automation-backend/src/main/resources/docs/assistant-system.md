> *Zuletzt aktualisiert: 31. März 2026 — manuell*

# Assistant System

Der Assistant ist ein kontext-sensitiver Chat-Assistent, der als Floating-Panel in der Plattform verfügbar ist. Er kennt die aktuelle Seite, kann Workflows anlegen oder erklären und schlägt kontextsensitive Aktionen vor.

## Überblick

Das Assistant-System besteht aus drei Bereichen:

1. **Chat** — Freeform-Konversation via `POST /api/v1/assistant/chat`
2. **Sessions** — Persistierte Chat-Verläufe pro Page (V41-Migration: `assistant_sessions`)
3. **Page Suggestions** — Vorberechnete, seitenspezifische Aktions-Vorschläge (V42-Migration: `page_suggestions`)

## Backend-Komponenten

### AssistantService

- Hauptklasse für Chat-Logik
- Bereitet den Kontext auf (aktuelle Seite, Workflow-Daten, etc.)
- Ruft Claude CLI auf dem Host auf (`HostClaudeService`)
- Standard-Modell: `claude-sonnet-4-6`
- Gibt `AssistantChatResponse` zurück (Antwort-Text + optional Aktionen)

### AssistantSessionService

- Persistiert Chat-Verläufe in der `assistant_sessions`-Tabelle (V41)
- Methode `appendMessages(sessionId, request, response)` — fügt Nutzer- und Assistenten-Nachricht an
- Sessions werden pro `sessionId` geführt; die Session-ID kommt vom Frontend

### PageSuggestionService

- Generiert und cached kontextsensitive Vorschläge pro UI-Seite (z.B. `"dashboard"`, `"workflows"`)
- Persistiert in der `page_suggestions`-Tabelle (V42)
- `getSuggestions(page)` — gibt gecachte Vorschläge zurück
- `refreshSuggestions(page)` — generiert neue Vorschläge mit Claude und speichert sie

### AssistantSessionUpdatedEvent

- Spring Application Event, das nach jedem Chat-Aufruf gefeuert wird
- Trägt den `page`-Wert aus dem Request-Kontext
- Kann genutzt werden, um Page Suggestions im Hintergrund zu aktualisieren

## API-Endpunkte

### Chat

```
POST /api/v1/assistant/chat
```

Request-Body (`AssistantChatRequest`):
```json
{
  "message": "Erkläre mir den letzten Workflow-Fehler",
  "sessionId": "optional-uuid",
  "context": {
    "page": "workflows",
    "workflowId": "abc-123"
  }
}
```

Response (`AssistantChatResponse`):
```json
{
  "reply": "Der Workflow ist fehlgeschlagen weil...",
  "actions": []
}
```

- `sessionId` ist optional. Wird er angegeben, wird der Chat-Verlauf persistiert.
- `context.page` steuert, welche Plattform-Daten in den System-Prompt injiziert werden.

### Page Suggestions

```
GET  /api/v1/assistant/suggestions?page=workflows
POST /api/v1/assistant/suggestions/refresh?page=workflows
```

- `GET` gibt gecachte Vorschläge als `List<String>` zurück (sofort, kein KI-Aufruf)
- `POST /refresh` generiert neue Vorschläge via Claude und gibt sie zurück (blocking)

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
| `page` | TEXT UNIQUE | UI-Seite (z.B. `"dashboard"`) |
| `suggestions` | TEXT | JSON-Array der Vorschläge |
| `generated_at` | TEXT | Zeitpunkt der letzten Generierung |

## Controller-Zuständigkeiten

Beide Controller sind unter dem Base-Path `/api/v1/assistant` registriert:

| Controller | Endpunkte | Beschreibung |
|---|---|---|
| `AssistantController` | `POST /chat` | Chat-Anfrage verarbeiten, Session persistieren |
| `AssistantController` | `GET /sessions` | Letzte 50 Sessions mit Preview (ohne Messages) |
| `AssistantController` | `GET /sessions/{id}` | Einzelne Session mit allen Nachrichten |
| `AssistantController` | `DELETE /sessions/{id}` | Session loeschen |
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Seitenspezifische Vorschläge |

## Tools (Server-seitige Aktionen)

Der Assistent kann über `<tool type="...">JSON</tool>`-Tags direkt Aktionen ausführen. Die Tags werden von `AssistantToolExecutor` geparst und serverseitig ausgeführt, bevor die Antwort an den User geht.

### Verfügbare Tools

| Tool | Pflichtfelder | Beschreibung |
|------|--------------|--------------|
| `create_todo` | `title` | Todo anlegen (optional: description, priority, tags, projectId) |
| `update_todo_status` | `id`, `status` | Todo-Status ändern (OPEN/IN_PROGRESS/DONE/BACKLOG) |
| `create_workflow` | `projectId`, `name` | Workflow anlegen (optional: blocks, instructions, model, defaultContext) |
| `update_workflow` | `workflowId` | Workflow-Felder aktualisieren (nur übergebene Felder werden geändert) |
| `delete_workflow` | `workflowId` | Workflow soft-deleten (Bestätigung durch User im Prompt vorgeschrieben) |
| `start_workflow` | `workflowId` | Workflow starten (optional: context für Input-Variablen) |
| `cancel_run` | `runId` | Laufenden Run abbrechen (Bestätigung durch User im Prompt vorgeschrieben) |
| `create_chain` | `name`, `entries` | Workflow-Chain anlegen |

### JSON-Sanitization

`AssistantToolExecutor.sanitizeJson()` normalisiert Fullwidth-Unicode-Zeichen (z.B. `，` → `,`) vor dem JSON-Parse, da Claude gelegentlich solche Zeichen generiert.

### Safety-Regeln

Destruktive Tools (`delete_workflow`, `cancel_run`) erfordern laut System-Prompt eine explizite Bestätigung durch den User vor der Ausführung.

## Fetch-Mechanismus (Dynamisches Nachladen)

Der Assistent kann über `<fetch type="..." id="..."/>`-Tags zusätzliche Daten nachladen. `AssistantFetchService` löst diese auf und liefert die Daten als Supplemental-Kontext zurück.

- **Max. 2 Fetch-Runden** pro Chat-Anfrage (konfiguriert in `AssistantService`)
- Truncation-Marker zeigen Größeninfo: `[Ausgabe gekürzt: 8000 von 23456 Zeichen]`
- Verfügbare Fetch-Typen: `run_detail`, `step_output`, `workflow_detail`, `workflow_runs`, `wish_detail`, `docs`, `pool_workflows`, `pool_workflow`, `project_context`

## Kontext-Injektion

`AssistantContextBuilder` injiziert je nach `context.page` unterschiedliche Plattform-Daten in den System-Prompt:

- **`workflows`** — Liste aktueller Workflows des Projekts
- **`runs`** — Status und Logs der letzten Runs
- **`todos`** — Offene TODOs des Projekts
- Weitere Seiten: allgemeiner Plattform-Kontext

Zusaetzlich auf **allen Seiten**:
- **Letzte 5 Sessions** — Erste User-Nachricht (150 Zeichen) + erste Assistenten-Antwort (100 Zeichen) pro Session
- **Workflow Memory** — Bis zu 8 aktive Memories (300 Zeichen pro Memory)

## Modell-Auswahl

- Standard: `claude-sonnet-4-6`
- Haiku (`claude-haiku-4-5`) ist zu ungenau für Assistent-Aufgaben
- Opus (`claude-opus-4-6`) ist zu langsam für interaktive Chats
- Das Modell kann über `workflow.model` oder System-Konfiguration übersteuert werden

## Frontend-Integration

Der Assistent ist als Floating-Panel (`AssistantPanel.tsx`) implementiert:
- Globale Einblendung über dem Layout, unabhängig von der aktuellen Seite
- Sendet den `page`-Kontext aus der aktuellen Route mit
- Zeigt Page Suggestions als Schnell-Aktionen an (klickbar → werden als Nutzer-Nachricht gesendet)
- Persistiert `sessionId` im Frontend-State fuer zusammenhaengende Konversationen
- Gespraechsverlauf wird aus dem Backend geladen (`GET /sessions`), mit localStorage gemergt (localStorage hat Vorrang bei gleicher ID)
- Beim Klick auf eine Backend-only Session wird der volle Verlauf on-demand nachgeladen und in den Zustand-Store hydriert
- WebSocket-Integration für Live-Streaming der Antwort (sofern implementiert)
