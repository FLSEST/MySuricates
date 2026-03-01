# Agent Instructions

Dein Arbeitsverzeichnis ist /workspace (= Projektverzeichnis des Nutzers).

## Grundprinzipien
- Arbeite systematisch: Plane → Ausfuehre → Verifiziere
- Schreibe Ergebnisse in Dateien (nicht nur stdout)
- Erstelle Verzeichnisse mit mkdir -p bevor du Dateien schreibst
- Verifiziere deine Arbeit: Pruefe ob Dateien existieren und korrekt sind
- Ueberschreibe keine bestehenden Projektdateien ohne Aufforderung

## Datei-Ausgabe
- Verwende relative Pfade (CWD ist bereits /workspace)
- Erstelle saubere Verzeichnisstrukturen fuer Multi-File-Outputs
- Verwende beschreibende Dateinamen

## Fehlerbehandlung
- Analysiere Fehlermeldungen sorgfaeltig
- Versuche alternative Ansaetze statt gleiche Befehle zu wiederholen
- Installiere fehlende Abhaengigkeiten oder arbeite ohne sie

## Qualitaet
- Vollstaendige Ergebnisse — keine Platzhalter oder unvollstaendigen Abschnitte
- Folge Konventionen der Zielsprache/Framework
- Validiere Outputs wo moeglich (Linter, Type-Checks, etc.)

## TODO-API

Erkenntnisse, Probleme oder Verbesserungsvorschlaege kannst du als TODOs ablegen:

```bash
curl -X POST http://localhost:9090/api/v1/todos \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Kurze Beschreibung des Problems",
    "description": "Detaillierte Erklaerung (optional)",
    "source": "AI",
    "sourceAgent": "claude-opus-4-6",
    "priority": "MEDIUM",
    "tags": ["bug", "performance"],
    "projectId": "optional-project-uuid"
  }'
```

Felder: `title` (Pflicht), `description`, `status` (OPEN|IN_PROGRESS|DONE), `priority` (LOW|MEDIUM|HIGH), `source` (USER|AI|WORKFLOW), `sourceAgent`, `tags` (Array), `projectId`, `workflowRunId`.
