## Content-Workflows

Für Content-Erstellung und -Management stehen folgende Workflow-Patterns zur Verfügung:

### Content-Generierung
- `simple`-Block für Texterstellung mit spezifischen Prompts
- `foreach`-Block für Batch-Generierung (z.B. mehrere Blog-Posts)
- Template-Variablen für Ton, Zielgruppe und Format

### Review & Qualitätssicherung
- `simple`-Block für Lektorat und Stilprüfung
- `approval`-Block für manuelles Review vor Veröffentlichung
- Chain: Entwurf → Review → Überarbeitung → Freigabe

### Batch-Verarbeitung
- `foreach`-Block für Verarbeitung mehrerer Dateien
- `shell`-Block für Dateioperationen (Umbenennen, Konvertieren)
- `conditional`-Block für formatabhängige Verarbeitung

### Übersetzung & Lokalisierung
- `foreach`-Block über Sprachliste
- `simple`-Block mit Übersetzungs-Prompt
- Parallele Verarbeitung durch separate Workflow-Runs

### Empfohlene Variablen
- `content_type`: Art des Contents (blog, docs, social)
- `target_audience`: Zielgruppe
- `tone`: Tonalität (formal, casual, technical)
- `output_format`: Ausgabeformat (markdown, html, plain)
