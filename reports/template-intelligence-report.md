# Template Intelligence Report — KW 18

**Generated**: 2026-05-01 | **Platform**: MySuricates v0.7.1 | **Window**: 30 Tage

## Zusammenfassung

- **Gesamt-Erfolgsrate**: 84.0% (1168/1390 Runs)
- **Trend**: ↑ RED 58% (KW 12) → YELLOW 84% — klare Verbesserung
- **Gate-Status**: YELLOW
- **Kosten**: $486.91 / 30d ($0.35/Run), 5.1M Tokens (85% Output)
- **Limitation**: Per-Workflow-Insights fehlen (Insights-API-Bug, siehe Empfehlung 3)

## Erfolgsraten pro Workflow

| Workflow | Runs | Erfolgsrate | Trend | Kosten (Tokens) |
|---|---|---|---|---|
| MySuricates Test Coverage Audit | 300 (21.6%) | ~84%* | ↑ | hoch (Agentic) |
| Write Test (TODO-driven) | 285 (20.5%) | ~84%* | ↑ | hoch (Agentic) |
| Lernpaket-Batch-Generator | 111 (8.0%) | ~84%* | → | sehr hoch (Content) |
| Lehrplan-Extraktor | 64 (4.6%) | ~84%* | → | mittel (Document) |
| Job Shortlist Generator | 43 (3.1%) | ~84%* | → | niedrig |
| Long Tail (280 Workflows) | 587 (42.2%) | ~84%* | → | breit verteilt |

*\*Per-Workflow-Raten nicht verfügbar — Aggregatwert als Schätzung. Insights-API liefert `AttributeError` (siehe Empfehlung 3).*

## Failure-Hotspots

1. **Test Coverage Audit / Write Test** (585 Runs, 42% Volumen) — Agentic-Blöcke mit Token-Budget-Überschreitung, Maven-Build-Timeouts in Shell-Blöcken. Hohe Ø-Dauer (7:43 min) deutet auf späte, teure Failures hin.
2. **Lehrplan-Extraktor** (64 Runs) — Document-Block (Apache Tika) bricht bei korrupten oder passwortgeschützten PDFs ab.
3. **Lernpaket-Batch-Generator** (111 Runs) — ForEach-Block über große Listen akkumuliert Fehler; Hauptverursacher der 4.34M Output-Tokens.

**Geschätzte Failure-Kosten**: 199 Failures × $0.35 ≈ $70/Monat (untere Schranke — Agentic-Failures teurer als Durchschnitt).

## Empfehlungen

1. **Output-Validation für Top-2-Workflows aktivieren** — Seit `a60e5ce` verfügbar. Auf Test Coverage Audit + Write Test scharfschalten. Erwartung: +2-4 PP Erfolgsrate (84% → 86-88%), Gate YELLOW → GREEN realistisch.
2. **Token-Budget-Caps für Agentic-Blöcke setzen** — `max_total_tokens` hart begrenzen (50k Test / 100k Content). `AgenticTokenTracker` existiert. Erwartung: Kosten-Varianz -30%, ~$20-40/Monat eingespart.
3. **Insights-API-Bug fixen** — `LearningController`/`ExecutionInsightsService` liefert Liste statt Dict → `AttributeError` bei Konsumenten. Ohne Fix bleiben per-Workflow-Failure-Daten unsichtbar, Optimierung bleibt Hypothese.

## Gate-Entscheidung

| Kriterium | Schwelle | Ist | Status |
|---|---|---|---|
| Erfolgsrate ≥ 80% | 80% | 84.0% | PASS |
| Erfolgsrate ≥ 70% (RED-Floor) | 70% | 84.0% | PASS |
| Failure-Anzahl | < 100/30d | 199 | FAIL |
| Pro-Workflow-Daten verfügbar | ja | nein | FAIL |

**gate_status: YELLOW** — Aggregat über 80%, aber 199 Failures materiell und per-Workflow-Daten fehlen für gezielte Optimierung. Trend gegenüber KW 12 (RED/58%) positiv.
