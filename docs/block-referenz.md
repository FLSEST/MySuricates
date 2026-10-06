<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->

# Block-Referenz

> **Scope:** Alle Config-Schlüssel jedes Block-Typs, ab „Gemeinsame Felder“ erzeugt aus
> `ai-automation-frontend/src/data/blockConfigSchema.json` (`python3 scripts/docs-reference.py --write`,
> siehe [Entwicklung → Erzeugte Referenzen](entwicklung.md#erzeugte-referenzen)). Aus derselben Datei
> nimmt der Block-Editor seine Vorschläge und der Analyzer seine Prüfung. Wozu ein Typ taugt, ein Beispiel
> und die Fallen stehen im [Workflow-Guide, Kapitel 2](WORKFLOW-GUIDE.md#2-block-typ-referenz).

Ein Block ist `{ "type": …, "name": …, "config": { … } }`; die Tabellen beschreiben die Schlüssel in
`config`. **Pflicht** „ja“ heißt: muss gesetzt sein; „einer von“: genau einer dieser Schlüssel, ein leerer
Text gilt als nicht gesetzt. **Werte** nennt die erlaubten Werte mit ihrer Bedeutung, dazu Untergrenzen
(„≥ 1“). „Gelesen nur mit …“ heißt: Der Executor liest den Schlüssel nur, wenn die genannten anderen
Schlüssel so gesetzt sind. Unbekannte Schlüssel ignoriert das Backend; Editor und Analyzer melden sie.

## Gemeinsame Felder

Gelten für jeden Block-Typ.

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `on_failure` | string |  | `stop` | `stop`: Workflow beenden, der Run wird FAILED (Standard). · `continue`: Mit dem nächsten Block weitermachen. Der Block selbst bleibt FAILED. | Was passiert, wenn dieser Block fehlschlägt (Status FAILED). Jeder andere Wert als `continue` stoppt wie `stop`. |
| `label` | string |  |  |  | Anzeigename des Schritts, nur zur Beschreibung. Bei der Ausführung ohne Wirkung. |
| `description` | string |  |  |  | Notiz zum Schritt, nur zur Beschreibung. Bei der Ausführung ohne Wirkung. |
| `run_if` | string |  |  |  | Shell-Test vor dem Block (nur oberste Ebene): Exit 0 → der Block läuft, sonst wird er übersprungen (SKIPPED, ohne KI-Aufruf). Läuft im Arbeitsverzeichnis des Laufs, höchstens 60 s; Platzhalter wie in jedem Shell-Feld. |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter der `run_if`-Bedingung POSIX-sicher in Single-Quotes setzen. Bei Blöcken ohne eigene Shell wirkt der Schlüssel nur zusammen mit `run_if`. Gelesen nur mit `run_if`. |
| `workspace` | object |  |  |  | Arbeitsverzeichnis des Runs — wirkt nur am **ersten** Block. `{"mode": "isolated"}` lässt den Run in einem eigenen Git-Klon laufen (weitere Schlüssel: `source`, `source_path`, `branch`, `install_npm`, `additional_sources`). |
| `validation` | object |  |  |  | Prüft die Ausgabe des erfolgreich gelaufenen Blocks: `contains` (Teilstring), `min_length`, `max_length`. Mit `retry_on_fail: true` läuft der Block bei einem Verstoß bis zu `max_retries`-mal erneut (Standard 1), sonst wird der Verstoß nur an die Ausgabe gehängt. |

## KI-Felder

Gelten zusätzlich für `simple`, `agentic`, `skill-capability`, `retry`, `foreach`, `while`, `conditional`, `switch`, `compress-ai`.

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `provider` | string |  |  | `CLAUDE`: Claude CLI · `PI`: pi CLI (Modell als `<backend>/<model>`) · `CODEX`: Codex CLI (nur lesend) · Groß-/Kleinschreibung egal | KI-Anbieter nur für diesen Block. Vorrang: Block > Workflow > System-Standard. |
| `model` | string |  |  |  | Modell nur für diesen Block: Alias `opus`/`sonnet`/`haiku`/`fable`, eine konkrete ID oder bei pi `<backend>/<model>`. Setzt der Block einen anderen `provider` als der Workflow, wird das Workflow-Modell nicht übernommen. |
| `effort` | string |  |  | `low`: Wenig Denkaufwand, schnell und günstig. · `medium`: Mittlerer Denkaufwand. · `high`: Hoher Denkaufwand. · `xhigh`: Sehr hoher Denkaufwand, am teuersten. · Groß-/Kleinschreibung egal | Denkaufwand nur für diesen Block. Ohne Angabe gilt der Workflow- bzw. Anbieter-Standard. Codex ignoriert ihn. |

## `simple`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `command` | string | ja |  |  | Der Prompt, den die KI bekommt. Platzhalter wie `{{ previous_output }}` werden aufgelöst. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in Sekunden für den KI-Aufruf. Ohne Angabe gilt `automation.host-claude.timeout-seconds`. |

## `shell`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `command` | string | ja |  |  | Shell-Befehl(e). Tipp: mit `set -euo pipefail` beginnen. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in Sekunden (Standard 300). |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben. |
| `container_image` | string |  |  |  | Docker-Image, in dem der Befehl läuft. Ohne Angabe läuft er auf dem Host. |
| `mount_docker_socket` | boolean |  | `false` |  | Docker-Socket in den Container mounten (nur mit `container_image`). |
| `mounts` | string[] |  |  |  | Zusätzliche Volumes `host:container[:ro\|rw]` (nur mit `container_image`). |
| `checked_command` | boolean |  | `false` |  | Leitet aus dem Exit-Code ein Gate ab (`gate_status`) und stellt `set -e -o pipefail` voran. |
| `check_category` | string |  | `build-check` |  | Gate-Kategorie für `checked_command`, falls das Skript selbst keine `gate_category` ausgibt. |

## `agentic`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `goal` | string | ja |  |  | Was der Agent erreichen soll. |
| `success_criteria` | string |  |  |  | Woran der Agent erkennt, dass er fertig ist. |
| `max_iterations` | integer |  | `10` | ≥ 1 | Harte Obergrenze für Reason/Act-Runden (Standard 10). |
| `iteration_timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout pro Runde in Sekunden (Standard 300). |
| `total_timeout_seconds` | integer |  | `1800` | ≥ 1 | Timeout für den ganzen Block in Sekunden (Standard 1800). |
| `max_total_tokens` | integer |  | `200000` | ≥ 1 | Token-Budget über alle Runden: ungecachte Eingabe + Ausgabe, Cache-Tokens zählen nicht (Standard 200 000). |
| `max_repeated_actions` | integer |  | `3` | ≥ 1 | Abbruch, wenn dieselbe Aktion so oft hintereinander kommt. |
| `require_approval` | boolean |  | `false` |  | Vor jeder Aktion eine Freigabe einholen. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in Sekunden für den KI-Aufruf. Ohne Angabe gilt `automation.host-claude.timeout-seconds`. |

## `retry`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `command` | string | ja |  |  | Befehl, der bei Fehler wiederholt wird (Prompt oder Shell, je nach `execution_mode`). |
| `max_retries` | integer |  | `3` | ≥ 0 | Maximale Zahl der Wiederholungen. |
| `delay` | integer |  | `5` | ≥ 0 | Pause zwischen den Versuchen in Sekunden. |
| `exponential_backoff` | boolean |  | `false` |  | Pause bei jedem Versuch verdoppeln. |
| `execution_mode` | string |  | `ai` | `ai`: Als KI-Prompt über den Anbieter aus `provider` (Standard). · `shell`: Als Shell-Befehl. Kein KI-Aufruf, keine Tokens. · alt, noch angenommen: `claude` | Wie Bedingung und Befehl laufen: als KI-Prompt oder als Shell-Befehl. |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in Sekunden (Standard 300). |
| `container_image` | string |  |  |  | Docker-Image, in dem der Befehl im Shell-Modus (`execution_mode: shell`) läuft. Ohne Angabe läuft er auf dem Host. Gelesen nur mit `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Docker-Socket in den Container mounten (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Zusätzliche Volumes `host:container[:ro\|rw]` (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |

## `conditional`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `condition` | string | ja |  |  | Bedingung. Shell: Exit-Code 0 = wahr. KI: Antwort TRUE/FALSE. |
| `then_command` | string |  |  |  | Befehl, wenn die Bedingung wahr ist. |
| `else_command` | string |  |  |  | Befehl, wenn die Bedingung falsch ist. |
| `execution_mode` | string |  | `ai` | `ai`: Als KI-Prompt über den Anbieter aus `provider` (Standard). · `shell`: Als Shell-Befehl. Kein KI-Aufruf, keine Tokens. · alt, noch angenommen: `claude` | Wie Bedingung und Befehl laufen: als KI-Prompt oder als Shell-Befehl. |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in Sekunden (Standard 300). |
| `container_image` | string |  |  |  | Docker-Image, in dem der Befehl im Shell-Modus (`execution_mode: shell`) läuft. Ohne Angabe läuft er auf dem Host. Gelesen nur mit `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Docker-Socket in den Container mounten (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Zusätzliche Volumes `host:container[:ro\|rw]` (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |

## `switch`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `expression` | string | ja |  |  | Ausdruck, dessen Wert mit den Cases verglichen wird (`${var}` oder `{{ var }}`). |
| `cases` | array | ja |  |  | Fälle mit `value` (Alternativen mit `\|`, z. B. `"YELLOW\|RED"`) und `blocks`. |
| `default` | array |  |  |  | Blöcke, wenn kein Case passt. |

## `foreach`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `command` | string | einer von `command`, `workflow_id` |  |  | Befehl pro Item. Das Item steht als `{{ <variable_name> }}` bereit, sein Index als `{{ <variable_name>_index }}`, bei einem JSON-Objekt seine Felder als `{{ <variable_name>.<feld> }}`. Entweder `command` oder `workflow_id`. |
| `workflow_id` | string | einer von `command`, `workflow_id` |  |  | Statt `command`: je Item ein Lauf dieses Workflows, wie beim sub-workflow-Block (eigener Lauf je Item, max. 5 Ebenen, kein Zirkel). Der Kind-Lauf bekommt die Variablen dieses Laufs samt Item. Ein Platzhalter wie `{{ feature_workflow_id }}` ist erlaubt, aber nur aus vertrauenswürdigen Kontextvariablen. |
| `context` | object |  |  |  | Nur mit `workflow_id`: zusätzliche Variablen für den Kind-Lauf je Item, Name → Vorlage, z. B. `{"feature_id": "{{ feature.id }}"}`. Gelesen nur mit `workflow_id`. |
| `variable_name` | string |  | `item` |  | Name der Schleifenvariable (Standard `item`). |
| `items_source` | string |  | `list` | `list`: Aus `items` (Standard). · `command`: Aus der Ausgabe von `items_command`, ein Item pro Zeile. | Woher die Items kommen. |
| `items_command` | string |  |  |  | Shell-Befehl, der die Items ausgibt (eine Zeile = ein Item). Läuft immer als Shell. |
| `items` | array |  |  |  | Feste Liste von Items. |
| `execution_mode` | string |  | `ai` | `ai`: Als KI-Prompt über den Anbieter aus `provider` (Standard). · `shell`: Als Shell-Befehl. Kein KI-Aufruf, keine Tokens. · alt, noch angenommen: `claude` | Wie Bedingung und Befehl laufen: als KI-Prompt oder als Shell-Befehl. |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in Sekunden (Standard 300). |
| `continue_on_error` | boolean |  | `false` |  | Fehlgeschlagene Items lassen den Block nicht scheitern, er endet COMPLETED. |
| `max_consecutive_failures` | integer |  | `3` | ≥ 0 | Nach so vielen fehlgeschlagenen Items in Folge hört die Schleife auf, die übrigen laufen nicht mehr und der Block scheitert, auch mit `continue_on_error` (Standard 3, 0 = aus). |
| `container_image` | string |  |  |  | Docker-Image, in dem der Befehl im Shell-Modus (`execution_mode: shell`) läuft. Ohne Angabe läuft er auf dem Host. Gelesen nur mit `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Docker-Socket in den Container mounten (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Zusätzliche Volumes `host:container[:ro\|rw]` (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |

## `while`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `command` | string | ja |  |  | Befehl pro Durchlauf. |
| `condition_command` | string |  |  |  | Bedingung vor jedem Durchlauf. Shell: Exit 0 = weiter. KI: "false"/"0"/leer = stopp. |
| `max_iterations` | integer |  | `100` | ≥ 1 | Maximale Zahl der Durchläufe. |
| `execution_mode` | string |  | `ai` | `ai`: Als KI-Prompt über den Anbieter aus `provider` (Standard). · `shell`: Als Shell-Befehl. Kein KI-Aufruf, keine Tokens. · alt, noch angenommen: `claude` | Wie Bedingung und Befehl laufen: als KI-Prompt oder als Shell-Befehl. |
| `shell_escape_variables` | boolean |  | `false` |  | Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in Sekunden (Standard 300). |
| `container_image` | string |  |  |  | Docker-Image, in dem der Befehl im Shell-Modus (`execution_mode: shell`) läuft. Ohne Angabe läuft er auf dem Host. Gelesen nur mit `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Docker-Socket in den Container mounten (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Zusätzliche Volumes `host:container[:ro\|rw]` (nur im Shell-Modus mit `container_image`). Gelesen nur mit `execution_mode: shell`. |

## `approval`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `question` | string |  | `Approval required` |  | Freigabe-Frage (Markdown, Platzhalter werden aufgelöst). |
| `options` | string[] |  | `["Yes", "Yes always", "No"]` |  | Erlaubte Antworten. |
| `reject_options` | string[] |  | `["No", "Nein"]` |  | Antworten, die als Ablehnung gelten und `on_no` auslösen. |
| `variable_name` | string |  | `approval_answer` |  | Kontext-Variable, in die das Ergebnis geschrieben wird (Standard `approval_answer`). Danach als `{{ name }}` nutzbar. |
| `timeout_minutes` | integer |  | `30` | ≥ 1 | Wartezeit in Minuten. |
| `mode` | string |  | `ask` | `ask`: Wartet auf eine Antwort im UI (Standard). · `auto`: Antwortet sofort mit `auto_answer`. | Interaktiv fragen oder automatisch antworten. |
| `auto_answer` | string \| null |  |  |  | Antwort im Modus `auto`. |
| `on_no` | string |  | `continue` | `continue`: Weiter, Block COMPLETED (Standard). · `fail`: Block FAILED. · `skip`: Block SKIPPED. | Verhalten bei Ablehnung (siehe `reject_options`). |

## `human-input`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `prompt` | string |  | `Please provide input:` |  | Frage an den Benutzer. |
| `input_type` | string |  | `text` | `text`: Freitext (Standard). · `textarea`: Mehrzeiliger Text. · `number`: Zahlenfeld. · `select`: Auswahl aus `options`. · `checkbox`: Kontrollkästchen, Antwort `"true"` oder `"false"`. · `date`: Datum, Antwort als `JJJJ-MM-TT`. | Art der Eingabe. |
| `variable_name` | string |  | `user_input` |  | Kontext-Variable, in die das Ergebnis geschrieben wird (Standard `user_input`). Danach als `{{ name }}` nutzbar. |
| `options` | string[] |  |  |  | Auswahlmöglichkeiten für `select`. |
| `default_value` | string |  |  |  | Standardwert, u. a. bei Timeout. |
| `required` | boolean |  | `true` |  | Eingabe erforderlich. |
| `validation_pattern` | string |  |  |  | Regex, gegen den die Eingabe geprüft wird. |
| `timeout_minutes` | integer |  | `30` | ≥ 1 | Wartezeit in Minuten. |
| `use_default_on_timeout` | boolean |  | `true` |  | Bei Timeout `default_value` nehmen statt zu scheitern. |
| `fields` | array |  |  |  | Formular mit mehreren Feldern (`variable_name`, `label`, `input_type`, `required`, …). |

## `sub-workflow`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `workflow_id` | string | ja |  |  | ID des Workflows, der als Schritt läuft (max. 5 Ebenen tief). Ein Platzhalter wie `{{ child_workflow_id }}` ist erlaubt, aber nur aus vertrauenswürdigen Kontextvariablen – nicht aus Webhook-Daten oder Blockausgaben. |
| `workflowId` | string |  |  |  | Alte Schreibweise von `workflow_id`: wird noch gelesen, fällt später weg. Stattdessen `workflow_id` verwenden. |

## `skill-capability`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `capability` | string | ja |  |  | Name der Skill-Fähigkeit, z. B. `review_code`. Ihr Skill muss in den `skillIds` des Blocks stehen (neben `config`), Workflow-Skills zählen hier nicht. |
| `input` | string |  |  |  | Eingabe für die Fähigkeit, z. B. `{{ previous_output }}`. |
| `params` | object |  |  |  | Zusätzliche Parameter. |
| `allow_writes` | boolean |  | `false` |  | Schreibzugriff aufs Repo erlauben. Pflicht für Fähigkeiten mit `writes: true`. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in Sekunden für den KI-Aufruf. Ohne Angabe gilt `automation.host-claude.timeout-seconds`. |

## `integration`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `provider` | string | ja |  |  | Integrations-Anbieter, z. B. `google-calendar` (nicht der KI-Anbieter). |
| `action` | string | ja |  |  | Aktion des Anbieters. |
| `params` | object |  |  |  | Parameter der Aktion. |
| `connection` | string |  | `default` |  | Benannte Verbindung des Anbieters, z. B. `arbeit` — wenn mehrere Konten oder Dienste desselben Anbieters eingerichtet sind (Seite Integrationen). Ohne Angabe gilt `default`. |

## `document`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `file_path` | string | ja |  |  | Datei (PDF, DOCX, XLSX, HTML, TXT, …), absolut oder relativ zum Projekt. |
| `variable_name` | string |  |  |  | Kontext-Variable, in die das Ergebnis geschrieben wird. Danach als `{{ name }}` nutzbar. |
| `fail_on_empty` | boolean |  | `false` |  | Scheitern, wenn weniger als `min_characters` Text herauskommt. |
| `min_characters` | integer |  | `1` | ≥ 0 | Schwelle für `fail_on_empty`. |

## `todo-create`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `title` | string | ja |  |  | Titel des TODOs. |
| `description` | string |  |  |  | Beschreibung (Markdown). |
| `priority` | string |  | `MEDIUM` | `CRITICAL`: Kritisch · `HIGH`: Hoch, blockierend · `MEDIUM`: Mittel (Standard) · `LOW`: Niedrig · Groß-/Kleinschreibung egal | Priorität. |
| `tags` | array \| string |  |  |  | Tags als Liste oder kommagetrennt. |
| `project_id` | string |  |  |  | Ziel-Projekt. Standard: Projekt des Workflows. |
| `source` | string |  | `WORKFLOW` | `WORKFLOW`: Workflow (Standard) · `AI`: KI · `USER`: Benutzer · Groß-/Kleinschreibung egal | Herkunft des TODOs. |
| `source_agent` | string |  | `workflow` |  | Bezeichnung des erzeugenden Agenten. |
| `variable_name` | string |  | `todo_id` |  | Kontext-Variable, in die das Ergebnis geschrieben wird (Standard `todo_id`). Danach als `{{ name }}` nutzbar. |

## `todo-update`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `id` | string | einer von `id`, `updates` |  |  | ID des TODOs, z. B. `{{ todo_id }}`. Entweder `id` oder `updates`. |
| `title` | string |  |  |  | Neuer Titel (nur gesetzte, nicht leere Felder werden geändert). |
| `description` | string |  |  |  | Neue Beschreibung. |
| `priority` | string |  |  | `CRITICAL`: Kritisch · `HIGH`: Hoch · `MEDIUM`: Mittel · `LOW`: Niedrig · Groß-/Kleinschreibung egal | Neue Priorität. |
| `tags` | array \| string |  |  |  | Neue Tags. |
| `updates` | array \| string | einer von `id`, `updates` |  |  | Viele TODOs des Lauf-Projekts auf einmal: JSON-Liste, meist `{{ block.<shell>.output }}` (ist die ganze Ausgabe kein JSON, gilt ihre letzte Zeile). Je Eintrag `id`, optional `status` (OPEN/IN_PROGRESS/DONE/BACKLOG), `add_tags`, `remove_tags`, `note`, `require_tags`, `require_status`. Höchstens 50; ein unpassender Eintrag wird mit Grund übersprungen. |

## `todo-close`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `id` | string | ja |  |  | ID des TODOs, das auf DONE gesetzt wird, z. B. `{{ todo_id }}`. |

## `compress`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `source` | string | ja |  |  | Name eines früheren Blocks oder `"previous"`. |
| `stages` | array | ja |  |  | Stufen, die der Reihe nach laufen. JSON-Stufen vor Textstufen. |
| `max_chars` | integer |  | `20000` | ≥ 1 | Harte Obergrenze inkl. Kopfzeilen. |
| `timeout_seconds` | integer |  | `30` | ≥ 1 | Timeout in Sekunden (Standard 30). |

### Stufen

| Stufe | Wirkung |
|---|---|
| `text.normalize` | ANSI, CR, Leerraum am Zeilenende und Leerzeilen-Folgen entfernen. |
| `text.truncate` | Kürzen an Zeilengrenzen: `max_chars`, `keep` (`head`/`tail`/`head_tail`). |
| `lines.filter` | Zeilen behalten/entfernen: `include`, `exclude` (Regex-Listen), `before`, `after`. |
| `lines.replace` | Regex-Ersetzung je Zeile: `pattern`, `replacement`. |
| `lines.dedupe` | Wiederholungen entfernen: `count`, `consecutive_only`. |
| `lines.head` | Erste `lines` Zeilen. |
| `lines.tail` | Letzte `lines` Zeilen. |
| `markdown.section` | Nur passende Abschnitte: `headings` (Regex), `level`. |
| `json.select` | Nur diese Felder: `paths`. |
| `json.limit` | Array kürzen: `path`, `count`. |
| `json.sort` | Array sortieren: `path`, `by`, `order`. |
| `json.to_lines` | Ein Eintrag pro Zeile: `path`, `template` (`{title} — {url}`). |

## `compress-ai`

Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `source` | string | ja |  |  | Name eines früheren Blocks oder `"previous"`. |
| `instruction` | string |  |  |  | Anweisung zur Verdichtung (Platzhalter werden **nicht** aufgelöst). |
| `max_chars` | integer |  | `4000` | ≥ 500 | Zielgröße, mindestens 500. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in Sekunden für den KI-Aufruf. Ohne Angabe gilt `automation.host-claude.timeout-seconds`. |

## `dashboard-report`

Dazu die [gemeinsamen Felder](#gemeinsame-felder).

| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |
|---|---|---|---|---|---|
| `status` | string | ja |  | `GREEN`: Grün · `YELLOW`: Gelb · `RED`: Rot | Ampel des Runs (Platzhalter erlaubt, z. B. `{{ block.<name>.output }}` eines Blocks, der GREEN, YELLOW oder RED ausgibt). |
| `title` | string |  |  |  | Titel, höchstens 80 Zeichen. |
| `summary` | string |  |  |  | Zusammenfassung, höchstens 500 Zeichen. |
| `kpis` | array |  |  |  | Bis zu 6 Kennzahlen `{ label, value, trend? }`. |
| `items` | string[] |  |  |  | Bis zu 10 Einträge, je höchstens 200 Zeichen. |
