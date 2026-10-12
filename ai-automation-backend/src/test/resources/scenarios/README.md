# Szenario-Tests: Katalog und Format

Ein Szenario ist eine Workflow-Definition, die durch den echten `WorkflowExecutor` läuft. Jeder
KI-Aufruf geht dabei an einen `ScriptedAiProvider` statt an eine CLI (TODO fdf79a86). Auf GitHub
gibt es weder `claude` noch `codex` noch `pi`. Fast jeder echte Fehler dieser Plattform lag aber
in der deterministischen Verrohrung rund ums Modell. Genau die prüfen die Szenarien: vor dem Aufruf
Prompt, Platzhalter, Timeout, Modell, Effort und Provider; nach dem Aufruf Marker, Gate, Auto-TODO
und Laufstatus.

Runner: `ScenarioIntegrationTest` (Paket `com.automation.integration.scenario`). Daneben liegen der
Aufbau (`ScenarioFixtures`: Projekt, Workflows, Kette), die Prüfungen der `expect`-Schlüssel
(`ScenarioChecks`) und die Antworten auf Freigaben (`ScenarioApprovals`). Eine neue Datei in diesem
Ordner ist ein neuer Testfall, Code braucht es dafür nicht.

## Wie die Fälle entstehen

Die Fälle werden nicht erfunden. Ein Fall lohnt sich dort, wo zwei Quellen zusammenkommen:

- **Die Spec** sagt, was das System tun soll: `docs/WORKFLOW-GUIDE.md`, `docs/architektur.md`,
  `CLAUDE.md` (Execution Config) und die Javadoc der betroffenen Klasse.
- **Die Historie** sagt, wo das System tatsächlich gebrochen ist.

Stand der Erhebung am 2026-09-23:

- 243 erledigte TODOs mit Tag `bug`, davon 67 mit `workflow-execution`
- 148 `category:execution-failure` und 16 `category:backend-test-failure`
- 67 FAILED-Läufe. Davon sind 11 Ghost-Runs (Watchdog-Reaps nach einem Neustart, „no active
  execution thread“ bzw. 717–719 s Laufzeit) und zählen nicht. Wer sie mitzählt, misst Neustarts
  statt Fehlern.

Jede Datei nennt beide Quellen in `derivedFrom`. `thereAreScenarios_andEveryOneNamesItsOrigin`
lehnt eine Datei ohne sie ab.

## Katalog

Die ersten zehn Zeilen sind die Startmenge aus fdf79a86, die übrigen stammen aus der Historie.

| # | Fall | Abgedeckt durch |
|---|---|---|
| 1 | Gate-Marker mit Text davor (`## Verdict: gate_status: YELLOW`) wird nicht still GREEN | `gate-marker-behind-text` |
| 2 | Prüf-Workflow ohne Marker, Lauf COMPLETED, darf nicht GREEN werden | `gate-ordered-but-missing`, Gegenproben `gate-not-ordered-stays-empty`, `failed-step-keeps-its-own-verdict` |
| 3 | `{{ previous_chain_output }}` im Einzellauf | `chain-only-placeholder-in-single-run` — **als Analyzer-Befund, nicht zur Laufzeit.** Der `TemplateResolver` lässt unbekannte Platzhalter bewusst stehen. Die Sicherung ist deshalb die Analyzer-Regel `chain-only-placeholder`. |
| 4 | Blockausgabe ohne Leser (Analyzer R1) | `unwired-output` |
| 5 | `timeout_seconds` je Block kommt am Host-Aufruf an | `block-timeout-reaches-host-call` (dazu Modell und Effort) |
| 6 | Kette: Stufe 2 sieht die Ausgabe von Stufe 1, Stufe 1 einen leeren Platzhalter (0775c5e6) | `chain-passes-previous-output` |
| 7 | YELLOW legt ein Auto-TODO mit den richtigen Tags an | `yellow-gate-creates-auto-todo` |
| 8 | Audit-Envelope: stabile Identität, RESOLVED beim Verschwinden, Regression bei Wiederkehr; ein gekürzter Snapshot löst nichts auf (e39379a9) | `audit-finding-lifecycle` (vier Läufe) |
| 9 | Resume nach `foreach`/`while` überspringt keine Blöcke | `WorkflowExecutorDeterministicIntegrationTest` (`resumeAfterWrapperBlock…`, `secondResume…` aus cd0ea0f9) |
| 10 | Block-`provider` abweichend, ohne `model`: das Workflow-Modell wird nicht geerbt | `block-provider-does-not-inherit-model` |
| 11 | Der Workflow-Provider gilt für jeden KI-Block (24680e80) | `workflow-provider-reaches-every-block` |
| 12 | foreach-Präfix `[item]:` verschluckt keine Marker (8671d03b) | `foreach-marker-behind-item-prefix` |
| 13 | Leere `items_command`-Ausgabe ergibt null Durchläufe (838cad00) | `foreach-empty-items-command` |
| 14 | Scheitert ein foreach-Item, nennt die Lauf-Fehlermeldung es zuerst, nicht die erfolgreichen davor (4a8cabc4) | `foreach-failed-item-named-first` |
| 15 | Freigabe: die Frage kommt aufgelöst an (e2efb393), „Nein“ lehnt ab, nicht nur „No“ (50c7b5d5) | `approval-question-resolved-and-german-rejection` |
| 16 | Kopierte Marker zählen nur am Originalschritt: `compress` (348b7b3b) und `sub-workflow` (40471709), beides 6e5d6e2c | `compress-copy-counts-markers-once`, `sub-workflow-copy-counts-markers-once` |
| 17 | `switch`: eine KI-Ausgabe mit Zeilenumbruch am Ende trifft ihren Case, statt still in `default` zu fallen (bf11e6fe) | `switch-ai-output-trailing-newline` |
| 18 | `skill-capability`: Endet die KI-CLI mit Fehler, scheitert der Schritt; kein Folgeblock und kein Gate nimmt die Fehlermeldung als Antwort (ea0c0b3f) | `skill-capability-cli-error-fails-step` |
| 19 | Ziel-Wächter: ein Lauf der mitgelieferten Vorlage `goal-guard` mit `goal_id` bekommt `goal_status`/`goal_guard_*` und meldet ohne KI Rot mit dem überfälligen Meilenstein und TODO an die Dashboard-Kachel (259f4f66) | `goal-guard-reports-overdue` |
| 20 | Audit-Workflow: der zweite Lauf sieht in `{{ audit_findings }}` die Befunde des ersten, der erste „(keine bekannten Befunde)“; sein Sub-Workflow ohne `metadata.audit` bekommt die Variable auch über die Variablen-Übergabe nicht (3643beac) | `audit-findings-reach-the-next-run` (zwei Läufe) |
| 21 | `todo_details` filtert (Ausschluss-Tag, Status-Liste) und liefert Beschreibungen; `todo-update` mit `updates` schreibt nur, wo `require_status` passt; `run_if` überspringt einen KI-Block ohne Aufruf (cc0eb3ba) | `todo-details-drive-bulk-update` |
| 22 | foreach hört nach drei fehlgeschlagenen Items in Folge auf (`max_consecutive_failures`, Standard 3), auch mit `continue_on_error`; die Lauf-Fehlermeldung nennt den Grund zuerst (a6659a46) | `foreach-stops-after-consecutive-failures` |
| 23 | foreach mit `workflow_id` startet je Eintrag einen Kind-Lauf: die ID kommt aus einer Kontextvariable, Item-Felder (`{{ current_feature.id }}`), Index und `context`-Zuordnung kommen im Kind an, dessen Marker zählen nur dort (a5c9fbbf) | `foreach-sub-workflow-per-item` |
| 24 | Lauf-Token: ein Workflow mit `metadata.permissions: ["read"]` gibt seinen Shell-Schritten `MYSURICATES_RUN_TOKEN`, `MYSURICATES_API` (endet auf `/api/v1`) und `no_proxy` für die lokalen Hosts mit; der Token-Wert steht in der gespeicherten Ausgabe nur als `***`; ein Sub-Workflow ohne Anforderung bekommt keinen (cfb5d56b) | `run-token-reaches-shell-and-is-masked` |
| 25 | Besetzung (dca0c03e): `actor: ai` aus einer Laufvariable beantwortet eine Freigabe aus den Optionen, der Prompt trägt `actor_prompt`, die aufgelöste Frage und die Optionen, Modell und Effort kommen am Aufruf an; `shadow` zeigt den KI-Vorschlag in der Frage, die Person entscheidet, `shadowAnswer`/`agreed` stehen am Schritt | `approval-actor-ai-answers-from-options`, `approval-actor-shadow-records-both-answers` |
| 26 | Besetzung (dca0c03e): `actor: ai` füllt das Formular eines `human-input`, die Felder kommen als Variablen im nächsten Block an; ein Wert außerhalb der Optionen lässt den Schritt scheitern | `human-input-actor-ai-fills-the-form` (zwei Läufe) |

### Aus der Historie, noch ohne Szenario

- **Marker, die ein Shell-Block kopiert** (c0cf21db): Ein Shell-Block ist kein abgeleiteter Schritt,
  seine Marker zählen mit Absicht. Der Fall lag im Workflow (Faktenblatt echote fremde Gate-Header)
  und wurde dort behoben (ea5bd405). Ein Szenario würde nur festhalten, dass die Plattform ihn nicht
  abfängt.
- **Abbrechen vor der Thread-Registrierung** (45d09d19). Das ist ein Timing-Fall und passt schlecht
  zu einem Datenformat.
- **Nicht hier:** Fehler in der Ausgabe eines Providers, etwa Hook-JSON als Blockausgabe oder
  „Prompt is too long“. Sie entstehen in der jeweiligen CLI-Anbindung, nicht im Pfad dahinter.

## Format

```json
{
  "id": "dateiname-ohne-endung",
  "title": "Was der Fall zeigt",
  "derivedFrom": { "spec": "…", "incidents": ["<todo-id> (Titel)", "…"] },
  "execute": true,
  "workflows": { "kind": { "provider": "SCRIPTED", "blocks": [ "…" ] } },
  "workflow": {
    "provider": "SCRIPTED", "model": "sonnet", "instructions": null,
    "metadata": { "autoTodo": { "enabled": true, "mode": "auto" } },
    "blocks": [ { "type": "simple", "name": "pruefen", "config": { "command": "…" } },
                { "type": "sub-workflow", "name": "kind_laufen", "config": { "workflow_id": "@kind" } } ]
  },
  "todos": [ { "title": "…", "description": "…", "status": "OPEN", "priority": "MEDIUM", "tags": ["…"] } ],
  "context": { "variable": "wert" },
  "ai": { "SCRIPTED": [ { "match": "Teil des Prompts", "respond": "Antwort", "exitCode": 0 } ] },
  "answers": [ { "answer": "Nein", "questionContains": ["…"], "questionNotContains": ["{{"],
                 "rejectOptions": ["Nein"] } ],
  "expect": {
    "runStatus": "COMPLETED",
    "runError": { "startsWith": "Step 0 (foreach) failed: …", "contains": ["…"], "notContains": ["…"] },
    "gate": { "status": "YELLOW", "category": "gate-missing" },
    "analyzerRules": ["unwired-output"],
    "aiCallCount": { "SCRIPTED": 1 },
    "aiCalls": { "SCRIPTED": [ { "model": "sonnet", "timeoutSeconds": 42, "effort": "low",
                                 "promptContains": ["…"], "promptNotContains": ["{{"] } ] },
    "steps": [ { "index": 0, "status": "COMPLETED", "outputContains": ["…"], "outputNotContains": ["…"],
                 "metadata": { "answeredBy": "ai" } } ],
    "todos": [ { "titleContains": "…", "priority": "MEDIUM", "tags": ["gate:yellow"], "status": "OPEN", "tagsAbsent": ["…"], "descriptionContains": "…" } ],
    "dashboard": { "status": "RED", "title": "…", "summaryContains": ["…"], "kpis": { "Label": "Wert" },
                   "itemsContain": ["…"] },
    "noTodos": true,
    "markers": { "gates": 1, "todos": 1 },
    "auditReport": "VALID",
    "auditFindingCount": 2,
    "auditFindings": [ { "subject": "…", "status": "RESOLVED", "occurrenceCount": 1, "regressionCount": 0 } ]
  }
}
```

Statt eines Laufs sind auch mehrere Läufe desselben Workflows oder eine Kette möglich:

```json
{
  "runs": [ { "ai": { "…": [] }, "context": { }, "answers": [ ], "expect": { } } ],
  "chain": { "stopOnFailure": true,
             "entries": [ { "workflow": "@stufe1", "context": { } }, { "workflow": "@stufe2" } ] },
  "expect": { "chainStatus": "COMPLETED", "entries": [ { "runStatus": "COMPLETED" }, { } ] }
}
```

- **Provider:** `SCRIPTED` und `SCRIPTED_B`. Den zweiten gibt es für Fälle, in denen ein Block einen
  anderen Provider nennt als der Workflow.
- **`ai`:** Regeln je Provider, in dieser Reihenfolge geprüft. Eine Regel ohne `match` beantwortet
  jeden Prompt. Beantwortet keine Regel den Prompt, endet der Aufruf mit Exit-Code 1. Ein Szenario
  besteht also nie durch eine stille Standardantwort.
- **`gate`:** `"none"` heißt: Der Lauf trägt kein `gate_status`.
- **`runError`:** prüft die Fehlermeldung des Laufs (`error.message`). Ein fehlender Fehler zählt als
  leere Meldung.
- **`workflows`:** weitere Workflows im selben Projekt, angelegt vor dem Hauptworkflow. Jeder
  Textwert `"@name"` in einer Block-Config oder einem Ketteneintrag wird durch die ID des Workflows
  `name` ersetzt, etwa `workflow_id` eines `sub-workflow`-Blocks.
- **`runs`:** Läufe desselben Workflows nacheinander, jeder mit eigenem `ai`, `context` und
  `answers` (sonst gelten die des Szenarios) und eigenem `expect`. Das `expect` des Szenarios wird
  nach dem letzten Lauf geprüft. Schreibt der Workflow einen Audit-Envelope (`metadata.audit`),
  wartet der Runner vor dem nächsten Lauf auf dessen Auswertung.
- **`chain`:** startet statt des Workflows eine Kette über den `ChainExecutor`. Die Einträge nennen
  Workflows aus `workflows`. `expect.chainStatus` prüft die Kette, `expect.entries[i]` den Lauf des
  i-ten Eintrags mit denselben Schlüsseln wie ein Einzellauf. `aiCalls` zählt über alle Läufe.
- **`answers`:** Der Runner wartet, bis der Lauf eine Freigabe verlangt, prüft die Frage so, wie sie
  angezeigt wird, und antwortet wie `POST /executions/{id}/approve`. Verlangt der Lauf keine
  Freigabe mehr, ist das ein Fehler.
- **`steps[].metadata`:** Schrittmetadaten als Schlüssel → erwarteter Wert, als Text verglichen
  (`answeredBy`, `shadowAnswer`, `agreed`; ein fehlender Schlüssel ist `null`).
- **`markers`:** wie viele `gate_status`- und `todo_item`-Marker der Lauf gezählt hat
  (`gate_summary.items`, `todo_items`). Eine Kopie darf nicht noch einmal zählen. Der Ersatzeintrag
  eines bestellten, aber ausgebliebenen Urteils (`synthetic`) ist kein Marker und zählt nicht mit.
- **`auditReport`**, **`auditFindingCount`**, **`auditFindings`:** was die Audit-Auswertung aus dem
  Lauf gemacht hat: den Status des Reports (`VALID`/`INVALID`), die Zahl der Findings im Projekt
  (eines je Identität) und einzelne Findings, gefunden über ihr `subject`.
- **`goal`:** legt vor dem Lauf ein Ziel im Projekt des Szenarios an – `title`, `targetInDays`,
  `workPackages` (Arbeitspakete: `title`, `startInDays`, `endInDays`; nur `endInDays` = Ein-Tages-Paket,
  keins von beiden = ohne Zeitraum), `milestones` (`title`, `dueInDays`, `workPackages` als Indizes) und
  `todos` (`title`, `status`, `priority`, `dueInDays`, `workPackage` als Index). Tage relativ zu jetzt,
  auch negativ. `"@goal"` in `context` wird zur ID.
- **`workflow.builtinTemplate`:** statt eigener Blöcke die Blöcke einer mitgelieferten Vorlage, in
  `locale` aufgelöst (Standard `de`) – das Szenario fährt, was ausgeliefert wird.
- **`dashboard`:** was der Lauf an seine Dashboard-Kachel gemeldet hat (`dashboard_widget` in den
  Laufmetadaten): `status`, `title`, Teile von `summary`, `kpis` als Label → Wert, und Teile, die in
  irgendeinem Punkt von `items` stehen. Jede der drei Prüfarten wurde beim Anlegen einmal mit einer
  falschen Erwartung gefahren (Status, KPI, Punkt) und wurde rot.
- **`execute: false`:** Es läuft nur der Analyzer über die Definition, kein Lauf.
- **`model: null`** in `aiCalls` prüft, dass kein Modell übergeben wurde.

Eine Prüfung, die nicht rot wird, wenn sie soll, ist Dekoration. Beim Anlegen des Runners wurde
deshalb jede Prüfart einmal mit einer absichtlich falschen Erwartung gefahren, und alle wurden rot. Für
`runError` gilt das ebenso: `startsWith` war vor dem Fix aus 4a8cabc4 rot, `contains` und
`notContains` wurden es mit einer falschen Erwartung. Die Erweiterung aus 0fc5073b ebenfalls: 17
Varianten der fünf neuen Fälle mit je einer falschen Erwartung, alle 17 rot, jede an der erwarteten
Stelle (Kette, Eintrag, Prompt der zweiten Stufe; Report-Status, Anzahl, Vorkommen, RESOLVED,
Regression; Frage, Ablehnungsoptionen, Laufstatus, eine nie gestellte zweite Freigabe; gezählte
Gate- und TODO-Marker bei `compress` und `sub-workflow`).
