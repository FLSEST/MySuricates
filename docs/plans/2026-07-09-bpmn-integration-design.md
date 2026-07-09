# BPMN-Integration — Design-Spec

> Stand: 2026-07-09 · Status: validiert (Stufe-0-Proof abgeschlossen, Design vom User freigegeben)

## 1. Ziel & Kontext

MySuricates soll BPMN 2.0 sprechen — in drei Ausbaustufen:

1. **Anzeigen**: Vorhandene Workflows als BPMN-like-Diagramm visualisieren
2. **Bauen**: Von der AI (Wish/Factory) erstellte Workflows als Camunda-taugliches BPMN exportieren — MySuricates als AI-Autorenwerkzeug für Standard-Prozess-Engines
3. **Später**: BPMN importieren/ausführen; weitere Prozess-Formate als zusätzliche Adapter

Entscheidung „Zielumgebung": **Beides, extern zuerst** — Export für Standard-Engines (Camunda, Flowable) vor eigener BPMN-Ausführung. Das kanonische Prozessmodell bleibt das bestehende Workflow-JSON; BPMN ist ein Format-Adapter, kein zweites Modell.

### Verworfene Alternativen

- **AI-Generierung pro Export** (Simple-Block konvertiert Workflow-JSON): funktioniert (siehe Proof), aber nicht-deterministisch und kostet Tokens pro Export. Nur als Stufe-0-Proof genutzt.
- **Eigenständiges Tool/Plugin**: dupliziert Factory/Wishes-Infrastruktur; das bestehende Plugin-System (IntegrationProvider) ist für externe Dienste gedacht, nicht für Formate. Ein Camunda-IntegrationProvider (Deploy) kommt erst in Stufe 3 — als Ergänzung, nicht als Träger.
- **bpmn-js im Frontend**: verletzt die No-external-Dependencies-Regel; unnötig, weil das strukturierte Blockmodell ein einfaches eigenes SVG-Rendering erlaubt.

## 2. Stufe-0-Proof (abgeschlossen 2026-07-09)

Wegwerf-Workflow „BPMN-Export-Proof (Stufe 0)" (simple: Opus konvertiert, shell: Python validiert), getestet gegen zwei echte Workflows:

| Run | Quelle | Härtetest | Ergebnis |
|---|---|---|---|
| 1 | AutoPilot Backend Quality Gate | conditional | ✓ Gateway-Paar (Split mit benannten Flows + Default + Merge), Kommandos in `<script>` erhalten, DI-Layout korrekt |
| 2 | Frequenz-basierte Vokabel-Songs | foreach, 9 KB JSON | ✓ `subProcess` mit `multiInstanceLoopCharacteristics` + inneren Start-/End-Events, semantische Task-Namen aus Prompt-Text |

Beide Outputs wohlgeformt, vollständig verbunden, Camunda-Modeler-tauglich.

**Erkenntnisse fürs Design:**

1. Die Mapping-Tabelle (Abschnitt 4) trägt — sie wurde vom Proof validiert.
2. AI-Generierung ist nicht-deterministisch (Run 1 ohne `<incoming>/<outgoing>`, Run 2 mit; beide halluzinierten eine `exporterVersion`) → Produktion braucht den deterministischen Java-Mapper.
3. Echten AI-Mehrwert gibt es nur bei sprechenden Task-Namen — bewusst NICHT eingebaut (Block-Namen sind UI-editierbar, der Assistent kann sie setzen). YAGNI.

## 3. Architektur

### Backend

- **`ProcessFormatAdapter`** (Interface): `String export(Workflow workflow)`, plus Format-Metadaten (Format-Key, Dateiendung, Content-Type). Import-Methode erst in Stufe 3.
- **`BpmnExportAdapter`** (erste Implementierung): deterministisches Mapping nach Abschnitt 4 + Auto-Layout nach Abschnitt 5. Kein AI-Call, kein DB-Zugriff über die Workflow-Entity hinaus.
- **Endpoint**: `GET /api/v1/workflows/{id}/export/bpmn` → `application/xml`, `Content-Disposition: attachment; filename="<workflow-name>.bpmn"`. Read-only, kein neuer Zustand, keine Transaktions-Fallen (Workflow inkl. Blocks wird in einer kurzen `@Transactional`-Service-Methode geladen, Mapping danach ohne Connection).

### Frontend

- **`BpmnDiagramView`**-Komponente: leichter SVG-Renderer, der BPMN-XML samt DI-Koordinaten zeichnet (Shapes: Events, Tasks, Gateways, Sub-Prozesse; Edges: Waypoint-Polylines mit Pfeilspitzen; Marker: Script, Multi-Instance, Default-Flow-Tick). Keine neue Dependency.
- **Neuer Tab „Diagramm"** im Workflow-Detail — additiv, die Block-Liste bleibt Default-Ansicht.
- **Export-Button** (.bpmn-Download) im Workflow-Detail-Header.
- i18n: de + en gemeinsam pflegen; API-Client-Methode am Singleton `api`.

## 4. Mapping-Referenz (alle 14 Block-Typen)

Es gibt keinen „nicht exportierbar"-Fall — jeder Typ hat ein definiertes Ziel:

| Block-Typ | BPMN-Element | Anmerkung |
|---|---|---|
| shell | `scriptTask` | Kommando im `<script>`-Element (scriptFormat="bash") |
| simple | `serviceTask` | Prompt (erste Zeile, gekürzt) als Doku |
| agentic | `serviceTask` | Ziel/Iterationslimit in `documentation` |
| skill-capability | `serviceTask` | Skill-Name in `documentation` |
| integration | `serviceTask` | Provider/Action in `documentation` |
| document | `serviceTask` | Quelle in `documentation` |
| approval | `userTask` | Frage/Optionen in `documentation` |
| human-input | `userTask` | Eingabefelder in `documentation` |
| conditional | `exclusiveGateway`-Paar | Split mit benannten then/else-Flows, else als Default-Flow, Merge-Gateway |
| switch | `exclusiveGateway`-Paar | N benannte Pfade + Default + Merge |
| foreach | `subProcess` (expanded) | `multiInstanceLoopCharacteristics isSequential="true"`, innere Blöcke rekursiv gemappt, eigene innere Start-/End-Events |
| while | `subProcess` (expanded) | `standardLoopCharacteristics`, Bedingung in `documentation` |
| retry | Task des inneren Blocks | Namenssuffix „(bis zu N Versuche)" |
| sub-workflow | `callActivity` | `calledElement` = Ziel-Workflow-ID |

- **Prozess-Rahmen**: genau ein `<process>` mit `startEvent` + `endEvent`; Stufe 1 immer plain `startEvent` (Timer-/Message-Start-Events für Schedule/Webhook-Trigger: Stufe 2).
- **Task-Namen**: `block.name` falls gesetzt, sonst deterministisch Typ + erste Zeile des Commands, gekürzt auf 60 Zeichen (kein AI-Call).
- **IDs**: stabil und sprechend aus Index + Slug (`task_2_analyse`), damit Diffs zwischen Exporten lesbar bleiben.
- **Unbekannter (künftiger) Blocktyp**: generischer `task` + XML-Kommentar als Warnung; Export schlägt nie fehl.
- **Workflow ohne Blöcke**: minimaler Prozess Start → Ende.

## 5. Layout (BPMN-DI)

Links-nach-rechts-Fluss auf einer Hauptachse (Tasks 100×80 bei y=160, Events 36×36, Gateways 50×50 auf Mittellinie y=200), horizontaler Abstand 150 px. Verzweigungszweige (conditional/switch) vertikal versetzt (+150 px pro Zweig), Kanten als Rechtwinkel-Waypoints. Sub-Prozesse werden rekursiv gelayoutet; ihre Breite ergibt sich aus dem Inhalt. Das Blockmodell ist strukturiert (Liste + Verschachtelung), daher ist das ein einfacher rekursiver Algorithmus, kein generisches Graph-Layout.

## 6. Verlustfreiheit

Jedes gemappte Element trägt `extensionElements` mit MySuricates-Namespace (`xmlns:mysuricates`), darin die Original-Block-Config als JSON (`<mysuricates:blockConfig>`), plus menschenlesbare `documentation`. Damit ist der Export roundtrip-fähig: Der spätere Import (Stufe 3) stellt die exakte Block-Config wieder her, statt sie aus BPMN-Semantik zu raten. Externe Engines ignorieren fremde extensionElements laut BPMN-Spec.

## 7. Tests

- **Unit (Backend)**: Golden-File-Test pro Blocktyp (`src/test/resources/bpmn/`); zwei Invarianten-Tests über alle Fixtures — (a) jeder Flow-Node via `sequenceFlow` verbunden, (b) jede Shape/Edge hat DI-Koordinaten. Das sind die Checks des Stufe-0-Validators, nach Java portiert.
- **XSD-Validierung**: BPMN-2.0-XSD als Test-Resource eingecheckt (Datei, keine Library-Dependency) — jeder Fixture-Export wird gegen das Schema validiert.
- **Frontend**: Komponententest für `BpmnDiagramView` (Shapes/Edges aus Beispiel-XML), `npx tsc --noEmit` + `npm run lint`.

## 8. Stufenplan & Nicht-Ziele

| Stufe | Inhalt | Status |
|---|---|---|
| 0 | AI-Proof als Workflow | ✓ abgeschlossen |
| 1 | Adapter + Endpoint + Diagramm-Tab + Export-Button + Tests | **diese Spec** |
| 2 | Export-Sichtbarkeit im Factory-/Wish-Ergebnis; Timer-/Message-Start-Events aus Triggern | danach, klein |
| 3 | BPMN-Import (Subset), Camunda-IntegrationProvider (Deploy), zweiter Format-Adapter | eigene Spec |

**Nicht-Ziele (Stufe 1):** AI-Label-Veredelung, BPMN-Import, BPMN-Ausführung in MySuricates, grafisches Editieren im Diagramm-Tab (View only), bpmn-js oder andere neue Dependencies.
