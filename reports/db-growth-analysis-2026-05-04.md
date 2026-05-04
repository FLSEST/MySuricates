# DB-Wachstum Analyse — 2026-05-04

TODO: `c5e766f4-0839-498b-9079-a3c3b8a16192`

## Aktueller Stand

- DB-Datei: **140 MB** (`automation.db`)
- WAL: 4 MB, SHM: 32 KB
- Audit hatte 31 → 114 MB notiert; aktuell **140 MB** — Wachstum hält an.

## Top-Tabellen (aus REST-API)

| Tabelle | Zeilen | Auto-Cleanup? |
|---|---|---|
| `workflow_runs` (+ steps) | **1497** | nur INTERRUPTED nach 24h/30d |
| `activity_log` | 1430 | ✅ tiered: 30d/7d/24h |
| `todos` | 1198 | ❌ keine |
| `wishes` | 8 | ❌ keine |

Daten direkt aus DB lesen ist per CLAUDE.md verboten — Größenprofile pro Tabelle daher nicht messbar. Aber mit ~1500 WorkflowRuns × jeweils mehreren Steps × TEXT-Outputs (Beobachtung: ~800–4200 Bytes pro Step-Output) ist offensichtlich, dass **die Run-History den Großteil belegt**.

## Befunde

### F1 — `RunCleanupService` löscht nur INTERRUPTED-Runs

`service/workflow/RunCleanupService.java`:

```java
// 1. INTERRUPTED runs with no completed steps — delete after 24h
// 2. INTERRUPTED runs with completed steps — delete after 30d
```

**Lücke:** `COMPLETED`, `FAILED`, `CANCELLED`, `COMPLETED_WITH_ERRORS` werden nie gelöscht. Sie wachsen unbegrenzt.

### F2 — `ActivityLogService.cleanupLogs()` ✅ vorbildlich

Tiered:
- alles >30d → weg
- DEBUG+INFO >7d → weg
- DEBUG >24h → weg

Resultat: nur 1430 Zeilen — gut beherrscht.

### F3 — `incrementalVacuum` daily ✓, kein full VACUUM

`DatabaseBackupService.scheduledIncrementalVacuum()` läuft täglich 03:00, reclaimed bis zu 1000 Pages (~4 MB). Aber:
- `incremental_vacuum` funktioniert nur, wenn `auto_vacuum=INCREMENTAL` **bei DB-Erstellung** gesetzt war. Ob das hier der Fall ist, müsste über App-Logs geprüft werden.
- Reclaimed nur freie Pages **nach Deletes**. Wenn nichts gelöscht wurde, gibt es nichts zu reclaimen → der Aufruf bringt nichts gegen das Wachstum.
- Ein `fullVacuum()`-Endpoint existiert (`DatabaseBackupService.fullVacuum()`), wird aber nirgends regelmäßig aufgerufen.

### F4 — Step-Outputs unbegrenzt

`StepResult.output` und `error` sind `@Column(columnDefinition = "text")` ohne Längenbegrenzung. Bei Shell-Blocks mit großem Output (Test-Suite-Logs, Build-Outputs) können einzelne Steps mehrere MB schreiben. Akkumuliert über 1500 Runs → wesentlicher Wachstumstreiber.

## Empfehlungen

### R1 — Run-Retention für nicht-INTERRUPTED-Status (höchste Wirkung)

`RunCleanupService.cleanupInterruptedRuns()` erweitern um:
- COMPLETED/CANCELLED-Runs nach **N Tagen** löschen (Default 90, settable)
- FAILED/COMPLETED_WITH_ERRORS nach längerem Fenster (Default 180, settable) — diagnostischer Wert
- Cap auf **maximale Run-History pro Workflow** (Default 100 letzte Runs)

Konfigurierbar über `ExecutionSettingsService` (siehe Memory: „Timeouts gehören in die Settings"). Bounds + Frontend-Card + i18n.

**Nicht eigenmächtig umsetzen** — Retention-Policy ist eine Produkt-Entscheidung. User-Approval einholen, dann Settings-UI dazu bauen.

### R2 — Step-Output-Truncation (mittlere Wirkung, einfach)

In `WorkflowExecutor` / `StepResultMapper` Step-Outputs > N Zeichen (z. B. 50 KB) bei Persistence truncaten + Hinweis "[output truncated]" anhängen. Für Debugging reicht ein Auszug; volle Outputs sind sehr selten nochmal nötig.

### R3 — Full VACUUM monatlich (kleine Wirkung, defensive)

`DatabaseBackupService` um:
```java
@Scheduled(cron = "0 0 4 1 * *")  // 1. des Monats, 04:00
public void scheduledFullVacuum() { fullVacuum(); }
```

Voraussetzung: alle Workflow-Runs gestoppt (kann mit `runRepository.countByStatus(RUNNING) == 0` geprüft werden). Erst nach R1 sinnvoll, weil davor nichts zu reclaimen ist.

### R4 — Auto-Vacuum-Pragma verifizieren (kleine Wirkung)

Beim Backend-Start einmal `PRAGMA auto_vacuum;` lesen und in den Logs ausgeben. Wenn `0` (NONE) → die incremental_vacuum-Aufrufe sind wirkungslos. Migration zu INCREMENTAL erfordert allerdings ein `VACUUM` nach dem Pragma-Set → einmaligen Migration-Schritt einplanen.

## Konkreter nächster Schritt

User-Approval für R1 (Run-Retention-Policy) einholen. R2 ist nieder-Risiko und könnte direkt umgesetzt werden, hat aber semantische Auswirkungen (Step-Outputs für Debugging gekürzt) — auch besser mit Approval.

R4 lässt sich risikoarm umsetzen: nur ein Logging-Hinweis beim Start, keine Verhaltensänderung — kann direkt geliefert werden.
