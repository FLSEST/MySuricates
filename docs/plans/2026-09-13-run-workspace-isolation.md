# Run-Workspaces — Umsetzung zu TODO 77fc07e3

## Stand am 14.09.2026

Die Implementierung ist über Branch `fix/77fc07e3-run-workspace-isolation-integrated`
in `main` integriert (Merge-Stand `b063748d`). Die zwischenzeitlichen Scheduler-,
Shell-Escaping- und Gate-Korrekturen aus main sind erhalten. 51 der 52 betroffenen
installierten Workflows sind per REST unter denselben IDs migriert. Die sechs
CS2BIM-Nachzügler gehören jetzt dazu. Acht Pool-Definitionen und neun Built-in-Templates
sind angepasst; zwei absichtlich lokal ignorierte Pool-Dateien wurden nach Sicherung
übernommen, ihre neutralen Varianten sind unter `scripts/workspace-isolation/pool-overrides`
versioniert. Es wurde nichts gepusht.

**Einzige offene Quellzuordnung:** Treasure Hunt Backend (`6f8ff410`). Der vorhandene
Quellordner und seine Sicherungen besitzen keine Git-Historie; im GitHub-Konto wurde
kein entsprechendes Backend-Repository gefunden. Die Zustimmung zur lokalen
Initialversionierung ist angefragt. Bis dahin bleibt diese Definition unverändert;
eine fremde oder künstlich erfundene Commit-Historie wird nicht als Projektstand ausgegeben.

## Verbindliche Quellwahl

Geplante Build- und Health-Checks prüfen den letzten lokalen Commit beim Run-Start.
Dessen SHA wird vor dem Warten auf einen Ausführungsslot festgehalten. Workflows,
die ausdrücklich uncommittete Änderungen prüfen oder dafür Tests schreiben, verwenden
eine eingefrorene Kopie des Arbeitsstands. Ändert sich die Quelle während der Kopie,
schlägt die Vorbereitung fehl; es gibt keinen Rückfall auf das Originalverzeichnis.

CS2BIM verwendet den dauerhaft angelegten Checkout `~/.mysuricates/sources/cs2bim`
(Herkunft `FLSEST/cs2bim`, Branch `dev`, initialer HEAD
`bd8ffe447d6e3e6aa6c1243ab0d27843996fdd9c`). Runs laden keine Remote-Änderungen automatisch
in diesen Quellordner. Der ausdrücklich dafür vorgesehene Upstream-Sync darf einen
Merge von upstream/develop auf seinem privaten Run-Branch vorbereiten; er verändert
und pusht keinen Ausgangsbranch.

## Ausführung und Konfiguration

```json
{"workspace":{"mode":"isolated","source":"commit","install_npm":true}}
```

- `source: working-tree` übernimmt zusätzlich Diff und geeignete neue Dateien.
- `branch: true` erstellt `fix/workflow-<run-id>` im unabhängigen Klon.
- `source_path` bezeichnet die physische Git-Quelle unabhängig von der Projektidentität.
- `additional_sources: {alias: "/pfad/zum/repo"}` pinnt weitere Repositories; deren
  Run-Pfade stehen unter `execution_path_<alias>` bereit.
- `allow_working_tree_target: true` erlaubt bei `git_target: WORKTREE` einen Arbeitsstand.
- Templates ohne Metadata-Feld transportieren die Policy in `config.workspace` des ersten
  Blocks. Workflow-Metadaten haben Vorrang.

Der Speicher liegt unter `~/.mysuricates/workspaces/<project>/<run>` und ist über
`automation.run-workspace.root` konfigurierbar. Separate Klone teilen weder Arbeitsdateien
noch Git-Index. `workspace.json` hält SHA, Quellpfad, Snapshot-Hash und Run-Pfade fest.
Resume verwendet dieselbe Kopie ohne Reset; Retry erzeugt einen neuen Run. Die Push-URL
des lokalen Klons ist gesperrt. Fixes bleiben zur ausdrücklichen Übernahme erhalten.

`project_path` bleibt die Projektidentität. `execution_path` ist der tatsächliche CWD
für Shell, AI, Skills und Kontextdateien. Absolute Eingaben innerhalb einer Quelle werden
auf deren Kopie abgebildet, einschließlich ihrer Kennzeichnung als nicht vertrauenswürdig.
`workspace_logs` und `workspace_source_sha` dienen als nachvollziehbare Report-Bezüge.
Dies ist Arbeitsverzeichnis-Isolation, keine Sicherheits-Sandbox gegen beliebige absolute
Shell-Pfade. Container erhalten die Run-Kopie unter `/workspace`; Abbruch entfernt den
zugehörigen Container und stoppt den Prozessbaum.

## Checks, Nachweise und Aufbewahrung

`checked_command: true` aktiviert `set -e -o pipefail`; der tatsächliche Exitcode bestimmt
das technische Gate. Backend, Frontend und Lint laufen als unabhängige Checks. Ein
fortgesetzter fehlgeschlagener Check führt zu `COMPLETED_WITH_ERRORS`; ein optimistischer
AI-Bericht kann das technische RED nicht überschreiben. Die Pool-Auswertung behält
YELLOW für unvollständige oder unbekannte Report-Ergebnisse. Output-Platzhalter werden
im Shell-Kontext ohne zusätzliche Quotes und mit aktiviertem Escaping verwendet.

`logs/step-<index>.log` hält die erfasste Ausgabe fest. CS2BIM-Verifikationen bewahren zusätzlich
getrennte Dependency-, Security-, Lint-, Typ- und Test-Logs. Gemeinsame temporäre Python-
Umgebungen und Installationen in die Systemumgebung entfallen.

`prune-workspaces.py` arbeitet standardmäßig lesend. Es entfernt auf ausdrücklichen Aufruf
nur alte, erfolgreiche, unveränderte eigene Commit-Kopien nach API-Abgleich. Auch ein sauberer
Arbeitsbaum mit neuem Fix-Commit bleibt erhalten. Logs, Manifeste, Fehlerfälle, Fix-Branches
und zusätzliche Repository-Slots werden konservativ aufbewahrt.

## Verifikation

- Vollständige Backend-Baseline: 7.063 Tests, keine Fehler, ein übersprungener Test.
- Nach Integration der neuen main-Änderungen: 219 gezielte Java-Tests ohne Fehler
  (Executor, Scheduler, Escaping, Templates und Quellgrößen).
- 13 Python-Tests ohne Fehler; 11 Hygiene-Hook-Prüfungen bestanden.
- Erneute API-/Docker-Abnahme: parallele Commit-Kopien, Dirty-Snapshots, unveränderte
  Originale, Exitcodes 7/127, Log-Erhalt, Container-Mount und vollständiger Abbruch,
  drei Repository-Quellen sowie die reale CS2BIM-Quelle.
- Self-Care-Fehlersimulation: alle drei Checks laufen (7/3/0); ein optimistischer
  Report lässt den Run RED und `COMPLETED_WITH_ERRORS`.

Aktuelle Testnachweise: Self Care `90743f88-e387-4342-a426-c775bb315a44`, Container-Abbruch
`a4649f87-9a9e-4b6e-8d9e-f790049fbfaf`, drei Quellen `c1e78069-6932-4de7-86e2-6cadb7aadc9f`,
CS2BIM `b9c0d96a-0999-4b3b-8a29-60f59e211e5a`. Getestete integrierte JAR: SHA256-Präfix
`2c610bfa8098`.

## Abschlussplan

- [x] Kernimplementierung committen und aktuelle main-Korrekturen integrieren.
- [x] Sechs CS2BIM-Quellen zuordnen, Definitionen migrieren und echte Quelle testen.
- [x] Pool-Dateien einschließlich lokaler Varianten übernehmen und nach main mergen.
- [x] Integrierten Stand gezielt und über die Testinstanz verifizieren.
- [x] Dev auf die geprüfte JAR umgestellt (0 aktive Runs unmittelbar zuvor); Build-Zeitstempel `2026-09-14T09:19:27Z`. REST-Endkontrolle bestätigt 51 isolierte Policies und das unveränderte Treasure-Hunt-Gate.
- [ ] Nach Zustimmung Treasure Hunt Backend initial versionieren oder vorhandenen Git-Checkout zuordnen, Gate isolieren und testen.

Die Originaldefinitionen, Vorschläge und Protokolle liegen dauerhaft unter
`~/.local/state/autotrigger-dev/workspace-migrations/77fc07e3-293ec589/` und
`~/.local/state/autotrigger-dev/workspace-migrations/77fc07e3-final/`.
Vor jedem Backend-Neustart werden aktive Runs geprüft; bei laufenden Runs ist nach
AGENTS.md eine Freigabe erforderlich. Die vorherige Runtime wird zur Rückkehr aufbewahrt.
