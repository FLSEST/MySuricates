> *Zuletzt aktualisiert: 28. März 2026, 14:30 Uhr — Workflow: MySuricates Docs aktualisieren | Run: c47b8112-0468-445d-bfaf-a7a8a5d46264*

# MySuricates — Vision

## Die Idee

MySuricates verfolgt zwei Ziele:

1. **Ein universeller Problemlöser** — für Alltag und Beruf
2. **Eine selbstverbessernde AI-Plattform** — die mit jeder Ausführung schlauer wird

Nicht nur für Entwickler. Für jeden, der reale Probleme lösen will.

---

## Das Prinzip der stetigen Annäherung

Perfektion ist kein Zustand — sie ist eine Richtung. Ein System, das Perfektion für erreichbar hält, hört irgendwann auf zu lernen. Dieses nicht.

Jeder Lauf produziert Daten. Jede Iteration verfeinert den nächsten Versuch. Learning, Memory, Factory und Wishes sind keine Features — sie sind die Mechanismen dieser stetigen Annäherung. Das System wird nie perfekt sein. Aber mit jedem Schritt kommt es der Perfektion näher — und genau das ist der Punkt.

---

## Säule 1: Universeller Problemlöser

MySuricates löst echte Probleme — im Privatleben und in der Berufswelt. Workflows sind die Bausteine, Chains kombinieren sie zu komplexen Lösungen.

### Beruf

- **Morgen-Briefing**: News, Kalender, Prioritäten — alles auf einen Blick
- **Meeting-Protokoll**: Aufnahme → Zusammenfassung → Action Items → Verteilung
- **Social Media Kalender**: Content-Ideen → Texte → Zeitplan → Posting
- **Security Audit**: Codebase scannen → Schwachstellen bewerten → Report
- **Code Review**: Automatische Analyse → Verbesserungsvorschläge → PR-Kommentare

### Privat

- **Bewerbungs-Coach**: Stellenanzeige analysieren → Anschreiben → Lebenslauf anpassen
- **Reiseplanung**: Budget, Interessen, Zeitraum → kompletter Reiseplan
- **Vokabel-Songs**: Frequenz-basiert Lieder generieren zum Sprachenlernen
- **Event-Organisation**: Gästeliste, Location, Zeitplan, Einladungen

### Kombiniert (Chains)

- **Content-Maschine**: Recherche → Artikel → Social Posts → Newsletter
- **Website-Audit**: Performance → SEO → Accessibility → Gesamtreport
- **Büro-Morgen-Routine**: Mails checken → Kalender → Briefing → Todos priorisieren

Der Kern: Jeder Workflow ist ein gelöstes Problem. Jede Chain ist eine gelöste Problemkette. Die Template-Bibliothek wächst — und damit die Menge der Probleme, die MySuricates out-of-the-box lösen kann.

---

## Säule 2: Selbstverbessernde AI-Plattform

Das System wird mit jeder Ausführung besser. Vier Mechanismen treiben die Evolution:

### Wishes — From Intent to Solution

The user says what they want in natural language. The system decomposes, builds, tests, executes, evaluates, and iterates.

```
User: "Ich brauche einen wöchentlichen Security-Report für mein Projekt"

MySuricates:
  1. Decompose → 3 sub-tasks identified
  2. Build → Workflow with shell + agentic blocks
  3. Pre-flight verify → Syntax OK, dependencies available
  4. Execute → Run on test instance
  5. Evaluate → Success rate, output quality
  6. Iterate → Fix issues, re-run if needed
```

The Decompose-Approval Gate lets the user review and adjust the plan before execution. Pre-flight verification catches errors before they waste compute.

### Factory — Workflow Generation Pipeline

Topic in, verified template out:

1. **Generate**: AI creates a workflow from a topic description
2. **Test**: Auto-deploy to the test instance and run
3. **Verify**: Check success, output quality, cost
4. **Promote**: Successful workflows become reusable templates

Multi-provider support (Claude + Codex) means the best model for each generation task.

### Learning — Feedback Loop

Every workflow run produces data that flows back into generation:

- **Success rates** per template, block type, and model
- **Strategy patterns** — what worked, what didn't
- **Cost tracking** — token usage per block and workflow
- **Cross-project recognition** — reusable patterns across different projects

Learning feedback is injected into prompts, making each generation smarter than the last.

### Memory — Accumulated Knowledge

Workflows accumulate knowledge across runs:

- Knowledge extraction from completed runs
- Summarization via Claude or Codex
- Active memories injected into future executions
- Per-workflow knowledge base that grows over time

---

## Was schon gebaut ist (v0.6.0)

| Baustein | Status |
|----------|--------|
| Block-basierte Workflows (12 Block-Typen) | Done |
| Docker-Container-Execution | Done |
| Agentic Blocks (Goal → Reason → Act → Observe) | Done |
| Workflow Chains (sequentielle Komposition) | Done |
| Workflow Factory (AI-generiert + Auto-Test) | Done |
| Wishes (Intent → Decompose → Execute → Evaluate) | Done |
| Decompose-Approval Gate | Done |
| Pre-flight Verification + Auto-Fix | Done |
| Learning-Feedback in Prompts | Done |
| Cross-Project Workflow-Erkennung | Done |
| 44 Built-in Templates (Dev + Büro + Alltag) | Done |
| 16 Built-in Skills (Code-Review, Test-Generator, OCR u.a.) | Done |
| Staging (Dev + Stable + Test) | Done |
| Multi-Provider (Claude + Codex) | Done |
| Assistent-System (kontextueller Floating-Chat mit Page-Suggestions) | Done |
| Gate Status System (Quality-Gates, AutoTodo-Integration) | Done |
| Agent Instructions System (Base- und Per-Workflow-Instruktionen) | Done |
| CompletionTrigger-System (automatische Aktionen nach Workflow-Abschluss) | Done |
| Notification-System (NotificationConfig) | Done |
| Workflow Memory (Wissensextraktion und -akkumulation über Runs) | Done |
| Template Verification (Qualitätsprüfung von Workflow-Templates) | Done |
| Workflow Conversion (Agentic-Runs → deterministische Workflows) | Done |

---

## Roadmap

### Phase 2: Kosten & Intelligenz (nächster Schritt)

- **Kosten-Schätzung vor Ausführung** — User sieht erwartete Token-Kosten bevor ein Wish läuft
- **Modell-Auswahl pro Wish** — Automatische oder manuelle Wahl des passenden Modells
- **Auto-Refine ohne User-Intervention** — Wish iteriert selbständig bis zur Zielqualität
- **Iterations-Historie mit Delta-Tracking** — Nachvollziehbar, was sich zwischen Iterationen verändert hat

### Phase 3: Crystallize & Discovery

- **Crystallize-Wizard** — Erfolgreiche Wishes in wiederverwendbare Templates umwandeln
- **Template-Discovery & Suche** — Templates finden nach Problem, nicht nach Namen
- **Wish-Analytics Dashboard** — Erfolgsraten, Kosten, Trends visualisieren

### Langfrist: Ökosystem

- **Externe Skill-Pakete** — Installierbare Pakete (Git-Repo/ZIP) mit neuen Skills + Capabilities
- **MCP-Server als Skills** — MCP-Server automatisch als Skills/Capabilities verfügbar machen
- **Integrations-Plugins** — Slack, Jira, GitHub, Email als Workflow-Blöcke
- **Workflow-Marketplace** — Community-Templates teilen und entdecken

---

## Architektur-Überblick

```
                    User
                      |
              "Ich will X lösen"
                      |
                  [ Wish API ]
                      |
        Decompose → Approve → Execute
                                |
                    [ Workflow Engine ]
                    /        |        \
              Blocks      Chains    Templates
             /  |  \
        Shell  Agentic  Human-Input  ...
                |
        [ Host Claude/Codex CLI ]
                |
        Goal → Reason → Act → Observe
                |
        [ Learning + Memory ]
                |
        Feedback → Next Generation
```

## Technical Stack

- **Backend**: Spring Boot 3.4, Java 21, SQLite, Flyway
- **Frontend**: React 18, TypeScript, TailwindCSS, TanStack Query, Zustand
- **Execution**: Docker containers (Python/Node/Go), Host Claude CLI, Host Codex CLI
- **Deployment**: Dev (source) + Stable (Docker, port 9091) + Test (Docker, port 8090)
