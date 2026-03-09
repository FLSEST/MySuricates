# Template Intelligence Report — KW 11

**Generiert**: 2026-03-09 | **Platform**: MySuricates v0.3.0

---

## Zusammenfassung

- **Gesamt-Erfolgsrate**: 58.85% (143/243 Runs erfolgreich)
- **Trend**: → (stabil, keine Vorwochendaten zum Vergleich)
- **Gate-Status**: **RED** — Durchschnitt unter 70%, Handlungsbedarf

| Metrik | Wert |
|---|---|
| Runs gesamt (30 Tage) | 243 |
| Erfolgreiche Runs | 143 |
| Fehlgeschlagene Runs | 99 |
| Ø Dauer pro Run | 20.9 Min |
| Kosten (30 Tage) | $169.59 |
| Verschwendete Kosten | ~$69.30 |

---

## Erfolgsraten pro Workflow

| Workflow | Runs | Erfolgsrate | Trend | Kosten (Tokens) |
|---|---|---|---|---|
| Frequenz-basierte Vokabel-Songs | 21 | — ¹ | → | ~14.700 |
| Code Quality | 20 | — ¹ | → | ~13.600 |
| Repo-Ready Gate (e4dd6622) | 8 | — ¹ | → | ~5.440 |
| Repo-Ready Gate (d2a6cec7) | 8 | — ¹ | → | ~5.440 |
| Repo-Ready Gate (2e2830de) | 7 | — ¹ | → | ~4.760 |
| Übrige 138 Workflows | 179 | — ¹ | → | ~121.720 |

> ¹ Per-Workflow-Erfolgsraten konnten nicht aufgelöst werden (Block-2-Datenquelle: `AttributeError: 'list' object has no attribute 'get'`). Gesamt-Erfolgsrate: 58.85%. Token-Schätzung basiert auf Ø 6.800 Tokens/Run.

---

## Failure-Hotspots

### 1. Timeout-Überschreitungen in Agentic Blocks — Häufigkeit: HOCH
`iteration_timeout_seconds` wird auto-aligned (300 → 1260), was auf systematische Fehlkonfiguration hindeutet. Opus-Workflows brauchen mindestens 900s, viele Templates nutzen den Default (300s).

### 2. Fehlende ESLint-Konfiguration — Häufigkeit: MITTEL
ESLint v9+ erfordert `eslint.config.js`, aber Self-Care-Workflows rufen `npx eslint .` ohne passende Config auf. Frontend-Lint-Schritte schlagen konsistent fehl.

### 3. Token-Budget-Erschöpfung & Tracking-Lücken — Häufigkeit: MITTEL
Agentic-Blocks melden fehlende Usage-Daten → Fallback-Schätzung (2 in, 1 out) → Token-Budget wird nicht zuverlässig durchgesetzt. Workflows laufen unkontrolliert weiter oder brechen unerwartet ab.

---

## Empfehlungen

1. **Timeout-Konfiguration standardisieren** — Alle Agentic-Block-Templates mit expliziten Werten ausstatten (Opus: `timeout_seconds: 900`, `iteration_timeout_seconds: 900`; Sonnet: `300`/`600`). Erwartete Wirkung: **+10-15% Erfolgsrate**, Reduktion von Timeout-Failures um ~60%.

2. **Repo-Ready Gate konsolidieren** — 3 Varianten (23 Runs) zu einem parametrisierten Workflow zusammenführen. Erwartete Wirkung: **Bessere Wartbarkeit**, einheitliche Erfolgsmetrik, kein Workflow-Chaos. Aufwand: 1-2h.

3. **ESLint-Config migrieren** — `eslint.config.js` für Frontend erstellen oder Lint-Step auf `npm run lint` umstellen. Erwartete Wirkung: **Self-Care-Workflows laufen sauber**, CI-Qualität steigt. Aufwand: 30min.

---

## Nächste Schritte

- [ ] Per-Workflow-Erfolgsraten aufschlüsseln (Block-2-Script fixen)
- [ ] Wöchentlichen Trend-Vergleich einführen (KW 11 vs. KW 12)
- [ ] Empfehlungen 1-3 umsetzen, Wirkung in KW 12 messen

---

`gate_status: RED`

*Report generiert von MySuricates Template Intelligence — KW 11/2026*
