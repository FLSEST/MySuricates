> *Zuletzt aktualisiert: 28. März 2026, 14:30 Uhr — Workflow: MySuricates Docs aktualisieren | Run: c47b8112-0468-445d-bfaf-a7a8a5d46264*

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

MySuricates unterstuetzt 12 Block-Typen. Jeder Block hat einen `type` und eine `config`-Map.

### `simple` — KI-Textverarbeitung

Sendet den Prompt an die Claude CLI auf dem Host. Keine Shell-Ausfuehrung.

| Config-Schluessel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Der Prompt, der an Claude gesendet wird |
| `timeout_seconds` | int | 300 | Claude-CLI-Timeout |

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
| `mount_docker_socket` | bool | false | Docker-Socket in den Container mounten (nur wirksam wenn `container_image` gesetzt) |

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
| `then_command` | string | **erforderlich** | Befehl wenn Bedingung wahr |
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
| `condition_command` | string | **erforderlich** | Bedingung, die jede Iteration ausgewertet wird |
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
| `input` | string | **erforderlich** | Eingabedaten |
| `params` | map | `{}` | Zusaetzliche Parameter |
| `timeout_seconds` | int | null | Claude-CLI-Timeout |

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

---

## 3. Template-Variablen

### Variablen-Syntax

Variablen verwenden doppelte Klammern: `{{ variable_name }}` (Leerzeichen optional: `{{variable_name}}` funktioniert auch).

### Eingebaute Variablen

| Variable | Quelle | Beschreibung |
|---|---|---|
| `{{ previous_output }}` | Auto | Ausgabe des vorherigen Blocks (Step N-1) |
| `{{ previous_chain_output }}` | Auto | Ausgabe des vorherigen Chain-Eintrags (nur Chains) |
| `{{ item }}` / `{{ <variable_name> }}` | ForEach | Aktuelles Schleifen-Item |

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

Diese Patterns sind aus den 40+ eingebauten Templates destilliert. Jedes Pattern hat eine klare Struktur und einen Anwendungsfall.

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

Nach 7 Turns werden aeltere Turns zusammengefasst um das Context-Window zu verwalten. Das bedeutet, fruehere Beobachtungen koennen komprimiert werden. Ziele und Erfolgskriterien so gestalten, dass sie aus dem juengsten Kontext auswertbar sind.

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

Workflows koennen ueber Runs hinweg Wissen ansammeln. Memory wird **manuell extrahiert** — es passiert nicht automatisch nach einem Run.

#### Wie es funktioniert

1. **Extrahieren** (manuell): Nach einem oder mehreren Runs, Extraktion ueber UI ("Memory"-Tab auf der Workflow-Detailseite) oder API ausloesen. Runs auswaehlen und Provider waehlen (Claude oder Codex). Der `ConversationPreprocessor` komprimiert die Step-Outputs um ~50-70% bevor sie an die KI gesendet werden, die einen strukturierten Wissenseintrag (Titel, Markdown-Inhalt, Tags) erstellt.
2. **Aktivieren** (manuell): Extrahiertes Memory startet als `active=false`. Explizit aktivieren bevor es wirksam wird. Nur eine Version kann gleichzeitig aktiv sein.
3. **Beim naechsten Run verwendet**: Vor jeder Workflow-Ausfuehrung wird das aktive Memory nach `.claude/rules/project-memory.md` im Projektverzeichnis geschrieben. Die Claude CLI greift auf diese Datei automatisch via Rules-System zu.
4. **Versionierung**: Mehrere Memory-Versionen pro Workflow werden aufbewahrt. Aeltere Versionen koennen durch Aktivierung wiederhergestellt werden.
5. **Bearbeiten**: Titel und Inhalt koennen in der UI (Monaco-Editor) manuell verfeinert werden.

#### Wann verwenden

Memory extrahieren nachdem ein Workflow einen Run mit wertvollen Erkenntnissen produziert hat — Patterns, Fallstricke, Domain-Wissen — die zukuenftige Runs leiten sollen. Besonders nuetzlich fuer Agentic-Workflows die projektspezifische Konventionen ueber die Zeit lernen.

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
- **stopOnFailure**: Steuert ob die Chain bei Workflow-Fehler stoppt (konfigurierbar pro Chain und pro Eintrag)

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
POST   /api/v1/chains              # Chain erstellen
POST   /api/v1/chains/{id}/execute  # Ausfuehrung starten
DELETE /api/v1/chains/{id}          # Chain loeschen
```

### Limits

- Polling-basierte Fertigstellungspruefung (2s Intervall)
- 30-Minuten-Timeout pro Workflow in der Chain

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

Alle 40+ eingebauten Templates mit ihrem primaeren Pattern:

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
| `hunt-quality-pipeline` | Mehrstufige Pipeline | CODE |
| `folder-backup` | Shell-Kette | UTILITY |
| `github-project-bootstrap` | Shell-Kette | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `secret-scanner` | Shell + Conditional | CODE |
| `container-vuln-scan` | Shell + Conditional | CODE |
| `database-backup-verify` | Shell-Kette | CODE |
| `directory-sync` | Shell-Kette | UTILITY |
| `docker-image-build-push` | Shell + Conditional | CODE |
| `todo-creator` | Agentic | UTILITY |
