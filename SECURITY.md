# Security Policy

## Project status

MySuricates is a research / personal automation project, not a maintained
product. It is developed and run by a single person for local, single-user
use. There is no support commitment and no guaranteed response time for any
report — security included.

## Threat model (short version)

- Workflow steps can execute arbitrary shell commands and invoke AI CLIs
  (`claude`, `codex`, `pi`) **with the privileges of the host user running the
  backend** — by default there is no container sandbox between a workflow step
  and the host. The startup switch `automation.hardening.unattended-sandbox` (Linux,
  off by default) keeps steps outside runs a person started away from the
  instance's own key files and database (Landlock). It governs file access
  only, not requests to other services of the same user, so it is a partial
  protection, not a separation. The startup switch
  `automation.hardening.unattended-in-container` (off by default, needs Docker)
  separates them: such steps run in a fresh container that gets only the
  working directory, the CLI program and its login — not the home directory,
  the data directories or the Docker socket.
- API access is protected by a single static API key (`X-API-Key`), compared
  in constant time; a handful of endpoints (health/info/auth-status, webhook
  ingestion) are reachable without one.
- Webhook triggers accept inbound requests authenticated by a per-webhook
  token/signing secret, not the API key.

Given this, a vulnerability here can mean host code execution, not just data
exposure inside the app. See
[Vertrauensmodell und Netzwerk-Exposition](docs/deployment.md#vertrauensmodell-und-netzwerk-exposition)
for the full picture (currently German-only).

## Supported versions

Only the `main` branch is supported. There are no maintained release
branches and no backported fixes.

## Reporting a vulnerability

Please use GitHub's **private vulnerability reporting** (Security Advisories)
for this repository instead of a public issue or a mailing address:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Describe the issue, ideally with reproduction steps and impact.

This keeps the report private between you and the maintainer while it is
being looked at. Given the project's status above, there is no committed
response-time SLA, but reports will be read and taken seriously.

Please do not open a public issue for a suspected security vulnerability.

---

## Kurzfassung (Deutsch)

MySuricates ist ein Forschungs-/Hobbyprojekt eines einzelnen Entwicklers,
kein betreutes Produkt — es gibt keinen Support und keine Zusage zu
Reaktionszeiten, auch nicht bei Sicherheitsmeldungen. Workflow-Schritte
führen Shell-Befehle und KI-CLIs mit den Rechten des Host-Benutzers aus,
standardmäßig ohne Container-Sandbox (der Startschalter `automation.hardening.unattended-sandbox`
hält unter Linux Schritte außerhalb von Hand gestarteter Läufe von den
Schlüsseldateien und der Datenbank der Instanz fern, regelt aber nur
Dateizugriffe und ist damit ein Teilschutz; `automation.hardening.unattended-in-container`
trennt sie mit Docker ganz: Solche Schritte laufen in einem frischen Container,
der nur Arbeitsverzeichnis, CLI-Programm und dessen Anmeldung bekommt); der API-Zugriff läuft über einen einzelnen statischen
API-Key, einzelne Endpunkte (Health/Info/Auth-Status, Webhook-Eingang) sind
unauthentifiziert erreichbar. Details:
[Vertrauensmodell und Netzwerk-Exposition](docs/deployment.md#vertrauensmodell-und-netzwerk-exposition).
Unterstützt wird nur der `main`-Branch. Sicherheitslücken bitte **nicht** als
öffentliches Issue melden, sondern über die privaten Sicherheitshinweise von
GitHub (Repository → Tab **Security** → **Report a vulnerability**).
