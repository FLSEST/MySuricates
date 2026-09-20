<!-- Erzeugt aus docs/*-diagrams.md von scripts/docs-diagrams.py — nicht von Hand bearbeiten. -->

# Diagramme

Ablauf- und Architekturdiagramme als Mermaid-Zaeune: GitHub rendert sie direkt in der Markdown-Fassung. Jede Seite gibt es zusaetzlich als HTML (`<name>-diagrams.html`) — die wird aus der Markdown-Datei erzeugt und nicht von Hand gepflegt.

| Seite | Inhalt | Diagramme |
|---|---|---:|
| [Deployment & Container Lifecycle](deployment-container-diagrams.md) | Compose-Setup, Agent-Images, ContainerService und Ressourcenlimits | 2 |
| [Frontend Flow](frontend-flow-diagrams.md) | Routing, State, Datenfluss, WebSockets und Cache-Invalidierung | 3 |
| [Logging & Monitoring](logging-monitoring-diagrams.md) | WebSocket-Logs, ActivityLog, Health/Info, RateLimit Header | 2 |
| [Request Lifecycle & Security](request-lifecycle-diagrams.md) | Vom Browser bis zur Datenbank: Filter, Fehlerformat, Auth, Rate Limits | 3 |
| [Scheduler & Cron](scheduler-diagrams.md) | Schedules, InputContext, Ausloesung und Ausfuehrungspipeline | 3 |
| [Skills & Capabilities](skills-diagrams.md) | Skill-Lebenszyklus, Aktivierung pro Workflow, Ausfuehrung im Skill-Capability Block | 3 |
| [Workflow Control & States](workflow-control-diagrams.md) | Status-Maschinen, Pause/Resume/Cancel, Approval & Fehlerpfade | 4 |
| [Workflow Engine](workflow-engine-diagrams.md) | Kernlogik, Ausführungsmodi (mit/ohne KI) und Steuerflüsse | 5 |
| [Workflow Memory](workflow-memory-diagrams.md) | Architektur, Datenfluss & Dateistruktur | 7 |

Zusammen 32 Diagramme in 9 Seiten.

## Erzeugen und pruefen

```bash
python3 scripts/docs-diagrams.py          # HTML-Fassungen neu erzeugen
python3 scripts/docs-diagrams.py --check  # meldet Drift, Exit-Code 1
```
