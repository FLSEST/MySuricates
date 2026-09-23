# MySuricates als eine Instanz im Container — auf dem Rechner ist nur Docker noetig.
#
# Mehrstufig: Maven- und Node-Build laufen in Docker (docker/Dockerfile.stable kopiert dagegen ein
# auf dem Host gebautes JAR). Die Benutzer-ID kommt erst beim Start (deploy/entrypoint.sh), damit
# ein Image fuer alle taugt (TODO fc9de67b, spaeter Registry-Image 4c992a25).
#
#   docker build -f deploy/Dockerfile --target runtime -t mysuricates:local .   (Kontext = Repo-Wurzel)
#
# Zwei Ziele: `runtime` ist das Image fuer eine eingerichtete Instanz (Werkzeuge und KI-Logins kommen
# vom Rechner), `tryout` baut darauf auf und bringt alles selbst mit — zum Ausprobieren ohne
# Installation (scripts/tryout.sh, 40c31211). `runtime` steht vor `tryout`: der klassische Builder
# baut jede Stufe bis zum Ziel, `--target runtime` laesst die Tryout-Stufe also aus.
#
# Bewusst ohne BuildKit-Syntax (Cache-Mounts): ohne das buildx-Plugin baut Docker klassisch, und das
# fehlt vielen Installationen (Distributionspaket docker.io). Den Cache fuer Maven/npm liefern die
# Schichten: pom.xml bzw. package-lock.json unveraendert = Abhaengigkeiten nicht neu laden.
#   Normalerweise ueber scripts/instance.sh up --name NAME.

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src/ai-automation-backend
COPY ai-automation-backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY ai-automation-backend/src/main ./src/main
# maven-resources-plugin kopiert ../docs ins JAR (Doku-Seiten der App)
COPY docs /src/docs
RUN mvn -q -B -DskipTests package \
 && cp target/ai-automation-backend-*.jar /app.jar

FROM node:24-bookworm-slim AS frontend
WORKDIR /src/ai-automation-frontend
COPY ai-automation-frontend/package.json ai-automation-frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ai-automation-frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl git gosu python3 jq ca-certificates gnupg \
 && rm -rf /var/lib/apt/lists/*
# Node fuer die KI-CLIs (codex >= 16, pi >= 20.6) — wie docker/Dockerfile.stable
ARG NODE_MAJOR=22
RUN install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
 && apt-get update && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*
# Docker-CLI: Shell-Bloecke mit container_image laufen nur, WENN der Socket eingebunden ist
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list \
 && apt-get update && apt-get install -y --no-install-recommends docker-ce-cli \
 && rm -rf /var/lib/apt/lists/*
# Startdateien von codex/pi verlinken statt mounten: Docker loest Symlinks beim Bind-Mount auf, Node
# suchte die Abhaengigkeiten dann neben der Datei (a1236a64). Leerer Link = Anbieter nicht da.
ARG NODE_MODULES_DIR=/usr/lib/node_modules
RUN ln -sf ${NODE_MODULES_DIR}/@openai/codex/bin/codex.js /usr/local/bin/codex \
 && ln -sf ${NODE_MODULES_DIR}/@earendil-works/pi-coding-agent/dist/cli.js /usr/local/bin/pi

WORKDIR /app
COPY --from=backend /app.jar /app/app.jar
COPY --from=frontend /src/ai-automation-frontend/dist /app/frontend
COPY docs /app/docs
COPY workflow-pool/ /app/workflow-pool/
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && mkdir -p /app/data /app/secrets

# Alles, was ueber einen Neuaufbau des Containers hinaus bleiben muss, liegt auf Volumes:
# /app/data (DB, Logs, Backups, Workspaces, Reports) und /app/secrets (Integrations- und Siegel-Schluessel
# — absichtlich getrennt von der DB, siehe IntegrationEncryptionService und WorkflowSealSigner; 3fb2242c).
ENV AUTOMATION_DB_PATH=/app/data/automation.db \
    LOG_DIR=/app/data/logs \
    AUTOMATION_BACKUP_DIR=/app/data/backups \
    AUTOMATION_REPORTS_DIR=/app/data/reports \
    AUTOMATION_RUNWORKSPACE_ROOT=/app/data/workspaces \
    AUTOMATION_INTEGRATION_KEYFILE=/app/secrets/integration-encryption.key \
    AUTOMATION_SEAL_KEY_FILE=/app/secrets/seal-signing.key \
    AUTOMATION_DOCS_DIR=/app/docs \
    AUTOMATION_POOL_DIR=/app/workflow-pool \
    AUTOMATION_SERVEFRONTEND=true \
    AUTOMATION_FRONTENDPATH=/app/frontend \
    AUTOMATION_TRAY_ENABLED=false
EXPOSE 9090
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:9090/api/v1/system/health >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx1g", "-Djava.awt.headless=true", \
     "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/data/logs/", \
     "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------------------------------------------
# Tryout: ausprobieren ohne Installation. Nichts vom Rechner wird eingebunden, also bringt das Image
# mit, was die mitgelieferten Vorlagen und der Pool brauchen: python3 (+ pip/venv), Node, JDK + Maven,
# dazu die KI-CLIs. Angemeldet wird im Container (scripts/tryout.sh login). Kein gcc/Go/Rust/Gradle —
# wer mehr braucht, baut `FROM mysuricates:tryout` oder nimmt den Installer.
# ---------------------------------------------------------------------------------------------------
FROM runtime AS tryout
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3-pip python3-venv \
 && rm -rf /var/lib/apt/lists/*
# JDK und Maven aus der Build-Stufe: dieselben Versionen, kein weiterer Download
COPY --from=backend /opt/java/openjdk /opt/java/jdk
COPY --from=backend /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/local/bin/mvn
# Die Links auf codex/pi aus der runtime-Stufe zeigen genau auf diese Pakete
RUN npm install -g --no-audit --no-fund @anthropic-ai/claude-code @openai/codex @earendil-works/pi-coding-agent \
 && npm cache clean --force
# HOME auf dem Daten-Volume: KI-Logins, API-Key und eigene Projekte ueberstehen einen Neuaufbau.
# Preset research: Chains, Memory, Assistent … ja; Netz- und Host-Module (Fernzugriff, Staging,
# Terminal) bleiben aus — die ergeben ohne eingebundenen Rechner keinen Sinn.
ENV JAVA_HOME=/opt/java/jdk \
    PATH=/opt/java/jdk/bin:$PATH \
    HOST_HOME=/app/data/home \
    AUTOMATION_MODULES_PRESET=research \
    AUTOMATION_ENVIRONMENT=Tryout \
    DISABLE_AUTOUPDATER=1
