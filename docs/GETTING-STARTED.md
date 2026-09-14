# Getting Started — MySuricates auf einem neuen Linux-Rechner

Diese Anleitung bringt MySuricates von `git clone` bis „läuft im Browser" — auf einem frischen
**Linux**-Rechner, auf dem evtl. noch kein Java, Node oder Docker installiert ist.

Empfohlener Weg: **direkt auf dem Host aus den Quellen** (nicht containerisiert). Das ist hier
einfacher und zuverlässiger, weil der `claude` CLI ohnehin auf dem Host läuft, kein JAR-Rebuild
nötig ist und Datenbank/Migrationen ohne Volume-Mapping funktionieren. **Docker ist optional**
und wird nur für Container-Execution-Workflow-Blöcke gebraucht.

> **Bevor du startest — das Vertrauensmodell in drei Sätzen.** Shell-Blöcke und KI-Aufrufe laufen
> auf dem Host mit *deinen* Rechten, nicht in einem Container; die Claude CLI fragt dabei
> standardmäßig nicht vor Werkzeugnutzung nach. Backend und Frontend lauschen auf allen
> Netzwerk-Interfaces, und einige Endpunkte (Healthchecks, WebSocket-Handshake, Webhook-Eingang,
> OAuth-Rückleitungen) brauchen keinen API-Key. MySuricates ist für den lokalen Betrieb durch eine
> vertrauenswürdige Person gebaut — für Fernzugriff gehört ein VPN davor, kein offener Port.
> Vollständig: [Vertrauensmodell und Netzwerk-Exposition](deployment.md#vertrauensmodell-und-netzwerk-exposition).

---

## 1. Was du brauchst

| Tool | Version | Pflicht? | Wofür |
|---|---|---|---|
| Java (JDK) | 21+ | ✅ | Backend bauen/starten |
| Maven | — | ⬜ optional | Wrapper `ai-automation-backend/mvnw` ist dabei und lädt Maven 3.9.9 selbst (nur Java + curl nötig) |
| Node.js | 20.19+, 22.12+ oder 24+ | ✅ | Frontend (Version in `ai-automation-frontend/.nvmrc` → `nvm use`) |
| npm | 10+ | ✅ | Frontend-Deps |
| git, curl | aktuell | ✅ | Basis |
| **Claude-Account + `claude` CLI** | aktuell | ✅ für KI | AI-Workflows laufen über `claude` auf dem Host |
| Docker | 24+ | ⬜ optional | nur Container-Execution-Blöcke |

> **System-Maven nicht nötig:** Der Maven-Wrapper (`mvnw`) lädt beim ersten Build automatisch die
> passende Maven-Version herunter. `scripts/setup.sh` und der Start nutzen ihn automatisch.
> Node-Nutzer mit `nvm` bekommen mit `nvm use` (im Frontend-Ordner) die richtige Node-Version.

Die Person braucht einen **eigenen Claude-Account** und meldet sich mit `claude login` selbst an.

Vieles richtet sich beim **ersten Backend-Start automatisch** ein: SQLite-Datenbank +
Flyway-Migrationen, API-Key, Verschlüsselungs-Key für Integrationen, Built-in-Skills/Templates.
Nichts davon muss manuell angelegt werden.

---

## 2. Setup in einem Befehl

```bash
git clone <repository-url> autotrigger
cd autotrigger
./scripts/setup.sh
```

`scripts/setup.sh` prüft alle Voraussetzungen, nennt für jedes fehlende/zu alte Tool den
passenden Install-Befehl (apt/dnf/pacman erkannt), installiert die Frontend-Abhängigkeiten und
kompiliert das Backend vor. Es greift **nie** auf die Datenbank oder die REST-API zu und
installiert ohne `--install` nichts von selbst.

Optionen:

```bash
./scripts/setup.sh --install   # fehlende Tools per apt automatisch installieren (sonst: Hinweise ausgeben)
./scripts/setup.sh --start     # nach erfolgreichem Setup direkt starten
```

Fehlt ein Pflicht-Tool, bricht das Skript mit einer klaren Liste + Install-Befehlen ab. Nach dem
Installieren einfach `./scripts/setup.sh` erneut ausführen.

---

## 3. Starten

```bash
./scripts/start-autotrigger-dev.sh
```

Startet Backend (Port **9090**) und Frontend (Port **3000**) im Hintergrund und wartet, bis beide
erreichbar sind. Danach im Browser öffnen:

- **Frontend:** http://localhost:3000
- **Backend-API:** http://localhost:9090/api/v1

Logs liegen unter `~/.local/state/autotrigger-dev/{backend,frontend}.log`.

> Ohne Dev-Zertifikat läuft das Frontend auf **HTTP**. Wer HTTPS will (z. B. für PWA-Installation),
> siehe Abschnitt 7.

---

## 4. API-Key eintragen

Beim ersten Backend-Start wird automatisch ein API-Key erzeugt. **`start-autotrigger-dev.sh` zeigt
ihn nach dem Start im Terminal an** — standardmäßig **maskiert** (nur die letzten 4 Zeichen), damit
er nicht versehentlich in Terminal-Scrollback/Logs/Screen-Sharing landet. Voll anzeigen mit dem Flag:

```bash
./scripts/start-autotrigger-dev.sh --show-key    # oder: ./scripts/setup.sh --show-key --start
```

Der Key liegt immer im Klartext in der Datei `~/.autotrigger/api-key` (und im Backend-Log):

```bash
cat ~/.autotrigger/api-key
```

Beim **ersten Öffnen** von `localhost:3000` erscheint automatisch ein **Login-Screen** mit einem
Eingabefeld (er zeigt den Key nicht an — du fügst ihn dort ein). Danach ist der Key im Browser
gespeichert; du wirst nicht erneut gefragt. Er wird für alle API-Aufrufe als Header `X-API-Key`
verwendet.

Eigenen Key vorgeben statt generieren lassen:

```bash
export AUTOMATION_API_KEY="dein-key"   # vor dem Start setzen
```

Die API-Key-Pflicht lässt sich für rein lokale Experimente abschalten
(`AUTOMATION_SECURITY_ENABLED=false`) — für den Normalbetrieb aktiviert lassen.

---

## 5. Claude CLI einrichten (für AI-Workflows)

Die KI-Blöcke führen `claude` auf dem Host aus. Einmalig:

```bash
npm install -g @anthropic-ai/claude-code   # falls noch nicht vorhanden
claude login                               # mit eigenem Claude-Account anmelden
```

`scripts/setup.sh` prüft, ob `claude` vorhanden und (heuristisch) eingeloggt ist, und weist sonst
darauf hin. Ohne `claude` startet die App trotzdem — nur AI-Workflows funktionieren dann nicht.

---

## 6. Optional: Docker (nur für Container-Blöcke)

Die App und alle Nicht-Container-Workflows (Simple, Shell, Agentic auf dem Host …) laufen **ohne**
Docker. Erst Workflow-Blöcke, die in isolierten Containern ausführen, brauchen einen laufenden
Docker-Daemon:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER     # danach neu einloggen
```

---

## 7. Optional: HTTPS im Dev-Frontend

Für HTTPS auf https://localhost:3000 (selbst-signiert, z. B. für PWA-Install oder Tailscale):

```bash
./scripts/generate-dev-cert.sh
```

Erzeugt `certs/dev.{crt,key,p12}`. Der PKCS12-Keystore wird mit dem Dev-Passwort `mysuricates`
angelegt — demselben Wert, den `application.yml` als Default von `AUTOMATION_SSL_PASSWORD` fuehrt.
Das ist ein bekannter Entwicklungs-Default fuer ein selbst-signiertes Zertifikat, kein Geheimnis.
Wer HTTPS auch im Backend nutzt, setzt `AUTOMATION_SSL_ENABLED=true` und vergibt ueber
`AUTOMATION_SSL_PASSWORD` einen eigenen Wert; das Skript nimmt ihn aus der Umgebung entgegen. Vite serviert dann automatisch HTTPS; der Browser warnt einmalig
wegen des selbst-signierten Zertifikats. Ohne dieses Skript bleibt es bei HTTP — völlig ausreichend
für die lokale Nutzung.

`config/SslPasswordGuard` prüft das beim Start des Backends: Ist `AUTOMATION_SSL_ENABLED=true`
gesetzt und `AUTOMATION_SSL_PASSWORD` noch der Dev-Default `mysuricates`, bricht der Boot mit einer
`IllegalStateException` ab. Damit läuft niemals ein HTTPS-Server mit dem aus dem Repo bekannten
Passwort — SSL aus mit dem Default bleibt weiterhin erlaubt.

---

## 8. Troubleshooting

| Problem | Lösung |
|---|---|
| `mvn`/`java`/`node` „not found" | `./scripts/setup.sh` ausführen — es nennt den genauen Install-Befehl |
| Java zu alt (`benötigt 21+`) | JDK 21 installieren (apt: `openjdk-21-jdk`, sonst https://adoptium.net) |
| `claude: command not found` | `npm install -g @anthropic-ai/claude-code` + `claude login` |
| AI-Block schlägt fehl, „nicht eingeloggt" | `claude login` ausführen |
| Port 9090 / 3000 belegt | belegenden Prozess beenden (`lsof -i :9090`). Das Start-Skript setzt 9090/3000 voraus; andere Ports nur beim manuellen Start: Backend `AUTOMATION_PORT=9190`, Frontend `VITE_API_BASE_URL=http://localhost:9190/api/v1 VITE_WS_BASE_URL=ws://localhost:9190 npm run dev -- --port 3100` |
| Frontend startet, aber Skript meldet „nicht erreichbar" | behoben — das Start-Skript prüft jetzt HTTP **und** HTTPS |
| Container-Block schlägt fehl | Docker installieren/starten (Abschnitt 6) |
| `permission denied` auf Docker-Socket | `sudo usermod -aG docker $USER`, dann neu einloggen |
| Self-signed-Cert-Warnung im Browser | bei Dev erwartet; akzeptieren oder Abschnitt 7 / kein Cert nutzen |

---

## Weiterführend

- [Entwicklung](entwicklung.md) — Build, Tests, Konventionen
- [Deployment](deployment.md) — Docker, Umgebungsvariablen, Staging
- [Architektur](architektur.md) — Systemübersicht, Block-Typen
- [Workflow-Guide](WORKFLOW-GUIDE.md) — Workflows bauen
