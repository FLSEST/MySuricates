**Deutsch** | [English](README.md)

# MySuricates

Ein Forschungsprojekt zur Frage, wie weit sich Softwareentwicklung mit KI-Agenten kontrolliert
betreiben laesst — und ein lauffaehiges System, an dem sich das beobachten laesst.

MySuricates ist eine lokale Workflow-Plattform: Workflows bestehen aus Bloecken, die entweder
deterministisch sind (Shell, Verzweigung, Schleife) oder eine KI aufrufen. Aus dieser Mischung ist
ein Regelkreis entstanden, der Befunde ueber die eigene Codebasis erzeugt, daraus TODOs macht,
Fix-Workflows anstoesst und das Ergebnis wieder prueft. Der Code ist weitgehend in KI-Sitzungen
entstanden, ein Teil davon ueber diesen Regelkreis selbst.

**Was das nicht ist:** kein Produkt, kein Dienst, kein Support, keine Stabilitaetszusage. Die
Architekturschulden sind bekannt und werden im System selbst als Audit-Befunde gefuehrt — siehe
[Reifegrad](#reifegrad-und-bekannte-schulden).

## Die Fragen dahinter

Vier Mechanismen sind gebaut, um jeweils eine Frage zu untersuchen. Gebaut heisst hier: sie laufen.
Es heisst nicht, dass die Frage beantwortet ist.

| Frage | Mechanismus im Code |
|---|---|
| Kann ein System den eigenen Verbesserungsbedarf feststellen und abarbeiten, ohne dass jemand den Anstoss gibt? | **Self-Improvement-Loop**: Audit-Workflow → Gate-Bewertung → automatisch erzeugtes TODO → `TodoTrigger` startet einen Fix-Workflow → Verifikation. Drei Schichten verhindern, dass der Loop sich selbst nachlaeuft. |
| Was muss ein Agent ueber ein Projekt wissen, und woher kommt dieses Wissen, wenn niemand es aufschreibt? | **Workflow-Memory**: aus abgeschlossenen Runs wird Wissen extrahiert und vor dem naechsten Lauf als Kontext eingespielt. |
| Wie behaelt man die Kontrolle ueber einen Agenten, der mehrere Schritte selbst plant? | **Agentische Bloecke** trennen Reason und Act und laufen gegen harte Budgets: Iterationen, Zeit, kumulierte Tokens, Wiederholungserkennung. |
| Kann man die Qualitaet einer KI-Ausgabe maschinell bewerten, statt sie zu lesen? | **Gates**: Bloecke geben `gate_status: GREEN\|YELLOW\|RED` aus. RED erzeugt ein TODO mit hoher, YELLOW eines mit mittlerer Prioritaet. Die Bewertung steckt im Workflow, nicht in der Plattform. |

Die ehrlichste Antwort nach sieben Monaten: die Mechanismen tragen, aber sie brauchen Leitplanken,
und die Leitplanken sind der eigentliche Ertrag. Ein Gate, das nur ein Sprachmodell fragt, ob etwas
gut ist, liefert zuverlaessig Befunde — auch erfundene. Deterministische Messung vor KI-Bewertung ist
die wiederkehrende Lehre.

## Was da steht

Stand 2026-09-19, Commit-Zaehler und Zeilen aus dem Arbeitsbaum an diesem Tag (Zeilen per
`wc -l`, inkl. Leer- und Kommentarzeilen). Testzahlen aus einem Lauf vom 2026-09-18 in einem
isolierten Worktree:

| | |
|---|---|
| Entstanden seit | 2026-02-15, rund 1 600 Commits, ein Autor |
| Backend | Java 21 / Spring Boot 3.4, rund 67 000 Zeilen, 47 REST-Controller, 77 Flyway-Migrationen |
| Frontend | React 19 / TypeScript, rund 66 000 Zeilen |
| Tests | Backend 9 197 (0 Fehler, 1 uebersprungen), Frontend 1 367 — Lauf vom 2026-09-19 |
| Bausteine | 20 Block-Typen, 66 Workflow-Templates, 19 Built-in Skills, 7 Integrations-Provider |

Die Testzahlen sind ein datierter Befund. Dass sie es bleiben, prueft seit dem 2026-09-12 die CI:
sie faehrt beide Suiten bei jedem Push, zusaetzlich zu Hygiene-Gate und Secret-Scan. Ein CI-Lauf
auf einem externen Git-Host steht noch aus.

## Reifegrad und bekannte Schulden

Experimentell. Das System laeuft taeglich auf einem Rechner und hatte am 2026-09-12 rund 2 400
Runs hinter sich — es ist nie unter Last, nie mit mehreren Benutzern und nie ausserhalb eines
vertrauenswuerdigen Netzes betrieben worden.

Die Schulden sind nicht versteckt, sondern gezaehlt. Das Audit Center fuehrte am 2026-09-12
**89 Befunde**:
26 offen (davon 1 kritisch, 4 hoch), 25 warten auf Verifikation, 38 sind erledigt. Inhaltlich fast
durchgehend Struktur: zu grosse und zu stark gekoppelte Services und UI-Komponenten, unscharfe
Controller-Repository-Grenzen, Speicher- und Retention-Themen. Wie diese Befunde entstehen und was
die Statusse bedeuten, steht im
[Audit-Workflow-Protokoll](docs/audit-workflow-protocol.md).

Bekannte harte Grenzen:

- **SQLite mit genau einer Verbindung.** Der Connection-Pool ist bewusst auf 1 gesetzt, weil SQLite
  nur einen Schreiber kennt. Das serialisiert alle Datenbankzugriffe und begrenzt die Parallelitaet.
- **Keine Mandantenfaehigkeit.** Ein API-Key, ein Benutzer, keine Rollen.
- **Die KI-Aufrufe haengen an lokal installierten CLIs** (`claude`, `codex`, `pi`) samt deren
  Anmeldung — es gibt keinen API-Schluessel-Pfad als Alternative.

## Bevor du es startest

Shell-Bloecke und KI-Aufrufe laufen **auf dem Host mit deinen Rechten**, nicht in einem Container;
die Claude CLI fragt dabei standardmaessig nicht vor Werkzeugnutzung nach. Backend und Frontend
lauschen auf allen Netzwerk-Interfaces, und einige Endpunkte brauchen keinen API-Key. MySuricates
ist fuer den lokalen Betrieb durch eine vertrauenswuerdige Person gebaut — fuer Fernzugriff gehoert
ein VPN davor, kein offener Port. Vollstaendig:
[Vertrauensmodell und Netzwerk-Exposition](docs/deployment.md#vertrauensmodell-und-netzwerk-exposition).

## Tech-Stack

| Komponente | Technologie |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript, Vite 7 |
| Datenbank | SQLite (WAL-Modus) + Flyway |
| Container | Docker (Java Docker Client 3.4.1) — optional, nur fuer Container-Bloecke |
| Styling | Tailwind CSS 4.2, CSS-Variablen |
| State | Zustand 4.4 + TanStack Query 5 |
| WebSocket | STOMP ueber SockJS |
| Sicherheit | API-Key-Auth, Rate Limiting |
| i18n | Deutsch / Englisch |
| PWA | Vite PWA Plugin |
| Integrationen | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Taskwarrior |

Ausgefuehrt wird **auf dem Host**: Shell-Bloecke ueber die Shell des Benutzers, KI-Bloecke ueber die
installierte CLI. Docker ist optional und wird nur gebraucht, wenn ein Shell-Block ausdruecklich ein
`container_image` setzt.

## Quickstart

> Neuer Rechner (Linux, macOS, Windows ueber WSL2), evtl. ohne Java/Node/Docker? → **[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)**
> fuehrt Schritt fuer Schritt durch das Setup. Kurzform: `git clone <repository-url> autotrigger && cd autotrigger && ./scripts/setup.sh`.

```bash
git clone <repository-url> mysuricates
cd mysuricates
./scripts/setup.sh              # gefuehrt, auf Englisch; auf Deutsch: ./scripts/setup.sh --lang de
```

Der Installer fragt nach dem Weg — eine Instanz im Container (empfohlen), direkt auf dem Rechner
oder gestuft **Dev · Test · Prod** —, installiert fehlende Werkzeuge nach Rueckfrage, baut und
startet die Instanz. Danach: http://localhost:9090, den API-Key zeigt
`scripts/instance.sh key --name main`. Die Instanz lauscht nur auf `127.0.0.1`; der Docker-Socket
wird nur auf ausdruecklichen Wunsch eingebunden.

Wer an MySuricates selbst entwickelt: `./scripts/setup.sh --from-source --start`, danach
`./scripts/start-autotrigger-dev.sh` (Backend :9090, Vite mit Hot Reload :3000) — siehe
[docs/entwicklung.md](docs/entwicklung.md).

<!-- Screenshot-Platzhalter -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [Getting Started](docs/GETTING-STARTED.md) | Erstinstallation mit dem gefuehrten Installer (Container, Rechner oder Dev · Test · Prod) |
| [Architektur](docs/architektur.md) | Systemuebersicht, Schichten, Datenfluss, Sicherheit |
| [Diagramme](docs/diagramme.md) | Neun Seiten Ablauf- und Architekturdiagramme (Engine, Scheduler, Skills, Memory, Request-Pfad, Frontend, Deployment) |
| [API-Referenz](docs/api-referenz.md) | REST-Endpunkte mit Request/Response |
| [Datenmodell](docs/datenmodell.md) | Entities, DB-Schema, Migrationen |
| [Frontend](docs/frontend.md) | Seiten, Komponenten, Hooks, Design-System |
| [Deployment](docs/deployment.md) | Docker, Konfiguration, Umgebungsvariablen, Vertrauensmodell |
| [Entwicklung](docs/entwicklung.md) | Lokales Setup, Build, Tests, Konventionen |
| [Integrations](docs/integrations.md) | Plugin-System, OAuth, Provider-Referenz |
| [Workflow-Guide](docs/WORKFLOW-GUIDE.md) | Block-Typen, Patterns, Timeouts |
| [Assistant](docs/assistant-system.md) | Chat-Assistent, Sessions, Suggestions |
| [Audit-Protokoll](docs/audit-workflow-protocol.md) | Wie Befunde entstehen, Gate-Status, TODO-Verknuepfung |
| [Belege](docs/belege.md) | Faelle, in denen ein Mechanismus etwas fand, das der normale Prozess uebersah — mit Run-Id und Zahl |
| [Vision](docs/vision.md) | Produktvision und Roadmap |

Fuer Agenten, die die REST-API direkt ansprechen, ist [AGENTS.md](AGENTS.md) der Einstieg.

## Todo-Workflow-Verknuepfung

Todos koennen optional mit einem Resolver-Workflow verknuepft werden. Die Verknuepfung ist bewusst nicht global und startet nichts automatisch.

- `linkedWorkflowId` am Todo ist optional.
- Ein verlinkter Workflow wird erst durch die manuelle Aktion `Loesen` gestartet.
- Beim Start wechselt das Todo auf `IN_PROGRESS` und `resolutionState=RUNNING`.
- Bei erfolgreichem Workflow-Run wird das Todo automatisch auf `DONE` gesetzt.
- Bei einem fehlgeschlagenen Run springt das Todo auf den vorherigen Status zurueck und `resolutionState=FAILED`.

Abgrenzung:
- Das ist nicht dasselbe wie der bestehende `TodoTrigger`-Mechanismus.
- `TodoTrigger` startet Workflows regelbasiert/automatisch.
- Die Todo-Workflow-Verknuepfung ist aktuell ein manueller, optionaler Resolve-Flow pro Todo.

## Beiträge und Support

Issues bleiben offen — aber dieses Repository ist ein Forschungsprojekt eines
einzelnen Entwicklers, kein betreutes Produkt: kein Support, keine Zusage zu
Reaktionszeiten, auch nicht auf Pull Requests. Wer trotzdem etwas beitragen
will, meldet sich am besten zuerst per Issue.

Sicherheitslücken bitte **nicht** als öffentliches Issue melden, sondern über
den Meldeweg in [SECURITY.md](SECURITY.md) (private Sicherheitshinweise von
GitHub).

---

> 🌍 Die englische `README.md` wird vom Workflow **„README DE↔EN Sync"** aus dieser Datei generiert. Bei inhaltlichen Änderungen diese deutsche Quelle bearbeiten und den Workflow laufen lassen. Der Workflow selbst gehört zur lokalen MySuricates-Instanz und ist nicht Teil dieses Repositories.
