# Getting Started — MySuricates auf einem neuen Rechner

Diese Anleitung bringt MySuricates von `git clone` bis „läuft im Browser" — auf einem frischen
**Linux**- oder **macOS**-Rechner, auf dem evtl. noch kein Java, Node oder Docker installiert ist.
Unter **Windows** läuft derselbe Weg in WSL2 (Ubuntu), siehe Abschnitt 5.

> **Bevor du startest — das Vertrauensmodell in drei Sätzen.** Shell-Blöcke und KI-Aufrufe laufen
> mit *deinen* Rechten (auf dem Rechner bzw. mit deinen eingebundenen Ordnern im Container); die
> KI-CLIs fragen dabei standardmäßig nicht vor Werkzeugnutzung nach. Die vom Installer eingerichtete
> Instanz lauscht nur auf `127.0.0.1` (erst `--bind` öffnet sie ins Netz), einige Endpunkte
> (Healthchecks, WebSocket-Handshake, Webhook-Eingang, OAuth-Rückleitungen) brauchen keinen API-Key.
> MySuricates ist für den lokalen Betrieb durch eine vertrauenswürdige Person gebaut — für
> Fernzugriff gehört ein VPN davor, kein offener Port.
> Vollständig: [Vertrauensmodell und Netzwerk-Exposition](deployment.md#vertrauensmodell-und-netzwerk-exposition).

---

## 1. Was du brauchst

Fast nichts — der Installer prüft, was fehlt, zeigt die Befehle und installiert es nach einer
Rückfrage im selben Durchgang (apt, dnf, pacman, zypper oder Homebrew; Java und Node notfalls als
geprüfter Download ohne sudo).

| Weg | Auf dem Rechner nötig |
|---|---|
| **Eine Instanz im Container** (Vorschlag) | Docker |
| **Eine Instanz auf diesem Rechner** | Java 21, Node 20.19+/22.12+/24+ (nur zum Bauen), git, curl |
| **Gestaffelt — Dev · Test · Prod** | Docker |

Für KI-Workflows brauchst du **eine KI-CLI deiner Wahl** — `claude`, `codex` oder `pi` —, bei der
du angemeldet bist. MySuricates startet auch ohne; der Installer zeigt, welche er findet.

---

## 2. Installieren

```bash
git clone <repository-url> mysuricates
cd mysuricates
./scripts/setup.sh              # Englisch; auf Deutsch: ./scripts/setup.sh --lang de
```

Der Installer führt in acht Schritten durch: Willkommen · Modus · Prüfung · Module · KI · Ports ·
Bauen · Fertig. Mit `b` geht es einen Schritt zurück, mit `q` wird abgebrochen.

- **Modus:** eine Instanz im Container (nur Docker nötig), eine Instanz direkt auf diesem Rechner,
  oder gestaffelt **Dev · Test · Prod** — in Dev Workflows planen und bauen (Factory, Assistent),
  in Test prüfen, in Prod verlässlich laufen lassen. Workflows wandern vorerst per Export und
  Import in der Oberfläche; ein automatischer Abgleich zwischen den dreien ist noch nicht eingerichtet.
- **Module:** Standard (kleinste Oberfläche), Research, Voll oder eine eigene Auswahl samt Härtung.
  Die Namen sagen beim ersten Lauf noch wenig — das ist in Ordnung: Die Wahl ist nicht endgültig
  (Abschnitt 4), im Zweifel Standard nehmen. Was die Module tun, steht in
  [architektur.md](architektur.md) und [deployment.md](deployment.md).
  Beim gestaffelten Betrieb bekommt Dev alle Module, Test und Prod Standard mit Siegeln
  („unbeaufsichtigt nur versiegelte Workflows“).
- **KI:** claude, codex und pi gleichrangig mit Anmeldestatus; du wählst den Standard-Anbieter.
  Im Container nutzt die Instanz die CLI **und** deine Anmeldung vom Rechner — kein zweites Login
  (unter macOS nur die Anmeldung, das Programm liegt im Image).
- **Ports:** Default 9090, belegte Ports werden übersprungen. Beim Container zusätzlich der
  Projektordner, den die Instanz sieht (Default: der Ordner über dem Klon, nie das ganze Home).

Ohne Rückfragen, z. B. für Skripte: die Entscheidungen der Schritte gibt es als Flag (`--help` listet
sie); Härtung, ui-editable und weitere Einstellungen stehen in der Instanz-Datei (Abschnitt 4).

```bash
./scripts/setup.sh --yes                                   # Defaults: Container, Standard, Port 9090
./scripts/setup.sh --yes --runtime host --port 9190        # auf diesem Rechner, anderer Port
./scripts/setup.sh --yes --mode staged                     # Dev · Test · Prod
./scripts/setup.sh --check                                 # nur prüfen und den Plan zeigen
./scripts/setup.sh --help                                  # alle Flags
```

Ohne Terminal (Pipe, CI) und ohne `--yes` prüft der Installer nur.

---

## 3. Anmelden und benutzen

Am Ende zeigt der Installer die Adresse (z. B. http://localhost:9090) und die letzten Zeichen des
API-Keys. Den ganzen Key holst du so:

```bash
scripts/instance.sh key --name main
```

Beim **ersten Öffnen** erscheint ein **Login-Screen** mit einem Eingabefeld — Key einfügen, danach
merkt sich der Browser ihn. Der Key steht **nicht** im Backend-Log (dort nur maskiert).

Starten, stoppen und ansehen — für jeden Weg derselbe Befehl:

```bash
scripts/instance.sh up|down|status|logs|key --name main
scripts/instance.sh list                         # alle Instanzen mit Port
```

### Und jetzt?

Damit läuft die Anwendung. Ein erster Weg hinein, ohne etwas zu bauen:

1. **Projekt anlegen** — jeder Workflow gehört zu einem Projekt; ein Projekt zeigt auf ein
   Verzeichnis auf deinem Rechner (beim Container: innerhalb des Projektordners). Zum Ausprobieren
   gibt es das **Beispielprojekt**: Dashboard, Workflows und Projekte bieten es an, solange noch kein
   Projekt existiert. Ein Klick, und die Beispiel-Workflows laufen ohne KI und ohne Einrichtung.
2. **Eine Vorlage übernehmen** — unter *Templates* liegen 67 fertige Workflows. Eine auswählen,
   ins Projekt kopieren, starten. Der Lauf zeigt Schritt für Schritt, was passiert.
3. **Den Lauf ansehen** — die Detailansicht zeigt je Block Dauer, Status und ob er deterministisch
   war oder ein Modell gerufen hat.

Eigene Workflows baut man danach am besten mit dem
[Workflow-Leitfaden](WORKFLOW-GUIDE.md) daneben.

---

## 4. Einstellungen später ändern

`./scripts/setup.sh` erneut starten → **Einstellungen ändern** (Port, Module, KI-Anbieter).
Oder direkt die Datei `deploy/instances/main.env` bearbeiten und `scripts/instance.sh up --name main`
ausführen:

- `MYSURICATES_PORT`, `MYSURICATES_BIND`, `MYSURICATES_PRESET`, `MYSURICATES_PROJECTS`
- `MYSURICATES_DOCKER_SOCKET=1` (oder `setup.sh --docker-socket`) — erlaubt Shell-Blöcke mit
  `container_image`, gibt der Instanz aber volle Kontrolle über Docker auf dem Rechner
- jede `AUTOMATION_…`-Zeile geht unverändert an die Instanz, z. B. `AUTOMATION_MODULES_WEBHOOKS=true`
  oder `AUTOMATION_AI_DEFAULT_PROVIDER=CODEX`

Welche Module es gibt, mit Reifegrad und Risiko, zeigt **Settings → Module & Härtung**. Details zu
den Instanzen: [deployment.md](deployment.md#eine-instanz-im-container-deploy).

---

## 5. Windows

Unter Windows läuft MySuricates in **WSL2**: `wsl --install -d Ubuntu`, dann in der Ubuntu-Shell
Abschnitt 2. Der Browser unter Windows erreicht `http://localhost:9090` direkt. Ein nativer
Windows-Betrieb fehlt noch (Shell-Blöcke setzen bash voraus).

---

## 6. Für Mitwirkende: Quellcode-Setup

Wer an MySuricates selbst entwickelt, braucht Vite mit Hot Reload statt einer gebauten Oberfläche:

```bash
./scripts/setup.sh --from-source --start     # Werkzeuge, npm ci, Runtime-JAR, Modul-Preset, Start
./scripts/start-autotrigger-dev.sh           # später: Backend :9090 + Vite :3000
```

Der Dev-Starter zeigt den API-Key nach dem Start maskiert (`--show-key` zeigt ihn ganz); er liegt in
`~/.autotrigger/api-key`. Details: [entwicklung.md](entwicklung.md).

---

## 7. Optional: HTTPS im Dev-Frontend (Quellcode-Setup)

Für HTTPS auf https://localhost:3000 (selbst-signiert, z. B. für PWA-Install oder Tailscale):

```bash
./scripts/generate-dev-cert.sh
```

Erzeugt `certs/dev.{crt,key,p12}`. Der PKCS12-Keystore wird mit dem Dev-Passwort `mysuricates`
angelegt — demselben Wert, den `application.yml` als Default von `AUTOMATION_SSL_PASSWORD` führt.
Das ist ein bekannter Entwicklungs-Default für ein selbst-signiertes Zertifikat, kein Geheimnis.
Wer HTTPS auch im Backend nutzt, setzt `AUTOMATION_SSL_ENABLED=true` und vergibt über
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
| Werkzeug fehlt oder ist zu alt | `./scripts/setup.sh --check` zeigt, was fehlt, und den genauen Befehl |
| `permission denied` auf den Docker-Socket | der Installer hat dich in die Gruppe `docker` aufgenommen — einmal ab- und anmelden |
| Docker installiert, läuft aber nicht | Linux: `sudo systemctl start docker`, macOS: Docker Desktop öffnen |
| Port belegt | der Installer nimmt den nächsten freien; ändern in `deploy/instances/main.env` |
| KI-Block schlägt fehl, „nicht angemeldet“ | bei der CLI anmelden: `claude auth login`, `codex login` oder `pi` → `/login` |
| Instanz startet nicht | `scripts/instance.sh logs --name main`; Setup-Protokolle unter `~/.local/state/mysuricates/setup-logs/` |
| Settings zeigen „Docker nicht eingerichtet“ | kein Fehler — nur Shell-Blöcke mit `container_image` brauchen Docker |

---

## Weiterführend

- [Entwicklung](entwicklung.md) — Build, Tests, Konventionen
- [Deployment](deployment.md) — Docker, Umgebungsvariablen, Staging
- [Architektur](architektur.md) — Systemübersicht, Block-Typen
- [Workflow-Guide](WORKFLOW-GUIDE.md) — Workflows bauen
