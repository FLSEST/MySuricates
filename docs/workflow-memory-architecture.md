> *Zuletzt aktualisiert: [auto-generated]*

# Workflow Memory — Architektur & Datenfluss

## Uebersicht

Das Workflow Memory Feature extrahiert wiederverwendbares Wissen aus vergangenen Workflow-Runs
und stellt es bei zukuenftigen Ausfuehrungen als Kontext bereit. Es unterstuetzt zwei Provider:
**Claude CLI** und **OpenAI Codex CLI**, beide laufen direkt auf dem Host.

---

## 1. Datenflussdiagramm — Wissen extrahieren

```
 Browser (WorkflowMemoryPanel.tsx)
 ┌──────────────────────────────────────────────────────────────────┐
 │  POST /api/v1/workflows/{id}/memory/extract                     │
 │  Body: { provider: "CLAUDE" | "CODEX", runIds?: [...] }         │
 └──────────────────────────┬───────────────────────────────────────┘
                            │
                            ▼
 Backend (Spring Boot, Port 9090)
 ┌──────────────────────────────────────────────────────────────────┐
 │  WorkflowMemoryController.extract()                             │
 │  ├── Error Handling: 400 / 422 / 502                            │
 │  └──────────────┬───────────────────────────────────────────────│
 │                 ▼                                                │
 │  WorkflowMemoryService.extractKnowledge()                       │
 │  │                                                               │
 │  ├─ 1. Runs laden ◄──── WorkflowRunRepository (SQLite)          │
 │  │     └── Letzte 10 Runs oder spezifische Run-IDs              │
 │  │                                                               │
 │  ├─ 2. StepResults sammeln                                      │
 │  │     └── Alle Steps aus allen geladenen Runs                  │
 │  │                                                               │
 │  ├─ 3. Preprocessing ◄── ConversationPreprocessor               │
 │  │     ├── Code-Bloecke komprimieren (>30 Zeilen)               │
 │  │     ├── Data-Dumps komprimieren (>20 Zeilen)                 │
 │  │     ├── Meta-Kommentare entfernen                            │
 │  │     ├── Wiederholte Fehler zusammenfassen                    │
 │  │     └── Agentic-Iterationen komprimieren                     │
 │  │     Reduktion: typisch 50-70%                                │
 │  │                                                               │
 │  ├─ 4. getSummarizer(provider)                                  │
 │  │     ├── "CLAUDE" ──► ClaudeSummarizer                        │
 │  │     └── "CODEX"  ──► CodexSummarizer                        │
 │  │                                                               │
 │  └─ 5. Ergebnis speichern ──► WorkflowMemoryRepository (SQLite) │
 │        └── WorkflowMemory { title, content, tags, provider }    │
 └──────────────────────────────────────────────────────────────────┘
```

---

## 2. Summarizer-Architektur

```
                  KnowledgeSummarizer (Interface)
                  │ summarize(preprocessed) → SummarizationResult
                  │
          ┌───────┴──────────┐
          │                  │
  ClaudeSummarizer    CodexSummarizer
          │                  │
  HostClaudeService   HostCodexService
          │                  │
          ▼                  ▼
  ┌──────────────┐   ┌──────────────┐
  │ claude -p    │   │ codex exec   │
  │ --max-turns 1│   │ -o result.txt│
  │ --output-    │   │ --skip-git-  │
  │  format      │   │  repo-check  │
  │  stream-json │   │ -C <workdir> │
  └──────┬───────┘   └──────┬───────┘
         │                   │
         ▼                   ▼
  ~/.autotrigger/     ~/.autotrigger/
  summarization/      summarization/
  claude/             codex/
  └── CLAUDE.md       └── AGENTS.md
  (Instruktionen)     (Instruktionen)
```

### Vergleich der Provider

| Eigenschaft       | ClaudeSummarizer              | CodexSummarizer               |
|-------------------|-------------------------------|-------------------------------|
| CLI               | `claude -p`                   | `codex exec`                  |
| Max Turns         | 1 (kein Tool-Use)             | — (codex exec default)        |
| Output-Methode    | Stream-JSON parsen            | `-o` Flag (Datei)             |
| Instruktionen     | `CLAUDE.md` im Working Dir    | `AGENTS.md` im Working Dir    |
| Auth              | `~/.claude/` (CLI-eigen)      | `~/.codex/` (CLI-eigen)       |
| Timeout           | `automation.summarization.timeout-seconds` (default: 120s) |

---

## 3. Datenflussdiagramm — Memory bei Workflow-Ausfuehrung nutzen

```
  WorkflowExecutor.execute(workflow)
  │
  ├─ 1. writeActiveMemoryToProject(workflowId, projectPath)
  │     │
  │     ├── Aktive Memory aus DB lesen
  │     │   └── WorkflowMemoryRepository.findByWorkflowIdAndActiveTrue()
  │     │
  │     ├── Falls vorhanden:
  │     │   └── Schreibt .claude/rules/project-memory.md ins Projekt
  │     │
  │     └── Falls nicht vorhanden:
  │         └── Loescht alte project-memory.md (Aufraeum-Logik)
  │
  ├─ 2. Block-Execution startet
  │     └── Claude CLI liest CLAUDE.md + .claude/rules/*.md automatisch
  │         └── Workflow Memory ist als Kontext verfuegbar
  │
  └─ 3. Run-Ergebnis wird gespeichert
        └── WorkflowRun + StepResults in SQLite
            └── Kann spaeter fuer neue Memory-Extraktion genutzt werden
```

---

## 4. Datenmodell

```
┌─────────────────────────┐       ┌─────────────────────────┐
│     workflow_memory      │       │      workflow_run        │
├─────────────────────────┤       ├─────────────────────────┤
│ id          TEXT PK      │       │ id          TEXT PK      │
│ workflow_id TEXT FK       │◄──────│ workflow_id TEXT FK       │
│ title       TEXT          │       │ status      TEXT          │
│ content     TEXT (MD)     │       │ started_at  TIMESTAMP    │
│ tags        TEXT (JSON)   │       │ finished_at TIMESTAMP    │
│ source_run_ids TEXT (JSON)│───────│ steps       (embedded)   │
│ provider    TEXT          │       │   ├── step_index INT     │
│ active      BOOLEAN       │       │   ├── input     TEXT     │
│ created_at  TIMESTAMP    │       │   ├── output    TEXT     │
└─────────────────────────┘       │   ├── status    TEXT     │
                                   │   └── metadata  JSON     │
 provider: "CLAUDE" | "CODEX"     └─────────────────────────┘
 active: nur 1 pro Workflow

 Beziehung: source_run_ids verweist auf
 die Run-IDs die zur Extraktion genutzt wurden
```

---

## 5. Verzeichnisstruktur

```
~/.autotrigger/summarization/           ← Konfigurierbar via application.yml
├── claude/                             ← Working Dir fuer ClaudeSummarizer
│   └── CLAUDE.md                       ← Instruktionen (aus resources kopiert)
└── codex/                              ← Working Dir fuer CodexSummarizer
    └── AGENTS.md                       ← Instruktionen (aus resources kopiert)

ai-automation-backend/src/main/resources/
├── agent-instructions.md               ← Base Instructions (Block-Execution)
└── summarization/
    ├── claude-summarizer.md            ← Template fuer CLAUDE.md
    └── codex-summarizer.md             ← Template fuer AGENTS.md
```

`SummarizationSetupService` kopiert die Templates beim Backend-Start
in die Working Directories. Aenderungen an den Resource-Dateien werden
beim naechsten Neustart uebernommen.

---

## 6. Konfiguration (application.yml)

```yaml
automation:
  summarization:
    base-dir: ${AUTOMATION_SUMMARIZATION_DIR:~/.autotrigger/summarization}
    timeout-seconds: ${AUTOMATION_SUMMARIZATION_TIMEOUT:120}
```

---

## 7. API-Endpunkte

| Methode  | Pfad                                              | Beschreibung                        |
|----------|---------------------------------------------------|-------------------------------------|
| `GET`    | `/api/v1/workflows/{id}/memory`                   | Alle Memories eines Workflows       |
| `POST`   | `/api/v1/workflows/{id}/memory/extract`            | Wissen extrahieren (async, ~30-60s) |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}`            | Title/Content aktualisieren         |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}/activate`   | Memory aktivieren                   |
| `DELETE` | `/api/v1/workflows/{id}/memory/{memId}`            | Memory loeschen                     |

### Error Responses (POST /extract)

| HTTP Status | Ursache                                     |
|-------------|---------------------------------------------|
| 201         | Erfolgreich extrahiert                      |
| 400         | Unbekannter Provider                        |
| 422         | Keine Runs / keine Steps gefunden           |
| 502         | CLI-Aufruf fehlgeschlagen (Timeout, Crash)  |
