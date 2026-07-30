> *Zuletzt aktualisiert: [auto-generated]*

# Deployment

## Docker Compose

Das Projekt wird über Docker Compose deployt. Die Konfiguration liegt in `ai-automation-backend/docker-compose.yml`.

### Starten

```bash
cd ai-automation-backend

# 1. JAR bauen
mvn clean package -DskipTests

# 2. Backend starten
docker compose up --build -d

# 3. Agent-Images bauen (einmalig)
docker compose --profile build-only build
```

Das Backend ist dann unter `http://localhost:9090` erreichbar.

### Services

| Service | Image | Port | Beschreibung |
|---|---|---|---|
| `backend` | `Dockerfile.backend` | 9090 | Spring Boot Backend |
| `agent-node` | `Dockerfile.agent-node` | - | Node.js 22 Agent-Container |
| `agent-python` | `Dockerfile.agent-python` | - | Python 3.12 Agent-Container |
| `agent-go` | `Dockerfile.agent-go` | - | Go 1.24 Agent-Container |

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
- **Pakete:** anthropic, pandas, numpy, requests, beautifulsoup4

### Agent Go (`docker/Dockerfile.agent-go`)

- **Base:** `golang:1.24-alpine`
- **Tools:** git, curl, bash, jq
- **User:** `aiuser` (UID 1000)

### Agent DevOps (`docker/Dockerfile.agent-devops`)

- **Base:** `ubuntu:24.04`
- **Tools:** git, curl, jq, bash, python3
- **GitHub CLI:** `gh` aus dem offiziellen APT-Repository
- **Security-Tools:** gitleaks, trivy (Binaries mit SHA256-Verifizierung, multi-arch)
- **Docker:** Docker CE CLI (nur Client, kein Daemon)
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
- Volumes: `stable-data` (persistente DB), `stable-frontend/` (read-only), Docker Socket, Claude CLI Directories vom Host, Host-Projektverzeichnisse, Backup-Volume (`~/.autotrigger/backups/stable`)
- Named Volume: `stable-data`

### `docker-compose.test.yml`

Test-Instanz auf Port 8090 (nutzt `docker/Dockerfile.stable` als Build-Basis):
- Container: `mysuricates-test`
- Image: `ai-automation-backend-test`
- Profil: `SPRING_PROFILES_ACTIVE=test`
- Volumes: `test-data` (persistente DB), `stable-frontend/` (read-only), `application-test.yml` (read-only), Docker Socket, Claude CLI Directories vom Host, Backup-Volume (`~/.autotrigger/backups/test`)
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
| `AUTOMATION_TAILSCALE_ENABLED` | `true` | Tailscale Remote-Access |
| `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` | (leer) | Optionales per-Call Token-Limit für Claude CLI |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT_SECONDS` | `300` | Globaler Claude-CLI-Timeout in Sekunden |
| `AUTOMATION_SSL_ENABLED` | `false` | SSL/TLS aktivieren |
| `AUTOMATION_SSL_KEYSTORE` | `./certs/dev.p12` | Pfad zum PKCS12-Keystore |
| `AUTOMATION_SSL_PASSWORD` | `mysuricates` | Keystore-Passwort |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker Socket Pfad |

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
automation.tailscale.enabled: true
```

- API-Key-Authentifizierung aktiv (Key wird beim ersten Start auto-generiert)
- Rate Limiting aktiv (120 req/min, konfigurierbar in Settings → Execution)
- SQLite unter `./data/automation.db`

### Produktion

```bash
# docker-compose.yml Umgebung
AUTOMATION_SECURITY_ENABLED=true
AUTOMATION_API_KEY=<sicherer-key>
AUTOMATION_TAILSCALE_ENABLED=true    # optional
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
- `AUTOMATION_TAILSCALE_ENABLED=true` (Standard in application.yml, in docker-compose.yml auf `false` gesetzt)

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

- **`automation.scheduler.skip-until`** (`AUTOMATION_SCHEDULER_SKIP_UNTIL`, ISO-Instant, z. B. `2026-07-30T22:00:00Z`) — für Zeitfenster. Fällige Schedules werden übersprungen **und** ihr `nextRunAt` hinter das Fenster gespult, es gibt also am Ende keinen Nachhol-Stau. Läuft am Stichtag von selbst aus; ein unparsebarer Wert wird ignoriert (fail open). Wird nur beim Backend-Start gelesen.
- **Wartungsmodus** (`POST /api/v1/schedules/maintenance`, Pause-Button auf der Schedules-Seite) — Laufzeit-Schalter ohne Enddatum, persistiert in `app_settings`. Achtung: `nextRunAt` wird dabei *nicht* vorgespult, beim Abschalten feuern also alle inzwischen fällig gewordenen Schedules gemeinsam.

Für lokale, nicht eingecheckte Overrides eignet sich `ai-automation-backend/config/application.yml` — Spring Boot lädt dieses Verzeichnis zusätzlich zur Classpath-Config und mit höherer Priorität (das Verzeichnis ist in `.gitignore`).

### Stable-Umgebung (Port 9091)

Die Stable-Umgebung ist ein Docker-Container, der Backend + eingebettetes React-Frontend enthält.

**Key-Dateien:**
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-basiert (glibc für Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — Volume-Mounts für Claude CLI + Projekte
- `ai-automation-backend/src/main/resources/application-stable.yml` — Port 9091, Auto-Start deaktiviert, Frontend-Serving aktiviert

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
```

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
