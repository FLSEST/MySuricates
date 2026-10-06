# Getting Started — MySuricates auf einem neuen Rechner

Diese Anleitung bringt MySuricates von `git clone` bis „läuft im Browser" — auf einem frischen
**Linux**- oder **macOS**-Rechner, auf dem evtl. noch kein Java, Node oder Docker installiert ist.
Unter **Windows** läuft derselbe Weg in WSL2 (Ubuntu), siehe Abschnitt 5.

> **Bevor du startest — das Vertrauensmodell in drei Sätzen.** Shell-Blöcke und KI-Aufrufe laufen
> mit *deinen* Rechten (auf dem Rechner bzw. mit deinen eingebundenen Ordnern im Container); die
> KI-CLIs fragen dabei standardmäßig nicht vor Werkzeugnutzung nach. Die vom Installer eingerichtete
> Instanz lauscht nur auf `127.0.0.1` (erst `--bind` öffnet sie ins Netz; dann nennt der Installer auch die Netzadresse des Rechners), einige Endpunkte
> (Healthchecks, WebSocket-Handshake, Webhook-Eingang, OAuth-Rückleitungen) brauchen keinen API-Key.
> MySuricates ist für den lokalen Betrieb durch eine vertrauenswürdige Person gebaut — für
> Fernzugriff gehört ein VPN davor, kein offener Port.
> Vollständig: [Vertrauensmodell und Netzwerk-Exposition](deployment.md#vertrauensmodell-und-netzwerk-exposition).

---

## Nur ausprobieren — ohne Installation

Wer MySuricates erst kennenlernen will, braucht nur Docker und startet einen Container, der alles
mitbringt: KI-CLIs (`claude`, `codex`, `pi`), Python mit pip/venv, Node, JDK und Maven. Vom Rechner
wird nichts eingebunden — kein Projektordner, kein Docker-Socket, keine vorhandene Anmeldung.
Workflows sehen nur, was im Container liegt; eigene Projekte legst du unter `/app/data/home` an
oder klonst sie dort hinein.

Mit dem Repository (baut aus dem neuesten Release-Tag):

```bash
scripts/tryout.sh build          # Image mysuricates:<version>-tryout
scripts/tryout.sh start          # http://localhost:9095, zeigt den API-Key
scripts/tryout.sh login          # KI-Anmeldung im Container (claude; auch: login codex / login pi)
scripts/tryout.sh remove --data  # Container, Daten und Anmeldungen wieder weg
```

Ohne Repository, mit einer Image-Datei (`scripts/tryout.sh export` erzeugt sie):

```bash
docker load < mysuricates-<version>-tryout.tar.gz
docker run -d --name mysuricates-tryout -p 127.0.0.1:9095:9090 \
  -e AUTOMATION_CORS_ORIGINS=http://localhost:9095 \
  -v mysuricates-tryout-data:/app/data -v mysuricates-tryout-secrets:/app/secrets mysuricates:tryout
docker exec -u 1000 mysuricates-tryout cat /app/data/home/.mysuricates/api-key  # API-Key (Images bis v0.7.8: .autotrigger)
docker exec -it -u 1000 mysuricates-tryout claude                                  # KI-Anmeldung
```

Statt der Anmeldung geht auch ein Schlüssel per Umgebungsvariable (`-e ANTHROPIC_API_KEY=…`).
Ob eine Anmeldung angekommen ist, zeigt die Startseite.
Ohne Anmeldung laufen nur Shell- und deterministische Blöcke — die Blaupausen außerhalb der
Kategorie `AI` kommen damit aus. Für die Arbeit an eigenen Projekten auf dem Rechner ist der
Installer (Abschnitt 2) der richtige Weg. Details: [Deployment → Tryout-Image](deployment.md#tryout-image).

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
du angemeldet bist — per Anmeldedatei oder per Umgebungsvariable (`CLAUDE_CODE_OAUTH_TOKEN`,
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY` …). Eine Instanz im Container sieht Anmeldedateien und, was in
`~/.config/mysuricates/ai.env` steht — für Claude einen Token, sonst die Variable; dorthin kommt sie nur
mit deiner Zustimmung ([Deployment → KI-Anbieter im Container](deployment.md#ki-anbieter-im-container)).
MySuricates startet auch ohne; der Installer zeigt, welche er findet,
und die Startseite der Oberfläche sagt es ebenso — samt Befehl für das, was fehlt.

---

## 2. Installieren

```bash
git clone https://github.com/FLSEST/MySuricates.git
cd MySuricates
./scripts/setup.sh              # Englisch; auf Deutsch: ./scripts/setup.sh --lang de
```

Der Installer führt in acht Schritten durch: Willkommen · Modus · Prüfung · Module · KI · Ports ·
Bauen · Fertig. Mit `b` geht es einen Schritt zurück, mit `q` wird abgebrochen.

- **Modus:** eine Instanz im Container (nur Docker nötig), eine Instanz direkt auf diesem Rechner,
  oder gestaffelt **Dev · Test · Prod** — in Dev Workflows planen und bauen (Factory, Assistent),
  in Test prüfen, in Prod verlässlich laufen lassen. Dev überträgt seine Definitionen unter
  **Einstellungen → Umgebungen** nach Test und Prod — nach Prod nur versiegelte. Die Ziele und den Weg
  zu ihren API-Keys trägt der Installer ein; laufen Test oder Prod beim Einrichten noch nicht, holt
  `scripts/instance.sh sync-keys --name dev` die Keys später ([deployment.md](deployment.md#gestufter-betrieb-dev--test--prod)).
- **Module:** Standard (kleinste Oberfläche), Research, Voll oder eine eigene Auswahl samt Härtung.
  Die Namen sagen beim ersten Lauf noch wenig — das ist in Ordnung: Die Wahl ist nicht endgültig
  (Abschnitt 4), im Zweifel Standard nehmen. Was die Module tun, steht in
  [architektur.md](architektur.md) und [deployment.md](deployment.md).
  Beim gestaffelten Betrieb bekommt Dev alle Module und Siegel (ohne Pflicht), Test und Prod Standard
  mit Siegeln („unbeaufsichtigt nur versiegelte Workflows“).
- **KI:** claude, codex und pi gleichrangig mit Anmeldestatus; du wählst den Standard-Anbieter.
  Fehlt pi, bietet der Installer an, es per npm mitzuinstallieren — in der geprüften, festen Version
  (Vorgabe nein, ohne Terminal `--with-pi`); für claude und codex nennt er den Befehl. Anmelden musst du dich selbst.
  Im Container nutzt die Instanz die CLI **und** deine Anmeldung vom Rechner — kein zweites Login
  (unter macOS nur die Anmeldung, das Programm liegt im Image). Bist du bei codex oder pi nur per
  Umgebungsvariable angemeldet, fragt der Installer, ob er sie nach `~/.config/mysuricates/ai.env`
  übernehmen soll (Vorgabe nein; ohne Terminal nur mit `--store-ai-env`, auch mit `--yes` nicht von selbst).
- **Ports:** Default 9090, belegte Ports werden übersprungen — nicht der, den die laufende Instanz
  selbst belegt, wenn sie neu eingerichtet wird. Beim Container zusätzlich der
  Projektordner, den die Instanz sieht (Default: der Ordner über dem Klon, nie das ganze Home).

Ohne Rückfragen, z. B. für Skripte: die Entscheidungen der Schritte gibt es als Flag (`--help` listet
sie); Härtung, ui-editable und weitere Einstellungen stehen in der Instanz-Datei (Abschnitt 4).

```bash
./scripts/setup.sh --yes                                   # Defaults: Container, Standard, Port 9090
./scripts/setup.sh --yes --runtime host --port 9190        # auf diesem Rechner, anderer Port
./scripts/setup.sh --yes --mode staged                     # Dev · Test · Prod
./scripts/setup.sh --yes --mode staged --port 19090        # dasselbe ab Port 19090 (Test 19091, Prod 19092)
./scripts/setup.sh --check                                 # nur prüfen und den Plan zeigen
./scripts/setup.sh --help                                  # alle Flags
```

Ohne Terminal (Pipe, CI) und ohne `--yes` prüft der Installer nur.

Sind Java oder Node des Paketmanagers zu alt, lädt der Installer sie nach `~/.local/share/mysuricates`.
Die Instanz findet sie dort selbst, deine Shell erst mit einer Zeile im Profil — sonst fehlen dort
`node`, `npm` und was `npm install -g` dorthin legt (codex, pi):

```bash
. ~/.local/share/mysuricates/env.sh      # in ~/.profile (bash mit ~/.bash_profile: dort; macOS: ~/.zprofile)
```

Im geführten Ablauf bietet der Installer an, die Zeile einzutragen; mit `--yes` zeigt er sie nur an.

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

**Nach einem Neustart des Rechners:** Eine Instanz im Container startet von selbst wieder, sobald der
Docker-Dienst läuft. Eine Instanz auf diesem Rechner (`--runtime host`) startet **nicht** von selbst —
dann `scripts/instance.sh up --name main`. Einen Autostart dafür richtet der Installer nicht ein.

### Und jetzt?

Damit läuft die Anwendung. Einen festen Weg hinein gibt es nicht — nimm das Beispiel, das zu dir
passt. Jedes zeigt andere [Bausteine](baukasten.md), und nur das letzte setzt ein Software-Projekt voraus.

| Beispiel | Wo | Was es zeigt |
|---|---|---|
| Beispielprojekt | Dashboard, Workflows und Projekte bieten es an, solange es noch kein Projekt gibt | Fünf Workflows ohne KI und ohne Einrichtung: Freigabe, Wenn-dann-sonst, Schleife, TODO-Lebenszyklus, Meldung ans Dashboard |
| „Ordner-Backup (tar.gz)“ | Vorlagen | Feste Schritte erledigen die Arbeit (Archive, Prüfsummen), ein KI-Schritt fasst am Ende zusammen; per Zeitplan etwa jede Nacht |
| „Dokument-Prüfung“ | Vorlagen | KI-Schritte prüfen Grammatik und Fakten und fassen zusammen; das Ergebnis liest du im Lauf |
| „Iterative Verbesserung“ | Vorlagen | Ein Text wird in einer Schleife verbessert, bis die Qualität stimmt |
| „SSL-Zertifikat-Prüfung“ | Vorlagen | Je Domain ein fester Prüfschritt, danach eine Meldung über anstehende Verlängerungen |
| „Ziel-Wächter“ | Schalter „Wächter“ auf der Seite eines Ziels | Prüft täglich ohne KI, was an einem Ziel überfällig ist, und meldet eine Ampel ans Dashboard |
| „Code-Qualität“ | Vorlagen | Lint, Tests mit Wiederholung und Build, ganz ohne KI |

Für jedes Beispiel gilt:

1. **Projekt anlegen** — jeder Workflow gehört zu einem Projekt; ein Projekt zeigt auf ein
   Verzeichnis auf deinem Rechner (beim Container: innerhalb des Projektordners). Das
   Beispielprojekt legt sich mit einem Klick selbst an und bekommt eine eigene Dashboard-Ansicht.
2. **Eine Vorlage übernehmen** — unter *Templates* liegen 54 fertige Workflows. Eine auswählen,
   ins Projekt kopieren, starten. Der Lauf zeigt Schritt für Schritt, was passiert. Vorlagen, die
   ein auf dieser Instanz ausgeschaltetes Modul brauchen, stehen hinter einem Zähler, und der Filter
   „Läuft ohne KI“ zeigt nur, was kein Modell ruft.
3. **Den Lauf ansehen** — die Detailansicht zeigt je Block Dauer, Status und ob er deterministisch
   war oder ein Modell gerufen hat.

Ein Projekt lässt sich auch mitnehmen: Auf der Projektseite legt **Exportieren** Workflows, Ketten
und Zeitpläne des Projekts als eine einzelne `.mysuricates-bundle.json`-Datei an. Auf der
Projektliste nimmt **Projekt importieren** so eine Datei zurück entgegen — als neues oder in ein
vorhandenes Projekt. Weil absolute Pfade (Projektverzeichnis, aber auch feste Pfade in
Shell-Blöcken) sich zwischen Rechnern unterscheiden, fragt der Import-Dialog vor dem eigentlichen
Import je Fundstelle nach dem passenden Pfad auf diesem Rechner — vorbelegt mit dem Pfad vom
exportierenden Rechner. Die Vorschau klappt vorher jeden Shell-Befehl auf: Er läuft nach dem Import
mit deinen eigenen Rechten auf diesem Rechner, ein Blick lohnt sich also, besonders bei einem
fremden Bündel. Zeitpläne kommen bewusst **deaktiviert** an und müssen erst nach dem Import wieder
eingeschaltet werden.

Unterwegs erklären kurze **Hinweise** die Seiten und Zustände, die man zum ersten Mal sieht, etwa
eine wartende Freigabe oder einen fehlgeschlagenen Lauf. Jeder lässt sich wegklicken und an seiner
Stelle zurückholen; unter *Einstellungen → Allgemein* lassen sich alle abschalten.

Eigene Workflows baut man danach am besten mit dem
[Workflow-Leitfaden](WORKFLOW-GUIDE.md) daneben. Workflows aus einer Beschreibung in Worten bauen
Assistent und Workflow-Factory; im Preset `core` sind sie aus, die Vorlagen-Auswahl sagt dann, wie
man sie zuschaltet.

---

## 4. Einstellungen später ändern

`./scripts/setup.sh` erneut starten → **Einstellungen ändern** (Port, Module, KI-Anbieter).
Mit `--yes` und ohne `--reconfigure` bleibt eine vorhandene Instanz-Datei, wie sie ist: Die Schritte
zeigen dann ihre Werte („aus deploy/instances/main.env“), und Flags, die dagegen nicht wirken, nennt
der Installer; `--reconfigure` schreibt die Datei neu — auch ohne Terminal von ihren Werten aus, nur
die übergebenen Flags ändern etwas (`--reconfigure --yes --port 9095` verlegt nur den Port).
Oder direkt die Datei `deploy/instances/main.env` bearbeiten und die Instanz neu starten
(`scripts/instance.sh down --name main`, dann `up --name main`):

- `MYSURICATES_PORT`, `MYSURICATES_BIND`, `MYSURICATES_PRESET`, `MYSURICATES_PROJECTS`
- `MYSURICATES_DOCKER_SOCKET=1` (oder `setup.sh --docker-socket`) — erlaubt Shell-Blöcke mit
  `container_image`, gibt der Instanz aber volle Kontrolle über Docker auf dem Rechner
- jede `AUTOMATION_…`-Zeile geht unverändert an die Instanz, z. B. `AUTOMATION_MODULES_WEBHOOKS=true`
  oder `AUTOMATION_AI_DEFAULT_PROVIDER=CODEX`

Welche Module es gibt, mit Reifegrad und Risiko, zeigt **Settings → Module & Härtung**. Details zu
den Instanzen: [deployment.md](deployment.md#eine-instanz-im-container-deploy).

### Aktualisieren

Nach `git pull` die Instanz einmal stoppen und wieder starten — `up` baut dann aus dem neuen Stand:

```bash
git pull
scripts/instance.sh down --name main
scripts/instance.sh up --name main
```

Im Container baut `up` das Image jedes Mal neu (unveränderte Schichten kommen aus dem Cache). Auf dem
Rechner merkt sich `instance.sh` beim Bauen den Commit und baut neu, sobald der Klon auf einem anderen
steht; eine laufende Instanz baut `up` nicht um, sondern meldet, dass sie noch den alten Stand zeigt.
Aus welchem Commit der Build stammt, zeigt `scripts/instance.sh built --name main`. Die Daten bleiben
in beiden Fällen erhalten. Ein erneuter Lauf von `./scripts/setup.sh` bringt die Instanz ebenfalls auf
den neuen Stand.

Ein Neustart bricht Workflow-Runs ab, die gerade laufen. Bevor der Installer eine laufende Instanz neu
startet, fragt er sie deshalb, ob welche laufen (nur lesend, mit dem Key aus `instance.sh key`). Laufen
welche, fragt er zurück — Vorgabe: nicht neu starten; mit `--yes` startet er sie dann nicht neu und
nennt den Befehl für später. Antwortet die Instanz nicht, geht es weiter wie bisher, mit einem Hinweis.
`scripts/instance.sh down` nennt vor dem Stoppen, wie viele Runs dabei abbrechen.

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
./scripts/start-mysuricates-dev.sh           # später: Backend :9090 + Vite :3000
```

Der Dev-Starter zeigt den API-Key nach dem Start maskiert (`--show-key` zeigt ihn ganz); er liegt in
`~/.mysuricates/api-key`. Details: [entwicklung.md](entwicklung.md).

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
| KI-Block schlägt fehl, „nicht angemeldet“ | bei der CLI anmelden: `claude auth login`, `codex login` oder `pi` → `/login`; ob Programm und Anmeldung gefunden werden, zeigen Startseite und Einstellungen je Anbieter |
| `npm`, `node` oder `pi` nach dem Installer nicht gefunden | Node kam als Download: `. ~/.local/share/mysuricates/env.sh`, dauerhaft als Zeile im Profil (Abschnitt 2) |
| Instanz startet nicht | `scripts/instance.sh logs --name main`; Setup-Protokolle unter `~/.local/state/mysuricates/setup-logs/` |
| Nach einem Neustart des Rechners nicht erreichbar | Instanz auf dem Rechner: `scripts/instance.sh up --name main` (kein Autostart); im Container: startet mit dem Docker-Dienst |
| Settings zeigen „Docker nicht eingerichtet“ | kein Fehler — nur Shell-Blöcke mit `container_image` brauchen Docker |

---

## Weiterführend

- [Entwicklung](entwicklung.md) — Build, Tests, Konventionen
- [Deployment](deployment.md) — Docker, Umgebungsvariablen, Staging
- [Architektur](architektur.md) — Systemübersicht, Schichten, Sicherheit
- [Workflow-Guide](WORKFLOW-GUIDE.md) — Workflows bauen
