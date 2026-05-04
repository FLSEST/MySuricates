# Dateisystem-Verfügbarkeit Stable/Test — Analyse 2026-05-05

TODO: `0a6c65da-52f6-4048-8c72-77e10cc3ea6e`

## Stand der Mounts

`docker-compose.stable.yml`:

```yaml
volumes:
  - stable-data:/app/data                  # eigene DB
  - ./stable-frontend:/app/frontend:ro
  - /var/run/docker.sock:/var/run/docker.sock
  - ${HOME}/.claude.json:${HOME}/.claude.json:ro
  - ${HOME}/.local/share/claude:${HOME}/.local/share/claude:ro
  - ${HOME}/.local/bin:${HOME}/.local/bin:ro
  - ${HOME}/.claude:${HOME}/.claude
  - ${HOME}/Entwicklung:${HOME}/Entwicklung
  - ${HOME}/dokumente:${HOME}/dokumente
  - ${HOME}/.autotrigger/backups/stable:/host-backups/stable
```

⚠️ Die TODO-Beschreibung („`${HOME}/Dokumente` ist nicht gemountet") ist nur teilweise korrekt: `${HOME}/dokumente` (lowercase) ist gemountet. Workflows mit Groß-D können den Pfad nicht erreichen.

## Tatsächliche Workflow-Pfad-Referenzen (heute, REST-Scan)

| Pfad | # Workflows | Mount auf Stable? |
|---|---|---|
| `/home/flo/Entwicklung` | (alle Code-Workflows) | ✅ |
| `/home/flo/.claude` | (alle CLI-Workflows) | ✅ |
| `/home/flo/dokumente` | 2 (Job Search, CV) | ✅ |
| `/home/flo/Dokumente` | 1 (Präsentationen) | ❌ verfehlt durch case |
| `/home/flo/Innofuse` | 10 (Audits, QA) | ❌ |
| `/home/flo/IdeaProjects` | 1 (Dep-Audit) | ❌ |
| `/home/flo/backup` | 1 (DB-Mirror) | ❌ |

## Befunde

### F1 — Case-Sensitivity-Falle: `dokumente` vs `Dokumente`

Der Volume-Mount ist `${HOME}/dokumente` (klein), der Host hat ein Verzeichnis `Dokumente` (groß). Linux-Filesystem ist case-sensitive — workflow „MySuricates Präsentation" greift ins Leere.

**Quick Fix:** Symlink auf Host (`ln -s Dokumente dokumente`), oder Workflow auf Kleinschreibung umstellen, oder beide Mounts setzen.

### F2 — `/home/flo/Innofuse` ist die größte Lücke

10 Workflows (Security Audits, Frontend QA, Dependency Audit) referenzieren das. Auf Stable laufen sie nie erfolgreich. Wenn Stable als „verlässliche Audit-Instanz" gedacht ist (siehe TODO `82c64a35` Staging-Konzept), ist das das größte praktische Problem.

### F3 — Sicherheitsabwägung

Jeder zusätzliche Mount erhöht die Angriffsfläche. Der Stable-Container hat bereits `docker.sock` gemountet (= praktisch root auf dem Host). Innofuse-Mount macht den Angriffs-Blast-Radius nicht spürbar größer.

## Optionen

### Opt-A — Mehr Pfade mounten (pragmatisch)

`docker-compose.stable.yml` und `docker-compose.test.yml` ergänzen:

```yaml
- ${HOME}/Innofuse:${HOME}/Innofuse
- ${HOME}/IdeaProjects:${HOME}/IdeaProjects
- ${HOME}/Dokumente:${HOME}/Dokumente   # neben "dokumente"
```

Vorteil: bestehende Workflows funktionieren überall.
Nachteil: Stable bekommt Lese-/Schreibzugriff auf alle Audits-Inputs. Akzeptabel im Single-User-Setup, problematisch bei Multi-Tenancy.

### Opt-B — Workflows portabel machen (sauber, größerer Aufwand)

Statt absolute Pfade `/home/flo/...` Konvention `{{ project_root }}` o.ä. Variablen einführen, beim Run-Start vom Backend gefüllt.

Vorteil: Workflows portabel zwischen Dev / Stable / Test / fremden Hosts.
Nachteil: Bestehende Workflows müssen migriert werden.

### Opt-C — Stable nur für Plattform-Tasks (Konzept-Schärfung)

Stable als Demo-/Showcase-Instanz definieren, die KEINE host-bezogenen Workflows ausführt. Innofuse-Audits laufen nur Dev. Test-Instanz wird zur sandboxed Spielwiese.

Vorteil: klares Modell, keine Mounts nötig.
Nachteil: schließt Use-Cases aus, die Stable als „Secondary Worker" gedacht hatten.

Hängt eng mit `82c64a35` (Staging-Konzept klären) zusammen — diese Entscheidung sollte dort getroffen werden.

## Empfehlung

**Kurzfristig (heute machbar, ohne Code):** Opt-A umsetzen (3 Volume-Zeilen ergänzen) → die 12 betroffenen Workflows funktionieren wieder. Ist eine 5-Minuten-Änderung an `docker-compose.stable.yml` + Container-Restart durch User.

**Mittelfristig:** TODO `82c64a35` (Staging-Konzept) prioritisieren — Opt-B vs Opt-C entscheiden, dann Workflows ggf. migrieren.

## Konkreter nächster Schritt

User-Approval für Opt-A einholen. Wenn ja: Volume-Mounts ergänzen, Container restart durch User → fertig.
