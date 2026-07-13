# BPMN-Import (Stufe 3, Roundtrip) — Design-Spec

> Stand: 2026-07-13 · Status: Design vom User freigegeben (Review der Spec folgt später)

## 1. Ziel & Scope

MySuricates soll BPMN 2.0 XML wieder in Workflows **importieren** — als Gegenstück zum bestehenden Export. Diese Spec deckt **nur den Roundtrip** ab: Import unserer **eigenen** Exporte (BPMN mit `mysuricates:blockConfig`-Erweiterung). Fremd-BPMN (Camunda Modeler o. ä. ohne unsere Erweiterung) wird sauber abgelehnt; ein vollwertiger Fremd-Import ist eine spätere, eigene Spec.

**Entscheidungen (Brainstorming):**
- Scope: nur Roundtrip, deterministisch, keine neue Dependency, kein AI-Call.
- Ergebnis: jeder Import legt **immer einen neuen** Workflow an; Ziel-Projekt wählbar; Name aus `<process name>`.
- Einstieg: POST-Endpoint **und** Upload-Knopf in der Workflow-Liste.
- Namen: verlustfrei — der Export wird minimal ergänzt (Block-Name als Attribut im `blockConfig`), damit der Import Name+Typ+Config exakt wiederherstellt.
- Fremd-BPMN: sauber ablehnen mit Hinweis, nichts anlegen.

### Verworfene Alternativen
- **Generische BPMN-Bibliothek** (Camunda Model API): verletzt No-Dependencies-Regel; unnötig für das eigene, bekannte Format.
- **KI-gestützter Import**: nicht-deterministisch, Token-Kosten — dieselbe Begründung wie beim Export-Stufe-0-Proof.

## 2. Architektur (Backend)

### `ProcessFormatAdapter` — Import-Methode
Das Interface bekommt eine zweite Methode:
```java
ImportedWorkflow importFrom(String content);   // parst + validiert; wirft BpmnImportException
```
`ImportedWorkflow` ist ein Record `{ String name; List<BlockDefinition> blocks; }` — bewusst **kein** fertiger `Workflow`, damit der Controller Projekt-Zuordnung und Persistenz steuert.

### `BpmnImportAdapter` (neu, neben `BpmnExportAdapter`)
1. XML mit JDK-DOM parsen (namespace-aware). Wohlgeformtheit-Fehler → `BpmnImportException`.
2. `<process>`-Element finden; `name`-Attribut = Workflow-Name (Fallback „Importierter Workflow").
3. Über die **direkten Kind-Flow-Knoten** von `<process>` iterieren (Dokumentreihenfolge). Für jeden Knoten mit `extensionElements/mysuricates:blockConfig`:
   - `type` (Attribut), `name` (Attribut, optional), Config aus dem JSON-Textinhalt (injizierter `ObjectMapper`) → `BlockDefinition{type, name, config}`.
4. **Null blockConfig-Elemente → `BpmnImportException` „stammt nicht aus MySuricates".**

**Warum reicht „direkte Kinder von `<process>` mit blockConfig, in Dokumentreihenfolge"?** Der Export schreibt je Block **genau ein** blockConfig-tragendes Element, als direktes Kind von `<process>`, in Blockreihenfolge: `singleTask` (Task), `buildConditional`/`buildSwitch` (Split-Gateway), `buildLoop` (Sub-Prozess). Verschachtelte Switch-Cases stecken vollständig im Config-JSON des Eltern-Blocks (`config.cases[].blocks`), nicht als eigene Elemente. Innere Sub-Prozess-Knoten (Start/Body/Ende) tragen kein blockConfig und sind keine direkten Kinder von `<process>`. Also = exakte Block-Liste, ohne Flow-Walking.

### Export-Mini-Ergänzung (verlustfreie Namen)
- `BpmnModel.Node` bekommt ein Feld `String blockName`.
- `BpmnModelBuilder` setzt es in `singleTask`/`buildLoop` aus `block.getName()` — **nur wenn nicht leer**.
- `BpmnXmlRenderer` schreibt es als `name`-Attribut aufs `blockConfig`-Element:
  `<mysuricates:blockConfig type="shell" name="Maven Tests">{…}</mysuricates:blockConfig>`.
- Golden-File `linear.bpmn` wird dadurch einmal neu erzeugt (Fixture „Linear Demo" hat den benannten Block „Daten sammeln").

### Endpoint
`POST /api/v1/workflows/import/bpmn?projectId={id}`, Body = BPMN-XML (`Content-Type: application/xml`).
Der Controller:
1. `bpmnImportAdapter.importFrom(xml)` → `ImportedWorkflow`.
2. `CreateWorkflowRequest{name, projectId, blocks}` bauen, `workflowService.create(...)`.
3. **201** mit dem neuen Workflow (`WorkflowResponse`).
`BpmnImportException` → **400** mit klarer Meldung über den bestehenden `GlobalExceptionHandler`.

## 3. Frontend (Upload)

- **„BPMN importieren"** in `WorkflowListToolbar`/`WorkflowListHeader`, neben dem bestehenden JSON-Import. Klick öffnet versteckten `<input type="file" accept=".bpmn,.xml">`.
- Ablauf: Datei via `file.text()` lesen → (bei mehreren Projekten) kleiner Projekt-Auswahl-Dialog (Default: aktuell gefiltertes/erstes Projekt; bei nur einem: überspringen) → `POST …/import/bpmn?projectId` mit XML als Body (raw `fetch` mit `X-API-Key`, analog `fetchWorkflowBpmn`).
- Erfolg → Toast „Workflow importiert", `['workflows']` invalidieren, zum neuen Workflow-Detail navigieren.
- Fehler (400) → `apiErrorMessage(err)` im Toast (Backend-Meldung wörtlich).
- API-Client: `importWorkflowBpmn(xml, projectId): Promise<Workflow>`; Hook `useImportWorkflowBpmn` (invalidiert Liste im `onSuccess`).
- i18n (de+en): `importBpmn`, `bpmnImported`, `selectProjectForImport`.
- **Kein** Vorschau-/Bestätigungsschritt (Entscheidung „immer neuer Workflow"); Versionierung fängt Fehlgriffe ab.

## 4. Fehlerfälle (alle → 400, klare Meldung)

| Fall | Verhalten |
|---|---|
| Kaputtes/nicht wohlgeformtes XML | „Ungültiges BPMN-XML" |
| Kein `<process>` | „Keine Prozess-Definition gefunden" |
| Kein einziges `mysuricates:blockConfig` | „Diese BPMN stammt nicht aus MySuricates — Fremd-Import folgt später" |
| `blockConfig` mit ungültigem JSON | „Block N: Konfiguration nicht lesbar" (Index nennen) |
| Unbekannter `type` | **akzeptieren** (as-is) — Roundtrip-Typen sind valide; Editor/Executor behandeln Unbekanntes bereits |
| Leerer Prozess (nur Start→Ende) | leerer Workflow (0 Blöcke), gültig |

Nichts wird angelegt, wenn der Import scheitert (Parsen + Validieren vor `create`).

## 5. Tests

- **Roundtrip-Beweis (Kernstück):** Für jede bestehende Export-Fixture (linear, conditional, switch, loop/while, mix, Sonderzeichen) — `export(wf)` → `importFrom(xml)` → assert die zurückgegebene Block-Liste **entspricht exakt** `workflow.blocks` (Typ, Name, Config je Block). Beweist Verlustfreiheit inkl. Namen und verschachtelter Switch-Cases in einem Test.
- **Namen-Roundtrip:** benannter + namenloser Block → nach Roundtrip Name gesetzt bzw. `null` (nicht der abgeleitete „KI: …"-Text).
- **Ablehnung:** valides BPMN ohne unsere Erweiterung → `BpmnImportException`; kaputtes XML → Exception; jeweils erwartete Meldung.
- **Controller-Test:** POST mit gültigem XML → 201 + Workflow im richtigen Projekt; Fremd-BPMN → 400.
- **Frontend:** Komponententest Import-Button (Dateiwahl → `fetch` mit korrektem projectId; Erfolg navigiert, Fehler zeigt Backend-Meldung); `npx tsc --noEmit` + `npm run lint`.
- **Test-Instanz-Verifikation:** Wegwerf-Backend (Worktree, eigener Port/DB): echten Workflow anlegen → exportieren → importieren → beide Block-Listen identisch; zusätzlich eine Fremd-BPMN hochladen → 400. Kein Eingriff in Dev/Stable.

## 6. Nicht-Ziele

- Fremd-BPMN-Import (heuristisches Mapping) — eigene spätere Spec.
- Import bestehender Workflows überschreiben — nur neue anlegen.
- Vorschau-/Diff-Ansicht vor dem Anlegen.
- Import von Chains oder anderen Formaten.
