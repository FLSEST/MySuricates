# Baukasten

> **Wie arbeite ich mit starken LLMs, um auch meine beschränkten Fähigkeiten nutzen zu können?**

Aus dieser Frage ist MySuricates entstanden. Die Antworten darauf sind Bausteine, und man braucht
nicht alle: Ein kleiner Kern ist immer da, der Rest wird zugeschaltet – oder man schließt an, was man
schon hat. Deterministisch ist der rote Faden, weil man das, was man gebaut hat, wiederverwenden will.

Die Kennzeichen in den Tabellen:

- **Kern** – immer da (Preset `core`, der Standard)
- **+research**, **+full** – Module, die beim Start zugeschaltet werden
  ([Liste aller Module](architektur.md#module-system-startkonfiguration))
- **Schalter** – Härtung, die beim Start gesetzt wird

## Die vier Antworten

### 1. Nichts verschwindet im Leeren

Aufgaben und Nebenstränge, die aus einer Arbeit entstehen, gehen nicht verloren. Dazu kommen
Workflows, die an Dinge denken, die in einer Sitzung vergessen gingen.

| Baustein | | Wozu |
|---|---|---|
| Lauf-Verlauf | Kern | Jeder Schritt hält fest, was hineinging und was herauskam. |
| Versionen | Kern | Jede Änderung an einem Workflow bleibt erhalten und lässt sich zurückholen. |
| TODOs | Kern | Was ein Lauf findet, wird eine Aufgabe. Zum Ausprobieren: Blaupause „TODO-Lebenszyklus“. |
| API für Agenten | Kern | Eine KI-Sitzung (etwa Claude Code oder Codex) legt TODOs an und startet Workflows – das Projekt kennt MySuricates. → [AGENTS.md](../AGENTS.md) |
| Gedächtnis | +research | Erkenntnisse aus Läufen fließen in spätere Läufe ein. → [Workflow-Memory](konzepte/workflow-memory.md) · Zum Ausprobieren: Blaupause „Workflow-Memory nutzen“. |
| Audit | +research | Befunde mit Verlauf, auch solche, die bewusst in Kauf genommen werden. → [Audit-Protokoll](audit-workflow-protocol.md) |

### 2. Deterministisch und nicht-deterministisch kombinieren – und sichtbar machen

| Baustein | | Wozu |
|---|---|---|
| Workflow aus Schritten | Kern | Feste Schritte (Shell, Verzweigung, Schleife), KI-Schritte und Mensch-Schritte (Freigabe, Eingabe) in einem Ablauf. → [Workflow-Guide](WORKFLOW-GUIDE.md), alle Einstellungen je Schritt in der [Block-Referenz](block-referenz.md) · Zum Ausprobieren: Blaupausen „Wenn – dann – sonst“, „Liste abarbeiten“, „Freigabe vor dem Handeln“, „Formular und Verwendung“. |
| Schrittart im Lauf | Kern | Bei jedem Schritt ist zu sehen, ob eine KI, eine feste Regel oder ein Mensch gehandelt hat. |
| Ampel | Kern | Ein Schritt gibt `GREEN`, `YELLOW` oder `RED` aus – ein Urteil, an dem weitere Schritte hängen können. → [Gates](konzepte/gates.md) · Zum Ausprobieren: Blaupause „Ampel“. |
| Agent-Schritt mit Budget | Kern | Eine offene KI-Aufgabe läuft gegen feste Grenzen: Durchgänge, Zeit, Tokens. → [Agentische Budgets](konzepte/agentische-budgets.md) · Zum Ausprobieren: Blaupause „Agent mit Freigabe (teuer)“. |
| Prüfung der Definition | Kern | Meldet Baufehler eines Workflows, bevor er läuft. |
| Diagramm (BPMN) | +research | Derselbe Ablauf als Diagramm, auch während eines Laufs – lesbar ohne Code, austauschbar mit BPMN-Werkzeugen. |
| Siegel | Schalter | Ein freigegebener Ablauf läuft nur so, wie er freigegeben wurde. → [Härtung](konzepte/haertung.md) |

### 3. Unabhängig von Anbietern

| Baustein | | Wozu |
|---|---|---|
| KI-Anbieter | Kern | Claude und Codex direkt, über Pi weitere Modelle – wählbar je Workflow oder je Schritt. Wechselt man den Anbieter, bleibt der Ablauf. |
| Modell je Schritt | Kern | Ein eng gefasster KI-Schritt kann ein kleineres Modell oder einen anderen Anbieter bekommen. Zum Ausprobieren: Blaupause „Modell pro Schritt“. |
| Anbindungen | Kern / +full | Eigene Bausteine nutzen oder ein vorhandenes System anschließen – siehe [Eigen oder anschließen](#eigen-oder-anschließen). Zum Ausprobieren: Blaupause „Integration abfragen“ (+full). |

### 4. Daraus viele weitere Ideen

| Baustein | | Wozu |
|---|---|---|
| Vorlagen, Pool, Blaupausen | Kern | Fertige Workflows übernehmen und anpassen; Blaupausen sind Grundgerüste zum Ausfüllen. → [Pool](../workflow-pool/README.md) |
| Auslöser | Kern | Zeitplan, Folge auf einen Lauf, Folge auf ein TODO. → [Zugänge](konzepte/zugaenge.md) · Zum Ausprobieren: Blaupausen „Per Zeitplan starten“, „Von einem TODO-Trigger gestartet“, „Per Webhook starten (mit Signatur)“ (+full, Schalter `webhook-signatures`). |
| Teil-Workflows | Kern | Ein Workflow ruft einen anderen auf, auch einmal je Eintrag einer Liste. Zum Ausprobieren: Blaupause „Teil-Workflow aufrufen (Paket)“. |
| Ziele | Kern | TODOs und Workflows auf ein Ergebnis hin bündeln. Zum Ausprobieren: Vorlage „Ziel-Wächter“. |
| Dashboard-Bericht | Kern | Ein Workflow legt sein Ergebnis als Kachel aufs Dashboard. Zum Ausprobieren: Blaupause „Ergebnis melden“. |
| Ketten | +research | Mehrere Workflows hintereinander. Zum Ausprobieren: Blaupause „Glied einer Kette“. |

## Eigen oder anschließen

In manchen Fällen sind die einen Bausteine sinnvoll, weil man das andere schon hat, in anderen nicht.
Wer seine Aufgaben schon in Jira führt, schließt Jira an, statt die eigenen TODOs zu nutzen.

| Thema | eigen | anschließen, zum Beispiel |
|---|---|---|
| Aufgaben | TODOs, Ziele | Jira, GitHub, Trello, … |
| Auslöser | Zeitplan, Folge-Auslöser | Webhook, … |
| Benachrichtigen | Oberfläche | ntfy, Slack, Telegram, … |
| Wissen | Gedächtnis, Dokument-Schritt | Confluence, Feeds, … |
| Termine | Kalender mit den geplanten Läufen | CalDAV, Google Kalender, iCal-Feeds, … |
| Code | Projektordner | GitHub, GitLab, Gitea, … |

Die genannten Systeme sind Beispiele, keine abschließende Liste. Welche Anbindungen gegen den echten
Dienst geprüft sind, steht unter [Verfügbare Provider](integrations.md#verfügbare-provider).

### Drei Wege, etwas anzuschließen

1. **Shell-Schritt** (Kern) – mit dem Werkzeug, das das System selbst mitbringt: sein
   Kommandozeilenprogramm oder ein Aufruf seiner Web-Schnittstelle.
2. **HTTP-API** (+full) – eine Web-Schnittstelle beschreiben, ohne Code.
   → [HTTP-API-Verbindung](integrations.md#http-api-verbindung-http-api)
3. **Eigener Provider** (Code) – für Anbindungen mit Anmeldung und eigenen Aktionen; die
   mitgelieferten Provider sind Vorlagen dafür.
   → [Neuen Provider hinzufügen](integrations.md#neuen-provider-hinzufügen-schritt-für-schritt)

## Ansätze aus denselben Bausteinen

Es gibt sehr viele Arten, Lösungen mit KI anzugehen, und verschiedene Projekte haben unterschiedliche
Ansätze gewählt. Hier ist ein Ansatz eine Kombination derselben Bausteine – man kann mehrere
nebeneinander ausprobieren. Die Läufe halten fest, welche Schritte liefen, wie viele Tokens sie
brauchten und wo es scheiterte; so lässt sich an derselben Aufgabe sehen, was funktioniert.

### Loop-Engineering

Ein Lauf prüft und findet etwas, daraus wird ein TODO, ein Folge-Lauf repariert, die Prüfung läuft
erneut. Bausteine: Ampel → TODO → TODO-Auslöser → Reparatur-Workflow. Begrenzt wird der Kreis durch
eine Höchstzahl an Versuchen, und ein Reparaturlauf erzeugt selbst kein TODO.

Zum Ansehen: die Blaupausen „Ampel“, „Von einem TODO-Trigger gestartet“ und „Schleife mit Freigabe“,
im Pool „SaaS — Auto-Fix“. Mehr: [Selbstverbesserung](konzepte/selbstverbesserung.md).

### Spec-Driven Development

Am Anfang steht eine Beschreibung (Spec). Daraus entsteht ein Plan, der freigegeben wird; jeder Teil
wird einzeln umgesetzt und geprüft. Bausteine: Dokument-Schritt liest die Spec → KI-Schritt zerlegt
→ Freigabe → je Teil ein Teil-Workflow → Tests als feste Prüfung.

Zum Ansehen im Pool: „SaaS — Spec zu Build-Plan“ → „SaaS — Feature-Schleife“ → „SaaS — Feature
implementieren“. Sie setzen eine SaaS-Factory im Projekt voraus, sind als Muster aber auch ohne lesbar.

### Agenten orchestrieren

Mehrere KI-Schritte oder Workflows arbeiten nacheinander oder einmal je Eintrag einer Liste, jeder mit
eigenen Grenzen; ein zweiter Anbieter kann das Ergebnis prüfen, ohne selbst zu schreiben. Bausteine:
Teil-Workflow je Eintrag, Agent-Schritt mit Budget, isolierter Klon, Ketten (+research).

Zum Ansehen im Pool: die Kette „TODO-Batch“ aus „TODO-Batch: Einordnen“ und „TODO-Batch: Abarbeiten“,
außerdem „SaaS — Feature-Schleife“. Mehr: [Agentische Budgets](konzepte/agentische-budgets.md).

Weitere Ansätze entstehen, indem man dieselben Bausteine anders zusammensetzt.

## Zwei Wege durch diese Doku

**Ich baue Workflows.** [Workflow-Guide](WORKFLOW-GUIDE.md) für Schritte, Muster und Fallen, die
Blaupausen in der Oberfläche, die [Konzepte](konzepte/README.md) zum Übernehmen,
[Integrationen](integrations.md) zum Anschließen.

**Ich nutze, prüfe, gebe frei.** Das kann jeder sein, nicht nur, wer Software entwickelt.
[Getting Started](GETTING-STARTED.md) für den Einstieg; in der Oberfläche der „Einfache Modus“, die
Seite „Freigaben“ mit allem, was auf eine Entscheidung wartet, und die Laufansicht mit Schrittart und
Diagramm. Hinweise erscheinen dort, wo man sie braucht.

Zum Nachschlagen: [Architektur](architektur.md), [API-Referenz](api-referenz.md),
[Block-Referenz](block-referenz.md),
[Datenmodell](datenmodell.md), [Deployment](deployment.md), [Entwicklung](entwicklung.md).
