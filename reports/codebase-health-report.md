# Codebase Health Report — MySuricates v0.3.0

**Datum:** 2026-03-09
**Basis:** CLAUDE.md vs. tatsächlicher Code (main branch)
**Methode:** Systematischer 5-Dimensionen-Audit

---

## 1. CLAUDE.md-Aktualisierungen

### 1.1 Controller-Liste (Zeile 72)

**Ist:** 16 Controller aufgelistet
**Soll:** 19 Controller existieren tatsächlich

```
- Zeile 72: "controller/ REST controllers (Workflow, WorkflowTemplate, Skill, ...)"
+ Zeile 72: Ergänzen um: Wish, WorkflowFactory, WorkflowPool
```

### 1.2 Pages-Liste (Zeile 102)

**Ist:** 15 Pages aufgelistet
**Soll:** 17 Pages existieren tatsächlich

```
- Zeile 102: "pages/ Route pages (Dashboard, Projects, ...ActivityLog)"
+ Zeile 102: Ergänzen um: Wishes, WorkflowFactory
```

### 1.3 Hooks-Liste (Zeile 104)

**Ist:** 19 Hooks aufgelistet
**Soll:** 22 Hooks existieren tatsächlich

```
- Zeile 104: "hooks/ TanStack Query hooks (useWorkflows, ...useWebSocket)"
+ Zeile 104: Ergänzen um: useWishes, useWorkflowFactory, usePool
```

### 1.4 Flyway-Migrations-Zählung (Zeile 95)

**Ist:** `V1-V24`
**Soll:** `V1-V35`

```
- Zeile 95: "db/migration/ Flyway migrations (V1-V24, SQLite)"
+ Zeile 95: "db/migration/ Flyway migrations (V1-V35, SQLite)"
```

### 1.5 Fehlende Feature-Abschnitte

CLAUDE.md hat keine Dokumentation für diese seit v0.2.4/v0.3.0 eingeführten Features:

| Feature | Eingeführt | Betroffene Artefakte |
|---|---|---|
| **Wish-System** | v0.2.4 | WishController, WishService, Wishes-Page, useWishes-Hook |
| **Workflow Factory** | v0.3.0 | WorkflowFactoryController, WorkflowFactoryService, WorkflowFactory-Page, useWorkflowFactory-Hook |
| **Workflow Pool** | v0.3.0 | WorkflowPoolController, usePool-Hook |
| **AI Provider Abstraction** | v0.3.0 | Claude + Codex Provider, per-Workflow Provider-Override |

**Empfehlung:** Für jedes Feature einen eigenen Abschnitt analog zu bestehenden (z.B. "## Workflow Chains", "## Workflow Memory") anlegen.

---

## 2. Konventions-Verletzungen

**Keine kritischen Verletzungen gefunden.** Die geänderten Dateien folgen den CLAUDE.md-Konventionen:

- Release-Commits: `Release vX.Y.Z`-Format ✓
- API-Prefix: `/api/v1` ✓
- Flyway-Naming: `V{N}__description.sql` ✓
- i18n: de/en parallel gepflegt ✓
- MapStruct-Mapper: Entity ↔ DTO ✓

**Hinweis:** Da Wish, Factory und Pool in CLAUDE.md nicht dokumentiert sind, können deren architekturelle Konventionen nicht gegen eine Referenz geprüft werden. Die fehlende Dokumentation ist selbst das Problem.

---

## 3. Cleanup-Empfehlungen (priorisiert)

| Priorität | Empfehlung | Aufwand | Begründung |
|---|---|---|---|
| **HIGH** | CLAUDE.md: Controller/Pages/Hooks-Listen aktualisieren (Zeilen 72, 102, 104) | 10 min | Direkte Falschinformation für AI-Agenten und Entwickler |
| **HIGH** | CLAUDE.md: Migrations-Zählung V24 → V35 korrigieren (Zeile 95) | 1 min | Faktisch falsch, führt zu Verwirrung bei Schema-Arbeiten |
| **HIGH** | CLAUDE.md: Feature-Abschnitte für Wish, Factory, Pool, AI-Provider ergänzen | 30-60 min | Drei Feature-Bereiche komplett undokumentiert — AI-Agenten wissen nicht, dass sie existieren |
| **MEDIUM** | Entity-Liste (Zeile 73) konkretisieren statt "..." | 10 min | "..." kaschiert ~8 undokumentierte Entities |
| **LOW** | `ContainerService.java:110` TODO evaluieren (Credential-Mounting read-only) | 1-2h | Sicherheitsverbesserung, nicht blockierend |

---

## 4. TODO/FIXME-Bewertung

### Kritisch
Keine kritischen TODOs im Code gefunden.

### Nice-to-have
| Datei | Zeile | Inhalt | Bewertung |
|---|---|---|---|
| `ContainerService.java` | 110 | `// TODO: Consider copying only auth credentials read-only` | Sicherheits-Hardening für Container-Credential-Management. Funktional korrekt, aber Least-Privilege-Prinzip nicht voll umgesetzt. |

### Falsche Positive (kein Tech Debt)
- Alle "TODO"-Treffer in `BuiltInTemplates.java` sind Template-Content-Strings (der "TODO-Creator"-Workflow)
- Referenzen auf `AutoTodoService`, `TodoService` etc. sind funktionaler Code

---

## Gate-Status

```
┌─────────────────────────────────────┐
│  Gate-Status:  🟡 YELLOW            │
└─────────────────────────────────────┘
```

### Begründung

**Für YELLOW (nicht RED):**
- Drei Feature-Bereiche (Wish, Factory, Pool) + AI-Provider-Abstraction sind in CLAUDE.md komplett undokumentiert
- Migrations-Zählung ist um 11 Versionen veraltet (V24 → V35)
- Controller/Pages/Hooks-Listen sind unvollständig und liefern falsche Zahlen

**Gegen RED:**
- Keine falschen oder veralteten Einträge (alles Dokumentierte stimmt noch)
- Keine kritischen Konventions-Verletzungen
- Nur 1 unkritischer TODO im gesamten Codebase
- Grundlegende Architektur-Konventionen werden eingehalten

### Nächste Schritte für GREEN

1. **Zeilen 72, 95, 102, 104 aktualisieren** — Zahlen und Listen korrigieren
2. **Feature-Abschnitte ergänzen** — Wish-System, Workflow Factory, Workflow Pool, AI Provider Abstraction
3. **Entity-Liste konkretisieren** — "..." durch vollständige Aufzählung ersetzen

Nach diesen Änderungen wäre der Gate-Status **GREEN**.
