## Forschungs-Workflows

- **Daten:** `shell` lädt und bereitet auf (kostet keine Tokens), `simple` deutet, `foreach` geht über mehrere Quellen.
- **Recherche:** `agentic` mit Ziel, begrenzt durch sein Budget (Standard: 10 Iterationen, 200 000 Tokens); `while` für Paginierung.
<!-- module: chains -->
- **Pipelines:** Sammeln → Bereinigen → Auswerten → Bericht als Kette.
<!-- /module -->
- **Dateien schreiben:** Mit Anbieter `CODEX` laufen Blöcke schreibgeschützt, Schreibversuche scheitern still; für Berichte als Datei `CLAUDE` oder `PI` nehmen.
- **Vorigen Bericht nutzen:** `{{ previous_report }}` gibt einem Lauf die längste Ausgabe des letzten erfolgreichen Laufs.

### Ergebnisse vergleichen

- Läufe eines Workflows: `GET $MS/executions?workflowId=<id>&page=0&size=10`.
- Ausgabe eines Laufs: `GET $MS/executions/<runId>` → `steps[].output`.

<!-- module: memory -->
### Workflow-Gedächtnis

- Lesen: `GET $MS/workflows/<id>/memory`.
- Aus Läufen lernen: `POST $MS/workflows/<id>/memory/extract` mit `{}` (die letzten 10 Läufe) oder `{"runIds": ["…"]}`.
- Das Ergebnis ist inaktiv, bis `PUT $MS/workflows/<id>/memory/<memoryId>/activate`.
<!-- /module -->
