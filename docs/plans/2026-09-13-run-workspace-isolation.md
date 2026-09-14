# Run-Workspaces — Umsetzung zu TODO 77fc07e3

Arbeitsbranch: `fix/77fc07e3-run-workspace-isolation-integrated`.
Implementierung und Builds erfolgen im separaten Checkout `/tmp/mysuricates-run-isolation-integrated`.

## Verbindliche Quellwahl

Geplante Build- und Health-Checks prüfen den letzten lokalen Commit beim Run-Start.
Die Ausführung hält dessen SHA vor dem Warten auf den Parallelitätsslot fest.
Workflows, die ausdrücklich uncommittete Änderungen prüfen oder Tests dafür schreiben,
verwenden eine eingefrorene Kopie des Arbeitsstands. Ändert sich die Quelle während
Kopie und Kontrollmessung, schlägt die Vorbereitung fehl.

## Ausführung

Workflow-Metadaten aktivieren die Isolation explizit:

```json
{"workspace":{"mode":"isolated","source":"commit","install_npm":true}}
```

`source: working-tree` übernimmt zusätzlich den aktuellen Diff und geeignete neue Dateien.
`branch: true` erstellt `fix/workflow-<run-id>` im eigenen Klon.
`source_path` erlaubt eine andere physische Git-Quelle als den Projektpfad.
`allow_working_tree_target: true` erlaubt bei `git_target: WORKTREE` den Arbeitsstand.
Templates ohne Metadata-Feld können dieselbe Policy unter `config.workspace` des ersten
Blocks transportieren. Workflow-Metadaten haben Vorrang.

Der private Speicher liegt standardmäßig unter `~/.mysuricates/workspaces/<project>/<run>`;
`automation.run-workspace.root` überschreibt die Wurzel. `repo` ist ein unabhängiger lokaler
Git-Klon ohne gemeinsam benutzte Arbeitsdateien oder Git-Index. `workspace.json` hält SHA,
Quellpfad, Snapshot-Hash und Ausführungspfad fest. Ein vorhandener Run wird niemals
zurückgesetzt. Fortsetzen lädt seinen vorhandenen Workspace; ein Retry ist ein neuer Run.
Der lokale Clone hat eine gesperrte Push-URL, damit ein Agent nicht versehentlich in den
Original-Checkout pusht. Eine spätere Übernahme bleibt eine explizite Handlung.

`project_path` bleibt die Projektidentität. `execution_path` ist der tatsächliche CWD für
Shell, AI und Kontextdateien; `workspace_logs` und `workspace_source_sha` stehen für Reports
bereit. Fest kodierte Quellpfade müssen deshalb auch in Workflow-Definitionen ersetzt werden.
Dies ist eine Trennung von Arbeitsverzeichnissen, keine Sicherheits-Sandbox gegen beliebige
Shell-Befehle mit absoluten Pfaden.

## Technische Checks

Shell-Config `checked_command: true` setzt `set -e -o pipefail`. Der tatsächliche Exitcode
bestimmt den technischen Gate-Marker; `check_category` benennt den Check. Getrennte Schritte
für Backend-Tests, Frontend-Build und Lint behalten getrennte Ergebnisse, auch bei
`on_failure: continue`. Reports können einen technischen roten Gate-Marker nicht überstimmen
(die vorhandene Run-Auswertung verwendet die höchste Schwere aller Schritte).
`logs/step-<index>.log` bewahrt die vom Shell-Service erfasste Ausgabe; absichtliches `tail`
im eigentlichen Befehl muss bei der Migration entfernt werden, sonst fehlen frühere Zeilen.

## Migrationsstand und nächste Abnahme

Die Vorschlagsgeneratoren erfassen 52 installierte Workflows: 31 eigene und 21 externe.
45 Vorschläge sind für die Validierung vorbereitet. Sieben bleiben unverändert, solange die
lokalen Git-Quellen für CS2BIM und Treasure Hunt Backend fehlen. Die Migration ersetzt
Definitionen per REST unter derselben ID; Schedules und Trigger bleiben zugeordnet.
`apply-migration.py` prüft die getestete JAR-Version und unveränderte Ausgangsdefinitionen,
sichert die Originale und prüft die PUT-Antwort. Der gesamte Ablauf wurde auf Test validiert.
Am 14.09. wurden alle 45 vorbereiteten Definitionen lokal per REST migriert und anschließend
gegen die gespeicherten Policies geprüft. Die sieben blockierten Definitionen sind unverändert.

Sechs versionierte Pool-Dateien und neun Built-in-Templates sind angepasst. Zwei weitere
lokal ignorierte Pool-Dateien liegen als prüfbare Varianten unter
`scripts/workspace-isolation/pool-overrides`. Die Templates behalten Fixes zur manuellen
Übernahme; sie pushen oder mergen nicht automatisch. `on_failure: stop` ersetzt das bislang
wirkungslos verwendete `abort` in den betroffenen Definitionen.

Mehrere Repositories werden über `additional_sources: {alias: "/pfad/zum/repo"}` pro Run
gepinnt und separat geklont. Der jeweilige Pfad steht als `execution_path_<alias>` bereit.
Absolute Eingabepfade innerhalb der Quelle werden auf die Kopie umgeschrieben; Projektidentität
und die Kennzeichnung nicht vertrauenswürdiger Eingaben bleiben erhalten.

`prune-workspaces.py` arbeitet standardmäßig lesend und entfernt nur ausdrücklich ausgewählte,
alte, unveränderte, erfolgreich abgeschlossene eigene Repository-Kopien. Logs, Manifeste,
Fix-Branches und Fehlerfälle bleiben erhalten; zusätzliche Repository-Slots werden derzeit
konservativ behalten. `prepare-backend-runtime.sh` baut ein unveränderliches JAR aus einer
isolierten Kopie. Der inzwischen auf main vorhandene Runtime-/IDE-Schutz wird mitgeführt.

Die geprüfte JAR läuft auf Dev (:9090), Build-Zeitstempel `2026-09-13T20:49:53Z`.
Offen sind die sieben fehlenden Quellpfade sowie der gemeinsame Commit/Merge des Arbeitsbranches.
Die Pool-Dateien einschließlich der beiden lokalen Overrides sind im Arbeitsbranch vorbereitet;
ihre Übernahme in den Haupt-Checkout steht mit dem Merge noch aus. Vor einem Neustart müssen laufende Runs geprüft werden; bei aktiven
Runs verlangt AGENTS.md die Freigabe des Nutzers.

## Verifikation

Python-Verhaltenstests: `python3 -m unittest discover -s ai-automation-backend/src/test/python -v`.
Sie verwenden ausschließlich temporäre Git-Repositories und prüfen parallele Runs, dirty
Snapshots, Commit-Pinning, Resume, fehlende Workspaces und Pfadgrenzen.
Java-Regressionstests decken Context/Executor, Prozessregistrierung, Shell-Exitcodes und
Workspace-Persistenz ab. Der Gesamtplan ist erst nach der operativen Migration abgeschlossen.

Stand nach Integration: Basis `293ec589`; vorherige Arbeitsstände bleiben als Sicherung erhalten.

Auf einer eigenen Testinstanz wurden parallele Commit-Snapshots, Dirty-Snapshots,
Exitcodes 7/127, Log-Erhalt, reale Docker-Mounts und Container-Abbruch verifiziert.
Drei Repository-Quellen liefern getrennte Workspaces und korrekte gepinnte SHAs;
die Originaldateien bleiben unverändert. Eine Self-Care-Simulation führt alle drei
Checks trotz Fehlern aus und bleibt bei optimistischem Abschlussbericht RED mit
`COMPLETED_WITH_ERRORS`. Die REST-Migration wurde einschließlich Backup und ID-Erhalt geprüft.
Alle genannten API-Prüfungen wurden auf dem integrierten JAR erneut erfolgreich ausgeführt.
JAR-SHA256: `3c6767667fd5` (Präfix). Self-Care-Nachweis: `f90e9258-5307-4214-9a43-6121aecfd7c2`;
Container-Abbruch: `6cc9eab0-f53d-4d20-8e21-f75633573002`; drei Quellen: `169114f5-3899-4dea-bdf3-088d0a1458f6`.

Gesamtsuite auf Basis 293ec589: 7.063 Tests, 0 Fehler, 1 übersprungen.
Der aktualisierte Export erfasst fünf weitere Security-Audits mit gemeinsamem QA-Checkout;
164 Shell-Befehle der 45 vorbereiteten Definitionen sind mit Platzhalterwerten syntaktisch geprüft.

Die ursprünglichen Definitionen, Vorschläge und das Migrationsprotokoll liegen dauerhaft unter
`~/.local/state/autotrigger-dev/workspace-migrations/77fc07e3-293ec589/`.
Die vorherige Runtime-JAR bleibt erhalten. Änderungen wurden nicht committed oder gepusht.
