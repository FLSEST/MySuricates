# Szenario-Tests: Katalog und Format

Ein Szenario ist eine Workflow-Definition, die durch den echten `WorkflowExecutor` läuft. Jeder
KI-Aufruf geht dabei an einen `ScriptedAiProvider` statt an eine CLI (TODO fdf79a86). Auf GitHub
gibt es weder `claude` noch `codex` noch `pi`. Fast jeder echte Fehler dieser Plattform lag aber
in der deterministischen Verrohrung rund ums Modell. Genau die prüfen die Szenarien: vor dem Aufruf
Prompt, Platzhalter, Timeout, Modell, Effort und Provider; nach dem Aufruf Marker, Gate, Auto-TODO
und Laufstatus.

Runner: `ScenarioIntegrationTest` (Paket `com.automation.integration.scenario`), die Prüfungen der
`expect`-Schlüssel stehen daneben in `ScenarioChecks`. Eine neue Datei in diesem Ordner ist ein neuer
Testfall, Code braucht es dafür nicht.

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
| 6 | Kette: Stufe 2 sieht die Ausgabe von Stufe 1 | offen — `ChainExecutorTest` prüft die Weitergabe als Unit-Test; der Pfad braucht einen Ketten-Runner im Szenario-Format |
| 7 | YELLOW legt ein Auto-TODO mit den richtigen Tags an | `yellow-gate-creates-auto-todo` |
| 8 | Audit-Envelope: stabile Identität, RESOLVED beim Verschwinden, Regression bei Wiederkehr | offen als Pfad — Unit-Tests `AuditIngestionServiceTest`, `AuditFindingMergerTest` |
| 9 | Resume nach `foreach`/`while` überspringt keine Blöcke | `WorkflowExecutorDeterministicIntegrationTest` (`resumeAfterWrapperBlock…`, `secondResume…` aus cd0ea0f9) |
| 10 | Block-`provider` abweichend, ohne `model`: das Workflow-Modell wird nicht geerbt | `block-provider-does-not-inherit-model` |
| 11 | Der Workflow-Provider gilt für jeden KI-Block (24680e80) | `workflow-provider-reaches-every-block` |
| 12 | foreach-Präfix `[item]:` verschluckt keine Marker (8671d03b) | `foreach-marker-behind-item-prefix` |
| 13 | Leere `items_command`-Ausgabe ergibt null Durchläufe (838cad00) | `foreach-empty-items-command` |
| 14 | Scheitert ein foreach-Item, nennt die Lauf-Fehlermeldung es zuerst, nicht die erfolgreichen davor (4a8cabc4) | `foreach-failed-item-named-first` |

### Aus der Historie, noch ohne Szenario

- **Approval:** Die Frage wird vor der Anzeige aufgelöst (e2efb393), und eine Ablehnung zählt nicht
  nur beim englischen „No“ (50c7b5d5). Dafür braucht der Runner einen Schritt, der auf eine Freigabe
  antwortet.
- **Marker aus kopierten Ausgaben nicht doppelt werten** (6e5d6e2c, c0cf21db).
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
  "workflow": {
    "provider": "SCRIPTED", "model": "sonnet", "instructions": null,
    "metadata": { "autoTodo": { "enabled": true, "mode": "auto" } },
    "blocks": [ { "type": "simple", "name": "pruefen", "config": { "command": "…" } } ]
  },
  "context": { "variable": "wert" },
  "ai": { "SCRIPTED": [ { "match": "Teil des Prompts", "respond": "Antwort", "exitCode": 0 } ] },
  "expect": {
    "runStatus": "COMPLETED",
    "runError": { "startsWith": "Step 0 (foreach) failed: …", "contains": ["…"], "notContains": ["…"] },
    "gate": { "status": "YELLOW", "category": "gate-missing" },
    "analyzerRules": ["unwired-output"],
    "aiCallCount": { "SCRIPTED": 1 },
    "aiCalls": { "SCRIPTED": [ { "model": "sonnet", "timeoutSeconds": 42, "effort": "low",
                                 "promptContains": ["…"], "promptNotContains": ["{{"] } ] },
    "steps": [ { "index": 0, "status": "COMPLETED", "outputContains": ["…"], "outputNotContains": ["…"] } ],
    "todos": [ { "titleContains": "…", "priority": "MEDIUM", "tags": ["gate:yellow"] } ],
    "noTodos": true
  }
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
- **`execute: false`:** Es läuft nur der Analyzer über die Definition, kein Lauf.
- **`model: null`** in `aiCalls` prüft, dass kein Modell übergeben wurde.

Eine Prüfung, die nicht rot wird, wenn sie soll, ist Dekoration. Beim Anlegen des Runners wurde
deshalb jede Prüfart einmal mit einer absichtlich falschen Erwartung gefahren, und alle wurden rot. Für
`runError` gilt das ebenso: `startsWith` war vor dem Fix aus 4a8cabc4 rot, `contains` und
`notContains` wurden es mit einer falschen Erwartung.
