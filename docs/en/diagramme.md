<!-- Generated from docs/*-diagrams.md and docs/i18n/*-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# Diagrams

Flow and architecture diagrams as Mermaid fences: GitHub renders them directly in the Markdown version. Each page also exists as HTML (`<name>-diagrams.html`), generated from the Markdown file rather than maintained by hand. The English pages are generated from the German source pages plus one translation table per page (`docs/i18n/<name>-diagrams.en.json`).

| Page | Content | Diagrams |
|---|---|---:|
| [Deployment & Container Lifecycle](deployment-container-diagrams.md) | Compose setup, agent images, ContainerService and resource limits | 2 |
| [Frontend Flow](frontend-flow-diagrams.md) | Routing, state, data flow, WebSockets and cache invalidation | 3 |
| [Logging & Monitoring](logging-monitoring-diagrams.md) | WebSocket logs, ActivityLog, health/info, RateLimit header | 2 |
| [Request Lifecycle & Security](request-lifecycle-diagrams.md) | From the browser to the database: filters, error format, auth, rate limits | 3 |
| [Scheduler & Cron](scheduler-diagrams.md) | Schedules, InputContext, triggering and execution pipeline | 3 |
| [Skills & Capabilities](skills-diagrams.md) | Skill lifecycle, activation per workflow, execution in the skill-capability block | 3 |
| [Workflow Control & States](workflow-control-diagrams.md) | State machines, pause/resume/cancel, approval & error paths | 4 |
| [Workflow Engine](workflow-engine-diagrams.md) | Core logic, execution modes (with/without AI) and control flows | 5 |
| [Workflow Memory](workflow-memory-diagrams.md) | Architecture, data flow & file structure | 7 |

In total, 32 diagrams across 9 pages.

## Generating and checking

```bash
python3 scripts/docs-diagrams.py                # regenerate the HTML versions and the English pages
python3 scripts/docs-diagrams.py --check        # reports drift and translation gaps, exit code 1
python3 scripts/docs-diagrams.py --add-missing  # add missing translation keys with empty values
```
