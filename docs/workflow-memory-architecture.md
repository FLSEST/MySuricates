# Workflow Memory — Architektur & Datenfluss

> Dieselben Abläufe als Mermaid-Diagramme (Sequenz, Klassen, ER):
> [workflow-memory-diagrams.md](workflow-memory-diagrams.md)

## Übersicht

Das Workflow Memory Feature extrahiert wiederverwendbares Wissen aus vergangenen Workflow-Runs
und stellt es bei zukünftigen Ausführungen als Kontext bereit. Es unterstützt zwei Provider:
**Claude CLI** und **OpenAI Codex CLI**, beide laufen direkt auf dem Host. Es ist ein optionales
Modul (`memory`, in den Presets `research` und `full`).

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
 │  ├── Error Handling: 400 / 404 / 422 / 502                       │
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
 │  │     ├── Code-Blöcke über 30 Zeilen ersetzen                   │
 │  │     ├── Data-Dumps über 20 Zeilen ersetzen                    │
 │  │     ├── Meta-Kommentare entfernen                             │
 │  │     ├── Wiederholte Zeilen zusammenfassen                     │
 │  │     └── Agentic-Iterationen verdichten                        │
 │  │                                                               │
 │  ├─ 4. getSummarizer(provider)                                  │
 │  │     ├── "CLAUDE" ──► ClaudeSummarizer                        │
 │  │     └── "CODEX"  ──► CodexSummarizer                        │
 │  │                                                               │
 │  └─ 5. Ergebnis speichern ──► WorkflowMemoryRepository (SQLite) │
 │        └── WorkflowMemory { title, content, tags, provider }    │
 └──────────────────────────────────────────────────────────────────┘
```

Die Anfrage läuft synchron: Der Controller antwortet erst nach dem KI-Aufruf, mit `201` und der
neuen, noch inaktiven Memory (ohne `provider` im Body: `CLAUDE`). Der KI-Aufruf selbst läuft
außerhalb jeder Transaktion, zwischen einem kurzen Lesevorgang (Schritte 1–2) und einem kurzen
Schreibvorgang (Schritt 5), weil die Datenbank nur eine Verbindung hat.

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
  │ claude -p    │   │ codex exec - │
  │ --max-turns 1│   │ -o <tmpdatei>│
  │ --output-    │   │ --skip-git-  │
  │  format      │   │  repo-check  │
  │  stream-json │   │ -C <workdir> │
  │  --verbose   │   │              │
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
| CLI               | `claude -p`                   | `codex exec -`                |
| Prompt-Übergabe  | stdin                         | stdin (`-` Placeholder)       |
| Max Turns         | 1 (kein Tool-Use)             | — (codex exec default)        |
| Output-Methode    | Stream-JSON parsen            | `-o` Flag (Temp-Datei)        |
| Instruktionen     | `CLAUDE.md` im Working Dir    | `AGENTS.md` im Working Dir    |
| Auth              | `~/.claude/` (CLI-eigen)      | `~/.codex/` (CLI-eigen)       |
| Timeout           | `automation.host-claude.timeout-seconds` (Standard 300 s) | `automation.summarization.timeout-seconds` (Standard 120 s) |

---

## 3. Datenflussdiagramm — Memory bei Workflow-Ausführung nutzen

```
  WorkflowExecutor, vor dem ersten Block
  (ExecutionContextFactory.writeProjectContextFiles)
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
  │         └── Löscht alte project-memory.md (Aufräum-Logik)
  │
  ├─ 2. Block-Execution startet
  │     ├── Claude CLI liest CLAUDE.md + .claude/rules/*.md automatisch
  │     ├── Codex und Pi: RunContextFiles hängt .claude/rules/*.md
  │     │   an den System-Prompt
  │     └── Workflow Memory ist damit als Kontext verfügbar
  │
  └─ 3. Run-Ergebnis wird gespeichert
        └── WorkflowRun + StepResults in SQLite
            └── Kann später für neue Memory-Extraktion genutzt werden
```

---

## 4. Datenmodell

```
┌─────────────────────────┐       ┌─────────────────────────┐
│     workflow_memory      │       │      workflow_runs       │
├─────────────────────────┤       ├─────────────────────────┤
│ id          TEXT PK      │       │ id          TEXT PK      │
│ workflow_id TEXT FK       │◄──────│ workflow_id TEXT FK       │
│ title       TEXT          │       │ workflow_name TEXT        │
│ content     TEXT (MD)     │       │ status      TEXT          │
│ tags        TEXT (JSON)   │       │ started_at  TIMESTAMP    │
│ source_run_ids TEXT (JSON)│───────│ completed_at TIMESTAMP   │
│ provider    TEXT          │       │ duration_ms INTEGER       │
│ active      INTEGER       │       │ metadata    JSON          │
│ created_at  TEXT          │       └────────┬────────────────┘
└─────────────────────────┘                │ @OneToMany
                                   ┌───────┴─────────────────┐
 provider: "CLAUDE" | "CODEX"     │      step_results        │
 active: nur 1 pro Workflow       ├─────────────────────────┤
                                   │ id          TEXT PK      │
 Beziehung: source_run_ids        │ workflow_run_id TEXT FK   │
 verweist auf die Run-IDs die     │ step_index  INT           │
 zur Extraktion genutzt wurden    │ input       TEXT           │
                                   │ output      TEXT           │
                                   │ status      TEXT           │
                                   │ metadata    JSON           │
                                   │ context_delta JSON         │
                                   └─────────────────────────┘
```

---

## 5. Verzeichnisstruktur

```
~/.autotrigger/summarization/           ← Konfigurierbar via application.yml
├── claude/                             ← Working Dir für ClaudeSummarizer
│   └── CLAUDE.md                       ← Instruktionen (aus resources kopiert)
└── codex/                              ← Working Dir für CodexSummarizer
    └── AGENTS.md                       ← Instruktionen (aus resources kopiert)

ai-automation-backend/src/main/resources/
├── agent-instructions.md               ← Base Instructions (Block-Execution)
└── summarization/
    ├── claude-summarizer.md            ← Template für CLAUDE.md
    └── codex-summarizer.md             ← Template für AGENTS.md
```

`SummarizationSetupService` kopiert die Templates beim Backend-Start
in die Working Directories. Änderungen an den Resource-Dateien werden
beim nächsten Neustart übernommen.

---

## 6. Konfiguration (application.yml)

```yaml
automation:
  summarization:
    base-dir: ${AUTOMATION_SUMMARIZATION_DIR:${user.home}/.autotrigger/summarization}
    timeout-seconds: ${AUTOMATION_SUMMARIZATION_TIMEOUT:120}
```

`timeout-seconds` begrenzt den Codex-Aufruf und lässt sich zur Laufzeit in den Einstellungen ändern
(`summarization-timeout-seconds`); der Claude-Aufruf folgt `automation.host-claude.timeout-seconds`.

---

## 7. API-Endpunkte

| Methode  | Pfad                                              | Beschreibung                        |
|----------|---------------------------------------------------|-------------------------------------|
| `GET`    | `/api/v1/workflows/{id}/memory`                   | Alle Memories eines Workflows       |
| `POST`   | `/api/v1/workflows/{id}/memory/extract`            | Wissen extrahieren (synchron, ohne `provider`: CLAUDE) |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}`            | Title/Content aktualisieren         |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}/activate`   | Memory aktivieren                   |
| `DELETE` | `/api/v1/workflows/{id}/memory/{memId}`            | Memory löschen                     |

### Error Responses (POST /extract)

| HTTP Status | Ursache                                     |
|-------------|---------------------------------------------|
| 201         | Erfolgreich extrahiert                      |
| 400         | Unbekannter Provider                        |
| 404         | Unbekannte Run-ID in `runIds`               |
| 422         | Keine Runs / keine Steps gefunden           |
| 502         | CLI-Aufruf fehlgeschlagen (Timeout, Crash, Exit-Code ≠ 0 wie Limit oder fehlende Anmeldung) |

---

## 8. Auto-Memory-Extraktion

Der `AutoMemoryExtractionListener` extrahiert und aktiviert Memory automatisch
nach erfolgreichen Workflow-Runs. Die Konfiguration erfolgt per Workflow über
das `metadata`-Feld.

### Aktivierung (Workflow-Metadata)

```json
{
  "autoMemoryExtraction": {
    "enabled": true,
    "interval": 5
  }
}
```

- `enabled`: Auto-Extraktion ein-/ausschalten
- `interval`: Anzahl erfolgreicher Runs zwischen Extraktionen (Default: 5)

### Ablauf

```
  WorkflowRunCompletedEvent (status == COMPLETED)
  │
  ├─ 1. isAutoMemoryEnabled(workflowMetadata) → true?
  │
  ├─ 2. Erfolgreiche Runs seit letzter Extraktion zählen
  │     └── WorkflowRunRepository.countByWorkflowIdAndStatusAndCompletedAtAfter()
  │
  ├─ 3. Schwellwert (interval) erreicht?
  │     └── Nein → Abbruch (nächster Run zählt weiter)
  │
  └─ 4. extractAndActivate(workflowId, "CLAUDE")
        ├── Extrahiert Knowledge aus den letzten 10 Runs
        ├── Deaktiviert alle bisherigen Memories
        ├── Aktiviert die neue Memory
        └── Schreibt project-memory.md ins Projekt (falls projectPath vorhanden)
```

- Läuft asynchron (`@Async @EventListener`) — blockiert den Workflow-Run nicht
- Nur mit eingeschaltetem Modul `memory`
- Verwendet immer Provider `CLAUDE`
- Scheitert der CLI-Aufruf (Timeout, Exit-Code ≠ 0 wie Limit oder fehlende Anmeldung), entsteht keine Memory: Die bisher aktive bleibt aktiv, `project-memory.md` bleibt unverändert, der Fehler steht im Log.
- Auch `autoMemoryExtraction: true` (boolean statt Map) wird unterstützt (nutzt Default-Interval 5)
