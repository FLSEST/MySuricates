# Setup-Anleitung

Schritt-fuer-Schritt Anleitung zur Einrichtung der lokalen Entwicklungsumgebung.

---

## Voraussetzungen

| Tool | Version | Pruefung |
|---|---|---|
| **JDK** | 21+ | `java -version` |
| **Maven** | 3.9+ | `mvn -version` |
| **Node.js** | 20.19+ / 22.12+ / 24+ (`engines` in `package.json`) | `node -v` |
| **npm** | 10+ | `npm -v` |
| **Docker** | 24+ | `docker --version` |
| **Docker Compose** | 2.x | `docker compose version` |
| **Claude CLI** | aktuell | `claude --version` |

### Claude CLI installieren

Die KI-gesteuerten Bloecke benoetigen eine lokal installierte Claude CLI auf dem Host:

```bash
# Installation (siehe offizielle Dokumentation)
# Claude CLI muss im PATH verfuegbar sein
which claude
```

> **Wichtig:** Die Claude CLI wird immer auf dem Host-System ausgefuehrt, NICHT innerhalb von Docker-Containern.

---

## 1. Repository klonen

```bash
git clone <repository-url>
cd autotrigger
```

---

## 2. Backend einrichten

### 2.1 Build

```bash
cd ai-automation-backend
mvn clean package -DskipTests
```

### 2.2 Konfiguration

Die Standardkonfiguration in `src/main/resources/application.yml` funktioniert out-of-the-box fuer die lokale Entwicklung:

| Eigenschaft | Standard | Beschreibung |
|---|---|---|
| `server.port` | `9090` | Backend-Port |
| `AUTOMATION_DB_PATH` | `./data/automation.db` | SQLite-Datenbankpfad |
| `AUTOMATION_SECURITY_ENABLED` | `true` | API-Key-Authentifizierung |
| `AUTOMATION_API_KEY` | (leer) | API-Key (wird beim ersten Start generiert) |
| `AUTOMATION_CORS_ORIGINS` | `http(s)://localhost:3000` und `:5173` | Erlaubte CORS-Origins (ersetzt die Liste); die eigene Tailscale-Adresse kommt mit `remote-access` automatisch dazu |
| `AUTOMATION_MAX_CONCURRENT_RUNS` | `10` | Maximale parallele Workflow-Ausfuehrungen |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT` | `300` | Timeout fuer Claude CLI (Sekunden) |

### Umgebungsvariablen (optional)

```bash
# Security deaktivieren (nur Entwicklung!)
export AUTOMATION_SECURITY_ENABLED=false

# Eigenen API-Key setzen
export AUTOMATION_API_KEY=mein-geheimer-key

# Workspace-Root setzen (fuer Projekt-Pfade)
export AUTOMATION_WORKSPACE_ROOT=/pfad/zu/projekten
```

### 2.3 Starten

```bash
# aus dem Repo-Root -- baut das JAR, kopiert es und startet `java -jar`
./scripts/start-autotrigger-dev.sh start   # bzw. restart | stop
```

> **Nicht** `mvn spring-boot:run` verwenden: der `RuntimeClasspathGuard` beendet einen Start aus `target/classes` mit Exit-Code 2 (Escape-Hatch: `automation.runtime.allow-classes-dir`).

Das Backend startet auf **http://localhost:9090**. Beim ersten Start:
- Flyway fuehrt alle 86 Migrationen aus (V1-V86)
- SQLite-Datenbank wird unter `data/automation.db` erstellt
- 19 Built-in Skills werden geseedet
- 55 Workflow-Templates werden geseedet

### 2.4 Health-Check

```bash
curl http://localhost:9090/api/v1/system/health
```

---

## 3. Frontend einrichten

### 3.1 Abhaengigkeiten installieren

```bash
cd ai-automation-frontend
npm install
```

### 3.2 Konfiguration

Im Repository liegt nur `.env.example`. Fuer eine eigene Konfiguration kopieren:

```bash
cp .env.example .env
```

Darin stehen die Backend-URLs:

```env
VITE_API_BASE_URL=http://localhost:9090
VITE_WS_BASE_URL=ws://localhost:9090
```

> **Hinweis:** Im Dev-Modus proxied Vite `/api` und `/ws` automatisch zum Backend (siehe `vite.config.ts`).

### 3.3 Starten

```bash
npm run dev
```

Das Frontend startet auf **http://localhost:3000**.

### 3.4 Verfuegbare npm-Scripts

| Script | Beschreibung |
|---|---|
| `npm run dev` | Vite Dev-Server mit HMR (Port 3000) |
| `npm run build` | TypeScript-Check + Production Build |
| `npm run preview` | Production Build lokal testen |
| `npm run lint` | ESLint ausfuehren |
| `npm test` | Vitest-Unit-Tests (einmalig) |
| `npm run test:watch` | Vitest im Watch-Modus |
| `npm run cy:open` | Cypress interaktiv oeffnen |
| `npm run cy:run` | Cypress headless ausfuehren |
| `npm run test:e2e` | Alias fuer `cy:run` |

---

## 4. Docker-Container bauen (optional)

Die Agent-Container werden fuer Skill-Ausfuehrung benoetigt:

```bash
cd ai-automation-backend

# Alle Agent-Images bauen
docker compose --profile build-only build

# Einzelne Images
docker build -f docker/Dockerfile.agent-python -t automation-agent-python:latest .
docker build -f docker/Dockerfile.agent-node -t automation-agent-node:latest .
docker build -f docker/Dockerfile.agent-devops -t automation-agent-devops:latest .
docker build -f docker/Dockerfile.agent-java -t automation-agent-java:latest .
```

### Docker-basierter Start (alternativ)

```bash
cd ai-automation-backend

# Nur Backend via Docker
docker compose up

# Backend + Agent-Images bauen
docker compose --profile build-only up --build
```

---

## 5. Tests ausfuehren

### Backend (JUnit)

```bash
cd ai-automation-backend
mvn test
```

Stand 2026-09-18 enthaelt `src/test/java` 715 Testklassen. Die Zahl altert -- im Zweifel frisch erheben statt zitieren:

```bash
find src/test/java \( -name '*Test.java' -o -name '*Tests.java' \) | wc -l
```

### Frontend (Vitest + Cypress E2E)

```bash
cd ai-automation-frontend

# Unit-Tests (kein laufendes Backend noetig)
npm test

# E2E -- Voraussetzung: Backend muss laufen!
npm run test:e2e    # Headless
npm run cy:open     # Interaktiv
```

Stand 2026-09-18: 114 Vitest-Dateien unter `src/` und 19 Cypress-Specs unter `cypress/e2e/`. Frisch erheben:

```bash
find src \( -name '*.test.ts' -o -name '*.test.tsx' \) | wc -l
ls cypress/e2e | wc -l
```

> **Hinweis:** Cypress erkennt automatisch den API-Key aus der SQLite-Datenbank (siehe `cypress.config.ts`).

---

## 6. Erste Schritte nach dem Setup

1. **Browser oeffnen:** http://localhost:3000
2. **Dashboard pruefen:** Zeigt Systemstatus und aktuelle Statistiken
3. **Projekt erstellen:** Unter "Projects" ein neues Projekt anlegen (Name, Typ, Pfad)
4. **Workflow erstellen:** Unter "Workflows" einen neuen Workflow mit Template starten
5. **Workflow ausfuehren:** Im Workflow-Editor "Execute" klicken

### API-Key (bei aktivierter Security)

Wenn `AUTOMATION_SECURITY_ENABLED=true`:
1. Der API-Key wird beim ersten Start generiert
2. Im Frontend unter "Settings" den API-Key eintragen
3. Alternativ: `AUTOMATION_API_KEY` als Umgebungsvariable setzen

---

## Haeufige Setup-Probleme

| Problem | Loesung |
|---|---|
| `Port 9090 already in use` | Anderen Prozess auf Port 9090 beenden oder `AUTOMATION_PORT` setzen |
| `Port 3000 already in use` | Vite waehlt automatisch den naechsten freien Port |
| `SQLite BUSY` | Nur eine Backend-Instanz gleichzeitig starten |
| `Claude CLI not found` | Sicherstellen dass `claude` im PATH ist |
| `Docker socket permission denied` | Benutzer zur `docker`-Gruppe hinzufuegen: `sudo usermod -aG docker $USER` |
| `CORS-Fehler im Browser` (403 `Invalid CORS request`) | Die Adresse, unter der die Oberflaeche aufgerufen wird, samt der localhost-Eintraege in `AUTOMATION_CORS_ORIGINS` aufnehmen — Security abzuschalten aendert an CORS nichts |
| `mvn: command not found` | Maven installieren oder Maven Wrapper nutzen: `./mvnw` |
