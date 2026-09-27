# AGENTS.md — Codex Workflow-Referenz

> **Scope:** Kuratierte Kurzreferenz für externe Agents. Die vollständige REST-API-Referenz steht in [docs/api-referenz.md](docs/api-referenz.md).

Anleitung für OpenAI Codex CLI, um eigenständig Workflows und Chains über die REST API anzulegen und auf der Test-Instanz zu validieren.

## Voraussetzungen

- **Dev-Instanz** läuft auf `localhost:9090`
- **Test-Instanz** läuft auf `localhost:8090` (via `POST /api/v1/testing/start`)
- **API-Keys** für Dev und Test sind separate Datenbanken

### API-Key bereitstellen

```bash
export DEV_KEY="<dev-api-key>"
export TEST_KEY="<test-api-key>"
```

Alle Beispiele mit API-Key rufen `curl --noproxy '*'` auf: Bei gesetztem `http_proxy` schickt curl
sonst auch Anfragen an `localhost` über den Proxy – samt Key. Beim Kopieren die Option behalten.

## TODO-First Arbeitsweise

- Bei Aufgaben mit Code-, UI-, Config- oder Commit-Bezug zuerst ein MySuricates-TODO ueber die REST API anlegen oder ein vorhandenes TODO als Arbeitsanker referenzieren.
- Danach die Umsetzung explizit an dieser TODO-ID ausrichten und den Status mindestens auf `IN_PROGRESS` setzen.
- Vor einer Fertigmeldung oder vor einem Commit immer die zugehoerige TODO-ID nennen.
- Kein "fertig", "umgesetzt" oder "committed" behaupten, wenn noch kein TODO existiert oder die Arbeit nicht diesem TODO zugeordnet ist.
- Commits werden anschliessend gemeinsam und bewusst gemacht, nicht stillschweigend.

### TODO anlegen

```bash
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/todos \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{
    "title": "Kurzbeschreibung der Arbeit",
    "description": "Worum es konkret geht",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "projectId": "<project-uuid>",
    "source": "AI",
    "sourceAgent": "Codex CLI"
  }'
```

### TODO in ein Ziel einordnen und einschaetzen

Ziele buendeln TODOs projektuebergreifend (`/api/v1/goals`). Wer ein TODO einschaetzt, schreibt das
ueber einen eigenen Endpunkt – `ratedBy: "AI"` markiert die Einschaetzung als maschinell, ein Mensch
kann sie spaeter ueberschreiben:

```bash
curl -s --noproxy '*' -X PATCH http://localhost:9090/api/v1/todos/<todo-id>/planning \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" \
  -d '{"goalId": "<goal-id>", "complexity": "M", "suggestedEffort": "high",
       "ratingNote": "Viele Dateien, klare Vorlage", "openQuestion": "Welcher Stil?", "ratedBy": "AI"}'

# Reihenfolge im Ziel setzen (Rang 1..n)
curl -s --noproxy '*' -X PUT http://localhost:9090/api/v1/goals/<goal-id>/order \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"todoIds": ["<id-1>", "<id-2>"]}'
```

Woher die TODOs eines Ziels kommen und welche Workflows sie abarbeiten (Herkunft, Fix-Workflow und
passende TodoTrigger, letzter Fix-Lauf), liefert die Relationskarte; passende TODOs ohne Ziel findet
`goalId=none`:

```bash
curl -s --noproxy '*' http://localhost:9090/api/v1/goals/<goal-id>/relations -H "X-API-Key: $DEV_KEY"
curl -s --noproxy '*' "http://localhost:9090/api/v1/todos?goalId=none&status=OPEN&tag=release&size=20" -H "X-API-Key: $DEV_KEY"
```

Ein Ziel ist ausdruecklich einem oder mehreren Projekten zugeordnet (Reihenfolge = Gliederung). Ein
TODO aus einem neuen Projekt bringt sein Projekt beim Zuordnen automatisch mit; entfernen geht nur,
solange im Ziel nichts mehr daran haengt (sonst 409, `?cascade=true` loescht dessen Teilziele und loest
die TODOs):

```bash
curl -s --noproxy '*' "http://localhost:9090/api/v1/goals?status=ACTIVE&projectId=<project-id>" -H "X-API-Key: $DEV_KEY"
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/goals/<goal-id>/projects \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"projectId": "<project-id>"}'
curl -s --noproxy '*' -X PUT http://localhost:9090/api/v1/goals/<goal-id>/projects/order \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"ids": ["<project-id-1>", "<project-id-2>"]}'
curl -s --noproxy '*' -X DELETE "http://localhost:9090/api/v1/goals/<goal-id>/projects/<project-id>?cascade=false" \
  -H "X-API-Key: $DEV_KEY"
```

Teilziele (im Code `milestones`) gehoeren genau einem Projekt des Ziels; das Datum ist optional,
die Reihenfolge je Projekt explizit. Zugeordnet wird ueber `planning` (`milestoneId`, nur Teilziele des
eigenen Ziels UND des eigenen Projekts – sonst 400). Der Zustand (`OPEN`/`DUE_SOON`/`OVERDUE`/`REACHED`,
ohne Datum nur `OPEN`/`REACHED`) wird beim Lesen berechnet. Der Ziel-Waechter legt einmalig einen
Workflow aus der Vorlage `goal-guard` mit taeglichem Zeitplan an; ausschalten pausiert nur den Zeitplan:

```bash
# projectId Pflicht, ausser das Ziel hat genau ein Projekt; dueDate optional
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/goals/<goal-id>/milestones \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"title": "Beta", "dueDate": "2026-10-01", "projectId": "<project-id>"}'
# Datum entfernen ("" = leeren); Projekt wechseln nur, solange das Teilziel leer ist
curl -s --noproxy '*' -X PUT http://localhost:9090/api/v1/goals/<goal-id>/milestones/<milestone-id> \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"dueDate": ""}'
# Reihenfolge der Teilziele EINES Projekts
curl -s --noproxy '*' -X PUT http://localhost:9090/api/v1/goals/<goal-id>/milestones/order \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"ids": ["<milestone-id-1>", "<milestone-id-2>"]}'
curl -s --noproxy '*' -X PATCH http://localhost:9090/api/v1/todos/<todo-id>/planning \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"milestoneId": "<milestone-id>"}'
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/goals/<goal-id>/guard \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" -d '{"enabled": true, "locale": "de"}'
```

Im Workflow selbst kommen Ziel-Daten nie per REST, sondern als Kontextvariablen `{{ goal_status }}`
und `{{ goal_guard_* }}`, sobald der Lauf `goal_id` traegt ([docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md)
→ „Ziel-Kontext“).

Referenz: [docs/api-referenz.md](docs/api-referenz.md) → „Ziele“.

## Cypress E2E gegen Dev oder Test

Die Cypress-Tests koennen jetzt gegen **Dev** (`9090`) oder **Test** (`8090`) laufen. Wichtig ist, dass **Frontend-Proxy und Cypress immer dasselbe Ziel verwenden**.

### Zielwahl

- Standard: kein `CYPRESS_TARGET` gesetzt -> **Dev**
- `CYPRESS_TARGET=test` -> **Test-Instanz**

### Dev-Target

```bash
cd ai-automation-frontend

# Frontend proxied /api und /ws nach localhost:9090
npm run dev

# Cypress-Helper sprechen ebenfalls localhost:9090/api/v1
npm run test:e2e
```

Der Dev-API-Key wird fuer Cypress aus der lokalen Testkonfiguration bereitgestellt; alternativ kann `CYPRESS_API_KEY` gesetzt werden.

### Test-Target

```bash
# Test-Instanz mit bekanntem privaten API-Key starten
cd ai-automation-backend
AUTOMATION_API_KEY=<test-api-key> docker compose -f docker-compose.test.yml up -d

# Optional: Definitionen von Dev nach Test synchronisieren
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{}'

# Frontend und Cypress beide auf Test schalten
cd ../ai-automation-frontend
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run dev:e2e:test
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run test:e2e:test
```

Hinweise:
- `CYPRESS_TEST_API_KEY` ist fuer die Test-Instanz gedacht; `CYPRESS_API_KEY` ueberschreibt beide Ziele global.
- Falls `AUTOMATION_API_KEY` beim Test-Container gesetzt wurde, kann derselbe Wert fuer `CYPRESS_TEST_API_KEY` verwendet werden.
- Direkte Cypress-Setup-Calls (`cypress/support/api.ts`) und UI-Traffic nutzen dann beide `localhost:8090`.
- Damit bleiben fachliche Testdaten auf der Test-Instanz statt in Dev.

## API-Endpunkte

### 1. Workflow erstellen

```
POST http://localhost:9090/api/v1/workflows
Headers: Content-Type: application/json, X-API-Key: <dev-key>
Response: 201 Created
```

**Body:**
```json
{
  "projectId": "<project-uuid>",
  "name": "Mein Workflow",
  "description": "Beschreibung",
  "blocks": [
    {
      "type": "shell",
      "config": {
        "description": "Dateien auflisten",
        "command": "ls -la {{ base_dir }}"
      }
    },
    {
      "type": "simple",
      "config": {
        "description": "Ergebnis analysieren",
        "command": "Analysiere die Dateiliste: {{ previous_output }}"
      }
    }
  ],
  "defaultContext": {
    "base_dir": "/your/project/path"
  },
  "model": "sonnet",
  "instructions": "Optionale workflow-spezifische Instruktionen"
}
```

### Block-Typen und Config

| Typ | Config-Felder | Beschreibung |
|-----|--------------|--------------|
| `simple` | `description`, `command`, `timeout_seconds?`, `effort?` | AI-Aufruf (`command` ist der Prompt, Pflichtfeld) |
| `shell` | `description`, `command`, `on_failure?` | Shell-Befehl auf Host |
| `agentic` | `description`, `goal`, `max_iterations`, `max_total_tokens`, `iteration_timeout_seconds`, `total_timeout_seconds`, `max_repeated_actions`, `require_approval` | Goal→Reason→Act→Observe Loop |
| `conditional` | `description`, `condition`, `then_command`, `else_command`, `execution_mode?`, `timeout_seconds?` | Verzweigung |
| `foreach` | `description`, `command`, `items` \| `items_command`, `items_source?`, `variable_name?`, `continue_on_error?`, `execution_mode?` | Iteration (Achtung: lowercase!) |
| `while` | `description`, `command`, `condition_command`, `max_iterations?`, `execution_mode?` | Schleife |
| `retry` | `description`, `command`, `max_retries?`, `delay?`, `exponential_backoff?`, `execution_mode?` | Wiederholung bei Fehler (`delay` in Sekunden) |
| `switch` | `description`, `expression`, `cases`, `default` | Multi-Way-Branching |
| `approval` | `description`, `question`, `options?`, `reject_options?`, `mode?`, `auto_answer?`, `on_no?`, `variable_name?`, `timeout_minutes?` | Warten auf Genehmigung |
| `sub-workflow` | `description`, `workflow_id` | Verschachtelter Workflow (max 5 Ebenen) |
| `skill-capability` | `description`, `capability`, `input?`, `params?`, `allow_writes?` | Skill-Ausführung (ohne `allow_writes` nur lesend) |
| `human-input` | `description`, `prompt`, `input_type`, `variable_name`, `options`, `timeout_minutes` | User-Eingabe |
| `integration` | `description`, `provider`, `action`, `params?`, `connection?` (benannte Verbindung, default `default`) | Integration-Provider-Aktion (Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Gitea/Forgejo, GitLab, Taskwarrior, CalDAV, iCal-Feed, ntfy, RSS/Atom, Open-Meteo, Todoist, Slack, Telegram) |
| `document` | `description`, `file_path`, `fail_on_empty?`, `min_characters?`, `variable_name?` | Text-Extraktion aus Dokumenten via Tika (PDF mit Text-Layer, DOCX, …) — kein OCR |
| `compress` | `description`, `source`, `stages`, `max_chars?`, `timeout_seconds?` | Regelbasierte Komprimierung einer Blockausgabe |
| `compress-ai` | `description`, `source`, `instruction?`, `max_chars?`, `model?`, `effort?`, `provider` (nur `CLAUDE`; setzen, wenn der Workflow einen anderen Provider nutzt), `timeout_seconds?` | KI-Komprimierung einer Blockausgabe (nur CLAUDE, ohne Tools) |
| `todo-create` | `description`, `title`, `priority?`, `tags?`, `project_id?`, `source?`, `source_agent?`, `variable_name?` | TODO anlegen (`variable_name` default `todo_id`) |
| `todo-update` | `description`, `id` oder `updates`, `title?`, `description?`, `priority?`, `tags?` | TODO ändern; mit `updates` viele TODOs des Lauf-Projekts (Status, Tags, Vermerk) |
| `todo-close` | `description`, `id` | TODO auf DONE setzen |
| `dashboard-report` | `status` (erforderlich), `title`, `summary`, `kpis`, `items` | Dashboard-Ergebnis des Runs aus Config-Feldern, ohne Marker; Modul `dashboard-report` |

Mit `?` markierte Felder sind optional. `on_failure` (`stop` \| `continue`, default `stop`) gilt für **jeden** Blocktyp.
`effort` (`low` \| `medium` \| `high` \| `xhigh`) gilt für jeden Block, der die AI aufruft, und überschreibt die Workflow-Einstellung.

### 2. Chain erstellen

```
POST http://localhost:9090/api/v1/chains
Headers: Content-Type: application/json, X-API-Key: <dev-key>
```

**Body:**
```json
{
  "name": "Meine Chain",
  "description": "Sequenzielle Ausführung mehrerer Workflows",
  "stopOnFailure": true,
  "entries": [
    {
      "workflowId": "<workflow-1-uuid>",
      "label": "Schritt 1: Vorbereitung",
      "sortOrder": 0,
      "stopOnFailure": true,
      "inputContext": { "mode": "prepare" }
    },
    {
      "workflowId": "<workflow-2-uuid>",
      "label": "Schritt 2: Ausführung",
      "sortOrder": 1,
      "stopOnFailure": true,
      "inputContext": {}
    }
  ]
}
```

**Hinweise:**
- Chains sind cross-project — Workflows aus verschiedenen Projekten möglich
- `previous_chain_output` wird automatisch vom letzten Workflow propagiert
- `stopOnFailure` auf Chain- UND Entry-Level müssen beide `true` sein, damit die Chain bei Fehler stoppt
- `timeoutMinutes` (5–1440) am Eintrag: so lange wartet die Chain höchstens auf diesen Workflow (sonst `chain-workflow-timeout-minutes`, Vorgabe 180) – für Einträge mit Freigabe

### 3. Workflow ausführen

```
POST http://localhost:9090/api/v1/workflows/{id}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
Response: 200 OK — der gestartete Lauf (`id` = Run-ID, `status`)
```

**Body:**
```json
{
  "context": {
    "variable1": "wert1"
  }
}
```

**WICHTIG:** Immer `context` verwenden, nicht `variables`!

### 4. Chain ausführen

```
POST http://localhost:9090/api/v1/chains/{id}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
Response: 201 Created — der Chain-Lauf (`id` = Chain-Run-ID)
```

**Body (optional):** die Variablen selbst, ohne Hülle — der Body **ist** der Eingabe-Kontext:
```json
{ "key": "value" }
```

Ein Body `{"inputContext": {...}}` käme verschachtelt unter dem Schlüssel `inputContext` an; `{{ key }}` bliebe leer.
Status des Chain-Laufs: `GET /api/v1/chains/{id}/runs/{runId}` (nicht `/executions/{runId}`). Nur mit Modul `chains`.

### 5. Todo-Resolve-Workflow ausführen

```
POST http://localhost:9090/api/v1/todos/{id}/resolve
Headers: X-API-Key: <key>
Response: 200 OK
```

**Body:** keiner

**Voraussetzungen und Verhalten:**
- Das Todo muss ein `linkedWorkflowId` gesetzt haben.
- Der Endpunkt startet den verlinkten Workflow als Resolve-Run.
- Das Todo wechselt dabei auf `IN_PROGRESS` und speichert den Run in `lastFixRunId`.
- Ein bereits laufender Resolve-Run fuer dasselbe Todo blockiert einen weiteren Start.
- Der Workflow startet nur durch diesen expliziten API-Aufruf oder die UI-Aktion `Loesen`.
- Das reine Setzen von `linkedWorkflowId` startet noch nichts.

### 6. Run-Status abfragen

```
GET http://localhost:9090/api/v1/executions/{runId}
Headers: X-API-Key: <key>
```

**Polling:** Alle 2-5 Sekunden abfragen, Timeout 30 Minuten.

| Status | Terminal? | Beschreibung |
|--------|----------|--------------|
| `PENDING` | Nein | Erstellt, nicht gestartet |
| `RUNNING` | Nein | Läuft gerade |
| `PAUSED` | Nein | Pausiert |
| `COMPLETED` | Ja | Erfolgreich |
| `COMPLETED_WITH_ERRORS` | Ja | Durchgelaufen, aber ein Schritt mit `checked_command: true` ist fehlgeschlagen (Lauf ging per `on_failure: continue` weiter). Das Gate steht unabhängig davon in `metadata.gate_status` |
| `FAILED` | Ja | Fehlgeschlagen |
| `CANCELLED` | Ja | Abgebrochen |
| `INTERRUPTED` | Ja | Unterbrochen (Neustart der Instanz) |

### 7. Execution steuern

| Aktion | Endpunkt |
|--------|----------|
| Pausieren | `POST /api/v1/executions/{id}/pause` |
| Fortsetzen | `POST /api/v1/executions/{id}/resume` |
| Abbrechen | `POST /api/v1/executions/{id}/cancel` |
| Wiederholen | `POST /api/v1/executions/{id}/retry` |
| Genehmigen | `POST /api/v1/executions/{id}/approve` — Body: `{"answer": "Yes"}`; bei einem Approval nur eine der angebotenen `options`, sonst 400 |
| Genehmigung prüfen | `GET /api/v1/executions/{id}/approval` — mit `rejectOptions` (die Optionen, die `on_no` auslösen) |
| Alle wartenden Freigaben | `GET /api/v1/executions/pending-input` — Approvals und Human-Input über alle Läufe, die ältesten zuerst |
| Live-Output nachladen | `GET /api/v1/executions/{id}/live-log?afterSeq=-1` — gepufferte Zeilen laufender/kürzlich beendeter Runs |

### 8. Workflow-Versionen

```bash
# Versionen eines Workflows auflisten (neueste zuerst, paginiert)
GET /api/v1/workflows/{id}/versions?page=0&size=20

# Einzelne Version mit Snapshot
GET /api/v1/workflows/{id}/versions/{versionId}

# Version wiederherstellen (erstellt automatisch neuen Snapshot des aktuellen Zustands)
POST /api/v1/workflows/{id}/versions/{versionId}/restore
```

**Hinweise:**
- Versionen werden automatisch bei jedem Update erstellt (nur bei Änderung an Blocks, Instructions, Model, Provider, Metadata, DefaultContext, Skills)
- Jede Version beschreibt den Zustand **nach** einer Änderung und trägt `definitionHash` (SHA-256 der Definition). `changeSource` bestimmt der Server, nicht der Request: `user`, `assistant`, `factory` (auch Auto-Repair), `wish`, `dialog`, `conversion`, `chain-template`, `pool-install`, `example-project`, `goal-guard`, `import`, `bpmn-import`, `restore`, `untracked` (Stand ohne Version, z. B. Altbestand oder Änderung am Service vorbei). Ältere Zeilen können noch `ai`/`api` tragen.
- Kosmetische Änderungen (Name, Beschreibung, Icon, Versions-Label `version`) erzeugen keine Version
- Context-Variablen in Workflows: `{{ workflow_version }}` (Versionsnummer), `{{ workflow_changed_since_last_run }}` (true/false), `{{ workflow_definition_hash }}`, `{{ trigger_source }}`

**Härtung (optional, `automation.hardening.*`):**

```bash
POST /api/v1/workflows/{id}/seal      # Definition sperren (Schalter sealing)
POST /api/v1/workflows/{id}/unseal
POST /api/v1/webhooks/{id}/secret     # Signatur-Secret, einmalig sichtbar (Schalter webhook-signatures)
DELETE /api/v1/webhooks/{id}/secret
```

- Versiegelt: PUT/DELETE mit Definitionsänderung → `409 WORKFLOW_SEALED`; manipulierte Definition startet nicht → `409 WORKFLOW_TAMPERED`
- `require-sealed-for-unattended`: Schedules, Webhooks, TODO-/Completion-Trigger, Auto-Repair und Wishes (auch über Chains/Sub-Workflows) starten nur versiegelte Workflows → sonst `403 UNATTENDED_REQUIRES_SEAL`
- Runs tragen `triggerSource`, `definitionHash`, `definitionVersionId`; Einträge einer Chain zusätzlich `chainRunId`

### 9. Test-Instanz

```bash
# Status prüfen
GET /api/v1/testing/status

# Starten
POST /api/v1/testing/start

# Stoppen
POST /api/v1/testing/stop

# Definitionen synchronisieren (Dev → Test)
POST /api/v1/testing/sync-definitions
Body: { "workflowIds": ["..."], "projectIds": ["..."], "skillIds": ["..."] }
# Alle Felder optional — leerer Body synchronisiert alles

# Testergebnis melden
POST /api/v1/testing/report-result
Body: { "workflowName": "...", "status": "PASSED|FAILED|SKIPPED" }
```

**Sync-Verhalten:**
- Projects: Match by name+path → skip if exists, create if new
- Workflows: Match by name+projectName → update if exists, import if new
- Custom Skills: Match by name → skip if exists
- **Nicht synchronisiert:** workflow_runs, step_results, scheduled_workflows, webhook_triggers

### 10. Hilfreich

```bash
# Projekte auflisten
GET /api/v1/projects

# Workflows eines Projekts (GET /api/v1/workflows kennt keinen projectId-Filter)
GET /api/v1/projects/<uuid>/workflows

# Definition auf bekannte Baufehler pruefen (deterministisch, kein KI-Aufruf)
GET /api/v1/workflows/{id}/analyze
# → {"workflowId","workflowName","findingCount","bySeverity",
#     "findings":[{"rule","severity","blockIndex","blockName","detail"}]}
# Regeln: unknown-block-reference, chain-only-placeholder, audit-only-placeholder ({{ audit_findings }}
#         ohne gueltiges metadata.audit), unwired-output (nur KI-Bloecke),
#         absolute-home-path, missing-strict-mode, duplicated-instructions, dated-model-id,
#         on-no-without-reject-option (Approval mit on_no, aber keine Option lehnt ab),
#         items-command-without-source (foreach mit items_command, aber ohne items_source "command"),
#         unknown-config-key (Schluessel, den der Block nicht liest), missing-required-config-key,
#         invalid-config-value (on_failure ausser stop/continue = HIGH) — gegen das Block-Config-Schema
#         des Editors (ai-automation-frontend/src/data/blockConfigSchema.json), auch in verschachtelten
#         Bloecken; deren detail beginnt mit dem Pfad, z. B. "Block 2 › cases[0] › Block 1: …"
# Der Analyzer meldet nur — er blockiert keinen Schreibweg und veraendert nichts.

# Bestandslauf ueber alle Definitionen in aktiven Projekten
GET /api/v1/workflows/analyze?minSeverity=HIGH
# → {"workflowsChecked","workflowsAffected","findingsTotal","byRule","workflows":[…]}
# Verdichtet: je Workflow nur Zahlen. Die vollen Befunde holt man je Workflow.

# Workflow als BPMN 2.0 XML exportieren
GET /api/v1/workflows/{id}/export/bpmn
# → File-Download (.bpmn, Camunda-Modeler-kompatibel)

# BPMN-Export als NEUEN Workflow reimportieren (Roundtrip; liest nur mysuricates:blockConfig)
POST /api/v1/workflows/import/bpmn?projectId=<uuid>
Content-Type: application/xml   # Body = BPMN-XML (max. 2 MiB)
# → 201 + Workflow (Name aus <process name>, Blöcke exakt wie exportiert: Typ, Name, Config)
# → 400 {"code":"INVALID_ARGUMENT","error":"…"} ohne mysuricates-Erweiterung, bei kaputtem XML, DOCTYPE/XXE, zu großer Datei
#
# Formatfilter, kein Herkunftsbeleg: `exporter="MySuricates"` und der Namespace sind frei
# schreibbare Attribute, und `blockConfig` wird als Definition übernommen. Ein Import ist so
# vertrauenswürdig wie seine Quelle — wie ein JSON-Import. Prüfsumme/Signatur: TODO b18939a6.

# System-Backup erstellen
GET /api/v1/system/backup
# → File-Download

# Alle Runs auflisten (mit Filtern)
GET /api/v1/executions?status=FAILED&workflowId=<uuid>

# Assistenten-Sessions auflisten (letzte 50)
GET /api/v1/assistant/sessions

# Einzelne Session mit Nachrichten laden
GET /api/v1/assistant/sessions/<session-uuid>

# Session loeschen
DELETE /api/v1/assistant/sessions/<session-uuid>
```

### 11. CRUD-Kurzreferenz

| Aktion | Endpunkt |
|--------|----------|
| Workflow lesen / ändern / löschen | `GET`/`PUT`/`DELETE /api/v1/workflows/{id}` (DELETE = Soft-Delete; PUT nimmt optional `version`, das Versions-Label wie `4.4.0` — ohne Feld bleibt es, keine Definitionsversion, auch bei Siegel erlaubt) |
| Chains auflisten / lesen / ändern / löschen | `GET /api/v1/chains`, `GET`/`PUT`/`DELETE /api/v1/chains/{id}` |
| Chain-Runs | `GET /api/v1/chains/runs`, `GET /api/v1/chains/{id}/runs`, `POST /api/v1/chains/runs/{runId}/cancel` |
| TODOs auflisten (Filter) | `GET /api/v1/todos?status=OPEN&status=IN_PROGRESS&projectId=<uuid>&tag=<tag>&q=<suche>` |
| TODO lesen / ändern / löschen | `GET`/`PUT`/`DELETE /api/v1/todos/{id}` |
| TODO-Status setzen | `PATCH /api/v1/todos/{id}/status` — Body: `{"status": "IN_PROGRESS", "sessionId": "..."}` |
| Projekt anlegen / lesen / ändern | `POST /api/v1/projects`, `GET`/`PUT /api/v1/projects/{id}` |
| Beispielprojekt anlegen (idempotent) | `POST /api/v1/projects/example?locale=de` — 201 beim ersten Aufruf, danach 200 mit demselben Projekt |
| App-Setting lesen / setzen | `GET`/`PUT /api/v1/system/settings/{key}` — PUT-Body: `{"value": "..."}` |
| Execution-Config (Timeouts, Concurrency) | `GET`/`PUT /api/v1/system/execution-config` |
| Integrationen + Verbindungen | `GET /api/v1/integrations` (je Provider `connections[]`), `GET /api/v1/integrations/{providerId}/connections`; `connect`, `credentials`, `connection`, `actions`, `task-targets` nehmen `?connection=<name>` (ohne: `default`) |

Feld-Details zu allen Endpunkten: [docs/api-referenz.md](docs/api-referenz.md).

## Todo-Resolve in der Praxis

```bash
# Todo mit Resolver-Workflow verknuepfen
curl -s --noproxy '*' -X PUT http://localhost:9090/api/v1/todos/$TODO_ID \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d "{\"linkedWorkflowId\":\"$WORKFLOW_ID\"}"

# Resolver-Workflow fuer das Todo manuell starten
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/todos/$TODO_ID/resolve \
  -H "X-API-Key: $DEV_KEY"
```

Hinweise:
- `linkedWorkflowId` ist optional; ohne Link ist `resolve` nicht moeglich.
- `resolve` ist ein manueller Startpunkt und kein automatischer Trigger beim Speichern des Todos.

## Kompletter Beispiel-Flow

```bash
# 1. Private API-Keys bereitstellen
export DEV_KEY="<dev-api-key>"
export TEST_KEY="<test-api-key>"

# 2. Test-Status prüfen
curl -s --noproxy '*' -H "X-API-Key: $DEV_KEY" http://localhost:9090/api/v1/testing/status

# 3. Projekt-ID finden
curl -s --noproxy '*' -H "X-API-Key: $DEV_KEY" http://localhost:9090/api/v1/projects | python3 -c "import sys,json; [print(f'{p[\"id\"]}  {p[\"name\"]}') for p in json.load(sys.stdin)]"

# 4. Workflow erstellen
WORKFLOW=$(curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/workflows \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{
    "projectId": "<project-id>",
    "name": "Test-Workflow",
    "blocks": [{ "type": "shell", "config": { "description": "Hello", "command": "echo Hello World" } }]
  }')
WORKFLOW_ID=$(echo $WORKFLOW | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 5. Auf Test synchronisieren
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" \
  -d "{\"workflowIds\": [\"$WORKFLOW_ID\"]}"

# 6. Auf Test ausführen (Port 8090!)
RUN=$(curl -s --noproxy '*' -X POST http://localhost:8090/api/v1/workflows/$WORKFLOW_ID/execute \
  -H "Content-Type: application/json" -H "X-API-Key: $TEST_KEY" -d '{}')
RUN_ID=$(echo $RUN | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 7. Ergebnis pollen
while true; do
  STATUS=$(curl -s --noproxy '*' -H "X-API-Key: $TEST_KEY" \
    http://localhost:8090/api/v1/executions/$RUN_ID | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "Status: $STATUS"
  case $STATUS in COMPLETED|FAILED|CANCELLED|INTERRUPTED|COMPLETED_WITH_ERRORS) break;; esac
  sleep 3
done

# 8. Ergebnis melden
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/testing/report-result \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" \
  -d '{"workflowName": "Test-Workflow", "status": "PASSED"}'
```

## Wichtige Regeln

- **Context, nicht Variables:** Bei `execute` immer das `context`-Feld verwenden
- **Template-Variablen:** `{{ variable_name }}` Syntax in Prompts und Commands
- **previous_output:** Enthält den Output des vorherigen Steps
- **Ports:** Dev = 9090, Test = 8090, Stable = 9091
- **DB nie direkt beschreiben:** Immer über REST API gehen
- **foreach ist lowercase:** Block-Typ heißt `foreach`, nicht `forEach`
- **Approval-Feld:** `{"answer": "Yes"}`, nicht `{"approved": true}`
- **Sprache aus Vorlagen:** `GET /api/v1/workflow-templates?locale=de|en` liefert die Schritte zweisprachiger Vorlagen in dieser Sprache (ohne Angabe Englisch). Pool- und Ketten-Install nehmen `"locale"` im Body (Pool ohne Angabe Englisch, Ketten Deutsch). Wer Blöcke einer Vorlage in `POST /workflows` kopiert, holt sie vorher mit `?locale=`. Regeln: [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md#zweisprachige-vorlagen)
- **Blaupausen:** Dieselbe Liste enthält auch Blaupausen (`kind: BLUEPRINT`, `id` `blueprint-…`): leere Gerüste mit lauffähigen Platzhaltern, die Block-Verknüpfungen zeigen. Vorlagen haben `kind: TEMPLATE`. Anlegen funktioniert gleich (Blöcke per `?locale=` holen, `POST /workflows`).
