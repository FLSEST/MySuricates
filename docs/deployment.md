# Deployment

## Eine Instanz im Container (`deploy/`)

Auf dem Rechner ist nur Docker nötig. `deploy/Dockerfile` (Ziel `runtime`) baut Backend und Frontend in Docker
(beim ersten Mal etwa acht Minuten, danach aus dem Schichten-Cache), das Backend liefert das
Frontend selbst aus — eine Instanz ist ein Container und ein Port.

```bash
printf 'MYSURICATES_PORT=9090\n' > deploy/instances/main.env
scripts/instance.sh up --name main       # bauen, starten, auf Health warten
scripts/instance.sh key --name main      # API-Key für den Login
scripts/instance.sh list                 # alle Instanzen mit Port
scripts/instance.sh down --name main     # stoppen; Daten bleiben in den Volumes, laufende Runs brechen ab
```

| Wert in `deploy/instances/<name>.env` | Bedeutung |
|---|---|
| `MYSURICATES_PORT` | Port auf dem Rechner (Pflicht) |
| `MYSURICATES_BIND` | Adresse, Default `127.0.0.1` — nur lokal erreichbar |
| `MYSURICATES_PRESET` | `core` (Default), `research`, `full` |
| `MYSURICATES_PROJECTS` | Projektordner, Default: Ordner über dem Klon, nie ganz `$HOME` |
| `MYSURICATES_DOCKER_SOCKET` | `1` bindet den Docker-Socket ein (für Shell-Blöcke mit `container_image`) — gibt der Instanz volle Kontrolle über Docker auf dem Rechner |
| `MYSURICATES_AI_FROM_HOST` | was von den KI-Anbietern des Rechners in den Container kommt: `login` (Default), `programs`, `all` — siehe [KI-Anbieter im Container](#ki-anbieter-im-container) |
| `MYSURICATES_AI_PROVIDERS` | Auswahl aus `claude,codex,pi`, Default: alle vorhandenen |
| `MYSURICATES_HARDENING_SEALING`, `MYSURICATES_HARDENING_REQUIRE_SEALED` | Härtung, `true`/`false` |
| `MYSURICATES_ENVIRONMENT` | Anzeigename, z. B. `Prod` |
| `MYSURICATES_APT_REFRESH` | ein neuer Wert (etwa der Monat) holt beim nächsten `up` NodeSource- und Docker-Repo des Images frisch, statt Schlüssel, Paketlisten und Pakete aus dem Schichten-Cache des ersten Baus zu nehmen; geht auch als Umgebungsvariable (8b7a39b9) |

Das Image entsteht aus den getrackten Dateien des Klons (`git ls-files`), wie sie im Arbeitsbaum
liegen: Geänderte getrackte Dateien kommen mit, was nur lokal liegt — etwa per `.git/info/exclude`
ausgeschlossene Pool-Definitionen —, nicht. Eine eigene Pool-Datei erscheint im Container also erst,
wenn sie committet oder zumindest mit `git add` aufgenommen ist.

Die Instanz nutzt die KI-CLIs des Rechners, man meldet sich nicht doppelt an. Wie viel davon in den
Container kommt, bestimmt `MYSURICATES_AI_FROM_HOST` ([KI-Anbieter im Container](#ki-anbieter-im-container));
unter macOS kommen nur Anmeldungen, keine Programme (eine Mac-Binary startet im Linux-Container nicht).
Die Benutzer-ID kommt beim Start aus `id(1)`, Dateien in eingebundenen
Ordnern gehören also dem richtigen Benutzer. Daten liegen im Volume `mysuricates-<name>-data`,
Integrations- und Siegel-Schlüssel getrennt davon in `mysuricates-<name>-secrets`. Ohne Docker-Socket meldet
die Instanz Docker als „nicht eingerichtet“ — das ist kein Fehler.

Mehrere Instanzen — etwa Dev · Test · Prod — sind mehrere Env-Dateien mit eigenen Ports. Stable und
Test (`docker-compose.stable.yml`, `docker-compose.test.yml`) bleiben eigene Dateien, holen KI-Anbieter
und Docker-Socket aber aus derselben Stelle. Skript-Tests ohne Docker:
`bash scripts/tests/test-host-mounts.sh`, `bash scripts/tests/test-instance.sh`,
`bash scripts/tests/test-deploy-files.sh`, `bash scripts/tests/test-staging-override.sh`.

## KI-Anbieter im Container

Was ein Container vom Rechner bekommt, entscheidet eine Stelle: `scripts/lib/host-mounts.sh` (262948a4).
Sie gilt für Instanzen über `instance.sh`, für Stable und Test über `staging.sh` — auch wenn sie aus
der Oberfläche gestartet werden — und für den Dev-Container. Die Schalter stehen außerhalb der App und
wirken erst beim nächsten Start: Weder ein Workflow noch jemand mit dem API-Key kann sie ändern. Die
Karte „Startkonfiguration“ in den Einstellungen zeigt sie an, nur lesend.

| `…AI_FROM_HOST` | Vom Rechner im Container | Einrichtung | Was eine KI im Container damit kann |
|---|---|---|---|
| `login` (Standard) | die Programme, nur lesend; je Anbieter genau die Anmeldung: Codex `~/.codex/auth.json`, Pi `~/.pi/agent/auth.json`, Claude ein Token (unten) | für Claude einmal `claude setup-token` | die Anmeldung nutzen und auslesen. Einstellungen, Hooks, Plugins, Memory und Verläufe des Rechners bleiben draußen. |
| `programs` | nur die Programme | je Instanz einmal anmelden: `scripts/instance.sh login --name X claude` bzw. `scripts/staging.sh login stable claude` | am wenigsten. Jede Anmeldung liegt im Volume der Instanz und lässt sich einzeln widerrufen. |
| `all` | ganz `~/.claude`, `~/.codex`, `~/.pi` mit Schreibrecht, dazu `~/.local/bin` — so war es bisher | keine | Einstellungen, Hooks, Plugins, Memory und Verläufe aller Projekte sehen und ändern; was dort geändert wird, läuft später auf dem Rechner. Sinnvoll, wenn Workflows die eigenen Claude-Plugins oder MCP-Server brauchen. |

`…AI_PROVIDERS` wählt die Anbieter aus `claude,codex,pi` (Standard: alle); die übrigen bekommen weder
Programm noch Anmeldung. Mit `login` und `programs` hat jeder Anbieter ein eigenes Verzeichnis im
Daten-Volume (`/app/data/providers/<anbieter>`, über `CLAUDE_CONFIG_DIR`, `CODEX_HOME` und
`PI_CODING_AGENT_DIR`): Was die Programme dort schreiben — Sitzungen, Caches —, erreicht den Rechner nicht.

**Warum Claude einen Token braucht.** Claude ersetzt `~/.claude/.credentials.json` bei jedem Erneuern der
Anmeldung (neue Datei, dann Umbenennen). Eine einzeln eingebundene Datei zeigte im Container danach
weiter auf die alte, und nach der nächsten Erneuerung auf dem Rechner wäre der Container abgemeldet.
Codex und Pi schreiben ihre `auth.json` an Ort und Stelle, dort genügt die Datei. Den Token erzeugt
`claude setup-token` einmal (er gilt ein Jahr); er gehört als `CLAUDE_CODE_OAUTH_TOKEN=…` nach
`~/.config/mysuricates/ai.env` mit den Rechten 600, der Installer bietet das an. Compose reicht die
Datei beim Start als Umgebung durch. Sie landet weder im Image noch im Volume, ist aber für jeden mit
Docker-Zugriff per `docker inspect` lesbar. Als Anmeldung zählt nur ein Wert, der ein Token sein kann:
mindestens 40 Zeichen, ohne Leerzeichen und ohne spitze Klammern. Ein Platzhalter wie `<token>` bleibt
draußen; Starter, Installer und die Einstellungen melden ihn als fehlende Anmeldung.

**Anmeldung per Umgebungsvariable.** Auf dem Rechner zählt eine gesetzte Variable wie `OPENAI_API_KEY`,
`ANTHROPIC_API_KEY` oder `GEMINI_API_KEY` als Anmeldung. In den Container kommt sie nicht: Die Schalter
binden Anmeldedateien ein, für Claude den Token aus `ai.env`. Der Installer sagt das bei einer
Container-Instanz, statt „angemeldet“ stehen zu lassen, und nennt den Befehl, der die Anmeldedatei
anlegt (`codex login`, `pi` → `/login`); beim Start meldet `instance.sh` eine fehlende Datei (90b4422c).

**Projekt-Container.** Für einen Lauf startet die Plattform je Projekt einen Container
(`automation-<projekt-id>`, sobald Docker erreichbar ist, bei Stable und Test über den Socket). Darin
läuft Claude für Befehle mitten im Lauf und im Terminal. Er folgt derselben Regel wie seine Instanz:
Bei `login` bekommt er das Claude-Programm als einzelne Datei und den Token (aus der Umgebung der
Instanz oder aus `~/.config/mysuricates/ai.env`), dazu ein eigenes Konfigurationsverzeichnis auf seinem
`/tmp`, das mit dem Container verschwindet. Bei `programs` bekommt er keinen Token. Nur bei `all`
bekommt er wie früher `~/.claude` und `~/.claude.json` mit Schreibrecht. Ohne `claude` in `…AI_PROVIDERS`
bekommt er nichts von Claude.

| Weg | Schalter | Docker-Socket ohne Schalter |
|---|---|---|
| `scripts/instance.sh`, auch der Installer-Modus „Dev · Test · Prod“ | `MYSURICATES_AI_FROM_HOST`, `MYSURICATES_AI_PROVIDERS`, `MYSURICATES_DOCKER_SOCKET` in `deploy/instances/<name>.env` | aus |
| Stable und Test (`scripts/staging.sh`, Start aus der Oberfläche) | `STABLE_…` bzw. `TEST_…` mit `AI_FROM_HOST`, `AI_PROVIDERS`, `DOCKER_SOCKET` in `~/.config/mysuricates/staging.env` — zeilenweise gelesen, nie ausgeführt; eine gleichnamige Umgebungsvariable gewinnt | an |
| Dev-Container (`scripts/start-autotrigger-dev.sh --container`) | `AUTOTRIGGER_DEV_AI_FROM_HOST`, `AUTOTRIGGER_DEV_AI_PROVIDERS`, `AUTOTRIGGER_DEV_DOCKER_SOCKET` | an |

Mit dem Socket kann ein Workflow per `docker run -v /:/host …` den ganzen Rechner erreichen; die Grenze
oben schützt dann nur vor Versehen. Stable, Test und der Dev-Container behalten ihn, damit
Container-Blöcke dort weiterlaufen — wer die Grenze ernst nimmt, schaltet ihn dort aus.

Grenzen: Ein per npm installiertes Claude (statt des nativen Installers) wird mit `login` und `programs`
nicht eingebunden. Unter macOS kommen keine Programme (3dd32594). Codex' eigene Sandbox
(`--sandbox read-only`) startet weder auf dem Rechner noch im Container (`bwrap`), unabhängig von diesen
Schaltern. Tests ohne Docker: `bash scripts/tests/test-host-mounts.sh`,
`bash scripts/tests/test-staging-override.sh`.

## Tryout-Image

`deploy/Dockerfile` hat zwei Ziele. `runtime` ist das Image einer eingerichteten Instanz (oben; die
Werkzeuge und KI-Anmeldungen kommen vom Rechner). `tryout` baut darauf auf und bringt alles selbst
mit, damit man MySuricates ohne Installation ausprobieren kann: KI-CLIs (pi in derselben festen Version
wie der Installer, [entwicklung.md → pi-Version](entwicklung.md#pi-version-installer-und-tryout)), `python3-pip`/`-venv`, JDK
und Maven aus der Build-Stufe (`JAVA_HOME=/opt/java/jdk`), Preset `research`. `HOME` liegt auf dem
Daten-Volume (`/app/data/home`), Anmeldungen und API-Key überstehen also einen Neuaufbau. Bewusst
nicht enthalten: gcc/make, Go, Rust, Gradle — wer mehr braucht, baut `FROM mysuricates:tryout`.

```bash
scripts/tryout.sh build [--ref REF]   # neuestes vX.Y.Z-Tag (nach Version), sonst REF, z. B. HEAD
scripts/tryout.sh start [--port N]    # Container mysuricates-tryout, 127.0.0.1:9095
scripts/tryout.sh login [claude|codex|pi] | key | shell | status | logs | stop
scripts/tryout.sh remove [--data]     # --data löscht auch mysuricates-tryout-data/-secrets
scripts/tryout.sh export [DATEI]      # docker save beider Tags → .tar.gz für Tester
```

Gebaut wird aus `git archive <REF>`, nie aus dem Arbeitsverzeichnis: Das Image entspricht genau dem
Tag, Uncommittetes und die lokale `config/` kommen nicht hinein. Das Dockerfile stammt dabei aus dem
Tag selbst; Tags vor v0.7.6 kennen die Stufe `tryout` noch nicht und werden abgewiesen. Getaggt
wird `mysuricates:<version>-tryout` und `mysuricates:tryout` (zuletzt gebaut), mit den Labels
`org.opencontainers.image.version`/`.revision`. Für eine neue Version: `build`, dann
`remove` (Daten bleiben) und `start`. `MYSURICATES_APT_REFRESH=$(date +%Y-%m) scripts/tryout.sh build`
holt NodeSource- und Docker-Repo frisch statt aus dem Schichten-Cache — und damit auch die KI-CLIs.

Der Container bindet nichts vom Rechner ein (kein Projektordner, kein Docker-Socket, keine
Anmeldung) — ein Shell-Block oder `--dangerously-skip-permissions` wirkt nur im Container. Der
Instanzname `tryout` ist für `instance.sh` reserviert, damit sich Container und Volumes nicht
überschneiden. Ein Registry-Image ist ein eigener Schritt (4c992a25). Tests ohne Docker:
`bash scripts/tests/test-tryout.sh`.

## Docker Compose

Das Projekt wird über Docker Compose deployt. Die Konfiguration liegt in `ai-automation-backend/docker-compose.yml`.

### Starten

```bash
cd ai-automation-backend

# 1. JAR bauen
mvn clean package -DskipTests

# 2. Backend starten
docker compose up --build -d

# 3. Agent-Images bauen — der Monat im Wert frischt die Fremd-Repos von agent-devops auf
AGENT_APT_REFRESH=$(date +%Y-%m) docker compose --profile build-only build
```

Das Backend ist dann unter `http://localhost:9090` erreichbar.

### Services

| Service | Image | Port | Beschreibung |
|---|---|---|---|
| `backend` | `Dockerfile.backend` | 9090 | Spring Boot Backend |
| `agent-node` | `Dockerfile.agent-node` | - | Node.js 22 Agent-Container |
| `agent-python` | `Dockerfile.agent-python` | - | Python 3.12 Agent-Container |
| `agent-devops` | `Dockerfile.agent-devops` | - | Ubuntu 24.04 Agent-Container (gh, trivy, gitleaks, docker-cli) |
| `agent-java` | `Dockerfile.agent-java` | - | Temurin 21 Agent-Container (maven, LibreOffice) |

Die Agent-Images werden nur gebaut (`profiles: build-only`), nicht direkt über Compose gestartet. Das Backend erstellt und verwaltet Agent-Container dynamisch über die Docker API.

## Dockerfiles

### Backend (`docker/Dockerfile.backend`)

- **Base:** `eclipse-temurin:21-jre-alpine`
- **User:** `appuser` (UID 1000, nicht-root)
- **JVM:** ZGC (Generational), 256MB–1GB Heap
- **Extras:** Tailscale CLI (pinned Version + SHA256-Verifizierung, multi-arch amd64/arm64), sudo
- **Healthcheck:** `curl -f http://localhost:9090/api/v1/system/health`

### Agent Node (`docker/Dockerfile.agent-node`)

- **Base:** `node:22-slim`
- **Tools:** git, curl, jq
- **User:** `agent` (UID 1000)
- **Claude CLI:** Verzeichnisse vorbereitet (Binary + Config vom Host gemountet)
- **CMD:** `tail -f /dev/null` (Container bleibt aktiv für `docker exec`)

### Agent Python (`docker/Dockerfile.agent-python`)

- **Base:** `python:3.12-slim`
- **Tools:** git, curl, jq
- **Pakete:** anthropic, pandas, numpy, requests, beautifulsoup4 — ungepinnt, hinter `ARG PIP_REFRESH`:
  Derselbe `AGENT_APT_REFRESH` wie bei agent-devops installiert die aktuellen, sonst blieben sie auf dem
  Stand des ersten Baus (8b7a39b9)

### Agent DevOps (`docker/Dockerfile.agent-devops`)

- **Base:** `ubuntu:24.04`
- **Tools:** git, curl, jq, bash, python3
- **GitHub CLI:** `gh` aus dem offiziellen APT-Repository
- **Security-Tools:** gitleaks, trivy (Binaries mit SHA256-Verifizierung, multi-arch)
- **Docker:** Docker CE CLI (nur Client, kein Daemon)
- **Frische:** Die Layer mit Fremd-Repos (gh, Docker) stehen zuletzt, hinter `ARG APT_REFRESH`. Ein
  neuer Wert (Compose: `AGENT_APT_REFRESH`, etwa der Monat) baut sie neu, statt Schlüssel und
  Paketlisten aus einem alten Layer-Cache zu übernehmen — daran scheiterte ein Build mit `NO_PUBKEY`,
  obwohl der aktuelle Schlüsselbund stimmte (97e1529d). gitleaks und trivy sind fest gepinnt.
- **User:** `agent` (UID 1000)
- **CMD:** `tail -f /dev/null`

### Agent Java (`docker/Dockerfile.agent-java`)

- **Base:** `eclipse-temurin:21-jdk-jammy`
- **Tools:** maven, git, curl, jq, bash, python3, libreoffice-writer (headless DOCX→PDF)
- **Fonts:** fonts-liberation, fonts-dejavu
- **User:** `agent` (UID 1000)
- **CMD:** `tail -f /dev/null`

### Stable (`docker/Dockerfile.stable`)

- **Base:** `eclipse-temurin:21-jre-jammy` (Ubuntu-basiert, glibc für Claude CLI)
- **ARGs:** `HOST_USER`, `HOST_UID`, `HOST_GID`, `HOST_HOME` — für User-Mapping mit dem Host
- **Tools:** curl, git, gosu, python3, jq, nodejs, npm, wget, unzip, Docker CE CLI
- **Entrypoint:** `entrypoint.sh` (User-Switching via gosu, Docker-Socket-Permissions)
- **Port:** 9091
- **Healthcheck:** `curl -f http://localhost:9091/api/v1/system/health`
- **JVM:** ZGC (Generational), 256MB–1GB Heap, headless AWT

## Docker Compose Files

### `docker-compose.yml` (Haupt)

Backend + Agent-Images (build-only). Netzwerk: `automation` (Bridge).

### `docker-compose.stable.yml`

Stable-Umgebung auf Port 9091:
- Container: `mysuricates-stable`
- Profil: `SPRING_PROFILES_ACTIVE=stable`
- Volumes: `stable-data` (persistente DB), `stable-secrets` (Integrations- und Siegel-Schlüssel, getrennt von der DB — 3fb2242c), `stable-frontend/` (read-only), Host-Projektverzeichnisse, Backup-Volume (`~/.autotrigger/backups/stable`); KI-Anbieter und Docker-Socket kommen aus der Override-Datei von `scripts/staging.sh` ([KI-Anbieter im Container](#ki-anbieter-im-container))
- Named Volume: `stable-data`

### `docker-compose.test.yml`

Test-Instanz auf Port 8090 (nutzt `docker/Dockerfile.stable` als Build-Basis):
- Container: `mysuricates-test`
- Image: `ai-automation-backend-test`
- Profil: `SPRING_PROFILES_ACTIVE=test`
- Volumes: `test-data` (persistente DB), `test-secrets` (Schlüssel wie bei Stable), `stable-frontend/` (read-only), `application-test.yml` (read-only), Backup-Volume (`~/.autotrigger/backups/test`); KI-Anbieter und Docker-Socket wie bei Stable
- Healthcheck: `curl -f http://localhost:8090/api/v1/system/health`
- Named Volume: `test-data`

## Umgebungsvariablen

| Variable | Standard | Beschreibung |
|---|---|---|
| `AUTOMATION_DB_PATH` | `./data/automation.db` | Pfad zur SQLite-Datenbank |
| `AUTOMATION_PORT` | `9090` | Server-Port |
| `AUTOMATION_SECURITY_ENABLED` | `true` | API-Key-Authentifizierung |
| `AUTOMATION_API_KEY` | (leer) | API-Key für Authentifizierung |
| `AUTOMATION_RATE_LIMIT_ENABLED` | `true` | Rate Limiting aktivieren (auch zur Laufzeit in Settings → Execution konfigurierbar) |
| `AUTOMATION_RATE_LIMIT_RPM` | `120` | Requests pro Minute (auch zur Laufzeit konfigurierbar, 10–10.000) |
| `AUTOMATION_MODULES_PRESET` | `core` | Optionale Module beim Start: `core`, `research` oder `full` (siehe [Module-System](architektur.md#module-system-startkonfiguration)) |
| `AUTOMATION_MODULES_<NAME>` | (Preset) | Einzelnes Modul an/aus, z. B. `AUTOMATION_MODULES_REMOTE_ACCESS=true` — schlägt das Preset |
| `AUTOMATION_MODULES_UI_EDITABLE` | `false` | `true` = Funktionsmodule zusätzlich in den Settings schaltbar (sofort wirksam). Dann kann jeder mit API-Key z. B. Webhooks oder das Terminal einschalten — auf öffentlich erreichbaren Instanzen aus lassen |
| `AUTOMATION_HARDENING_<NAME>` | `provenance`: `true`, Rest `false` | Härtungs-Flags `PROVENANCE`, `SEALING` (braucht provenance), `REQUIRE_SEALED_FOR_UNATTENDED` (braucht sealing), `WEBHOOK_SIGNATURES` (braucht Modul webhooks) — siehe [Workflow-Härtung](architektur.md#workflow-härtung-optional) |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT` | `300` | Globaler Claude-CLI-Timeout in Sekunden (in `application.yml` deklariert) |
| `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` | (leer) | Optionales per-Call Token-Limit für Claude CLI |
| `AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED` | `true` | Tags und Kategorie eines Workflows per KI (`suggestions-model`), nach jedem Anlegen/Ändern und beim Start für Workflows ohne Tags — `false` spart diese Hintergrund-Aufrufe (Test- und Nebeninstanzen) |
| `AUTOMATION_SSL_ENABLED` | `false` | SSL/TLS aktivieren |
| `AUTOMATION_SSL_KEYSTORE` | `./certs/dev.p12` | Pfad zum PKCS12-Keystore |
| `AUTOMATION_SSL_PASSWORD` | `mysuricates` | Keystore-Passwort |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker Socket Pfad |

> **Warum manche Variablen nicht in `application.yml` stehen:** Die `automation.*`-Konfiguration wird
> über `@ConfigurationProperties` gebunden. Spring bindet dabei jede Property zusätzlich direkt aus
> der passenden Umgebungsvariablen — `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` setzt also
> `automation.host-claude.max-tokens`, auch wenn dort kein `${...}`-Platzhalter steht.
> Umgebungsvariablen haben dabei Vorrang vor den Werten aus `application.yml`.

### Konfigurationen: minimal und maximal

Zwei kommentierte Vorlagen für Module und Härtung liegen in `ai-automation-backend/config-examples/`. Zum Verwenden nach `ai-automation-backend/config/application.yml` kopieren (oder die Blöcke dort ergänzen) und das Backend neu starten; `ConfigExamplesTest` hält beide gültig, wenn der Katalog wächst.

| Vorlage | Module | Settings-Schalter | Härtung |
|---|---|---|---|
| [`application-minimal.yml`](../ai-automation-backend/config-examples/application-minimal.yml) | nur Kern (`core`) | aus | alles aus |
| [`application-maximal.yml`](../ai-automation-backend/config-examples/application-maximal.yml) | alle (`full`) | an | alles an |

Der öffentliche Default liegt dazwischen: `core`, Settings-Schalter aus, nur `provenance` an.

Auf einer schon laufenden Instanz setzt `./scripts/setup.sh --preset <core|research|full> --force` das Preset um: `--force` ersetzt in der vorhandenen `config/application.yml` ausschließlich die Zeile `automation.modules.preset:` und lässt alles andere (Kommentare, Reihenfolge, einzelne Modul-Overrides) unverändert — wirksam nach einem Backend-Neustart. Ohne `--force` schreibt `setup.sh` in eine vorhandene Datei nichts. Lässt sich die Zeile nicht eindeutig einordnen (mehrere `automation:`-Blöcke), bricht das Skript mit einer Meldung ab, statt zu raten.

Für Docker/Compose dieselben Werte als Umgebungsvariablen:

```bash
# Minimal: platform core only, every option off
AUTOMATION_MODULES_PRESET=core
AUTOMATION_MODULES_UI_EDITABLE=false
AUTOMATION_HARDENING_PROVENANCE=false
AUTOMATION_HARDENING_SEALING=false
AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED=false
AUTOMATION_HARDENING_WEBHOOK_SIGNATURES=false
```

```bash
# Maximal: every module and every hardening switch on (not for publicly reachable instances)
AUTOMATION_MODULES_PRESET=full
AUTOMATION_MODULES_UI_EDITABLE=true
AUTOMATION_HARDENING_PROVENANCE=true
AUTOMATION_HARDENING_SEALING=true
AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED=true   # seal workflows first, see below
AUTOMATION_HARDENING_WEBHOOK_SIGNATURES=true
```

> **Vor „maximal“:** `require-sealed-for-unattended` lehnt jeden unbeaufsichtigten Run eines unversiegelten Workflows ab (Schedules, Webhooks, TODO-/Completion-Trigger, Auto-Repair, Wishes — auch über Chains und Sub-Workflows). Erst die betroffenen Workflows versiegeln oder diesen einen Schalter auf `false` lassen. Mit `full` und `ui-editable` kann außerdem jeder mit API-Key Module mit Netzwerk-, Zugangsdaten- und Host-Zugriff einschalten — nur auf nicht öffentlich erreichbaren Instanzen.

### Vorlage und Ladeweg

`ai-automation-backend/.env.example` listet alle Variablen mit ihren tatsaechlichen Defaults,
gruppiert nach Thema. **Spring Boot laedt eine `.env` nicht von selbst** — es gibt keine
dotenv-Abhaengigkeit im Projekt und kein `env_file:` in den Compose-Dateien. Die Vorlage ist eine
Referenz, keine Auto-Konfiguration.

| Start | So kommen die Werte an |
|---|---|
| Host | `cp .env.example .env`, anpassen, dann `set -a; . ./.env; set +a` vor dem Start — oder die Variablen direkt exportieren |
| Compose | Eine `.env` neben der Compose-Datei ersetzt nur `${VAR}` **in** der Compose-Datei. Im Container ankommt nur, was unter `environment:` steht |
| Spring-Override | Alternativ `ai-automation-backend/config/application.yml` (git-ignoriert) — Spring liest ein `config/`-Verzeichnis neben dem Arbeitsverzeichnis automatisch |

### Container-Limits (application.yml)

| Property | Standard | Beschreibung |
|---|---|---|
| `docker.container.cpu-quota` | `200000` | CPU-Quota (2 Kerne) |
| `docker.container.memory-limit` | `4000000000` | RAM-Limit (~4GB) |
| `docker.container.pids-limit` | `100` | Max. Prozesse |
| `docker.container.idle-timeout-minutes` | `60` | Auto-Stop nach Inaktivität |

## Profile

### Entwicklung (Standard)

```yaml
# application.yml Defaults
automation.security.enabled: true
automation.security.rate-limit.enabled: true
automation.modules.preset: core   # optionale Module, siehe Module-System
```

- API-Key-Authentifizierung aktiv (Key wird beim ersten Start auto-generiert, sofern
  `AUTOMATION_API_KEY` leer ist — er steht danach im Log, in `~/.autotrigger/api-key` und in der
  Tabelle `app_settings`)
- Rate Limiting aktiv (120 req/min, konfigurierbar in Settings → Execution)
- SQLite unter `./data/automation.db`

### Produktion

```bash
# docker-compose.yml Umgebung
AUTOMATION_SECURITY_ENABLED=true
AUTOMATION_API_KEY=<sicherer-key>   # Pflicht: die Compose-Datei liefert keinen Default mit
AUTOMATION_MODULES_PRESET=core       # optional: research | full; Fernzugriff: AUTOMATION_MODULES_REMOTE_ACCESS=true
AUTOMATION_RATE_LIMIT_ENABLED=true   # optional
```

- API-Key-Authentifizierung aktiv
- Rate Limiting empfohlen
- Docker Socket gemountet (`/var/run/docker.sock`)

## Volumes

```yaml
volumes:
  - ./data:/app/data                    # SQLite-Datenbank
  - /var/run/docker.sock:/var/run/docker.sock  # Docker Socket
```

**Wichtig:** Der Docker-Socket-Mount gibt dem Backend volle Kontrolle über den Host-Docker. In Produktionsumgebungen sollte API-Key-Auth aktiviert sein.

**Hinweis:** HikariCP Pool-Size ist auf 1 gesetzt. SQLite erlaubt nur einen gleichzeitigen Schreibzugriff. Pool-Size=1 serialisiert alle DB-Operationen — kein Lock-Contention. Pool-Size erhöhen erst sinnvoll beim Wechsel auf PostgreSQL.

## Tailscale Remote-Access

Das Backend kann über Tailscale von außerhalb des lokalen Netzwerks erreichbar gemacht werden.

### Voraussetzungen

- Tailscale CLI im Container installiert (Dockerfile.backend enthält dies)
- Modul `remote-access` eingeschaltet (`AUTOMATION_MODULES_REMOTE_ACCESS=true` oder Preset `full`) — im öffentlichen Default `core` ist es aus; ohne Modul antworten die `/system/remote-access/*`-Endpunkte mit 404 `MODULE_DISABLED`

### Aktivierung

1. API-Endpunkt aufrufen: `POST /api/v1/system/remote-access/enable`
2. Backend startet Tailscale im Container
3. IP und Hostname werden in der Response zurückgegeben

### Status

- `GET /api/v1/system/remote-access/status` → `{ enabled, ip, hostname }`
- Im Frontend unter Settings → Remote Access sichtbar

## Healthcheck

Docker Compose und das Dockerfile definieren einen Healthcheck:

```
GET http://localhost:9090/api/v1/system/health

Interval: 30s
Timeout: 5s
Retries: 3
Start Period: 15s
```

Response bei Erfolg:

```json
{
  "status": "UP",
  "timestamp": "...",
  "docker": "CONNECTED",
  "components": {
    "database": { "status": "UP", "responseTimeMs": 1 },
    "docker": { "status": "UP" }
  }
}
```

Mögliche `status`-Werte: `UP` (DB + Docker ok), `DEGRADED` (DB ok, Docker nicht erreichbar), `DOWN` (DB nicht erreichbar).

## Datenbank-Backup

Die SQLite-Datenbank ist eine einzelne Datei:

```bash
# Backup erstellen
cp data/automation.db data/automation.db.backup

# Oder bei laufendem System (WAL-Modus)
sqlite3 data/automation.db ".backup 'data/automation.db.backup'"
```

**Speicherort:** `ai-automation-backend/data/automation.db` (lokal) bzw. `/app/data/automation.db` (Docker)

Die Datei enthält alle Projekte, Workflows, Runs, Skills, Schedules, Webhooks, Chains, Todos, Integrations, Templates und weitere Entitäten. Ein Backup dieser Datei sichert den gesamten Zustand der Plattform.

## Netzwerk

Docker Compose erstellt ein `automation`-Bridge-Netzwerk. Der Backend-Container und die dynamisch erstellten Agent-Container können darüber kommunizieren.

```yaml
networks:
  automation:
    driver: bridge
```

## Reverse-Proxy

Seit dem Umstieg auf Einmal-Tickets für den WebSocket-Handshake (TODO 05b759f6) erzwingt das
Frontend `transports: ['websocket']` und schaltet den SockJS-Fallback (HTTP-Polling) bewusst ab: Ein
Einmal-Ticket ist nur für den ersten Verbindungsversuch gültig und überlebt keinen
Transport-Wechsel. Ein Reverse-Proxy vor MySuricates **muss** WebSocket-Verbindungen also per
HTTP-Upgrade durchreichen — ohne Upgrade-Unterstützung verbindet sich die App nicht mehr.

Auf `localhost`, über Tailscale und in den Stable-/Test-Containern ist das der Fall (direkter
Zugriff, kein Proxy dazwischen); der Bruch entsteht erst bei eigenen Reverse-Proxy-Deployments.

### nginx

```nginx
location /ws/ {
    proxy_pass http://127.0.0.1:9090;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}

location / {
    proxy_pass http://127.0.0.1:9090;
    proxy_set_header Host $host;
}
```

### Caddy

Caddy erkennt WebSocket-Upgrades automatisch — es braucht keine gesonderte Konfiguration:

```caddyfile
example.com {
    reverse_proxy 127.0.0.1:9090
}
```

### Fehlerbild

Fehlt die Upgrade-Weiterleitung, kommt der `ticket`-Query-Parameter trotzdem noch beim Backend an (er
steckt in der URL, nicht in einem Header) — `WebSocketAuthInterceptor.beforeHandshake` läuft vor
Springs eigener Upgrade-Prüfung und verbraucht ein an sich gültiges Ticket also selbst dann, wenn der
Protokollwechsel gleich danach an den fehlenden Headern scheitert. Tickets sind bewusst kurzlebig und
Einmalgebrauch (TTL 30 s, `WebSocketTicketService`): Beim Nachstellen des Problems (`curl`,
wiederholtes Neuladen, ein aus den Browser-DevTools kopiertes Ticket) zeigt das Backend-Log eine von
zwei Zeilen aus `WebSocketAuthInterceptor`:

```
WebSocket handshake rejected: invalid ticket (expired, already used or forged) from {}
```

— ein vorgelegtes Ticket ist abgelaufen, schon verbraucht oder erraten; oder

```
WebSocket handshake rejected: no ticket presented by {}
```

— es kam gar kein `ticket`-Parameter an (z. B. weil ein Test-Werkzeug oder ein Proxy die Query
verwirft). Das ist kein Ticket-Bug. Prüfen: Antwortet der Proxy auf den `/ws/**`-Pfad mit Status 101
(Switching Protocols)?

## Vertrauensmodell und Netzwerk-Exposition

MySuricates ist für den **lokalen Betrieb durch eine vertrauenswürdige Person** gebaut. Es ist kein
gehärtetes Mehrbenutzersystem und sollte nicht unverändert öffentlich erreichbar betrieben werden.
Dieser Abschnitt beschreibt das bestehende Modell, damit vor dem Start klar ist, was erreichbar ist.

### Was ein Workflow darf

Shell-Blöcke und KI-Aufrufe laufen standardmäßig **auf dem Host mit den Rechten des startenden
Benutzers** — nicht in einem Container. Container-Ausführung ist opt-in pro Shell-Block
(`container_image`). Ein Workflow kann damit alles, was der Benutzer kann: Dateien lesen und
schreiben, Prozesse starten, ins Netz gehen.

Die Claude CLI wird ausserdem mit `automation.cli.skip-permissions` = **`true`** aufgerufen
(`AUTOMATION_CLI_SKIP_PERMISSIONS`). Sie fragt also nicht vor Werkzeugnutzung nach. Ausnahme: wird
einem Aufruf eine Werkzeug-Whitelist mitgegeben, entfällt das Flag und die CLI erzwingt die Liste —
so arbeiten `skill-capability`-Blöcke ohne `allow_writes` nur lesend.

### Drei Vertrauensklassen für Kontextwerte

Nicht jeder Wert, den ein Workflow in einen Shell-Befehl einsetzt, kommt aus der Definition. Jede
Kontextvariable trägt deshalb eine von drei Klassen; sie entscheidet, ob der Wert roh in die
Kommandozeile geht oder in POSIX-Single-Quotes gepackt wird.

| Klasse | Woher der Wert kommt | In Shell-Blöcken | In KI-Prompts | Färbt den Run |
|---|---|---|---|---|
| **trusted** | Workflow-Defaults, Systemwerte (`project_path`, `workflow_run_id`), blockinterne Werte, Antworten aus `approval`/`human-input` | roh eingesetzt | roh | nein |
| **untrusted extern** | Webhook-Nutzlast und alles, was daraus abgeleitet ist (Blockausgaben, `step_N_output`, foreach-Items) — auch über Resume und Sub-Workflows hinweg | immer single-quoted | roh | ja, ab dem Setzen |
| **Plattform-Daten** | `previous_report` und `open_todos`, die in **jeden** Run injiziert werden | immer single-quoted | roh | erst, wenn der Platzhalter wirklich in einem Shell-Befehl steht |

„Färbt den Run" heisst: Ab diesem Moment werden auch alle Folgeausgaben des Runs gequotet, denn ein
Block kann fremde Bytes in seine Ausgabe holen, ohne einen Platzhalter zu nennen. Die Färbung ist
klebrig und überlebt Resume und Sub-Workflow-Uebergabe. Plattform-Daten färben bewusst erst bei
Nutzung: Sie stecken in jedem Run, eine Färbung beim Injizieren würde jeden Run färben und die
Bedeutung der Ausgabe-Platzhalter in den mitgelieferten Templates ändern.

Bewusst **nicht** escaped werden Antworten aus `approval`- und `human-input`-Blöcken: Sie kommen
über die API-Key-geschützte Schnittstelle von der Person, der die Instanz gehört. Und setzt ein
Workflow eine Variable selbst (`setVariable`), gilt sie wieder als vertrauenswürdig — der Wert
stammt dann aus der Definition, nicht vom Aufrufer.

Grenzen, die man kennen sollte:

- **In KI-Prompts wird nichts escaped.** Ein Prompt ist keine Kommandozeile; gegen Prompt-Injection
  hilft das Modell nicht.
- **Eine KI-Antwort nach einem Prompt mit Plattform-Daten ist selbst gefärbt.** Stehen
  Plattform-Daten im Prompt eines `simple`-, `agentic`- oder `skill-capability`-Blocks (auch in der
  verschachtelten Form), gilt die Ausgabe genau dieses Blocks als nicht vertrauenswürdig: Ein
  folgender Shell-Block bekommt `{{ previous_output }}` **single-quoted**. Sobald diese gefärbte
  Ausgabe verbucht ist, ist der Lauf gefärbt — und die Färbung ist klebrig wie immer: Auch alle
  **weiteren** Blockausgaben und die daraus abgeleiteten Variablen (`step_N_output`,
  `foreach`-Items, `document`-Extrakte) werden ab da gequotet, und `exportTaint()` gibt das an
  Sub-Workflows und an ein Resume weiter. In der Praxis heisst das: Praktisch jeder Audit- oder
  Report-Workflow, der `previous_report` in einem Prompt liest, läuft ab diesem Block im gequoteten
  Modus. **Setze in eigenen Workflows in jedem Shell-Block, der die Ausgabe eines KI-Blocks
  weiterverarbeitet, `"shell_escape_variables": true`** — dann verhält sich der Block in gefärbten
  wie in ungefärbten Läufen gleich.
- **Was das Quoting nicht abwehrt:** Gequotet wird der Text der KI-Antwort, nicht ihr Zustandekommen.
  Gegen inhaltliche Manipulation (Prompt-Injection) hilft das Trust-Modell nicht.
- **Selbst geschriebene Dateien sieht das Quoting nicht.** Ein Shell-Block, der einen gefärbten Wert
  in eine Datei schreibt und diese später ausführt, liegt ausserhalb dessen, was sich quoten lässt.

Technische Fassung mit Klassennamen und reservierten Kontextschlüsseln:
[Workflow-Härtung](architektur.md#workflow-härtung-optional).

### Wo gelauscht wird

Weder Backend noch Frontend binden auf Loopback. `server.address` ist nirgends gesetzt, das Backend
lauscht also auf allen Interfaces; Vite läuft mit `host: true`. Der Compose-Dienst veröffentlicht
`9090:9090` ohne Host-IP-Präfix, also auf allen Adressen — und Dockers Portfreigabe umgeht dabei
die üblichen Host-Firewall-Regeln.

**CORS** betrifft eine installierte Instanz nicht: Das Backend liefert die Oberfläche selbst aus,
Seite und API haben denselben Ursprung. Eine Rolle spielt die Liste nur, wo beide verschiedene
Ursprünge haben — beim Vite-Dev-Server (sein Proxy schreibt den Host um, nicht den `Origin`) und
hinter einem Reverse-Proxy, der den Host umschreibt. Zugelassen sind dann:

- per Default nur die Dev-Server auf diesem Rechner: `localhost:3000` und `localhost:5173`, jeweils per
  http und https;
- mit dem Modul `remote-access` zusätzlich die **eigene** Tailscale-Adresse der Instanz — die IPv4 aus
  `tailscale ip -4` und der MagicDNS-Name aus `tailscale status --json`, auf jedem Port, per http und
  https. Die Instanz fragt Tailscale zur Laufzeit und merkt sich die Antwort eine Minute; ohne
  laufendes Tailscale kommt nichts dazu. Fremde Tailnets und andere `100.*`-Hosts bleiben draußen;
- was `AUTOMATION_CORS_ORIGINS` (`automation.cors.allowed-origins`) nennt. Die Variable ersetzt die
  Default-Liste — wer den Dev-Server etwa über die LAN-IP aufruft, trägt diese Adresse samt der
  localhost-Einträge ein. Muster mit `*` sind möglich, aber `*` deckt auch Punkte ab:
  `http://100.*:3000` passt auf `http://100.evil.example:3000`. Genaue Ursprünge sind die sichere Wahl.

Dieselbe Regel gilt für die WebSocket-Handshakes (`/ws`, `/ws/terminal/**`) und für das Ziel des
OAuth-Popups. Credentials gibt CORS nicht frei: Authentifiziert wird über den Header `X-API-Key`,
Cookies gibt es nicht.

**CORS ist keine Zugriffskontrolle** — es steuert nur, welche Herkunft ein Browser akzeptiert, und
hält niemanden von einem direkten Aufruf ab. Ein vorhandener Tailscale-Zugang macht die Anwendung
nicht zu einem abgesicherten System.

### Was ohne API-Key erreichbar ist

Authentifiziert wird über den Header `X-API-Key`, und zwar für `/api/**` und `/actuator/**`.
Ausgenommen sind:

| Pfad | Zweck |
|---|---|
| `/api/v1/system/health`, `/system/info`, `/system/auth-status` | Statusabfragen vor dem Login |
| `/api/v1/system/jvm-memory` | Heap-Belegung als genau drei Zahlen (`jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_heap_used_percent`) für den Performance-Audit |
| `/actuator/health` | Healthcheck für Docker |
| `/ws/**` | WebSocket-Handshake (der Key wird dort als Query-Parameter geprüft) |
| `/api/v1/webhook/trigger/**` | Oeffentlicher Webhook-Eingang |
| `/api/v1/integrations/*/callback` | OAuth-Rückleitungen |

Die übrige Actuator-Oberfläche (`/actuator/metrics`, `/actuator/info`) verlangt den Key: Metriken
verraten Routennamen, Lastprofil und Speicherlage an jeden, der den Port erreicht. Wer ohne Key den
Speicher messen will, bekommt deshalb nur die drei Zahlen von `/api/v1/system/jvm-memory`.

Alles ausserhalb von `/api/**` und `/actuator/**` ist ebenfalls offen (`anyRequest().permitAll()`) —
das betrifft das ausgelieferte Frontend und jeden nicht gemappten Pfad.

**Wichtig:** Ist kein API-Key gesetzt oder `automation.security.enabled=false`, gilt *jede* Anfrage
als authentifiziert. Ein leerer Schlüssel schaltet die Authentifizierung also vollständig ab, statt
sie zu verschärfen.

### Host-nahe Endpunkte hinter dem API-Key

Einige Endpunkte reichen bis auf den Host oder geben Betriebsdaten heraus. Sie stehen nicht in der
Ausnahmeliste oben und verlangen den API-Key wie jeder andere Pfad unter `/api/**`:

| Endpunkt | Was er tut | Zusätzliche Absicherung |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Listet Workflows, deren letzter Run fehlschlug (nur lesend) | Modul `debug`, im Standard-Preset `core` aus (`@RequiresModule`, `WorkflowDebugController`) |
| `POST /api/v1/system/open-path` | Öffnet einen Pfad per `xdg-open` auf dem Host-Desktop | Pfad wird normalisiert, `/etc`, `/proc`, `/sys`, `/dev`, `/var/run` sind gesperrt (`FileBrowserService`) |
| `GET /api/v1/system/browse` | Listet Unterverzeichnisse eines Pfads (nur Namen, ohne versteckte) | Wurzel und dieselben Systemverzeichnisse gesperrt (`FileBrowserService`) |
| `GET /api/v1/system/backups/{filename}` | Lädt eine Datenbanksicherung herunter | Nur Dateinamen aus dem Backup-Verzeichnis, `..`, `/` und `\` ergeben 404 (`DatabaseBackupService.getBackupFile`) |

Die Grenze ist hier der API-Key, nicht der einzelne Endpunkt. Wer den Key hat, kann ohnehin
Shell-Workflows mit den Rechten des Benutzers starten ([Was ein Workflow darf](#was-ein-workflow-darf)).
Diese Endpunkte erweitern das nicht, sie sind bequemere Wege zu Dingen, die ein Workflow auch kann. Eine
Datenbanksicherung enthält allerdings alle Runs, Ausgaben und Einstellungen der Instanz und ist so
vertraulich zu behandeln wie der Key selbst.

### Webhooks

Der Trigger-Endpunkt wird allein durch ein Token im Pfad geschützt (32 Zufallsbytes aus
`SecureRandom`). Es gibt keine Signaturprüfung und keinen Replay-Schutz. Als Gegengewicht gilt ein
fest verdrahtetes Limit von 10 Anfragen pro Minute und IP — unabhängig vom globalen Rate-Limit-
Schalter —, die Nutzlast ist auf 50 Felder begrenzt, und alle vom Aufrufer stammenden Werte werden
als nicht vertrauenswürdig markiert und in Shell-Blöcken immer escaped.

### Docker-Rechte — zwei getrennte Ebenen

Die beiden werden leicht verwechselt:

1. **`AUTOMATION_ALLOW_DOCKER_SOCKET_MOUNT`** (Default `false`) ist ein serverseitiger Schalter dafür,
   ob ein Shell-Block mit `mount_docker_socket: true` den Socket in einen **erzeugten Agent-Container**
   bekommt. Wird es verweigert, landet ein SECURITY-Eintrag im Activity-Log.
2. **Der Socket im Backend-Container** (`/var/run/docker.sock`) ist unabhängig von Punkt 1 und gibt der
   Anwendung volle Kontrolle über Docker auf dem Host. Bei `instance.sh`, Stable, Test und dem
   Dev-Container entscheidet das ein Schalter ([KI-Anbieter im Container](#ki-anbieter-im-container)); die
   schlanke `docker-compose.yml` mountet ihn weiterhin fest.

### Container-Image-Allowlist

`AUTOMATION_ALLOWED_CONTAINER_IMAGES` (Default leer = jedes Image erlaubt, einmaliges INFO beim
Start) schränkt ein, welches `container_image` ein Shell-Block anfordern darf. Die Prüfung sitzt
zentral in `ContainerShellService.execute` — beide Aufrufer (`ShellBlockExecutor`,
`CommandExecutorService`) laufen automatisch mit. Ein nicht gelistetes Image scheitert mit
Exit-Code 125 und einem SECURITY-Eintrag im Activity-Log, statt einfach zu starten. Ein Eintrag
matcht per Gleichheit, als Registry-/Namespace-Präfix (endet mit `/`), oder — ohne Tag — gegen
jeden Tag des Images. Ein Image, das mit `-` beginnt, wird immer abgelehnt (unabhängig von der
Allowlist) — `docker run` würde es sonst als Flag lesen. Analoges Muster zu
`AUTOMATION_ALLOWED_MOUNT_PREFIXES`.

### Daraus folgt

- Nur in einem Netz betreiben, dem du vertraust; für Fernzugriff ein VPN davorsetzen statt den Port zu öffnen.
- Einen eigenen API-Key setzen und prüfen, dass er nicht leer ist.
- Den Docker-Socket ausschalten (`…DOCKER_SOCKET=0`), wenn keine Container-Blöcke gebraucht werden.
- In Produktivumgebungen `AUTOMATION_ALLOWED_CONTAINER_IMAGES` auf die tatsächlich benötigten
  Images setzen, statt auf dem leeren Default (= alle Images erlaubt) zu bleiben.
- Keine Workflows aus fremder Quelle ungelesen ausführen — sie laufen mit deinen Rechten.

## Drei-Umgebungs-Setup

Das Projekt unterstützt drei parallele Umgebungen:

| Umgebung | Port | Beschreibung |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Aus Quellcode, Vite Dev-Server |
| **Stable** | 9091 | Docker-Container, Backend + eingebettetes Frontend |
| **Test** | 8090 | Docker-Container, dedizierte Test-Instanz für Workflow-Tests |

### Start (kein Auto-Start)

Stable und Test starten **nicht** automatisch — weder beim Boot noch mit dem Dev-Backend. Gestartet werden sie bewusst über die Desktop-Icons oder `scripts/staging.sh`:

| Umgebung | Icon | Kommando |
|---|---|---|
| Dev | „MySuricates Dev" | `scripts/start-autotrigger-dev.sh` |
| Stable | „MySuricates Stable" | `scripts/staging.sh start` |
| Test | „MySuricates Test" | `scripts/staging.sh test-start` |

Icons (Desktop + Anwendungsmenü) anlegen bzw. erneuern: `scripts/install_desktop_launcher.sh`.

Zwei Mechanismen wurden dafür abgeschaltet:
- **Docker-Restart-Policy** — `restart: "no"` in `docker-compose.stable.yml` / `docker-compose.test.yml`. Vorher (`unless-stopped`) fuhr der Docker-Daemon beide Container bei jedem Boot wieder hoch.
- **Dev-Auto-Start** — `automation.staging.auto-start` und `automation.staging.test-auto-start` stehen auf `false`. Vorher startete das Dev-Backend beide Container beim `ApplicationReadyEvent`, sofern ein Snapshot (`stable-frontend/`) vorhanden war und der Container nicht schon lief.

Reaktivieren ohne Code-Änderung: `AUTOMATION_STAGING_AUTO_START=true` bzw. `AUTOMATION_TEST_AUTO_START=true` setzen (und ggf. die Restart-Policy in der Compose-Datei zurückstellen).

### Automatische Trigger pausieren

Für ein befristetes Ruhigstellen des Schedulers gibt es zwei Wege:

- **`automation.scheduler.skip-until`** (`AUTOMATION_SCHEDULER_SKIP_UNTIL`, ISO-Instant, z. B. `2026-07-30T22:00:00Z`) — für Zeitfenster. Fällige Schedules werden übersprungen **und** ihr `nextRunAt` hinter das Fenster gespult, es gibt also am Ende keinen Nachhol-Stau. Läuft am Stichtag von selbst aus; ein unparsebarer Wert wird ignoriert (fail open). Wird nur beim Backend-Start gelesen. Die Oberfläche zeigt die Pause: Banner auf der Schedules-Seite, Hinweis im Dashboard, als „nächster Lauf“ in Liste, Kalender und Dashboard der erste Termin nach der Pause (API: `pausedUntil`, siehe [API-Referenz](api-referenz.md#get-apiv1schedules)).
- **Wartungsmodus** (`POST /api/v1/schedules/maintenance`, Pause-Button auf der Schedules-Seite) — Laufzeit-Schalter ohne Enddatum, persistiert in `app_settings`. Achtung: `nextRunAt` wird dabei *nicht* vorgespult, beim Abschalten feuern also alle inzwischen fällig gewordenen Schedules gemeinsam.

Für lokale, nicht eingecheckte Overrides eignet sich `ai-automation-backend/config/application.yml` — Spring Boot lädt dieses Verzeichnis zusätzlich zur Classpath-Config und mit höherer Priorität (das Verzeichnis ist in `.gitignore`).

### Stable-Umgebung (Port 9091)

Die Stable-Umgebung ist ein Docker-Container, der Backend + eingebettetes React-Frontend enthält.

**Key-Dateien:**
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-basiert (glibc für Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — Daten, Frontend, Projekte; KI-Anbieter und Socket über `scripts/staging.sh write-override stable`
- `ai-automation-backend/src/main/resources/application-stable.yml` — Port 9091, Auto-Start deaktiviert, Frontend-Serving aktiviert

**Schlüssel:** Integrations- und Siegel-Schlüssel liegen im Volume `stable-secrets`, getrennt von der
Datenbank. Images vor dem 23.09.2026 hielten den Integrations-Schlüssel noch in der Datenbank; der erste
Start mit einem neueren Image verschiebt ihn auf das Volume und löscht ihn in der Datenbank. Ohne das
Volume läge er danach in der Container-Schicht und wäre beim nächsten `staging.sh stop` oder `promote`
verloren — mit ihm alle verschlüsselten Integrations-Zugänge (3fb2242c). Test hält es mit `test-secrets`
genauso; `test-reset` löscht beide Volumes, dort ist das gewollt.

### Test-Instanz (Port 8090)

Die Test-Instanz ist ein dedizierter Docker-Container für automatisierte Workflow-Tests:

- `TestingService` — verwaltet den Test-Docker-Container
- Kein Auto-Start beim Boot (`automation.staging.test-auto-start=false`) — Start via Icon „MySuricates Test" bzw. `staging.sh test-start`
- Kein Auto-Sync von Definitionen beim Boot (nur bei `reset`)
- Datenbank kann via API zurückgesetzt werden (`POST /api/v1/testing/reset`)

**Key-Dateien:**
- `ai-automation-backend/docker-compose.test.yml` — Container `mysuricates-test`, Port 8090, Profil `test`

### Staging CLI-Verwaltung

```bash
./scripts/staging.sh snapshot    # JAR + Frontend + Docker-Image bauen, Git-Commit taggen
./scripts/staging.sh start       # Stable-Container auf :9091 starten
./scripts/staging.sh stop        # Stable-Container stoppen
./scripts/staging.sh status      # Container-Status + Health-Check
./scripts/staging.sh log         # Container-Logs anzeigen (tail)
./scripts/staging.sh db-seed     # Rohe DB-Kopie (nur Notfall, überschreibt alles)
./scripts/staging.sh clean       # Container + Volume + Image entfernen
./scripts/staging.sh promote     # stop → snapshot → start (Einzeiler)
./scripts/staging.sh write-override stable|test   # Override-Datei (was vom Rechner kommt) schreiben, Pfad ausgeben
./scripts/staging.sh login stable|test claude     # eigene Anmeldung im Container (nur *_AI_FROM_HOST=programs)
```

`MYSURICATES_APT_REFRESH=$(date +%Y-%m) ./scripts/staging.sh promote` (ebenso `test-promote`) holt
NodeSource- und Docker-Repo des Images (`Dockerfile.stable`) frisch, statt sie aus dem Schichten-Cache
des ersten Baus zu übernehmen (8b7a39b9).

### Definition-Sync (Dev → Stable / Dev → Test)

`POST /api/v1/staging/sync-definitions` und `POST /api/v1/testing/sync-definitions` synchronisieren **nur Definitionen**:

| Synciert | Matching | Verhalten |
|---|---|---|
| Projekte | Name + Pfad | Überspringen wenn vorhanden, erstellen wenn neu |
| Workflows | Name + Projektname | Aktualisieren wenn vorhanden, importieren wenn neu |
| Custom Skills | Name | Überspringen wenn vorhanden, importieren wenn neu |
| Chains | Name | Aktualisieren wenn vorhanden, erstellen wenn neu |

Zusätzlich werden die App-Settings `auto-todo-default` und `auto-repair-default` synchronisiert.

**Nicht synciert** (auf Stable/Test erhalten): workflow_runs, step_results, scheduled_workflows, webhook_triggers, activity_log, todos, built-in Skills/Templates. Archivierte Projekte und soft-deleted Workflows werden automatisch ausgeschlossen.

### Rollback nach Sync

Vor jedem Definition-Sync ruft das Backend automatisch `POST /api/v1/system/backup?reason=pre-sync-definitions` auf dem **Ziel** auf. Der zurückgegebene Dateiname wird im `SyncResult.preSyncBackup` mitgeliefert und im Frontend-Toast angezeigt. Der Backup-Erfolg blockiert den Sync nicht — scheitert das Backup, läuft der Sync trotzdem durch, die Warnung landet in `SyncResult.errors`.

Zusätzlich legt `./scripts/staging.sh db-seed` ein File-Backup an (`~/.autotrigger/backups/stable/mysuricates-backup-<timestamp>-pre-seed.db`), bevor die Stable-DB mit der Dev-DB überschrieben wird.

**Rollback-Pfade:**

| Quelle des Snapshots | Speicherort | Restore-Weg |
|---|---|---|
| Pre-Sync (Definition-Sync) | Ziel-Backup-Verzeichnis (`~/.autotrigger/backups/<env>/`) | UI auf dem Ziel: Settings → Backups → Download; manuell zurückspielen. |
| Pre-Seed (`staging.sh db-seed`) | `~/.autotrigger/backups/stable/…-pre-seed.db` | `docker compose -f ai-automation-backend/docker-compose.stable.yml down && docker run --rm -v ai-automation-backend_stable-data:/data -v <backup>:/src/db:ro alpine cp /src/db /data/automation.db && ./scripts/staging.sh start` |

Pre-Sync-Backups zählen zur regulären Retention (`automation.backup.retain-count`, Default 30) und werden ggf. durch jüngere Backups verdrängt.

### Versionsanzeige

In Settings → Stable wird ein Versionsvergleich angezeigt:
- **Dev**: Version aus `/api/v1/system/info`
- **Stable**: Version aus dem Stable-Container's `/api/v1/system/info`

Wenn die Versionen abweichen (z.B. nach einem Release), wird ein Promote empfohlen.

### Release + Promote Workflow

```bash
# 1. Version bumpen
./scripts/release.sh           # z.B. 0.3.2 → 0.3.3

# 2. Neue Version in Stable deployen
./scripts/staging.sh promote   # Baut und startet Stable mit der neuen Version
```

### Git-Tags

| Tag-Format | Erstellt durch | Zweck |
|---|---|---|
| `v0.3.2` | `release.sh` | Semver-Release-Markierung |
| `stable-20250225-143022` | `staging.sh promote` | Deployment-Zeitstempel |
