# Audit-Follow-ups Status — 2026-05-05

Sammelreport für die fünf [Follow-up]-TODOs aus den Self-Care-Workflows.

---

## 1. self-care-codebase-health (`b69f6231`) — YELLOW

Quelle: `.output/claude-md-audit.md`

### Empfehlungen aus dem Audit

| # | Aktion | Status |
|---|---|---|
| 1 | CLAUDE.md: `document` zur Block-Typ-Liste ergänzen | ✅ erledigt (dieser Commit) |
| 2 | WORKFLOW-GUIDE.md: named outputs, output-validation, contextSchema dokumentieren | 🔜 separates TODO empfohlen — größere Doku-Arbeit |
| 3 | MEMORY.md (Flyway-Notiz): „bis V62" → V63 | ✅ erledigt (dieser Commit) |
| 4 | TODO ContainerService read-only auth (MEDIUM, security/container-execution) | ⚠️ separates TODO einplanen |

→ Kann nach #2 und #4 als TODOs angelegt werden, dann TODO `b69f6231` schließbar.

---

## 2. self-care-vision-tracker (`7688e84a`) — YELLOW

Quelle: `.output/vision-check-2026-05-01.md`

VISION.md ist um 2 Minor-Releases veraltet (v0.6.4 vs. Code v0.7.1). Zahlen-Diff: 12→14 Block-Typen, 53→63 Templates, 16→19+ Skills. 17+ unerwähnte Features.

### Empfehlung

VISION.md gezielt **entrümpeln**, statt aktualisieren: Inventarzahlen (Block-Typen, Templates, Skills) entfernen — sie rotten bei jedem Commit. Stattdessen Verweis auf Live-Endpoints / Build-time-Generierung. Die qualitative Vision (zwei Säulen, Wishes-/Factory-/Learning-Loop) bleibt korrekt und stabil.

→ **Nicht eigenmächtig umsetzen.** VISION.md ist Produkt-Doku — Format-Entscheidung beim User. Dieses Follow-up bleibt offen.

Memory-Regel passt: „Keine statischen Inventur-Markdowns — Zustand, der live abrufbar ist, nicht in md einfrieren. Rottet sofort."

---

## 3. MySuricates Architecture Review (`e365bc25`) — GREEN

> 7.5/10 GREEN — 105 Commits, 0 neue Findings.
> Top-Empfehlung: AssistantFetchService nach Domäne aufteilen (935 LOC).

### Status

Kein akuter Handlungsbedarf. Der einzige genannte Schritt (Split AssistantFetchService) ist ein größerer Refactor — zählt zu den unter Punkt 5 „God Classes" geführten Aufgaben.

→ TODO offen lassen, wird durch das God-Class-Refactor adressiert (siehe #5).

---

## 4. MySuricates Security Audit (`2b8edd92`) — GREEN ✅ schließbar

Quelle: TODO-Beschreibung

### Findings im Audit

| Finding | Status |
|---|---|
| Container-Mount Path-Traversal (NEU, MEDIUM) | ✅ **gefixt heute** — Commit `dd3ebea` |
| Vite dev-only Vulnerabilities (NEU, LOW) | ⚠️ `npm audit fix` verfügbar — separat einplanen |
| Shell-Injection (BEKANNT) | bleibt offen — größere Architektur-Frage |
| X-Forwarded-For (BEKANNT) | bleibt offen |

### Empfehlung

Dieses Follow-up-TODO **schließen** — der heute neu hinzugekommene Hauptbefund ist behoben. Die zwei bekannten Findings haben bereits eigene TODOs/Tracking. Vite-`npm audit` als kleines separates TODO einplanen.

Wird im Anschluss an diesen Commit auf DONE gesetzt.

---

## 5. MySuricates Health Check (`eaab7583`) — YELLOW

> 6.7/10 YELLOW. Schlechteste Dimension: Komplexität (4/10) mit 23 God Classes.
> Wichtigste Empfehlung: God-Class-Wachstum stoppen.

### Status

23 God Classes (Klassen >300 LOC) — Auflösung ist ein **strategisches Refactor-Programm**, kein Quick-Fix. Liste der Hotspots laut Architecture-Review:
- `AssistantFetchService` (935 LOC)
- `WorkflowExecutor` (880 LOC)
- weitere folgen aus dem Health-Report

### Empfehlung

Dieses Follow-up bleibt offen — adressierbar nur durch koordinierte Refactor-Initiative über mehrere Sprints. Vorschlag:
1. **Top-3 God Classes** als eigene HIGH-Prio TODOs anlegen, je mit konkretem Split-Plan.
2. **Backstop**: Pre-Commit-Hook oder CI-Check, der neue Java-Dateien >300 LOC blockt.
3. Bestehende Health-Score-Erfassung weiterlaufen lassen (Trend-Tracking).

→ **Nicht eigenmächtig starten.** Größeres Programm, User-Approval erforderlich.

---

## Zusammenfassung — was passiert in diesem Commit

- ✅ **MEMORY.md**: V59 → V63 (codebase-health)
- ✅ **CLAUDE.md**: `document`-Block ergänzt (codebase-health)
- ✅ **TODO `2b8edd92` schließen** (security): Hauptfinding ist heute gefixt (Commit `dd3ebea`)
- ⚠️ **TODO `b69f6231` codebase-health bleibt offen**, bis WORKFLOW-GUIDE.md-Updates und ContainerService-TODO angelegt sind
- ⚠️ **TODO `7688e84a` vision-tracker bleibt offen** — User-Entscheidung über VISION.md-Format nötig
- ⚠️ **TODO `e365bc25` architecture und `eaab7583` health bleiben offen** — Refactor-Programm
