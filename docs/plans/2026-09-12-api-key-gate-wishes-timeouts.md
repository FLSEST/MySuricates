# Plan: API-Key-Log, Gate-Upsert, Wish-Artefakte und Timeout-Analyse

Stand: 2026-09-12. Projekt: AutoPilot.
Gemeinsamer Ausgangscommit: `c9f39629f3a98d695d6d6abb24ee2b04dcfd928b`.

## Auftrag und Arbeitsweise

Die vier vom Benutzer ausgewählten TODOs werden nacheinander in eigenen Branches
und separaten Git-Worktrees bearbeitet. Die ersten drei Aufgaben ändern lokalen
Code; die vierte untersucht den laufenden Bestand ausschließlich lesend. Dieser
Plan gehört zu den vier vorhandenen TODOs und erzeugt keinen zusätzlichen Auftrag.

Jede Aufgabe wird vor der Umsetzung dem vorhandenen TODO zugeordnet, gezielt
verifiziert und mit Ergebnissen in dessen Beschreibung dokumentiert. Der
Arbeitsstand wird anschließend hier aktualisiert. Commits werden gemäß AGENTS.md
gemeinsam abgestimmt; die geprüften Änderungen bleiben bis dahin in den jeweiligen
Worktrees. Kein Push, Merge, Deploy oder Neustart einer vorhandenen Instanz.

| Reihenfolge | TODO | Branch | Worktree | Arbeitsstand |
|---|---|---|---|---|
| 1 | `b7b58b6b-9c23-402c-99a5-2cac401921a5` | `fix/b7b58b6b-api-key-log` | `/tmp/mysuricates-b7b58b6b` | geprüft, TODO DONE; Commit ausstehend |
| 2 | `13556fce-ee8f-4cde-9589-47f1fa454818` | `fix/13556fce-gate-todo-upsert` | `/tmp/mysuricates-13556fce` | geprüft, TODO DONE; Commit ausstehend |
| 3 | `7ecceaa6-f502-4155-a8d5-e6617cd06dc1` | `feat/7ecceaa6-wish-artifacts` | `/tmp/mysuricates-7ecceaa6` | geprüft, TODO DONE; Commit ausstehend |
| 4 | `4778ac4c-5e49-44ad-bb34-f519e332c11f` | `research/4778ac4c-timeouts` | `/tmp/mysuricates-4778ac4c` | geprüft, TODO DONE; Commit ausstehend |

## 1. API-Key im Startlog maskieren

`ApiKeyService.init()` protokolliert einen neu generierten Schlüssel vollständig.
Nur die letzten vier Zeichen sollen sichtbar sein. Speicherung und Zugriff auf
den Schlüssel müssen weiterhin funktionieren; die private Schlüsseldatei bleibt
der vorhandene lokale Zugangsweg.

Verifikation: Log-Ausgabe eines Testschlüssels erfassen und prüfen, dass dessen
vollständiger Wert beziehungsweise Präfix nicht erscheint, der maskierte Suffix
aber schon. Bestehende ApiKeyService-Tests und SourceSizeRatchet ausführen.
Fertig: kein Klartextschlüssel im Startlog, erfolgreicher Nachweis im TODO.

Ergebnis: Regression vor dem Fix erwartungsgemäß rot; nach Maskierung sind
`ApiKeyServiceTest` und `SourceSizeRatchetTest` grün (15 Tests, 0 Fehler).
Vollständige Speicherung in Repository und isolierter Schlüsseldatei mitgeprüft.

## 2. Gate-TODOs und granulare Finding-TODOs beim Upsert trennen

Der Lookup nach `category:` kann ein zuvor von `GranularTodoService` erzeugtes
`item:`-TODO treffen und dessen Inhalt und Tags überschreiben. Die Auswahl muss
Gate-TODOs zuverlässig von Item-TODOs unterscheiden, auch wenn beide bereits
existieren. Den Approval-Pfad mit derselben Lookup-Logik mitprüfen.

Verifikation: Regression mit Gate YELLOW/RED und granularen Findings derselben
Kategorie, wiederholtem Lauf, vorhandenem Gate-TODO und Approval-Pfad. Service-
und Repository-Tests müssen belegen, dass Item-Inhalt/Tags erhalten bleiben und
genau das passende Gate-TODO angelegt oder aktualisiert wird. SourceSizeRatchet
mitprüfen. Keine bestehenden Nutzdaten reparieren oder bereinigen.

## 3. Ergebnisse direkt in Wish-Details erschließen

Die aktuelle Oberfläche liegt inzwischen in `components/wishes/WishDetail.tsx`.
Vorhandene Run-IDs und Iterationsdaten nutzen, fehlende Informationen nur bei
Bedarf ergänzen. Aktueller Run und historische Iterationen sollen korrekt auf
Workflow- beziehungsweise Chain-Ausführungen verlinken. Step-Ergebnisse werden
aufklappbar sichtbar; `PathLinkedOutput` erschließt vorhandene Dateipfade.

Verifikation: Frontend-Tests für Workflow-Run, Chain-Run, Iterationslinks,
aufgeklappte Ergebnisse und fehlende/gelöschte Runs; deutsche und englische Texte;
Vitest und Frontend-Build. Bei erforderlichen Backend-Ergänzungen zusätzlich die
betroffenen Mapper-/Service-/Controller-Tests. Eine Browserprüfung erfolgt mit
isolierten Testdaten, ohne private Wishes zu verändern.

## 4. Timeout-Befund lesend überprüfen

Den verlinkten Audit-Run und dessen Bezugszeitraum lesen. Prüfen, was die Zahlen
7 und 25 tatsächlich zählen: eindeutige Timeouts, Log-Zeilen oder bloße
Texttreffer. Fehler nach Workflow/Block, Zeit, Provider und Ausführungsart
zuordnen. Container-Starts und den bekannten DB-Verbindungsbefund bei
`extractKnowledge` zeitlich korrelieren; Korrelation von belegter Ursache trennen.

Ergebnis: Bericht in diesem Branch und Zusammenfassung im TODO mit Abfragen,
Zeitraum, normalisierten Zählungen, konkreten Ursachen und Erkenntnisgrenzen.
Keine Konfigurationsänderung, kein Workflow-Start, keine DB-Schreiboperation und
keine Behebung weiterer Audit-Schulden. Der TODO-Abschluss belegt die Analyse,
nicht automatisch die Beseitigung aller untersuchten Timeout-Ursachen.

## Abschluss

Pro TODO: Branch/Worktree, geänderte Dateien beziehungsweise Analysebericht,
Prüfergebnisse und verbleibende Grenzen dokumentieren. Status erst nach dem
jeweiligen Fertig-Kriterium auf DONE setzen. Die vier Arbeitsstände bleiben
getrennt und für die gemeinsame Commit-Entscheidung überprüfbar.
