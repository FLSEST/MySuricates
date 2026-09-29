**Deutsch** | [English](README.md)

<p align="center"><img src="docs/assets/readme-banner-de.svg" alt="MySuricates – Baue deine eigenen Workflows mit deinen Erdmännchen und lass sie dir helfen, jeden Schritt im Blick zu behalten. Deterministisch, wo es geht. Jeder Anbieter: Wechsle ihn und behalte deinen Ablauf. Behalte im Blick, was du getan hast und was du tust. Setze deine Erdmännchen ein, so wie du sie brauchst. Workflows, Ketten, Skills, Vorlagen, Zeitpläne, TODOs, Integrationen, Webhooks, Human-in-the-Loop, Assistent, BPMN, Versionen, Gates, Memory, Dashboard, Audit, hoch konfigurierbar, komplett individualisierbar, Features abschaltbar und mehr."></p>

# MySuricates

Ein Forschungsprojekt zur Frage, wie weit sich Softwareentwicklung mit KI-Agenten kontrolliert
betreiben laesst — und ein lauffaehiges System, an dem sich das beobachten laesst.

MySuricates ist eine lokale Workflow-Plattform: Workflows bestehen aus Bloecken, die entweder
deterministisch sind (Shell, Verzweigung, Schleife) oder eine KI aufrufen. Aus dieser Mischung ist
ein Regelkreis entstanden, der Befunde ueber die eigene Codebasis erzeugt, daraus TODOs macht,
Fix-Workflows anstoesst und das Ergebnis wieder prueft. Der Code ist weitgehend in KI-Sitzungen
entstanden: 1 641 von 1 825 Commits tragen mindestens einen KI-Trailer — 1 354 den von Claude,
340 den des Regelkreises selbst (`Generated-By: MySuricates`), 53 beide (Stand Commit `c421f2e3`).

**Was das nicht ist:** kein Produkt, kein Dienst, kein Support, keine Stabilitaetszusage. Die
Architekturschulden sind bekannt und werden im System selbst als Audit-Befunde gefuehrt — siehe
[Reifegrad](#reifegrad-und-bekannte-schulden).

## Schnellstart

Nur ausprobieren — dafuer braucht es nur Docker:

```bash
git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates
scripts/tryout.sh build          # baut das Image aus dem neuesten Release, beim ersten Mal einige Minuten
scripts/tryout.sh start          # http://localhost:9095 — die Ausgabe zeigt den API-Key fuer die Anmeldung
scripts/tryout.sh login claude   # optional, fuer KI-Bloecke; ebenso codex oder pi
```

Das Repository veroeffentlicht kein fertiges Image, `build` baut lokal aus dem Repository. Der
Container bindet nichts vom Rechner ein — keine Ordner, keine Anmeldungen; die KI-Anmeldung bleibt
in einem eigenen Docker-Volume. Dauerhaft einrichten: [Installation](#installation) weiter unten
oder Schritt fuer Schritt in [Getting Started](docs/GETTING-STARTED.md).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [Getting Started](docs/GETTING-STARTED.md) | Ausprobieren ohne Installation (Tryout-Image) und Erstinstallation mit dem gefuehrten Installer (Container, Rechner oder Dev · Test · Prod) |
| [Konzepte](docs/konzepte/README.md) | Acht Mechanismen zum Uebernehmen — Gates, Selbstverbesserung, Haertung u. a. —, je mit Ablaufdiagramm, Codebelegen und datierter Messung |
| [Workflow-Guide](docs/WORKFLOW-GUIDE.md) | Block-Typen, Patterns, Timeouts |
| [Architektur](docs/architektur.md) | Systemuebersicht, Schichten, Datenfluss, Sicherheit |
| [Diagramme](docs/diagramme.md) | Neun Seiten Ablauf- und Architekturdiagramme (Engine, Steuerung und Status, Scheduler, Skills, Memory, Request-Pfad, Frontend, Deployment, Logging und Monitoring) |
| [API-Referenz](docs/api-referenz.md) | REST-Endpunkte mit Request/Response |
| [Datenmodell](docs/datenmodell.md) | Entities, DB-Schema, Migrationen |
| [Frontend](docs/frontend.md) | Seiten, Komponenten, Hooks, Design-System |
| [Deployment](docs/deployment.md) | Docker, Konfiguration, Umgebungsvariablen, Vertrauensmodell |
| [Entwicklung](docs/entwicklung.md) | Lokales Setup, Build, Tests, Konventionen |
| [Entwickler-Onboarding](docs/onboarding/README.md) | Einstieg in den Code: Architektur, Code-Rundgang, Glossar, FAQ |
| [Integrations](docs/integrations.md) | Plugin-System, OAuth, Provider-Referenz |
| [Assistant](docs/assistant-system.md) | Chat-Assistent, Sessions, Suggestions |
| [Audit-Protokoll](docs/audit-workflow-protocol.md) | Wie Befunde entstehen, Gate-Status, TODO-Verknuepfung |
| [Belege](docs/belege.md) | Faelle, in denen ein Mechanismus etwas fand, das der normale Prozess uebersah — mit Run-Id und Zahl |
| [Vision](docs/vision.md) | Produktvision und Roadmap |

Fuer Agenten, die die REST-API direkt ansprechen, ist [AGENTS.md](AGENTS.md) der Einstieg.

## Leitideen

1. **Deterministisch, wo es geht**, auch wegen der Kosten: Ein Shell-Schritt kostet keine Tokens, KI nur dort, wo sie nötig ist. Frontier-Modelle helfen beim Bauen. Was gebaut ist, läuft deterministisch, und die verbleibenden, eng gefassten KI-Stellen können anders gefüttert werden (kleineres Modell, anderer Anbieter).
2. **Wiederverwenden und nachhalten:** Vorlagen, Pool, Sub-Workflows, Chains und Skills zum Wiederverwenden. Das TODO-System hält nach.
3. **Einfacher Zugang und Übersicht** über eine Oberfläche.
4. **Alles lässt sich zusammenstöpseln:** diverse Konzepte, weitere Quellen anbinden.
5. **Unabhängig vom Anbieter, so weit wie möglich:** Anbieter und Modell je Block wählbar. Die Kapselung der KI im Container ist noch nicht erreicht.

## Die Fragen dahinter

Vier Mechanismen sind gebaut, um jeweils eine Frage zu untersuchen. Gebaut heisst hier: sie laufen.
Es heisst nicht, dass die Frage beantwortet ist.

| Frage | Mechanismus im Code |
|---|---|
| Kann ein System den eigenen Verbesserungsbedarf feststellen und abarbeiten, ohne dass jemand den Anstoss gibt? | **Self-Improvement-Loop**: Audit-Workflow → maschinelle Bewertung durch ein Gate (letzte Zeile dieser Tabelle) → automatisch erzeugtes TODO → der `TodoTrigger` startet dazu einen Fix-Workflow → Verifikation. Drei Schichten verhindern, dass der Loop sich selbst nachlaeuft: ein Fix-Run ist als solcher markiert und darf selbst kein TODO erzeugen, je TODO ist die Zahl der Fix-Versuche begrenzt, und solange ein Fix-Run laeuft, startet kein zweiter. [Details](docs/architektur.md#todo-triggers--self-improvement-loop-v51v52) |
| Was muss ein Agent ueber ein Projekt wissen, und woher kommt dieses Wissen, wenn niemand es aufschreibt? | **Workflow-Memory**: aus abgeschlossenen Runs wird Wissen extrahiert und vor dem naechsten Lauf als Kontext eingespielt. |
| Wie behaelt man die Kontrolle ueber einen Agenten, der mehrere Schritte selbst plant? | **Agentische Bloecke** trennen Reason und Act — erst entscheidet ein Aufruf, was zu tun ist, ein zweiter fuehrt es aus — und laufen gegen harte Budgets: Iterationen, Zeit, kumulierte Tokens, Wiederholungserkennung. |
| Kann man die Qualitaet einer KI-Ausgabe maschinell bewerten, statt sie zu lesen? | **Gates**: Bloecke geben `gate_status: GREEN\|YELLOW\|RED` aus. RED erzeugt ein TODO mit hoher, YELLOW eines mit mittlerer Prioritaet. Die Bewertung steckt im Workflow, nicht in der Plattform. |

Die ehrlichste Antwort nach sieben Monaten: die Mechanismen tragen, aber sie brauchen Leitplanken,
und die Leitplanken sind der eigentliche Ertrag. Ein Gate, das nur ein Sprachmodell fragt, ob etwas
gut ist, liefert zuverlaessig Befunde — auch erfundene. Deterministische Messung vor KI-Bewertung ist
die wiederkehrende Lehre. Die Mechanismen einzeln, je mit Ablaufdiagramm, Codebelegen und Grenzen:
[Konzepte](docs/konzepte/README.md).

Nicht alles davon ist nach der Installation eingeschaltet. Randfunktionen sind Module, die beim
Start festgelegt werden. Das Preset `core` (Standard) enthaelt den Kern — Workflows, Bloecke,
Zeitplaene, TODOs. `research` ergaenzt die Mechanismen dieses Abschnitts und ihre Oberflaechen:
Chains (mehrere Workflows als eine Kette), den Chat-Assistenten, Learning (Auswertungen ueber
abgelaufene Runs — Kennzahlen und Empfehlungen), Workflow-Memory (Wissen aus Runs, das in spaetere
Runs eingespielt wird), das Audit Center (die Seite, auf der die Befunde der Audit-Workflows ueber
die Zeit gefuehrt werden) und BPMN-Export. `full` schaltet alles frei — darunter Integrationen,
Webhooks und Fernzugriff. Der Installer fragt danach; die vollstaendige Liste mit allen Modulen
steht in [Architektur](docs/architektur.md#module-system-startkonfiguration).

## Was da steht

Stand 2026-09-22 (Commit `d23a09ed`, Release v0.7.6 plus Nacharbeit), Commit-Zaehler und Zeilen
aus dem Arbeitsbaum an diesem Tag (Zeilen per `wc -l`, inkl. Leer- und Kommentarzeilen; Backend
`src/main/java`, Frontend `src` inkl. Tests). Testzahlen aus dem CI-Lauf zu diesem Commit,
datiert in der Tabelle:

| | |
|---|---|
| Entstanden seit | 2026-02-15, rund 1 800 Commits, ein Autor |
| Backend | Java 21 / Spring Boot 3.5, rund 73 000 Zeilen, 52 REST-Controller, 91 Flyway-Migrationen |
| Frontend | React 19 / TypeScript, rund 77 000 Zeilen |
| Tests | Backend 10 476, Frontend 1 580 (192 Dateien) — 0 Fehler, 0 uebersprungen, CI-Lauf 35777382298 vom 2026-09-22 |
| Bausteine | 20 Block-Typen, 54 Workflow-Templates, 20 Blaupausen (Grundgerueste zum Ausfuellen), 19 Built-in Skills, 18 Integrations-Provider |

Die Testzahlen stammen aus dem CI-Lauf zu genau diesem Commit und sind damit nachpruefbar. Die
CI-Definition (`.github/workflows/ci.yml`) faehrt seit dem 2026-09-12 beide Suiten bei jedem Push,
zusaetzlich zu Hygiene-Gate und Secret-Scan; seit dem 2026-09-21 auf Ubuntu 26.04. Seit dem
2026-09-29 entfaellt eine Suite, wenn die Aenderung sie nachweislich nicht beruehrt (etwa reine
Doku); einmal pro Woche laufen beide ohne Filter.

## Reifegrad und bekannte Schulden

Experimentell. Das System laeuft beim Autor taeglich auf einem Rechner und hatte am 2026-09-12
rund 2 400 Runs hinter sich — es ist bisher nicht unter Last, nicht mit mehreren Benutzern und
nicht ausserhalb eines vertrauenswuerdigen Netzes betrieben worden (Eigenangabe).

Die Schulden sind nicht versteckt, sondern gezaehlt. Das Audit Center — eine Seite der Anwendung,
in der die Befunde der Audit-Workflows ueber die Zeit gefuehrt werden — zaehlte am 2026-09-22 fuer
MySuricates selbst **322 Befunde** seit dem 2026-07-19: 29 offen (davon 1 kritisch, 4 hoch),
7 warten auf Gegenpruefung, 267 sind behoben, 18 als Risiko akzeptiert, 1 verworfen. Die offenen
betrafen an diesem Tag vor allem drei Felder: Struktur (eine zu grosse zentrale Ausfuehrungsklasse, stark
gekoppelte Services, Controller mit direktem Repository-Zugriff), Performance und Speicher
(Datenbankgroesse ohne Vacuum-Strategie, N+1-Kandidaten, Bundle-Groesse) und die Reife fuer eine
Veroeffentlichung. Wie diese Befunde entstehen und was die Statusse bedeuten, steht im
[Audit-Workflow-Protokoll](docs/audit-workflow-protocol.md).

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
Sandbox; die KI-CLIs fragen dabei standardmaessig nicht vor Werkzeugnutzung nach. Aus dem Quellcode
gestartet lauschen Backend und Frontend auf allen Netzwerk-Interfaces (die per Installer
eingerichtete Instanz bindet standardmaessig nur `127.0.0.1`), und einige Endpunkte brauchen
keinen API-Key. MySuricates
ist fuer den lokalen Betrieb durch eine vertrauenswuerdige Person gebaut — fuer Fernzugriff gehoert
ein VPN davor, kein offener Port. Vollstaendig:
[Vertrauensmodell und Netzwerk-Exposition](docs/deployment.md#vertrauensmodell-und-netzwerk-exposition).

## Tech-Stack

| Komponente | Technologie |
|---|---|
| Backend | Spring Boot 3.5, Java 21 |
| Frontend | React 19, TypeScript 6, Vite 8 |
| Datenbank | SQLite (WAL-Modus) + Flyway |
| Container | Docker (Java Docker Client 3.7.1) — optional, nur fuer Shell-Bloecke mit `container_image` |
| Styling | Tailwind CSS 4, CSS-Variablen |
| State | Zustand 5 + TanStack Query 5 |
| WebSocket | STOMP ueber SockJS |
| Sicherheit | API-Key-Auth, Rate Limiting, Zulassungspruefung vor jedem Run; optional HMAC-signierte Workflow-Siegel |
| i18n | Deutsch / Englisch |
| PWA | Vite PWA Plugin |
| Integrationen | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Gitea/Forgejo, GitLab, Taskwarrior — Modul, im Preset `full` |

Ausgefuehrt wird dort, wo die Instanz laeuft — im Container (empfohlen) oder direkt auf dem
Rechner: Shell-Bloecke ueber die Shell des Benutzers, KI-Bloecke ueber die installierte CLI (im
Container vom Rechner eingebunden). Einen eigenen Container je
Shell-Block (`container_image`) gibt es nur, wenn der Docker-Socket ausdruecklich freigegeben ist.
Was von den KI-Anbietern in den Container kommt, bestimmt ein Schalter: standardmaessig nur die
Programme und je Anbieter die Anmeldung (fuer Claude einmal ein Token aus `claude setup-token`), nicht
die Einstellungen, Plugins, Memory oder Verlaeufe des Rechners. Wer weniger oder alles teilen will,
findet die Varianten unter [KI-Anbieter im Container](docs/deployment.md#ki-anbieter-im-container).

## Installation

> Neuer Rechner (Linux, macOS, Windows ueber WSL2), evtl. ohne Java/Node/Docker? → **[Getting Started](docs/GETTING-STARTED.md)**
> fuehrt Schritt fuer Schritt durch das Setup. Kurzform: `git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates && ./scripts/setup.sh`.

**Voraussetzung fuer KI-Bloecke:** Mindestens eine der unterstuetzten Kommandozeilen muss auf dem
Rechner installiert und angemeldet sein — `claude` (Anthropic), `codex` (OpenAI) oder `pi`. Ohne
eine davon laufen nur die deterministischen Bloecke (Shell, Verzweigung, Schleife). Der Installer
zeigt an, welche CLIs er gefunden hat und ob sie angemeldet sind, verlangt aber keine — ohne CLI
scheitern KI-Bloecke erst zur Laufzeit.

> **Zum Namen:** Das Projekt heisst MySuricates, einige Skripte tragen noch den frueheren Namen
> `autotrigger` (etwa `scripts/start-autotrigger-dev.sh`). Beides meint dasselbe; die Umbenennung
> steht aus.

Dauerhaft einrichten:

```bash
git clone https://github.com/FLSEST/MySuricates.git
cd MySuricates
./scripts/setup.sh              # gefuehrt, auf Englisch; auf Deutsch: ./scripts/setup.sh --lang de
```

Der Installer fragt nach dem Weg — eine Instanz im Container (empfohlen), direkt auf dem Rechner
oder gestuft **Dev · Test · Prod** — und nach den Modulen, installiert fehlende Werkzeuge nach
Rueckfrage, baut und startet die Instanz. Danach: http://localhost:9090, den API-Key zeigt
`scripts/instance.sh key --name main`. Die Instanz lauscht nur auf `127.0.0.1`; der Docker-Socket
wird nur auf ausdruecklichen Wunsch eingebunden.

Wer an MySuricates selbst entwickelt: `./scripts/setup.sh --from-source --start` richtet ein, baut
das Runtime-JAR und startet damit gleich `./scripts/start-autotrigger-dev.sh` — Backend auf :9090
und Vite mit Hot Reload auf :3000, in einer Instanz. Ohne `--start` endet der Installer mit
demselben Befehl als Hinweis; von da an ist er der Weg, die Dev-Instanz zu starten, zu stoppen
(`stop`) und nach einer Aenderung neu zu bauen (`restart`). Er ist idempotent und startet keine
zweite Instanz. Siehe [Entwicklung](docs/entwicklung.md).

<!-- Screenshot-Platzhalter -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Beiträge und Support

Issues bleiben offen — aber dieses Repository ist ein Forschungsprojekt eines
einzelnen Entwicklers, kein betreutes Produkt: kein Support, keine Zusage zu
Reaktionszeiten, auch nicht auf Pull Requests. Wer trotzdem etwas beitragen
will, meldet sich am besten zuerst per Issue.

Sicherheitslücken bitte **nicht** als öffentliches Issue melden, sondern über
den Meldeweg in [SECURITY.md](SECURITY.md) (private Sicherheitshinweise von
GitHub).

---

> 🌍 **README.de.md ist die Quelle, `README.md` die Übersetzung.** Inhaltliche Änderungen gehören in diese deutsche Datei. Die englische Fassung erzeugt der Autor daraus in seiner MySuricates-Instanz mit dem Workflow „README DE↔EN Sync"; im Repository liegt nur das Ergebnis, der Workflow selbst nicht. Wer von außen beiträgt, ändert also die deutsche Quelle und passt `README.md` im selben Commit von Hand an — oder ändert nur die deutsche und weist im Pull Request darauf hin, dass die Übersetzung noch nachzuziehen ist.
