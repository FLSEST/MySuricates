# Plan: AutoPilot-Workflows fuer die Entwicklungsumgebung

## Analyse-Ergebnis

### Projektlandschaft (18 Projekte, 3 Directories)

| Projekt | Stack | LOC (ca.) | Tests | Aktiv |
|---------|-------|-----------|-------|-------|
| **autotrigger** (AutoPilot) | Java 21 / Spring Boot + React 18 / TS | 25.600 | 336 Unit + 8 Cypress | Sehr aktiv |
| **treasure_hunt_app** | Flutter/Dart | ~8.000 | 11 Unit + 5 Integration | Aktiv |
| **Schweizerbahn_hunts** | Flutter/Dart (Variante) | ~8.000 | 11 Unit + 5 Integration | Aktiv |
| **Test_Deutsche_Geschichte** | Flutter/Dart (Variante) | ~8.000 | 11 Unit + 5 Integration | Aktiv |
| **treasure_hunt_backend** | Java 21 / Spring Boot + PostgreSQL | ~2.000 | 6 Unit | Aktiv |
| **treasure_hunt_level** | Python + Shell + JSON | ~5.000 | - | Aktiv |
| **treasure_hunt_web** | Next.js 16 / React 19 / Tailwind 4 | ~3.000 | - | Aktiv |
| **treasure_hunt_sync** | Dart (Flutter Package) | ~500 | - | Stabil |
| **treasure_hunt_scripts** | Shell/Python | ~500 | - | Gelegentlich |
| **dev-dashboard** | Docker + PyQt6 | ~2.000 | 7 Unit | Aktiv |
| **expense-tracker-ai** | Next.js / React | ~3.000 | 5 Unit | Stabil |
| **Musik** | Python/Shell (Suno-API Lyrics) | ~1.000 | - | Gelegentlich |
| **audio_input** | Bash/Python (Whisper) | ~500 | - | Stabil |
| **Mathlock** / **Mathlock_full** | Kotlin/Android | ~2.000 | - | Stabil |
| **LearnLock** | Flutter/Dart | ~3.000 | - | Stabil |
| **LeviPapaEins** | Flutter/Dart | ~5.000 | - | Stabil |
| **AnkiVocabScanner** | Java (Android) | ~2.000 | - | Stabil |
| **remote_access** | Shell/Config | ~200 | - | Stabil |

### Erkannte Probleme (uebergreifend)

1. **Kein CI/CD** in keinem Projekt — kein GitHub Actions, kein automatisches Testing
2. **Dependency-Risiken**: autotrigger hat Spring Boot 3.2.2 (veraltet), Vite 5 (6 aktuell), lucide-react sehr alt
3. **Test-Luecken**: autotrigger fehlen 3 Block-Executor-Tests + alle Frontend-Unit-Tests; treasure_hunt_web hat 0 Tests
4. **Keine Security-Scans**: Weder `npm audit` noch OWASP dependency-check konfiguriert
5. **Code-Duplikation**: 5 Block-Executors im autotrigger mit identischem Pattern
6. **Kein Git** fuer audio_input, treasure_hunt_backend (nur tar.gz)
7. **15-Sprachen-Lokalisierung** in treasure_hunt_app ohne automatische Vollstaendigkeitspruefung
8. **Hunt-Content** (treasure_hunt_level) hat Python-Generierungsskripte ohne Validierung der JSON-Manifeste

---

## Empfohlene Workflows (12 Workflows in 4 Kategorien)

### Kategorie A: Qualitaetssicherung (taegliche/woechentliche Cron-Schedules)

#### Workflow 1: "AutoPilot Backend Quality Gate"
**Projekt**: autotrigger
**Schedule**: CRON `0 0 6 * * *` (taeglich 6:00)
**Bloecke**:
1. `simple` — `cd ai-automation-backend && mvn test -q` (Backend-Tests)
2. `conditional` — Wenn Exit-Code != 0: Fehler-Report erstellen
3. `simple` — `mvn dependency:tree -DoutputType=dot` (Abhaengigkeitsbaum pruefen)
4. `simple` — `cd ai-automation-frontend && npm run build` (Frontend-Build-Check)
5. `simple` — `cd ai-automation-frontend && npm run lint` (Lint-Check)

**Zweck**: Fruehe Erkennung von Build-Bruechen und Regressions

#### Workflow 2: "Flutter App Test Suite"
**Projekt**: treasure_hunt_app (auch fuer Schweizerbahn_hunts, Test_Deutsche_Geschichte)
**Schedule**: CRON `0 0 7 * * 1` (Montags 7:00)
**Bloecke**:
1. `forEach` ueber `[treasure_hunt_app, Schweizerbahn_hunts, Test_Deutsche_Geschichte_Deutschlandticket]`
   - `simple` — `cd $item && flutter test`
2. `conditional` — Bei Fehler: Zusammenfassung der fehlgeschlagenen Tests

**Zweck**: Alle 3 Flutter-App-Varianten regelmaessig testen

#### Workflow 3: "Dependency Security Audit"
**Projekt**: tools (uebergreifend)
**Schedule**: CRON `0 0 8 * * 1` (Montags 8:00)
**Bloecke**:
1. `simple` — `cd autotrigger/ai-automation-frontend && npm audit --json`
2. `simple` — `cd autotrigger/ai-automation-backend && mvn versions:display-dependency-updates -q`
3. `forEach` ueber weitere Projekte mit package.json:
   - `simple` — `cd $item && npm audit --json 2>/dev/null || true`
4. `agentic` — "Analysiere die Audit-Ergebnisse und erstelle einen priorisierten Report mit Critical/High-Vulnerabilities"

**Zweck**: Sicherheitsluecken frueh erkennen, bevor sie exploited werden

#### Workflow 4: "Hunt Manifest Validator"
**Projekt**: treasure_hunt_level
**Schedule**: CRON `0 0 9 * * *` (taeglich 9:00)
**Bloecke**:
1. `simple` — Alle `.hunt`-Dateien auflisten
2. `forEach` ueber Hunts:
   - `simple` — `unzip -p $item manifest.json | jq .` (JSON-Validierung)
   - `conditional` — Pruefe Pflichtfelder (title, description, tasks, solutions)
3. `agentic` — "Erstelle eine Zusammenfassung: Wie viele Hunts valide, wie viele kaputt, welche Felder fehlen"

**Zweck**: Datenqualitaet der generierten Hunt-Inhalte sichern

---

### Kategorie B: Entwicklungs-Unterstuetzung (On-Demand)

#### Workflow 5: "Feature Development Assistant"
**Projekt**: autotrigger
**Schedule**: On-Demand (manuell)
**Bloecke**:
1. `agentic` — Goal: "Analysiere den aktuellen Git-Diff und die offenen Aenderungen. Identifiziere welche Dateien geaendert wurden und ob zugehoerige Tests existieren. Erstelle fehlende Tests."
   - max_iterations: 8
   - max_total_tokens: 150000

**Zweck**: Automatische Test-Generierung fuer neue Features

#### Workflow 6: "Code Review & Refactoring Suggestions"
**Projekt**: autotrigger
**Schedule**: On-Demand
**Bloecke**:
1. `simple` — `cd ai-automation-backend && git diff main..HEAD --stat` (geaenderte Files)
2. `agentic` — Goal: "Fuehre ein Code-Review der geaenderten Dateien durch. Pruefe auf: Code-Duplikation, fehlende Fehlerbehandlung, Security-Risiken (OWASP Top 10), inkonsistente Patterns. Erstelle einen strukturierten Review-Report."
   - max_iterations: 6
   - max_total_tokens: 100000

**Zweck**: Automatisiertes Code-Review vor Merges

#### Workflow 7: "i18n Completeness Check"
**Projekt**: autotrigger (auch treasure_hunt_app)
**Schedule**: On-Demand (vor Releases)
**Bloecke**:
1. `simple` — Alle i18n-Keys aus en.ts extrahieren
2. `simple` — Alle i18n-Keys aus de.ts extrahieren
3. `conditional` — Vergleich: Fehlende Keys in de.ts? Fehlende Keys in en.ts?
4. `agentic` — "Fuer treasure_hunt_app: Pruefe ob alle 15 ARB-Sprachdateien die gleichen Keys haben. Liste fehlende Uebersetzungen auf."

**Zweck**: Keine fehlenden Uebersetzungen in Produktion

---

### Kategorie C: Content & Deployment (scheduled + On-Demand)

#### Workflow 8: "Hunt Build & Deploy Pipeline"
**Projekt**: treasure_hunt_level
**Schedule**: On-Demand
**Bloecke**:
1. `simple` — `cd treasure_hunt_level && bash scripts/build_hunts.sh` (Hunt-Dateien bauen)
2. `simple` — Validierung der gebauten .hunt-Dateien
3. `conditional` — Wenn Backend erreichbar: Upload der neuen Hunts
4. `simple` — Report: Welche Hunts gebaut, Groessen, Status

**Zweck**: Reproduzierbare Hunt-Builds mit Validierung

#### Workflow 9: "Website Build & Preview"
**Projekt**: treasure_hunt_web
**Schedule**: On-Demand
**Bloecke**:
1. `simple` — `cd zeitreise-touren && npm install && npm run build`
2. `conditional` — Build erfolgreich? → Report. Fehlgeschlagen? → Fehleranalyse
3. `agentic` — "Pruefe die gebaute Website auf tote Links, fehlende Bilder, und SEO-Basics (meta tags, alt texts)"

**Zweck**: Website-Qualitaet vor Deployment sicherstellen

---

### Kategorie D: Cross-Project Intelligence (woechentlich)

#### Workflow 10: "Project Health Dashboard"
**Projekt**: tools (uebergreifend)
**Schedule**: CRON `0 0 8 * * 5` (Freitags 8:00)
**Bloecke**:
1. `forEach` ueber alle Git-Repos:
   - `simple` — `cd $repo && git log --oneline -5 && git status --short`
2. `agentic` — "Erstelle einen woechentlichen Entwicklungs-Report: Welche Projekte wurden diese Woche bearbeitet, wo gibt es uncommitted changes, welche Repos haben keine Recent-Activity. Formatiere als uebersichtliche Tabelle."

**Zweck**: Ueberblick ueber alle Projekte, verwaiste Aenderungen finden

#### Workflow 11: "Dependency Update Planner"
**Projekt**: autotrigger
**Schedule**: CRON `0 0 10 * * 1` (Montags 10:00, monatlich genuegt)
**Bloecke**:
1. `simple` — Spring Boot Version pruefen, aktuelle Version ermitteln
2. `simple` — Frontend: `npm outdated --json`
3. `agentic` — "Erstelle einen Dependency-Update-Plan. Priorisiere: 1) Security-relevante Updates 2) Major-Version-Spruenge 3) Minor/Patch. Fuer jedes Update: Schaetze Aufwand und Risiko ein."

**Zweck**: Technische Schulden gezielt abbauen

#### Workflow 12: "AutoPilot Self-Improvement"
**Projekt**: autotrigger
**Schedule**: CRON `0 0 6 * * 5` (Freitags 6:00)
**Bloecke**:
1. `simple` — `mvn test -q` + Test-Count extrahieren
2. `simple` — Testabdeckung nach Package analysieren (Test-Files vs. Production-Files)
3. `agentic` — "Analysiere die Test-Abdeckung von autotrigger. Identifiziere die 3 wichtigsten ungetesteten Klassen und generiere Testvorschlaege. Fokus auf: WhileBlockExecutor, ApprovalBlockExecutor, SubWorkflowBlockExecutor, ContainerService, PathValidator."
   - max_iterations: 10
   - max_total_tokens: 200000

**Zweck**: AutoPilot verbessert seine eigene Testabdeckung iterativ

---

## Implementierungsreihenfolge

| Phase | Workflows | Aufwand |
|-------|-----------|---------|
| **Phase 1** (sofort) | WF1 (Backend Quality Gate), WF10 (Health Dashboard) | Gering — nur Cron-Schedules + simple Blocks |
| **Phase 2** (diese Woche) | WF3 (Security Audit), WF7 (i18n Check), WF4 (Hunt Validator) | Mittel — forEach + conditional Logic |
| **Phase 3** (naechste Woche) | WF5 (Feature Assistant), WF6 (Code Review), WF12 (Self-Improvement) | Hoch — agentic Blocks mit Token-Budgets |
| **Phase 4** (spaeter) | WF2 (Flutter Tests), WF8 (Hunt Deploy), WF9 (Website), WF11 (Dep Updates) | Mittel — Cross-Project + Flutter SDK noetig |

## Voraussetzungen

- **Projekte als AutoPilot-Projects registrieren**: autotrigger, treasure_hunt_level, treasure_hunt_web muessen als Projects mit korrektem `path` angelegt sein
- **Flutter SDK** muss auf dem Host installiert sein fuer WF2
- **jq** muss installiert sein fuer WF4 (Hunt-Validierung)
- **npm/node** auf Host fuer Frontend-Workflows

## Keine Code-Aenderungen noetig

Dieser Plan definiert nur **Workflows innerhalb der bestehenden AutoPilot-Plattform**. Alle Workflows nutzen existierende Block-Typen (simple, conditional, forEach, agentic). Es werden keine Aenderungen am autotrigger-Code vorgenommen.
