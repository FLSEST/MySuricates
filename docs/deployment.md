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
| `agent-node` | `Dockerfile.agent-node` | - | Node.js 21 Agent-Container |
| `agent-python` | `Dockerfile.agent-python` | - | Python 3.12 Agent-Container |
| `agent-go` | `Dockerfile.agent-go` | - | Go 1.22 Agent-Container |

Die Agent-Images werden nur gebaut (`profiles: build-only`), nicht direkt über Compose gestartet. Das Backend erstellt und verwaltet Agent-Container dynamisch über die Docker API.

## Dockerfiles

### Backend (`docker/Dockerfile.backend`)

- **Base:** `eclipse-temurin:21-jre-alpine`
- **User:** `appuser` (UID 1000, nicht-root)
- **JVM:** ZGC (Generational), 256MB–1GB Heap
- **Extras:** Tailscale CLI (optional, für Remote-Access)
- **Healthcheck:** `curl http://localhost:9090/api/v1/system/health`

### Agent Node (`docker/Dockerfile.agent-node`)

- **Base:** `node:21-alpine`
- **Tools:** git, curl, bash, jq
- **Pakete:** `@anthropic-ai/claude-code` (optional)
- **CMD:** `tail -f /dev/null` (Container bleibt aktiv für `docker exec`)

### Agent Python (`docker/Dockerfile.agent-python`)

- **Base:** `python:3.12-slim`
- **Tools:** git, curl, jq
- **Pakete:** anthropic, pandas, numpy, requests, beautifulsoup4

### Agent Go (`docker/Dockerfile.agent-go`)

- **Base:** `golang:1.22-alpine`
- **Tools:** git, curl, bash, jq

## Umgebungsvariablen

| Variable | Standard | Beschreibung |
|---|---|---|
| `AUTOMATION_DB_PATH` | `./data/automation.db` | Pfad zur SQLite-Datenbank |
| `AUTOMATION_PORT` | `9090` | Server-Port |
| `AUTOMATION_SECURITY_ENABLED` | `false` | API-Key-Authentifizierung |
| `AUTOMATION_API_KEY` | (leer) | API-Key für Authentifizierung |
| `AUTOMATION_RATE_LIMIT_ENABLED` | `false` | Rate Limiting aktivieren |
| `AUTOMATION_RATE_LIMIT_RPM` | `120` | Requests pro Minute |
| `AUTOMATION_TAILSCALE_ENABLED` | `false` | Tailscale Remote-Access |
| `AUTOMATION_HOST_CLAUDE_MAX_TOKENS` | (leer) | Optionales per-Call Token-Limit für Claude CLI |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT_SECONDS` | `300` | Globaler Claude-CLI-Timeout in Sekunden |
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
automation.security.enabled: false
automation.security.rate-limit.enabled: false
automation.tailscale.enabled: false
logging.level.com.automation: DEBUG
```

- Keine Authentifizierung
- Kein Rate Limiting
- Debug-Logging
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
- `AUTOMATION_TAILSCALE_ENABLED=true` in Umgebungsvariablen

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
  "docker": "CONNECTED"
}
```

## Datenbank-Backup

Die SQLite-Datenbank ist eine einzelne Datei:

```bash
# Backup erstellen
cp data/automation.db data/automation.db.backup

# Oder bei laufendem System (WAL-Modus)
sqlite3 data/automation.db ".backup 'data/automation.db.backup'"
```

**Speicherort:** `ai-automation-backend/data/automation.db` (lokal) bzw. `/app/data/automation.db` (Docker)

Die Datei enthält alle Projekte, Workflows, Runs, Skills, Schedules und Webhooks. Ein Backup dieser Datei sichert den gesamten Zustand der Plattform.

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

### Auto-Start

Dev startet Stable und Test automatisch beim Boot (`ApplicationReadyEvent`), wenn:
1. Ein Snapshot vorhanden ist (`stable-frontend/`-Verzeichnis existiert)
2. Der Container nicht bereits läuft

Kontrolliert via `automation.staging.auto-start` (Standard: `true`, in `application-stable.yml` deaktiviert).

### Stable-Umgebung (Port 9091)

Die Stable-Umgebung ist ein Docker-Container, der Backend + eingebettetes React-Frontend enthält.

**Key-Dateien:**
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-basiert (glibc für Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — Volume-Mounts für Claude CLI + Projekte
- `ai-automation-backend/src/main/resources/application-stable.yml` — Port 9091, Auto-Start deaktiviert, Frontend-Serving aktiviert

### Test-Instanz (Port 8090)

Die Test-Instanz ist ein dedizierter Docker-Container für automatisierte Workflow-Tests:

- `TestingService` — verwaltet den Test-Docker-Container
- Auto-Start beim Boot wenn `automation.staging.test-auto-start=true` und Snapshot vorhanden
- Auto-Sync von Dev-Definitionen beim Start
- Datenbank kann via API zurückgesetzt werden (`POST /api/v1/testing/reset`)

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

**Nicht synciert** (auf Stable/Test erhalten): workflow_runs, step_results, scheduled_workflows, webhook_triggers, activity_log, todos, built-in Skills/Templates. Archivierte Projekte und soft-deleted Workflows werden automatisch ausgeschlossen.

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
