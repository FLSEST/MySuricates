## Plattform-Wissen

### Gates

Ein Block gibt Qualitäts-Marker aus:

```
gate_status: GREEN|YELLOW|RED
gate_category: security
gate_description: Keine kritischen Befunde
```

- Die schlechteste Einstufung aller Blöcke gilt; sie steht in `metadata.gate_status` des Laufs.
- Das Gate ändert den Status des Laufs nicht: `COMPLETED` mit Gate `RED` ist möglich. `FAILED` heißt Ausführungsfehler, nicht schlechtes Ergebnis.

### AutoTodo und TodoTrigger

- AutoTodo am Workflow: `"metadata": {"autoTodo": {"enabled": true, "mode": "auto"}}`. Modi: `auto` (GREEN schließt, YELLOW → MEDIUM, RED → HIGH), `track` (immer anlegen), `approval` (zur Bestätigung).
- Einzelne TODOs aus der Ausgabe: `todo_item: Titel | HIGH | tag1,tag2 | Beschreibung`.
- TodoTrigger (`GET/POST $MS/todo-triggers`): Eine Regel mit `tagPattern` (alle Tags müssen passen) und `targetWorkflowId` startet für neue TODOs einen Fix-Workflow; höchstens `maxAttempts` Versuche (Standard 3).
- Completion-Trigger (`$MS/completion-triggers`) starten einen Workflow, wenn ein anderer endet — bei Erfolg, Fehler oder immer. Alle Auslöser eines Workflows zeigt die Karte „Wird ausgelöst von“.
- Resolve (`POST $MS/todos/<id>/resolve`) gilt als erfolgreich, wenn der Lauf `COMPLETED` endet und das Gate `GREEN` ist oder fehlt; dann wird das TODO `DONE`, sonst kehrt es zu seinem vorigen Status zurück.

### Anbieter und Modelle

- KI-fähige Blöcke (`simple`, `agentic`, `skill-capability` und die Hüllen `foreach`, `while`, `conditional`, `switch`, `retry`) nehmen `provider` (`CLAUDE`, `PI`, `CODEX`), `model` und `effort`. Vorrang: Block > Workflow > Standard der Instanz.
- `CODEX` ist schreibgeschützt; Schreibversuche scheitern still.
- Claude-Modelle als Aliase, die immer auf das neueste Modell zeigen: `opus` (Analyse, Audits), `sonnet` (Routine, Code), `haiku` (einfache Klassifikation und Extraktion), `fable` (schwerste Aufgaben). Pi nimmt `<backend>/<modell>`, Codex einen Slug.
- Standard-Anbieter: `GET $MS/system/settings/default-ai-provider`, ändern mit `PUT` und `{"value": "CLAUDE"}`.

### Variablen

- `{{ previous_output }}`, `{{ step_0_output }}`, `{{ block.<name>.output }}` – Ausgaben früherer Schritte.
- `{{ workflow_run_id }}`, `{{ project_path }}`, `{{ run_timestamp }}`, `{{ open_todos }}`, `{{ previous_report }}` setzt die Plattform ein.
- Eigene Variablen über `context` beim Start: `{"context": {"branch": "main"}}` → `{{ branch }}`.

### Agentic-Budget

| Einstellung | Standard |
|---|---|
| `max_iterations` | 10 |
| `iteration_timeout_seconds` | 300 |
| `total_timeout_seconds` | 1800 |
| `max_total_tokens` | 200 000 |
