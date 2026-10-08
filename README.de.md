**Deutsch** | [English](README.md)

<p align="center"><img src="docs/assets/readme-banner-de.svg" alt="MySuricates – Baue deine eigenen Workflows mit deinen Erdmännchen und lass sie dir helfen, jeden Schritt im Blick zu behalten. Deterministisch, wo es geht. Jeder Anbieter: Wechsle ihn und behalte deinen Ablauf. Behalte im Blick, was du getan hast und was du tust. Setze deine Erdmännchen ein, so wie du sie brauchst. Workflows, Ketten, Skills, Vorlagen, Zeitpläne, TODOs, Integrationen, Webhooks, Human-in-the-Loop, Assistent, BPMN, Versionen, Gates, Memory, Dashboard, Audit, hoch konfigurierbar, komplett individualisierbar, Features abschaltbar und mehr."></p>

<p align="center">
  <a href="https://github.com/FLSEST/MySuricates/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/FLSEST/MySuricates/ci.yml?branch=main&amp;label=CI" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/FLSEST/MySuricates" alt="Lizenz"></a>
  <a href="https://github.com/FLSEST/MySuricates/releases/latest"><img src="https://img.shields.io/github/v/release/FLSEST/MySuricates" alt="Release"></a>
</p>

# MySuricates

Ein Forschungsprojekt zur Frage, wie sich Softwareentwicklung und andere Arbeit mit KI-Agenten verändern
und wie sich diese Arbeit aus wiederholbaren, möglichst nachvollziehbaren Bausteinen unterschiedlich
zusammensetzen lässt — und ein lauffähiges System, an dem sich das beobachten lässt.

## Schnellstart

Nur ausprobieren — dafuer braucht es nur Docker:

```bash
git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates
scripts/tryout.sh build          # baut das Image aus dem neuesten Release; der erste Build dauert je nach Rechner laenger
scripts/tryout.sh start          # http://localhost:9095 — die Ausgabe zeigt den API-Key fuer die Anmeldung
scripts/tryout.sh login claude   # optional, fuer KI-Bloecke; ebenso codex oder pi
```

Das Repository veroeffentlicht kein fertiges Image, `build` baut lokal aus dem Repository. Der
Container bindet nichts vom Rechner ein — keine Ordner, keine Anmeldungen; die KI-Anmeldung bleibt
in einem eigenen Docker-Volume. Dauerhaft einrichten: [Installation](#installation) weiter unten
oder Schritt fuer Schritt in [Getting Started](docs/GETTING-STARTED.md).

## Dokumentation

Geordnet nach Tiefe: erst der Überblick, dann die einzelnen Bausteine, dann das Nachschlagewerk.

**Einstieg**

| Dokument | Inhalt |
|---|---|
| [Baukasten](docs/baukasten.md) | Die Ausgangsfrage und ihre Antworten als Bausteine: was zum Kern gehoert, was zugeschaltet wird, was man selbst hat oder anschliesst — und Ansaetze wie Loop-Engineering oder Spec-Driven Development aus denselben Bausteinen |
| [Getting Started](docs/GETTING-STARTED.md) | Ausprobieren ohne Installation (Tryout-Image) und Erstinstallation mit dem gefuehrten Installer (Container, Rechner oder Dev · Test · Prod) |

**Bausteine vertiefen**

| Dokument | Inhalt |
|---|---|
| [Konzepte](docs/konzepte/README.md) | Acht Mechanismen zum Uebernehmen — Gates, Selbstverbesserung, Haertung u. a. —, je mit Ablaufdiagramm, Grenzen und Codebelegen |
| [Workflow-Guide](docs/WORKFLOW-GUIDE.md) | Block-Typen, Patterns, Timeouts |
| [Integrations](docs/integrations.md) | Plugin-System, OAuth, Provider-Referenz |
| [Audit-Protokoll](docs/audit-workflow-protocol.md) | Wie Befunde entstehen, Gate-Status, TODO-Verknuepfung |
| [Assistant](docs/assistant-system.md) | Chat-Assistent, Sessions, Suggestions |
| [Diagramme](docs/diagramme.md) | Ablaufdiagramme zum Kern: Workflow-Engine, Steuerung und Status, Workflow-Memory |

**Nachschlagen**

| Dokument | Inhalt |
|---|---|
| [API-Referenz](docs/api-referenz.md) | REST-Endpunkte je Bereich mit Zweck und Modul, erzeugt aus den Controllern |
| [Block-Referenz](docs/block-referenz.md) | Alle Config-Schlüssel je Block-Typ, erzeugt aus dem Schema des Editors |
| [Datenmodell](docs/datenmodell.md) | Tabellen, Beziehungen, Enums, SQLite-Besonderheiten |

**Betrieb und Entwicklung**

| Dokument | Inhalt |
|---|---|
| [Deployment](docs/deployment.md) | Docker, Konfiguration, Umgebungsvariablen, Vertrauensmodell |
| [Architektur](docs/architektur.md) | Systemuebersicht, Schichten, Datenfluss, Sicherheit |
| [Entwicklung](docs/entwicklung.md) | Lokales Setup, Einstieg in den Code, Build, Tests, Konventionen |
| [Frontend](docs/frontend.md) | Aufbau und Regeln: State, API-Client, i18n, Design-System und Themes |

Fuer Agenten, die die REST-API direkt ansprechen, ist [AGENTS.md](AGENTS.md) der Einstieg.

## Fragen, die dabei entstehen

Auf diese Fragen gibt es heute schon sehr unterschiedliche Antworten.

> **Wie arbeite ich mit starken LLMs, wenn ich Agentensystemen nicht nur auf Zuruf weitere Aufgaben geben
> oder sie einfach frei laufen lassen will?**

1. Aufgaben und Nebenstränge, die aus einer Arbeit entstehen, **verschwinden nicht im Leeren** —
   TODO-System, Historie. MySuricates kennt das Projekt, das Projekt kennt MySuricates. Dazu Workflows,
   die **an Sachen denken, die in der Sitzung vergessen gingen**.
2. Wie baue ich Abläufe, die **deterministisch und nicht-deterministisch kombinieren** — und wie mache
   ich das **sichtbar** (Versionen, Läufe, Prozessschritte)? Daraus dann: was braucht es da alles für
   unterschiedliche Schritte?
3. Wie mache ich das Ganze **unabhängig von Anbietern**?
4. Wie baue ich **Schleifen (Loop-Engineering)** wie das Audit-System, die einen Lauf mit früheren
   vergleichbar machen, schon einmal gestellte Fragen wiederfinden und das Ergebnis von **Menschen
   bewerten** lassen?
5. Daraus sind **viele zusätzliche Ideen** entstanden: wie man dahin kommt, wie man es verknüpft, wie
   man es sichtbar macht.

Die Antworten sind Bausteine, und man braucht nicht alle. Was man schon hat, schließt man an — etwa
ein vorhandenes Aufgabensystem statt der eigenen TODOs. Welcher Baustein zu welcher Antwort gehört und
welche Ansätze (etwa Loop-Engineering, Spec-Driven Development oder Agenten orchestrieren) sich aus
denselben Bausteinen bauen lassen, zeigt der [Baukasten](docs/baukasten.md).

Nach der Installation ist nur der Kern an (Preset `core`); `research` und `full` schalten weitere
Module zu — welche, steht unter [Bevor du es startest](#bevor-du-es-startest) und im [Baukasten](docs/baukasten.md).

## Leitideen

1. **Deterministisch, wo es geht**, auch wegen der Kosten: Ein Shell-Schritt kostet keine Tokens, KI nur dort, wo sie nötig ist. Frontier-Modelle helfen beim Bauen. Was gebaut ist, läuft deterministisch, und die verbleibenden, eng gefassten KI-Stellen können anders gefüttert werden (kleineres Modell, anderer Anbieter).
2. **Wiederverwenden und nachhalten:** Vorlagen, Pool, Sub-Workflows, Chains und Skills zum Wiederverwenden. Das TODO-System hält nach.
3. **Einfacher Zugang und Übersicht** über eine Oberfläche.
4. **Alles lässt sich zusammenstöpseln:** diverse Konzepte, weitere Quellen anbinden.
5. **Unabhängig vom Anbieter, so weit wie möglich:** Anbieter und Modell je Block wählbar. Die Kapselung der KI im Container ist noch nicht erreicht.

## Reifegrad und bekannte Schulden

Bekannte harte Grenzen:

- **SQLite mit genau einer Verbindung.** Der Connection-Pool ist bewusst auf 1 gesetzt, weil SQLite
  nur einen Schreiber kennt. Das serialisiert alle Datenbankzugriffe und begrenzt die Parallelitaet.
- **Keine Mandantenfaehigkeit.** Ein API-Key, ein Benutzer, keine Rollen.
- **Die KI-Aufrufe haengen an lokal installierten CLIs** (`claude`, `codex`, `pi`) — es gibt
  keinen Weg an der CLI vorbei. Die CLIs selbst nehmen statt der Anmeldung auch einen
  API-Schluessel per Umgebungsvariable.

## Bevor du es startest

Shell-Bloecke und KI-Aufrufe laufen **mit deinen Rechten** — direkt auf dem Rechner oder, bei der
Instanz im Container, mit deinen eingebundenen Ordnern und Anmeldungen —, nicht je Block in einer
Sandbox; die KI-CLIs fragen dabei standardmaessig nicht vor Werkzeugnutzung nach. Auf jedem Weg —
Installer, Container, Dev-Start aus dem Quellcode — lauschen Backend und Frontend standardmaessig nur
auf `127.0.0.1` (`MYSURICATES_DEV_BIND` bzw. `…BIND=0.0.0.0` oeffnen das bewusst), und einige
Endpunkte brauchen keinen API-Key. MySuricates
ist fuer den lokalen Betrieb durch eine vertrauenswuerdige Person gebaut — fuer Fernzugriff gehoert
ein VPN davor, kein offener Port. Vollstaendig:
[Vertrauensmodell und Netzwerk-Exposition](docs/deployment.md#vertrauensmodell-und-netzwerk-exposition).

Nicht alles ist nach der Installation eingeschaltet. Randfunktionen sind Module, die beim
Start festgelegt werden. Das Preset `core` (Standard) enthaelt den Kern — Workflows, Bloecke,
Zeitplaene, TODOs. `research` ergaenzt:
Chains (mehrere Workflows als eine Kette), den Chat-Assistenten, Learning (Auswertungen ueber
abgelaufene Runs — Kennzahlen und Empfehlungen), Workflow-Memory (Wissen aus Runs, das in spaetere
Runs eingespielt wird), das Audit Center (die Seite, auf der die Befunde der Audit-Workflows ueber
die Zeit gefuehrt werden) und BPMN-Export/-Import. `full` schaltet alles frei — darunter Integrationen,
Webhooks und Fernzugriff. Der Installer fragt danach; die vollstaendige Liste mit allen Modulen
steht in [Architektur](docs/architektur.md#module-system-startkonfiguration).

## Tech-Stack

| Komponente | Technologie |
|---|---|
| Backend | Spring Boot 3.5, Java 21 |
| Frontend | React 19, TypeScript 6, Vite 8 |
| Datenbank | SQLite (WAL-Modus) + Flyway |
| Container | Docker (Java Docker Client 3.7.1) — optional; fuer Shell-Bloecke mit `container_image`, Projekt-Container und Terminal |
| Styling | Tailwind CSS 4, CSS-Variablen |
| State | Zustand 5 + TanStack Query 5 |
| WebSocket | STOMP ueber SockJS |
| Sicherheit | API-Key-Auth, Rate Limiting, Zulassungspruefung vor jedem Run; optional HMAC-signierte Workflow-Siegel |
| i18n | Deutsch / Englisch |
| PWA | Vite PWA Plugin |
| Integrationen | Plugin-System für externe Dienste, z. B. Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Gitea/Forgejo, GitLab, Taskwarrior; ein neuer Anbieter ist eine einzelne Klasse — Modul, im Preset `full` |

Ausgefuehrt wird dort, wo die Instanz laeuft — im Container (empfohlen) oder direkt auf dem
Rechner: Shell-Bloecke ueber die Shell des Benutzers, KI-Bloecke ueber die installierte CLI (im
Container vom Rechner eingebunden). Einen eigenen Container je
Shell-Block (`container_image`) gibt es nur, wenn der Docker-Socket ausdruecklich freigegeben ist.
Was von den KI-Anbietern in den Container kommt, bestimmt ein Schalter: standardmaessig nur die
Programme und je Anbieter die Anmeldung (fuer Claude einmal ein Token aus `claude setup-token`), nicht
die Einstellungen, Plugins, Memory oder Verlaeufe des Rechners. Wer weniger oder alles teilen will,
findet die Varianten unter [KI-Anbieter im Container](docs/deployment.md#ki-anbieter-im-container).

## Installation

```bash
git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates
./scripts/setup.sh              # gefuehrt, auf Englisch; auf Deutsch: ./scripts/setup.sh --lang de
```

Der Installer fragt nach dem Weg – eine Instanz im Container (empfohlen), direkt auf dem Rechner oder
gestuft Dev · Test · Prod – und nach den Modulen, installiert fehlende Werkzeuge nach Rückfrage und
startet die Instanz. Am Ende nennt er ihre Adresse: Sie lauscht nur auf `127.0.0.1`, auf Port 9090 oder
dem nächsten freien; gestuft bekommt jede Stufe ihren eigenen Port. Den API-Key zeigt
`scripts/instance.sh key --name main`. Schritt für Schritt: [Getting Started](docs/GETTING-STARTED.md) –
dort auch der Stand für macOS (vorbereitet) und Windows über WSL2, beide noch nicht auf echter Hardware
durchgespielt.

**KI-Blöcke** brauchen mindestens eine angemeldete Kommandozeile – `claude`, `codex` oder `pi`. Der
Installer zeigt, welche er findet, verlangt aber keine; ohne sie laufen nur die deterministischen Blöcke.

**Am Code mitarbeiten:** `./scripts/setup.sh --from-source --start` richtet ein und startet die
Dev-Instanz (Backend auf :9090, Vite mit Hot Reload auf :3000); danach startet, stoppt und baut
`./scripts/start-mysuricates-dev.sh start|stop|restart` sie neu. Siehe [Entwicklung](docs/entwicklung.md).

## Beiträge und Support

**Was das nicht ist:** kein Produkt, kein Dienst, kein Support, keine Stabilitaetszusage. Die
Architekturschulden sind bekannt und werden im System selbst als Audit-Befunde gefuehrt — siehe
[Reifegrad](#reifegrad-und-bekannte-schulden).

Issues bleiben offen — aber dieses Repository ist ein Forschungsprojekt eines
einzelnen Entwicklers, kein betreutes Produkt: kein Support, keine Zusage zu
Reaktionszeiten, auch nicht auf Pull Requests. Wer trotzdem etwas beitragen
will, meldet sich am besten zuerst per Issue.

Sicherheitslücken bitte **nicht** als öffentliches Issue melden, sondern über
den Meldeweg in [SECURITY.md](SECURITY.md) (private Sicherheitshinweise von
GitHub).

---

> 🌍 **README.de.md ist die Quelle, `README.md` die Übersetzung.** Inhaltliche Änderungen gehören in diese deutsche Datei. Die englische Fassung erzeugt der Autor daraus in seiner MySuricates-Instanz mit dem Workflow „README DE↔EN Sync"; im Repository liegen das Ergebnis und der deterministische Teil des Abgleichs (`scripts/readme-sync/readme_sync.py`), die Workflow-Definition selbst nicht. Wer von außen beiträgt, ändert also die deutsche Quelle und passt `README.md` im selben Commit von Hand an — oder ändert nur die deutsche und weist im Pull Request darauf hin, dass die Übersetzung noch nachzuziehen ist.
