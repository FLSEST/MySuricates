## Entwickler-Workflows

Bausteine für Code-Projekte, keine feste Reihenfolge:

- **Review:** `shell` holt `git diff`, `simple` bewertet; für tiefere Analysen `agentic` mit einem Ziel.
- **Tests:** `shell` mit `"checked_command": true` leitet aus dem Exit-Code ein Gate ab; `retry` (Standard: 3 Versuche) fängt wacklige Tests ab.
- **Auto-Fix:** `agentic` mit Ziel wie „Fix failing tests“, davor ein `approval`, wenn ein Mensch zustimmen soll.
- **Qualitäts-Gate:** Ein Block gibt `gate_status: GREEN|YELLOW|RED` aus (dazu `gate_category`, `gate_description`). Das Gate ändert den Status des Laufs nicht.
<!-- module: chains -->
- **Build → Test → Deploy** als Kette.
<!-- /module -->

### Workflows per API

- Anlegen: `POST $MS/workflows` mit `{"projectId": "{{PROJECT_ID}}", "name": "Tests", "blocks": [{"type": "shell", "name": "tests", "config": {"command": "npm test"}}]}` – die Blöcke stehen direkt in der Anfrage.
- Baufehler prüfen, ohne auszuführen: `GET $MS/workflows/<id>/analyze`.
- Versionen: `GET $MS/workflows/<id>/versions`, zurückholen mit `POST $MS/workflows/<id>/versions/<versionId>/restore`.
- Workflows dieses Projekts: `GET $MS/projects/{{PROJECT_ID}}/workflows`.
- Läufe: `GET $MS/executions?workflowId=<id>&status=FAILED&page=0&size=10` (ohne Schritte; Details über `/executions/<runId>`).

### Zeitpläne

- Zeitplan: `POST $MS/schedules` mit `{"workflowId": "<id>", "cronExpression": "0 0 8 * * MON-FRI", "description": "Werktags 8 Uhr"}`.
  Weitere Felder: `maxRuns`, `endDate`, `conflictBehavior` (`SKIP` als Standard oder `OVERLAP`), `inputContext`.
  Ein-/ausschalten: `POST $MS/schedules/<id>/enable` bzw. `/disable`.

### Nach Abschluss eines anderen Workflows

- Anlegen: `POST $MS/completion-triggers` mit `{"sourceWorkflowId": "<a>", "targetWorkflowId": "<b>", "triggerOnSuccess": true}` (auch `triggerOnFailure`, `triggerAlways`, `contextVariables`).
- Was A auslöst: `GET $MS/completion-triggers/workflow/<id>/as-source`; wovon B ausgelöst wird: `…/as-target`.

### Was startet Workflow X?

Die Karte „Wird ausgelöst von“ auf der Workflow-Seite bündelt alles. Per API: `GET $MS/schedules/workflow/<id>`, `GET $MS/todo-triggers/workflow/<id>`, `GET $MS/completion-triggers/workflow/<id>/as-target` und, falls das Modul an ist, `GET $MS/webhooks/workflow/<id>`. Chains und `sub-workflow`-Blöcke, die ihn aufrufen, stehen in keiner dieser Listen.
<!-- module: webhooks -->

### Webhooks

- Anlegen: `POST $MS/webhooks` mit `{"workflowId": "<id>", "description": "Push"}`.
  Token und URL stehen nur in dieser Antwort vollständig.
- Aufruf ohne Key: `POST {{BASE_URL}}/api/v1/webhook/trigger/<token>`; die Felder des JSON-Bodys werden Variablen des Laufs.
<!-- /module -->
