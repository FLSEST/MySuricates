## Forschungs-Workflows

Für Forschungs- und Analyse-Projekte stehen folgende Workflow-Patterns zur Verfügung:

### Daten-Analyse
- `shell`-Block zum Laden und Vorverarbeiten von Daten
- `simple`-Block für Claude-gestützte Analyse und Interpretation
- `foreach`-Block für Batch-Analyse über mehrere Datensätze

### Zusammenfassungen & Reports
- `simple`-Block mit Prompt für Zusammenfassungen von Dokumenten
- Chain: Daten sammeln → Analysieren → Report generieren
- Ergebnisse als Markdown-Dateien in Projektverzeichnis schreiben

### Literatur & Recherche
- `shell`-Block für Web-Scraping oder API-Aufrufe
- `agentic`-Block für iterative Recherche mit Goal "Find relevant papers"
- `while`-Block für Pagination und umfangreiche Suchen

### Daten-Pipelines
- Chain mehrere Workflows: Collect → Clean → Analyze → Visualize
- `conditional`-Block für datenabhängige Verarbeitungspfade
- `retry`-Block für fehleranfällige externe Datenquellen

### Empfohlene Variablen
- `data_source`: Pfad oder URL zur Datenquelle
- `output_dir`: Verzeichnis für generierte Reports
- `date_range`: Zeitraum für zeitabhängige Analysen

## API-Referenz für Forschung

### Run-History (Ergebnisse vergleichen)

```
GET http://localhost:9090/api/v1/executions?workflowId=<id>&page=0&size=10
Headers: X-API-Key: $API_KEY
→ { content: [{ id, status, durationMs, startedAt, metadata }], totalElements }
```

Einzelnes Ergebnis mit Step-Output: `GET /api/v1/executions/{runId}`

### Workflow Memory (Wissen akkumulieren)

```
GET http://localhost:9090/api/v1/workflows/{workflowId}/memory
Headers: X-API-Key: $API_KEY
→ [{ id, title, content, tags, active }]
```

Neues Wissen aus letztem Run extrahieren: `POST /api/v1/workflows/{workflowId}/memory/extract`
