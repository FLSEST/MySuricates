# Template Intelligence Report — KW 12

**Datum**: 2026-03-16 | **Plattform**: MySuricates v0.3.2

---

## Zusammenfassung

- **Gesamt-Erfolgsrate**: 58.0% (293/505 Runs)
- **Trend**: ↓ -0.83 pp vs. Vorwoche (58.85% → 58.02%)
- **Gate-Status**: 🔴 RED — unter kritischem Schwellenwert von 70%

```
gate_status: RED
gate_category: success-rate
gate_description: Erfolgsrate 58.02% < 70% (kritisch). Regression vs. Vorwoche (-0.83pp). Haupttreiber: neue Batch-Workflows mit hohem Volumen und niedriger Erfolgsrate.
```

---

## Erfolgsraten pro Workflow

| Workflow | Runs | Erfolgsrate | Trend | Kosten (Tokens) |
|---|---|---|---|---|
| Lehrplan-Extraktor | 99 | ⚠️ Unbekannt* | 🆕 Neu | — |
| Lernpaket-Batch-Generator | 96 | ⚠️ Unbekannt* | 🆕 Neu | — |
| Frequenz-basierte Vokabel-Songs | 21 | ~70–80% | → Stabil | Mittel |
| Code Quality | 20 | ~70–80% | → Stabil | Niedrig |
| CS2BIM Code-Qualität & Auto-Fix-PR | 15 | ⚠️ Unbekannt* | 🆕 Neu | — |

*) Per-Workflow-Metriken nicht verfügbar (Block 2 Data-Pipeline defekt).

**Plattform-Metriken (Wochenvergleich)**:

| Metrik | Aktuell | Vorwoche | Veränderung |
|---|---|---|---|
| Workflows | 156 | 143 | +9.1% |
| Runs | 505 | 243 | +107.8% |
| Kosten (30d) | $265.85 | $169.59 | +57% |
| Tokens (30d) | 2.92M | 1.65M | +77% |
| Kosten/erfolgreicher Run | $0.91 | — | Hoch |

---

## Failure-Hotspots

### 1. Block 2 AttributeError — Data-Pipeline defekt (beide Runs)

```
AttributeError: 'list' object has no attribute 'get'
```
**Ursache**: Python-Script erwartet Dict, bekommt Liste. API-Response-Format hat sich geändert.
**Impact**: Verhindert detaillierte Per-Workflow-Analyse in jedem Template-Intelligence-Run.

### 2. Lernpaket-Batch-Generator — 0/36 Pakete generiert

- Shell-Step identifiziert 36 fehlende Themen korrekt (✅ ~80ms)
- AI-Generierungsschritt läuft lange / schlägt fehl (❌)
- 96 Runs, systematisches Fehlermuster, keine Error-Recovery

### 3. Batch-Workflows treiben Fehlerrate

Lehrplan-Extraktor + Lernpaket-Batch-Generator = 195 Runs (38.6% aller Runs).
Vermutlich 50–60% der Gesamtfehler, da Erfolgsrate trotz +107% mehr Runs stagniert.

---

## Empfehlungen

1. **Block 2 AttributeError beheben** — API-Response als Liste behandeln (`isinstance(data, list)`). Ermöglicht vollständiges Monitoring und schließt den Selbstverbesserungs-Loop. ✅ Sofort umsetzbar.

2. **Lernpaket-Batch-Generator stabilisieren** — Incremental Checkpoints, Retry-Block (3x exponential backoff), Batch-Größe auf 5–10 reduzieren. Erwartete Wirkung: Erfolgsrate +5–8pp gesamt, da Workflow 19% aller Runs ausmacht.

3. **Kosten-Monitoring einführen** — Budget-Alert bei >$300/30d, Token-Budget pro Workflow, Kosten-Trend im Dashboard. Verhindert unkontrollierte Ausgaben bei fehlschlagenden Workflows.

---

## Gate-Kriterien

| Kriterium | Schwellenwert | Aktuell | Status |
|---|---|---|---|
| Ø Erfolgsrate | >70% | 58.02% | 🔴 RED |
| Regression | <-5pp/Woche | -0.83 pp | 🟢 OK |
| Data Pipeline | Fehlerfrei | 2x Fehler | 🔴 RED |

**Gesamtbewertung: 🔴 RED** — Sofortiger Handlungsbedarf.

---

## Datenqualität

- ✅ Dashboard-Metriken vollständig
- ❌ Block 2 in beiden Runs fehlgeschlagen → keine Per-Workflow-Metriken
- ⚠️ Trend-Analyse auf 2 Datenpunkten basiert
- ⚠️ Einzelschätzungen basieren auf Volumen-Verteilung und bekannten Fehlermustern

---

*Generiert von: self-care-template-intelligence | MySuricates v0.3.2*
