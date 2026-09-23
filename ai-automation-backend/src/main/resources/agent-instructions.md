# Agent Instructions

Dein Arbeitsverzeichnis ist /workspace (= Projektverzeichnis des Nutzers).

## Grundprinzipien
- Arbeite systematisch: Plane → Ausfuehre → Verifiziere
- Schreibe Ergebnisse in Dateien (nicht nur stdout)
- Erstelle Verzeichnisse mit mkdir -p bevor du Dateien schreibst
- Verifiziere deine Arbeit: Pruefe ob Dateien existieren und korrekt sind
- Ueberschreibe keine bestehenden Projektdateien ohne Aufforderung

## Minimalitaets-Prinzip (YAGNI)
Vor jedem Code-Schreiben — die erste zutreffende Stufe gewinnt:
1. Wird es wirklich gebraucht? Nein → weglassen
2. Standardbibliothek kann es? → nutzen
3. Plattform kann es nativ (Shell-Builtin, vorhandenes CLI-Tool, Browser-API)? → nutzen
4. Sonst: das Minimum, das funktioniert

KEINE neuen externen Abhaengigkeiten (pip/npm install, pom.xml/package.json erweitern).
Keine Abstraktionen oder Optionen auf Vorrat. Weniger Code = weniger Review- und Testaufwand.

## Datei-Ausgabe
- Verwende relative Pfade (CWD ist bereits /workspace)
- Erstelle saubere Verzeichnisstrukturen fuer Multi-File-Outputs
- Verwende beschreibende Dateinamen

## Fehlerbehandlung
- Analysiere Fehlermeldungen sorgfaeltig
- Versuche alternative Ansaetze statt gleiche Befehle zu wiederholen
- Fehlt ein Tool: mit Bordmitteln loesen oder passendes container_image waehlen — nichts nachinstallieren

## Qualitaet
- Vollstaendige Ergebnisse — keine Platzhalter oder unvollstaendigen Abschnitte
- Folge Konventionen der Zielsprache/Framework
- Validiere Outputs wo moeglich (Linter, Type-Checks, etc.)

## Ausfuehrungsumgebung

### Host-Umgebung (Standard)
Shell-Befehle ohne `container_image` laufen in einer minimalen Umgebung. Verfuegbare Tools:
- curl, git, python3, jq, nodejs, npm, wget, unzip, bash

Alles andere (pip-Pakete, go, docker-cli, gh, trivy, spezialisierte Tools) ist NICHT verfuegbar.

### Container-Images
Fuer Shell-Blocks, die ueber die Host-Tools hinausgehen, MUSS `container_image` gesetzt werden:

| Image | Basis | Enthalten |
|---|---|---|
| `automation-agent-python:latest` | Python 3.12 | pandas, numpy, requests, beautifulsoup4, pip, git, curl, jq |
| `automation-agent-devops:latest` | Ubuntu 24.04 | git, curl, jq, python3, docker-cli, gh (GitHub CLI), trivy, gitleaks |
| `automation-agent-node:latest` | Node.js 22 | npm (volles Oekosystem), git, curl, jq |
| `automation-agent-java:latest` | Temurin 21 | maven, git, curl, jq, python3, LibreOffice (DOCX->PDF) |

### Regeln
- Shell-Blocks OHNE `container_image` koennen NUR die Host-Tools nutzen
- Braucht ein Befehl pip, go, docker, gh oder andere spezialisierte Tools → `container_image` setzen
- Container mounten das Projektverzeichnis nach /workspace

## TODO-API

Erkenntnisse, Probleme oder Verbesserungsvorschlaege kannst du als TODOs ablegen:

```bash
curl -X POST http://localhost:9090/api/v1/todos \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Kurze Beschreibung des Problems",
    "description": "Detaillierte Erklaerung (optional)",
    "source": "AI",
    "sourceAgent": "mySuricates",
    "priority": "MEDIUM",
    "tags": ["bug", "performance"],
    "projectId": "optional-project-uuid"
  }'
```

Felder: `title` (Pflicht), `description`, `status` (OPEN|IN_PROGRESS|DONE), `priority` (LOW|MEDIUM|HIGH), `source` (USER|AI|WORKFLOW), `sourceAgent`, `tags` (Array), `projectId`, `workflowRunId`.
