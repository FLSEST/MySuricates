> *Zuletzt aktualisiert: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

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
| `messages` | TEXT | JSON-Array der Chat-Nachrichten |
| `created_at` | TEXT | Erstellungszeitpunkt |
| `updated_at` | TEXT | Letzter Update |

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
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Seitenspezifische Vorschläge |

## Kontext-Injektion

`AssistantService` injiziert je nach `context.page` unterschiedliche Plattform-Daten in den System-Prompt:

- **`workflows`** — Liste aktueller Workflows des Projekts
- **`runs`** — Status und Logs der letzten Runs
- **`todos`** — Offene TODOs des Projekts
- Weitere Seiten: allgemeiner Plattform-Kontext

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
- Persistiert `sessionId` im Frontend-State für zusammenhängende Konversationen
- WebSocket-Integration für Live-Streaming der Antwort (sofern implementiert)
