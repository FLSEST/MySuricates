# MySuricates — Vision

## Die Idee

MySuricates verfolgt zwei Ziele:

1. **Ein universeller Problemloeser** — fuer Alltag und Beruf
2. **Eine selbstverbessernde AI-Plattform** — die mit jeder Ausfuehrung schlauer wird

Nicht nur fuer Entwickler. Fuer jeden, der reale Probleme loesen will.

---

## Das Prinzip der stätigen Annäherung

Perfektion ist kein Zustand — sie ist eine Richtung. Ein System, das Perfektion für erreichbar hält, hört irgendwann auf zu lernen. Dieses nicht.

Jeder Lauf produziert Daten. Jede Iteration verfeinert den nächsten Versuch. Learning, Memory, Factory und Wishes sind keine Features — sie sind die Mechanismen dieser stätigen Annäherung. Das System wird nie perfekt sein. Aber mit jedem Schritt kommt es der Perfektion näher — und genau das ist der Punkt.

---

## Saeule 1: Universeller Problemloeser

MySuricates loest echte Probleme — im Privatleben und in der Berufswelt. Workflows sind die Bausteine, Chains kombinieren sie zu komplexen Loesungen.

### Beruf

- **Morgen-Briefing**: News, Kalender, Prioritaeten — alles auf einen Blick
- **Meeting-Protokoll**: Aufnahme → Zusammenfassung → Action Items → Verteilung
- **Social Media Kalender**: Content-Ideen → Texte → Zeitplan → Posting
- **Security Audit**: Codebase scannen → Schwachstellen bewerten → Report
- **Code Review**: Automatische Analyse → Verbesserungsvorschlaege → PR-Kommentare

### Privat

- **Bewerbungs-Coach**: Stellenanzeige analysieren → Anschreiben → Lebenslauf anpassen
- **Reiseplanung**: Budget, Interessen, Zeitraum → kompletter Reiseplan
- **Vokabel-Songs**: Frequenz-basiert Lieder generieren zum Sprachenlernen
- **Event-Organisation**: Gaesteliste, Location, Zeitplan, Einladungen

### Kombiniert (Chains)

- **Content-Maschine**: Recherche → Artikel → Social Posts → Newsletter
- **Website-Audit**: Performance → SEO → Accessibility → Gesamtreport
- **Buero-Morgen-Routine**: Mails checken → Kalender → Briefing → Todos priorisieren

Der Kern: Jeder Workflow ist ein geloestes Problem. Jede Chain ist eine geloeste Problemkette. Die Template-Bibliothek waechst — und damit die Menge der Probleme, die MySuricates out-of-the-box loesen kann.

---

## Saeule 2: Selbstverbessernde AI-Plattform

Das System wird mit jeder Ausfuehrung besser. Vier Mechanismen treiben die Evolution:

### Wishes — From Intent to Solution

The user says what they want in natural language. The system decomposes, builds, tests, executes, evaluates, and iterates.

```
User: "Ich brauche einen woechentlichen Security-Report fuer mein Projekt"

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

## Was schon gebaut ist (v0.5.0)

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
| 44 Built-in Templates (Dev + Buero + Alltag) | Done |
| Staging (Dev + Stable + Test) | Done |
| Multi-Provider (Claude + Codex) | Done |

---

## Roadmap

### Phase 2: Kosten & Intelligenz (naechster Schritt)

- **Kosten-Schaetzung vor Ausfuehrung** — User sieht erwartete Token-Kosten bevor ein Wish laeuft
- **Modell-Auswahl pro Wish** — Automatische oder manuelle Wahl des passenden Modells
- **Auto-Refine ohne User-Intervention** — Wish iteriert selbststaendig bis zur Zielqualitaet
- **Iterations-Historie mit Delta-Tracking** — Nachvollziehbar, was sich zwischen Iterationen veraendert hat

### Phase 3: Crystallize & Discovery

- **Crystallize-Wizard** — Erfolgreiche Wishes in wiederverwendbare Templates umwandeln
- **Template-Discovery & Suche** — Templates finden nach Problem, nicht nach Namen
- **Wish-Analytics Dashboard** — Erfolgsraten, Kosten, Trends visualisieren

### Langfrist: Oekosystem

- **Externe Skill-Pakete** — Installierbare Pakete (Git-Repo/ZIP) mit neuen Skills + Capabilities
- **MCP-Server als Skills** — MCP-Server automatisch als Skills/Capabilities verfuegbar machen
- **Integrations-Plugins** — Slack, Jira, GitHub, Email als Workflow-Bloecke
- **Workflow-Marketplace** — Community-Templates teilen und entdecken

---

## Architektur-Ueberblick

```
                    User
                      |
              "Ich will X loesen"
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
