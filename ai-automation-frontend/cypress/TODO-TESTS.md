# Cypress E2E Test Coverage - TODO

Stand: 2026-03-09 | Aktuell: **164 Tests in 14 Specs**

## Abgedeckte Seiten (12 von 14)

| Spec | Tests | Seite |
|------|-------|-------|
| dashboard.cy.ts | 7 | Dashboard |
| navigation.cy.ts | 9 | Sidebar Navigation |
| projects.cy.ts | 8 | Projects List |
| project-detail.cy.ts | 15 | Project Detail |
| workflows.cy.ts | 21 | Workflows List + Detail |
| workflow-execution.cy.ts | 34 | Workflow Execution |
| runs.cy.ts | 10 | Run History |
| todos.cy.ts | 16 | Todos |
| schedules.cy.ts | 6 | Schedules |
| webhooks.cy.ts | 7 | Webhooks |
| skills.cy.ts | 9 | Skills |
| settings.cy.ts | 6 | Settings |
| activity-log.cy.ts | 8 | Activity Log |
| wishes.cy.ts | 8 | Wishes |

## Seiten OHNE Tests (0 von 14)

### WorkflowExecution (`/workflows/:id/run/:runId`) - ABGEDECKT
Verwendet cy.intercept() fuer API-Mocking statt echte Workflow-Ausfuehrung.
- [x] Execution-Header mit Status-Badge
- [x] Step-Cards (completed, failed, running, pending)
- [x] Step-Output-Anzeige (Input, Output, Error)
- [x] Pause/Resume-Buttons
- [x] Cancel-Button
- [x] Run-Command Eingabe
- [ ] Approval-Dialog (fuer Approval-Blocks)
- [ ] WebSocket Live-Updates

## Fehlende Tests auf BESTEHENDEN Seiten

### WorkflowDetail (`/workflows/:id`)
- [ ] Instructions bearbeiten (Monaco Editor)
- [ ] Workflow optimieren (AI)
- [ ] Workflow klonen
- [ ] Block bearbeiten (JSON Config Modal)
- [ ] Block-Reihenfolge per Drag-and-Drop
- [ ] Model-Auswahl (Opus/Sonnet/Haiku)

### Workflows - erweiterte Features
- [ ] Import Workflow (JSON Upload via file input)
- [ ] Als Template speichern

### Dashboard
- [ ] Zusaetzliche Stat-Cards (Total Cost, Planned Today/Week)
- [ ] Todos Donut-Chart
- [ ] Upcoming Schedules Card
- [ ] Failing Workflows Card
- [ ] Top Workflows Liste

### Settings
- [ ] Tailscale Remote Access Konfiguration

## Mobile Responsiveness (Stage 1 - commit 25c479d)
- [ ] Sidebar-Drawer auf Mobile
- [ ] Hamburger-Menu
- [ ] Touch-Targets
- [ ] Master-Detail Collapse auf Workflows
