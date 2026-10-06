<!-- Erzeugt aus docs/*-diagrams.md von scripts/docs-diagrams.py — nicht von Hand bearbeiten. -->

# Diagramme

Ablauf- und Architekturdiagramme als Mermaid-Zäune: GitHub rendert sie direkt in der Markdown-Fassung. Jede Seite gibt es zusätzlich als HTML (`<name>-diagrams.html`) — die wird aus der Markdown-Datei erzeugt und nicht von Hand gepflegt. Die englische Fassung unter `docs/en/` entsteht aus derselben Quelle und je Seite einer Übersetzungstabelle (`docs/i18n/<name>-diagrams.en.json`).

| Seite | Inhalt | Diagramme |
|---|---|---:|
| [Workflow Control & States](workflow-control-diagrams.md) | Status-Maschinen, Pause/Resume/Cancel, Approval & Fehlerpfade | 4 |
| [Workflow Engine](workflow-engine-diagrams.md) | Kernlogik, Ausführungsmodi (mit/ohne KI) und Steuerflüsse | 5 |
| [Workflow Memory](workflow-memory-diagrams.md) | Architektur, Datenfluss & Dateistruktur | 7 |

Zusammen 16 Diagramme in 3 Seiten.

## Erzeugen und prüfen

```bash
python3 scripts/docs-diagrams.py                # HTML-Fassungen und englische Seiten neu erzeugen
python3 scripts/docs-diagrams.py --check        # meldet Drift und Übersetzungslücken, Exit-Code 1
python3 scripts/docs-diagrams.py --add-missing  # fehlende Übersetzungsschlüssel leer eintragen
```
