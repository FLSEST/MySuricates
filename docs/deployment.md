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
