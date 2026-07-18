# Code-Explainer — wöchentlicher Delta-Begleiter (Workflow, kein Plattform-Code)

> **Umgesetzt am 2026-07-16.** Workflow `4885414e-76a0-4828-beba-4ee0f807a4be`,
> Schedule `c71847f5-b75a-4e3d-a6f4-f826098e5b4e` (`0 30 6 ? * FRI`, SKIP),
> MySuricates-TODO `57f589c2-91b4-4f51-b0c4-158060b06ee0`.
>
> **Erweitert 2026-07-16** (TODO `14a07a49`): viertes deterministisches Diagramm
> `hierarchies` — Backend-Familien: Interfaces/Basisklassen mit ≥3 Implementierungen
> (BlockExecutor, IntegrationProvider, AiProvider, …) als Cluster-Bäume
> (`scripts/explainer/extract_hierarchies.py`, `unflatten -l 3` + `packmode=array_u2`
> für ~2:1-Seitenverhältnis statt 35:1-Haarlinie unter `max-width:100%`).

## Kontext

Die MySuricates-Codebase wächst schnell und ist größtenteils AI-geschrieben — Flo will sein **eigenes Verständnis** des Codes aktuell halten. Es gibt bereits einmalige Erklär-Artefakte (`docs/onboarding/`, Built-in-Template `codebase-to-onboarding.json`), aber nichts Kontinuierliches. Lösung: ein wöchentlicher Workflow nach dem bewährten Audit-/Tech-Radar-Skelett, der erklärt, **was sich seit dem letzten Lauf geändert hat und warum es so gebaut ist**, einen lebenden Q&A-Katalog pflegt, ein Verständnis-Quiz generiert und statische Diagramme rendert. Workflow-First: null Plattform-Code.

**Abgestimmte Entscheidungen (Brainstorming):**
- Fragekatalog = **beides**: Quiz (aktives Verankern) + gepflegter Q&A-Katalog (lebendes FAQ)
- Betriebsmodus = **Delta-Begleiter** (Schedule, wöchentlich), kein On-Demand-Deep-Dive
- Diagramme = **alle vier**: Backend-Package-Graph, JPA-Entity-Diagramm, Frontend-Import-Struktur (alle drei deterministisch per Skript — keine halluzinierten Fakten) + AI-kuratierte Datenfluss-Diagramme (dot-Source von Opus, deterministisch validiert/gerendert)
- Deliverable = **eine selbstenthaltene HTML-Seite pro Lauf** (SVGs inline, Quiz hinter `<details>`), Archiv wie Tech-Radar (`index.html` + `archive/`)
- Ansatz A gewählt: Repo-Skripte + 4-Block-Workflow (statt In-App-Variante oder Onboarding-Docs-Refresher)

## Neue Dateien

```
scripts/explainer/                    (committed, NUR Python-stdlib, kein pip)
├── common.py                         # dot-Escaping, SVG-Render (subprocess dot -Tsvg), Edge-JSON, Struktur-Diff
├── delta_report.py                   # Block 1: Modus (BASELINE/DELTA), Git-Delta, Staleness-Erkennung
├── extract_backend_deps.py           # (a) Package-Abhängigkeitsgraph
├── extract_entities.py               # (b) JPA-ER-Diagramm
├── extract_frontend_deps.py          # (c) pages→hooks→api/stores
├── render_page.py                    # Block 3: HTML + Archiv + State (größtes Modul)
└── testdata/sample-explain.json      # Fixture für lokalen render_page-Test

workflow-pool/mysuricates-code-explainer.json
.gitignore                            # + Zeile `/explainer/` (root-anchored, wie /reports/)

explainer/                            (gitignored, Laufzeit)
├── state.json  ├── index.html  ├── archive/YYYY-MM-DD-code-explainer.html
└── graphs/{backend_deps,entities,frontend_deps}.edges.json
```

Laufzeit-Austausch zwischen Blöcken: `/tmp/code-explainer-{{ workflow_run_id }}/` (run-meta.json, *.dot/.svg/.edges.json/.diff.txt, explain.raw).

## Extraktions-Skripte (verifizierte Eckdaten)

Alle: CLI `<script>.py <repo_root> <run_dir>`, stdlib only, `/usr/bin/dot` vorhanden (dev-host-only ist ok, Präzedenz Codex-Image-Gen; **kein mmdc** — snap/chromium fragil).

- **extract_backend_deps.py**: `ai-automation-backend/src/main/java/**/*.java` (546 Dateien, 65 Packages) → Knoten = Package relativ `com.automation`, **auf Tiefe 2 gekappt** (~35–40 Knoten; Klassen-Ebene wäre unlesbar). Kanten aus `import com.automation.*`, Gewicht = Anzahl importierender Dateien, `rankdir=LR`, Layer-Farben (controller/service/repository/…).
- **extract_entities.py**: alle `*.java` mit `@Entity` (~25 echte Entities) → `shape=record` mit Tabellennamen; Relationen aus `@ManyToOne/@OneToMany/@OneToOne/@ManyToMany`, Kante nur von der Owning-Seite (`mappedBy` = Inverse, gestrichelt), `@JoinColumn` als Label.
- **extract_frontend_deps.py**: `ai-automation-frontend/src/{pages,hooks,api,stores}` (40 Pages, 89 Hooks, 1 Client) → drei Cluster (Pages | Hooks | API/Stores); Alias `@/`→`src/` auflösen; Guard: >350 Kanten ⇒ hooks→hooks weglassen + Hinweis.
- **Struktur-Diff**: jedes Skript schreibt `*.edges.json` und diffed gegen `explainer/graphs/*.edges.json` → Menschentext (`+ Kante: service.audit -> repository (3 Dateien)`) für Block 2 und die HTML-Seite.
- **delta_report.py**: liest `explainer/state.json`; fehlt ⇒ `@@MODE BASELINE` (kompakter Strukturüberblick statt Commits). DELTA: `git log --oneline <lastSha>..HEAD` (Cap 50), `git diff --name-only` gruppiert nach Bereich (Cap ~80, Rest „+N weitere") — Token-Budget. **Staleness deterministisch**: `changed_files ∩ entry.cited_files` je catalog/flows-Eintrag → `@@STALE_QA` / `@@STALE_FLOWS`. SHA-Rewrite-Fallback: `git cat-file -e`, sonst `--since='7 days ago'` + Hinweis. Schreibt `run-meta.json` (mode, head_sha, range, stale-ids, report_date).

## state.json (Schema v1)

```json
{ "schema_version": 1, "lastExplainedSha": "…", "baselineDate": "…", "lastRunDate": "…",
  "catalog": [{ "id": "qa-…", "frage": "…", "antwort_md": "…", "cited_files": ["…"],
                "verified_sha": "…", "created": "…", "updated": "…" }],
  "flows":   [{ "id": "flow-…", "titel": "…", "beschreibung": "…", "dot": "digraph {…}",
                "cited_files": ["…"], "verified_sha": "…" }] }
```

`lastExplainedSha` wird **nur** von `render_page.py` nach erfolgreichem Seiten-Schreiben fortgeschrieben (tmp + `os.replace`) — fehlgeschlagene Runs erklären beim nächsten Mal erneut. Edge-Listen liegen in `explainer/graphs/`, gleiche Erst-nach-Erfolg-Semantik.

## Workflow: 4 Blöcke (kein Audit-Envelope, keine Gate-Marker — bewusst, keine Findings)

`workflow-pool/mysuricates-code-explainer.json`: `"model": "claude-opus-4-6"` (Analyse ⇒ immer Opus). Kein `projectId` im Pool-JSON (Hygiene-Check blockt die interne UUID) — beim Seeden wird das AutoPilot-Projekt injiziert (`jq '. + {projectId: $pid}'`, Muster `scripts/seed-workflows.example.sh`); dessen Pfad = Repo-Root ⇒ `{{ project_path }}` stimmt.

1. **`delta`** (shell, 240s): `set -euo pipefail`; ruft die 4 Skripte auf (`delta_report`, 3 Extraktoren) mit `$BASE`/`$RUN_DIR`. Kein eingebettetes Python (Kern-Lehre aus tech-radar-Heredoc-Schmerz). Stdout = kompakter @@-Sektionen-Report für Block 2. Kein REST/DB-Zugriff.
2. **`erklaeren`** (simple/Opus, 900s): Rolle „Flos Code-Erklärer — verstehen, nicht auditieren". Input `{{ previous_output }}`. **Pflichtsatz: darf Dateien LESEN (nur geänderte + zitierte), aber KEINE schreiben/ändern/anlegen** (bekannte Simple-Block-Fehlfunktion). Modus-Weiche: BASELINE ⇒ Status-quo-Erklärung + ~10 Kern-Q&A seeden + 2–3 Kern-Flows; DELTA ⇒ (a) was & **warum** so gebaut (Design-Intent, Unsicherheit kennzeichnen), (b) NUR `@@STALE_QA`-Einträge neu beantworten + optional 0–2 neue Fragen, (c) NUR stale Flows neu. Quiz: 3–5 Fragen mit Antworten + Dateibezug. **Output: NUR ein JSON-Objekt** (Felder: `mode`, `erklaerung_md`, `catalog_updates[]`, `catalog_remove_ids[]`, `quiz[]`, `flows[]` mit dot-Source); `cited_files` repo-relativ.
3. **`render`** (shell, 120s): `{{ block.erklaeren.output }}` per Heredoc (Delimiter `MYS_EXPLAIN_RAW_EOF`) nach `explain.raw`, dann `render_page.py $RUN_DIR $BASE/explainer $BASE`. Exit ≠ 0 bei unbrauchbarem JSON ⇒ Run FAILED ⇒ State unverändert.
4. **`fazit`** (simple, 120s, `on_failure: continue`): max. 5 Zeilen aus der `@@SUMMARY`-Sektion von render_page; kein Datei-Zugriff.

## render_page.py — Kernablauf

1. `explain.raw` parsen (Zaun-Stripping wie tech-radar `load_json()`); Fehler ⇒ exit 1.
2. Katalog/Flows per id mergen (`verified_sha` ← head_sha); **jede AI-dot-Source via `dot -Tsvg` validieren** — invalide ⇒ alte Version behalten bzw. Notiz-Karte statt Run-Abbruch.
3. Seite bauen: Header (KW, BASELINE/DELTA-Badge, Commit-Range) → „Was ist neu & warum" (stdlib-Markdown-Subset-Renderer, ~80 Zeilen, `html.escape` zuerst) → Diagramme (3 statische SVGs + Struktur-Diff-Text, dann Flows) → Q&A-Katalog (alle als `<details>`, Badge „aktualisiert", Fußzeile cited_files + verifiziert@sha7) → Quiz (`<details>`-Antworten) → Footer. Self-contained: Inline-CSS/SVG, kein JS.
4. `archive/YYYY-MM-DD-code-explainer.html` schreiben, `index.html` aus Archiv-Listing regenerieren (tech-radar `render_index`-Muster).
5. **Erst danach**: `graphs/*.edges.json` + `state.json` atomar übernehmen. Stdout: Pfade + `@@SUMMARY`.

## Installation, Schedule & Verifikation

```bash
# 1) Lokale Skript-Tests (ohne Backend):
mkdir -p /tmp/ce-test
python3 scripts/explainer/extract_backend_deps.py . /tmp/ce-test   # SVG sichten
python3 scripts/explainer/extract_entities.py     . /tmp/ce-test
python3 scripts/explainer/extract_frontend_deps.py . /tmp/ce-test
python3 scripts/explainer/delta_report.py         . /tmp/ce-test   # ⇒ @@MODE BASELINE
cp scripts/explainer/testdata/sample-explain.json /tmp/ce-test/explain.raw
python3 scripts/explainer/render_page.py /tmp/ce-test /tmp/ce-explainer .  # HTML im Browser

# 2) Workflow seeden (kein Auto-Pool-Sync; Muster scripts/seed-workflows.sh, Key ~/.autotrigger/api-key):
WF_ID=$(curl -sf -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d @workflow-pool/mysuricates-code-explainer.json http://localhost:9090/api/v1/workflows | jq -r '.id')

# 3) Schedule: Freitag 06:30 (nach Tech-Radar 06:00), SKIP:
POST /api/v1/schedules  {workflowId, cronExpression:"0 30 6 ? * FRI", scheduleType:"CRON",
                         conflictBehavior:"SKIP", description:"Code-Explainer — Freitags 06:30"}

# 4) Baseline-Run: POST /api/v1/workflows/$WF_ID/execute  (Body {}), Status via /mysuricates:workflow-status --watch
```

Verifikation Baseline: `explainer/index.html` + Archiv-Seite im Browser (SVGs inline? `<details>`?), `state.json` (`lastExplainedSha` == HEAD, ~10 catalog, 2–3 flows). Danach 1–2 kleine Commits + zweiter manueller Run ⇒ DELTA-Modus, nur stale Q&A neu, Struktur-Diff sichtbar.

## Reihenfolge (inkl. CLAUDE.md-TODO-Pflicht)

1. TODO anlegen (AutoPilot, tags `workflow-execution, feature, documentation`, Claude-Session-ID in Description) → IN_PROGRESS
2. Plan/Spec nach `docs/plans/2026-07-16-code-explainer-design.md`
3. `common.py` + `extract_backend_deps.py` (~1,5 h) → 4. `extract_entities.py` (~1 h) → 5. `extract_frontend_deps.py` (~1 h) → 6. `delta_report.py` (~1 h) → 7. `render_page.py` + Fixture (~3 h; größtes Stück)
8. `.gitignore` `/explainer/` → 9. Workflow-JSON mit ausformulierten Prompts (~1,5 h)
10. Seeden, Schedule, Baseline-Run, Prompts anhand des ersten Outputs nachschärfen (~1 h)
11. TODO DONE, Commit `feat(workflow-pool): Code-Explainer — wöchentlicher Delta-Begleiter mit Q&A-Katalog, Quiz und dot-Diagrammen` (kein Push)

Gesamt ~8–10 h. Schritte 3–7 unabhängig testbar.

## Risiken / Randfälle (im Code adressiert)

- Heredoc-Kollision: JSON-only-Prompt + eindeutiger Delimiter
- Invalides AI-dot: validieren, droppen mit Seiten-Notiz, nie Run-Abbruch
- `lastExplainedSha` nach History-Rewrite: `git cat-file -e`-Check + 7-Tage-Fallback
- Token-Budget Block 1: Caps + Gruppierung; BASELINE ohne Commit-Liste
- Definition-Sync trägt Workflow nach Stable/Test: dort **keinen Schedule** anlegen (kein dot/Repo-Pfad im Container)
- No-Change-Woche: Block-2-Input winzig ⇒ billiger Call, bewusst kein Conditional-Block (YAGNI)
