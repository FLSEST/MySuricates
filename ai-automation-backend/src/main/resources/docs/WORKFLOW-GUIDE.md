> *Zuletzt aktualisiert: [auto-generated]*

# Workflow Building Guide

Praktischer Leitfaden zum Erstellen effektiver Workflows in MySuricates. Umfasst alle Block-Typen, bewaehrte Patterns, Timeout-Konfiguration und haeufige Fallstricke.

**Zielgruppe**: KI-Agenten (Claude, Codex) und Entwickler, die Workflows erstellen oder debuggen.

---

## 1. Schnellstart

Ein Workflow ist eine Sequenz von **Bloecken**, die nacheinander ausgefuehrt werden. Jeder Block hat einen `type` und eine `config`-Map. Die Ausgabe jedes Blocks steht dem naechsten via `{{ previous_output }}` zur Verfuegung.

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

Alle Workflow-CRUD- und Ausfuehrungsoperationen liegen unter `/api/v1/workflows`. Ein Run wird gestartet mit `POST /api/v1/workflows/{id}/execute`.

### Kontext-Variablen

Variablen beim Start uebergeben:

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

MySuricates unterstuetzt 14 Block-Typen. Jeder Block hat einen `type` und eine `config`-Map.

### Allgemeine Block-Eigenschaften

Jeder Block unterstuetzt neben seinen typspezifischen Config-Feldern auch diese allgemeinen Eigenschaften:

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `on_failure` | string | `"stop"` | Verhalten bei Fehler: `"stop"` (Workflow beenden) oder `"continue"` (mit naechstem Block fortfahren) |

Zusaetzlich unterstuetzt jeder Block ein optionales Top-Level-Feld (nicht in `config`):

| Feld | Typ | Default | Beschreibung |
|---|---|---|---|
| `skillIds` | string[] | `[]` | Skill-IDs die **zusaetzlich** zu den Workflow-Skills fuer diesen Block aktiviert werden |

**Standard-Verhalten**: Wenn ein Block fehlschlaegt (Status `FAILED`), stoppt der Workflow und der Run wird als `FAILED` markiert. Mit `"on_failure": "continue"` faehrt der Workflow mit dem naechsten Block fort.

**Per-Block Skills**: Bloecke koennen ueber `skillIds` eigene Skills mitbringen, die additiv zu den Workflow-Level-Skills gelten. Der `WorkflowExecutor` merged beide Listen vor der Ausfuehrung und stellt den Baseline-Zustand nach dem Block wieder her. Beispiel:

```json
{
  "type": "simple",
  "config": { "command": "Analysiere den Code auf Sicherheitsluecken" },
  "skillIds": ["builtin-code-review", "builtin-security-analyzer"]
}
```

---

### `simple` — KI-Textverarbeitung

Sendet den Prompt an die Claude CLI auf dem Host. Keine Shell-Ausfuehrung.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Der Prompt, der an Claude gesendet wird |
| `timeout_seconds` | int | null | Claude-CLI-Timeout (wenn null, gilt der globale `automation.host-claude.timeout-seconds`-Wert) |

```json
{
  "type": "simple",
  "config": {
    "command": "Analysiere den folgenden Code und liste Verbesserungsvorschlaege auf:\n\n{{ previous_output }}"
  }
}
```

**Wann verwenden**: Textanalyse, Zusammenfassung, Code-Review, Content-Generierung — jede Aufgabe, die KI-Reasoning ohne Shell-Zugriff benoetigt.

---

### `shell` — Shell-Befehl-Ausfuehrung

Fuehrt einen Shell-Befehl auf dem Host oder innerhalb eines Docker-Containers aus.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Shell-Befehl(e) zur Ausfuehrung |
| `container_image` | string | null | Docker-Image (siehe Ausfuehrungsumgebung) |
| `timeout_seconds` | int | 300 | Ausfuehrungs-Timeout |
| `shell_escape_variables` | bool | false | Template-Variablen POSIX-sicher escapen (Single-Quotes) |
| `mount_docker_socket` | bool | false | Docker-Socket in den Container mounten (nur wirksam wenn `container_image` gesetzt) |
| `mounts` | list | `[]` | Zusaetzliche Docker-Volume-Mounts im Format `host:container[:ro\|rw]` (nur wirksam wenn `container_image` gesetzt) |

```json
{
  "type": "shell",
  "config": {
    "command": "set -euo pipefail\ngit log --oneline -20"
  }
}
```

**Wann verwenden**: Skripte ausfuehren, Daten sammeln, Build-Befehle, Dateioperationen — alles was deterministische Shell-Ausfuehrung benoetigt.

---

### `agentic` — Zielgesteuerter KI-Agent

Fuehrt eine autonome Goal-Reason-Act-Observe-Schleife aus. Der maechtigste, aber auch teuerste Block-Typ.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `goal` | string | **erforderlich** | Was der Agent erreichen soll |
| `success_criteria` | string | `""` | Woran Erfolg gemessen wird |
| `max_iterations` | int | 10 | Harte Iterationsobergrenze |
| `iteration_timeout_seconds` | int | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | int | 1800 | Gesamt-Block-Timeout (30 Min) |
| `max_total_tokens` | int | 200000 | Token-Budget (Input+Output ueber alle Iterationen) |
| `max_repeated_actions` | int | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | bool | false | Freigabe-Gate vor jeder Aktion |
| `timeout_seconds` | int | null | Claude-CLI-Timeout pro Aufruf (REASON + ACT) |

```json
{
  "type": "agentic",
  "config": {
    "goal": "Erstelle eine Portfolio-Webseite in portfolio/ basierend auf {{ filepath }}.",
    "success_criteria": "index.html existiert und enthaelt alle Sektionen.",
    "max_iterations": 8,
    "timeout_seconds": 600,
    "max_total_tokens": 300000
  }
}
```

**Wann verwenden**: Komplexe mehrstufige Aufgaben, die Urteilsvermoegen erfordern — Dateien erstellen, Code reparieren, Projekte bauen. Siehe [Agentic Block Deep Dive](#8-agentic-block-deep-dive) fuer Details.

---

### `retry` — Wiederholung bei Fehler

Wiederholt einen Befehl bis zu N Mal mit einer Pause zwischen den Versuchen.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Auszufuehrender Befehl |
| `max_retries` | int | 3 | Maximale Wiederholungsversuche |
| `delay` | int | 5 | Pause zwischen Versuchen (Sekunden) |
| `exponential_backoff` | bool | false | Pause bei jedem Versuch verdoppeln |
| `execution_mode` | string | `"claude"` | `"claude"` (KI) oder `"shell"` |
| `timeout_seconds` | int | 300 | Ausfuehrungs-Timeout pro Versuch |

```json
{
  "type": "retry",
  "config": {
    "command": "npm test",
    "max_retries": 3,
    "delay": 5,
    "execution_mode": "shell"
  }
}
```

**Exponential Backoff**: Wenn `exponential_backoff: true`, wird die Wartezeit bei jedem Versuch verdoppelt: 1. Versuch = `delay` Sekunden, 2. Versuch = `delay * 2`, 3. Versuch = `delay * 4`, usw.

**Wann verwenden**: Instabile Tests, netzwerkabhaengige Operationen, API-Aufrufe die voruebergehend fehlschlagen koennen.

---

### `conditional` — If/Then/Else

Wertet eine Bedingung aus und fuehrt einen von zwei Zweigen aus.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `condition` | string | **erforderlich** | Auszuwertende Bedingung |
| `then_command` | string | null | Befehl wenn Bedingung wahr (wenn null, wird nichts ausgefuehrt) |
| `else_command` | string | null | Befehl wenn Bedingung falsch |
| `execution_mode` | string | `"claude"` | `"claude"` (KI) oder `"shell"` |
| `timeout_seconds` | int | 300 | Ausfuehrungs-Timeout |

```json
{
  "type": "conditional",
  "config": {
    "condition": "test -f package.json",
    "then_command": "npm test",
    "else_command": "echo 'Keine Tests'",
    "execution_mode": "shell"
  }
}
```

**Bedingungsauswertung**: Im `shell`-Modus gilt Exit-Code 0 = wahr, ungleich 0 = falsch. Im `claude`-Modus beantwortet die KI die Bedingung mit "TRUE" oder "FALSE".

**Dual-Modus**: `execution_mode` steuert sowohl die Bedingungsauswertung als auch die Ausfuehrung von `then_command`/`else_command`. `timeout_seconds` gilt fuer beide.

**Wann verwenden**: Verzweigungslogik basierend auf Dateiexistenz, vorheriger Ausgabe, Umgebungspruefungen.

---

### `switch` — Mehrweg-Verzweigung

Wertet einen Ausdruck aus und leitet die Ausfuehrung zum passenden Case weiter. Wie eine switch/case-Anweisung.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `expression` | string | **erforderlich** | Auszuwertender Ausdruck (unterstuetzt `${var}` und `{{ var }}`) |
| `cases` | list | **erforderlich** | Liste von Case-Objekten mit `value` und `blocks` |
| `default` | list | null | Bloecke bei keinem Treffer |

Jeder Case hat:
- `value`: String zum Abgleich (Pipe-getrennte Alternativen: `"error|critical"`)
- `blocks`: Liste von Block-Definitionen bei Treffer

```json
{
  "type": "switch",
  "config": {
    "expression": "${gate_status}",
    "cases": [
      {
        "value": "GREEN",
        "blocks": [{ "type": "shell", "config": { "command": "echo 'Alles gut'" } }]
      },
      {
        "value": "YELLOW|RED",
        "blocks": [{ "type": "shell", "config": { "command": "echo 'Probleme gefunden'" } }]
      }
    ],
    "default": [
      { "type": "shell", "config": { "command": "echo 'Unbekannter Status'" } }
    ]
  }
}
```

**Wann verwenden**: Mehrweg-Verzweigung wenn `conditional` (binaeres if/else) nicht ausreicht. Routing basierend auf Status-Codes, Umgebungsnamen oder Gate-Ergebnissen.

---

### `foreach` — Ueber Items iterieren

Fuehrt einen Befehl fuer jedes Item in einer Liste aus.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `variable_name` | string | `"item"` | Name der Schleifenvariable |
| `items_source` | string | `"list"` | `"command"` (Shell-Ausgabe) oder `"list"` (inline) |
| `items_command` | string | — | Shell-Befehl, ein Item pro Zeile (nur bei `items_source: "command"`) |
| `items` | list | — | Inline-Liste von Items (nur bei `items_source: "list"`) |
| `command` | string | **erforderlich** | Befehl pro Item |
| `execution_mode` | string | `"claude"` | `"claude"` (KI) oder `"shell"` |
| `timeout_seconds` | int | 300 | Timeout pro Iteration |

```json
{
  "type": "foreach",
  "config": {
    "variable_name": "repo",
    "items_source": "command",
    "items_command": "find . -maxdepth 2 -name package.json | xargs -n1 dirname | sort -u",
    "execution_mode": "shell",
    "timeout_seconds": 600,
    "command": "cd '{{ repo }}' && npm outdated --json || true"
  }
}
```

**Hinweis**: `items_command` wird immer als Shell-Befehl ausgefuehrt (unabhaengig von `execution_mode`), da es nur die Iterations-Ziele sammelt.

**Wann verwenden**: Verarbeitung mehrerer Dateien, Repos oder Items mit derselben Operation.

---

### `while` — Schleife bis Bedingung

Wiederholt einen Befehl bis eine Bedingung erfuellt ist oder die maximale Iterationszahl erreicht wird.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `condition_command` | string | null | Bedingung, die jede Iteration ausgewertet wird. Ohne `condition_command` laeuft die Schleife bis `max_iterations` oder Kommando-Fehler |
| `command` | string | **erforderlich** | Befehl pro Iteration |
| `max_iterations` | int | 100 | Maximale Schleifendurchlaeufe |
| `execution_mode` | string | `"claude"` | `"claude"` (KI) oder `"shell"` |
| `timeout_seconds` | int | 300 | Ausfuehrungs-Timeout pro Iteration |

```json
{
  "type": "while",
  "config": {
    "condition_command": "Bewerte die Qualitaet auf einer Skala von 1-10. Antworte NUR mit 'true' wenn unter 8, oder 'false' wenn 8 oder hoeher.",
    "command": "Verbessere den Text. Gib die vollstaendig ueberarbeitete Version aus.",
    "max_iterations": 3
  }
}
```

**Bedingungsauswertung**: Im `shell`-Modus gilt Exit-Code 0 = weitermachen, ungleich 0 = stopp. Im `claude`-Modus gilt "false", "0" oder leere Antwort = stopp, alles andere = weitermachen.

**Dual-Modus**: `execution_mode` steuert sowohl die Bedingungsauswertung als auch die Befehlsausfuehrung. `timeout_seconds` gilt fuer beide.

**Wann verwenden**: Iterative Verfeinerung, Polling bis eine Bedingung erfuellt ist.

---

### `approval` — Menschliche Freigabe

Pausiert die Ausfuehrung und wartet auf menschliche Freigabe via WebSocket.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `question` | string | `"Approval required"` | Freigabe-Frage fuer den Benutzer |
| `options` | list | `["Yes", "Yes always", "No"]` | Verfuegbare Antwortmoeglichkeiten |
| `variable_name` | string | `"approval_answer"` | Kontext-Variable fuer die Antwort |
| `timeout_minutes` | int | 30 | Wartezeit |
| `mode` | string | `"ask"` | `"ask"` (interaktiv) oder `"auto"` (nutzt `auto_answer`) |
| `auto_answer` | string | — | Automatische Antwort wenn `mode` = `"auto"` |
| `on_no` | string | `"continue"` | Verhalten bei "No": `"continue"` (weiter, Status COMPLETED), `"fail"` (FAILED), oder `"skip"` (SKIPPED) |

```json
{
  "type": "approval",
  "config": {
    "question": "## Ergebnis\n\n{{ previous_output }}\n\nFortfahren?",
    "options": ["Yes", "No", "Abort"],
    "variable_name": "pr_decision",
    "timeout_minutes": 60,
    "on_no": "fail"
  }
}
```

**"Yes always"**: Wenn der Benutzer "Yes always" waehlt, wird die Antwort fuer diese Frage fuer den Rest des Runs gemerkt — nachfolgende Approval-Bloecke mit derselben Frage werden automatisch beantwortet.

**Auto-Modus**: Mit `"mode": "auto"` und `"auto_answer": "Yes"` wird der Block sofort ohne Benutzerinteraktion beantwortet. Nuetzlich fuer CI/CD-Pipelines.

**Wann verwenden**: Vor destruktiven Operationen (Push, Deploy, Loeschen), Quality Gates.

---

### `human-input` — Benutzereingabe sammeln

Pausiert die Ausfuehrung um Freitext-Eingaben vom Benutzer zu sammeln.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `prompt` | string | `"Please provide input:"` | Frage an den Benutzer |
| `input_type` | string | `"text"` | `"text"` oder `"select"` |
| `variable_name` | string | `"user_input"` | Kontext-Variable fuer die Eingabe |
| `options` | list | `[]` | Auswahlmoeglichkeiten (fuer `select`-Typ) |
| `default_value` | string | `""` | Standardwert bei Timeout |
| `required` | bool | true | Ob Eingabe erforderlich ist |
| `validation_pattern` | string | — | Regex-Pattern zur Validierung |
| `timeout_minutes` | int | 30 | Wartezeit |
| `use_default_on_timeout` | bool | **true** | Bei Timeout den Standardwert verwenden statt fehlzuschlagen |
| `fields` | list | `[]` | Multi-Field-Formular: jeder Eintrag hat `variable_name`, `label`, `input_type`, `required`, `default_value`, `options`, `placeholder`, `description`, `validation_pattern` |

```json
{
  "type": "human-input",
  "config": {
    "prompt": "Welche Sprache soll die Dokumentation haben?",
    "input_type": "select",
    "variable_name": "doc_language",
    "options": ["Deutsch", "English", "Francais"],
    "default_value": "Deutsch",
    "timeout_minutes": 15,
    "use_default_on_timeout": true
  }
}
```

**Timeout-Verhalten**: Wenn `use_default_on_timeout` = true (Standard) und ein `default_value` gesetzt ist, wird bei Timeout der Standardwert verwendet und der Block endet mit COMPLETED. Wenn false oder kein Standardwert gesetzt, endet der Block mit FAILED.

**Wann verwenden**: Parameter zur Laufzeit abfragen, interaktive Workflows.

---

### `sub-workflow` — Verschachtelter Workflow

Fuehrt einen anderen Workflow als verschachtelten Schritt aus.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `workflow_id` | string | **erforderlich** | ID des auszufuehrenden Workflows |

**Limits**: Maximale Verschachtelungstiefe von 5 Ebenen. Zirkulaere Referenzen werden erkannt. 30-Minuten-Timeout.

**Wann verwenden**: Komplexe Workflows aus kleineren, wiederverwendbaren zusammensetzen.

---

### `skill-capability` — Skill aufrufen

Ruft die Faehigkeit eines registrierten Skills auf.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `capability` | string | **erforderlich** | Faehigkeitsname (z.B. `"web_search"`, `"analyze"`) |
| `input` | string | `""` | Eingabedaten (optional, Default: leerer String) |
| `params` | map | `{}` | Zusaetzliche Parameter |
| `timeout_seconds` | int | null | Claude-CLI-Timeout |
| `allow_writes` | bool | `false` | Erlaubt der Capability Schreibzugriff aufs Repo. Erforderlich fuer `writes:true`-Capabilities (test-generator, feature-implementer). Ohne `allow_writes` laeuft der Block read-only (Tool-Whitelist Read/Grep/Glob/WebFetch/WebSearch/Task/TodoWrite). |

```json
{
  "type": "skill-capability",
  "config": {
    "capability": "analyze",
    "input": "Analysiere: {{ previous_output }}",
    "params": { "max_entries": 5 }
  }
}
```

**Wann verwenden**: Spezialisierte Skills nutzen (OCR, Web-Suche, Analyse).

**Sicherheit**: Capabilities deklarieren ueber `Capability.writes`, ob sie Dateien schreiben. Built-in `writes:true`: `builtin-test-generator/*`, `builtin-feature-implementer/*`. Ein Block, der eine `writes:true`-Capability aufruft ohne `"allow_writes": true`, faellt sofort fehl mit klarer Meldung. Fuer alle anderen Faelle wird der Host-CLI mit einer Read-Only-Tool-Whitelist gestartet (Defense-in-Depth).

---

### `document` — Text aus Dokumenten extrahieren

Liest eine Datei (PDF, DOCX, XLSX, PPTX, HTML, TXT, EPUB, RTF, …) und extrahiert den Plain-Text via Apache Tika. Pure Java, keine nativen Abhängigkeiten. Ausgabe ist der extrahierte Text als `previous_output` (und optional als benannte Variable). Kosten: 0 Tokens.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `file_path` | string | **erforderlich** | Pfad zur Datei. Absolut oder relativ zum Projekt-Pfad. Unterstuetzt Templates (`{{ variable }}`). |
| `variable_name` | string | null | Wenn gesetzt, wird der extrahierte Text zusätzlich in diese Kontext-Variable geschrieben. |
| `fail_on_empty` | bool | `false` | Wenn true, FAILT der Block wenn der extrahierte Text leer/unter Schwelle ist. |
| `min_characters` | int | `1` | Schwelle für `fail_on_empty`: weniger Zeichen gelten als leer. |

```json
{
  "type": "document",
  "config": {
    "file_path": "{{ invoice_path }}",
    "variable_name": "invoice_text",
    "fail_on_empty": true,
    "min_characters": 50
  }
}
```

**Unterstützte Formate (Auswahl)**: PDF (mit Text-Layer), DOCX/XLSX/PPTX, HTML/XML, TXT, EPUB, RTF, ODT.

**Limits**:
- Default max. 50 MB pro Datei (konfigurierbar via `automation.document.max-bytes`)
- Default max. 5 Mio. Zeichen Output (konfigurierbar via `automation.document.max-characters`)
- Pfad-Traversal (`../`) aus dem Projekt-Pfad wird blockiert
- Gescannte PDFs ohne Text-Layer liefern leeren Output → OCR ist aktuell nicht Teil dieses Blocks (folgt in einer späteren Ausbaustufe)

**Wann verwenden**: Rechnungs-/Vertragstext extrahieren, PDF-Reports zusammenfassen (Kombination mit `simple`-Block), Dokumenten-Klassifikation, Vorverarbeitung für LLM-Analyse ohne Vision-Token-Kosten.

**Beispiel-Kombination mit `simple`**:
```json
[
  { "type": "document", "config": { "file_path": "{{ pdf }}", "variable_name": "text" } },
  { "type": "simple", "config": { "command": "Fasse zusammen:\n\n{{ text }}" } }
]
```

---

## 3. Template-Variablen

### Variablen-Syntax

Variablen verwenden doppelte Klammern: `{{ variable_name }}` (Leerzeichen optional: `{{variable_name}}` funktioniert auch).

### Eingebaute Variablen

| Variable | Quelle | Beschreibung |
|---|---|---|
| `{{ previous_output }}` | Auto | Ausgabe des vorherigen Blocks (Step N-1) |
| `{{ step_N_output }}` | Auto | Ausgabe von Step N (z.B. `{{ step_0_output }}` fuer den ersten Block) |
| `{{ block.<name>.output }}` | Auto | Ausgabe eines benannten Blocks (Block muss ein `name`-Feld haben) |
| `{{ previous_chain_output }}` | Auto | Ausgabe des vorherigen Chain-Eintrags (nur Chains) |
| `{{ item }}` / `{{ <variable_name> }}` | ForEach | Aktuelles Schleifen-Item |

### System-injizierte Variablen

Folgende Variablen werden vom `WorkflowExecutor` automatisch bei jedem Run gesetzt und stehen in allen Bloecken zur Verfuegung:

| Variable | Beschreibung |
|---|---|
| `{{ workflow_run_id }}` | Eindeutige Run-ID |
| `{{ workflow_id }}` | Workflow-ID |
| `{{ workflow_name }}` | Workflow-Name |
| `{{ project_id }}` | Projekt-ID (oder leer) |
| `{{ project_path }}` | Projektpfad (oder leer) |
| `{{ model }}` | Konfiguriertes Modell (oder leer) |
| `{{ provider }}` | Konfigurierter AI-Provider (oder leer) |
| `{{ started_at }}` | Startzeitpunkt des Runs (ISO-8601, z.B. `2026-04-22T21:30:45.123Z`) |
| `{{ run_timestamp }}` | Pfadsicherer Timestamp (UTC, Format `yyyyMMdd-HHmmss`, z.B. `20260422-213045`) — ideal fuer Output-Verzeichnisse pro Run |
| `{{ home_dir }}` | Home-Verzeichnis des JVM-Benutzers |
| `{{ user }}` | Betriebssystem-Benutzername |
| `{{ workflow_version }}` | Aktuelle Versionsnummer des Workflows |
| `{{ workflow_changed_since_last_run }}` | `"true"` oder `"false"` — ob sich der Workflow seit dem letzten Run geaendert hat |
| `{{ previous_report }}` | Laengster Non-Shell-Step-Output des letzten COMPLETED-Runs desselben Workflows (max. 3000 Zeichen), oder `"(kein vorheriger Report vorhanden)"` |
| `{{ open_todos }}` | Markdown-Liste offener/in-Bearbeitung-TODOs des Projekts, oder `"(keine offenen TODOs)"` |

### Kontext-Variablen

Eigene Variablen ueber den Ausfuehrungskontext uebergeben:

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

### Aufloesung

Variablen werden zur Laufzeit durch `ExecutionContext.resolveTemplate()` aufgeloest. Nicht aufgeloeste Variablen bleiben als Literaltext stehen (z.B. `{{ undefined_var }}` bleibt unveraendert).

### Approval/Human-Input-Variablen

Antworten aus `approval`- und `human-input`-Bloecken werden als Kontext-Variablen gespeichert und stehen in nachfolgenden Bloecken zur Verfuegung:

```json
// approval-Block speichert Antwort in {{ pr_decision }}
// naechster Block kann verwenden: {{ pr_decision }}
```

---

## 4. Bewaehrte Workflow-Patterns

Diese Patterns sind aus den 65+ eingebauten Templates destilliert. Jedes Pattern hat eine klare Struktur und einen Anwendungsfall.

### Pattern 1: Sequentielle Simple-Kette

**Struktur**: `simple` -> `simple` -> `simple` -> `simple`

Alle Bloecke sind rein KI-basiert (kein Shell). Jeder Block verfeinert die Ausgabe des vorherigen.

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

**Anwendungsfall**: Log-Analyse, Git-Historie, System-Metriken, Abhaengigkeitspruefungen.

**Templates**: `git-to-changelog`, `log-triage`, `git-statistics-report`

```
Block 1 (shell): Rohdaten sammeln (git log, find, grep, Metriken)
Block 2 (simple): Analysieren und kategorisieren
Block 3 (simple): Bericht mit handlungsorientierten Empfehlungen erstellen
```

---

### Pattern 3: Analysieren + Agentic erstellen + Verifizieren

**Struktur**: `simple` -> `agentic` -> `simple`

KI analysiert Eingabe, Agentic-Block erstellt komplexe Ausgabe (Dateien, Code), Simple-Block verifiziert.

**Anwendungsfall**: Webseiten, Prototypen, Dokumentationspakete, Dashboards generieren.

**Templates**: `doc-to-website`, `idea-to-prototype`, `cv-to-portfolio`, `csv-to-dashboard`, `codebase-to-onboarding`

```
Block 1 (simple): Eingabe analysieren, Plan erstellen
Block 2 (agentic): Plan ausfuehren — Dateien erstellen, Deps installieren, bauen
Block 3 (simple): Ausgabe verifizieren, Qualitaetspruefung
```

---

### Pattern 4: Shell + Bedingte Verzweigung

**Struktur**: `shell` -> `conditional` -> `shell`/`simple`

Shell-Block generiert Daten, Conditional-Block verzweigt basierend auf Ergebnis.

**Anwendungsfall**: Deploy-Pipelines, CI/CD, bedingte Verarbeitung.

**Templates**: `deploy-pipeline`, `git-to-changelog` (bedingte Migrationshilfe)

```
Block 1 (shell): Vorbedingungen pruefen
Block 2 (conditional): Basierend auf Ergebnis verzweigen
Block 3: Entsprechende Aktion ausfuehren
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

Shell sammelt Code-/Config-Daten, mehrere Simple-Bloecke analysieren verschiedene Aspekte, letzter Block erstellt strukturierten Bericht.

**Anwendungsfall**: Code-Audits, Security-Scans, Dokumentationspruefungen, Gesundheitsbewertungen.

**Templates**: `api-consistency-audit`, `dead-code-detector`, `doc-accuracy-check`, `codebase-health-scorecard`, `architecture-debt-radar`, `test-coverage-gaps`

```
Block 1 (shell): Rohdaten sammeln (grep, find, wc etc.)
Block 2 (simple): Tiefenanalyse mit spezifischen Kriterien
Block 3 (conditional): Optionale Schweregrad-Pruefung
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
Block 1 (shell): Git-Safety-Setup (sauberen Tree pruefen, Branch erstellen)
Block 2 (simple): Findings parsen, Fix-Plan erstellen
Block 3 (agentic): Fixes anwenden, ein Commit pro Fix
Block 4 (shell): Verifikation (Build + Test + Lint)
Block 5 (conditional): Rollback bei Fehler
Block 6 (approval): Menschliche Freigabe vor Push
Block 7 (shell): Push + PR erstellen (oder abbrechen)
```

---

### Pattern 8: Bildgenerierung via Codex (imagegen-Skill)

**Struktur**: `shell` (Projektkontext lesen) -> `simple` (Bild-Prompt destillieren, Opus) -> `shell` (Codex generiert, Bash kopiert)

Erzeugt ein Raster-Bild (Hero, Cover, Illustration) **ohne tiefe Plattform-Integration**: ein `shell`-Block ruft die auf dem Host installierte Codex-CLI (`codex exec`) mit dem `imagegen`-Skill auf. Das built-in `image_gen`-Tool nutzt `gpt-image-2` und braucht **keinen `OPENAI_API_KEY`** (Codex' eigener Login). Der destillierte Prompt fliesst per `{{ block.prompt.output }}` in den Codex-Block.

**Anwendungsfall**: App-Hero/Cover aus Projektkontext ableiten, Marketing-Bilder, Illustrationen.

```bash
set -euo pipefail
TS=$(date +%Y%m%d-%H%M%S)
OUT="assets/generated/hero-$TS.png"
mkdir -p "$(dirname "$OUT")"
GENDIR="${CODEX_HOME:-$HOME/.codex}/generated_images"
MARKER="$(mktemp)"
DESC={{ block.prompt.output }}   # Block-Config: shell_escape_variables: true -> sicher gequotet
# Codex NUR generieren lassen (siehe Fallen); das Kopieren macht der Workflow-Bash.
timeout 240 codex exec --skip-git-repo-check --sandbox workspace-write -C "$PWD" \
  "Nutze imagegen (built-in image_gen), erzeuge GENAU EIN Bild 1536x1024. Keine Shell/Validierung/Kopie. Bildbeschreibung: $DESC" </dev/null || true
IMG=$(find "$GENDIR" -name '*.png' -newer "$MARKER" -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)
cp "$IMG" "$OUT" && echo "IMAGE_PATH=$PWD/$OUT"
```

**Zwei Fallen (beide zwingend):**
1. **stdin auf `/dev/null`** — `codex exec` liest stdin bis EOF (auch wenn der Prompt als Argument uebergeben wird). Ohne `</dev/null` erbt Codex vom Backend eine stdin ohne EOF und **blockiert bis zum Block-Timeout**.
2. **Codex nicht selbst kopieren lassen** — Codex' Shell-Sandbox (bubblewrap) startet im Backend-Kontext nicht (`bwrap: loopback: … Operation not permitted`); jeder von Codex gestartete Shell-/`cp`-/`view_image`-Befehl scheitert. Deshalb Codex **nur generieren** lassen (built-in `image_gen` speichert nach `$CODEX_HOME/generated_images/<session>/ig_*.png`) und die Datei mit dem **Workflow-Bash** kopieren (der laeuft ohne bwrap). `timeout … || true` kappt Codex' Retry-Schleifen; das Bild ist zu dem Zeitpunkt bereits gespeichert.

**Grenzen:**
- **Nur auf dem Dev-Host** — Codex ist in den Docker-Containern (Stable/Test) nicht installiert/eingeloggt → dort Exit 127.
- Umgeht das CLI-Semaphore (`HostClaudeService`), da direkter Shell-Aufruf.
- Der Block liefert den **Pfad**, nicht das Bild — Anzeige im Run-UI braeuchte Artefakt-Handling.

**Beispiel-Workflow**: „Bild aus Projektkontext generieren" (Projekt „Deutschlandticket Entdecker").

---

### Quality-Gate-Marker

Jeder Block kann Gate-Status-Marker in seiner Ausgabe emittieren um Qualitaetsergebnisse zu signalisieren. Der `WorkflowExecutor` scannt **alle** Block-Ausgaben und verwendet den hoechsten Schweregrad.

```
gate_status: GREEN|YELLOW|RED
gate_category: lint-errors
gate_description: 3 Probleme gefunden
```

| Gate | Run-Status | Auto-Todo-Prioritaet | Bedeutung |
|------|-----------|-------------------|---------|
| GREEN | `COMPLETED` | — | Alle Pruefungen bestanden |
| YELLOW | `COMPLETED_WITH_ERRORS` | MEDIUM | Warnungen, nicht-blockierende Probleme |
| RED | `COMPLETED` | HIGH | Kritische Probleme (via AutoTodoService behandelt) |

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

Wenn mehrere Bloecke Gates emittieren, gewinnt der hoechste Schweregrad (RED > YELLOW > GREEN).

**`run.metadata.gate_summary`** aggregiert zusaetzlich alle geparsten Gates des Runs:

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

Damit lassen sich zwei Runs ohne Re-Parsing diffen (z. B. fuer Public-Readiness-Trends):

```bash
curl -s -H "X-API-Key: $KEY" "$BASE/executions/$RUN1" | jq '.metadata.gate_summary' > a.json
curl -s -H "X-API-Key: $KEY" "$BASE/executions/$RUN2" | jq '.metadata.gate_summary' > b.json
diff <(jq -S . a.json) <(jq -S . b.json)
```

---

## 5. Timeout-Konfiguration

### Standardwerte

Quelle: `ExecutionDefaults.java`

| Einstellung | Default | Beschreibung |
|---|---|---|
| `SHELL_TIMEOUT_SECONDS` | 300 (5 Min) | Shell- und Simple-Block-Timeout |
| `AGENTIC_MAX_ITERATIONS` | 10 | Agentic-Block-Iterationsobergrenze |
| `AGENTIC_ITERATION_TIMEOUT_SECONDS` | 300 (5 Min) | Wall-Clock-Timeout pro Iteration |
| `AGENTIC_TOTAL_TIMEOUT_SECONDS` | 1800 (30 Min) | Gesamt-Agentic-Block-Timeout |
| `AGENTIC_MAX_TOTAL_TOKENS` | 200.000 | Token-Budget (Input+Output) |
| `INTERACTION_TIMEOUT_MINUTES` | 30 | Approval-/Human-Input-Timeout |
| `SUB_WORKFLOW_MAX_WAIT_MINUTES` | 30 | Sub-Workflow-Fertigstellungs-Timeout |

### Die Dual-Timeout-Falle

Agentic-Bloecke haben **zwei unabhaengige Timeouts**, die aufeinander abgestimmt sein muessen:

1. **`timeout_seconds`**: Claude-CLI-Timeout pro Aufruf (gilt fuer REASON- und ACT-Phase)
2. **`iteration_timeout_seconds`**: Wall-Clock-Timeout fuer die gesamte Iteration (REASON + ACT)

**Die Falle**: Wenn `timeout_seconds=900` aber `iteration_timeout_seconds=300`, kann der CLI-Aufruf in 900s erfolgreich sein, aber die Iteration wird bei 300s als TIMEOUT markiert und der Block SCHEITERT.

### Auto-Alignment

`AgenticBlockConfig` stimmt Timeouts automatisch ab wenn `timeout_seconds` gesetzt ist:

```
iteration_timeout_seconds = max(aktuell, timeout_seconds * 2 + 60)
```

Die Formel: `timeout_seconds * 2` (Raum fuer REASON + ACT) `+ 60` (Overhead).

Wenn `total_timeout_seconds` nicht einmal eine Iteration fassen kann, wird auch das auto-aligned.

### Empfohlene Werte nach Modell

| Modell | `timeout_seconds` | `iteration_timeout_seconds` | `total_timeout_seconds` | `max_total_tokens` |
|---|---|---|---|---|
| **Opus** | 600-900 | 900-1260 | 3600-5400 | 300.000-400.000 |
| **Sonnet** | 300-600 | 300-660 | 1800-3600 | 200.000-300.000 |
| **Haiku** | 120-300 | 300 | 900-1800 | 100.000-200.000 |

### Simple-/Shell-Block-Timeouts

Fuer Simple- und Shell-Bloecke gilt nur `timeout_seconds`:

| Aufgabenkomplexitaet | Empfohlenes `timeout_seconds` |
|---|---|
| Schnelle Befehle (git, find) | 60-120 |
| Build/Test (npm, mvn) | 300-600 |
| Komplexe Analyse (Opus) | 600-900 |

---

## 6. Modellauswahl

Das Modell wird auf Workflow-Ebene gesetzt (`workflow.model`-Feld) oder auf null fuer den System-Standard belassen.

### Welches Modell wann verwenden

| Modell | Anwendungsfall | Staerken | Schwaechen |
|---|---|---|---|
| `claude-opus-4-6` | Analyse, Audits, Security-Reviews | Praezision, weniger Halluzinationen, tiefes Reasoning | Langsamer, teurer |
| `claude-sonnet-4-6` | Code-Generierung, Routine-Aufgaben | Schnell, gut genug fuer die meisten Aufgaben | Kann Findings in Analysen halluzinieren |
| `claude-haiku-4-5` | Einfache Klassifikation, Extraktion | Sehr schnell, guenstig | Begrenzte Reasoning-Tiefe |

### Kritische Regel

**Immer Opus fuer Analyse-Workflows verwenden.** Sonnet halluziniert Findings (z.B. falsche SQL-Injection-Schwachstellen melden). Fuer Code-Analyse, Security-Audits und Architektur-Reviews ist Praezision wichtiger als Geschwindigkeit.

### Modell setzen

Via API:
```json
{
  "model": "claude-opus-4-6"
}
```

Im Workflow-Entity wird das `model`-Feld via `--model`-Flag an die Claude CLI uebergeben.

**Wichtig**: Ein null-Model-Feld nutzt den System-Standard (der Sonnet sein kann). Wenn Opus benoetigt wird, explizit setzen.

---

## 7. Ausfuehrungsumgebung

### Host-Umgebung (Standard)

Shell-Bloecke ohne `container_image` laufen auf dem Host. Verfuegbare Tools:

- `curl`, `git`, `python3`, `jq`, `nodejs`, `npm`, `wget`, `unzip`, `bash`

Alles andere (pip-Pakete, go, docker-cli, gh, trivy, spezialisierte Tools) ist **NICHT** verfuegbar.

### Container-Images

Fuer Shell-Bloecke die mehr Tools benoetigen, `container_image` setzen:

| Image | Basis | Enthalten |
|---|---|---|
| `automation-agent-python:latest` | Python 3.12 | pandas, numpy, requests, beautifulsoup4, pip, git, curl, jq |
| `automation-agent-devops:latest` | Ubuntu 24.04 | git, curl, jq, python3, docker-cli, gh (GitHub CLI), trivy, gitleaks |
| `automation-agent-node:latest` | Node.js 21 | npm (volles Oekosystem), git, curl, jq |
| `automation-agent-go:latest` | Go 1.22 | git, curl, bash, jq |

### Regeln

- Shell-Bloecke OHNE `container_image` koennen NUR Host-Tools verwenden
- Braucht ein Befehl pip, go, docker, gh oder andere spezialisierte Tools -> `container_image` setzen
- Container mounten das Projektverzeichnis nach `/workspace`
- Claude CLI laeuft immer auf dem Host (nicht in Containern), um Nested-Session-Erkennung zu vermeiden

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

---

## 8. Agentic Block Deep Dive

### Die Goal-Reason-Act-Observe-Schleife

Jede Iteration:
1. **REASON**: Claude analysiert den aktuellen Stand, Gespraechsverlauf und entscheidet die naechste ACTION (nutzt `--system-prompt`, `--max-turns 3`)
2. **ACT**: Ein **separater** Claude-CLI-Aufruf fuehrt die ACTION aus (nutzt `--append-system-prompt`, `--model`, Projektverzeichnis)
3. **OBSERVE**: Das Ergebnis wird aufgezeichnet und in die naechste REASON-Phase zurueckgefuehrt

### Kritisch: ACT hat keinen Kontext aus REASON

Die ACT-Phase ist ein **frischer Claude-CLI-Aufruf**. Sie teilt keinen Gespraechskontext mit REASON. Das bedeutet:

**Jede ACTION muss eigenstaendig sein:**
- **Absolute Pfade** verwenden (nicht relativ, nicht "die Datei von vorhin")
- **Spezifische Namen** angeben (nicht "die Funktion die wir besprochen haben")
- **Exakte Anweisungen** geben (nicht "den Bug fixen" sondern "in `/pfad/zur/Datei.java` Zeile 42, `foo` zu `bar` aendern")

```
FALSCH: "Den Bug in der Service-Klasse fixen"
RICHTIG: "In /home/user/project/src/main/java/com/example/UserService.java
         die Methode getUserById() anpassen, um null-Eingaben zu behandeln
         indem am Anfang der Methode eine Null-Pruefung eingefuegt wird."
```

### History-Komprimierung

Wenn die History mehr als 7 Eintraege hat, werden mittlere Turns zusammengefasst — der erste Turn (initialer Prompt) und die letzten 4 Turns bleiben vollstaendig erhalten. Das bedeutet, fruehere Beobachtungen koennen komprimiert werden. Ziele und Erfolgskriterien so gestalten, dass sie aus dem juengsten Kontext auswertbar sind.

### Verschmutzung durch veraltete Dateien

Alte Dateien aus vorherigen Runs koennen den Kontext des Agentic-Blocks verschmutzen (z.B. Glob-Patterns die veraltete Reports aufgreifen). Einen Aufraeum-Schritt vor Agentic-Bloecken einfuegen:

```json
{
  "type": "shell",
  "config": {
    "command": "rm -rf output/ && mkdir -p output/"
  }
}
```

### Sicherheitslimits

| Config-Schluessel | Default | Zweck |
|---|---|---|
| `max_iterations` | 10 | Harter Stopp nach N Iterationen |
| `iteration_timeout_seconds` | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamt-Timeout (30 Min) |
| `max_total_tokens` | 200.000 | Token-Budget (Input+Output) |
| `max_repeated_actions` | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | false | Menschliches Gate vor jeder Aktion |

### Action-Komprimierung

Wenn eine ACTION 100.000 Zeichen ueberschreitet, wird sie via separatem Claude-CLI-Aufruf (60s Timeout) komprimiert bevor sie an die ACT-Phase gesendet wird.

### Token-Tracking

Token-Nutzung (Input + Output) wird ueber alle Iterationen getrackt. Wenn das Budget erschoepft ist, stoppt der Block. Wenn REASON oder ACT keine Nutzungsdaten liefern, wird ein WARN geloggt — das Budget wird nicht durchgesetzt.

---

## 9. Workflow-Instruktionen & Memory

### Zweistufiges Instruktionssystem

1. **Basis-Instruktionen** (`src/main/resources/agent-instructions.md`): Beim Start geladen, gelten fuer ALLE Workflows. Umfassen Arbeitsverzeichnis, Dateiausgabe, Fehlerbehandlung, Qualitaetsstandards.

2. **Workflow-spezifische Instruktionen** (`workflow.instructions`-Feld): Pro Workflow, editierbar in der UI (Monaco-Editor). Erscheinen als Teil des System-Prompts.

### Wie Instruktionen injiziert werden

- **Block-Ausfuehrung** (alle Block-Typen): Via `--append-system-prompt`-Flag an der Claude CLI
- **Agentic REASON-Phase**: Via `--system-prompt`-Flag, beinhaltet Workflow-Instruktionen im Abschnitt `## Workflow Instructions`

### Workflow Memory

Workflows koennen ueber Runs hinweg Wissen ansammeln. Memory kann **manuell** oder **automatisch** extrahiert werden.

#### Wie es funktioniert

1. **Extrahieren** (manuell oder automatisch): Nach einem oder mehreren Runs, Extraktion ueber UI ("Memory"-Tab auf der Workflow-Detailseite) oder API ausloesen. Runs auswaehlen und Provider waehlen (Claude oder Codex). Der `ConversationPreprocessor` komprimiert die Step-Outputs um ~50-70% bevor sie an die KI gesendet werden, die einen strukturierten Wissenseintrag (Titel, Markdown-Inhalt, Tags) erstellt.
2. **Aktivieren** (manuell oder automatisch): Bei manueller Extraktion startet Memory als `active=false`. Bei automatischer Extraktion wird es direkt aktiviert. Nur eine Version kann gleichzeitig aktiv sein.
3. **Beim naechsten Run verwendet**: Vor jeder Workflow-Ausfuehrung wird das aktive Memory nach `.claude/rules/project-memory.md` im Projektverzeichnis geschrieben. Die Claude CLI greift auf diese Datei automatisch via Rules-System zu.
4. **Versionierung**: Mehrere Memory-Versionen pro Workflow werden aufbewahrt. Aeltere Versionen koennen durch Aktivierung wiederhergestellt werden.
5. **Bearbeiten**: Titel und Inhalt koennen in der UI (Monaco-Editor) manuell verfeinert werden.

#### Automatische Extraktion (Auto-Memory)

Konfigurierbar pro Workflow ueber `metadata.autoMemoryExtraction`:

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
- Zaehlt erfolgreiche Runs seit letzter Extraktion
- Neues Memory wird automatisch aktiviert (ersetzt das bisherige aktive Memory)
- Konfiguration auch ueber den Toggle "Auto-Memory" auf der Workflow-Detailseite

#### Wann verwenden

Memory extrahieren nachdem ein Workflow einen Run mit wertvollen Erkenntnissen produziert hat — Patterns, Fallstricke, Domain-Wissen — die zukuenftige Runs leiten sollen. Besonders nuetzlich fuer Agentic-Workflows die projektspezifische Konventionen ueber die Zeit lernen. **Auto-Memory empfohlen** fuer Workflows die regelmaessig laufen und deren Erkenntnisse sich akkumulieren.

#### API

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/api/v1/workflows/{id}/memory` | Alle Memory-Versionen auflisten |
| POST | `/api/v1/workflows/{id}/memory/extract` | Aus Runs extrahieren (`runIds`, `provider`) |
| PUT | `/api/v1/workflows/{id}/memory/{mid}` | Titel/Inhalt aktualisieren |
| PUT | `/api/v1/workflows/{id}/memory/{mid}/activate` | Version aktivieren |
| DELETE | `/api/v1/workflows/{id}/memory/{mid}` | Version loeschen |

```json
// POST /api/v1/workflows/{id}/memory/extract
{
  "runIds": ["run-id-1", "run-id-2"],  // optional — weglassen fuer letzte 10 Runs
  "provider": "CLAUDE"                  // oder "CODEX"
}
```

---

## 10. Chains

Chains gruppieren mehrere Workflows zur **sequentiellen Ausfuehrung**.

### Kerneigenschaften

- **Projektuebergreifend**: Eine Chain kann Workflows aus beliebigen Projekten referenzieren
- **Sequentiell**: Workflows laufen nacheinander (nicht parallel)
- **Kontextweitergabe**: Jeder Eintrag kann auf `{{ previous_chain_output }}` des letzten abgeschlossenen Workflows zugreifen
- **stopOnFailure**: Steuert ob die Chain bei Workflow-Fehler stoppt — stoppt nur wenn **beide** Flags gesetzt sind (Chain-Level UND Entry-Level muessen `true` sein)

### Chain-Struktur

```json
{
  "name": "Vollstaendige Audit + Fix Pipeline",
  "entries": [
    {
      "workflowId": "<audit-workflow-id>",
      "sortOrder": 0,
      "context": { "scope": "backend" },
      "stopOnFailure": true
    },
    {
      "workflowId": "<fix-workflow-id>",
      "sortOrder": 1,
      "context": { "audit_report": "{{ previous_chain_output }}" },
      "stopOnFailure": false
    }
  ]
}
```

### API

```
GET    /api/v1/chains                      # Alle Chains auflisten
POST   /api/v1/chains                      # Chain erstellen
GET    /api/v1/chains/{id}                 # Chain abrufen
PUT    /api/v1/chains/{id}                 # Chain aktualisieren
DELETE /api/v1/chains/{id}                 # Chain loeschen
POST   /api/v1/chains/{id}/execute         # Ausfuehrung starten
GET    /api/v1/chains/runs                 # Alle Chain-Runs auflisten
GET    /api/v1/chains/{id}/runs            # Runs einer Chain auflisten
GET    /api/v1/chains/{id}/runs/{runId}    # Einzelnen Run abrufen
POST   /api/v1/chains/runs/{runId}/cancel  # Run abbrechen
```

### Limits

- Polling-basierte Fertigstellungspruefung (2s Intervall)
- 180-Minuten-Timeout pro Workflow in der Chain (3 Stunden, konfigurierbar via `automation.chain.workflow-timeout-minutes`)

---

## Integration Block (Plugin-System)

Ruft Actions von verbundenen externen Diensten auf (Google Calendar, Trello, etc.). Teil des Plugin-Systems — jeder `IntegrationProvider` stellt Actions bereit, die in Workflows als `integration`-Block genutzt werden koennen.

**Voraussetzung:** Der Provider muss auf der Integrationen-Seite (`/integrations`) verbunden sein.

### Config

| Feld | Pflicht | Beschreibung |
|------|---------|--------------|
| `provider` | Ja | Provider-ID: `google-calendar`, `trello`, etc. |
| `action` | Ja | Action-Name, z.B. `list_events`, `create_card` |
| `params` | Nein | Parameter als Key-Value, unterstuetzt `{{ template_vars }}` |

### Beispiel: Naechste Google Calendar Events abrufen

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

### Verfuegbare Actions

Alle Actions eines Providers sind auf der Integrationen-Seite sichtbar (Provider-Karte → "Test"-Button). Action-Namen und Parameter-Schemas werden dort angezeigt.

### Fehlerbehandlung

Wenn der Provider nicht verbunden ist oder die API fehlschlaegt, wird der Block als `FAILED` markiert mit der Fehlermeldung im Output. Token-Refresh passiert automatisch.

### Neuen Provider als Workflow-Block nutzen

1. Provider auf der Integrationen-Seite verbinden (OAuth-Flow)
2. Integration-Block im Workflow-Editor hinzufuegen
3. Provider und Action waehlen, Parameter konfigurieren
4. Template-Variablen (`{{ previous_output }}`, `{{ started_at }}`) funktionieren in Params

---

## 11. Dos und Don'ts

### DO

- **`timeout_seconds` explizit setzen** fuer komplexe Bloecke — nicht auf Defaults verlassen
- **Opus fuer Analyse-/Audit-Workflows verwenden** — Sonnet halluziniert Findings
- **`set -euo pipefail`** am Anfang mehrzeiliger Shell-Befehle verwenden
- **Absolute Pfade** in Agentic-Block-Zielen und -Aktionen verwenden
- **Aufraeum-Schritte** vor Agentic-Bloecken einfuegen um Verschmutzung durch veraltete Dateien zu vermeiden
- **Agentic-Block-Ziele spezifisch halten** — "Erstelle index.html in output/" statt "Baue eine Webseite"
- **`{{ previous_output }}`** verwenden um Block-Ausgaben zu verketten — Daten fliessen vorwaerts durch die Pipeline
- **Sowohl `timeout_seconds` als auch `iteration_timeout_seconds`** fuer Agentic-Bloecke setzen — das Auto-Alignment hilft, aber explizit ist besser
- **Verifikations-Block** nach Agentic-Bloecken einfuegen (Dateien pruefen, Tests ausfuehren, Ausgabe validieren)

### DON'T

- **Sonnet nicht fuer Code-Analyse verwenden** — halluziniert falsche Positives (gefaelschte SQL-Injection, gefaelschte Security-Issues)
- **`iteration_timeout_seconds` nicht niedriger als `timeout_seconds` setzen** — die CLI wird erfolgreich sein, aber die Iteration wird als TIMEOUT markiert
- **Nicht annehmen, dass ACT Kontext aus REASON hat** — jede Aktion ist ein frischer CLI-Aufruf
- **Keine relativen Pfade in Agentic-Aktionen** — die ACT-Phase teilt moeglicherweise nicht denselben Working-Directory-Kontext
- **Opus-Workflows nicht parallel ausfuehren** wenn `max-concurrent-calls` niedrig ist (z.B. 3) — Workflows blockieren sich gegenseitig
- **`container_image` nicht fuer Simple-Bloecke verwenden** — Simple-Bloecke nutzen immer die Host-Claude-CLI
- **Nicht vergessen, `model` explizit zu setzen** — null nutzt den System-Standard der moeglicherweise nicht gewuenscht ist
- **Shell-Bloecke nicht fuer KI-Reasoning verwenden** — stattdessen Simple-Bloecke nutzen (Shell-Bloecke fuehren nur Befehle aus)
- **Nicht mehr als 5-7 Bloecke** ohne guten Grund verketten — jeder Block fuegt Latenz und Kosten hinzu
- **Den Verifikationsschritt** nach Agentic-Bloecken nicht ueberspringen — Vertrauen ist gut, Kontrolle ist besser

### Anti-Patterns

| Anti-Pattern | Problem | Loesung |
|---|---|---|
| Agentic-Block mit vagem Ziel | Agent dreht sich ohne Fortschritt | Ziel konkret mit Dateipfaden und Erfolgskriterien formulieren |
| 100+ Woerter pro Agentic-Aktion | ACT-Phase laeuft in Timeout oder wird verwirrt | In kleinere, spezifische Aktionen aufteilen |
| Kein Aufraeum vor Agentic-Block | Veraltete Dateien aus vorherigen Runs verschmutzen Kontext | `rm -rf output/`-Shell-Block davor einfuegen |
| `timeout_seconds: 900` mit Standard-`iteration_timeout_seconds` | Iteration laeuft bei 300s in Timeout obwohl CLI erfolgreich | Beide Timeouts setzen oder Auto-Alignment nutzen |

---

## Anhang: Template-Kurzreferenz

Alle 65+ eingebauten Templates mit ihrem primaeren Pattern:

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
| `hunt-quality-pipeline` | Mehrstufige Pipeline | CODE |
| `folder-backup` | Shell-Kette | UTILITY |
| `github-issues-export` | Shell | TEAM |
| `github-issues-import` | Shell | TEAM |
| `github-issues-sync` | Shell-Kette | TEAM |
| `github-project-bootstrap` | Shell-Kette | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `log-rotate-cleanup` | Shell sammeln + KI analysieren | INFRA |
| `pool-neutrality-audit` | Audit-Pipeline | QUALITY |
| `pr-review-summary` | Shell sammeln + KI analysieren | TEAM |
| `research-enhance` | Sequentielle Simple-Kette | RESEARCH |
| `saas-auto-fix` | Analysieren + Agentic + Verifizieren | CODE |
| `saas-containerize` | Shell + Freigabe | DEPLOY |
| `saas-continuous-improve` | Shell sammeln + KI analysieren | AUTOMATION |
| `saas-doc-update` | Agentic | DOCS |
| `saas-feature-implement` | Analysieren + Agentic + Verifizieren | CODE |
| `saas-feature-loop` | Shell scannen + ForEach | TRANSFORM |
| `saas-marketing-site` | Analysieren + Agentic | CONTENT |
| `saas-scaffold` | Shell-Kette | TRANSFORM |
| `saas-spec-to-buildplan` | Document + Analysieren + Freigabe | BUSINESS |
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

---

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

**Tag-Matching**: Komma-separiert mit AND-Semantik. Alle Tags im Pattern muessen im TODO vorhanden sein. Beispiel: `"backend,bug"` matcht ein TODO mit Tags `["backend", "bug", "security"]`, aber nicht `["backend", "feature"]`.

**Weitere Felder**:

| Feld | Default | Beschreibung |
|------|---------|-------------|
| `enabled` | `true` | Trigger aktiv/inaktiv |
| `processBacklog` | `true` | Bestehende offene TODOs nachträglich verarbeiten (Polling alle 5 Min, max 1 pro Tick) |
| `contextTemplate` | `{}` | Statische Key-Value-Paare, die zusaetzlich zum TODO-Kontext an den Fix-Workflow uebergeben werden |

### Optionale Filter

| Feld | Beschreibung | Beispiel |
|------|-------------|----------|
| `projectId` | Nur TODOs aus diesem Projekt | `"ebf4f50c-..."` |
| `sourceFilter` | Nur TODOs aus dieser Quelle | `"WORKFLOW"` |
| `minPriority` | Mindest-Prioritaet (HIGH, MEDIUM, LOW) | `"HIGH"` |

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

Zusaetzlich werden alle Eintraege aus `contextTemplate` des Triggers als weitere Variablen uebergeben.

### API

| Methode | Pfad | Beschreibung |
|---------|------|-------------|
| GET | `/api/v1/todo-triggers` | Alle Trigger auflisten |
| GET | `/api/v1/todo-triggers/{id}` | Einzelnen Trigger abrufen |
| GET | `/api/v1/todo-triggers/workflow/{workflowId}` | Trigger nach Ziel-Workflow |
| POST | `/api/v1/todo-triggers` | Trigger erstellen |
| POST | `/api/v1/todo-triggers/{id}/enable` | Trigger aktivieren |
| POST | `/api/v1/todo-triggers/{id}/disable` | Trigger deaktivieren |
| DELETE | `/api/v1/todo-triggers/{id}` | Trigger loeschen |

### Loop-Prevention (3 Schichten)

1. **Max-Attempts**: Nach `maxAttempts` fehlgeschlagenen Fix-Versuchen wird nicht erneut getriggert
2. **Source-Filter**: Trigger können auf `sourceFilter: "WORKFLOW"` eingeschränkt werden — manuell erstellte TODOs lösen keinen Fix aus
3. **Fix-Attempt-Tracking**: `todo.fixAttempts` zählt Versuche, `todo.lastFixRunId` speichert den letzten Run

### Auto-Close

Bei `autoClose: true` (Standard): Wenn der Fix-Workflow mit **GREEN** Gate-Status abschließt, wird das TODO automatisch auf DONE gesetzt. Bei YELLOW oder RED bleibt es offen.

### Best Practices

- **Tag-Patterns spezifisch halten** — `"backend,security,bug"` statt nur `"bug"`, sonst triggert der Fix-Workflow bei jedem Bug
- **Fix-Workflows idempotent designen** — sie könnten mehrfach laufen
- **Gate-Status im Fix-Workflow setzen** — damit Auto-Close korrekt funktioniert
- **maxAttempts begrenzen** — Standard 3 ist sinnvoll, höhere Werte riskieren Endlos-Schleifen bei systematischen Fehlern
