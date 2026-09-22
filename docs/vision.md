# MySuricates — Vision

MySuricates ist eine selbst betriebene Anwendung aus Backend und Web-Oberflaeche, in der
Arbeitsablaeufe als Ketten von Bloecken laufen. Dieses Dokument beschreibt nicht, was sie heute
tut — das steht in [README.de.md](../README.de.md) —, sondern wohin sie sich entwickeln soll.

> Kuerzel in Klammern wie `(182bd224)` oder `TODO 63ecc124` sind Herkunftsnachweise:
> Commits dieses Repositories bzw. Vorgaenge in der begleitenden MySuricates-Instanz.
> Sie belegen, wann und warum etwas entstanden ist — von aussen nachschlagen laesst
> sich davon nur der Commit.

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

### Wishes — vom Wunsch zur Lösung

Der Nutzer sagt in eigenen Worten, was er will. Das System zerlegt, baut, testet, führt aus,
bewertet und bessert nach.

```
Nutzer: "Ich brauche einen wöchentlichen Security-Report für mein Projekt"

MySuricates:
  1. Zerlegen    → 3 Teilaufgaben erkannt
  2. Bauen       → Workflow aus shell- und agentic-Blöcken
  3. Vorprüfung  → Syntax in Ordnung, Abhängigkeiten vorhanden
  4. Ausführen   → Lauf auf der Testinstanz
  5. Bewerten    → Erfolgsquote, Qualität der Ausgabe
  6. Nachbessern → Fehler beheben, erneut laufen lassen
```

Ein Freigabe-Schritt nach dem Zerlegen lässt den Nutzer den Plan prüfen und ändern, bevor
etwas ausgeführt wird. Die Vorprüfung fängt Fehler ab, bevor sie Rechenzeit kosten.

### Factory — Fließband für Workflows

Thema hinein, geprüfte Vorlage heraus:

1. **Erzeugen**: Die KI baut aus einer Themenbeschreibung einen Workflow
2. **Testen**: Automatisch auf die Testinstanz bringen und laufen lassen
3. **Prüfen**: Erfolg, Qualität der Ausgabe und Kosten bewerten
4. **Übernehmen**: Bewährte Workflows werden wiederverwendbare Vorlagen

Weil mehrere KI-Anbieter zur Verfügung stehen (Claude, Codex, Pi), lässt sich je Aufgabe das
passende Modell wählen.

### Learning — Rückkopplung

Jeder Workflow-Lauf erzeugt Daten, die in die nächste Erzeugung zurückfließen:

- **Erfolgsquoten** je Vorlage, Blocktyp und Modell
- **Strategiemuster** — was funktioniert hat und was nicht
- **Kostenverfolgung** — Tokenverbrauch je Block und Workflow
- **Projektübergreifende Wiedererkennung** — Muster, die sich anderswo wiederverwenden lassen

Die Rückmeldungen werden in die Prompts eingespielt, sodass jede Erzeugung von der vorherigen
profitiert.

### Memory — angesammeltes Wissen

Workflows sammeln Wissen über Läufe hinweg:

- Wissen wird aus abgeschlossenen Läufen extrahiert
- Zusammenfassung über Claude oder Codex
- Aktive Einträge werden vor künftigen Ausführungen eingespielt
- Je Workflow eine Wissensbasis, die mit der Zeit wächst

---

## Was schon gebaut ist (v0.7.4)

| Baustein | Status |
|----------|--------|
| Block-basierte Workflows (20 Block-Typen) | Done |
| Docker-Container-Execution | Done |
| Agentic Blocks (Reason → Act → Observe) | Done |
| Workflow Chains (sequentielle Komposition) | Done |
| Workflow Factory (AI-generiert + Auto-Test) | Done |
| Wishes (Intent → Decompose → Execute → Evaluate) | Done |
| Decompose-Approval Gate | Done |
| Pre-flight Verification + Auto-Fix + AutoRepair-Config | Done |
| Learning-Feedback in Prompts | Done |
| Cross-Project Workflow-Erkennung | Done |
| 67 Built-in Templates (Dev + Büro + Alltag) | Done |
| 19 Built-in Skills (Code-Review, Test-Generator, Security-Analyzer u.a.) | Done |
| Staging (Dev + Stable + Test) | Done |
| Multi-Provider (Claude + Codex + Pi) | Done |
| Assistent-System (kontextueller Floating-Chat mit Page-Suggestions) | Done |
| Gate Status System (Quality-Gates, AutoTodo-Integration) | Done |
| Agent Instructions System (Base- und Per-Workflow-Instruktionen) | Done |
| CompletionTrigger-System (automatische Aktionen nach Workflow-Abschluss) | Done |
| TodoTrigger-System (Self-Improvement-Loop: TODO → Fix-Workflow → Verify → Close) | Done |
| Notification-System (NotificationConfig) | Done |
| Workflow Memory (Wissensextraktion und -akkumulation über Runs) | Done |
| Template Verification (Qualitätsprüfung von Workflow-Templates) | Done |
| Workflow Conversion (Agentic-Runs → deterministische Workflows) | Done |
| Run-Resumption (ab letztem Step fortsetzen nach Unterbrechung) | Done |
| Session-zu-Workflow-Import (Claude-Sessions als Workflows) | Done |
| Erweiterte Schedule-Steuerung (End-Conditions, Konflikt-Verhalten) | Done |
| In-App Dokumentation (DocsController, kontextuelle Hilfe) | Done |
| Onboarding-Profile (geführter Einstieg für neue Nutzer) | Done |
| Workflow Pool & PoolBrowser (Template-Marktplatz-Browsing) | Done |
| Workflow-Versionierung (Versionshistorie + Restore) | Done |
| Simple Mode (vereinfachte UI für Einsteiger) | Done |
| Insights-Page (Analyse-Dashboard) | Done |
| Chain-Templates (vorgefertigte Ketten-Vorlagen) | Done |

---

## Roadmap

### Phase 2: Kosten & Intelligenz (nächster Schritt)

- **Kosten-Schätzung vor Ausführung** — User sieht erwartete Token-Kosten bevor ein Wish läuft
- **Modell-Auswahl pro Wish** — Automatische oder manuelle Wahl des passenden Modells
- ~~**Auto-Refine ohne User-Intervention**~~ — Done (`Wish.autoRefine` + auto-approve in `WishDecomposeService`)
- ~~**Iterations-Historie mit Delta-Tracking**~~ — Done (`Wish.iterationHistory` + `iterationDiff.ts` Diff-Ansicht)

### Phase 3: Crystallize & Discovery

- ~~**Crystallize-Wizard**~~ — Done (`WishService.crystallize()` + Controller)
- ~~**Template-Discovery & Suche**~~ — Done (WorkflowPoolService + PoolBrowser)
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
        [ Host Claude/Codex/Pi CLI ]
                |
        Reason → Act → Observe
                |
        [ Learning + Memory ]
                |
        Feedback → Next Generation
```

## Technical Stack

- **Backend**: Spring Boot 3.5, Java 21, SQLite, Flyway
- **Frontend**: React 19, TypeScript, TailwindCSS, TanStack Query, Zustand
- **Execution**: Docker containers (Python/Node/Go), Host Claude CLI, Host Codex CLI, Host Pi CLI
- **Deployment**: Dev (source) + Stable (Docker, port 9091) + Test (Docker, port 8090)
