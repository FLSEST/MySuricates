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
Quellcode-Setup steht er in `~/.autotrigger/api-key`. Die `projectId`
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

MySuricates unterstützt 20 Block-Typen. Jeder Block hat einen `type` und eine `config`-Map.

**Unbekannte Block-Typen werden beim Speichern abgelehnt** (HTTP 400, Fehlermeldung nennt den Typ und die erlaubten Typen). Geprüft wird gegen die Executor-Registry (`BlockTypeRegistry` — alle registrierten `BlockExecutor`-Beans), rekursiv auch in verschachtelten Blöcken (`switch`-`cases[].blocks` und `default`). Ein fehlender oder leerer `type` zählt dabei als unbekannt. Das gilt für jeden Schreibweg gleichermassen: UI, REST-API, Import, Pool-Installation, Seeder und Factory. Ein Tippfehler wie `"shel"` scheitert damit sofort und nicht erst mitten im Run.

Auch das **Wiederherstellen einer alten Version** (`POST /api/v1/workflows/{id}/versions/{versionId}/restore`) geht durch diese Prüfung: enthält die alte Definition einen Typ, den es heute nicht mehr gibt, scheitert die Wiederherstellung mit 400 — eine nicht mehr ausführbare Definition kommt nicht zurück.

**Hilfe im Block-Editor:** Das Konfigurationsfeld kennt die Schlüssel jedes Block-Typs. Strg+Leertaste schlägt Schlüssel und erlaubte Werte vor (z. B. `on_failure` → `stop`/`continue`), ein zweites Strg+Leertaste zeigt die Erklärung dazu, und der Hover über einen Schlüssel erklärt ihn samt seinen Werten. Unbekannte Schlüssel, falsche Werte und fehlende Pflichtfelder werden gelb markiert, das Speichern bleibt trotzdem möglich. Der Dialog lässt sich an der rechten unteren Ecke aufziehen und maximieren. Die Block-Skills erscheinen nur bei Blöcken, die die KI im Projektverzeichnis aufrufen. Quelle der Texte ist `ai-automation-frontend/src/components/workflow/blockConfigSchema.ts`: Wer einem Executor einen neuen Config-Schlüssel beibringt, trägt ihn dort ein. `blockConfigSchema.test.ts` gleicht das Schema mit allen mitgelieferten Vorlagen, Blaupausen und Pool-Workflows ab.

### Allgemeine Block-Eigenschaften

Jeder Block unterstützt neben seinen typspezifischen Config-Feldern auch diese allgemeinen Eigenschaften:

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `on_failure` | string | `"stop"` | Verhalten bei Fehler: `"stop"` (Workflow beenden) oder `"continue"` (mit nächstem Block fortfahren) |
| `run_if` | string | null | **Nur oberste Ebene**: Shell-Test vor dem Block, im Arbeitsverzeichnis des Laufs (bei Isolation im Klon), höchstens 60 s. Exit 0 → der Block läuft; sonst ist er `SKIPPED` mit der Ausgabe `Übersprungen (run_if: exit N)` – sein Executor wird nicht gerufen (kein KI-Aufruf, keine Freigabe, nichts geschrieben), eine `variable_name`-Variable bleibt ungesetzt. Platzhalter wie in jedem Shell-Feld (fremde Werte immer gequotet, `shell_escape_variables` für alle). Typisch: `test -s .git/state/ids.txt`. |
| `provider` | string | null | **Nur KI-fähige Blöcke** (`simple`, `agentic`, `skill-capability`, `compress-ai` sowie der Inline-`command` von `retry`, `foreach`, `while`, `conditional`, `switch`): KI-Anbieter nur für diesen Block (`CLAUDE`, `PI`, `CODEX`; bei `compress-ai` nur `CLAUDE`). Präzedenz Block > Workflow > System-Default. Beim `integration`-Block bezeichnet `provider` dagegen den Integrations-Provider (`google-calendar`, …). |
| `model` | string | null | **Nur KI-fähige Blöcke** (inkl. `compress-ai`): Modell nur für diesen Block (CLI-Alias `opus`/`sonnet`, konkrete ID oder pi `<backend>/<model>`). Präzedenz Block > Workflow > Provider-Default. Setzt ein Block einen *anderen* `provider` als der Workflow, wird das Workflow-Modell nicht vererbt (Modell-IDs sind anbieterspezifisch). |
| `effort` | string | null | **Nur KI-fähige Blöcke**: Denkaufwand `low`/`medium`/`high`/`xhigh` nur für diesen Block, siehe [Reasoning-Effort](#reasoning-effort-denkaufwand). |

Zusätzlich unterstützt jeder Block optionale Top-Level-Felder (neben `type`, nicht in `config`):

| Feld | Typ | Default | Beschreibung |
|---|---|---|---|
| `name` | string | – | Name, unter dem spätere Blöcke die Ausgabe dieses Blocks per `{{ block.<name>.output }}` lesen. Empfohlen sind Buchstaben, Ziffern, `_` und `-`, keine Punkte und kein Leerraum — geprüft wird das beim Speichern nicht; abgelehnt wird nur der reservierte Name `previous_output` (`BlockNameRules`) |
| `skillIds` | string[] | `[]` | Skill-IDs die **zusätzlich** zu den Workflow-Skills für diesen Block aktiviert werden |

**Standard-Verhalten**: Wenn ein Block fehlschlägt (Status `FAILED`), stoppt der Workflow und der Run wird als `FAILED` markiert. Mit `"on_failure": "continue"` fährt der Workflow mit dem nächsten Block fort. Jeder andere Wert (etwa `"fail"` oder ein Tippfehler) stoppt wie `"stop"` — nur `continue` (Groß-/Kleinschreibung egal) lässt weiterlaufen.

**Per-Block Skills**: Blöcke können über `skillIds` eigene Skills mitbringen, die additiv zu den Workflow-Level-Skills gelten. Der `WorkflowExecutor` merged beide Listen vor der Ausführung und stellt den Baseline-Zustand nach dem Block wieder her. Beispiel:

```json
{
  "type": "simple",
  "config": { "command": "Analysiere den Code auf Sicherheitslücken" },
  "skillIds": ["builtin-code-review", "builtin-security-analyzer"]
}
```

---

### `simple` — KI-Textverarbeitung

Sendet den Prompt an die Claude CLI auf dem Host. Keine Shell-Ausführung.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Der Prompt, der an Claude gesendet wird |
| `timeout_seconds` | int | null | Claude-CLI-Timeout (wenn null, gilt der globale `automation.host-claude.timeout-seconds`-Wert) |

```json
{
  "type": "simple",
  "config": {
    "command": "Analysiere den folgenden Code und liste Verbesserungsvorschläge auf:\n\n{{ previous_output }}"
  }
}
```

**Wann verwenden**: Textanalyse, Zusammenfassung, Code-Review, Content-Generierung — jede Aufgabe, die KI-Reasoning ohne Shell-Zugriff benötigt.

---

### `shell` — Shell-Befehl-Ausführung

Führt einen Shell-Befehl auf dem Host oder innerhalb eines Docker-Containers aus.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Shell-Befehl(e) zur Ausführung |
| `container_image` | string | null | Docker-Image (siehe Ausführungsumgebung; Server kann per Allowlist einschränken) |
| `timeout_seconds` | int | 300 | Ausführungs-Timeout |
| `shell_escape_variables` | bool | false | **Alle** Template-Variablen POSIX-sicher escapen (Single-Quotes). Variablen aus Webhook-Payloads (inkl. `git_*`), die daraus abgeleiteten Block-Ausgaben sowie `{{ previous_report }}` und `{{ open_todos }}` werden **immer** escaped, unabhängig von dieser Option |
| `mount_docker_socket` | bool | false | Docker-Socket in den Container mounten (nur wirksam wenn `container_image` gesetzt) |
| `mounts` | list | `[]` | Zusätzliche Docker-Volume-Mounts im Format `host:container[:ro\|rw]` (nur wirksam wenn `container_image` gesetzt) |
| `checked_command` | bool | false | Leitet aus dem Exit-Code automatisch ein Gate ab (`gate_status`/`gate_category`/`gate_description`, siehe [Quality-Gate-Marker](#quality-gate-marker) → „`checked_command` / `check_category`") und stellt `set -e -o pipefail` voran |
| `check_category` | string | `build-check` | Kategorie für das aus `checked_command` abgeleitete Gate — nur ein Rückfall, wenn das Skript selbst keine `gate_category` liefert |

> **Platzhalter in Heredocs:** `{{ var }}` innerhalb eines quoted Heredocs (`<<'PY'`) landet als
> Quelltext im Interpreter — bei `PID = '{{ project_id }}'` wird jeder Wert mit `'` zu Python-Code.
> Werte deshalb auf der Shell-Zeile übergeben und im Skript aus der Umgebung lesen:
> `PROJECT_ID={{ project_id }} python3 - <<'PY'` … `os.environ['PROJECT_ID']`, Block-Option
> `"shell_escape_variables": true`. Shell-Blöcke erhalten sonst keine Umgebungsvariablen.

> **Begriff „Taint“:** Ein Run gilt als *getaintet* (gefärbt), sobald Daten von außen — etwa die
> Nutzlast eines Webhooks — in ihn geflossen sind. Ab dann setzt die Plattform auch Block-Ausgaben
> in Shell-Befehlen in Single-Quotes, weil ein Block fremde Bytes weitergeben kann. Die Regeln im
> Einzelnen stehen unten unter „Taint-Propagation“, das Vertrauensmodell in
> [deployment.md → Drei Vertrauensklassen](deployment.md#drei-vertrauensklassen-für-kontextwerte).
>
> **Nicht vertrauenswürdige Variablen:** Alles, was ein Webhook-Aufrufer liefert (Payload-Felder und
> die daraus abgeleiteten `git_*`-Felder), landet in Shell-Befehlen (`shell`, `foreach`, `while`, `retry`,
> `conditional` und in `switch` verschachtelte Blöcke, jeweils im Shell-Modus) grundsätzlich in Single-Quotes — auch ohne `shell_escape_variables`. Solche
> Platzhalter deshalb **unquoted** schreiben (`git checkout {{ git_branch }}`, nicht `"{{ git_branch }}"`),
> sonst stehen die Quotes doppelt. **Und das ist nicht nur Kosmetik: in eigenen Quotes ist das
> Escaping wirkungslos.** Beim Wert `x; id` wird `PREV='{{ previous_output }}'` zu
> `PREV=''x; id''` — die Shell führt das als `PREV=x` **und danach** `id` aus. Einen untrusted
> oder getainteten Platzhalter deshalb nie in Quotes setzen, sondern immer ohne Quotes schreiben
> (`PREV={{ previous_output }}`) **und den Block mit `"shell_escape_variables": true` versehen** —
> ohne Taint kommt der Wert sonst roh, und ein mehrzeiliger Output zerlegt den Befehl. Unquoted plus
> Escape-Option ist die einzige Form, die in beiden Fällen stimmt; der Test `TemplateHygieneGateTest`
> erzwingt sie für Built-in-Templates und den Pool. Werte aus `defaultContext` und System-Variablen
> bleiben ohne die Option roh.
>
> **Unbelegte Workflow-Variablen:** Bleibt eine Variable unbelegt, steht ihr Platzhalter wörtlich im
> Befehl; unquoted wäre `SOURCE_DIR={{ source_dir }}` dann ein Shell-Fehler. Idiom:
> `set -- {{ source_dir }}; SOURCE_DIR="$*"` — belegt liefert es den escapten Wert, unbelegt den
> wörtlichen Platzhalter, den man wie bisher mit `[ "$SOURCE_DIR" = "{{ source_dir }}" ]` erkennt.

> **Taint-Propagation auf Block-Ausgaben:** In einem webhook-getriggerten Run — und in jedem daraus
> gestarteten Sub-Workflow sowie nach einem Resume — werden auch `{{ previous_output }}`,
> `{{ block.<name>.output }}`, `{{ step_N_output }}` und die Items eines `items_command`
> single-quoted. Grund: ein Block kann die Payload in seine Ausgabe tragen, ohne den Platzhalter
> überhaupt zu nennen (Datei lesen, API aufrufen). Der Taint entsteht, sobald der Run irgendeinen
> untrusted Input trägt, und bleibt bis zum Ende des Runs bestehen. Diese Platzhalter also ebenfalls
> **unquoted** schreiben. In Runs ohne untrusted Input (Schedule, manueller Start, TodoTrigger,
> Chain) bleiben alle Block-Ausgaben unverändert roh.

> **`{{ previous_report }}` / `{{ open_todos }}`:** Beide werden **immer** als nicht
> vertrauenswürdig behandelt und in Shell-Blöcken deshalb **stets single-quoted** — in jedem Run,
> unabhängig vom Trigger und von `shell_escape_variables`. Grund: `previous_report` ist der Output
> eines früheren Runs (der eine Webhook-Payload verarbeitet haben kann), `open_todos` enthält
> Titel aus importierten Issues. Diese Platzhalter also **unquoted** schreiben
> (`REPORT={{ previous_report }}`, nicht `REPORT="{{ previous_report }}"`). In KI-Prompts wird der
> Wert weiterhin roh eingesetzt (dort wird nie escaped).
>
> **Beide Platzhalter lösen Taint-Propagation aus — auf zwei Wegen:**
>
> 1. **Substitution in ein Shell-Feld färbt den ganzen Lauf.** Sobald `{{ previous_report }}` oder
>    `{{ open_todos }}` wirklich in `command`, `items_command`, `condition`, … eines Shell-Blocks
>    eingesetzt wird, werden **ab diesem Punkt** auch alle Block-Ausgaben des Laufs
>    (`{{ previous_output }}`, `{{ block.<name>.output }}`, `{{ step_N_output }}`, `foreach`-Items)
>    single-quoted — genau wie in einem webhook-getriggerten Lauf.
> 2. **Ein KI-Prompt färbt die Ausgabe dieses Blocks — und ab ihr den Lauf.** Standen
>    Plattform-Daten im Prompt eines KI-Blocks (`simple`, `agentic`, `skill-capability` sowie die
>    Nicht-Shell-Zweige von `foreach`/`while`/`conditional`/`retry`/`switch`), gilt dessen Ausgabe
>    als nicht vertrauenswürdig: Ein folgender Shell-Block bekommt sie single-quoted. Sobald diese
>    Ausgabe verbucht ist, ist der Lauf gefärbt, und zwar klebrig wie in Fall 1 — auch alle
>    **weiteren** Block-Ausgaben und die daraus abgeleiteten Variablen (`{{ step_N_output }}`,
>    `foreach`-Items, `document`-Extrakte) werden ab da gequotet, und der Taint wird über
>    `exportTaint()` an Sub-Workflows und an ein Resume weitergereicht. Der Prompt allein färbt
>    nichts: Wird keine Ausgabe verbucht, bleibt der Lauf sauber.
>
> Ein Lauf, der die beiden Platzhalter überhaupt nicht verwendet, bleibt unverändert: Block-Ausgaben
> werden roh eingesetzt wie bisher.
>
> **Sichere Form für Folge-Blöcke:** `"shell_escape_variables": true` setzen und den Platzhalter
> **unquoted** über `set --` in eine Variable holen —
> `set -- {{ previous_output }}` und danach `ANTWORT="${1:-}"`. Das funktioniert in gefärbten wie in
> sauberen Läufen gleich und ist die Form, die die ausgelieferten Definitionen verwenden (das
> Gate `PlatformPromptQuotingGateTest` erzwingt sie dort).
>
> **Bestand prüfen statt raten:** `scripts/lib/shell_placeholder_audit.py <definitionen.json>`
> ordnet jeden Output-Platzhalter in Shell-Feldern nach seiner syntaktischen Rolle ein
> (gequotet · Heredoc-Inhalt · `set --` · Zuweisung · Wortauftrennung) und meldet nur, was
> wirklich eine Änderung braucht. Die Datei ist die Antwort von `GET /api/v1/workflows` oder
> eine Pool-Definition — das Skript ruft selbst nichts ab. Exit 1 = Befund.
> Heredoc-Inhalte dürfen dabei **nicht** escaped werden, sonst landen Anführungszeichen im Text.
>
> Vollständiges Trust-Modell (drei Vertrauensklassen, reservierte Kontextschlüssel, bekannte
> Grenzen): [architektur.md → Vertrauensklassen der Kontextwerte](architektur.md#workflow-härtung-optional).

```json
{
  "type": "shell",
  "config": {
    "command": "set -euo pipefail\ngit log --oneline -20"
  }
}
```

**Wann verwenden**: Skripte ausführen, Daten sammeln, Build-Befehle, Dateioperationen — alles was deterministische Shell-Ausführung benötigt.

---

### `agentic` — Zielgesteuerter KI-Agent

Führt eine autonome Goal-Reason-Act-Observe-Schleife aus. Der mächtigste, aber auch teuerste Block-Typ.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `goal` | string | **erforderlich** | Was der Agent erreichen soll |
| `success_criteria` | string | `""` | Woran Erfolg gemessen wird |
| `max_iterations` | int | 10 | Harte Iterationsobergrenze |
| `iteration_timeout_seconds` | int | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | int | 1800 | Gesamt-Block-Timeout (30 Min) |
| `max_total_tokens` | int | 200000 | Token-Budget (Input+Output über alle Iterationen) |
| `max_repeated_actions` | int | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | bool | false | Freigabe-Gate vor jeder Aktion |
| `timeout_seconds` | int | null | Claude-CLI-Timeout pro Aufruf (REASON + ACT) |

```json
{
  "type": "agentic",
  "config": {
    "goal": "Erstelle eine Portfolio-Webseite in portfolio/ basierend auf {{ filepath }}.",
    "success_criteria": "index.html existiert und enthält alle Sektionen.",
    "max_iterations": 8,
    "timeout_seconds": 600,
    "max_total_tokens": 300000
  }
}
```

**Wann verwenden**: Komplexe mehrstufige Aufgaben, die Urteilsvermögen erfordern — Dateien erstellen, Code reparieren, Projekte bauen. Siehe [Agentic Block Deep Dive](#8-agentic-block-deep-dive) für Details.

---

### `retry` — Wiederholung bei Fehler

Wiederholt einen Befehl bis zu N Mal mit einer Pause zwischen den Versuchen.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `command` | string | **erforderlich** | Auszuführender Befehl |
| `max_retries` | int | 3 | Maximale Wiederholungsversuche |
| `delay` | int | 5 | Pause zwischen Versuchen (Sekunden) |
| `exponential_backoff` | bool | false | Pause bei jedem Versuch verdoppeln |
| `execution_mode` | string | `"ai"` | `"ai"` (KI, Provider aus `provider` am Block/Workflow) oder `"shell"`; `"claude"` gilt weiter als `"ai"`, andere Werte werden abgelehnt |
| `timeout_seconds` | int | 300 | Ausführungs-Timeout pro Versuch |

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

**Wann verwenden**: Instabile Tests, netzwerkabhängige Operationen, API-Aufrufe die vorübergehend fehlschlagen können.

---

### `conditional` — If/Then/Else

Wertet eine Bedingung aus und führt einen von zwei Zweigen aus.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `condition` | string | **erforderlich** | Auszuwertende Bedingung |
| `then_command` | string | null | Befehl wenn Bedingung wahr (wenn null, wird nichts ausgeführt) |
| `else_command` | string | null | Befehl wenn Bedingung falsch |
| `execution_mode` | string | `"ai"` | `"ai"` (KI, Provider aus `provider` am Block/Workflow) oder `"shell"`; `"claude"` gilt weiter als `"ai"`, andere Werte werden abgelehnt |
| `timeout_seconds` | int | 300 | Ausführungs-Timeout |

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

**Bedingungsauswertung**: Im `shell`-Modus gilt Exit-Code 0 = wahr, ungleich 0 = falsch. Im `ai`-Modus (Standard) beantwortet die KI die Bedingung mit "TRUE" oder "FALSE".

**Dual-Modus**: `execution_mode` steuert sowohl die Bedingungsauswertung als auch die Ausführung von `then_command`/`else_command`. `timeout_seconds` gilt für beide.

**Wann verwenden**: Verzweigungslogik basierend auf Dateiexistenz, vorheriger Ausgabe, Umgebungsprüfungen.

---

### `switch` — Mehrweg-Verzweigung

Wertet einen Ausdruck aus und leitet die Ausführung zum passenden Case weiter. Wie eine switch/case-Anweisung.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `expression` | string | **erforderlich** | Auszuwertender Ausdruck (unterstützt `${var}` und `{{ var }}`) |
| `cases` | list | **erforderlich** | Liste von Case-Objekten mit `value` und `blocks` |
| `default` | list | null | Blöcke bei keinem Treffer |

Jeder Case hat:
- `value`: String zum Abgleich (Pipe-getrennte Alternativen: `"error|critical"`)
- `blocks`: Liste von Block-Definitionen bei Treffer

Verglichen wird ohne Leerzeichen und Zeilenumbrüche an Anfang und Ende, beim aufgelösten Ausdruck wie bei jedem `value`. Endet die Antwort eines KI-Blocks mit einem Zeilenumbruch, trifft `GREEN` also trotzdem den Case `GREEN`. Groß- und Kleinschreibung zählen weiter. Eingabe und Metadaten des Schritts (`expression`) zeigen den Rohwert.

Verschachtelte Blöcke in `cases` und `default` sind auf `shell` und `simple` beschränkt — andere Typen (auch `compress`/`compress-ai`) werden mit einer Fehlermeldung abgelehnt.

Ein `name` am verschachtelten Block macht dessen Ausgabe als `{{ block.<name>.output }}` in Folgeschritten verfügbar; ohne Namen bleibt nur `{{ previous_output }}`. Die Ausgabe des switch-Blocks selbst bleibt die Verkettung aller ausgeführten Case-Blöcke.

Die Blöcke eines Case laufen nacheinander und schieben dabei `{{ previous_output }}` weiter — wie die Iterationen einer `while`-Schleife. Der zweite Block eines Case sieht also die Ausgabe des ersten, nicht mehr die des Schritts vor dem switch (auch bei unbenannten Blöcken). Wer den Stand von vor dem switch braucht, benennt den betreffenden Block und referenziert `{{ block.<name>.output }}`.

Blocknamen sollten workflowweit eindeutig sein: bei Namensgleichheit gewinnt der zuletzt geschriebene Wert. Ein Case-Kind überschreibt also einen gleichnamigen früheren Block, und nach dem switch überschreibt dessen Gesamtausgabe wiederum ein gleichnamiges Kind.

Im Beispiel gibt ein früherer Block namens `ampel` nur `GREEN`, `YELLOW` oder `RED` aus. `gate_status` taugt als Ausdruck nicht: Es ist keine Laufvariable — der Run wertet die `gate_status:`-Marker erst am Ende aus (Run-Metadaten), `${gate_status}` bliebe leer und landete immer im `default`.

```json
{
  "type": "switch",
  "config": {
    "expression": "{{ block.ampel.output }}",
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

**Wann verwenden**: Mehrweg-Verzweigung wenn `conditional` (binäres if/else) nicht ausreicht. Routing basierend auf Status-Codes, Umgebungsnamen oder Gate-Ergebnissen.

---

### `foreach` — Ueber Items iterieren

Führt einen Befehl für jedes Item in einer Liste aus.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `variable_name` | string | `"item"` | Name der Schleifenvariable |
| `items_source` | string | `"list"` | `"command"` (Shell-Ausgabe) oder `"list"` (inline) |
| `items_command` | string | — | Shell-Befehl, ein Item pro Zeile (nur bei `items_source: "command"`) |
| `items` | list | — | Inline-Liste von Items (nur bei `items_source: "list"`) |
| `command` | string | **erforderlich** | Befehl pro Item |
| `execution_mode` | string | `"ai"` | `"ai"` (KI, Provider aus `provider` am Block/Workflow) oder `"shell"`; `"claude"` gilt weiter als `"ai"`, andere Werte werden abgelehnt |
| `timeout_seconds` | int | 300 | Timeout pro Iteration |
| `continue_on_error` | bool | `false` | `true`: fehlgeschlagene Items lassen den Block nicht scheitern — er endet COMPLETED, Folge-Blöcke laufen. Fehler werden im Output zusammengefasst (`N/M items failed: ...`). `false`: ein fehlgeschlagenes Item lässt den Block scheitern, die übrigen Items laufen trotzdem. In beiden Fällen listen die Step-Metadaten die Fehler (`failedItemCount`, `failedItems`, `totalItems`) |

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

**Hinweis**: `items_command` wird immer als Shell-Befehl ausgeführt (unabhängig von `execution_mode`), da es nur die Iterations-Ziele sammelt.

**Leere Item-Listen**: Leere und reine Whitespace-Zeilen werden verworfen — in der `items_command`-Ausgabe wie in inline `items`. Eine leere Ausgabe ergibt damit **null** Iterationen (Block endet COMPLETED, Metadaten `totalItems: 0`) statt einer Iteration mit leerem Item. Eine leere Liste ist also der saubere Weg, eine Schleife zu überspringen.

**Abgrenzung `continue_on_error` vs. `on_failure`**: `continue_on_error: true` toleriert Item-Fehler *innerhalb* des Blocks (Block endet COMPLETED, z. B. 29/30 Items erfolgreich → Folge-Blöcke wie Commit/Gate laufen). Das blockweite `on_failure: "continue"` lässt dagegen nur den Workflow trotz FAILED-Block weiterlaufen — der Block selbst bleibt FAILED.

**Fehlermeldung**: Scheitert der Block, beginnt die Fehlermeldung des Laufs mit den gescheiterten Items und dem Fehler des ersten, z. B. `Step 1 (foreach) failed: 1/37 items failed: erkner` und darunter `[erkner]: API Error: …`. Die Ausgabe des Blocks bleibt das Protokoll aller Items in ihrer Reihenfolge. Ein gescheitertes Item steht dort also zwischen erfolgreichen; aus deren Text allein („GATE PASS …“) lässt sich nicht ablesen, warum der Block scheiterte.

**Wann verwenden**: Verarbeitung mehrerer Dateien, Repos oder Items mit derselben Operation.

---

### `while` — Schleife bis Bedingung

Wiederholt einen Befehl bis eine Bedingung erfüllt ist oder die maximale Iterationszahl erreicht wird.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `condition_command` | string | null | Bedingung, die jede Iteration ausgewertet wird. Ohne `condition_command` läuft die Schleife bis `max_iterations` oder Kommando-Fehler |
| `command` | string | **erforderlich** | Befehl pro Iteration |
| `max_iterations` | int | 100 | Maximale Schleifendurchläufe |
| `execution_mode` | string | `"ai"` | `"ai"` (KI, Provider aus `provider` am Block/Workflow) oder `"shell"`; `"claude"` gilt weiter als `"ai"`, andere Werte werden abgelehnt |
| `timeout_seconds` | int | 300 | Ausführungs-Timeout pro Iteration |

```json
{
  "type": "while",
  "config": {
    "condition_command": "Bewerte die Qualität auf einer Skala von 1-10. Antworte NUR mit 'true' wenn unter 8, oder 'false' wenn 8 oder höher.",
    "command": "Verbessere den Text. Gib die vollständig überarbeitete Version aus.",
    "max_iterations": 3
  }
}
```

**Bedingungsauswertung**: Im `shell`-Modus gilt Exit-Code 0 = weitermachen, ungleich 0 = stopp. Im `ai`-Modus (Standard) gilt "false", "0" oder leere Antwort = stopp, alles andere = weitermachen.

**Dual-Modus**: `execution_mode` steuert sowohl die Bedingungsauswertung als auch die Befehlsausführung. `timeout_seconds` gilt für beide.

**Wann verwenden**: Iterative Verfeinerung, Polling bis eine Bedingung erfüllt ist.

---

### `approval` — Menschliche Freigabe

Pausiert die Ausführung und wartet auf menschliche Freigabe. Beantwortet wird im Dialog auf der Run-Seite oder auf der Seite **Freigaben** (`/approvals`). Deren Eintrag erscheint in der Seitenleiste und pulsiert, solange ein Lauf wartet.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `question` | string | `"Approval required"` | Freigabe-Frage (Markdown). Platzhalter wie `{{ block.X.output }}` werden vor der Anzeige aufgelöst |
| `options` | list | `["Yes", "Yes always", "No"]` | Verfügbare Antwortmöglichkeiten. `POST /approve` nimmt nur diese an (Groß-/Kleinschreibung egal), sonst 400 |
| `reject_options` | list | `["No", "Nein"]` | Welche Antworten eine Ablehnung sind und `on_no` auslösen (Groß-/Kleinschreibung egal) |
| `variable_name` | string | `"approval_answer"` | Kontext-Variable für die Antwort |
| `timeout_minutes` | int | 30 | Wartezeit |
| `mode` | string | `"ask"` | `"ask"` (interaktiv) oder `"auto"` (nutzt `auto_answer`) |
| `auto_answer` | string | — | Automatische Antwort wenn `mode` = `"auto"` |
| `on_no` | string | `"continue"` | Verhalten bei einer Ablehnung (siehe `reject_options`): `"continue"` (weiter, Status COMPLETED), `"fail"` (FAILED), oder `"skip"` (SKIPPED) |

```json
{
  "type": "approval",
  "config": {
    "question": "## Ergebnis\n\n{{ previous_output }}\n\nFortfahren?",
    "options": ["Yes", "No", "Abort"],
    "reject_options": ["No", "Abort"],
    "variable_name": "pr_decision",
    "timeout_minutes": 60,
    "on_no": "fail"
  }
}
```

**Eigene Optionen**: Ohne `reject_options` gelten nur „No“ und „Nein“ als Ablehnung. Wer andere Ablehnungen anbietet (im Beispiel „Abort“), muss sie in `reject_options` nennen. Sonst läuft der Workflow trotz `on_no` weiter.

**"Yes always"**: Wenn der Benutzer "Yes always" wählt, wird die Antwort für diese Frage für den Rest des Runs gemerkt — nachfolgende Approval-Blöcke mit derselben Frage werden automatisch beantwortet.

**Auto-Modus**: Mit `"mode": "auto"` und `"auto_answer": "Yes"` wird der Block sofort ohne Benutzerinteraktion beantwortet. Nützlich für CI/CD-Pipelines.

**Wann verwenden**: Vor destruktiven Operationen (Push, Deploy, Löschen), Quality Gates.

---

### `human-input` — Benutzereingabe sammeln

Pausiert die Ausführung, bis der Benutzer einen Wert eingibt oder ein Formular ausfüllt.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `prompt` | string | `"Please provide input:"` | Frage an den Benutzer (Platzhalter werden aufgelöst) |
| `input_type` | string | `"text"` | Feld im Dialog: `"text"` (Freitext), `"textarea"` (mehrzeilig), `"number"`, `"select"` (Auswahl aus `options`), `"checkbox"` (Antwort `"true"`/`"false"`), `"date"` (`JJJJ-MM-TT`). Die Antwort kommt immer als Text in die Variable |
| `variable_name` | string | `"user_input"` | Kontext-Variable für die Eingabe |
| `options` | list | `[]` | Auswahlmöglichkeiten (für `select`-Typ) |
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

Führt einen anderen Workflow als verschachtelten Schritt aus.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `workflow_id` | string | **erforderlich** | ID des auszuführenden Workflows |

**Limits**: Maximale Verschachtelungstiefe von 5 Ebenen. Zirkuläre Referenzen werden erkannt. 30-Minuten-Timeout.

**Kontext**: Der Child-Run bekommt die Variablen des Parent-Runs und dessen `{{ previous_output }}`, aber nicht dessen Plattform-Daten (`previous_report`, `open_todos`, `request_metrics`, `goal_status`/`goal_guard_*`, `audit_findings`): Die injiziert die Plattform dem Child-Run selbst, für ihn berechnet. Ein Wert unter einem dieser Namen wird auch aus keinem anderen Eingabe-Kontext übernommen. Der Taint des Parent-Runs reist mit.

**Marker**: Der Schritt reicht die letzte Ausgabe des Child-Runs nur durch (Step-Metadatum `derivedOutput: true`). `gate_status:`- und `todo_item:`-Marker darin werden ausschliesslich im Child-Run ausgewertet — der Parent-Run übernimmt sie bewusst nicht in seine Gate-Zusammenfassung oder TODO-Liste.

**Wann verwenden**: Komplexe Workflows aus kleineren, wiederverwendbaren zusammensetzen.

---

### `skill-capability` — Skill aufrufen

Ruft die Fähigkeit eines registrierten Skills auf.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `capability` | string | **erforderlich** | Fähigkeitsname (z.B. `"review_code"` aus `builtin-code-review`, `"summarize_text"` aus `builtin-smart-summarizer`) |
| `input` | string | `""` | Eingabedaten (optional, Default: leerer String) |
| `params` | map | `{}` | Zusätzliche Parameter |
| `timeout_seconds` | int | null | Claude-CLI-Timeout |
| `allow_writes` | bool | `false` | Erlaubt der Capability Schreibzugriff aufs Repo. Erforderlich für `writes:true`-Capabilities (test-generator, feature-implementer). Ohne `allow_writes` läuft der Block read-only (Tool-Whitelist Read/Grep/Glob/WebFetch/WebSearch/Task/TodoWrite). |

**Skill am Block:** Die Capability wird nur über die `skillIds` **des Blocks** aufgelöst (Feld neben `config`, siehe „Per-Block Skills“ oben); die Skills des Workflows zählen hier nicht. Ohne passenden Skill am Block läuft der Aufruf nur mit dem Capability-Namen als Aufgabe — ohne ihre Beschreibung und ohne den KI-Kontext des Skills, und die `writes`-Prüfung greift nicht (es bleibt die Read-Only-Whitelist, solange `allow_writes` fehlt).

```json
{
  "type": "skill-capability",
  "skillIds": ["builtin-code-review"],
  "config": {
    "capability": "review_code",
    "input": "Prüfe: {{ previous_output }}",
    "params": { "max_entries": 5 }
  }
}
```

**Wann verwenden**: Spezialisierte Skills nutzen (OCR, Web-Suche, Analyse).

**Sicherheit**: Capabilities deklarieren über `Capability.writes`, ob sie Dateien schreiben. Built-in `writes:true`: `builtin-test-generator/*`, `builtin-feature-implementer/*`. Ein Block, der eine `writes:true`-Capability aufruft ohne `"allow_writes": true`, fällt sofort fehl mit klarer Meldung. Für alle anderen Fälle wird der Host-CLI mit einer Read-Only-Tool-Whitelist gestartet (Defense-in-Depth).

**Fehler**: Endet die KI-CLI mit einem Exit-Code ungleich 0 (Limit, fehlende Anmeldung, Absturz), scheitert der Schritt wie ein `simple`-Block, und ihre Meldung steht in der Ausgabe. Weiter läuft der Workflow dann nur mit `"on_failure": "continue"`.

---

### `document` — Text aus Dokumenten extrahieren

Liest eine Datei (PDF, DOCX, XLSX, PPTX, HTML, TXT, EPUB, RTF, …) und extrahiert den Plain-Text via Apache Tika. Pure Java, keine nativen Abhängigkeiten. Ausgabe ist der extrahierte Text als `previous_output` (und optional als benannte Variable). Kosten: 0 Tokens.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `file_path` | string | **erforderlich** | Pfad zur Datei. Absolut oder relativ zum Projekt-Pfad. Unterstützt Templates (`{{ variable }}`). |
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

### `todo-create` / `todo-update` / `todo-close` — Native TODO-Operationen

Legen TODOs direkt über den TodoService an bzw. ändern sie — **kein REST-Self-Call, kein DB-Zugriff, 0 Tokens**. Ersetzen das Anti-Pattern `curl POST /api/v1/todos` aus shell-/agentic-Blöcken.

**`todo-create`**:

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `title` | string | **erforderlich** | Titel (Template-fähig) |
| `description` | string | null | Markdown-Beschreibung (Template-fähig) |
| `priority` | string | `MEDIUM` | HIGH / MEDIUM / LOW / CRITICAL |
| `tags` | list oder string | null | Liste oder kommagetrennt |
| `project_id` | string | Kontext-Variable `project_id` | Ziel-Projekt |
| `source` | string | `WORKFLOW` | USER / AI / WORKFLOW |
| `source_agent` | string | `workflow` | Quell-Agent-Label |
| `variable_name` | string | `todo_id` | Kontext-Variable, die die neue TODO-ID erhält |

**`todo-update`**: `id` (erforderlich, Template-fähig — z. B. `{{ todo_id }}`), optional `title`/`description`/`priority`/`tags` — nur gesetzte Felder werden geändert, leere Texte gelten als nicht gesetzt.

**`todo-update` mit `updates`** (statt `id`): ändert viele TODOs **des Lauf-Projekts** in einem Schritt. `updates` ist eine JSON-Liste (als Text, Template-fähig, meist `{{ block.<shell>.output }}`; ist die ganze Ausgabe kein JSON, gilt ihre letzte nicht-leere Zeile – ein Skript darf also vorher Zeilen für Menschen ausgeben):

| Schlüssel je Eintrag | Bedeutung |
|---|---|
| `id` | volle UUID eines TODOs im Projekt des Laufs |
| `status` | `OPEN`, `IN_PROGRESS`, `DONE` oder `BACKLOG` (DONE wie beim Schließen, auch für Audit-TODOs) |
| `add_tags` / `remove_tags` | Tags hinzufügen / entfernen (je höchstens 10, Zeichen `A–Z a–z 0–9 _ : . -`) |
| `note` | Vermerk, an die Beschreibung angehängt als `— <Datum> · <Workflow> (Lauf <id8>): <note>` (≤ 1000 Zeichen, Marker entschärft) |
| `require_tags` / `require_status` | Bedingung – sonst wird der Eintrag übersprungen |

Höchstens 50 Einträge. Kaputtes JSON, keine Liste oder ein unbekannter Schlüssel lassen den Schritt scheitern, bevor etwas geschrieben ist; ein unpassender Eintrag (ungültige ID, fremdes Projekt, Bedingung nicht erfüllt) wird mit Grund übersprungen. Ausgabe: `✓ N aktualisiert, M übersprungen` und je Eintrag eine Zeile.

**`todo-close`**: `id` (erforderlich, Template-fähig) — setzt den Status auf DONE.

```json
[
  { "type": "todo-create", "config": { "title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"] } },
  { "type": "simple", "config": { "command": "Behebe das Problem ..." } },
  { "type": "todo-close", "config": { "id": "{{ todo_id }}" } }
]
```

**Wann verwenden**: Deterministische Einzel-TODO-Operationen (Tracking-TODO anlegen und am Ende schließen). Für **dynamisch viele** TODOs aus allgemeinen Analyse-Workflows `todo_item:`-Marker verwenden (siehe [TODO-Marker](#todo-marker-todo_item)). Strukturierte v4-Audit-Templates verwenden stattdessen das versionierte `mysuricates:audit-result:v1`-Envelope; das Audit Center verwaltet daraus Findings und TODO-Verknüpfungen.

---

### `compress` / `compress-ai` — Ausgaben sichtbar verdichten

Verdichten die Ausgabe **genau eines** früheren Blocks, bevor sie an Folgeschritte geht. Das Original bleibt unverändert unter `{{ block.<quelle>.output }}`; der Komprimierungsblock hat seine eigene Ausgabe `{{ block.<name>.output }}`. Die Komprimierung ist **endgültig** — wer alles sehen will, referenziert das Original.

**Ausgabeformat** (beide Typen):

```
## backend_tests — FAILED · exit 1 · 312 s · 740.808 → 17.950 Zeichen
Stufen: text.normalize (−4.102 Zeichen) · lines.filter (−8.914 Zeilen) · text.truncate (−96.004 Zeichen)

<verdichteter Inhalt>
```

Status und Exit-Code stammen aus den gespeicherten Schrittdaten, nie aus dem Logtext. Gate- und TODO-Marker in Komprimierungs-Ausgaben werden beim Run-Abschluss **nicht** ausgewertet — maßgeblich bleibt der Originalschritt.

Als `source` taugt jeder Name, der sich auch als `{{ block.<name>.output }}` auflösen lässt — Top-Level-Blöcke und benannte `switch`-Case-Blöcke (2718c20e). Die Kopfzeile zeigt dann Status, Exit-Code und Dauer des Case-Schritts, nicht die des ganzen `switch`. Verschachtelte Blöcke ohne Namen (`foreach`-, `while`-Durchläufe) bewegen nur `previous_output`; dort ist `"previous"` die passende Quelle.

**`compress`** (Regeln, 0 Tokens):

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `source` | string | **erforderlich** | Blockname eines früheren Blocks (auch eines benannten `switch`-Case-Blocks) oder `"previous"` |
| `stages` | Liste | **erforderlich** | Stufen `{ "op": …, … }`, laufen der Reihe nach |
| `max_chars` | int | 20000 | harte Obergrenze inkl. Kopfzeilen; darüber wird head/tail gekürzt |
| `timeout_seconds` | int | 30 | Zeitbudget (schützt vor aufwendigen Regex) |

| Stufe | Optionen | Wirkung |
|---|---|---|
| `text.normalize` | — | ANSI, CR, Leerraum am Zeilenende, Leerzeilen-Folgen |
| `text.truncate` | `max_chars`, `keep` (`head`/`tail`/`head_tail`) | kürzen an Zeilengrenzen, Hinweis zählt zum Limit |
| `lines.filter` | `include`, `exclude` (Regex-Listen), `before`, `after` | Treffer mit Kontext behalten / Zeilen entfernen |
| `lines.replace` | `pattern`, `replacement` | Regex-Ersetzung je Zeile (z. B. Zeitstempel weg) |
| `lines.dedupe` | `count`, `consecutive_only` | Wiederholungen entfernen, optional `37× …` |
| `lines.head` / `lines.tail` | `lines` | erste / letzte n Zeilen |
| `markdown.section` | `headings` (Regex, ohne Groß/Klein), `level` | nur passende Abschnitte |
| `json.select` | `paths` (`items[].title`, `meta.count`) | nur diese Felder, gültiges JSON |
| `json.limit` | `path`, `count` | Array kürzen |
| `json.sort` | `path`, `by`, `order` | Array sortieren, fehlende Werte zuletzt |
| `json.to_lines` | `path`, `template` (`{title} — {url}`) | ein Eintrag pro Zeile — einfache geschweifte Klammern |

JSON-Stufen brauchen JSON als Eingabe und müssen vor Textstufen stehen.

**`compress-ai`** (ein KI-Aufruf ohne Werkzeuge): `source` (erforderlich), `instruction` (reiner Text, Platzhalter werden **nicht** aufgelöst), `max_chars` (Default 4.000, Minimum 500), `model`, `effort`, `timeout_seconds`. Läuft in einem frischen, privaten Arbeitsverzeichnis je Aufruf (kein Projekt-`CLAUDE.md`, keine MCP-Server, keine Plugin-Hooks — `--tools ""`, `--strict-mcp-config`, `--setting-sources local`). Wegen `--setting-sources local` gelten Claude-Einstellungen auf Benutzerebene (z. B. `env` oder `apiKeyHelper` in `~/.claude/settings.json`) für diesen Aufruf **nicht**. Nur Provider `CLAUDE`; ein abweichender `provider` schlägt fehl — läuft der Workflow mit einem anderen Provider, im Block `"provider": "CLAUDE"` setzen. Eingaben über der Einstellung `compress-ai-max-input-chars` (Default 60.000) scheitern ohne Aufruf — davor einen `compress`-Block setzen.

> **Grenze — `--bare` ist die stärkere Isolation, aber nicht nutzbar (TODO edb38a38):** Die Claude-CLI
> kennt mit `--bare` einen Minimalmodus, der zusätzlich Hooks, LSP, Plugin-Sync, Auto-Memory und die
> `CLAUDE.md`-Erkennung abschaltet. Er kommt hier trotzdem nicht zum Einsatz, weil `--bare` laut
> `claude --help` auch die **Keychain-Leseoperationen** überspringt: „Anthropic auth is strictly
> `ANTHROPIC_API_KEY` or `apiKeyHelper` via `--settings` (OAuth and keychain are never read)". Auf
> einem Host, der wie üblich per OAuth angemeldet ist, bricht der Aufruf deshalb ab — auf dem
> Dev-Host geprüft: `claude -p --bare "Antworte mit OK"` → `Not logged in · Please run /login`
> (Exit-Code 1), während `claude -p --setting-sources local "Antworte mit OK"` → `OK` liefert.
> `--bare` wäre also nur für Instanzen möglich, die ausschließlich mit `ANTHROPIC_API_KEY` arbeiten.

```json
[
  { "type": "shell", "name": "backend_tests", "config": { "command": "mvn -q test", "on_failure": "continue", "checked_command": true } },
  { "type": "compress", "name": "backend_kompakt", "config": { "source": "backend_tests", "stages": [
      { "op": "text.normalize" },
      { "op": "lines.filter", "exclude": ["^\\s+at "] },
      { "op": "lines.dedupe", "count": true },
      { "op": "text.truncate", "max_chars": 18000, "keep": "head_tail" } ] } },
  { "type": "simple", "name": "report", "config": { "command": "Bewerte:\n{{ block.backend_kompakt.output }}" } }
]
```

**Nicht erlaubt:** als verschachtelter Block in `switch` (wird abgelehnt).

---

### `dashboard-report` — Dashboard-Ergebnis ohne Marker melden

Füllt das **Dashboard-Ergebnis** dieses Runs direkt aus der Block-Config — dieselben Felder, dieselben Grenzen und dieselbe Prüfung wie der Marker `dashboard_widget:` (eine JSON-Zeile in der Ausgabe eines Schritts, siehe [api-referenz.md](api-referenz.md#marker-dashboard_widget-im-workflow)), aber **ohne Markerzeile und ohne Textsuche**. Deterministisch: kein KI-Aufruf, keine Shell, 0 Tokens.

Modul: `dashboard-report` (Standard an, abschaltbar). Module sind optionale Funktionen, die die Startkonfiguration ein- oder ausschaltet, hier per `automation.modules.dashboard-report: false` ([architektur.md → Module-System](architektur.md#module-system-startkonfiguration)). Ist es aus, verweigert der Block und der Typ wird im Editor nicht angeboten.

| Config-Schlüssel | Typ | Default | Beschreibung |
|---|---|---|---|
| `status` | string | **erforderlich** | `GREEN`, `YELLOW` oder `RED` (Template-fähig) |
| `title` | string | – | Titel, ≤ 80 Zeichen |
| `summary` | string | – | Zusammenfassung, ≤ 500 Zeichen |
| `kpis` | Liste von Objekten | – | ≤ 6 × `{ label (≤ 40), value (≤ 40, String), trend? (UP/DOWN/FLAT) }` |
| `items` | Liste von Strings | – | ≤ 10 Einträge, je ≤ 200 Zeichen |

Im Beispiel gibt ein früherer Block `ampel` nur `GREEN`, `YELLOW` oder `RED` aus (wie im switch-Beispiel). `{{ gate_status }}` gibt es während des Laufs nicht — der Platzhalter bliebe unaufgelöst und ließe den Block scheitern.

```json
{ "type": "dashboard-report", "name": "report", "config": {
  "status": "{{ block.ampel.output }}",
  "title": "Nightly QA",
  "kpis": [{ "label": "Hunts", "value": "{{ hunt_count }}", "trend": "UP" }],
  "items": ["{{ finding_1 }}"],
  "summary": "{{ block.analyse.output }}"
} }
```

**Regeln:**

- Alle Werte sind Template-fähig. Ein Platzhalter, der zur Laufzeit **leer** ist, lässt das Feld entfallen (bei `status` ist das ein Fehler). Ein KPI-Objekt entfällt nur, wenn **alle** seine Werte leer sind; ein halb gefülltes lässt den Block scheitern.
- Ein Platzhalter, der **nicht aufgelöst** werden konnte (unbekannte Variable — der Resolver lässt ihn wörtlich stehen), **lässt den Block scheitern** und nennt das Feld. Lieber ein roter Schritt als `{{ hunt_count }}` als Kennzahl auf dem Dashboard.
- Jeder Verstoß gegen den Vertrag (zu lang, unbekanntes Feld in einem KPI, Zahl statt String, unsichtbare Zeichen) lässt den Schritt **FAILED** werden; die Meldung nennt das Feld (`kpis[2].value`), nie den Wert.
- Unbekannte Schlüssel auf oberster Ebene werden ignoriert (dort stehen legitim Plattform-Schlüssel wie `timeout_seconds`); unbekannte Schlüssel **innerhalb** eines KPI-Objekts werden abgelehnt.
- Die Schritt-Ausgabe enthält nur Zählwerte, nie Vertragswerte — auf Englisch wie bei den übrigen Blöcken, etwa `✓ Dashboard report GREEN · title · 4 KPIs · 0 items`.

**Vorrang vor dem Marker:** Hat ein `dashboard-report`-Block ein Ergebnis geschrieben, wird der Marker `dashboard_widget:` in **keiner** Schritt-Ausgabe mehr ausgewertet. Der Block ist der bewusst konfigurierte Weg; ohne diese Regel könnte eine Markerzeile in der Ausgabe eines späteren KI-Schritts den Bericht überschreiben. Zwei `dashboard-report`-Blöcke in einem Workflow: der zuletzt ausgeführte gewinnt.

**Im Editor** hat der Block zusätzlich zur JSON-Bearbeitung ein Formular, mit einem optionalen Knopf „Vorschlag aus dem letzten Run" (ein KI-Aufruf; Einstellungen → Ausführung, braucht den Anbieter `CLAUDE`). Der Vorschlag wird angezeigt und erst nach „Übernehmen" in die Config geschrieben.

**Wann verwenden**: immer dann, wenn ein Workflow ein Dashboard-Ergebnis melden soll. Den Marker nur noch, wenn die Werte erst im Text einer KI entstehen und es keine Variable dafür gibt.

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
| `{{ trigger_source }}` | Auslöser des Runs: `MANUAL`, `RETRY`, `RESUME`, `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `TODO_RESOLUTION`, `COMPLETION_TRIGGER`, `CHAIN`, `SUB_WORKFLOW`, `ASSISTANT`, `AUTO_REPAIR`, `WISH` |
| `{{ previous_report }}` | Längster Non-Shell-Step-Output des letzten COMPLETED-Runs desselben Workflows (max. 3000 Zeichen), oder `"(kein vorheriger Report vorhanden)"`. Ein Audit-Envelope ist abgeschnitten, Plattform-Marker verlieren ihren Doppelpunkt (`gate_status GREEN` bleibt lesbar, wird aber nicht mehr als Urteil gewertet, wenn ein Modell die Zeile wiedergibt), Blockgrenzen wie `</audit_findings>` stehen als `[audit_findings]` da. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ open_todos }}` | Markdown-Liste offener/in-Bearbeitung-TODOs des Projekts in einem `<open_todos>…</open_todos>`-Block, oder `"(keine offenen TODOs)"`. Titel und Tags sind auf eine Zeile geglättet, auf 200/80 Zeichen gekürzt und von Plattform-Markern (`todo_item:`, `gate_*:`, `dashboard_widget:`) befreit — sie können aus importierten Issues stammen und gelten als Daten, nicht als Anweisung. Maximal 150 Einträge. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ audit_findings }}` | **Nur in Audit-Workflows** (gültiges `metadata.audit`): die bekannten Befunde des eigenen Audits im Projekt — offen, Wiedervorlage, akzeptiert, verworfen — in einem `<audit_findings>…</audit_findings>`-Block, je Befund eine Zeile mit Status, Schwere, `ruleKey`, `subject`, Titel und Einordnung, höchstens 250 Zeilen und 60 000 Bytes (eingeordnete zuerst, der Rest als Anzahl je Status); ohne Befunde `"(keine bekannten Befunde)"`. In anderen Workflows gibt es die Variable nicht, auch nicht über die Variablen-Übergabe an einen Sub-Workflow; der Platzhalter bliebe dort wörtlich stehen (Analyzer-Regel `audit-only-placeholder`). Format und Zweck: [audit-workflow-protocol.md](audit-workflow-protocol.md) → „Bekannte Befunde im Prompt“. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |
| `{{ todo_details }}` | **Nur mit Filter** (`todo_details_tags` und/oder `todo_details_exclude_tags` im Lauf-Kontext, meist `defaultContext`): TODOs des Lauf-Projekts **mit Beschreibung** als JSON-Liste im Block `<todo_details>…</todo_details>` – je Eintrag `id`, `status`, `priority`, `tags`, `title`, `complexity`, `suggestedEffort`, `openQuestion`, `updatedAt`, `description`. Weitere Filter: `todo_details_status` (OPEN/IN_PROGRESS/BACKLOG, Vorgabe OPEN), `todo_details_first_tags` (nach vorn), `todo_details_limit` (≤ 150, Vorgabe 40), `todo_details_chars` (je Beschreibung ≤ 8000, Vorgabe 1500); die ganze Liste höchstens 100 000 Bytes UTF-8, damit ein Shell-Befehl unter der Grenze von 128 KiB je Argument bleibt. Tags vergleicht die Plattform genau; `batch:*` heißt „jedes Tag mit diesem Anfang“. Reihenfolge: `first_tags`, dann Priorität, dann Alter. Titel geglättet, Beschreibungen zeilenweise von Markern befreit. Gilt immer als nicht vertrauenswürdig → in Shell-Blöcken stets single-quoted, deshalb unquoted schreiben |

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
| `{{ goal_status }}` | Block `<goal_status>…</goal_status>` mit Ziel, Zieldatum, Fortschritt, Meilensteinen samt Zustand (`OPEN`/`DUE_SOON`/`OVERDUE`/`REACHED`, `REOPENED`), überfälligen und bald fälligen (≤ 3 Tage) aktiven TODOs. Titel geglättet, gekürzt und von Plattform-Markern (`todo_item:`, `gate_*:`, `dashboard_widget:`) und Blockgrenzen befreit; je Liste höchstens 20 Zeilen |
| `{{ goal_guard_status }}` | `RED` (etwas überfällig oder Zieldatum mit offener Arbeit vorbei), `YELLOW` (etwas in ≤ 3 Tagen fällig), sonst `GREEN` |
| `{{ goal_guard_title }}` | `Ziel: <Titel>`, höchstens 80 Zeichen |
| `{{ goal_guard_summary }}` | Eine Zeile: `7/12 erledigt (58 %) · 2 überfällig · 1 bald fällig · nächster Meilenstein „Beta“ am 2026-10-01`, höchstens 500 Zeichen |
| `{{ goal_guard_progress }}` | `7/12 (58 %)` |
| `{{ goal_guard_overdue }}` / `{{ goal_guard_due_soon }}` | Anzahl überfälliger bzw. bald fälliger TODOs plus Meilensteine |
| `{{ goal_guard_item_1 }}` … `{{ goal_guard_item_5 }}` | Die dringendsten Punkte (überfällige Meilensteine, überfällige TODOs, Zieldatum, bald Fälliges), je höchstens 200 Zeichen; freie Plätze sind leer und fallen in `dashboard-report` weg |
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

Faustregel: `high`/`xhigh` für Analyse-/Audit-Blöcke, `low` für mechanische Transformationen. Der gesetzte Effort ist im Step-Metadata (`aiRequest.effort`) der Run-Details sichtbar, wenn der Anbieter ihn anwendet (bei Codex fehlt er). Ohne gesetzten Effort gilt der Standard der CLI; den meldet die CLI nicht, `aiRequest.effort` fehlt dann.

Tokens im Step-Metadata: `inputTokens` ist bei Claude nur der Teil der Eingabe, den der Prompt-Cache nicht bedient hat, bei einem Claude-Code-Aufruf oft einstellig. Der Rest steht in `cacheReadInputTokens` (aus dem Cache gelesen) und `cacheCreationInputTokens` (in den Cache geschrieben); die Insights-Karte des Laufs zeigt beides als „+… Cache“. Codex und Pi melden keine echten Zahlen (`estimated: true`), dort fehlen die Cache-Schlüssel. Das Budget `max_total_tokens` des `agentic`-Blocks zählt die Cache-Tokens nicht mit.

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
- Wrapper-Blöcke (`retry`, `foreach`, `while`, `conditional`, `switch`, `skill-capability`) übernehmen `aiRequest`, `provider` und `requestedModel` ihres **ersten inneren KI-Aufrufs** in ihren Sammel-Step (Anbieter und Modell sind pro Block konstant); die Run-Seite zeigt sie als Pills. Zusätzliche Schlüssel: `foreach` → `totalItems` + `items` (`{index, item, status, durationMs}`, max. 500, Item-Label auf 120 Zeichen gekürzt), `while` → `iterationCount` + `iterations`, `retry` → `attempts` + `maxAttempts`, `conditional` → `conditionMet`, `switch` → `expression` + `matchedCase`. Reine Shell-Iterationen tragen kein `aiRequest`.

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

| Config-Schlüssel | Default | Zweck |
|---|---|---|
| `max_iterations` | 10 | Harter Stopp nach N Iterationen |
| `iteration_timeout_seconds` | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamt-Timeout (30 Min) |
| `max_total_tokens` | 200.000 | Token-Budget (Input+Output) |
| `max_repeated_actions` | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | false | Menschliches Gate vor jeder Aktion |

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

| Feld | Pflicht | Beschreibung |
|------|---------|--------------|
| `provider` | Ja | Provider-ID: `google-calendar`, `trello`, etc. |
| `action` | Ja | Action-Name, z.B. `list_events`, `create_card` |
| `params` | Nein | Parameter als Key-Value, unterstützt `{{ template_vars }}` |
| `connection` | Nein | Benannte Verbindung des Providers, z.B. `arbeit` — wenn mehrere Konten/Dienste desselben Providers eingerichtet sind (Integrationen-Seite → „Verbindungen“). Default `default`, Platzhalter erlaubt |

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
| `hunt-quality-pipeline` | Mehrstufige Pipeline | CODE |
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

Neben **Vorlagen** (fertige Aufgaben) und dem **Pool** gibt es **Blaupausen**: leere Gerüste mit lauffähigen Platzhaltern, die zeigen, wie sich Block-Typen verknüpfen lassen – Formular → Verzweigung, Schleife mit Freigabe, Plan B bei Fehlern. Im Anlege-Dialog sind sie der erste Reiter (vor Vorlagen und Pool), gruppiert nach *Grundverknüpfungen*, *Kombinationen* und *Mit KI*; ganz oben steht der leere Workflow. Man startet eine Blaupause, sieht im Lauf, wie die Schritte zusammenspielen, und ersetzt dann die Platzhalter durch eigene Befehle.

- **Ablage:** `src/main/resources/templates/blueprints/*.json`, gleiches Format und gleicher Loader wie Vorlagen, `kind: BLUEPRINT` (Spalte `workflow_templates.kind`). Die API liefert beide Arten gemeinsam mit dem Feld `kind`.
- **Immer zweisprachig** (`"i18n": ["de","en"]`), `id` mit Präfix `blueprint-`, Kategorie `BASICS`, `COMBINATIONS` oder `AI`.
- **Platzhalter:** Shell-Schritte tun etwas Harmloses und tragen einen Kommentar `# placeholder - …`. Arbeitsdateien liegen in `/tmp/mysuricates-blueprints/<Run-ID>`, der letzte Schritt räumt sie weg.
- **0 Tokens außerhalb von `AI`:** keine KI-Block-Typen; `foreach`, `while`, `conditional` und `retry` brauchen `"execution_mode": "shell"`, sonst fragen sie die KI. Das prüft `BlueprintGateTest`.
- **Erklärtext je Schritt:** `config.description` (zweisprachig) sagt, was der Schritt im Muster tut und was man ersetzt. Der Editor zeigt ihn unter dem Block, die Run-Ansicht unter dem Step-Kopf (`metadata.blockDescription`). Das gilt für jeden Workflow, denn `description` ist ein gemeinsamer Schlüssel ohne Wirkung auf die Ausführung. Ausgenommen sind `todo-create` und `todo-update`, dort ist `description` der Text des TODOs.

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
