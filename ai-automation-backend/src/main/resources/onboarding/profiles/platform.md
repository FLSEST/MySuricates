## Plattform-Wissen

### Gate-Status-System

Workflow-Blöcke können Qualitäts-Gates emittieren:
```
gate_status: GREEN|YELLOW|RED
gate_category: security
gate_description: Keine kritischen Findings
```
- Höchste Severity aller Blöcke gewinnt (GREEN < YELLOW < RED)
- Gate-Status wird in `metadata` des Runs gespeichert
- Gate beeinflusst NICHT den Run-Status: `COMPLETED` + `RED` Gate ist möglich
- `FAILED` = Ausführungsfehler (Timeout, Crash), nicht schlechtes Ergebnis

### AutoTodo-System

Workflows können automatisch TODOs aus Ergebnissen erstellen:
- Aktivierung: `metadata: { autoTodo: { enabled: true, mode: "track" } }` im Workflow
- Modi: `auto` (GREEN schließt TODOs, YELLOW→MEDIUM, RED→HIGH), `track` (immer erstellen), `approval` (zur Bestätigung)
- Granulare TODOs: Blöcke können `todo_item: Titel | HIGH | tag1,tag2` im Output emittieren → individuelle TODOs pro Finding

### TodoTrigger (Self-Improvement-Loop)

TODOs können automatisch Fix-Workflows starten:
```
Audit-Workflow → AutoTodo → TODO (OPEN) → TodoTrigger → Fix-Workflow → TODO (DONE)
```
- Trigger matcht TODOs per Tag-Pattern (AND-Semantik)
- Loop-Prevention: max 3 Fix-Versuche, keine TODOs aus Fix-Runs, kein Doppel-Trigger bei laufendem Fix
- CRUD: `GET/POST/DELETE /api/v1/todo-triggers`

### Manuelle Todo-Workflow-Verknuepfung

Zusätzlich zum automatischen `TodoTrigger` gibt es einen manuellen Resolve-Flow pro TODO:
- Ein TODO kann optional `linkedWorkflowId` tragen.
- Dieser Link allein startet noch nichts.
- Der Workflow wird nur durch `POST /api/v1/todos/{id}/resolve` oder die UI-Aktion `Loesen` gestartet.
- Beim Start: TODO `status=IN_PROGRESS`, `resolutionState=RUNNING`
- Bei Erfolg: TODO `status=DONE`, `resolutionState=SUCCEEDED`
- Bei Fehlschlag: TODO springt auf den vorherigen Status zurueck, `resolutionState=FAILED`

Produktverhalten in der UI:
- Im TODO-Edit-Dialog kann optional ein Workflow verlinkt werden.
- In Liste und Kanban erscheint dann ein `Loesen`-Button.
- Solange ein Resolve-Run laeuft, wird das TODO eng gepollt und der Button deaktiviert.
- Die UI zeigt Workflow-Badge, letzten Fix-Run und den aktuellen `resolutionState`.

Abgrenzung:
- `TodoTrigger` = regelbasiert/automatisch
- `linkedWorkflowId` + `Loesen` = manuell/pro TODO

### Modell-Empfehlungen

| Use Case | Modell | Warum |
|---|---|---|
| Code-Analyse, Security-Audits | `claude-opus-4-6` | Präzision, weniger Halluzinationen |
| Einfache Tasks, Generierung | `claude-sonnet-4-6` | Schneller, günstiger |
| Chat/Assistent | `claude-sonnet-4-6` | Gute Balance |
| Vermeiden | `claude-haiku-4-5` | Zu ungenau für die meisten Tasks |

Modell im Workflow setzen: `"model": "claude-opus-4-6"` im Workflow-JSON

### Variablen-Konventionen

In Prompts und Shell-Commands verfügbar:
- `{{ previous_output }}` — Output des vorherigen Steps
- `{{ open_todos }}` — Offene TODOs des Projekts (injiziert vom Executor)
- `{{ workflow_run_id }}` — ID des aktuellen Runs
- Eigene Variablen via `context` beim Ausführen: `{ "context": { "branch": "main" } }` → `{{ branch }}`

### Agentic-Block-Safety

| Einstellung | Standard | Beschreibung |
|---|---|---|
| `max_iterations` | 10 | Harte Iterationsgrenze |
| `iteration_timeout_seconds` | 300 | Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamt-Timeout (30min) |
| `max_total_tokens` | 200000 | Token-Budget (Input+Output) |
| `max_repeated_actions` | 3 | Abbruch bei N identischen Aktionen |
| `require_approval` | false | Genehmigung vor jeder Aktion |

### Provider-Routing

Alle Block-Typen unterstützen `"provider": "CODEX"` im Workflow-JSON → nutzt OpenAI Codex statt Claude.
Standard-Provider ist konfigurierbar: `GET/PUT /api/v1/system/settings/default-ai-provider`

### Workflow Instructions

Zwei Ebenen:
1. **Globale Instructions** — gelten für alle Workflows (in `agent-instructions.md`)
2. **Workflow-spezifische Instructions** — pro Workflow editierbar, werden an Claude CLI übergeben
