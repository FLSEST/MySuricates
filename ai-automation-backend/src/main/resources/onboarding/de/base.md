## MySuricates API

Dieses Projekt ist mit der MySuricates-Instanz unter {{BASE_URL}} verbunden, Projekt-ID `{{PROJECT_ID}}`.
Über die REST-API legst du TODOs an, startest Workflows und verfolgst ihre Läufe.

### Verbinden

```bash
MS={{BASE_URL}}/api/v1
API_KEY="${MYSURICATES_API_KEY:-$({{API_KEY_COMMAND}})}"
curl -s "$MS/system/health"                                 # ohne Key
curl -s "$MS/system/capabilities" -H "X-API-Key: $API_KEY"  # Preset, Module, Block-Typen
```

Was diese Instanz kann, steht in `/system/capabilities`: eingeschaltete Module und Block-Typen.
Endpunkte eines abgeschalteten Moduls antworten mit 404 `MODULE_DISABLED`, also dort nachsehen statt raten.

### TODOs – bei jeder Code-Änderung

1. Passendes TODO suchen: `GET $MS/todos?projectId={{PROJECT_ID}}&status=OPEN&status=IN_PROGRESS`
   (Antwort `{content, totalElements, …}`; weitere Filter `priority`, `tag`, `q`, `page`, `size`).
2. Fehlt eins, anlegen: `POST $MS/todos` mit dem Body unten.
3. Vor der ersten Änderung `PATCH $MS/todos/<id>/status` mit `{"status": "IN_PROGRESS"}`, danach `{"status": "DONE"}` (volle ID).
4. TODOs immer mit Titel nennen, etwa `82ca537f (Titel)`, nie die ID allein.

```json
{
  "title": "Kurzer Titel",
  "description": "Kontext in Markdown",
  "priority": "LOW | MEDIUM | HIGH | CRITICAL",
  "tags": ["bug"],
  "projectId": "{{PROJECT_ID}}",
  "source": "AI",
  "sourceAgent": "<dein Agent>",
  "agentSessionRef": "<ID deiner Sitzung, mit der sie sich fortsetzen lässt>"
}
```

- `PUT $MS/todos/<id>` ändert nur die gesendeten Felder.
- `GET $MS/todos/similar?projectId={{PROJECT_ID}}&title=<titel>` findet Dubletten.
- `POST $MS/todos/<id>/resolve` startet den am TODO hinterlegten Workflow (`linkedWorkflowId`).
- Ein neues OPEN-TODO kann über eine TodoTrigger-Regel selbst einen Fix-Workflow starten.

### Workflows ausführen, Läufe verfolgen

```bash
curl -s -X POST "$MS/workflows/<id>/execute" -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" -d '{"context": {"branch": "main"}}'
```

- Variablen heißen `context`, nicht `variables`. Die Antwort ist der Lauf mit `id` und `status`.
- `GET $MS/executions/<runId>` liefert `status`, `steps[]` (`blockName`, `blockType`, `status`, `output`), `error` und das Gate in `metadata.gate_status`.
- Status: PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED (nach einem Neustart der Instanz).

| Aktion | Endpunkt |
|---|---|
| Wartet der Lauf auf Freigabe? | `GET $MS/executions/<id>/approval` → `pending`, `options`, `rejectOptions` |
| Freigeben | `POST $MS/executions/<id>/approve` mit `{"answer": "<eine der options>"}` |
| Alles, was gerade wartet | `GET $MS/executions/pending-input` |
| Pausieren, fortsetzen, abbrechen | `POST $MS/executions/<id>/pause`, `/resume`, `/cancel` |

### Regeln

- Nie direkt auf die SQLite-Datenbank zugreifen, weder lesend noch schreibend – immer über die API.
- Shell-Blöcke eines Workflows rufen die MySuricates-API nicht auf; was sie brauchen, etwa `{{ open_todos }}`, setzt die Plattform ein.
- Cron hat sechs Felder, Sekunden zuerst: `0 0 8 * * MON-FRI`.
- Vorlagen und Blaupausen zum Anfangen: `GET $MS/workflow-templates?locale=de`.
