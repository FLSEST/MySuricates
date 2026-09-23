## Operations-Workflows

- **Monitoring:** `shell` holt Messwerte (externe Dienste, nie die eigene MySuricates-API), `conditional` prüft Schwellen, `gate_status: RED` markiert kritische Werte; regelmäßig per Zeitplan.
- **Logs:** `shell` filtert, `simple` deutet, `agentic` sucht die Ursache.
- **Deployments:** `approval` vor Produktion, `retry` für das Health-Check-Polling.
<!-- module: chains -->
- **Build → Test → Deploy** als Kette.
<!-- /module -->
- **Befunde als TODO:** mit dem Block `todo-create` oder über `todo_item: Titel | HIGH | tag1,tag2 | Beschreibung` in der Ausgabe bei eingeschaltetem AutoTodo – nicht per REST aus einem Shell-Block.

### Zeitpläne

- Liste: `GET $MS/schedules`; was heute noch läuft: `GET $MS/schedules/remaining-today`.
- Cron hat sechs Felder: `0 */15 * * * *` heißt alle 15 Minuten.
- Termine, die in Standby oder einen Neustart fallen, werden je nach Einstellung einmal nachgeholt oder übersprungen.
- Was einen Workflow sonst noch startet (Webhooks, TODO-Trigger, Abschluss eines anderen Workflows), zeigt die Karte „Wird ausgelöst von“ auf seiner Seite.

### Fehler finden

- Gescheiterte Läufe: `GET $MS/executions?status=FAILED&page=0&size=10` (`error` ist ein Objekt).
- Aktivitätslog: `GET $MS/activity-log?level=ERROR&page=0&size=50` (Filter `level` genau diese Stufe, `category`, `workflowId`).

<!-- module: require-sealed-for-unattended -->

### Härtung

Diese Instanz startet über Zeitpläne, Webhooks und TODO-Trigger nur versiegelte Workflows; einen unversiegelten lehnt die API mit 403 `UNATTENDED_REQUIRES_SEAL` ab.
<!-- /module -->
