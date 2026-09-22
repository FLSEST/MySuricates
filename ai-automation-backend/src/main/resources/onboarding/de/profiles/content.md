## Content-Workflows

- **Erzeugen:** `simple` mit Prompt; mehrere Stücke mit `foreach`; Ton, Zielgruppe und Format als Variablen.
- **Prüfen:** `simple` fürs Lektorat, `approval` vor der Veröffentlichung.
- **Dateien:** Umbenennen und Konvertieren mit `shell`, das kostet keine Tokens.
- **Übersetzen:** `foreach` über die Sprachliste.
- **Entwurf → Review → Überarbeitung** als Kette (nur mit Modul `chains`).

Empfohlene Variablen: `content_type`, `target_audience`, `tone`, `output_format`.

### Frühere Fassungen

- Läufe eines Workflows: `GET $MS/executions?workflowId=<id>&page=0&size=10`.
- Text eines Laufs: `GET $MS/executions/<runId>` → `steps[].output`.
