# Vision Tracker Report — Maerz 2026

## Fortschritts-Zusammenfassung

- **Version**: v0.3.0 (VISION.md zeigt veraltet v0.2.2)
- **Neue Features seit letztem Check**: Wish-Watchdog, AI-Provider-Abstraktion, Wish-Iteration-History, JSON-Repair, Project-Archivierung, Gate-Status-Resolver
- **Roadmap-Fortschritt**: 3 von 4 Phase-2-Items teilweise implementiert, Phase 3 zu ~10%

---

## "Was schon gebaut ist" — Updates

### Bestehende Eintraege

| Feature | Status in VISION.md | Tatsaechlich | Aktion |
|---|---|---|---|
| Version | v0.2.2 | v0.3.0 | Aktualisieren |
| 48+ Templates | "Done" | 47 built-in (Code), 44 in DB | Zahl korrigieren auf "47 Templates" |
| Block-basierte Workflows (11 Block-Typen) | Done | 11 Executors bestaetigt | Keine |
| Docker-Container-Execution | Done | Korrekt | Keine |
| Agentic Blocks | Done | Korrekt | Keine |
| Workflow Chains | Done | Korrekt | Keine |
| Workflow Factory | Done | Korrekt | Keine |
| Wishes | Done | Korrekt | Keine |
| Decompose-Approval Gate | Done | Korrekt | Keine |
| Pre-flight Verification + Auto-Fix | Done | Korrekt | Keine |
| Learning-Feedback in Prompts | Done | Korrekt | Keine |
| Cross-Project Workflow-Erkennung | Done | Korrekt | Keine |
| Staging (Dev + Stable + Test) | Done | Korrekt | Keine |
| Multi-Provider (Claude + Codex) | Done | Korrekt | Keine |

### Fehlende Features (existieren im Code, nicht in Tabelle)

| Feature | Komponenten | Empfehlung |
|---|---|---|
| Workflow Memory System | WorkflowMemoryService, KnowledgeSummarizer | In Tabelle aufnehmen |
| Todo-System mit Gates | TodoService, AutoTodoService, GateStatusResolver | In Tabelle aufnehmen |
| Activity Log | ActivityLogService, ActivityLogController | In Tabelle aufnehmen |
| Human-Input Blocks | HumanInputBlockExecutor | In Tabelle aufnehmen |
| Sub-Workflow Blocks | SubWorkflowBlockExecutor | In Tabelle aufnehmen |
| Approval Blocks | ApprovalBlockExecutor | In Tabelle aufnehmen |
| Schedule/Cron-Support | SchedulerService | In Tabelle aufnehmen |
| Webhook-Trigger | WebhookService | In Tabelle aufnehmen |
| Template-Verification | TemplateVerificationService | In Tabelle aufnehmen |
| i18n (DE/EN) | de.ts, en.ts | In Tabelle aufnehmen |
| PWA-Support | Vite PWA Plugin | In Tabelle aufnehmen |
| Dark Mode | Theme Store | In Tabelle aufnehmen |
| WebSocket Real-Time Updates | useWebSocket | In Tabelle aufnehmen |
| Terminal-WebSocket | /ws/terminal | In Tabelle aufnehmen |
| Datenbank-Backup | DatabaseBackupService | In Tabelle aufnehmen |
| Soft-Delete Pattern | archived_at, deleted_at | In Tabelle aufnehmen |
| Workflow Pool | WorkflowPoolService | In Tabelle aufnehmen |
| Project AI Context | ProjectContextService | In Tabelle aufnehmen |
| Security (API-Key, Rate Limiting) | ApiKeyAuth, RateLimiting | In Tabelle aufnehmen |
| System Tray | Desktop-Integration | In Tabelle aufnehmen |

---

## Veraltete Eintraege

- **Versionsnummer v0.2.2**: Aktuell ist v0.3.0 — 4 Releases veraltet (v0.2.2 → v0.2.3 → v0.2.4 → v0.3.0)
- **"48+ Templates"**: Weder 48 noch mehr. BuiltInTemplates.java definiert 47, DB hat 44 (3 moeglicherweise geloescht). Korrektur auf "47 Templates"
- **"Was schon gebaut ist"-Tabelle**: Listet 13 Features, tatsaechlich existieren 30+ signifikante Features

---

## Roadmap-Empfehlungen

### Phase 2 — Status und naechste Schritte

| Item | Fortschritt | Naechster Schritt |
|---|---|---|
| Kosten-Schaetzung vor Ausfuehrung | ~20% (Token-Tracking existiert via AgenticTokenTracker) | Vorab-Schaetzung basierend auf historischen Daten implementieren |
| Modell-Auswahl pro Wish | ~60% (Modell pro Workflow, Provider pro Workflow) | Automatische Modell-Empfehlung basierend auf Wish-Typ |
| Auto-Refine ohne User-Intervention | ~70% (WishWatchdogService, Wish-Iteration) | Zielqualitaets-Kriterien definierbar machen |
| Iterations-Historie mit Delta-Tracking | ~90% (V33 Migration implementiert) | UI fuer Delta-Visualisierung |

### Phase 3 — Empfohlene Prioritaeten

1. **Crystallize-Wizard** (Basis vorhanden: ConversionService) — UI-Wizard bauen der erfolgreiche Wishes in Templates konvertiert
2. **Wish-Analytics Dashboard** — Daten existieren bereits (Runs, Token-Usage), nur Visualisierung fehlt
3. **Template-Discovery** — Problem-basierte Suche ueber Template-Beschreibungen, ggf. mit Embedding-Suche

### Generelle Empfehlungen

1. VISION.md auf v0.3.0 aktualisieren und "Was schon gebaut ist" erweitern
2. Roadmap Phase 2 Items als "teilweise implementiert" markieren
3. Fehlende Features (Todo, Activity Log, Memory etc.) in die Vision aufnehmen — sie sind wichtige Bausteine
4. Template-Zahl pruefen: 3 Templates fehlen in DB vs. Code — moeglicherweise Seeder-Problem

---

## Quantitative Uebersicht

| Kennzahl | VISION.md | Tatsaechlich | Delta |
|---|---|---|---|
| Version | v0.2.2 | v0.3.0 | 4 Releases hinterher |
| Block-Typen | 11 | 11 | OK |
| Templates | "48+" | 47 (Code) / 44 (DB) | -1 bis -4 |
| Gelistete Features | 13 | 30+ | 17+ fehlen |
| Skills (built-in) | - | 16 | Nicht erwaehnt |
| Frontend-Seiten | - | 17 | Nicht erwaehnt |
| Backend-Tests | - | 81 | Nicht erwaehnt |

---

## Gate-Status

- **Status: YELLOW**
- **Begruendung**:
  - Versionsnummer ist 4 Releases veraltet (v0.2.2 statt v0.3.0)
  - Template-Zahl stimmt nicht ("48+" vs. 47)
  - 20+ signifikante Features fehlen in der "Was schon gebaut ist"-Tabelle
  - Roadmap Phase 2 ist zu ~50% umgesetzt, aber VISION.md reflektiert das nicht
  - Inhaltlich stimmt die Vision noch (Zwei-Saeulen-Modell, Self-Improvement-Loop), aber die Dokumentation hinkt der Realitaet deutlich hinterher
  - **Kein RED**, weil: Keine strategische Fehlausrichtung — alle implementierten Features passen zur Vision
