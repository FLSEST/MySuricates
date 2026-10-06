# Workflow Building Guide

Praktischer Leitfaden zum Erstellen effektiver Workflows in MySuricates. Umfasst alle Block-Typen, bewährte Patterns, Timeout-Konfiguration und häufige Fallstricke.

**Zielgruppe**: KI-Agenten (Claude, Codex) und Entwickler, die Workflows erstellen oder debuggen.

> Kuerzel in Klammern wie `(182bd224)` oder `TODO 63ecc124` sind Herkunftsnachweise:
> Commits dieses Repositories bzw. Vorgaenge in der begleitenden MySuricates-Instanz.
> Sie belegen, wann und warum etwas entstanden ist — von aussen nachschlagen laesst
> sich davon nur der Commit.

---

## 1. Schnellstart

Ein Workflow ist eine Sequenz von **Blöcken**, die nacheinander ausgeführt werden. Jeder Block hat einen `type` und eine `config`-Map. Die Ausgabe jedes Blocks steht dem nächsten via `{{ previous_output }}` zur Verfügung.

### Minimales Beispiel: Zwei-Block-Workflow

```json
{
  "name": "Einfache Dateianalyse",
  "projectId": "<projekt-uuid>",
  "blocks": [
    {
      "type": "shell",
      "config": {
        "command": "find . -name '*.java' | head -20"
      }
    },
    {
      "type": "simple",
      "config": {
        "command": "Analysiere diese Dateien und beschreibe die Projektstruktur:\n\n{{ previous_output }}"
      }
    }
  ]
}
```

### API-Endpunkt

```
POST /api/v1/workflows
```

Alle Workflow-CRUD- und Ausführungsoperationen liegen unter `/api/v1/workflows`. Ein Run wird gestartet mit `POST /api/v1/workflows/{id}/execute`.

Jeder Aufruf braucht den Header `X-API-Key`. Den Schlüssel legt das Backend beim ersten Start an; bei
einer vom Installer eingerichteten Instanz zeigt ihn `scripts/instance.sh key --name <name>`, beim
Quellcode-Setup steht er in `~/.mysuricates/api-key`. Die `projectId`
ist die ID eines vorhandenen Projekts — `GET /api/v1/projects` listet die Projekte mit ihrem Feld `id`,
in der Oberfläche steht sie in der Adresse der Projektseite (`/projects/<id>`). Vollständige Referenz:
[api-referenz.md](api-referenz.md).

### Kontext-Variablen

Variablen beim Start übergeben:

```
POST /api/v1/workflows/{id}/execute
{
  "context": {
    "filepath": "/pfad/zur/datei.md",
    "language": "Deutsch"
  }
}
```

Zugriff in Block-Configs mit `{{ filepath }}` oder `{{ language }}`.

---

## 2. Block-Typ-Referenz

MySuricates unterstützt 20 Block-Typen. Jeder Block hat einen `type` und eine `config`-Map. **Alle Config-Schlüssel** mit Typ, Pflicht, Standard und erlaubten Werten stehen in der erzeugten [Block-Referenz](block-referenz.md). Hier steht je Typ, wozu er taugt, ein minimales Beispiel und was man über sein Verhalten wissen muss.

**Prüfung beim Speichern.** Unbekannte Block-Typen werden abgelehnt (HTTP 400, die Meldung nennt den Typ und die erlaubten Typen), geprüft gegen die Executor-Registry (`BlockTypeRegistry`), rekursiv auch in `switch`-`cases[].blocks` und `default`. Ein fehlender oder leerer `type` zählt als unbekannt. Das gilt für jeden Schreibweg (UI, REST-API, Import, Pool-Installation, Seeder, Factory) und auch für das Wiederherstellen einer alten Version: eine nicht mehr ausführbare Definition kommt nicht zurück.

**Editor und Analyzer.** Das Konfigurationsfeld des Block-Editors schlägt Schlüssel und Werte vor (Strg+Leertaste, ein zweites Mal zeigt die Erklärung) und markiert unbekannte Schlüssel, falsche Werte und fehlende Pflichtfelder gelb; Speichern bleibt möglich. Dieselbe Quelle, `blockConfigSchema.json`, nutzt der Analyzer, dessen Befunde die Workflow-Seite unter dem Kopf zeigt: `unknown-config-key`, `missing-required-config-key`, `invalid-config-value`, `legacy-config-key` (LOW, alter Schlüsselname wie `workflowId`), `dead-nested-blocks` (HIGH: eine Liste `config.blocks` führt kein Typ aus) und `unknown-block-field` (HIGH, wenn ein Config-Schlüssel neben statt in `config` steht – solche Felder werden mitgespeichert, nicht verworfen). Dazu der Zugriff auf die eigene Plattform: `self-api-call` (Shell-Code ruft die REST-API der Instanz oder liest ihre Schlüsseldatei; ausgenommen die schlüsselfreien Sonden) und `direct-db-access`. Ein `HIGH`-Befund dieser beiden sperrt unbeaufsichtigte Starts (`403 UNATTENDED_PLATFORM_ACCESS`, SECURITY-Eintrag, kein Run); von Hand startet der Workflow weiter ([architektur.md](architektur.md) → Workflow-Härtung). Stattdessen: Kontextvariablen wie `{{ open_todos }}`, die `todo-*`-Blöcke und für fremde Dienste der `integration`-Block. Von einer KI erzeugte Workflows laufen gleich nach der Erzeugung durch dieselbe Prüfung. Wer einem Executor einen neuen Config-Schlüssel beibringt, trägt ihn in `blockConfigSchema.json` ein (`ExecutorConfigKeyDriftTest`).

### Felder am Block

Neben `type` und `config` kennt ein Block diese Felder:

| Feld | Bedeutung |
|---|---|
| `name` | Name, unter dem spätere Blöcke die Ausgabe per `{{ block.<name>.output }}` lesen. Empfohlen: Buchstaben, Ziffern, `_` und `-`; abgelehnt wird nur der reservierte Name `previous_output` (`BlockNameRules`). Workflowweit eindeutig halten – bei Gleichheit gewinnt der zuletzt geschriebene Wert. |
| `skillIds` | Skills, die **zusätzlich** zu den Workflow-Skills für diesen Block gelten; der `WorkflowExecutor` stellt danach den Ausgangsstand wieder her |

Die Schlüssel, die jeder Block in `config` versteht, stehen unter [Gemeinsame Felder](block-referenz.md#gemeinsame-felder). Was man dazu wissen muss:

- **`on_failure`**: Ein FAILED-Block beendet den Run (FAILED). Nur `continue` (Groß-/Kleinschreibung egal) lässt den Workflow weiterlaufen; jeder andere Wert, auch ein Tippfehler wie `"fail"`, stoppt wie `stop`.
- **`run_if`** (nur oberste Ebene): Ein Exit-Code ≠ 0 macht den Block `SKIPPED` mit der Ausgabe `Übersprungen (run_if: exit N)`; sein Executor wird nicht gerufen, eine `variable_name`-Variable bleibt ungesetzt. Typisch: `test -s .git/state/ids.txt`.
- **`provider`/`model`/`effort`** (KI-fähige Blöcke): Vorrang Block > Workflow > System-Standard, siehe [Reasoning-Effort](#reasoning-effort-denkaufwand). Beim `integration`-Block meint `provider` dagegen den Integrations-Anbieter.

### `simple` — KI-Textverarbeitung

Ein Prompt an die KI-CLI auf dem Host, eine Antwort; keine Shell. Für Analyse, Zusammenfassung, Review.

```json
{ "type": "simple", "config": { "command": "Fasse zusammen:\n\n{{ previous_output }}" } }
```

### `shell` — Shell-Befehl-Ausführung

Führt einen Befehl auf dem Host aus, mit `container_image` in einem Container ([Ausführungsumgebung](#7-ausführungsumgebung)). `checked_command` leitet aus dem Exit-Code ein Gate ab ([Quality-Gate-Marker](#quality-gate-marker)).

```json
{ "type": "shell", "config": { "command": "set -euo pipefail\ngit log --oneline -20" } }
```

**Platzhalter in Shell-Befehlen** – die Regeln, die man kennen muss:

- Werte von außen (Webhook-Payload samt `git_*`) sowie `{{ previous_report }}` und `{{ open_todos }}` setzt die Plattform in Shell-Befehlen **immer** in Single-Quotes (in KI-Prompts nie). Ein Lauf, der solche Daten trägt, ist *getaintet*: Ab dann werden auch Block-Ausgaben (`{{ previous_output }}`, `{{ block.<name>.output }}`, `{{ step_N_output }}`, `foreach`-Items) gequotet, ebenso in Sub-Workflows und nach einem Resume. Gleiches gilt, sobald Plattform-Daten im Prompt eines KI-Blocks standen und dessen Ausgabe verbucht ist.
- Solche Platzhalter deshalb **unquoted** schreiben und den Block mit `"shell_escape_variables": true` versehen. In eigenen Quotes ist das Escaping wirkungslos: `PREV='{{ previous_output }}'` wird beim Wert `x; id` zu `PREV=''x; id''` und führt `id` aus. Ohne die Option bleiben Werte aus `defaultContext` und Systemvariablen roh, ebenso alle Block-Ausgaben in Läufen ohne Daten von außen (Zeitplan, manueller Start, TodoTrigger, Chain) – ein mehrzeiliger Wert zerlegt dann den Befehl. Sichere Form: `set -- {{ previous_output }}` und danach `ANTWORT="${1:-}"` (für die ausgelieferten Definitionen erzwingen das `TemplateHygieneGateTest` und `PlatformPromptQuotingGateTest`).
- Eine unbelegte Variable bleibt wörtlich stehen: `set -- {{ source_dir }}; SOURCE_DIR="$*"`, erkennbar an `[ "$SOURCE_DIR" = "{{ source_dir }}" ]`.
- In einem quoted Heredoc (`<<'PY'`) landet ein Platzhalter als Quelltext im Interpreter. Werte auf der Shell-Zeile übergeben (`PROJECT_ID={{ project_id }} python3 - <<'PY'`, im Skript `os.environ['PROJECT_ID']`); andere Umgebungsvariablen bekommen Shell-Blöcke nicht.
- `scripts/lib/shell_placeholder_audit.py <definitionen.json>` ordnet jeden Output-Platzhalter in Shell-Feldern nach seiner Rolle ein und meldet nur, was eine Änderung braucht (Exit 1 = Befund). Eingabe ist die Antwort von `GET /api/v1/workflows` oder eine Pool-Datei; das Skript ruft selbst nichts ab. Platzhalter im Rumpf eines Heredocs gelten dort als unkritisch und dürfen nicht escaped werden, sonst landen Anführungszeichen im Text.

Vertrauensmodell: [deployment.md → Drei Vertrauensklassen](deployment.md#drei-vertrauensklassen-für-kontextwerte).

### `agentic` — Zielgesteuerter KI-Agent

Autonome Schleife aus REASON und ACT je Iteration – der mächtigste und teuerste Typ, für mehrstufige Aufgaben mit Urteil. Details: [Agentic Block Deep Dive](#8-agentic-block-deep-dive).

```json
{ "type": "agentic", "config": { "goal": "Erstelle portfolio/index.html aus {{ filepath }}.",
  "success_criteria": "index.html enthält alle Sektionen.", "max_iterations": 8, "timeout_seconds": 600 } }
```

- `max_total_tokens` zählt je Iteration `inputTokens` + `outputTokens` von REASON und ACT, Cache-Tokens (`cacheReadInputTokens`, `cacheCreationInputTokens`) bewusst nicht – mitgezählt rissen übliche Budgets schon in der ersten Iteration. Codex und Pi melden keine echten Zahlen, dort zählt die Schätzung (Zeichen / 4, `estimated: true`). Geprüft wird nach jeder Iteration; liegt die Summe darüber, endet der Block mit `[TOKEN BUDGET EXCEEDED: …]`. Die letzte Iteration läuft also zu Ende – das Budget kann um bis zu eine Iteration überschritten werden.
- Zwei Timeouts: `timeout_seconds` je CLI-Aufruf, `iteration_timeout_seconds` je Iteration – siehe [Die Dual-Timeout-Falle](#die-dual-timeout-falle).

### `retry` — Wiederholung bei Fehler

Wiederholt `command` bis zu `max_retries`-mal mit `delay` Sekunden Pause; mit `exponential_backoff` verdoppelt sich die Pause je Versuch (`delay`, `delay * 2`, `delay * 4`, …). Für instabile Tests und Netzwerkaufrufe.

```json
{ "type": "retry", "config": { "command": "npm test", "max_retries": 3, "delay": 5, "execution_mode": "shell" } }
```

### `conditional` — If/Then/Else

Wertet `condition` aus und führt `then_command` oder `else_command` aus. `execution_mode` gilt für Bedingung und Zweige: `shell` → Exit 0 = wahr; `ai` (Standard) → die KI antwortet TRUE oder FALSE.

```json
{ "type": "conditional", "config": { "condition": "test -f package.json", "then_command": "npm test",
  "else_command": "echo 'Keine Tests'", "execution_mode": "shell" } }
```

### `switch` — Mehrweg-Verzweigung

Vergleicht den aufgelösten `expression` mit dem `value` jedes Case (Alternativen mit `|`) und führt die Blöcke des Treffers aus, sonst `default`. Im Beispiel gibt ein früherer Block `ampel` nur `GREEN`, `YELLOW` oder `RED` aus.

```json
{ "type": "switch", "config": { "expression": "{{ block.ampel.output }}",
  "cases": [ { "value": "GREEN", "blocks": [{ "type": "shell", "config": { "command": "echo 'Alles gut'" } }] },
             { "value": "YELLOW|RED", "blocks": [{ "type": "shell", "config": { "command": "echo 'Probleme'" } }] } ],
  "default": [{ "type": "shell", "config": { "command": "echo 'Unbekannt'" } }] } }
```

- Verglichen wird ohne Leerraum am Anfang und Ende (eine KI-Antwort `GREEN` mit Zeilenumbruch trifft `GREEN`); Groß-/Kleinschreibung zählt.
- Ohne Treffer und ohne `default` endet der Block still COMPLETED (Ausgabe `No matching case for: …`, Metadatum `matchedCase: "none"`) – wer einen unerwarteten Wert bemerken will, gibt `default` einen Block, der scheitert.
- Verschachtelte Blöcke dürfen nur `shell` oder `simple` sein, andere Typen werden abgelehnt.
- Die Blöcke eines Case schieben `{{ previous_output }}` weiter; wer den Stand vor dem switch braucht, benennt den Block davor. Ein `name` am Case-Block macht seine Ausgabe als `{{ block.<name>.output }}` verfügbar; die Ausgabe des switch ist die Verkettung aller ausgeführten Case-Blöcke.
- `gate_status` taugt nicht als Ausdruck: Die Marker wertet der Run erst am Ende aus.

### `foreach` — Ueber Items iterieren

Führt `command` für jedes Item aus `items` oder aus den Zeilen von `items_command` aus (das läuft immer als Shell) – oder mit `workflow_id` je Item einen Lauf eines anderen Workflows.

```json
{ "type": "foreach", "config": { "variable_name": "repo", "items_source": "command",
  "items_command": "find . -maxdepth 2 -name package.json | xargs -n1 dirname | sort -u",
  "execution_mode": "shell", "shell_escape_variables": true, "command": "cd {{ repo }} && npm outdated --json || true" } }
```

- Item-Variablen: `{{ <variable_name> }}`, `{{ <variable_name>_index }}` (ab 0), bei einem JSON-Objekt die Felder der obersten Ebene als `{{ <variable_name>.<feld> }}`. Leere Zeilen werden verworfen; eine leere Liste ergibt null Iterationen (COMPLETED, `totalItems: 0`).
- Fehler: Scheitert ein Item, scheitert der Block (die übrigen Items laufen trotzdem); mit `continue_on_error: true` endet er COMPLETED (`N/M items failed: …`). Nach `max_consecutive_failures` Fehlern **in Folge** (Standard 3, seit 2026-10) hört die Schleife auf und der Block scheitert, auch mit `continue_on_error` – der toleriert gescheiterte Items, nicht aber solche, die gar nicht liefen. Grund: Ein erschöpftes Kontingent lässt jedes weitere KI-Item genauso scheitern; ohne Abbruch verlor ein Lauf so 37 von 42 Items. Die Meldung nennt den Grund zuerst (`stopped after 3 consecutive failed items (max_consecutive_failures), 34 items not run`), darunter die gescheiterten Items. `0` schaltet den Abbruch ab, etwa für Erreichbarkeitsprüfungen über viele Hosts. `on_failure: "continue"` lässt dagegen nur den Workflow nach dem FAILED-Block weiterlaufen.
- Die Fehlermeldung beginnt mit den gescheiterten Items (`Step 1 (foreach) failed: 1/37 items failed: erkner`); die Block-Ausgabe bleibt das Protokoll aller Items in ihrer Reihenfolge.
- **Sub-Workflow je Item** (`workflow_id`): je Item ein eigener Lauf wie beim `sub-workflow`-Block (`SubWorkflowLauncher`, Zulassung als `SUB_WORKFLOW`). Der Kind-Lauf bekommt die Variablen dieses Laufs ohne Plattform-Daten, die Item-Variablen und die Werte aus `context`; der Taint reist mit. Ein Item scheitert, wenn sein Kind-Lauf nicht `COMPLETED` endet. Die Ausgabe `[item]: <letzte Ausgabe>` trägt `derivedOutput: true` – Marker zählen nur in den Kind-Läufen. `execution_mode`, `timeout_seconds` und die KI-Schlüssel wirken hier nicht.
- Ein Platzhalter in `workflow_id` wird **einmal vor dem ersten Item** aufgelöst und nur aus vertrauenswürdigen Kontextvariablen – nicht aus Webhook-Daten oder Blockausgaben –, sonst scheitert der Block vor dem ersten Item. `context` darf keine reservierten Namen setzen (Schlüssel mit `__`, Plattform-Daten, Werte des Laufs, die Item-Variablen).

### `while` — Schleife bis Bedingung

Wiederholt `command`, solange `condition_command` gilt, höchstens `max_iterations`-mal; ohne `condition_command` bis `max_iterations` oder zum ersten Fehler. `shell`: Exit 0 = weiter; `ai` (Standard): `false`, `0` oder eine leere Antwort = Stopp.

```json
{ "type": "while", "config": { "condition_command": "Antworte NUR mit 'true', wenn die Qualität unter 8 liegt.",
  "command": "Verbessere den Text. Gib die vollständige Fassung aus.", "max_iterations": 3 } }
```

### `approval` — Menschliche Freigabe

Pausiert, bis eine Person antwortet – im Dialog der Run-Seite oder auf der Seite **Freigaben** (`/approvals`), deren Eintrag in der Seitenleiste pulsiert, solange ein Lauf wartet. Für Schritte vor Push, Deploy oder Löschen.

```json
{ "type": "approval", "config": { "question": "## Ergebnis\n\n{{ previous_output }}\n\nFortfahren?",
  "options": ["Yes", "No", "Abort"], "reject_options": ["No", "Abort"], "on_no": "fail" } }
```

- `POST /api/v1/executions/{id}/approve` nimmt nur die `options` an (Groß-/Kleinschreibung egal), sonst 400. Ablehnung ist, was in `reject_options` steht (Standard „No“/„Nein“) – eigene Ablehnungen dort nennen, sonst läuft der Workflow trotz `on_no` weiter.
- „Yes always“ merkt die Antwort für dieselbe Frage bis zum Ende des Runs. `mode: "auto"` mit `auto_answer` antwortet sofort, ohne Person.

### `human-input` — Benutzereingabe sammeln

Pausiert, bis eine Person einen Wert eingibt oder ein Formular (`fields`) ausfüllt; die Antwort kommt immer als Text in die Variable `variable_name`.

```json
{ "type": "human-input", "config": { "prompt": "Welche Sprache soll die Doku haben?", "input_type": "select",
  "variable_name": "doc_language", "options": ["Deutsch", "English"], "default_value": "Deutsch" } }
```

Bei Timeout endet der Block mit `default_value` COMPLETED, wenn `use_default_on_timeout` gilt (Standard) und ein Standardwert gesetzt ist, sonst FAILED.

### `sub-workflow` — Verschachtelter Workflow

Führt einen anderen Workflow als Schritt aus und wartet auf ihn: höchstens 5 Ebenen, Zirkel werden erkannt, 30 Minuten. Bricht der Eltern-Lauf ab, bricht er auch den Kind-Lauf ab. Denselben Workflow je Listeneintrag startet `foreach` mit `workflow_id`.

```json
{ "type": "sub-workflow", "config": { "workflow_id": "<workflow-uuid>" } }
```

- Der Kind-Lauf bekommt die Variablen und `{{ previous_output }}` des Eltern-Laufs, aber nicht dessen Plattform-Daten (`previous_report`, `open_todos`, `request_metrics`, `goal_status`/`goal_guard_*`, `audit_findings`) – die berechnet die Plattform für ihn selbst. Der Taint reist mit.
- Die Ausgabe ist die letzte des Kind-Laufs (`derivedOutput: true`); `gate_status:`- und `todo_item:`-Marker darin zählen nur im Kind-Lauf. Platzhalter in `workflow_id`: wie bei `foreach`.

### `skill-capability` — Skill aufrufen

Ruft die Fähigkeit eines Skills als einen KI-Aufruf auf.

```json
{ "type": "skill-capability", "skillIds": ["builtin-code-review"],
  "config": { "capability": "review_code", "input": "Prüfe: {{ previous_output }}" } }
```

- Aufgelöst wird die Capability nur über die `skillIds` **des Blocks**. Ohne passenden Skill läuft nur der Capability-Name als Aufgabe – ohne Beschreibung, KI-Kontext und `writes`-Prüfung.
- Ohne `allow_writes` läuft die CLI mit einer Read-Only-Whitelist (Read/Grep/Glob/WebFetch/WebSearch/Task/ TodoWrite); eine `writes:true`-Capability (`builtin-test-generator/*`, `builtin-feature-implementer/*`) scheitert dann sofort.
- Endet die KI-CLI mit Exit-Code ≠ 0, scheitert der Schritt wie ein `simple`-Block.

### `integration` — Externer Dienst

Ruft eine Action eines verbundenen Integrations-Anbieters auf: [Integration Block](#integration-block-plugin-system).

### `document` — Text aus Dokumenten extrahieren

Extrahiert den Text einer Datei (PDF mit Text-Layer, DOCX/XLSX/PPTX, HTML/XML, TXT, EPUB, RTF, ODT) über Apache Tika, ohne native Abhängigkeiten, 0 Tokens.

```json
{ "type": "document", "config": { "file_path": "{{ invoice_path }}", "variable_name": "invoice_text", "fail_on_empty": true } }
```

Grenzen: 50 MB je Datei (`automation.document.max-bytes`), 5 Mio. Zeichen Ausgabe (`automation.document.max-characters`); `../` aus dem Projekt-Pfad wird blockiert. Gescannte PDFs ohne Text-Layer liefern leeren Text – kein OCR.

### `todo-create` / `todo-update` / `todo-close` — Native TODO-Operationen

Legen TODOs direkt über den TodoService an, ändern oder schließen sie – kein REST-Self-Call, kein DB-Zugriff, 0 Tokens.

```json
[ { "type": "todo-create", "config": { "title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"] } },
  { "type": "simple", "config": { "command": "Behebe das Problem ..." } },
  { "type": "todo-close", "config": { "id": "{{ todo_id }}" } } ]
```

- `todo-update` braucht genau eins: `id` (ein TODO; nur gesetzte, nicht leere Felder ändern sich) oder `updates`, eine JSON-Liste für bis zu 50 TODOs **des Lauf-Projekts**. `note` wird an die Beschreibung gehängt (`— <Datum> · <Workflow> (Lauf <id8>): <note>`, Datum in der Zeitzone der Instanz), einzeilig, auf 1000 Zeichen gekürzt und mit entschärften Markern (`UntrustedText.inline`); `add_tags`/`remove_tags`/`require_tags` je höchstens 10 Tags aus `[A-Za-z0-9_:.-]{1,60}` (`TodoBulkUpdates`), sonst wird der Eintrag übersprungen. Kaputtes JSON, keine Liste oder ein unbekannter Schlüssel lassen den Schritt scheitern, bevor etwas geschrieben ist; ein unpassender Eintrag wird mit Grund übersprungen (`✓ N aktualisiert, M übersprungen`).
- Für **dynamisch viele** TODOs aus Analyse-Workflows `todo_item:`-Marker verwenden ([TODO-Marker](#todo-marker-todo_item)); Audit-Templates nutzen das Envelope `mysuricates:audit-result:v1`.

### `compress` / `compress-ai` — Ausgaben sichtbar verdichten

Verdichten die Ausgabe **genau eines** früheren Blocks: `source` ist ein Blockname (auch ein benannter `switch`-Case-Block) oder `"previous"`. Das Original bleibt unter `{{ block.<quelle>.output }}`, die Verdichtung ist endgültig.

```json
[ { "type": "shell", "name": "backend_tests", "config": { "command": "mvn -q test", "on_failure": "continue", "checked_command": true } },
  { "type": "compress", "name": "backend_kompakt", "config": { "source": "backend_tests", "stages": [
      { "op": "text.normalize" }, { "op": "lines.filter", "exclude": ["^\\s+at "] },
      { "op": "text.truncate", "max_chars": 18000, "keep": "head_tail" } ] } },
  { "type": "simple", "config": { "command": "Bewerte:\n{{ block.backend_kompakt.output }}" } } ]
```

- Die Ausgabe beginnt mit einer Kopfzeile aus Quelle, Status, Exit-Code, Dauer und Zeichen vorher → nachher und einer Zeile mit den Stufen (englisch); Status und Exit-Code stammen aus den gespeicherten Schrittdaten. Gate- und TODO-Marker darin werden nicht ausgewertet.
- `compress`: Die [Stufen](block-referenz.md#stufen) laufen der Reihe nach, JSON-Stufen vor Textstufen.
- `compress-ai`: ein werkzeugloser Aufruf, nur Provider `CLAUDE` (sonst am Block `"provider": "CLAUDE"`), in einem frischen, privaten Arbeitsverzeichnis (`--tools ""`, `--strict-mcp-config`, `--setting-sources local` – Claude-Einstellungen auf Benutzerebene gelten nicht). Eingaben über der Einstellung `compress-ai-max-input-chars` (Standard 60.000) scheitern ohne Aufruf. Das stärker isolierte `--bare` liest keine OAuth-Anmeldung und ist deshalb nicht im Einsatz (TODO edb38a38).
- Beide sind als verschachtelter Block in `switch` nicht erlaubt.

### `dashboard-report` — Dashboard-Ergebnis ohne Marker melden

Füllt das Dashboard-Ergebnis des Runs direkt aus der Config – dieselben Felder, Grenzen und Prüfung wie der [Marker `dashboard_widget:`](#dashboard-marker-dashboard_widget), aber ohne Textsuche; kein KI-Aufruf, 0 Tokens. Modul `dashboard-report` (Standard an).

```json
{ "type": "dashboard-report", "config": { "status": "{{ block.ampel.output }}", "title": "Nightly QA",
  "kpis": [{ "label": "Hunts", "value": "{{ hunt_count }}", "trend": "UP" }], "summary": "{{ block.analyse.output }}" } }
```

- Ein zur Laufzeit leerer Platzhalter lässt das Feld entfallen (bei `status` ein Fehler); ein KPI entfällt nur, wenn alle seine Werte leer sind. Ein **unaufgelöster** Platzhalter oder ein Vertragsbruch (zu lang, unbekanntes Feld in einem KPI, Zahl statt String, unsichtbare Zeichen) lässt den Schritt scheitern; die Meldung nennt das Feld, nie den Wert.
- Hat ein `dashboard-report`-Block geschrieben, wird kein `dashboard_widget:`-Marker mehr ausgewertet; von zwei Blöcken gewinnt der zuletzt ausgeführte. `{{ gate_status }}` gibt es während des Laufs nicht.
- Im Editor hat der Block ein Formular mit einem optionalen Knopf „Vorschlag aus dem letzten Run“ (ein KI-Aufruf, Einstellungen → Ausführung, Anbieter `CLAUDE`); übernommen wird erst nach Bestätigung.

---

## 3. Template-Variablen

### Variablen-Syntax

Variablen verwenden doppelte Klammern: `{{ variable_name }}` oder `{{variable_name}}`. Das Leerzeichen muss auf beiden Seiten gleich sein: `{{ variable_name}}` wird **nicht** aufgelöst und bleibt wörtlich stehen.

**Hilfe im Block-Editor:** Nach `{{` schlägt das Konfigurationsfeld genau die Variablen vor, die an dieser Stelle belegt sind: Ausgaben früherer Blöcke, deren `variable_name`, System-Variablen und Startwerte. Hinter jedem Platzhalter steht, woher der Wert kommt (`{{ previous_output }} ← #3 backend_tests`), der Hover erklärt ihn. Gelb markiert werden Verweise auf Blöcke, die es nicht gibt oder die erst später laufen, ungleiche Leerzeichen sowie gequotete Ausgabe-Platzhalter in Shell-Befehlen. Die Logik steht in `ai-automation-frontend/src/utils/placeholderContext.ts` und folgt `BlockStepRunner`/`ExecutionContextFactory`: Wer dort eine Variable ergänzt, ergänzt sie auch hier.

### Eingebaute Variablen

| Variable | Quelle | Beschreibung |
|---|---|---|
| `{{ previous_output }}` | Auto | Ausgabe des vorherigen Blocks (Step N-1) |
| `{{ step_N_output }}` | Auto | Ausgabe von Step N (z.B. `{{ step_0_output }}` für den ersten Block) |
| `{{ block.<name>.output }}` | Auto | Ausgabe eines benannten Blocks (Block muss ein `name`-Feld haben) |
| `{{ previous_chain_output }}` | Auto | Ausgabe des vorherigen Chain-Eintrags (nur Chains) |
| `{{ chain_run_id }}` | Auto | ID des Chain-Runs, der den Eintrag gestartet hat (nur Chains); derselbe Wert steht als `chainRunId` an dem Lauf, den die Chain startet — nicht an Läufen, die den Kontext nur erben (Retry, Fortsetzen ohne abgeschlossenen Schritt, Sub-Workflow): Dort ist der Platzhalter belegt, `chainRunId` bleibt `null` |
| `{{ item }}` / `{{ <variable_name> }}` | ForEach | Aktuelles Schleifen-Item |

### System-injizierte Variablen

Folgende Variablen werden vom `WorkflowExecutor` automatisch bei jedem Run gesetzt und stehen in allen Blöcken zur Verfügung:

| Variable | Beschreibung |
|---|---|
| `{{ workflow_run_id }}` | Eindeutige Run-ID |
| `{{ workflow_id }}` | Workflow-ID |
| `{{ workflow_name }}` | Workflow-Name |
| `{{ project_id }}` | Projekt-ID (oder leer) |
| `{{ project_path }}` | Projektpfad (oder leer) |
| `{{ model }}` | Konfiguriertes Modell (oder leer) |
| `{{ provider }}` | Konfigurierter AI-Provider (oder leer) |
| `{{ effort }}` | Konfigurierter Reasoning-Effort (oder leer) |
| `{{ started_at }}` | Startzeitpunkt des Runs (ISO-8601, z.B. `2026-04-22T21:30:45.123Z`) |
| `{{ run_timestamp }}` | Pfadsicherer Timestamp (UTC, Format `yyyyMMdd-HHmmss`, z.B. `20260422-213045`) — ideal für Output-Verzeichnisse pro Run |
| `{{ home_dir }}` | Home-Verzeichnis des JVM-Benutzers |
| `{{ user }}` | Betriebssystem-Benutzername |
| `{{ workflow_version }}` | Aktuelle Versionsnummer des Workflows |
| `{{ workflow_changed_since_last_run }}` | `"true"` oder `"false"` — ob sich die Definition seit dem letzten erfolgreichen Run geändert hat (Hash-Vergleich; ohne Hash über Zeitstempel) |
| `{{ workflow_definition_hash }}` | SHA-256 der ausgeführten Definition (leer, wenn `automation.hardening.provenance` aus ist) |
| `{{ trigger_source }}` | Auslöser des Runs: `MANUAL`, `RETRY`, `RESUME`, `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `TODO_RESOLUTION`, `COMPLETION_TRIGGER`, `CHAIN`, `SUB_WORKFLOW`, `ASSISTANT`, `AUTO_REPAIR`, `AUTO_RESUME`, `WISH` |
| `{{ previous_report }}` | Ausgabe des Report-Blocks (`metadata.audit.reportBlock`) aus dem letzten COMPLETED-Run desselben Workflows, in dem dieser Block abgeschlossen hat; ohne `reportBlock` der längste Step-Output ab dem zweiten Schritt (über 200 Zeichen). Max. 3000 Zeichen, sonst `"(kein vorheriger Report vorhanden)"`. Ein Audit-Envelope ist abgeschnitten, Plattform-Marker verlieren ihren Doppelpunkt (`gate_status GREEN` bleibt lesbar, wird aber nicht mehr als Urteil gewertet, wenn ein Modell die Zeile wiedergibt), Blockgrenzen wie `</audit_findings>` stehen als `[audit_findings]` da. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ open_todos }}` | Markdown-Liste offener/in-Bearbeitung-TODOs des Projekts in einem `<open_todos>…</open_todos>`-Block, oder `"(keine offenen TODOs)"`. Titel und Tags sind auf eine Zeile geglättet, auf 200/80 Zeichen gekürzt und von Plattform-Markern (`todo_item:`, `gate_*:`, `dashboard_widget:`) befreit — sie können aus importierten Issues stammen und gelten als Daten, nicht als Anweisung. Maximal 150 Einträge. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ audit_findings }}` | **Nur in Audit-Workflows** (gültiges `metadata.audit`): die bekannten Befunde des eigenen Audits im Projekt — offen, Wiedervorlage, akzeptiert, verworfen — in einem `<audit_findings>…</audit_findings>`-Block, je Befund eine Zeile mit Status, Schwere, `ruleKey`, `subject`, Titel und Einordnung, höchstens 250 Zeilen und 60 000 Bytes (eingeordnete zuerst, der Rest als Anzahl je Status); ohne Befunde `"(keine bekannten Befunde)"`. In anderen Workflows gibt es die Variable nicht, auch nicht über die Variablen-Übergabe an einen Sub-Workflow; der Platzhalter bliebe dort wörtlich stehen (Analyzer-Regel `audit-only-placeholder`). Format und Zweck: [audit-workflow-protocol.md](audit-workflow-protocol.md) → „Bekannte Befunde im Prompt“. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ todo_details }}` | **Nur mit Filter** (`todo_details_tags` und/oder `todo_details_exclude_tags` im Lauf-Kontext, meist `defaultContext`): TODOs des Lauf-Projekts **mit Beschreibung** als JSON-Liste im Block `<todo_details>…</todo_details>` – je Eintrag `id`, `status`, `priority`, `tags`, `title`, `complexity`, `suggestedEffort`, `openQuestion`, `updatedAt`, `description`. Weitere Filter: `todo_details_status` (OPEN/IN_PROGRESS/BACKLOG, Vorgabe OPEN), `todo_details_first_tags` (nach vorn; zählen nicht gegen das Limit, nur gegen die Byte-Grenze), `todo_details_limit` (≤ 150, Vorgabe 40), `todo_details_chars` (je Beschreibung ≤ 8000, Vorgabe 1500); die ganze Liste höchstens 100 000 Bytes UTF-8, damit ein Shell-Befehl unter der Grenze von 128 KiB je Argument bleibt. Tags vergleicht die Plattform genau; `batch:*` heißt „jedes Tag mit diesem Anfang“. Reihenfolge: `first_tags`, dann Priorität, dann Alter. Titel geglättet, Beschreibungen zeilenweise von Markern befreit. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |

> **Plattform-Daten fehlen nie stillschweigend.** `previous_report`, `open_todos` und
> `request_metrics` (in Audit-Workflows auch `audit_findings`) werden einzeln injiziert: Scheitert
> eine dieser Ladungen, bekommen die anderen sie trotzdem, und die ausgefallene Variable steht als
> `NICHT GEMESSEN: <name> (nicht verfügbar: <Fehlerart>)` im Kontext — dazu eine WARN-Zeile im
> Activity-Log mit der Run-Id. Ein Gate-Block sieht also die Lücke und nicht den rohen Platzhalter
> `{{ … }}` oder einen leeren Wert, der als GREEN durchginge.

#### Ziel-Kontext: `goal_status` und `goal_guard_*`

Nur wenn der zusammengeführte Kontext eines Laufs `goal_id` trägt (Default-Kontext oder Eingabe, etwa
der Zeitplan des Ziel-Wächters), injiziert die Plattform den Stand dieses Ziels (TODO 259f4f66). Ohne
`goal_id` gibt es diese Variablen nicht. Alle gelten als nicht vertrauenswürdig (Titel können aus
importierten Issues stammen) → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben. Eine
unbekannte `goal_id` setzt **jede** davon auf `NICHT GEMESSEN: <name> (nicht verfügbar: …)` und
schreibt eine WARN-Zeile ins Activity-Log. Die Sprache der Texte wählt `goal_locale` (`de`/`en`,
ohne Angabe Deutsch). Kein REST-Aufruf nötig – und keiner erlaubt.

| Variable | Inhalt |
|----------|--------|
| `{{ goal_status }}` | Block `<goal_status>…</goal_status>` mit Ziel, Zieldatum, Fortschritt, Meilensteinen (Termine des Ziels, mit abgeschlossenen/verknüpften Arbeitspaketen) und Arbeitspaketen (Zeitraum oder „ohne Zeitraum“ – dann nie fällig –, erledigte/zugeordnete TODOs), je samt Zustand (`OPEN`/`DUE_SOON`/`OVERDUE`/`REACHED`, `REOPENED`), überfälligen und bald fälligen (≤ 3 Tage) aktiven TODOs. Titel geglättet, gekürzt und von Plattform-Markern (`todo_item:`, `gate_*:`, `dashboard_widget:`) und Blockgrenzen befreit; je Liste höchstens 20 Zeilen |
| `{{ goal_guard_status }}` | `RED` (etwas überfällig oder Zieldatum mit offener Arbeit vorbei), `YELLOW` (etwas in ≤ 3 Tagen fällig), sonst `GREEN` |
| `{{ goal_guard_title }}` | `Ziel: <Titel>`, höchstens 80 Zeichen |
| `{{ goal_guard_summary }}` | Eine Zeile: `7/12 erledigt (58 %) · 2 überfällig · 1 bald fällig · nächster Meilenstein „Beta“ am 2026-10-01`, höchstens 500 Zeichen |
| `{{ goal_guard_progress }}` | `7/12 (58 %)` |
| `{{ goal_guard_overdue }}` / `{{ goal_guard_due_soon }}` | Anzahl überfälliger bzw. bald fälliger TODOs, Meilensteine und Arbeitspakete (Basis: ihr Ende) |
| `{{ goal_guard_item_1 }}` … `{{ goal_guard_item_5 }}` | Die dringendsten Punkte (überfällige Meilensteine, überfällige Arbeitspakete, überfällige TODOs, Zieldatum, bald Fälliges in derselben Folge – „Meilenstein …“, „Arbeitspaket …“), je höchstens 200 Zeichen; freie Plätze sind leer und fallen in `dashboard-report` weg |
| `{{ goal_guard_items }}` | Alle Punkte, eine Zeile je Punkt |

Die Kachelwerte sind frei von Steuer- und Formatzeichen und halten die Längen des Dashboard-Vertrags
ein – sie passen unverändert in einen `dashboard-report`-Block. Genau so arbeitet die mitgelieferte
Vorlage **Ziel-Wächter** (`goal-guard`, kein Modell): ein Shell-Block schreibt `{{ goal_status }}` mit
`shell_escape_variables` ins Protokoll, ein `dashboard-report`-Block füllt die Kachel. Angelegt wird
sie über den Schalter „Wächter“ auf der Zielseite (`POST /api/v1/goals/{id}/guard`), je Ziel ein
Workflow mit täglichem Zeitplan.

### Kontext-Variablen

Eigene Variablen über den Ausführungskontext übergeben:

```json
{
  "context": {
    "filepath": "/home/user/dokument.md",
    "language": "Deutsch",
    "batch_size": "50"
  }
}
```

Zugriff in jeder Block-Config: `{{ filepath }}`, `{{ language }}`, `{{ batch_size }}`.

### Auflösung

Variablen werden zur Laufzeit durch `ExecutionContext.resolveTemplate()` aufgelöst. Nicht aufgelöste Variablen bleiben als Literaltext stehen (z.B. `{{ undefined_var }}` bleibt unverändert).

### Approval/Human-Input-Variablen

Antworten aus `approval`- und `human-input`-Blöcken werden als Kontext-Variablen gespeichert und stehen in nachfolgenden Blöcken zur Verfügung:

```json
// approval-Block speichert Antwort in {{ pr_decision }}
// nächster Block kann verwenden: {{ pr_decision }}
```

---

## 4. Bewährte Workflow-Patterns

Diese Patterns sind aus den 65+ eingebauten Templates destilliert. Jedes Pattern hat eine klare Struktur und einen Anwendungsfall.

### Block-Wahl: Sparsamkeits-Leiter

Beim Workflow-Bau gilt dieselbe YAGNI-Logik wie beim Code — die erste zutreffende Stufe gewinnt:

1. **Geht es deterministisch?** → `shell`-Block (0 Tokens, schnell, reproduzierbar)
2. **Reicht ein einzelner KI-Call?** → `simple` (1 Call) statt `agentic` (N Calls: REASON + ACT pro Iteration)
3. **`agentic` nur**, wenn die nötigen Schritte vorab unbekannt sind und das Ergebnis verifizierbar ist
4. **Existierender Block-Typ vor neuem Plattform-Feature** — erst die Referenz in Abschnitt 2 prüfen
5. **Wenige Blöcke vor vielen** — jeder Block kostet Tokens, Laufzeit und ist ein eigenes Timeout-Risiko

Keine Blöcke „auf Vorrat" (Zusammenfassungen, Reports, Formatierungen, die niemand liest).
Token-Budget ist knapp: Jeder vermiedene KI-Call verlängert die Laufzeit des Kontingents.

### Pattern 1: Sequentielle Simple-Kette

**Struktur**: `simple` -> `simple` -> `simple` -> `simple`

Alle Blöcke sind rein KI-basiert (kein Shell). Jeder Block verfeinert die Ausgabe des vorherigen.

**Anwendungsfall**: Textanalyse, Content-Erstellung, Uebersetzung, Code-Review.

**Templates**: `file-improve`, `code-refactor`, `translation-pipeline`, `fact-check-pipeline`, `project-overview`

```
Block 1: Eingabe lesen/erkunden
Block 2: Analysieren/bewerten
Block 3: Verbessern/transformieren
Block 4: Zusammenfassen/ausgeben
```

---

### Pattern 2: Shell sammeln + KI analysieren

**Struktur**: `shell` -> `simple` (oder `skill-capability`) -> `simple`

Shell-Block sammelt Rohdaten, KI-Block analysiert sie.

**Anwendungsfall**: Log-Analyse, Git-Historie, System-Metriken, Abhängigkeitsprüfungen.

**Templates**: `git-to-changelog`, `log-triage`, `git-statistics-report`

```
Block 1 (shell): Rohdaten sammeln (git log, find, grep, Metriken)
Block 2 (simple): Analysieren und kategorisieren
Block 3 (simple): Bericht mit handlungsorientierten Empfehlungen erstellen
```

**Große Ausgaben:** zwischen Shell und KI einen `compress`-Block setzen; bei sehr großen Logs `compress` → `compress-ai`.

---

### Pattern 3: Analysieren + Agentic erstellen + Verifizieren

**Struktur**: `simple` -> `agentic` -> `simple`

KI analysiert Eingabe, Agentic-Block erstellt komplexe Ausgabe (Dateien, Code), Simple-Block verifiziert.

**Anwendungsfall**: Webseiten, Prototypen, Dokumentationspakete, Dashboards generieren.

**Templates**: `doc-to-website`, `idea-to-prototype`, `cv-to-portfolio`, `csv-to-dashboard`, `codebase-to-onboarding`

```
Block 1 (simple): Eingabe analysieren, Plan erstellen
Block 2 (agentic): Plan ausführen — Dateien erstellen, Deps installieren, bauen
Block 3 (simple): Ausgabe verifizieren, Qualitätsprüfung
```

---

### Pattern 4: Shell + Bedingte Verzweigung

**Struktur**: `shell` -> `conditional` -> `shell`/`simple`

Shell-Block generiert Daten, Conditional-Block verzweigt basierend auf Ergebnis.

**Anwendungsfall**: Deploy-Pipelines, CI/CD, bedingte Verarbeitung.

**Templates**: `deploy-pipeline`, `git-to-changelog` (bedingte Migrationshilfe)

```
Block 1 (shell): Vorbedingungen prüfen
Block 2 (conditional): Basierend auf Ergebnis verzweigen
Block 3: Entsprechende Aktion ausführen
```

---

### Pattern 5: Shell scannen + ForEach verarbeiten

**Struktur**: `shell` -> `foreach` -> `simple`/`skill-capability`

Shell-Block entdeckt Items, ForEach verarbeitet jedes einzelne, letzter Block aggregiert.

**Anwendungsfall**: Multi-Repo-Operationen, Batch-Dateiverarbeitung, Massenanalyse.

**Templates**: `dependency-risk-radar`

```
Block 1 (shell): Items entdecken (Repos finden, Dateien auflisten etc.)
Block 2 (foreach): Jedes Item verarbeiten
Block 3 (simple): Ergebnisse aggregieren und zusammenfassen
```

---

### Pattern 6: Audit-Pipeline

**Struktur**: `shell` -> `simple` -> `simple` -> `simple` (+ optional `conditional` + TODO-Block)

Shell sammelt Code-/Config-Daten, mehrere Simple-Blöcke analysieren verschiedene Aspekte, letzter Block erstellt strukturierten Bericht.

**Anwendungsfall**: Code-Audits, Security-Scans, Dokumentationsprüfungen, Gesundheitsbewertungen.

**Templates**: `api-consistency-audit`, `dead-code-detector`, `doc-accuracy-check`, `codebase-health-scorecard`, `architecture-debt-radar`, `test-coverage-gaps`

```
Block 1 (shell): Rohdaten sammeln (grep, find, wc etc.)
Block 2 (simple): Tiefenanalyse mit spezifischen Kriterien
Block 3 (conditional): Optionale Schweregrad-Prüfung
Block 4 (simple): Strukturierter Bericht mit Findings-Tabelle + Aktionsplan
Block 5 (agentic): Optional: TODOs aus Findings erstellen
```

---

### Pattern 7: Mehrstufige Pipeline mit Freigabe-Gate

**Struktur**: `shell` -> `simple` -> `agentic` -> `shell` -> `conditional` -> `approval` -> `shell`

Das komplexeste Pattern. Safety-First mit Verifikation und menschlicher Freigabe.

**Anwendungsfall**: Automatisierte Code-Fixes mit PR-Erstellung, Deploy mit Rollback.

**Templates**: `audit-fix-pr-pipeline`

```
Block 1 (shell): Git-Safety-Setup (sauberen Tree prüfen, Branch erstellen)
Block 2 (simple): Findings parsen, Fix-Plan erstellen
Block 3 (agentic): Fixes anwenden, ein Commit pro Fix
Block 4 (shell): Verifikation (Build + Test + Lint)
Block 5 (conditional): Rollback bei Fehler
Block 6 (approval): Menschliche Freigabe vor Push
Block 7 (shell): Push + PR erstellen (oder abbrechen)
```

---

### Quality-Gate-Marker

Jeder Block kann Gate-Status-Marker in seiner Ausgabe emittieren um Qualitätsergebnisse zu signalisieren. Der `WorkflowExecutor` scannt **alle** Block-Ausgaben und verwendet den höchsten Schweregrad.

```
gate_status: GREEN|YELLOW|RED
gate_category: lint-errors
gate_description: 3 Probleme gefunden
```

| Gate | Auto-Todo-Priorität | Bedeutung |
|------|-------------------|---------|
| GREEN | — | Alle Prüfungen bestanden |
| YELLOW | MEDIUM | Warnungen, nicht-blockierende Probleme |
| RED | HIGH | Kritische Probleme (via `AutoTodoService` behandelt) |

**Ein Gate ändert den Run-Status nicht.** Ein Lauf, der ein RED meldet, steht trotzdem auf
`COMPLETED` — das Gate ist ein Befund über das Ergebnis, kein Fehlschlag der Ausführung. Den
Run-Status bestimmt etwas anderes: `COMPLETED_WITH_ERRORS` entsteht, wenn ein Schritt mit
`checked_command` fehlschlägt (`RunCompletionHandler`), und `FAILED`, wenn die Ausführung selbst
abbricht. Eine frühere Fassung dieser Tabelle führte eine Spalte „Run-Status", die eine
Kausalität behauptete, die es im Code nicht gibt.

**Schon die Erwähnung bestellt ein Urteil.** Steht `gate_status` (oder `gate-status`) irgendwo in
einer Block-Config — auch in einem Verbot wie „Verwende das Wort gate_status nicht" —, erwartet
der Lauf ein Urteil (`GateExpectation`). Bleibt es aus, wird er YELLOW `gate-missing` und erzeugt
ein Follow-up-TODO. KI-Prompts, die keine Marker schreiben sollen, deshalb ohne das Wort
formulieren („keine Statuszeilen") und das Urteil im abschließenden Shell-Block setzen. Gesehen
2026-09-20 bei der Doku-Durchsicht.

**Ein unlesbares Urteil wird YELLOW.** Steht in den Lauf-Metadaten ein `gate_status`, der weder
GREEN noch YELLOW noch RED ist, wird er geloggt und der Lauf YELLOW `gate-invalid`
(`GateStatusResolver`). Der Wert darf das Urteil aus dem Laufstatus nur verschärfen, nie mildern: Ein
`FAILED`-Lauf bleibt RED. Grund: Vorher fiel ein unlesbarer Wert stumm auf GREEN – ein Prüf-Workflow,
dessen KI-Schritt still scheiterte, war nicht von einem Workflow ohne Gate zu unterscheiden.

**Shell-Block-Beispiel** mit Gate-Emission:
```bash
set -euo pipefail
ERRORS=$(eslint src/ --format json | jq '[.[] | .errorCount] | add // 0')
if [ "$ERRORS" -gt 0 ]; then
  echo "gate_status: RED"
  echo "gate_category: lint-errors"
  echo "gate_description: $ERRORS Lint-Fehler gefunden"
elif [ "$WARNINGS" -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: lint-warnings"
  echo "gate_description: $WARNINGS Warnungen gefunden"
else
  echo "gate_status: GREEN"
fi
```

Wenn mehrere Blöcke Gates emittieren, gewinnt der höchste Schweregrad (RED > YELLOW > GREEN). Bei Gleichstand gilt für RED und YELLOW das erste Gate – die Ursache, nach deren Kategorie auch das Auto-TODO heißt –, für GREEN das letzte, also das abschließende Urteil statt des `Command exit 0` eines `checked_command` davor – es sei denn, es sagt weniger: Das synthetische `Command exit N` verdrängt nie eine Beschreibung mit eigenen Worten, auch nicht hinter dem Urteil, und ein GREEN ohne `gate_description` keines mit. Fehlt dem späteren die `gate_category`, bleibt die bisherige (TODO 2aa9ddd5).

**Gate-Verdict nach KI-Blöcken: Fallback nie GREEN.** Ein Shell-Block, der den `gate_status` aus dem Output eines Simple-/Agentic-Blocks parst, darf im `else`-Zweig nicht GREEN melden — sonst wird ein leerer, abgebrochener oder als `UNKNOWN` markierter KI-Report zum grünen Gate (gesehen 2026-09-13, Security-Audit ohne Codezugriff). Muster: RED → RED, YELLOW → YELLOW, explizites GREEN → GREEN, sonst YELLOW mit `gate_description: Ergebnis unvollständig — kein Gate-Status im Report`. Deterministische Build-Gates (Exit-Code entscheidet) dürfen weiter `else → GREEN` nutzen.

**Das Gate liest den Report-Block selbst, nicht dessen Zusammenfassung.** Folgt auf den Report noch ein KI-Block, der ihn kurz zusammenfasst, dann parst das Gate `{{ block.report.output }}` und nimmt dort die letzte `gate_status:`-Zeile, nicht `{{ previous_output }}`. Gültig ist nur eine Zeile mit genau einem Urteilswort nach dem Doppelpunkt, davor und dahinter höchstens Markdown (`*`, `_`, `` ` ``) und Leerraum: Gibt das Modell die Schablonenzeile „gate_status: GREEN wenn …, YELLOW wenn …“ unverändert zurück, zählt sie nicht – sonst entschiede ihr erstes Wort. Ohne gültige Zeile greift der Fallback YELLOW. Zwei Audits liefen 2026-09 in den Fallback UNVOLLSTÄNDIG, weil die Zusammenfassung „7,1/10 (YELLOW)“ statt einer `gate_status:`-Zeile schrieb (TODO 68dbfa19 „Audit-Workflows optimieren“). Die Zusammenfassung selbst gibt dann keine `gate_`-Zeilen aus: `gate_summary` wertet die Gate-Zeilen **jedes** Steps aus, eine zweite Zeile wäre ein zweiter Gate-Eintrag. Die Plattform-Audits verzichten seitdem ganz auf so eine Zusammenfassung: Ihre Ausgabe las kein Block, und genau das meldet der Analyzer als `unwired-output`.

**`run.metadata.gate_summary`** aggregiert zusätzlich alle geparsten Gates des Runs:

```json
{
  "gate_summary": {
    "highest": "YELLOW",
    "counts_by_status": {"GREEN": 3, "YELLOW": 2},
    "items": [
      {"step_index": 0, "status": "GREEN", "category": "public-inventory"},
      {"step_index": 2, "status": "YELLOW", "category": "lint"}
    ]
  }
}
```

Damit lassen sich zwei Runs ohne Re-Parsing diffen (z. B. für Public-Readiness-Trends):

```bash
curl -s --noproxy '*' -H "X-API-Key: $KEY" "$BASE/executions/$RUN1" | jq '.metadata.gate_summary' > a.json
curl -s --noproxy '*' -H "X-API-Key: $KEY" "$BASE/executions/$RUN2" | jq '.metadata.gate_summary' > b.json
diff <(jq -S . a.json) <(jq -S . b.json)
```

### TODO-Marker (`todo_item:`)

Neben dem Gate kann jeder Block beliebig viele TODOs vorschlagen, je eine Zeile in seiner Ausgabe:

```
todo_item: <Titel> | <CRITICAL|HIGH|MEDIUM|LOW> | <tag1,tag2> | <Beschreibung, optional>
```

```
todo_item: ChainExecutor testen | HIGH | backend,testing | Execution-Loop ohne Test
todo_item: useWebSocket-Hook testen | MEDIUM | frontend,testing
```

- Gelesen wird die Ausgabe **aller** Schritte (`TodoOutputParser`); Markdown davor (`#`, `**`,
  Backticks) und das `[<item>]: `-Präfix einer `foreach`-Iteration stören nicht.
- Angelegt werden die TODOs nur, wenn Auto-TODO für den Workflow an ist — Workflow-Metadatum
  `autoTodo.enabled`, sonst die globale Einstellung `auto-todo-default` — und nicht bei
  Audit-Workflows (`audit.enabled`), deren Befunde das Audit Center verwaltet (`AutoTodoService`).
- Der Titel ist die Identität: Derselbe Titel in einem späteren Lauf aktualisiert das TODO; was ein
  Lauf mit Markern nicht mehr nennt, schließt er (`GranularTodoService`). Ein neues TODO verknüpft
  eine KI-Ähnlichkeitssuche mit einem gleichen oder verwandten offenen TODO (`relatedTodoId`),
  angelegt wird es trotzdem.

### Dashboard-Marker (`dashboard_widget:`)

Ein Workflow kann sein Dashboard-Ergebnis auch als Markerzeile in einer Blockausgabe melden. Der bewusst konfigurierte Weg ist der [`dashboard-report`-Block](#dashboard-report--dashboard-ergebnis-ohne-marker-melden); hat er geschrieben, wird kein Marker ausgewertet – auch ein kaputter setzt dann kein `dashboard_widget_error`. Das JSON darf über mehrere Zeilen gehen:

```
dashboard_widget: {"title": "Nightly QA", "status": "YELLOW",
  "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}],
  "items": ["3 Hunts ohne Route"], "summary": "Zwei Befunde."}
```

- Felder und Grenzen wie beim `dashboard-report`-Block ([Block-Referenz](block-referenz.md#dashboard-report)): `status` ist Pflicht (`GREEN`, `YELLOW`, `RED`), KPI-`label` und -`value` je ≤ 40 Zeichen, `value` ist ein **String**, `trend` `UP`/`DOWN`/`FLAT`. Ein unbekanntes Feld – auch in einem KPI-Objekt – lehnt den ganzen Vertrag ab, ebenso ein leerer, zu langer oder nicht einzeiliger Text (Steuer-, Zero-Width-, Bidi-, Separator-, Private-Use-Zeichen). Geprüft wird beim Run-Ende und beim Lesen erneut.
- Gelesen wird höchstens 8192 Zeichen weit; schließt die Klammer bis dahin nicht, ist der Marker ungültig. Melden mehrere Blöcke einen Marker, gewinnt der letzte; Ausgaben von Komprimierungs-Blöcken (`derivedOutput`) zählen nicht. **Zwei Marker in derselben Blockausgabe lehnen diese Ausgabe ab** (`resultState: INVALID`): Ein zitierter Marker darf das eigene Ergebnis nie überstimmen.
- Den Marker am Zeilenanfang schreiben, das JSON direkt dahinter (ein Codeblock in der nächsten Zeile geht auch). Grund: Steht er mitten in einer Zeile und trennt ihn eine Leerzeile von seinem JSON – oder trennt ein geschütztes Leerzeichen (U+00A0) oder ein NEL (U+0085) den Doppelpunkt vom JSON –, wird er nicht erkannt; dann kann ein aus fremdem Text zitierter Marker der einzige erkannte sein und gewinnen. Das Dashboard zeigt den Inhalt nur als Text – kein Markdown, HTML, Link oder Bild.
- Lesen: `GET /api/v1/dashboard/workflow-results?workflowIds=…` liefert je Workflow den letzten Run und `resultState` (`VALID`, `INVALID` oder `MISSING`).

### `checked_command` / `check_category` — Gate automatisch aus dem Exit-Code

Statt selbst `gate_status`-Zeilen zu `echo`en, kann ein `shell`-Block das dem `CheckedCommandGate`
überlassen: `"checked_command": true` stellt dem Befehl `set -e -o pipefail` voran und leitet aus
dem Exit-Code ein Gate ab — **Exit 0 → `GREEN`, Exit ≠0 → `RED`**, Standardkategorie `build-check`
(überschreibbar über `check_category`), Beschreibung `Command exit <code>`.

```json
{
  "type": "shell",
  "config": {
    "command": "mvn -q test",
    "checked_command": true,
    "check_category": "backend-tests"
  }
}
```

**Vorrangregel, wenn das Skript selbst schon einen Gate-Marker ausgibt** (`CheckedCommandGate`,
seit b19f5225): Der eigene Marker des Skripts gewinnt, sobald er **mindestens so streng** ist wie
der aus dem Exit-Code abgeleitete — bei gleicher Schwere gewinnt also ebenfalls das Skript. Nur wenn
der Exit-Code strenger ausfällt als das, was das Skript selbst meldet (oder das Skript gar keinen
Marker liefert), setzt sich der Exit-Code durch. Kategorie und Beschreibung aus `check_category`
bzw. dem Default sind dabei immer nur ein Rückfall: Sie werden nur ergänzt, wenn das gewinnende
Skript-Gate diese Felder selbst nicht mitliefert (ein „nackter" `gate_status:` ohne Kategorie bleibt
sonst kategorielos).

Beispiel: Ein Skript, das selbst ein milderes `YELLOW` meldet, aber mit einem Fehler-Exit-Code
abbricht — der Exit-Code (`RED`) ist strenger und gewinnt:

```bash
#!/usr/bin/env bash
set -euo pipefail
echo "gate_status: YELLOW"
echo "gate_category: lint-warnings"
exit 2   # strenger als YELLOW → das Block-Gate meldet RED, nicht YELLOW
```

Meldet das Skript dagegen ein gleich strenges oder strengeres Gate als der Exit-Code nahelegt (z. B.
selbst `gate_status: RED` bei Exit 1), bleibt dessen `gate_category`/`gate_description` erhalten statt
durch `check_category`/den Default ersetzt zu werden.

Getestet in `ShellBlockExecutorTest` (u. a. `execute_checkedCommandExitZero_doesNotShadowTheScriptsOwnWorseGate`,
`execute_checkedCommandFailing_stillEscalatesAMilderScriptGate`,
`execute_checkedCommandFailingWithItsOwnRed_keepsTheScriptsCategoryAndDescription`,
`execute_checkedCommandWithABareScriptGate_fillsInCategoryAndDescriptionFromTheBlock`).

---

## 5. Timeout-Konfiguration

### Standardwerte

Die Standardwerte der Config-Schlüssel stehen in der [Block-Referenz](block-referenz.md), die Konstanten dahinter in `ExecutionDefaults.java`. Ohne `timeout_seconds` enden Shell- und Simple-Blöcke nach 300 s; Approval und Human-Input warten 30 Minuten, ein Sub-Workflow ebenso.

### Die Dual-Timeout-Falle

Agentic-Blöcke haben **zwei unabhängige Timeouts**, die aufeinander abgestimmt sein müssen:

1. **`timeout_seconds`**: Claude-CLI-Timeout pro Aufruf (gilt für REASON- und ACT-Phase)
2. **`iteration_timeout_seconds`**: Wall-Clock-Timeout für die gesamte Iteration (REASON + ACT)

**Die Falle**: Wenn `timeout_seconds=900` aber `iteration_timeout_seconds=300`, kann der CLI-Aufruf in 900s erfolgreich sein, aber die Iteration wird bei 300s als TIMEOUT markiert und der Block SCHEITERT.

### Auto-Alignment

`AgenticBlockConfig` stimmt Timeouts automatisch ab wenn `timeout_seconds` gesetzt ist:

```
iteration_timeout_seconds = max(aktuell, timeout_seconds * 2 + 60)
```

Die Formel: `timeout_seconds * 2` (Raum für REASON + ACT) `+ 60` (Overhead).

Wenn `total_timeout_seconds` nicht einmal eine Iteration fassen kann, wird auch das auto-aligned.

### Empfohlene Werte nach Modell

| Modell | `timeout_seconds` | `iteration_timeout_seconds` | `total_timeout_seconds` | `max_total_tokens` |
|---|---|---|---|---|
| **Opus** | 600-900 | 900-1260 | 3600-5400 | 300.000-400.000 |
| **Sonnet** | 300-600 | 300-660 | 1800-3600 | 200.000-300.000 |
| **Haiku** | 120-300 | 300 | 900-1800 | 100.000-200.000 |

### Simple-/Shell-Block-Timeouts

Für Simple- und Shell-Blöcke gilt nur `timeout_seconds`:

| Aufgabenkomplexität | Empfohlenes `timeout_seconds` |
|---|---|
| Schnelle Befehle (git, find) | 60-120 |
| Build/Test (npm, mvn) | 300-600 |
| Komplexe Analyse (Opus) | 600-900 |

**Bytecode-Messungen kosten Kompilierzeit**: Shell-Blöcke, die über den Bytecode messen (`scripts/public-readiness/arch-metrics.sh` mit `jdeps`), brauchen ein `target/classes` — im Workspace-Klon eines isolierten Runs fehlt das immer und der erste Lauf kompiliert (~1-2 Minuten, deshalb `timeout_seconds: 600`). Ab dem zweiten Run desselben Commits greift der Kompilat-Cache unter `$AUTOMATION_ARCH_CACHE` (Default `~/.cache/mysuricates/arch-classes`) und der Block ist in Sekunden durch; `compile=cached` im Abschnitt `## run` zeigt den Treffer. Bei uncommitteten Änderungen wird bewusst neu kompiliert.

---

## 6. Modellauswahl

Das Modell wird auf Workflow-Ebene gesetzt (`workflow.model`-Feld) oder auf null für den System-Standard belassen.

### Welches Modell wann verwenden

| Modell | Anwendungsfall | Stärken | Schwächen |
|---|---|---|---|
| `opus` | Analyse, Audits, Security-Reviews | Präzision, weniger Halluzinationen, tiefes Reasoning | Langsamer, teurer |
| `sonnet` | Code-Generierung, Routine-Aufgaben | Schnell, gut genug für die meisten Aufgaben | Kann Findings in Analysen halluzinieren |
| `haiku` | Einfache Klassifikation, Extraktion | Sehr schnell, günstig | Begrenzte Reasoning-Tiefe |
| `fable` | Schwerste Reasoning-/Agentic-Aufgaben | Stärkstes Modell | Teuerste Stufe |

Die Werte sind **CLI-Aliase** der Claude CLI und zeigen immer auf das neueste Modell ihrer Familie — sie
veralten nicht. Eine konkrete ID (z. B. `claude-opus-5`) kann im Modell-Feld frei eingetippt werden, wenn
ein Workflow auf eine Version gepinnt werden soll. Die Vorschlagsliste im UI kommt aus
`GET /api/v1/system/ai-providers/{id}/models` (pi: `pi --list-models`, codex: lokaler Modell-Cache).

### Kritische Regel

**Immer Opus für Analyse-Workflows verwenden.** Sonnet halluziniert Findings (z.B. falsche SQL-Injection-Schwachstellen melden). Für Code-Analyse, Security-Audits und Architektur-Reviews ist Präzision wichtiger als Geschwindigkeit.

### Provider-Regel: Codex ist read-only

`"provider": "CODEX"` ruft `codex exec` immer mit `--sandbox read-only` auf (`HostCodexService`). Blöcke, die
Dateien schreiben sollen (simple/agentic/foreach mit Datei-Output), melden trotzdem COMPLETED — die Dateien
fehlen aber (stiller Fehlschlag). Deshalb:

- **CODEX nur für Analyse/Extraktion ohne Datei-Writes** (Reports in `previous_output`, Klassifikation).
- **Schreibende Codex-Workflows über Pi:** `"provider": "PI"`, `"model": "openai-codex/gpt-5.5"` — der
  ACT-Pfad von Pi hat read/write/edit/bash-Tools und kann schreiben (Beispiel: `pi-smoke-test`).

**Sandbox startet nicht:** Meldet Codex' eigene Ausgabe einen Startfehler der Sandbox (eine Zeile, die mit `bwrap:` beginnt oder mit `ERROR`/`exec failed` und `…: bwrap:` enthält, etwa
`bwrap: setting up uid map: Permission denied` oder vereinzelt `bwrap: loopback: Failed RTM_NEWADDR: Operation not
permitted`), scheitert der Schritt mit „Codex sandbox could not start on this host (…)“ — auch bei Exit-Code 0 und
auch, wenn die Antwort nur aus dieser Fehlerzeile besteht. Der Provider wiederholt den Aufruf nicht; eine
blinde Antwort ohne Dateizugriff geht nicht an den nächsten Schritt weiter. Zeilen, die auch im Prompt oder
in der eigentlichen Antwort stehen (das Modell schreibt *über* bwrap), zählen nicht.

### Modell setzen

Via API:
```json
{
  "model": "opus"
}
```

Im Workflow-Entity wird das `model`-Feld via `--model`-Flag an die Claude CLI übergeben.

**Wichtig**: Ein null-Model-Feld nutzt den System-Standard (der Sonnet sein kann). Wenn Opus benötigt wird, explizit setzen.

### Reasoning-Effort (Denkaufwand)

Zusätzlich zum Modell kann pro Workflow ein einheitlicher Effort-Level gesetzt werden (`workflow.effort`-Feld, UI: Sidebar → „Denkaufwand"). Gültige Werte: `low`, `medium`, `high`, `xhigh`. Unset (null) = Provider-Default, es wird **kein** Flag emittiert.

| Provider | CLI-Mapping | Hinweise |
|---|---|---|
| Claude | `--effort <level>` | REASON- und ACT-Aufrufe |
| Pi | `--thinking <level>` | Explizites Effort gewinnt über die maxTurns-Heuristik; ohne Effort verhält sich der ACT-Pfad wie bisher (kein Flag) |
| Codex | — | Wird ignoriert (`supportsEffort=false`), UI blendet den Regler aus |

**Pro Block überschreiben**: Block-Config-Key `"effort"` übersteuert das Workflow-Level (Präzedenz: Block > Workflow > Provider-Default). Ungültige Block-Werte werden mit Warnung ignoriert.

```json
{
  "type": "simple",
  "config": {
    "command": "Analysiere die Architektur ...",
    "effort": "xhigh"
  }
}
```

Faustregel: `high`/`xhigh` für Analyse-/Audit-Blöcke, `low` für mechanische Transformationen. Der gesetzte Effort ist im Step-Metadata (`aiRequest.effort`) der Run-Details sichtbar, wenn der Anbieter ihn anwendet (bei Codex fehlt er). Ohne gesetzten Effort gilt der Standard der CLI; den meldet die CLI nicht, `aiRequest.effort` fehlt dann. Ausnahme Pi im `agentic`-Block: REASON läuft dort immer mit `--thinking` (ohne Effort aus der maxTurns-Heuristik bzw. `automation.host-pi.default-thinking-level`); die verwendete Stufe steht als `aiRequest.reasonThinking` am Schritt.

Tokens im Step-Metadata: `inputTokens` ist bei Claude nur der Teil der Eingabe, den der Prompt-Cache nicht bedient hat, bei einem Claude-Code-Aufruf oft einstellig. Der Rest steht in `cacheReadInputTokens` (aus dem Cache gelesen) und `cacheCreationInputTokens` (in den Cache geschrieben); die Insights-Karte des Laufs zeigt beides als „+… Cache“. Codex und Pi melden keine echten Zahlen (`estimated: true`), dort fehlen die Cache-Schlüssel. Das Budget `max_total_tokens` des `agentic`-Blocks zählt die Cache-Tokens nicht mit. `thinkingTokens` ist der Teil der `outputTokens`, den Claude mit erweitertem Denken verbraucht hat (aus `output_tokens_details`) — schon in `outputTokens` enthalten, die Insights-Karte zeigt ihn als „davon … Denken“. Die Workflow-Insights (`GET /api/v1/learning/workflows/{id}/execution-insights`) summieren Cache- und Thinking-Tokens wie die Run-Insights (`totalCacheReadInputTokens`, `totalCacheCreationInputTokens`, `totalThinkingTokens`).

### Provider und Modell pro Block

Nach demselben Muster wie `effort` können KI-fähige Blöcke (`simple`, `agentic`, `skill-capability` und der Inline-`command` von `retry`, `foreach`, `while`, `conditional`, `switch`) den Anbieter und das Modell nur für sich selbst festlegen — Config-Schlüssel `"provider"` und `"model"`. Auflösung: **Block > Workflow > System-Default** (`AiProviderRegistry.resolve()` bzw. Provider-Default-Modell). Ohne Block-Schlüssel verhalten sich bestehende Workflows unverändert.

```json
[
  { "type": "shell",  "config": { "command": "git diff --stat HEAD~1" } },
  { "type": "simple", "config": { "command": "Fasse den Diff zusammen:\n\n{{ previous_output }}", "model": "haiku" } },
  { "type": "simple", "config": { "command": "Bewerte die Architektur-Risiken ...", "provider": "PI", "model": "anthropic/opus", "effort": "high" } }
]
```

Regeln:
- Ein Block, der nur `provider` setzt, übernimmt das Workflow-Modell **nicht**, wenn der Anbieter vom Workflow-Anbieter abweicht — Modell-IDs sind anbieterspezifisch (`opus` vs. `anthropic/opus`). Bei gleichem Anbieter (oder ohne `provider`) gilt das Workflow-Modell weiter.
- Verschachtelte Blöcke (z. B. `blocks` in `switch`) tragen ihre eigenen Schlüssel; der Wrapper-Block vererbt seine Werte nicht an Kind-Blöcke.
- Der `integration`-Block nutzt `provider` für den Integrations-Provider und ist kein KI-Block — dort wirkt kein Override.
- Im Block-Editor der UI erscheinen die Felder „KI-Anbieter und Modell für diesen Block" nur bei KI-fähigen Block-Typen; die Modell-Vorschläge kommen vom Host-Katalog des gewählten Anbieters (`GET /api/v1/system/ai-providers/{id}/models`), Freitext bleibt erlaubt.
- Die tatsächlich verwendeten Werte stehen im Step-Metadata der Run-Details: `aiRequest.provider`, `aiRequest.model`, `aiRequest.effort` (für `agentic` zusätzlich top-level `provider`/`model`).
- Wrapper-Blöcke (`retry`, `foreach`, `while`, `conditional`, `switch`, `skill-capability`) übernehmen `aiRequest`, `provider` und `requestedModel` ihres **ersten inneren KI-Aufrufs** in ihren Sammel-Step (Anbieter und Modell sind pro Block konstant); die Run-Seite zeigt sie als Pills. Zusätzliche Schlüssel: `foreach` → `totalItems` + `items` (`{index, item, status, durationMs}`, mit `workflow_id` dazu `subRunId`; max. 500, Item-Label auf 120 Zeichen gekürzt), `while` → `iterationCount` + `iterations`, `retry` → `attempts` + `maxAttempts`, `conditional` → `conditionMet`, `switch` → `expression` + `matchedCase`. Reine Shell-Iterationen tragen kein `aiRequest`.

---

## 7. Ausführungsumgebung

### Host-Umgebung (Standard)

Shell-Blöcke ohne `container_image` laufen auf dem Host. Verfügbare Tools:

- `curl`, `git`, `python3`, `jq`, `nodejs`, `npm`, `wget`, `unzip`, `bash`

Alles andere (pip-Pakete, go, docker-cli, gh, trivy, spezialisierte Tools) ist **NICHT** verfügbar.

### Live-Output beim Beobachten eines Runs

Während ein Block läuft, erscheinen seine Ausgaben zeilenweise in den Live-Logs der Ausführungsseite: Shell- und
Container-Blöcke mit stdout/stderr (zusammengeführt), Claude-Blöcke als Tool-Aufrufe, gekürzter Text und
Abschluss-Zeile, Pi/Codex als Rohzeilen — auch für Blöcke innerhalb von `foreach`/`while`/`retry` (unter dem Index des
äußeren Steps). Hinweise:

- Fortschrittsbalken mit `\r` zeigen nur den jeweils letzten Stand; sehr lange Zeilen werden gekürzt.
- Programme, die ihre Ausgabe puffern (z. B. `python3` ohne `-u`), erscheinen erst beim Flush — für Live-Ausgabe
  `python3 -u` oder `PYTHONUNBUFFERED=1` verwenden.
- Der Step-Output (`{{ block.<name>.output }}`, Step-Karte) ist davon unberührt und enthält wie bisher die komplette Ausgabe.
- Nach dem Lauf bleibt der Mitschnitt (letzte 1000 Zeilen) gespeichert, solange der Workflow es nicht abschaltet:
  Seitenleiste → „Live-Mitschnitt speichern“ (Systemstandard / immer / nie); der Systemstandard steht unter
  Einstellungen → Ausführung. Er wird mit dem Lauf gelöscht.

### Container-Images

Für Shell-Blöcke die mehr Tools benötigen, `container_image` setzen:

| Image | Basis | Enthalten |
|---|---|---|
| `automation-agent-python:latest` | Python 3.12 | pandas, numpy, requests, beautifulsoup4, pip, git, curl, jq |
| `automation-agent-devops:latest` | Ubuntu 24.04 | git, curl, jq, python3, docker-cli, gh (GitHub CLI), trivy, gitleaks |
| `automation-agent-node:latest` | Node.js 22 | npm (volles Oekosystem), git, curl, jq |
| `automation-agent-java:latest` | Temurin 21 | maven, git, curl, jq, python3, LibreOffice (DOCX→PDF) |

### Regeln

- Shell-Blöcke OHNE `container_image` können NUR Host-Tools verwenden
- Braucht ein Befehl pip, go, docker, gh oder andere spezialisierte Tools -> `container_image` setzen
- Container mounten das Projektverzeichnis nach `/workspace`
- Auch `conditional`, `retry`, `foreach`, `while` und `switch` im Shell-Modus reichen `container_image`, `mount_docker_socket` und `mounts` an ihren Befehl durch. Die Bedingung selbst (`condition` bzw. `condition_command`) läuft auf dem Host
- `container_image` gilt nur für Shell-Befehle: KI-Aufrufe (Claude, Codex, Pi) laufen immer auf dem Host im Projektverzeichnis
- Der Server kann `container_image` per Allowlist einschränken (`automation.execution.allowed-container-images` /
  `AUTOMATION_ALLOWED_CONTAINER_IMAGES`). Leer (Default) erlaubt jedes Image. Ist ein Image nicht gelistet,
  scheitert der Block sichtbar mit Exit-Code 125 und einem SECURITY-Eintrag im Activity-Log, statt still
  auszuführen.

### Beispiel: Shell-Block mit Container

```json
{
  "type": "shell",
  "config": {
    "command": "gh pr list --state open --json number,title",
    "container_image": "automation-agent-devops:latest"
  }
}
```

### Beispiel: Shell-Block mit Docker-Socket

```json
{
  "type": "shell",
  "config": {
    "command": "docker build -t myapp:latest .",
    "container_image": "automation-agent-devops:latest",
    "mount_docker_socket": true
  }
}
```

### Werkzeug-Container (Messfühler)

Mit `container_args` statt `command` startet ein Shell-Block ein Image mit **dessen eigenem Einstiegsbefehl**:
`docker run … <image> <args…>`, ohne `/bin/bash -c`. So laufen auch Werkzeug-Images mit `ENTRYPOINT` oder
ohne bash (Alpine), etwa OWASP Dependency-Check, trivy oder gitleaks. Das Projekt liegt **nur lesbar** unter
`/workspace`; Image-Allowlist, Socket- und Mount-Politik, Abbruch und Timeout gelten wie bei `container_image`.

| Schlüssel | Bedeutung |
|---|---|
| `container_args` | Argumente als Liste. Braucht `container_image`, schließt `command` aus. Jedes Argument wird einzeln aufgelöst; nicht vertrauenswürdige Platzhalter (Webhook-Daten, belastete Ausgaben, `open_todos` & Co.) lehnt der Block ab. |
| `output_format` | `raw` (Standard: Ausgabe des Werkzeugs, Exit-Code entscheidet) oder `sarif`. |
| `output_dir` | Nur bei `sarif`: Pfad im Container (Standard `/out`), an dem die Plattform je Schritt einen leeren Ordner einbindet. Das Werkzeug muss seine SARIF-Datei dorthin schreiben. |
| `cache_dir` | Pfad im Container für einen dauerhaften Cache je Image (`<Datenordner>/tool-cache/<image>`), etwa für eine Schwachstellen-Datenbank. |
| `fact_prefix` | Präfix der Faktennamen, Standard: Block-Name. |

Mit `output_format: sarif` ist die Schrittausgabe ein Faktenblatt nach dem
[Audit-Protokoll](audit-workflow-protocol.md): `FAKT <prefix>_findings`, `_critical`, `_high`, `_medium`, `_low`,
`_suppressed`, `_exit_code` und je Befund eine Zeile `TREFFER <prefix>: <pfad>:<zeile> <SCHWERE> <regel> – <text>`
(höchstens 200). Die Schwere kommt aus `security-severity`, sonst aus dem SARIF-`level`. Code-Ausschnitte gibt der
Block nie aus. Lesbares SARIF heißt `COMPLETED`, auch bei Exit-Code ≠ 0; fehlt es, steht
`NICHT GEMESSEN: <prefix>_findings (…)` da und der Schritt ist `FAILED` (für Audits: `on_failure: continue`).
Ebenso nicht gemessen: ein Lauf ohne Ergebnisliste (`results` fehlt) und ein abgebrochener Container (Timeout,
Abbruch, Exit 125–127 oder 137), auch wenn schon eine SARIF-Datei daliegt. Das Werkzeug-Log darunter steht mit
`| ` vor jeder Zeile, damit keine Logzeile als `FAKT` gilt.

```json
{
  "name": "trivy",
  "type": "shell",
  "config": {
    "container_image": "automation-agent-devops:latest",
    "container_args": ["trivy", "fs", "/workspace", "--scanners", "vuln", "--cache-dir", "/cache",
                       "--format", "sarif", "--output", "/out/trivy.sarif"],
    "cache_dir": "/cache",
    "output_format": "sarif",
    "timeout_seconds": 900,
    "on_failure": "continue"
  }
}
```

Grenzen: kein Host-Netz (ein Scan der laufenden Instanz geht so nicht), keine Umgebungsvariablen, feste Grenzen
2 GB / 100 Prozesse, nur auf oberster Ebene (nicht in foreach/retry & Co.; Editor und Analyzer melden es dort), und
wie `container_image` nur dort, wo die Pfade des Backends Host-Pfade sind (Dev, Host-Installation). Fehlt der
Projektordner, startet der Container nicht, statt ein leeres Verzeichnis zu prüfen. Git-Werkzeuge sehen in einem
Git-Worktree keine Historie: `.git` ist dort eine Datei mit einem Pfad außerhalb des Containers, und manche
Werkzeuge (etwa `gitleaks git`) melden dann ohne Fehler 0 Befunde.

Welche Images es gibt, zeigt der Menüpunkt **Container** (`GET /api/v1/container-images`): je mitgeliefertem
Agent-Image die Werkzeuge mit festgeschriebener Version, ob der lokale Bau zu dieser Version passt (aktuell,
veraltet, ohne Kennung, fehlt), ob es erlaubt ist, und welche Workflows und Projekt-Container es nutzen; dazu jedes
andere Image, das ein Workflow nennt. Neu gebaut wird auf dem Host mit `scripts/build-agent-images.sh` – einen
Bau-Knopf gibt es bewusst nicht. Ein geändertes Dockerfile braucht die neue Prüfsumme in
`container-images/agent-images.json`, sonst wird `AgentImageCatalogGateTest` rot.

---

## 8. Agentic Block Deep Dive

### Die Goal-Reason-Act-Observe-Schleife

Jede Iteration:
1. **REASON**: Claude analysiert den aktuellen Stand, Gesprächsverlauf und entscheidet die nächste ACTION (nutzt `--system-prompt`, `--max-turns 3`)
2. **ACT**: Ein **separater** Claude-CLI-Aufruf führt die ACTION aus (nutzt `--append-system-prompt`, `--model`, Projektverzeichnis)
3. **OBSERVE**: Das Ergebnis wird aufgezeichnet und in die nächste REASON-Phase zurückgeführt

Endet die KI-CLI in der REASON-Phase mit einem Exit-Code ungleich 0 (Limit, fehlende Anmeldung, Absturz), scheitert der Block sofort: Die Ausgabe zeigt `[iteration N - REASON ERROR]` mit der Meldung der CLI. Als ACTION ausgeführt wird die Meldung nicht.

### Kritisch: ACT hat keinen Kontext aus REASON

Die ACT-Phase ist ein **frischer Claude-CLI-Aufruf**. Sie teilt keinen Gesprächskontext mit REASON. Das bedeutet:

**Jede ACTION muss eigenständig sein:**
- **Absolute Pfade** verwenden (nicht relativ, nicht "die Datei von vorhin")
- **Spezifische Namen** angeben (nicht "die Funktion die wir besprochen haben")
- **Exakte Anweisungen** geben (nicht "den Bug fixen" sondern "in `/pfad/zur/Datei.java` Zeile 42, `foo` zu `bar` ändern")

```
FALSCH: "Den Bug in der Service-Klasse fixen"
RICHTIG: "In /home/user/project/src/main/java/com/example/UserService.java
         die Methode getUserById() anpassen, um null-Eingaben zu behandeln
         indem am Anfang der Methode eine Null-Prüfung eingefügt wird."
```

### History-Komprimierung

Wenn die History mehr als 7 Einträge hat, werden mittlere Turns zusammengefasst — der erste Turn (initialer Prompt) und die letzten 4 Turns bleiben vollständig erhalten. Das bedeutet, frühere Beobachtungen können komprimiert werden. Ziele und Erfolgskriterien so gestalten, dass sie aus dem jüngsten Kontext auswertbar sind.

### Verschmutzung durch veraltete Dateien

Alte Dateien aus vorherigen Runs können den Kontext des Agentic-Blocks verschmutzen (z.B. Glob-Patterns die veraltete Reports aufgreifen). Einen Aufräum-Schritt vor Agentic-Blöcken einfügen:

```json
{
  "type": "shell",
  "config": {
    "command": "rm -rf output/ && mkdir -p output/"
  }
}
```

### Sicherheitslimits

Iterationen (`max_iterations`), Zeit je Iteration und insgesamt, Token-Budget (`max_total_tokens`), Abbruch nach wiederholten identischen Aktionen (`max_repeated_actions`) und eine Freigabe vor jeder Aktion (`require_approval`) – Standardwerte in der [Block-Referenz](block-referenz.md#agentic).

### Action-Komprimierung

Wenn eine ACTION 100.000 Zeichen überschreitet, wird sie via separatem Claude-CLI-Aufruf (60s Timeout) komprimiert bevor sie an die ACT-Phase gesendet wird. Scheitert dieser Aufruf (Timeout, Exit-Code ungleich 0), wird die ACTION stattdessen auf 100.000 Zeichen gekürzt.

### Token-Tracking

Token-Nutzung (Input + Output) wird über alle Iterationen getrackt. Wenn das Budget erschöpft ist, stoppt der Block. Wenn REASON oder ACT keine Nutzungsdaten liefern, wird ein WARN geloggt — das Budget wird nicht durchgesetzt.

---

## 9. Workflow-Instruktionen & Memory

### Zweistufiges Instruktionssystem

1. **Basis-Instruktionen** (`src/main/resources/agent-instructions.md`): Beim Start geladen, gelten für ALLE Workflows. Umfassen Arbeitsverzeichnis, Dateiausgabe, Fehlerbehandlung, Qualitätsstandards.

2. **Workflow-spezifische Instruktionen** (`workflow.instructions`-Feld): Pro Workflow, editierbar in der UI (Monaco-Editor). Erscheinen als Teil des System-Prompts.

### Wie Instruktionen injiziert werden

- **Block-Ausführung** (alle Block-Typen): Via `--append-system-prompt`-Flag an der Claude CLI
- **Agentic REASON-Phase**: Via `--system-prompt`-Flag, beinhaltet Workflow-Instruktionen im Abschnitt `## Workflow Instructions`

### Workflow Memory

Workflows können über Runs hinweg Wissen ansammeln. Memory kann **manuell** oder **automatisch** extrahiert werden.

#### Wie es funktioniert

1. **Extrahieren** (manuell oder automatisch): Nach einem oder mehreren Runs, Extraktion über UI ("Memory"-Tab auf der Workflow-Detailseite) oder API auslösen. Runs auswählen und Provider wählen (Claude oder Codex). Der `ConversationPreprocessor` verdichtet die Step-Outputs, bevor sie an die KI gehen; die KI erstellt daraus einen strukturierten Wissenseintrag (Titel, Markdown-Inhalt, Tags).
2. **Aktivieren** (manuell oder automatisch): Bei manueller Extraktion startet Memory als `active=false`. Bei automatischer Extraktion wird es direkt aktiviert. Nur eine Version kann gleichzeitig aktiv sein.
3. **Beim nächsten Run verwendet**: Vor jeder Workflow-Ausführung wird das aktive Memory nach `.claude/rules/project-memory.md` im Projektverzeichnis geschrieben. Die Claude CLI greift auf diese Datei automatisch via Rules-System zu; pi und Codex lesen `.claude/rules/` nicht und bekommen Memory, Skills und Projekt-Kontext deshalb als Anhang an den System-Prompt (`RunContextFiles`).
4. **Versionierung**: Mehrere Memory-Versionen pro Workflow werden aufbewahrt. Aeltere Versionen können durch Aktivierung wiederhergestellt werden.
5. **Bearbeiten**: Titel und Inhalt können in der UI (Monaco-Editor) manuell verfeinert werden.

#### Automatische Extraktion (Auto-Memory)

Konfigurierbar pro Workflow über `metadata.autoMemoryExtraction`:

```json
{
  "metadata": {
    "autoMemoryExtraction": {
      "enabled": true,
      "interval": 5
    }
  }
}
```

- **`enabled`**: Automatische Extraktion ein/aus
- **`interval`**: Nach wie vielen erfolgreichen Runs extrahiert wird (Default: 5)
- Nur bei **COMPLETED**-Runs (nicht FAILED, CANCELLED etc.)
- Zählt erfolgreiche Runs seit letzter Extraktion
- Neues Memory wird automatisch aktiviert (ersetzt das bisherige aktive Memory)
- Konfiguration auch über den Toggle "Auto-Memory" auf der Workflow-Detailseite

#### Wann verwenden

Memory extrahieren nachdem ein Workflow einen Run mit wertvollen Erkenntnissen produziert hat — Patterns, Fallstricke, Domain-Wissen — die zukünftige Runs leiten sollen. Besonders nützlich für Agentic-Workflows die projektspezifische Konventionen über die Zeit lernen. **Auto-Memory empfohlen** für Workflows die regelmäßig laufen und deren Erkenntnisse sich akkumulieren.

#### API

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/api/v1/workflows/{id}/memory` | Alle Memory-Versionen auflisten |
| POST | `/api/v1/workflows/{id}/memory/extract` | Aus Runs extrahieren (`runIds`, `provider`) |
| PUT | `/api/v1/workflows/{id}/memory/{mid}` | Titel/Inhalt aktualisieren |
| PUT | `/api/v1/workflows/{id}/memory/{mid}/activate` | Version aktivieren |
| DELETE | `/api/v1/workflows/{id}/memory/{mid}` | Version löschen |

```json
// POST /api/v1/workflows/{id}/memory/extract
{
  "runIds": ["run-id-1", "run-id-2"],  // optional — weglassen für letzte 10 Runs
  "provider": "CLAUDE"                  // oder "CODEX"
}
```

---

## 10. Chains

Chains gruppieren mehrere Workflows zur **sequentiellen Ausführung**.

### Kerneigenschaften

- **Projektübergreifend**: Eine Chain kann Workflows aus beliebigen Projekten referenzieren
- **Sequentiell**: Workflows laufen nacheinander (nicht parallel)
- **Kontextweitergabe**: Jeder Eintrag kann auf `{{ previous_chain_output }}` des letzten abgeschlossenen Workflows zugreifen
- **Kettenbezug**: Jeder Eintrag kennt seinen Chain-Run: `{{ chain_run_id }}` im Kontext, `chainRunId` am Lauf (`GET /api/v1/executions/{id}`). Beides setzt die Chain selbst, nach ihrem Eingabe-Kontext — ein mitgegebener `chain_run_id` überschreibt es nicht. Einzeln gestartet bleibt der Platzhalter wörtlich stehen (Analyzer-Regel `chain-only-placeholder`). `chainRunId` trägt nur der Lauf, den die Chain startet, auch nach einem Fortsetzen; ein Retry, ein Fortsetzen ohne abgeschlossenen Schritt (das startet einen neuen Lauf) und Sub-Workflows erben `{{ chain_run_id }}` mit dem Kontext, ihr `chainRunId` bleibt `null`
- **stopOnFailure**: Steuert ob die Chain bei Workflow-Fehler stoppt — stoppt nur wenn **beide** Flags gesetzt sind (Chain-Level UND Entry-Level müssen `true` sein)
- **Wartezeit je Eintrag**: `timeoutMinutes` (5–1440) am Eintrag begrenzt, wie lange die Kette auf diesen Workflow wartet; ohne Angabe gilt `chain-workflow-timeout-minutes` (Vorgabe 180). Nötig für Einträge mit Freigabe, die über Nacht wartet. Läuft die Frist ab, zählt der Eintrag als FAILED – der Workflow-Lauf selbst läuft weiter

### Chain-Struktur

```json
{
  "name": "Vollständige Audit + Fix Pipeline",
  "entries": [
    {
      "workflowId": "<audit-workflow-id>",
      "sortOrder": 0,
      "inputContext": { "scope": "backend" },
      "stopOnFailure": true
    },
    {
      "workflowId": "<fix-workflow-id>",
      "sortOrder": 1,
      "inputContext": { "scope": "fix" },
      "stopOnFailure": false,
      "timeoutMinutes": 600
    }
  ]
}
```

Werte in `inputContext` werden nicht aufgelöst – `{{ previous_chain_output }}` gehört in die Blöcke des Workflows selbst.

### API

```
GET    /api/v1/chains                      # Alle Chains auflisten
POST   /api/v1/chains                      # Chain erstellen
GET    /api/v1/chains/{id}                 # Chain abrufen
PUT    /api/v1/chains/{id}                 # Chain aktualisieren
DELETE /api/v1/chains/{id}                 # Chain löschen
POST   /api/v1/chains/{id}/execute         # Ausführung starten
GET    /api/v1/chains/runs                 # Alle Chain-Runs auflisten
GET    /api/v1/chains/{id}/runs            # Runs einer Chain auflisten
GET    /api/v1/chains/{id}/runs/{runId}    # Einzelnen Run abrufen
POST   /api/v1/chains/runs/{runId}/cancel  # Run abbrechen
```

### Limits

- Polling-basierte Fertigstellungsprüfung (2s Intervall)
- 180-Minuten-Timeout pro Workflow in der Chain (3 Stunden, konfigurierbar via `automation.chain.workflow-timeout-minutes`)

---

## Integration Block (Plugin-System)

Ruft Actions von verbundenen externen Diensten auf (Google Calendar, Trello, etc.). Teil des Plugin-Systems — jeder `IntegrationProvider` stellt Actions bereit, die in Workflows als `integration`-Block genutzt werden können.

**Voraussetzung:** Der Provider muss auf der Integrationen-Seite (`/integrations`) verbunden sein.

**Modul:** `integrations` (nur im Preset `full` an). Ist es aus, verweigert der Block, der Editor bietet den Typ nicht an, und die Vorlagen-Auswahl blendet Vorlagen mit diesem Block aus.

### Config

`provider` (Anbieter-ID wie `google-calendar` oder `trello`), `action` (etwa `list_events`, `create_card`), `params` (Platzhalter erlaubt) und optional `connection` – die benannte Verbindung, wenn mehrere Konten oder Dienste desselben Anbieters eingerichtet sind (Standard `default`). Alle Schlüssel: [Block-Referenz](block-referenz.md#integration).

### Beispiel: Nächste Google Calendar Events abrufen

```json
{
  "type": "integration",
  "config": {
    "provider": "google-calendar",
    "action": "list_events",
    "params": {
      "timeMin": "{{ started_at }}",
      "maxResults": 5
    }
  }
}
```

### Beispiel: Trello-Karte erstellen nach Workflow-Abschluss

```json
{
  "type": "integration",
  "config": {
    "provider": "trello",
    "action": "create_card",
    "params": {
      "idList": "abc123",
      "name": "Ergebnis: {{ workflow_name }}",
      "desc": "{{ previous_output }}"
    }
  }
}
```

### Verfügbare Actions

Alle Actions eines Providers sind auf der Integrationen-Seite sichtbar (Provider-Karte → "Test"-Button). Action-Namen und Parameter-Schemas werden dort angezeigt.

### Fehlerbehandlung

Wenn der Provider nicht verbunden ist oder die API fehlschlägt, wird der Block als `FAILED` markiert mit der Fehlermeldung im Output. Token-Refresh passiert automatisch.

### Neuen Provider als Workflow-Block nutzen

1. Provider auf der Integrationen-Seite verbinden (OAuth-Flow)
2. Integration-Block im Workflow-Editor hinzufügen
3. Provider und Action wählen, Parameter konfigurieren
4. Template-Variablen (`{{ previous_output }}`, `{{ started_at }}`) funktionieren in Params

---

## 11. Dos und Don'ts

### DO

- **`timeout_seconds` explizit setzen** für komplexe Blöcke — nicht auf Defaults verlassen
- **Opus für Analyse-/Audit-Workflows verwenden** — Sonnet halluziniert Findings
- **`set -euo pipefail`** am Anfang mehrzeiliger Shell-Befehle verwenden
- **Absolute Pfade** in Agentic-Block-Zielen und -Aktionen verwenden
- **Aufräum-Schritte** vor Agentic-Blöcken einfügen um Verschmutzung durch veraltete Dateien zu vermeiden
- **Agentic-Block-Ziele spezifisch halten** — "Erstelle index.html in output/" statt "Baue eine Webseite"
- **`{{ previous_output }}`** verwenden um Block-Ausgaben zu verketten — Daten fliessen vorwärts durch die Pipeline
- **Sowohl `timeout_seconds` als auch `iteration_timeout_seconds`** für Agentic-Blöcke setzen — das Auto-Alignment hilft, aber explizit ist besser
- **Verifikations-Block** nach Agentic-Blöcken einfügen (Dateien prüfen, Tests ausführen, Ausgabe validieren)

### DON'T

- **Sonnet nicht für Code-Analyse verwenden** — halluziniert falsche Positives (gefälschte SQL-Injection, gefälschte Security-Issues)
- **`iteration_timeout_seconds` nicht niedriger als `timeout_seconds` setzen** — die CLI wird erfolgreich sein, aber die Iteration wird als TIMEOUT markiert
- **Nicht annehmen, dass ACT Kontext aus REASON hat** — jede Aktion ist ein frischer CLI-Aufruf
- **Keine relativen Pfade in Agentic-Aktionen** — die ACT-Phase teilt möglicherweise nicht denselben Working-Directory-Kontext
- **Opus-Workflows nicht parallel ausführen** wenn `max-concurrent-calls` niedrig ist (z.B. 3) — Workflows blockieren sich gegenseitig
- **`container_image` nicht für Simple-Blöcke verwenden** — Simple-Blöcke nutzen immer die Host-Claude-CLI
- **Nicht vergessen, `model` explizit zu setzen** — null nutzt den System-Standard der möglicherweise nicht gewünscht ist
- **Shell-Blöcke nicht für KI-Reasoning verwenden** — stattdessen Simple-Blöcke nutzen (Shell-Blöcke führen nur Befehle aus)
- **Nicht mehr als 5-7 Blöcke** ohne guten Grund verketten — jeder Block fügt Latenz und Kosten hinzu
- **Den Verifikationsschritt** nach Agentic-Blöcken nicht überspringen — Vertrauen ist gut, Kontrolle ist besser

### Anti-Patterns

| Anti-Pattern | Problem | Lösung |
|---|---|---|
| Agentic-Block mit vagem Ziel | Agent dreht sich ohne Fortschritt | Ziel konkret mit Dateipfaden und Erfolgskriterien formulieren |
| 100+ Wörter pro Agentic-Aktion | ACT-Phase läuft in Timeout oder wird verwirrt | In kleinere, spezifische Aktionen aufteilen |
| Kein Aufräum vor Agentic-Block | Veraltete Dateien aus vorherigen Runs verschmutzen Kontext | `rm -rf output/`-Shell-Block davor einfügen |
| `timeout_seconds: 900` mit Standard-`iteration_timeout_seconds` | Iteration läuft bei 300s in Timeout obwohl CLI erfolgreich | Beide Timeouts setzen oder Auto-Alignment nutzen |

---

## Anhang: Template-Kurzreferenz

Was in den öffentlichen Workflow-Pool gehört, wie er sich von den eingebauten Templates abgrenzt und welche Kriterien das Hygiene-Gate durchsetzt, steht in [workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

Alle eingebauten Templates mit ihrem primären Pattern. Die SaaS-Factory-Workflows (`saas-*`) liegen im Pool (Scope `external`), weil sie ein Werkzeug außerhalb des Repositories brauchen:

| Template | Pattern | Kategorie |
|---|---|---|
| `file-improve` | Sequentielle Simple-Kette | CONTENT |
| `code-refactor` | Sequentielle Simple-Kette | CODE |
| `translation-pipeline` | Sequentielle Simple-Kette | CONTENT |
| `fact-check-pipeline` | Sequentielle Simple-Kette + Conditional | RESEARCH |
| `project-overview` | Sequentielle Simple-Kette | CODE |
| `iterative-refine` | Simple + While-Schleife | CONTENT |
| `document-review` | Skill-Capability-Kette | CONTENT |
| `research-assistant` | Skill-Capability-Kette | RESEARCH |
| `data-analysis` | Skill-Capability-Kette | DATA |
| `deploy-pipeline` | Conditional + Shell | CODE |
| `code-quality` | Shell + Retry | CODE |
| `git-to-changelog` | Shell sammeln + KI analysieren | CODE |
| `log-triage` | Shell sammeln + KI analysieren | CODE |
| `git-statistics-report` | Shell sammeln + KI analysieren | CODE |
| `doc-to-website` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `idea-to-prototype` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `cv-to-portfolio` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `csv-to-dashboard` | Analysieren + Agentic + Verifizieren | DATA |
| `codebase-to-onboarding` | Analysieren + Agentic + Verifizieren | CODE |
| `notes-to-presentation` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `api-to-sdk` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `markdown-to-ebook` | Analysieren + Agentic + Verifizieren | TRANSFORM |
| `bug-to-fix` | Analysieren + Agentic + Verifizieren | CODE |
| `dependency-risk-radar` | Shell scannen + ForEach | CODE |
| `api-consistency-audit` | Audit-Pipeline | CODE |
| `dead-code-detector` | Audit-Pipeline | CODE |
| `doc-accuracy-check` | Audit-Pipeline | CODE |
| `codebase-health-scorecard` | Audit-Pipeline | CODE |
| `architecture-debt-radar` | Audit-Pipeline | CODE |
| `test-coverage-gaps` | Audit-Pipeline | CODE |
| `audit-fix-pr-pipeline` | Mehrstufig + Freigabe | CODE |
| `auto-fix-java` | Mehrstufig + Freigabe | CODE |
| `auto-fix-python` | Mehrstufig + Freigabe | CODE |
| `auto-fix-typescript` | Mehrstufig + Freigabe | CODE |
| `folder-backup` | Shell-Kette | UTILITY |
| `github-project-bootstrap` | Shell-Kette | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `log-rotate-cleanup` | Shell sammeln + KI analysieren | INFRA |
| `pool-neutrality-audit` | Audit-Pipeline | QUALITY |
| `pr-review-summary` | Shell sammeln + KI analysieren | TEAM |
| `research-enhance` | Sequentielle Simple-Kette | RESEARCH |
| `secret-scanner` | Shell + Conditional | CODE |
| `service-health-monitor` | Shell scannen + ForEach + KI | INFRA |
| `sprint-retrospective` | Shell sammeln + KI analysieren | TEAM |
| `ssl-cert-check` | ForEach + KI analysieren | INFRA |
| `standup-from-git` | Shell sammeln + KI analysieren | TEAM |
| `container-vuln-scan` | Shell + Conditional | CODE |
| `database-backup-verify` | Shell-Kette | CODE |
| `directory-sync` | Shell-Kette | UTILITY |
| `docker-image-build-push` | Shell + Conditional | CODE |
| `test-matrix` | Shell scannen + ForEach + Skill | CODE |
| `todo-creator` | Agentic | UTILITY |
| `topic-deep-dive` | Sequentielle Simple-Kette | RESEARCH |

### Zweisprachige Vorlagen

Name und Beschreibung einer Vorlage sind immer `{"de": …, "en": …}`. Eine Vorlage oder Pool-Datei mit dem Marker `"i18n": ["de", "en"]` darf dieselbe Form auch **in den Schritten** verwenden, also in `blocks` und `instructions`. Beim Anlegen wählt die Plattform eine Sprache: die UI-Sprache (Rückfall Englisch, dann Deutsch). Der neue Workflow ist danach einsprachig und gehört dir.

```json
{
  "i18n": ["de", "en"],
  "name": {"de": "Beispiel: Freigabe mit Messung", "en": "Example: approval with a measurement"},
  "blocks": [
    {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
    {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
     "config": {"question": {"de": "{{ block.messen.output }} – weiter?", "en": "{{ block.measure.output }} – continue?"},
                "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}], "on_no": "fail"}}
  ]
}
```

**Regeln** (das Gate `LocalizedDefinitionGateTest` prüft sie):

- **Jede Map trägt genau die deklarierten Sprachen, keine bleibt leer.** Ohne Marker wird nichts aufgelöst.
- **Einsprachig bleiben Shell-Felder** (`command`, `condition` …, sonst hieße es zwei Befehle prüfen) **und alles, was verglichen oder ausgewertet wird:** `type`, `variable_name`, `auto_answer`, `reject_options`, `mode`, `on_no`, `execution_mode`, `model`, `provider` sowie `metadata`.
- **Schritt-Namen sind übersetzbar, aber pro Sprache konsistent.** `{{ block.X.output }}` und compress-`source` zeigen in jeder Fassung auf einen vorhandenen Namen. Der Report-Block eines Audits (`metadata.audit.reportBlock`) bleibt einsprachig. Ein Block, auf den ein **Shell-Feld** verweist, bekommt einen sprachneutralen Namen (z. B. `data`), denn das Shell-Feld selbst bleibt einsprachig.
- **Übersetzte Approval-`options` dürfen nirgends verglichen werden.** Die Antwortvariable darf in keinem Shell-, conditional-, switch- oder while-Feld vorkommen, denn auf Deutsch wäre die Antwort `Nein` statt `No`. Die Ablehnung erkennt der Approval-Block selbst (`reject_options`, Vorgabe `No`/`Nein`).
- **Bestehende Datenbanken erhalten geänderte eingebaute Vorlagen nur über einen Versionssprung** (`version`), wie jede Änderung an einer eingebauten Vorlage.

Die API liefert die Schritte in der gewünschten Sprache: `GET /api/v1/workflow-templates?locale=de`. Pool und Ketten installieren mit `"locale"` im Body.

### Blaupausen

Neben **Vorlagen** (fertige Aufgaben) und dem **Pool** gibt es **Blaupausen**: leere Gerüste mit lauffähigen Platzhaltern, die zeigen, wie sich Block-Typen verknüpfen lassen – Formular → Verzweigung, Schleife mit Freigabe, Plan B bei Fehlern. Im Anlege-Dialog sind sie der erste Reiter (vor Vorlagen und Pool), gruppiert nach *Grundverknüpfungen*, *Kombinationen*, *Rund um Workflows* und *Mit KI*; ganz oben steht der leere Workflow. Man startet eine Blaupause, sieht im Lauf, wie die Schritte zusammenspielen, und ersetzt dann die Platzhalter durch eigene Befehle.

- **Ablage:** `src/main/resources/templates/blueprints/*.json`, gleiches Format und gleicher Loader wie Vorlagen, `kind: BLUEPRINT` (Spalte `workflow_templates.kind`). Die API liefert beide Arten gemeinsam mit dem Feld `kind`.
- **Immer zweisprachig** (`"i18n": ["de","en"]`), `id` mit Präfix `blueprint-`, Kategorie `BASICS`, `COMBINATIONS`, `AROUND` oder `AI`.
- **Platzhalter:** Shell-Schritte tun etwas Harmloses und tragen einen Kommentar `# placeholder - …`. Arbeitsdateien liegen in `/tmp/mysuricates-blueprints/<Run-ID>`, der letzte Schritt räumt sie weg.
- **0 Tokens außerhalb von `AI`:** keine KI-Block-Typen; `foreach`, `while`, `conditional` und `retry` brauchen `"execution_mode": "shell"`, sonst fragen sie die KI. Das prüft `BlueprintGateTest`.
- **Pakete (sub-workflow):** Ein `sub-workflow`-Block einer Blaupause nennt die aufgerufene Blaupause statt einer Workflow-ID (`"workflow_id": "blueprint-sub-workflow-part"`). Beim Anlegen legt der Server beide im selben Projekt an und trägt die neue ID ein (`POST /api/v1/workflow-templates/{id}/install`, wie die Ketten-Installation). Der Verweis steht nur auf oberster Ebene und nennt eine Blaupause, die selbst keine weitere aufruft; außerhalb von `AI` ist auch der Teil ohne KI – beides prüft `BlueprintGateTest`. Nur in einer Blaupause zählt ein solcher Aufruf deshalb nicht als KI (`ModelUse.needsAiAsBlueprint`, KI-Badge nur bei ausgelieferter Teil-Blaupause). Ein Workflow, der noch `blueprint-…` als `workflow_id` trägt, wird beim Anlegen und Ändern mit 400 `UNWIRED_BLUEPRINT_REFERENCE` abgewiesen.
- **Rund um Workflows** (`AROUND`, 0 Tokens): je eine Blaupause für das, was außerhalb der Blöcke eingerichtet wird; die Anleitung steht kurz in ihrer Beschreibung und im Erklärtext der Schritte. *Per Zeitplan starten* (CRON, `maxRuns`, Konfliktverhalten `SKIP`), *Per Webhook starten (mit Signatur)* (Body-Felder als Variablen, `X-Signature`), *Von einem TODO-Trigger gestartet* (`todo_*`-Variablen, GREEN schließt per `autoClose`), *Glied einer Kette* (`{{ previous_chain_output }}`; die Kettenvorlage `blueprint-chain` „Kette aus zwei Blaupausen“ reiht *Ampel* und dieses Glied), *Workflow-Memory nutzen*, *Integration abfragen* (Open-Meteo, ohne Zugangsdaten) und *Isoliert im Klon laufen* (`workspace.mode: isolated`). Die Hinweise auf den Seiten Zeitpläne, Webhooks und Integrationen verlinken die passende Blaupause (`/workflows?new=1&template=<id>` öffnet den Anlegedialog mit ihr).
- **Mit KI** (`AI`): außer *Sammeln → KI fasst zusammen* auch *Modell pro Schritt* (`provider`/`model`/`effort` am Block), *KI verdichtet eine lange Ausgabe* (`compress-ai`), *Skill einbinden* (`skill-capability` mit `skillIds`) und *Agent mit Freigabe (teuer)* – eine Freigabe vor dem Start, dann `agentic` mit `require_approval` und kleinem Budget (3 Runden, 40 000 Tokens).
- **Erklärtext je Schritt:** `config.description` (zweisprachig) sagt, was der Schritt im Muster tut und was man ersetzt. Der Editor zeigt ihn unter dem Block, die Run-Ansicht unter dem Step-Kopf (`metadata.blockDescription`). Das gilt für jeden Workflow, denn `description` ist ein gemeinsamer Schlüssel ohne Wirkung auf die Ausführung. Ausgenommen sind `todo-create` und `todo-update`, dort ist `description` der Text des TODOs.

---

## Automatisch fortsetzen nach Neustart oder Standby

Ein Backend-Neustart oder ein System-Standby unterbricht laufende Runs; sie stehen danach auf `INTERRUPTED`. Ohne Opt-in geht es nur per Knopf „Resume from Step“ weiter. Mit dem Opt-in setzt die Plattform den Run selbst fort (TODO 90d10ba1):

```json
{ "metadata": { "autoResume": { "enabled": true } } }
```

In der Oberfläche: Workflow-Detailseite → „Nach Neustart automatisch fortsetzen“. Der Schlüssel gehört zur Definition — er ändert den Definitions-Hash (eine neue Version), ist bei einem versiegelten Workflow gesperrt, und ein Workflow ohne ihn behält seinen bisherigen Hash.

**Was fortgesetzt wird** — nur Runs, die in diesem Lebenslauf des Backends vom Neustart-Cleanup oder vom Standby-Watchdog auf `INTERRUPTED` gesetzt wurden **und vorher `RUNNING` waren**. Nicht fortgesetzt werden: pausierte Runs (Pause setzt eine Person), Ketten-Einträge und Sub-Workflows (ihr Eltern-Run entscheidet — setzt er fort, läuft der Sub-Workflow-Block neu), Runs gelöschter Workflows und ältere `INTERRUPTED`-Runs. Jeder Run bekommt **höchstens einen** automatischen Versuch (`autoResumeAttempts` in den Run-Metadaten, vor dem Start gezählt); wird er erneut unterbrochen, bleibt er für eine Person liegen.

**Nebenwirkungen** — Fortgesetzt wird hinter dem letzten fertigen Block; **der unterbrochene Block läuft von vorn**. Blöcke mit Wirkung nach außen (Dateien anhängen, Nachrichten senden, deployen) müssen eine Wiederholung vertragen. Wartete der Run auf eine Freigabe (`approval`) oder Eingabe (`human-input`), war dieser Block nicht fertig: Die Frage wird neu gestellt und der Run wartet wieder — die Regeln für unbeaufsichtigte Runs beantworten sie nicht (ein `auto_answer` des Blocks greift wie immer). Ohne fertigen Block startet — wie beim Knopf — ein neuer Run desselben Workflows. Dieser neue Run übernimmt den Zähler (`autoResumeAttempts`, Herkunft in `autoResumedFrom`) und wird bei einer weiteren Unterbrechung nicht noch einmal automatisch fortgesetzt; der alte Run bleibt `INTERRUPTED` und nennt den neuen in `autoRestartedAs`.

**Unbeaufsichtigt** — Der Run trägt danach `trigger_source = AUTO_RESUME`. Mit `automation.hardening.require-sealed-for-unattended` braucht der Workflow ein Siegel; sonst lehnt `RunAdmission` ab, der Run bleibt `INTERRUPTED`, im Activity-Log steht ein `SECURITY`-Eintrag, und es gibt keinen zweiten Versuch.

**Global** — Einstellungen → Ausführung: „Unterbrochene Runs automatisch fortsetzen“ (`run-auto-resume-enabled`, Default an) und „Wartezeit vor jeder Fortsetzung“ (`run-auto-resume-delay-seconds`, Default 30 s, 0–3600). Die Runs werden nacheinander mit dieser Wartezeit fortgesetzt, auch vor dem ersten. Startwerte: `automation.execution.auto-resume.enabled` / `.delay-seconds`.

**Zusammen mit einem Zeitplan** — Hat der Workflow einen Zeitplan mit Konfliktverhalten `SKIP` (Standard), gewinnt die Fortsetzung: Solange eine automatische Fortsetzung für den Workflow ansteht oder läuft, entfällt ein fälliger Termin — auch ein Nachhol-Run (`catchUpOnResume`) nach Neustart oder Standby. Das Activity-Log vermerkt „Schedule skipped (interrupted run is being resumed automatically)“ mit `reason: auto-resume-pending`. Mit `OVERLAP` startet der Termin wie gewohnt daneben (TODO 070ccc3d).

## Auto-Repair

Auto-Repair ist der Self-Healing-Mechanismus der Plattform: Wenn ein Workflow fehlschlägt, analysiert die KI den Fehler, korrigiert die Workflow-Blocks und verifiziert die Reparatur automatisch.

### Wie es funktioniert

1. **Erkennung** — Ein `WorkflowRunCompletedEvent` mit Status `FAILED` wird erkannt
2. **Analyse** — Die KI (`WorkflowTextGenerator`) analysiert den Fehler und die aktuellen Blocks
3. **Fix** — Korrigierte Blocks werden als JSON generiert und im Workflow gespeichert
4. **Verifikation** — Der reparierte Workflow wird erneut ausgeführt (Default: bis zu 2 Versuche, konfigurierbar 1–5)
5. **Loop-Schutz** — Repair-Runs werden mit `autoRepairAttempt=true` markiert, um Endlosschleifen zu verhindern

### Repair-Modi

| Modus | Verhalten |
|-------|-----------|
| `auto` (Standard) | Bevorzugt Test-Instanz, fällt auf lokale Ausführung zurück |
| `test` | Nur über Test-Instanz — überspringt wenn Test nicht verfügbar |
| `local` | Immer lokale Ausführung auf der gleichen Instanz |

### Konfiguration

**Global** (Settings → General → Auto-Repair):
- Toggle: Auto-Repair aktivieren/deaktivieren
- Dropdown: Repair-Modus (Auto/Test/Lokal)

**Per Workflow** (Workflow-Metadata):
```json
{
  "metadata": {
    "autoRepair": {
      "enabled": true,
      "mode": "auto",
      "maxRetries": 3,
      "timeoutSeconds": 900
    }
  }
}
```

| Feld | Default | Bereich | Beschreibung |
|------|---------|---------|-------------|
| `enabled` | false | — | Auto-Repair aktivieren |
| `mode` | `"auto"` | `auto`/`test`/`local` | Repair-Modus |
| `maxRetries` | 2 | 1–5 | Maximale Reparaturversuche |
| `timeoutSeconds` | 600 (10 Min) | 60–3600 | Timeout pro Reparaturversuch |

Priorität: Workflow-Metadata > Globales Setting > Default (`auto`, deaktiviert).

### API-Endpoints

| Aktion | Methode | Pfad |
|--------|---------|------|
| Einzelnen Workflow reparieren | POST | `/api/v1/workflows/{id}/repair` |
| Alle fehlgeschlagenen reparieren | POST | `/api/v1/system/repair-failed?force=false` |
| Globalen Repair-Modus setzen | PUT | `/api/v1/system/settings/auto-repair-mode` — body: `{"value": "auto"}` |
| Max-Retries setzen | PUT | `/api/v1/system/settings/auto-repair-max-retries` — body: `{"value": "3"}` |
| Timeout setzen | PUT | `/api/v1/system/settings/auto-repair-timeout-seconds` — body: `{"value": "900"}` |

### Hinweise

- Im Modus `auto` oder `local` braucht die Test-Instanz **nicht** zu laufen
- Default 2 Reparaturversuche pro Fehlschlag (konfigurierbar 1–5 via Metadata oder Global Setting `auto-repair-max-retries`)
- Default 600s Timeout pro Versuch (konfigurierbar 60–3600 via Metadata oder Global Setting `auto-repair-timeout-seconds`)
- Repair-Runs erscheinen im ActivityLog
- Die KI-Reparatur nutzt das konfigurierte `WorkflowTextGenerator`-Interface (Claude oder Codex)

---

## TodoTrigger — Self-Improvement-Loop

TodoTrigger schließen den Self-Improvement-Loop: Wenn ein Audit-Workflow ein Problem findet und ein TODO erstellt, kann ein TodoTrigger automatisch einen Fix-Workflow starten, der das Problem behebt.

### Ablauf

```
Audit-Workflow → AutoTodoService → TODO (OPEN)
                                     ↓ TodoCreatedEvent
                             TodoTriggerService → Fix-Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

### Trigger einrichten

**POST** `/api/v1/todo-triggers`

```json
{
  "tagPattern": "backend,bug",
  "targetWorkflowId": "<fix-workflow-id>",
  "description": "Backend-Bugs automatisch fixen",
  "maxAttempts": 3,
  "autoClose": true,
  "enabled": true,
  "processBacklog": true,
  "contextTemplate": {}
}
```

**Tag-Matching**: Komma-separiert mit AND-Semantik. Alle Tags im Pattern müssen im TODO vorhanden sein. Beispiel: `"backend,bug"` matcht ein TODO mit Tags `["backend", "bug", "security"]`, aber nicht `["backend", "feature"]`.

**Weitere Felder**:

| Feld | Default | Beschreibung |
|------|---------|-------------|
| `enabled` | `true` | Trigger aktiv/inaktiv |
| `processBacklog` | `true` | Bestehende offene TODOs nachträglich verarbeiten (Polling alle 5 Min, max 1 pro Tick) |
| `contextTemplate` | `{}` | Statische Key-Value-Paare, die zusätzlich zum TODO-Kontext an den Fix-Workflow übergeben werden |

### Optionale Filter

| Feld | Beschreibung | Beispiel |
|------|-------------|----------|
| `projectId` | Nur TODOs aus diesem Projekt | `"ebf4f50c-..."` |
| `sourceFilter` | Nur TODOs aus dieser Quelle | `"WORKFLOW"` |
| `minPriority` | Mindest-Priorität (HIGH, MEDIUM, LOW) | `"HIGH"` |

### Kontext-Variablen im Fix-Workflow

Der Fix-Workflow erhält automatisch diese Variablen:

| Variable | Inhalt |
|----------|--------|
| `todo_id` | UUID des TODOs |
| `todo_title` | Titel |
| `todo_description` | Beschreibung (Markdown) |
| `todo_tags` | Tags als JSON-String (z.B. `["backend","bug"]`) |
| `todo_priority` | HIGH / MEDIUM / LOW |
| `todo_project_id` | Projekt-UUID |
| `todo_source` | USER / AI / WORKFLOW |
| `todo_reference_type` | z.B. `WORKFLOW_RUN` |
| `todo_reference_id` | Run-UUID |
| `todo_trigger_id` | Trigger-UUID |

Zusätzlich werden alle Einträge aus `contextTemplate` des Triggers als weitere Variablen übergeben.

### API

| Methode | Pfad | Beschreibung |
|---------|------|-------------|
| GET | `/api/v1/todo-triggers` | Alle Trigger auflisten |
| GET | `/api/v1/todo-triggers/{id}` | Einzelnen Trigger abrufen |
| GET | `/api/v1/todo-triggers/workflow/{workflowId}` | Trigger nach Ziel-Workflow |
| POST | `/api/v1/todo-triggers` | Trigger erstellen |
| POST | `/api/v1/todo-triggers/{id}/enable` | Trigger aktivieren |
| POST | `/api/v1/todo-triggers/{id}/disable` | Trigger deaktivieren |
| DELETE | `/api/v1/todo-triggers/{id}` | Trigger löschen |

### Loop-Prevention (3 Schichten)

1. **Metadata-Flag**: Ein vom TodoTrigger gestarteter Fix-Run trägt `todoTriggerRun` in den Run-Metadaten; für solche Runs erzeugt `AutoTodoService` kein TODO — das kappt die Rekursion TODO → Run → TODO
2. **Max-Attempts**: `todo.fixAttempts` zählt die Versuche; nach `maxAttempts` (Standard 3) wird zu diesem TODO nicht erneut getriggert
3. **Running-Check**: Solange der letzte Fix-Run (`todo.lastFixRunId`) noch läuft, startet kein zweiter

Zusätzlich, aber nicht Teil der Schleifenbremse: das Pausenfenster `automation.todo-trigger.skip-until`, ein Kandidat pro Poll-Tick sowie die Source-, Tag-, Projekt- und Prioritätsfilter des Triggers (`sourceFilter: "WORKFLOW"` etwa schließt manuell erstellte TODOs aus).

### Auto-Close

Bei `autoClose: true` (Standard): Wenn der Fix-Workflow mit **GREEN** Gate-Status abschließt, wird das TODO automatisch auf DONE gesetzt. Bei YELLOW oder RED bleibt es offen.

### Best Practices

- **Tag-Patterns spezifisch halten** — `"backend,security,bug"` statt nur `"bug"`, sonst triggert der Fix-Workflow bei jedem Bug
- **Fix-Workflows idempotent designen** — sie könnten mehrfach laufen
- **Gate-Status im Fix-Workflow setzen** — damit Auto-Close korrekt funktioniert
- **maxAttempts begrenzen** — Standard 3 ist sinnvoll, höhere Werte riskieren Endlos-Schleifen bei systematischen Fehlern
