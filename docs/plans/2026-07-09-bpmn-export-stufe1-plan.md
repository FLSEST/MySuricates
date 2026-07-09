# BPMN-Export Stufe 1 — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deterministischer BPMN-2.0-Export für MySuricates-Workflows (Backend-Adapter + REST-Endpoint) plus BPMN-Diagramm-Tab und .bpmn-Download im Frontend.

**Architecture:** Ein `ProcessFormatAdapter`-Interface mit `BpmnExportAdapter` als erster Implementierung. Der Adapter baut aus `workflow.getBlocks()` ein internes Modell inkl. Koordinaten (`BpmnModelBuilder`), das ein DOM-Renderer (`BpmnXmlRenderer`) zu BPMN-XML serialisiert — reines JDK-XML, kein AI-Call. Das Frontend rendert das XML mit einer eigenen SVG-Komponente (kein bpmn-js).

**Tech Stack:** Java 21 / Spring Boot (Package-Root `com.automation`), JDK-`javax.xml` (DOM, Transformer, XSD-Validator), JUnit 5 + AssertJ + Mockito; React + TypeScript, TanStack Query, Vitest + Testing Library, lucide-react, TailwindCSS.

**Spec:** [2026-07-09-bpmn-integration-design.md](2026-07-09-bpmn-integration-design.md)

## Global Constraints

- **Keine neuen Dependencies** — weder Maven noch npm. XML-Erzeugung/Validierung nur mit JDK-Bordmitteln, Diagramm-Rendering nur mit eigenem SVG.
- **Wichtige Spec-Präzisierung** (bei Codebase-Recherche entdeckt): `conditional`/`foreach`/`while`/`retry` haben KEINE verschachtelten Block-Listen, sondern flache Command-Strings (`then_command`, `else_command`, `command`). Nur `switch` hat echte Sub-Block-Listen (`cases[].blocks`, `default`). Das Mapping bleibt wie in der Spec, die Rekursion braucht nur `switch`.
- **CLAUDE.md-Pflichten gelten**: Vor Implementierungsbeginn ein TODO im AutoPilot-Projekt anlegen und auf IN_PROGRESS setzen; nach Abschluss DONE. Tests müssen vor jedem Commit grün sein.
- **Nie `mvn clean` im Haupt-Checkout** — das Dev-Backend läuft aus dem shared `target/`. Implementierung in einem Git-Worktree (superpowers:using-git-worktrees) ausführen.
- **Backend-Kommandos** in `ai-automation-backend/`, **Frontend-Kommandos** in `ai-automation-frontend/` ausführen.
- **i18n**: `de.ts` und `en.ts` immer zusammen ändern (de.ts ist gegen `TranslationKeys` aus en.ts typisiert).
- **Nicht anfassen**: lokal modifizierte Dateien `ai-automation-backend/src/main/resources/docs/*.md` — gehören nicht zu diesem Feature, nie mit committen.
- **Commit-Messages** enden mit `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `ProcessFormatAdapter`-Interface + `BpmnNames` (IDs & Task-Namen)

**Files:**
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/ProcessFormatAdapter.java`
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnNames.java`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnNamesTest.java`

**Interfaces:**
- Consumes: nichts (Startpunkt)
- Produces: `ProcessFormatAdapter { String formatKey(); String fileExtension(); String contentType(); String export(Workflow workflow); }` — von Task 6 implementiert, von Task 7 injiziert. `BpmnNames.taskName(String blockName, String typeLabel, String command): String` und `BpmnNames.elementId(String base, String name): String` — von Task 2–5 genutzt.

- [ ] **Step 1: Failing Test schreiben**

```java
package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnNamesTest {

    @Test
    void blockNameGewinntImmer() {
        assertThat(BpmnNames.taskName("Maven Tests", "Shell", "mvn test -q")).isEqualTo("Maven Tests");
    }

    @Test
    void ohneNamenLabelPlusErsteZeileGekuerzt() {
        String command = "Analysiere die folgenden Logs und erstelle einen ausführlichen Bericht über alle Fehler\nZeile 2";
        String name = BpmnNames.taskName(null, "KI", command);
        assertThat(name).startsWith("KI: Analysiere die folgenden Logs");
        assertThat(name.length()).isLessThanOrEqualTo(64); // "KI: " + 60
        assertThat(name).endsWith("...");
        assertThat(name).doesNotContain("Zeile 2");
    }

    @Test
    void ohneCommandNurLabel() {
        assertThat(BpmnNames.taskName(null, "Freigabe", null)).isEqualTo("Freigabe");
        assertThat(BpmnNames.taskName("  ", "Freigabe", "  ")).isEqualTo("Freigabe");
    }

    @Test
    void elementIdIstXmlSicherUndStabil() {
        assertThat(BpmnNames.elementId("b_0", "Maven Tests ausführen!")).isEqualTo("b_0_maven_tests_ausf_hren");
        assertThat(BpmnNames.elementId("b_1", null)).isEqualTo("b_1");
        assertThat(BpmnNames.elementId("b_2", "///")).isEqualTo("b_2");
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnNamesTest`
Expected: COMPILE ERROR („cannot find symbol: BpmnNames")

- [ ] **Step 3: Interface + Utility implementieren**

`ProcessFormatAdapter.java`:

```java
package com.automation.service.export;

import com.automation.entity.Workflow;

/**
 * Adapter für ein externes Prozess-Definitionsformat.
 * Das kanonische Modell bleibt das Workflow-JSON; jedes Format ist ein Adapter (Spec §3).
 */
public interface ProcessFormatAdapter {

    /** Eindeutiger Format-Schlüssel, z. B. "bpmn". */
    String formatKey();

    /** Dateiendung ohne Punkt, z. B. "bpmn". */
    String fileExtension();

    /** HTTP-Content-Type des Exports, z. B. "application/xml". */
    String contentType();

    /** Serialisiert den Workflow deterministisch in das Zielformat. */
    String export(Workflow workflow);
}
```

`BpmnNames.java`:

```java
package com.automation.service.export.bpmn;

final class BpmnNames {

    private static final int MAX_NAME = 60;

    private BpmnNames() {
    }

    /** Task-Name: block.name falls gesetzt, sonst Typ-Label + erste Command-Zeile (max. 60 Zeichen). */
    static String taskName(String blockName, String typeLabel, String command) {
        if (blockName != null && !blockName.isBlank()) {
            return blockName.strip();
        }
        String firstLine = command == null ? "" : command.strip().lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return typeLabel;
        }
        if (firstLine.length() > MAX_NAME) {
            firstLine = firstLine.substring(0, MAX_NAME - 3) + "...";
        }
        return typeLabel + ": " + firstLine;
    }

    /** Stabile, sprechende XML-ID: base + Slug aus dem Namen ([a-z0-9_], max. 30 Slug-Zeichen). */
    static String elementId(String base, String name) {
        if (name == null || name.isBlank()) {
            return base;
        }
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.length() > 30) {
            slug = slug.substring(0, 30).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? base : base + "_" + slug;
    }
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnNamesTest`
Expected: PASS (4 Tests)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export
git commit -m "feat(bpmn): ProcessFormatAdapter-Interface + BpmnNames (IDs, Task-Namen)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Internes Modell + Builder für lineare Workflows

**Files:**
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnModel.java`
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnModelBuilder.java`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnModelBuilderTest.java`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/WorkflowFixtures.java`

**Interfaces:**
- Consumes: `BpmnNames` (Task 1), `com.automation.entity.Workflow` (`getBlocks(): List<BlockDefinition>`, `getName()`), `BlockDefinition` (`getType(): String`, `getName(): String`, `getConfig(): Map<String,Object>`)
- Produces: `BpmnModel` (Klassen `Node`, `Edge`, Enum `Kind`) und `new BpmnModelBuilder(objectMapper).build(workflow): BpmnModel`. Felder von `Node`: `id, kind, name, documentation, configJson, script, calledElement, loop, defaultFlowId, warning, children, childEdges, x, y, w, h`. Felder von `Edge`: `id, sourceRef, targetRef, name, waypoints (List<double[]>)`. Von Task 3–5 erweitert, von Task 5/6 konsumiert.

- [ ] **Step 1: Fixture-Utility + Failing Test schreiben**

`WorkflowFixtures.java`:

```java
package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    static Workflow workflow(String name, List<BlockDefinition> blocks) {
        return Workflow.builder().id("wf-test").name(name).blocks(blocks).build();
    }

    static Workflow linear() {
        return workflow("Linear Demo", List.of(
                block("shell", "Daten sammeln", Map.of("command", "git log --oneline -20")),
                block("simple", null, Map.of("command", "Fasse die Historie zusammen:\n{{ previous_output }}")),
                block("approval", null, Map.of("question", "Bericht freigeben?"))));
    }
}
```

Hinweis: Falls `BlockDefinition` keine Setter hat (kein `@Data`/`@Setter`), stattdessen den vorhandenen Builder oder All-Args-Konstruktor verwenden — in `BlockDefinition.java` nachsehen und die Fixture entsprechend anpassen. Falls `Workflow.builder()` das `blocks`-Feld nicht anbietet, `Workflow w = new Workflow(); w.setName(...); w.setBlocks(...);` verwenden.

`BpmnModelBuilderTest.java`:

```java
package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnModelBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BpmnModel build(com.automation.entity.Workflow wf) {
        return new BpmnModelBuilder(objectMapper).build(wf);
    }

    @Test
    void linearerWorkflowWirdZuStartTasksEnde() {
        BpmnModel model = build(WorkflowFixtures.linear());

        assertThat(model.processName).isEqualTo("Linear Demo");
        assertThat(model.nodes).hasSize(5); // start + 3 Tasks + end
        assertThat(model.nodes.get(0).kind).isEqualTo(BpmnModel.Kind.START_EVENT);
        assertThat(model.nodes.get(1).kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(model.nodes.get(1).name).isEqualTo("Daten sammeln");
        assertThat(model.nodes.get(1).script).isEqualTo("git log --oneline -20");
        assertThat(model.nodes.get(2).kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
        assertThat(model.nodes.get(3).kind).isEqualTo(BpmnModel.Kind.USER_TASK);
        assertThat(model.nodes.get(4).kind).isEqualTo(BpmnModel.Kind.END_EVENT);
        assertThat(model.edges).hasSize(4); // lückenlose Kette
        assertThat(model.nodes.get(1).configJson).contains("git log");
    }

    @Test
    void leererWorkflowErgibtStartDirektZuEnde() {
        BpmnModel model = build(WorkflowFixtures.workflow("Leer", List.of()));

        assertThat(model.nodes).hasSize(2);
        assertThat(model.edges).hasSize(1);
        assertThat(model.edges.get(0).sourceRef).isEqualTo(model.nodes.get(0).id);
        assertThat(model.edges.get(0).targetRef).isEqualTo(model.nodes.get(1).id);
    }

    @Test
    void unbekannterTypWirdGenerischerTaskMitWarnung() {
        BpmnModel model = build(WorkflowFixtures.workflow("Unbekannt", List.of(
                WorkflowFixtures.block("quantum", null, Map.of()))));

        BpmnModel.Node task = model.nodes.get(1);
        assertThat(task.kind).isEqualTo(BpmnModel.Kind.TASK);
        assertThat(task.warning).contains("quantum");
    }

    @Test
    void koordinatenLiegenAufDerHauptachse() {
        BpmnModel model = build(WorkflowFixtures.linear());

        for (BpmnModel.Node n : model.nodes) {
            // Alle Knoten eines linearen Workflows sind auf y=200 zentriert
            assertThat(n.y + n.h / 2).isEqualTo(200.0);
        }
        // X-Positionen streng aufsteigend
        for (int i = 1; i < model.nodes.size(); i++) {
            assertThat(model.nodes.get(i).x).isGreaterThan(model.nodes.get(i - 1).x);
        }
        // Jede Kante hat nach dem Routing mindestens 2 Waypoints
        for (BpmnModel.Edge e : model.edges) {
            assertThat(e.waypoints.size()).isGreaterThanOrEqualTo(2);
        }
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: COMPILE ERROR („cannot find symbol: BpmnModel")

- [ ] **Step 3: Modell + Builder implementieren**

`BpmnModel.java`:

```java
package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;

/** Internes BPMN-Modell: semantische Knoten/Kanten inkl. Diagramm-Koordinaten. */
class BpmnModel {

    enum Kind { START_EVENT, END_EVENT, TASK, SERVICE_TASK, SCRIPT_TASK, USER_TASK, EXCLUSIVE_GATEWAY, SUB_PROCESS, CALL_ACTIVITY }

    String processId;
    String processName;
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();

    static class Node {
        final String id;
        final Kind kind;
        String name;
        String documentation;
        String configJson;
        String script;          // nur SCRIPT_TASK
        String calledElement;   // nur CALL_ACTIVITY
        String loop;            // null | "multiInstanceSequential" | "standardLoop"
        String defaultFlowId;   // nur EXCLUSIVE_GATEWAY mit Default-Pfad
        String warning;         // erzeugt XML-Kommentar (unbekannter Blocktyp)
        final List<Node> children = new ArrayList<>();      // nur SUB_PROCESS
        final List<Edge> childEdges = new ArrayList<>();    // nur SUB_PROCESS
        double x, y, w, h;

        Node(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    static class Edge {
        final String id;
        final String sourceRef;
        final String targetRef;
        String name;
        final List<double[]> waypoints = new ArrayList<>();

        Edge(String id, String sourceRef, String targetRef) {
            this.id = id;
            this.sourceRef = sourceRef;
            this.targetRef = targetRef;
        }
    }
}
```

`BpmnModelBuilder.java` (in diesem Task nur der lineare Teil — `buildBlock` behandelt alle Typen als Einzel-Task; Gateways/Schleifen kommen in Task 3/4):

```java
package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnModel.Kind.CALL_ACTIVITY;
import static com.automation.service.export.bpmn.BpmnModel.Kind.END_EVENT;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SCRIPT_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SERVICE_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.START_EVENT;
import static com.automation.service.export.bpmn.BpmnModel.Kind.TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.USER_TASK;

/**
 * Baut aus einem Workflow das interne BPMN-Modell inkl. Koordinaten.
 * Deterministisch, kein AI-Call (Spec §3). Eine Instanz pro build()-Aufruf.
 */
class BpmnModelBuilder {

    // Layout-Konstanten (Spec §5)
    static final double TASK_W = 100, TASK_H = 80, EVT = 36, GW = 50;
    static final double GAP = 150, MAIN_Y = 200, ROW_STEP = 150, SUB_PAD = 40;

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("simple", "KI"), Map.entry("shell", "Shell"), Map.entry("agentic", "Agent"),
            Map.entry("skill-capability", "Skill"), Map.entry("integration", "Integration"),
            Map.entry("document", "Dokument"), Map.entry("approval", "Freigabe"),
            Map.entry("human-input", "Eingabe"), Map.entry("sub-workflow", "Sub-Workflow"),
            Map.entry("retry", "Retry"));

    private final ObjectMapper objectMapper;
    private final Map<String, BpmnModel.Node> nodeIndex = new HashMap<>();
    private int flowCounter = 0;

    BpmnModelBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    BpmnModel build(Workflow workflow) {
        BpmnModel model = new BpmnModel();
        model.processName = workflow.getName() == null || workflow.getName().isBlank() ? "Workflow" : workflow.getName();
        model.processId = BpmnNames.elementId("process", model.processName);

        BpmnModel.Node start = node("start_event", START_EVENT, "Start", EVT, EVT);
        BpmnModel.Node end = node("end_event", END_EVENT, "Ende", EVT, EVT);
        place(start, 0, 0);

        List<BlockDefinition> blocks = workflow.getBlocks() == null ? List.of() : workflow.getBlocks();
        Segment chain = buildChain(blocks, "b");
        chain.translate(EVT + GAP, 0);

        double endX = chain.entryId == null ? EVT + GAP : EVT + GAP + chain.width + GAP;
        place(end, endX, 0);

        model.nodes.add(start);
        model.nodes.addAll(chain.nodes);
        model.nodes.add(end);
        model.edges.addAll(chain.edges);
        if (chain.entryId == null) {
            model.edges.add(edge(start.id, end.id, null));
        } else {
            model.edges.add(edge(start.id, chain.entryId, null));
            model.edges.add(edge(chain.exitId, end.id, null));
        }

        Segment.translateNodes(model.nodes, 100, MAIN_Y);
        model.edges.forEach(this::route);
        routeChildEdges(model.nodes);
        return model;
    }

    /** Horizontale Kette aus Blöcken; Koordinaten relativ (Zeilenmitte y=0, Beginn x=0). */
    private Segment buildChain(List<BlockDefinition> blocks, String idPrefix) {
        Segment seg = new Segment();
        double cursor = 0;
        String prevExit = null;
        for (int i = 0; i < blocks.size(); i++) {
            Segment part = buildBlock(blocks.get(i), idPrefix + "_" + i);
            part.translate(cursor, 0);
            seg.nodes.addAll(part.nodes);
            seg.edges.addAll(part.edges);
            if (seg.entryId == null) {
                seg.entryId = part.entryId;
            }
            if (prevExit != null) {
                seg.edges.add(edge(prevExit, part.entryId, null));
            }
            prevExit = part.exitId;
            cursor += part.width + GAP;
        }
        seg.exitId = prevExit;
        seg.width = blocks.isEmpty() ? 0 : cursor - GAP;
        return seg;
    }

    private Segment buildBlock(BlockDefinition block, String idBase) {
        // Task 3 ergänzt: case "conditional" / "switch"; Task 4 ergänzt: case "foreach" / "while"
        return singleTask(block, idBase);
    }

    /** Einzelner Block -> ein Task-Knoten. */
    private Segment singleTask(BlockDefinition block, String idBase) {
        String type = block.getType() == null ? "" : block.getType();
        Map<String, Object> config = block.getConfig();
        BpmnModel.Kind kind = switch (type) {
            case "shell" -> SCRIPT_TASK;
            case "simple", "agentic", "skill-capability", "integration", "document" -> SERVICE_TASK;
            case "approval", "human-input" -> USER_TASK;
            case "sub-workflow" -> CALL_ACTIVITY;
            case "retry" -> "shell".equals(str(config, "execution_mode")) ? SCRIPT_TASK : SERVICE_TASK;
            default -> TASK;
        };
        String label = TYPE_LABELS.getOrDefault(type, type.isBlank() ? "Task" : type);
        String name = BpmnNames.taskName(block.getName(), label, nameSource(block));
        if ("retry".equals(type)) {
            name = name + " (bis zu " + intVal(config, "max_retries", 3) + " Versuche)";
        }

        BpmnModel.Node n = node(BpmnNames.elementId(idBase, name), kind, name, TASK_W, TASK_H);
        n.documentation = documentationFor(block);
        n.configJson = toJson(config);
        if (kind == SCRIPT_TASK) {
            n.script = str(config, "command");
        }
        if (kind == CALL_ACTIVITY) {
            n.calledElement = str(config, "workflow_id");
        }
        if (kind == TASK) {
            n.warning = "Unbekannter Block-Typ: " + type;
        }
        place(n, 0, 0);

        Segment seg = new Segment();
        seg.nodes.add(n);
        seg.entryId = n.id;
        seg.exitId = n.id;
        seg.width = TASK_W;
        return seg;
    }

    /** Task für einen flachen Command-String (conditional-Zweige, Schleifen-Body). */
    BpmnModel.Node commandTask(String id, String command, boolean shellMode) {
        BpmnModel.Node n = node(id, shellMode ? SCRIPT_TASK : SERVICE_TASK, null, TASK_W, TASK_H);
        n.name = BpmnNames.taskName(null, shellMode ? "Shell" : "KI", command);
        n.documentation = firstLine(command, 200);
        if (shellMode) {
            n.script = command;
        }
        return n;
    }

    private String nameSource(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> str(c, "goal");
            case "approval" -> str(c, "question");
            case "human-input" -> str(c, "prompt");
            case "integration" -> joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> str(c, "file_path");
            case "skill-capability" -> str(c, "capability");
            default -> str(c, "command");
        };
    }

    private String documentationFor(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> "Ziel: " + str(c, "goal");
            case "approval" -> "Freigabefrage: " + str(c, "question");
            case "human-input" -> "Eingabe: " + str(c, "prompt");
            case "integration" -> "Integration: " + joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> "Dokument: " + str(c, "file_path");
            case "skill-capability" -> "Skill-Capability: " + str(c, "capability");
            case "while" -> "Wiederholen solange: " + firstLine(str(c, "condition_command"), 200);
            case "foreach" -> "Iteriert über: " + str(c, "variable_name");
            case "retry" -> "Automatische Wiederholung bei Fehler";
            default -> firstLine(str(c, "command"), 200);
        };
    }

    // ---- Basis-Helfer ----

    BpmnModel.Node node(String id, BpmnModel.Kind kind, String name, double w, double h) {
        BpmnModel.Node n = new BpmnModel.Node(uniqueId(id), kind);
        n.name = name;
        n.w = w;
        n.h = h;
        nodeIndex.put(n.id, n);
        return n;
    }

    private String uniqueId(String id) {
        String candidate = id;
        int suffix = 2;
        while (nodeIndex.containsKey(candidate)) {
            candidate = id + "_" + suffix++;
        }
        return candidate;
    }

    /** Setzt x und zentriert den Knoten vertikal auf rowCenterY. */
    void place(BpmnModel.Node n, double x, double rowCenterY) {
        n.x = x;
        n.y = rowCenterY - n.h / 2;
    }

    BpmnModel.Edge edge(String sourceRef, String targetRef, String name) {
        BpmnModel.Edge e = new BpmnModel.Edge("flow_" + (flowCounter++), sourceRef, targetRef);
        e.name = name;
        return e;
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    static String str(Map<String, Object> c, String key) {
        Object v = c == null ? null : c.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static int intVal(Map<String, Object> c, String key, int fallback) {
        Object v = c == null ? null : c.get(key);
        return v instanceof Number number ? number.intValue() : fallback;
    }

    static String firstLine(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() > max ? line.substring(0, max - 3) + "..." : line;
    }

    static String joinNonBlank(String left, String right) {
        if (left.isBlank()) {
            return right;
        }
        return right.isBlank() ? left : left + "." + right;
    }

    // ---- Kanten-Routing (rechtwinklig, Spec §5) ----

    private void routeChildEdges(List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            n.childEdges.forEach(this::route);
            routeChildEdges(n.children);
        }
    }

    private void route(BpmnModel.Edge e) {
        BpmnModel.Node s = nodeIndex.get(e.sourceRef);
        BpmnModel.Node t = nodeIndex.get(e.targetRef);
        double sy = s.y + s.h / 2, ty = t.y + t.h / 2;
        if (Math.abs(sy - ty) < 1) {
            e.waypoints.add(new double[]{s.x + s.w, sy});
            e.waypoints.add(new double[]{t.x, ty});
        } else if (ty > sy) { // abwärts: unten aus der Quelle, dann nach rechts ins Ziel
            double sx = s.x + s.w / 2;
            e.waypoints.add(new double[]{sx, s.y + s.h});
            e.waypoints.add(new double[]{sx, ty});
            e.waypoints.add(new double[]{t.x, ty});
        } else { // aufwärts: rechts aus der Quelle, dann oben ins Ziel
            double tx = t.x + t.w / 2;
            e.waypoints.add(new double[]{s.x + s.w, sy});
            e.waypoints.add(new double[]{tx, sy});
            e.waypoints.add(new double[]{tx, t.y + t.h});
        }
    }

    /** Teil-Graph mit relativen Koordinaten; wird beim Einhängen verschoben. */
    static class Segment {
        final List<BpmnModel.Node> nodes = new ArrayList<>();
        final List<BpmnModel.Edge> edges = new ArrayList<>();
        String entryId, exitId;
        double width;

        void translate(double dx, double dy) {
            translateNodes(nodes, dx, dy);
        }

        static void translateNodes(List<BpmnModel.Node> list, double dx, double dy) {
            for (BpmnModel.Node n : list) {
                n.x += dx;
                n.y += dy;
                translateNodes(n.children, dx, dy);
            }
        }
    }
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: PASS (4 Tests)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export
git commit -m "feat(bpmn): internes Modell + Builder für lineare Workflows

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Builder — `conditional` & `switch` (Gateways)

**Files:**
- Modify: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnModelBuilder.java` (Methode `buildBlock` + neue Methoden)
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnModelBuilderTest.java` (erweitern)

**Interfaces:**
- Consumes: `Segment`, `commandTask`, `node/place/edge` aus Task 2. Config-Schlüssel (aus den Executoren verifiziert): conditional → `condition`, `then_command`, `else_command`, `execution_mode`; switch → `expression`, `cases` (Liste von Maps mit `value` + `blocks`), `default` (Liste von Block-Maps).
- Produces: Gateway-Knoten (`Kind.EXCLUSIVE_GATEWAY`) mit gesetztem `defaultFlowId`; benannte Kanten („ja"/„nein" bzw. Case-Wert/„default").

- [ ] **Step 1: Failing Tests ergänzen** (in `BpmnModelBuilderTest`)

```java
    @Test
    void conditionalWirdZuGatewayPaarMitDefaultFlow() {
        BpmnModel model = build(WorkflowFixtures.workflow("Cond", List.of(
                WorkflowFixtures.block("conditional", null, Map.of(
                        "condition", "Tests fehlgeschlagen?",
                        "then_command", "Analysiere die Fehler",
                        "execution_mode", "claude")))));

        List<BpmnModel.Node> gateways = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.EXCLUSIVE_GATEWAY).toList();
        assertThat(gateways).hasSize(2);
        BpmnModel.Node split = gateways.get(0);
        assertThat(split.name).isEqualTo("Tests fehlgeschlagen?");
        assertThat(split.defaultFlowId).isNotNull();
        // Default-Flow (kein else_command) geht direkt zum Merge-Gateway
        BpmnModel.Edge defaultFlow = model.edges.stream()
                .filter(e -> e.id.equals(split.defaultFlowId)).findFirst().orElseThrow();
        assertThat(defaultFlow.targetRef).isEqualTo(gateways.get(1).id);
        // Then-Zweig liegt unterhalb der Hauptachse
        BpmnModel.Node thenTask = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SERVICE_TASK).findFirst().orElseThrow();
        assertThat(thenTask.y + thenTask.h / 2).isEqualTo(350.0); // 200 + ROW_STEP
    }

    @Test
    void switchMitCasesUndDefaultBlocks() {
        BpmnModel model = build(WorkflowFixtures.workflow("Switch", List.of(
                WorkflowFixtures.block("switch", null, Map.of(
                        "expression", "{{ status }}",
                        "cases", List.of(
                                Map.of("value", "RED", "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "echo alarm")))),
                                Map.of("value", "GREEN", "blocks", List.of())),
                        "default", List.of(
                                Map.of("type", "simple", "config", Map.of("command", "Prüfe manuell"))))))));

        List<BpmnModel.Node> gateways = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.EXCLUSIVE_GATEWAY).toList();
        assertThat(gateways).hasSize(2);
        assertThat(gateways.get(0).name).isEqualTo("{{ status }}");
        // RED-Case: scriptTask; leerer GREEN-Case: Direktkante mit Namen "GREEN"
        assertThat(model.nodes.stream().anyMatch(n -> n.kind == BpmnModel.Kind.SCRIPT_TASK
                && "echo alarm".equals(n.script))).isTrue();
        assertThat(model.edges.stream().anyMatch(e -> "GREEN".equals(e.name))).isTrue();
        // Default-Zweig existiert als serviceTask und ist der Default-Flow des Splits
        assertThat(gateways.get(0).defaultFlowId).isNotNull();
        assertThat(model.nodes.stream().anyMatch(n -> n.kind == BpmnModel.Kind.SERVICE_TASK)).isTrue();
    }
```

Zusätzliche Imports im Test: `java.util.List`, `java.util.Map` (bereits vorhanden aus Task 2).

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: FAIL — conditional/switch werden noch als Einzel-Task gemappt (Gateway-Assertions schlagen fehl)

- [ ] **Step 3: Implementieren** — in `buildBlock` den Dispatch erweitern:

```java
    private Segment buildBlock(BlockDefinition block, String idBase) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "conditional" -> buildConditional(block, idBase);
            case "switch" -> buildSwitch(block, idBase);
            // Task 4 ergänzt: case "foreach", "while" -> buildLoop(block, idBase);
            default -> singleTask(block, idBase);
        };
    }
```

Neue Methoden (unter `singleTask` einfügen):

```java
    private Segment buildConditional(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean shellMode = "shell".equals(str(c, "execution_mode"));

        BpmnModel.Node split = node(idBase + "_split", BpmnModel.Kind.EXCLUSIVE_GATEWAY, str(c, "condition"), GW, GW);
        split.configJson = toJson(c);
        BpmnModel.Node merge = node(idBase + "_merge", BpmnModel.Kind.EXCLUSIVE_GATEWAY, null, GW, GW);
        place(split, 0, 0);
        place(merge, GW + GAP + TASK_W + GAP, 0);

        Segment seg = new Segment();
        seg.nodes.add(split);

        BpmnModel.Node thenTask = commandTask(idBase + "_then", str(c, "then_command"), shellMode);
        place(thenTask, GW + GAP, ROW_STEP);
        seg.nodes.add(thenTask);
        seg.edges.add(edge(split.id, thenTask.id, "ja"));
        seg.edges.add(edge(thenTask.id, merge.id, null));

        String elseCommand = str(c, "else_command");
        if (elseCommand.isBlank()) {
            BpmnModel.Edge direct = edge(split.id, merge.id, "nein");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            BpmnModel.Node elseTask = commandTask(idBase + "_else", elseCommand, shellMode);
            place(elseTask, GW + GAP, 2 * ROW_STEP);
            seg.nodes.add(elseTask);
            BpmnModel.Edge elseEdge = edge(split.id, elseTask.id, "nein");
            seg.edges.add(elseEdge);
            seg.edges.add(edge(elseTask.id, merge.id, null));
            split.defaultFlowId = elseEdge.id;
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = GW + GAP + TASK_W + GAP + GW;
        return seg;
    }

    private Segment buildSwitch(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();

        BpmnModel.Node split = node(idBase + "_split", BpmnModel.Kind.EXCLUSIVE_GATEWAY, str(c, "expression"), GW, GW);
        split.configJson = toJson(c);
        BpmnModel.Node merge = node(idBase + "_merge", BpmnModel.Kind.EXCLUSIVE_GATEWAY, null, GW, GW);
        place(split, 0, 0);

        Segment seg = new Segment();
        seg.nodes.add(split);

        List<Map<String, Object>> cases = listOfMaps(c.get("cases"));
        List<Segment> rows = new ArrayList<>();
        List<String> rowNames = new ArrayList<>();
        double maxChainWidth = TASK_W;
        for (int k = 0; k < cases.size(); k++) {
            Segment row = buildChain(toBlockDefinitions(cases.get(k).get("blocks")), idBase + "_c" + k);
            rows.add(row);
            rowNames.add(str(cases.get(k), "value").isBlank() ? "case" + k : str(cases.get(k), "value"));
            maxChainWidth = Math.max(maxChainWidth, row.width);
        }
        List<BlockDefinition> defaultBlocks = toBlockDefinitions(c.get("default"));
        Segment defaultRow = defaultBlocks.isEmpty() ? null : buildChain(defaultBlocks, idBase + "_default");
        if (defaultRow != null) {
            maxChainWidth = Math.max(maxChainWidth, defaultRow.width);
        }

        double mergeX = GW + GAP + maxChainWidth + GAP;
        place(merge, mergeX, 0);

        int rowIndex = 1;
        for (int k = 0; k < rows.size(); k++) {
            rowIndex = attachRow(seg, split, merge, rows.get(k), rowNames.get(k), rowIndex, false);
        }
        if (defaultRow == null) {
            BpmnModel.Edge direct = edge(split.id, merge.id, "default");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            rowIndex = attachRow(seg, split, merge, defaultRow, "default", rowIndex, true);
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = mergeX + GW;
        return seg;
    }

    /** Hängt eine Case-Zeile unterhalb der Hauptlinie ein; leere Zeilen werden zur Direktkante. */
    private int attachRow(Segment seg, BpmnModel.Node split, BpmnModel.Node merge,
                          Segment row, String label, int rowIndex, boolean isDefault) {
        if (row.entryId == null) {
            BpmnModel.Edge direct = edge(split.id, merge.id, label);
            seg.edges.add(direct);
            if (isDefault) {
                split.defaultFlowId = direct.id;
            }
            return rowIndex;
        }
        row.translate(GW + GAP, rowIndex * ROW_STEP);
        seg.nodes.addAll(row.nodes);
        seg.edges.addAll(row.edges);
        BpmnModel.Edge in = edge(split.id, row.entryId, label);
        seg.edges.add(in);
        seg.edges.add(edge(row.exitId, merge.id, null));
        if (isDefault) {
            split.defaultFlowId = in.id;
        }
        return rowIndex + 1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /** Verschachtelte Block-Maps (switch cases[].blocks / default) -> BlockDefinitions. */
    @SuppressWarnings("unchecked")
    private static List<BlockDefinition> toBlockDefinitions(Object value) {
        List<BlockDefinition> result = new ArrayList<>();
        for (Map<String, Object> map : listOfMaps(value)) {
            BlockDefinition b = new BlockDefinition();
            b.setType((String) map.get("type"));
            b.setName((String) map.get("name"));
            Object config = map.get("config");
            b.setConfig(config instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>());
            result.add(b);
        }
        return result;
    }
```

(Setter-Hinweis aus Task 2 gilt auch hier: Konstruktion von `BlockDefinition` an die tatsächliche Klasse anpassen.)

- [ ] **Step 4: Alle Builder-Tests ausführen — müssen bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: PASS (6 Tests)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export
git commit -m "feat(bpmn): conditional/switch -> exklusive Gateway-Paare mit Default-Flow

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Builder — `foreach`/`while` (Sub-Prozesse mit Loop-Markern)

**Files:**
- Modify: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnModelBuilder.java`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnModelBuilderTest.java` (erweitern)

**Interfaces:**
- Consumes: Task 2/3. Config-Schlüssel: foreach → `variable_name`, `command`, `execution_mode`; while → `command`, `condition_command`.
- Produces: `Kind.SUB_PROCESS`-Knoten mit `loop` (`"multiInstanceSequential"` bzw. `"standardLoop"`), `children` (innerer Start → Body-Task → inneres Ende) und `childEdges`.

- [ ] **Step 1: Failing Tests ergänzen**

```java
    @Test
    void foreachWirdZuSubProcessMitMultiInstance() {
        BpmnModel model = build(WorkflowFixtures.workflow("Loop", List.of(
                WorkflowFixtures.block("foreach", null, Map.of(
                        "variable_name", "chunk",
                        "command", "Erstelle einen Song für {{ chunk }}",
                        "execution_mode", "claude")))));

        BpmnModel.Node sub = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SUB_PROCESS).findFirst().orElseThrow();
        assertThat(sub.loop).isEqualTo("multiInstanceSequential");
        assertThat(sub.name).isEqualTo("Für jedes chunk");
        assertThat(sub.children).hasSize(3);
        assertThat(sub.children.get(0).kind).isEqualTo(BpmnModel.Kind.START_EVENT);
        assertThat(sub.children.get(1).kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
        assertThat(sub.children.get(2).kind).isEqualTo(BpmnModel.Kind.END_EVENT);
        assertThat(sub.childEdges).hasSize(2);
        // Kinder liegen innerhalb der SubProcess-Bounds
        for (BpmnModel.Node child : sub.children) {
            assertThat(child.x).isGreaterThanOrEqualTo(sub.x);
            assertThat(child.x + child.w).isLessThanOrEqualTo(sub.x + sub.w);
            assertThat(child.y).isGreaterThanOrEqualTo(sub.y);
            assertThat(child.y + child.h).isLessThanOrEqualTo(sub.y + sub.h);
        }
    }

    @Test
    void whileWirdZuSubProcessMitStandardLoop() {
        BpmnModel model = build(WorkflowFixtures.workflow("While", List.of(
                WorkflowFixtures.block("while", null, Map.of(
                        "command", "npm test 2>&1 | tail -5",
                        "condition_command", "npm test > /dev/null || echo weiter",
                        "execution_mode", "shell")))));

        BpmnModel.Node sub = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SUB_PROCESS).findFirst().orElseThrow();
        assertThat(sub.loop).isEqualTo("standardLoop");
        assertThat(sub.documentation).startsWith("Wiederholen solange:");
        assertThat(sub.children.get(1).kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
    }
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: FAIL — foreach/while werden noch als Einzel-Task gemappt

- [ ] **Step 3: Implementieren** — Dispatch in `buildBlock` erweitern (`case "foreach", "while" -> buildLoop(block, idBase);`) und Methode ergänzen:

```java
    private Segment buildLoop(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean foreach = "foreach".equals(block.getType());
        boolean shellMode = "shell".equals(str(c, "execution_mode"));

        double innerWidth = EVT + GAP + TASK_W + GAP + EVT;
        double subW = innerWidth + 2 * SUB_PAD;
        double subH = TASK_H + 2 * SUB_PAD;

        String label = block.getName() != null && !block.getName().isBlank()
                ? block.getName()
                : (foreach ? "Für jedes " + str(c, "variable_name") : "Schleife bis Bedingung");

        BpmnModel.Node sub = node(idBase + "_loop", BpmnModel.Kind.SUB_PROCESS, label, subW, subH);
        sub.loop = foreach ? "multiInstanceSequential" : "standardLoop";
        sub.documentation = documentationFor(block);
        sub.configJson = toJson(c);
        place(sub, 0, 0);

        BpmnModel.Node innerStart = node(idBase + "_start", BpmnModel.Kind.START_EVENT, null, EVT, EVT);
        BpmnModel.Node body = commandTask(idBase + "_body", str(c, "command"), shellMode);
        BpmnModel.Node innerEnd = node(idBase + "_end", BpmnModel.Kind.END_EVENT, null, EVT, EVT);
        place(innerStart, SUB_PAD, 0);
        place(body, SUB_PAD + EVT + GAP, 0);
        place(innerEnd, SUB_PAD + EVT + GAP + TASK_W + GAP, 0);

        sub.children.add(innerStart);
        sub.children.add(body);
        sub.children.add(innerEnd);
        sub.childEdges.add(edge(innerStart.id, body.id, null));
        sub.childEdges.add(edge(body.id, innerEnd.id, null));

        Segment seg = new Segment();
        seg.nodes.add(sub);
        seg.entryId = sub.id;
        seg.exitId = sub.id;
        seg.width = subW;
        return seg;
    }
```

- [ ] **Step 4: Alle Builder-Tests ausführen — müssen bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnModelBuilderTest`
Expected: PASS (8 Tests)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export
git commit -m "feat(bpmn): foreach/while -> Sub-Prozesse mit Loop-Markern

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `BpmnXmlRenderer` (Modell → BPMN-XML) + XSD-Validierung + Invarianten

**Files:**
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnXmlRenderer.java`
- Create (per Download): `ai-automation-backend/src/test/resources/bpmn/xsd/{BPMN20,BPMNDI,DC,DI,Semantic}.xsd`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnXmlRendererTest.java`

**Interfaces:**
- Consumes: `BpmnModel` (Task 2–4)
- Produces: `new BpmnXmlRenderer().render(model): String` — von Task 6 konsumiert

- [ ] **Step 1: XSDs als Test-Resources einchecken** (Dateien, keine Library — verletzt die No-Dependencies-Regel nicht; URLs am 2026-07-09 verifiziert)

```bash
mkdir -p ai-automation-backend/src/test/resources/bpmn/xsd
cd ai-automation-backend/src/test/resources/bpmn/xsd
for f in BPMN20.xsd BPMNDI.xsd DC.xsd DI.xsd Semantic.xsd; do
  curl -fsSL -o "$f" "https://www.omg.org/spec/BPMN/20100501/$f"
done
ls -la  # 5 Dateien, jeweils > 1 KB
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BpmnXmlRendererTest {

    private static final Set<String> FLOW_NODE_NAMES = Set.of(
            "startEvent", "endEvent", "task", "serviceTask", "scriptTask", "userTask",
            "exclusiveGateway", "subProcess", "callActivity");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String render(com.automation.entity.Workflow wf) {
        return new BpmnXmlRenderer().render(new BpmnModelBuilder(objectMapper).build(wf));
    }

    private List<com.automation.entity.Workflow> alleFixtures() {
        return List.of(
                WorkflowFixtures.linear(),
                WorkflowFixtures.workflow("Leer", List.of()),
                WorkflowFixtures.workflow("Cond", List.of(WorkflowFixtures.block("conditional", null, Map.of(
                        "condition", "Fehler?", "then_command", "Analysiere", "else_command", "Weiter",
                        "execution_mode", "shell")))),
                WorkflowFixtures.workflow("Loop", List.of(WorkflowFixtures.block("foreach", null, Map.of(
                        "variable_name", "item", "command", "Verarbeite {{ item }}")))),
                WorkflowFixtures.workflow("Mix", List.of(
                        WorkflowFixtures.block("shell", null, Map.of("command", "ls -la")),
                        WorkflowFixtures.block("switch", null, Map.of(
                                "expression", "{{ gate }}",
                                "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "echo alarm"))))))),
                        WorkflowFixtures.block("sub-workflow", "Unterprozess", Map.of("workflow_id", "wf-child-1")))));
    }

    @Test
    void alleFixturesSindSchemaValide() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Validator validator = factory
                .newSchema(new File("src/test/resources/bpmn/xsd/BPMN20.xsd"))
                .newValidator();
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            String xml = render(wf);
            assertThatCode(() -> validator.validate(new StreamSource(new StringReader(xml))))
                    .as("XSD-Validierung für Fixture '%s':\n%s", wf.getName(), xml)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void invarianteJederKnotenIstVerbunden() throws Exception {
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            Document doc = parse(render(wf));
            Set<String> nodeIds = new HashSet<>();
            Set<String> referenced = new HashSet<>();
            collect(doc.getDocumentElement(), nodeIds, referenced);
            assertThat(nodeIds).as("Fixture '%s': unverbundene Knoten", wf.getName()).isSubsetOf(referenced);
        }
    }

    @Test
    void invarianteJedesElementHatDiagrammKoordinaten() throws Exception {
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            Document doc = parse(render(wf));
            Set<String> nodeIds = new HashSet<>();
            collect(doc.getDocumentElement(), nodeIds, new HashSet<>());
            Set<String> shapeRefs = new HashSet<>();
            NodeList shapes = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_DI, "BPMNShape");
            for (int i = 0; i < shapes.getLength(); i++) {
                shapeRefs.add(((Element) shapes.item(i)).getAttribute("bpmnElement"));
            }
            assertThat(nodeIds).as("Fixture '%s': Knoten ohne BPMNShape", wf.getName()).isSubsetOf(shapeRefs);

            NodeList edges = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_DI, "BPMNEdge");
            NodeList flows = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_MODEL, "sequenceFlow");
            assertThat(edges.getLength()).isEqualTo(flows.getLength());
            for (int i = 0; i < edges.getLength(); i++) {
                NodeList wps = ((Element) edges.item(i))
                        .getElementsByTagNameNS(BpmnXmlRenderer.NS_DDI, "waypoint");
                assertThat(wps.getLength()).isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    void extensionElementsTragenDieOriginalConfig() {
        String xml = render(WorkflowFixtures.linear());
        assertThat(xml).contains("mysuricates:blockConfig");
        assertThat(xml).contains("git log --oneline -20");
        assertThat(xml).contains("scriptFormat=\"bash\"");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }

    private static void collect(Element element, Set<String> nodeIds, Set<String> referenced) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            if (FLOW_NODE_NAMES.contains(child.getLocalName())) {
                nodeIds.add(child.getAttribute("id"));
            }
            if ("sequenceFlow".equals(child.getLocalName())) {
                referenced.add(child.getAttribute("sourceRef"));
                referenced.add(child.getAttribute("targetRef"));
            }
            collect(child, nodeIds, referenced);
        }
    }
}
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnXmlRendererTest`
Expected: COMPILE ERROR („cannot find symbol: BpmnXmlRenderer")

- [ ] **Step 4: Renderer implementieren**

```java
package com.automation.service.export.bpmn;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

/** Serialisiert das interne Modell zu BPMN-2.0-XML (JDK-DOM, keine externe Library). */
class BpmnXmlRenderer {

    static final String NS_MODEL = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String NS_DI = "http://www.omg.org/spec/BPMN/20100524/DI";
    static final String NS_DC = "http://www.omg.org/spec/DD/20100524/DC";
    static final String NS_DDI = "http://www.omg.org/spec/DD/20100524/DI";
    static final String NS_MYSURICATES = "https://mysuricates.dev/schema/bpmn/1.0";
    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    String render(BpmnModel model) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();

            Element definitions = doc.createElementNS(NS_MODEL, "definitions");
            definitions.setAttribute("id", "Definitions_1");
            definitions.setAttribute("targetNamespace", "http://bpmn.io/schema/bpmn");
            definitions.setAttribute("exporter", "MySuricates");
            definitions.setAttribute("exporterVersion", "1.0");
            definitions.setAttributeNS(XMLNS, "xmlns:bpmndi", NS_DI);
            definitions.setAttributeNS(XMLNS, "xmlns:dc", NS_DC);
            definitions.setAttributeNS(XMLNS, "xmlns:di", NS_DDI);
            definitions.setAttributeNS(XMLNS, "xmlns:mysuricates", NS_MYSURICATES);
            doc.appendChild(definitions);

            Element process = appendChild(doc, definitions, "process");
            process.setAttribute("id", model.processId);
            process.setAttribute("name", model.processName);
            process.setAttribute("isExecutable", "true");
            for (BpmnModel.Node n : model.nodes) {
                appendNode(doc, process, n);
            }
            for (BpmnModel.Edge e : model.edges) {
                appendFlow(doc, process, e);
            }

            appendDiagram(doc, definitions, model);
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException ex) {
            throw new IllegalStateException("BPMN-Rendering fehlgeschlagen", ex);
        }
    }

    private void appendNode(Document doc, Element parent, BpmnModel.Node n) {
        if (n.warning != null) {
            parent.appendChild(doc.createComment(" " + n.warning + " "));
        }
        Element el = appendChild(doc, parent, elementName(n.kind));
        el.setAttribute("id", n.id);
        if (n.name != null && !n.name.isBlank()) {
            el.setAttribute("name", n.name);
        }
        if (n.kind == BpmnModel.Kind.SCRIPT_TASK) {
            el.setAttribute("scriptFormat", "bash");
        }
        if (n.kind == BpmnModel.Kind.CALL_ACTIVITY && n.calledElement != null && !n.calledElement.isBlank()) {
            el.setAttribute("calledElement", n.calledElement);
        }
        if (n.defaultFlowId != null) {
            el.setAttribute("default", n.defaultFlowId);
        }
        // Reihenfolge laut XSD: documentation, extensionElements, loopCharacteristics, script, Kinder
        if (n.documentation != null && !n.documentation.isBlank()) {
            appendChild(doc, el, "documentation").setTextContent(n.documentation);
        }
        if (n.configJson != null) {
            Element ext = appendChild(doc, el, "extensionElements");
            Element cfg = doc.createElementNS(NS_MYSURICATES, "mysuricates:blockConfig");
            cfg.setTextContent(n.configJson);
            ext.appendChild(cfg);
        }
        if ("multiInstanceSequential".equals(n.loop)) {
            appendChild(doc, el, "multiInstanceLoopCharacteristics").setAttribute("isSequential", "true");
        } else if ("standardLoop".equals(n.loop)) {
            appendChild(doc, el, "standardLoopCharacteristics");
        }
        if (n.script != null && !n.script.isBlank()) {
            appendChild(doc, el, "script").setTextContent(n.script);
        }
        for (BpmnModel.Node child : n.children) {
            appendNode(doc, el, child);
        }
        for (BpmnModel.Edge childEdge : n.childEdges) {
            appendFlow(doc, el, childEdge);
        }
    }

    private void appendFlow(Document doc, Element parent, BpmnModel.Edge e) {
        Element flow = appendChild(doc, parent, "sequenceFlow");
        flow.setAttribute("id", e.id);
        flow.setAttribute("sourceRef", e.sourceRef);
        flow.setAttribute("targetRef", e.targetRef);
        if (e.name != null && !e.name.isBlank()) {
            flow.setAttribute("name", e.name);
        }
    }

    private void appendDiagram(Document doc, Element definitions, BpmnModel model) {
        Element diagram = doc.createElementNS(NS_DI, "bpmndi:BPMNDiagram");
        diagram.setAttribute("id", "BPMNDiagram_1");
        definitions.appendChild(diagram);
        Element plane = doc.createElementNS(NS_DI, "bpmndi:BPMNPlane");
        plane.setAttribute("id", "BPMNPlane_1");
        plane.setAttribute("bpmnElement", model.processId);
        diagram.appendChild(plane);
        appendShapes(doc, plane, model.nodes);
        appendEdgesDi(doc, plane, model.edges);
        appendChildDi(doc, plane, model.nodes);
    }

    private void appendShapes(Document doc, Element plane, java.util.List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            Element shape = doc.createElementNS(NS_DI, "bpmndi:BPMNShape");
            shape.setAttribute("id", "shape_" + n.id);
            shape.setAttribute("bpmnElement", n.id);
            if (n.kind == BpmnModel.Kind.SUB_PROCESS) {
                shape.setAttribute("isExpanded", "true");
            }
            Element bounds = doc.createElementNS(NS_DC, "dc:Bounds");
            bounds.setAttribute("x", fmt(n.x));
            bounds.setAttribute("y", fmt(n.y));
            bounds.setAttribute("width", fmt(n.w));
            bounds.setAttribute("height", fmt(n.h));
            shape.appendChild(bounds);
            plane.appendChild(shape);
        }
    }

    private void appendEdgesDi(Document doc, Element plane, java.util.List<BpmnModel.Edge> edges) {
        for (BpmnModel.Edge e : edges) {
            Element edge = doc.createElementNS(NS_DI, "bpmndi:BPMNEdge");
            edge.setAttribute("id", "edge_" + e.id);
            edge.setAttribute("bpmnElement", e.id);
            for (double[] wp : e.waypoints) {
                Element waypoint = doc.createElementNS(NS_DDI, "di:waypoint");
                waypoint.setAttribute("x", fmt(wp[0]));
                waypoint.setAttribute("y", fmt(wp[1]));
                edge.appendChild(waypoint);
            }
            plane.appendChild(edge);
        }
    }

    private void appendChildDi(Document doc, Element plane, java.util.List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            appendShapes(doc, plane, n.children);
            appendEdgesDi(doc, plane, n.childEdges);
            appendChildDi(doc, plane, n.children);
        }
    }

    private static String elementName(BpmnModel.Kind kind) {
        return switch (kind) {
            case START_EVENT -> "startEvent";
            case END_EVENT -> "endEvent";
            case TASK -> "task";
            case SERVICE_TASK -> "serviceTask";
            case SCRIPT_TASK -> "scriptTask";
            case USER_TASK -> "userTask";
            case EXCLUSIVE_GATEWAY -> "exclusiveGateway";
            case SUB_PROCESS -> "subProcess";
            case CALL_ACTIVITY -> "callActivity";
        };
    }

    private static Element appendChild(Document doc, Element parent, String localName) {
        Element el = doc.createElementNS(NS_MODEL, localName);
        parent.appendChild(el);
        return el;
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String serialize(Document doc) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
```

- [ ] **Step 5: Tests ausführen — müssen bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnXmlRendererTest`
Expected: PASS (4 Tests). Falls die XSD-Validierung fehlschlägt: Die Fehlermeldung nennt Element + erwartete Reihenfolge — die Kind-Element-Reihenfolge in `appendNode` gegen die Meldung abgleichen (documentation → extensionElements → loopCharacteristics → script → Kinder).

- [ ] **Step 6: Sichtprüfung** — ein Fixture-XML ausgeben und im Camunda Modeler (falls installiert) oder unter https://demo.bpmn.io öffnen. KEIN Pflicht-Gate, aber bei offensichtlichen Layout-Fehlern (überlappende Shapes) die Konstanten in `BpmnModelBuilder` prüfen.

- [ ] **Step 7: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export ai-automation-backend/src/test/resources/bpmn
git commit -m "feat(bpmn): XML-Renderer (JDK-DOM) + XSD-Validierung + DI-Invarianten

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: `BpmnExportAdapter` (Spring-Komponente)

**Files:**
- Create: `ai-automation-backend/src/main/java/com/automation/service/export/bpmn/BpmnExportAdapter.java`
- Test: `ai-automation-backend/src/test/java/com/automation/service/export/bpmn/BpmnExportAdapterTest.java`

**Interfaces:**
- Consumes: `ProcessFormatAdapter` (Task 1), `BpmnModelBuilder` (Task 2–4), `BpmnXmlRenderer` (Task 5)
- Produces: Spring-Bean `BpmnExportAdapter implements ProcessFormatAdapter` — von Task 7 als `ProcessFormatAdapter` injiziert

- [ ] **Step 1: Failing Test schreiben**

```java
package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnExportAdapterTest {

    private final BpmnExportAdapter adapter = new BpmnExportAdapter(new ObjectMapper());

    @Test
    void metadatenSindKorrekt() {
        assertThat(adapter.formatKey()).isEqualTo("bpmn");
        assertThat(adapter.fileExtension()).isEqualTo("bpmn");
        assertThat(adapter.contentType()).isEqualTo("application/xml");
    }

    @Test
    void exportLiefertVollstaendigesBpmn() {
        String xml = adapter.export(WorkflowFixtures.linear());
        assertThat(xml).startsWith("<?xml");
        assertThat(xml).contains("<definitions");
        assertThat(xml).contains("BPMNDiagram");
        assertThat(xml).contains("name=\"Linear Demo\"");
    }

    @Test
    void exportIstDeterministisch() {
        assertThat(adapter.export(WorkflowFixtures.linear()))
                .isEqualTo(adapter.export(WorkflowFixtures.linear()));
    }

    @Test
    void goldenFileLinearBleibtStabil() throws Exception {
        Path golden = Path.of("src/test/resources/bpmn/golden/linear.bpmn");
        String actual = adapter.export(WorkflowFixtures.linear());
        if (!Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
            fail("Golden-File neu erzeugt — prüfen (z. B. unter https://demo.bpmn.io öffnen) und committen: " + golden);
        }
        assertThat(actual).isEqualTo(Files.readString(golden));
    }
}
```

Zusätzliche Imports: `java.nio.file.Files`, `java.nio.file.Path`, `static org.assertj.core.api.Assertions.fail`.

Anmerkung zur Spec: Dort stehen „Golden-File-Tests pro Blocktyp" — ein Golden-File (linear) plus XSD-Validierung und Invarianten über ALLE Fixtures (Task 5) deckt denselben Regressionsschutz ab; weitere Golden-Files nur bei Bedarf ergänzen (YAGNI). Beim ersten Lauf schlägt der Test absichtlich fehl und erzeugt die Datei — prüfen, committen, erneut laufen lassen.

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnExportAdapterTest`
Expected: COMPILE ERROR

- [ ] **Step 3: Implementieren**

```java
package com.automation.service.export.bpmn;

import com.automation.entity.Workflow;
import com.automation.service.export.ProcessFormatAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** BPMN 2.0 als erster ProcessFormatAdapter (Spec §3): deterministisch, 0 Tokens pro Export. */
@Component
@RequiredArgsConstructor
public class BpmnExportAdapter implements ProcessFormatAdapter {

    private final ObjectMapper objectMapper;

    @Override
    public String formatKey() {
        return "bpmn";
    }

    @Override
    public String fileExtension() {
        return "bpmn";
    }

    @Override
    public String contentType() {
        return "application/xml";
    }

    @Override
    public String export(Workflow workflow) {
        BpmnModel model = new BpmnModelBuilder(objectMapper).build(workflow);
        return new BpmnXmlRenderer().render(model);
    }
}
```

- [ ] **Step 4: Tests ausführen — müssen bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=BpmnExportAdapterTest`
Expected: Erster Lauf FAIL (Golden-File wird erzeugt) → Datei prüfen → zweiter Lauf PASS (4 Tests)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/export ai-automation-backend/src/test/java/com/automation/service/export ai-automation-backend/src/test/resources/bpmn/golden
git commit -m "feat(bpmn): BpmnExportAdapter als Spring-Komponente + Golden-File

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: REST-Endpoint `GET /api/v1/workflows/{id}/export/bpmn`

**Files:**
- Modify: `ai-automation-backend/src/main/java/com/automation/controller/WorkflowController.java` (neues Feld + neue Methode; bestehender JSON-Export liegt unter `@GetMapping("/export/{id}")` — kein Pfadkonflikt)
- Test: `ai-automation-backend/src/test/java/com/automation/controller/WorkflowBpmnExportControllerTest.java`

**Interfaces:**
- Consumes: `ProcessFormatAdapter` (Task 1/6), `WorkflowService.findByIdEager(String): Workflow` (lädt Blocks innerhalb der Transaktion — Mapping läuft danach ohne DB-Connection, wichtig wegen SQLite pool-size=1)
- Produces: `ResponseEntity<String>` mit `application/xml` + `Content-Disposition: attachment; filename="<safeName>.bpmn"`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.service.export.ProcessFormatAdapter;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowBpmnExportControllerTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ProcessFormatAdapter bpmnExportAdapter;

    @InjectMocks
    private WorkflowController controller;

    @Test
    void liefertBpmnAlsXmlAttachment() {
        Workflow wf = Workflow.builder().id("wf-1").name("Mein Workflow!").build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf);
        when(bpmnExportAdapter.export(wf)).thenReturn("<definitions/>");
        when(bpmnExportAdapter.fileExtension()).thenReturn("bpmn");

        ResponseEntity<String> response = controller.exportWorkflowAsBpmn("wf-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Mein_Workflow_.bpmn\"");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/xml");
        assertThat(response.getBody()).isEqualTo("<definitions/>");
    }
}
```

Hinweis: `@InjectMocks` befüllt den `@RequiredArgsConstructor`-Konstruktor — nicht gemockte Parameter werden `null`, was für diesen Test in Ordnung ist. Sollte der Konstruktor sich zur Compile-Zeit ändern, den Test einfach neu kompilieren.

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=WorkflowBpmnExportControllerTest`
Expected: COMPILE ERROR („method exportWorkflowAsBpmn not found")

- [ ] **Step 3: Endpoint implementieren** — in `WorkflowController`:

Neues Feld bei den anderen `private final`-Feldern:

```java
    private final ProcessFormatAdapter bpmnExportAdapter;
```

Neuer Import: `import com.automation.service.export.ProcessFormatAdapter;` sowie (falls nicht vorhanden) `import org.springframework.http.HttpHeaders;` und `import org.springframework.http.MediaType;`.

Neue Methode (neben dem bestehenden JSON-Export):

```java
    /** Exportiert den Workflow als BPMN 2.0 XML (Spec: docs/plans/2026-07-09-bpmn-integration-design.md). */
    @GetMapping(value = "/{id}/export/bpmn", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> exportWorkflowAsBpmn(@PathVariable String id) {
        Workflow workflow = workflowService.findByIdEager(id);
        String xml = bpmnExportAdapter.export(workflow);
        String safeName = workflow.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "." + bpmnExportAdapter.fileExtension() + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
```

- [ ] **Step 4: Tests ausführen — müssen bestehen**

Run: `cd ai-automation-backend && mvn -q test -Dtest=WorkflowBpmnExportControllerTest`
Expected: PASS (1 Test)

- [ ] **Step 5: Backend-Gesamttest**

Run: `cd ai-automation-backend && mvn -q test`
Expected: Alle Tests PASS (keine bestehenden Tests gebrochen — insbesondere Controller-Integrationstests, die den erweiterten Konstruktor betreffen)

- [ ] **Step 6: Manueller Smoke-Test gegen das laufende Dev-Backend** (nur wenn Dev-Backend auf :9090 läuft — NICHT selbst starten/neustarten)

```bash
DEV_KEY="<dev-api-key>"   # siehe CLAUDE.md „API-Key bereitstellen"
WF_ID=$(curl -s http://localhost:9090/api/v1/workflows -H "X-API-Key: $DEV_KEY" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s "http://localhost:9090/api/v1/workflows/$WF_ID/export/bpmn" -H "X-API-Key: $DEV_KEY" | head -20
```

Expected: BPMN-XML beginnend mit `<?xml`. Hinweis: Das laufende Dev-Backend hat den neuen Endpoint erst nach einem Neustart — diesen Schritt dann auf die Verifikations-Phase (Task 10) verschieben und den User um den Neustart bitten.

- [ ] **Step 7: Commit**

```bash
git add ai-automation-backend/src/main/java/com/automation/controller/WorkflowController.java ai-automation-backend/src/test/java/com/automation/controller/WorkflowBpmnExportControllerTest.java
git commit -m "feat(bpmn): Export-Endpoint GET /workflows/{id}/export/bpmn

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Frontend — BPMN-Fetch + Export-Button (.bpmn-Download)

**Files:**
- Modify: `ai-automation-frontend/src/api/client.ts` (neue exportierte Funktion `fetchWorkflowBpmn`)
- Modify: `ai-automation-frontend/src/components/workflow/WorkflowDetailHeader.tsx` (neuer Ghost-Button)
- Modify: `ai-automation-frontend/src/i18n/en.ts` + `ai-automation-frontend/src/i18n/de.ts` (neue Keys)

**Interfaces:**
- Consumes: `downloadBlob(blob, filename)` aus `src/utils/downloadFile.ts`; `useSettingsStore` (bereits in client.ts importiert); bestehender `safeName`-Regex aus `WorkflowDetailHeader.tsx:96-104`
- Produces: `fetchWorkflowBpmn(id: string): Promise<string>` — auch von Task 9 (Hook) konsumiert. i18n-Keys `workflows.exportBpmn`, `workflows.bpmnExportFailed`, `workflows.diagramTab`, `workflows.diagramLoadFailed`.

- [ ] **Step 1: `fetchWorkflowBpmn` in `client.ts` ergänzen** (ans Dateiende, nutzt das bestehende `useSettingsStore`-Import; `api.get` geht nicht, weil es immer JSON parst):

```ts
/** Lädt den BPMN-2.0-Export eines Workflows als XML-String (kein JSON — daher raw fetch). */
export async function fetchWorkflowBpmn(id: string): Promise<string> {
  const headers: HeadersInit = {};
  const apiKey = useSettingsStore.getState().apiKey;
  if (apiKey) headers['X-API-Key'] = apiKey;
  const res = await fetch(`/api/v1/workflows/${id}/export/bpmn`, { headers });
  if (!res.ok) throw new Error(`BPMN export failed (${res.status})`);
  return res.text();
}
```

- [ ] **Step 2: i18n-Keys ergänzen** — in `en.ts` im `workflows:`-Block direkt nach `exportWorkflow`:

```ts
    exportBpmn: 'Export as BPMN',
    bpmnExportFailed: 'BPMN export failed',
    diagramTab: 'Diagram',
    diagramLoadFailed: 'Could not load diagram',
```

In `de.ts` an derselben Stelle:

```ts
    exportBpmn: 'Als BPMN exportieren',
    bpmnExportFailed: 'BPMN-Export fehlgeschlagen',
    diagramTab: 'Diagramm',
    diagramLoadFailed: 'Diagramm konnte nicht geladen werden',
```

- [ ] **Step 3: Export-Button in `WorkflowDetailHeader.tsx`** — neben dem bestehenden JSON-Download-Button (Button-Gruppe Zeile ~57-106). Imports ergänzen: `FileCode2` aus `lucide-react`, `fetchWorkflowBpmn` aus `../../api/client`, `downloadBlob` aus `../../utils/downloadFile` (Pfade an die vorhandenen Imports der Datei angleichen — der JSON-Export dort zeigt das Muster). Handler + Button:

```tsx
  const handleBpmnExport = async () => {
    try {
      const xml = await fetchWorkflowBpmn(workflow.id);
      const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
      downloadBlob(new Blob([xml], { type: 'application/xml' }), `${safeName}.bpmn`);
    } catch {
      // Fehleranzeige analog zu den anderen Header-Aktionen der Datei
      // (falls dort Toasts genutzt werden: toast.error(t.workflows.bpmnExportFailed))
      console.error('BPMN export failed');
    }
  };
```

```tsx
      <Button
        variant="ghost"
        size="sm"
        icon={<FileCode2 className="w-4 h-4" />}
        onClick={handleBpmnExport}
        title={t.workflows.exportBpmn}
        aria-label={t.workflows.exportBpmn}
      />
```

WICHTIG: Beim Einbau prüfen, wie die Datei Fehler anzeigt (Toast-Import vorhanden?) und das dortige Muster übernehmen statt `console.error`.

- [ ] **Step 4: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: 0 Fehler (fehlende de/en-Keys wären Compile-Fehler)

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/api/client.ts ai-automation-frontend/src/components/workflow/WorkflowDetailHeader.tsx ai-automation-frontend/src/i18n/en.ts ai-automation-frontend/src/i18n/de.ts
git commit -m "feat(bpmn): .bpmn-Export-Button im Workflow-Detail-Header

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Frontend — `BpmnDiagramView` (SVG-Renderer) + Diagramm-Tab

**Files:**
- Create: `ai-automation-frontend/src/components/workflow/BpmnDiagramView.tsx`
- Create: `ai-automation-frontend/src/components/workflow/WorkflowDiagramTab.tsx`
- Modify: `ai-automation-frontend/src/pages/WorkflowDetail.tsx` (Tab-Union, Tab-Button, Tab-Content, lazy import)
- Modify: `ai-automation-frontend/src/hooks/useWorkflows.ts` (neuer Hook `useWorkflowBpmn`)
- Test: `ai-automation-frontend/src/components/workflow/__tests__/BpmnDiagramView.test.tsx`

**Interfaces:**
- Consumes: `fetchWorkflowBpmn` (Task 8), Tab-Mechanismus in `WorkflowDetail.tsx:64` (`useState<'blocks' | 'versions'>`), Tab-Leiste `:261-283`, Lazy-Pattern `WorkflowDetail.tsx:5`
- Produces: `BpmnDiagramView({ xml }: { xml: string })` (pure, testbar ohne Mocks); `WorkflowDiagramTab({ workflowId }: { workflowId: string })` (default export); `useWorkflowBpmn(id: string, enabled?: boolean)`

- [ ] **Step 1: Failing Komponententest schreiben**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import BpmnDiagramView from '../BpmnDiagramView';

const SAMPLE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
             id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <process id="p1" name="Demo" isExecutable="true">
    <startEvent id="s1" name="Start" />
    <scriptTask id="t1" name="Maven Tests ausführen" />
    <exclusiveGateway id="g1" name="OK?" />
    <endEvent id="e1" name="Ende" />
    <sequenceFlow id="f1" sourceRef="s1" targetRef="t1" />
    <sequenceFlow id="f2" sourceRef="t1" targetRef="g1" name="ja" />
    <sequenceFlow id="f3" sourceRef="g1" targetRef="e1" />
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="s1"><dc:Bounds x="100" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="t1"><dc:Bounds x="286" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh3" bpmnElement="g1"><dc:Bounds x="536" y="175" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh4" bpmnElement="e1"><dc:Bounds x="736" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="f1"><di:waypoint x="136" y="200"/><di:waypoint x="286" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed2" bpmnElement="f2"><di:waypoint x="386" y="200"/><di:waypoint x="536" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed3" bpmnElement="f3"><di:waypoint x="586" y="200"/><di:waypoint x="736" y="200"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

describe('BpmnDiagramView', () => {
  it('rendert alle Shapes und Kanten aus dem BPMN-DI', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    expect(container.querySelectorAll('svg')).toHaveLength(1);
    expect(container.querySelectorAll('circle')).toHaveLength(2); // Start + Ende
    expect(container.querySelectorAll('rect')).toHaveLength(1); // Task
    expect(container.querySelectorAll('polygon')).toHaveLength(1); // Gateway
    expect(container.querySelectorAll('polyline')).toHaveLength(3); // Kanten
    expect(screen.getByText('Maven Tests ausführen')).toBeInTheDocument();
    expect(screen.getByText('ja')).toBeInTheDocument(); // Kanten-Label
  });

  it('zeigt eine Meldung bei ungültigem XML', () => {
    render(<BpmnDiagramView xml="kein xml" />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd ai-automation-frontend && npx vitest run src/components/workflow/__tests__/BpmnDiagramView.test.tsx`
Expected: FAIL („Cannot find module '../BpmnDiagramView'")

- [ ] **Step 3: `BpmnDiagramView.tsx` implementieren**

```tsx
const NS_MODEL = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const NS_DI = 'http://www.omg.org/spec/BPMN/20100524/DI';
const NS_DC = 'http://www.omg.org/spec/DD/20100524/DC';
const NS_DDI = 'http://www.omg.org/spec/DD/20100524/DI';

interface Shape {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

interface EdgeLine {
  id: string;
  name: string;
  points: [number, number][];
}

interface Diagram {
  shapes: Shape[];
  edges: EdgeLine[];
}

function parseBpmn(xml: string): Diagram | null {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length > 0) return null;
  const byId = new Map<string, Element>();
  for (const el of Array.from(doc.getElementsByTagNameNS(NS_MODEL, '*'))) {
    const id = el.getAttribute('id');
    if (id) byId.set(id, el);
  }
  const shapes: Shape[] = Array.from(doc.getElementsByTagNameNS(NS_DI, 'BPMNShape')).flatMap((s) => {
    const bounds = s.getElementsByTagNameNS(NS_DC, 'Bounds')[0];
    const ref = byId.get(s.getAttribute('bpmnElement') ?? '');
    if (!bounds || !ref) return [];
    return [{
      id: ref.getAttribute('id') ?? '',
      kind: ref.localName,
      name: ref.getAttribute('name') ?? '',
      x: Number(bounds.getAttribute('x')),
      y: Number(bounds.getAttribute('y')),
      w: Number(bounds.getAttribute('width')),
      h: Number(bounds.getAttribute('height')),
    }];
  });
  const edges: EdgeLine[] = Array.from(doc.getElementsByTagNameNS(NS_DI, 'BPMNEdge')).map((e) => ({
    id: e.getAttribute('id') ?? '',
    name: byId.get(e.getAttribute('bpmnElement') ?? '')?.getAttribute('name') ?? '',
    points: Array.from(e.getElementsByTagNameNS(NS_DDI, 'waypoint')).map(
      (w) => [Number(w.getAttribute('x')), Number(w.getAttribute('y'))] as [number, number],
    ),
  }));
  if (shapes.length === 0) return null;
  return { shapes, edges };
}

/** Bricht einen Namen in maximal 3 Zeilen à ~14 Zeichen um (rein visuell). */
function wrapLabel(name: string): string[] {
  const words = name.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let current = '';
  for (const word of words) {
    if (current && (current + ' ' + word).length > 14) {
      lines.push(current);
      current = word;
    } else {
      current = current ? current + ' ' + word : word;
    }
  }
  if (current) lines.push(current);
  return lines.slice(0, 3);
}

function ShapeSvg({ s }: { s: Shape }) {
  const cx = s.x + s.w / 2;
  const cy = s.y + s.h / 2;
  switch (s.kind) {
    case 'startEvent':
    case 'endEvent':
      return (
        <g>
          <circle cx={cx} cy={cy} r={s.w / 2} className="fill-surface stroke-text"
            strokeWidth={s.kind === 'endEvent' ? 3.5 : 1.6} />
          {s.name && <text x={cx} y={s.y + s.h + 16} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
        </g>
      );
    case 'exclusiveGateway':
      return (
        <g>
          <polygon
            points={`${cx},${s.y} ${s.x + s.w},${cy} ${cx},${s.y + s.h} ${s.x},${cy}`}
            className="fill-surface stroke-text" strokeWidth={1.6} />
          <line x1={cx - 8} y1={cy - 8} x2={cx + 8} y2={cy + 8} className="stroke-text" strokeWidth={2.2} />
          <line x1={cx + 8} y1={cy - 8} x2={cx - 8} y2={cy + 8} className="stroke-text" strokeWidth={2.2} />
          {s.name && <text x={cx} y={s.y - 8} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
        </g>
      );
    case 'subProcess':
      return (
        <g>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={12}
            className="fill-accent/5 stroke-text" strokeWidth={1.6} />
          {s.name && <text x={cx} y={s.y + 18} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
        </g>
      );
    default: // task, serviceTask, scriptTask, userTask, callActivity
      return (
        <g>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={10}
            className="fill-surface stroke-text" strokeWidth={1.6} />
          <text x={cx} y={cy - (wrapLabel(s.name).length - 1) * 7} textAnchor="middle" className="fill-text text-[12px]">
            {wrapLabel(s.name).map((line, i) => (
              <tspan key={i} x={cx} dy={i === 0 ? 0 : 14}>{line}</tspan>
            ))}
          </text>
        </g>
      );
  }
}

export default function BpmnDiagramView({ xml }: { xml: string }) {
  const diagram = parseBpmn(xml);
  if (!diagram) {
    return <div role="alert" className="p-4 text-sm text-text-secondary">Invalid BPMN XML</div>;
  }
  const pad = 40;
  const xs = diagram.shapes.flatMap((s) => [s.x, s.x + s.w]);
  const ys = [...diagram.shapes.flatMap((s) => [s.y, s.y + s.h + 20]), ...diagram.edges.flatMap((e) => e.points.map((p) => p[1]))];
  const minX = Math.min(...xs) - pad;
  const minY = Math.min(...ys) - pad;
  const width = Math.max(...xs) + pad - minX;
  const height = Math.max(...ys) + pad - minY;

  return (
    <div className="overflow-x-auto text-text">
      <svg viewBox={`${minX} ${minY} ${width} ${height}`} style={{ minWidth: width * 0.75 }} role="img">
        <defs>
          <marker id="bpmn-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="fill-text" />
          </marker>
        </defs>
        {diagram.edges.map((e) => (
          <g key={e.id}>
            <polyline
              points={e.points.map((p) => p.join(',')).join(' ')}
              fill="none" className="stroke-text" strokeWidth={1.6} markerEnd="url(#bpmn-arrow)" />
            {e.name && e.points.length >= 2 && (
              <text
                x={(e.points[0][0] + e.points[e.points.length - 1][0]) / 2}
                y={(e.points[0][1] + e.points[e.points.length - 1][1]) / 2 - 6}
                textAnchor="middle" className="fill-text-secondary text-[11px]">
                {e.name}
              </text>
            )}
          </g>
        ))}
        {diagram.shapes.map((s) => <ShapeSvg key={s.id} s={s} />)}
      </svg>
    </div>
  );
}
```

Hinweis: Die Tailwind-Klassen (`fill-surface`, `stroke-text`, `fill-text-secondary`, `fill-accent/5`) setzen voraus, dass `surface`/`text`/`accent` als Farb-Tokens in der Tailwind-Config existieren (werden im Projekt durchgängig als `bg-surface`/`text-text`/`border-accent` genutzt). Falls eine Klasse nicht greift (Element bleibt schwarz), in `tailwind.config.js` die exakten Token-Namen nachsehen.

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd ai-automation-frontend && npx vitest run src/components/workflow/__tests__/BpmnDiagramView.test.tsx`
Expected: PASS (2 Tests)

- [ ] **Step 5: Hook + Tab-Wrapper implementieren**

In `src/hooks/useWorkflows.ts` (Import `fetchWorkflowBpmn` aus `../api/client` ergänzen):

```ts
export function useWorkflowBpmn(id: string, enabled = true) {
  return useQuery({
    queryKey: ['workflows', id, 'bpmn'],
    queryFn: () => fetchWorkflowBpmn(id),
    enabled: !!id && enabled,
    staleTime: 30_000,
  });
}
```

`WorkflowDiagramTab.tsx`:

```tsx
import { useT } from '../../i18n';
import { useWorkflowBpmn } from '../../hooks/useWorkflows';
import BpmnDiagramView from './BpmnDiagramView';

export default function WorkflowDiagramTab({ workflowId }: { workflowId: string }) {
  const t = useT();
  const { data: xml, isLoading, isError } = useWorkflowBpmn(workflowId);

  if (isLoading) {
    return <div className="p-6 text-sm text-text-secondary">…</div>;
  }
  if (isError || !xml) {
    return <div role="alert" className="p-6 text-sm text-text-secondary">{t.workflows.diagramLoadFailed}</div>;
  }
  return (
    <div className="border border-border rounded-lg bg-surface p-4">
      <BpmnDiagramView xml={xml} />
    </div>
  );
}
```

(Der Import-Pfad von `useT` muss dem in `WorkflowVersionsTab.tsx` entsprechen — dort nachsehen und übernehmen.)

- [ ] **Step 6: Tab in `WorkflowDetail.tsx` verdrahten**

1. Zeile ~5, neben dem bestehenden lazy import: `const WorkflowDiagramTab = lazy(() => import('../components/workflow/WorkflowDiagramTab'));`
2. Zeile ~64: Union erweitern zu `useState<'blocks' | 'versions' | 'diagram'>('blocks')`
3. In der Tab-Leiste (~261-283) nach dem Blocks-Button einen Button nach exakt dem Muster der bestehenden Buttons ergänzen:

```tsx
          <button
            onClick={() => setActiveTab('diagram')}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'diagram'
                ? 'border-accent text-accent'
                : 'border-transparent text-text-secondary hover:text-text hover:border-border'
            }`}
          >
            {t.workflows.diagramTab}
          </button>
```

4. Beim Tab-Content (nach dem `activeTab === 'versions'`-Block, Muster `:317-321` inkl. `<Suspense>`):

```tsx
          {activeTab === 'diagram' && id && (
            <Suspense fallback={<div className="p-6 text-sm text-text-secondary">…</div>}>
              <WorkflowDiagramTab workflowId={id} />
            </Suspense>
          )}
```

- [ ] **Step 7: Alle Frontend-Checks**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint && npm test`
Expected: 0 Fehler, alle Tests PASS

- [ ] **Step 8: Commit**

```bash
git add ai-automation-frontend/src
git commit -m "feat(bpmn): Diagramm-Tab mit eigenem SVG-Renderer (kein bpmn-js)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: End-to-End-Verifikation + Doku + Abschluss

**Files:**
- Modify: `AGENTS.md` (eine Zeile in der REST-API-Referenz)
- Modify: `CLAUDE.md` (eine Zeile in der Tabelle „Key API Action Endpoints")

**Interfaces:**
- Consumes: alles aus Task 1–9

- [ ] **Step 1: Backend + Frontend Gesamttests**

Run: `cd ai-automation-backend && mvn -q test` und `cd ai-automation-frontend && npx tsc --noEmit && npm run lint && npm test`
Expected: alles grün

- [ ] **Step 2: Browser-Verifikation** (CLAUDE.md-Pflicht: Frontend-Änderungen im Browser verifizieren). Falls das Dev-Backend den neuen Endpoint noch nicht hat: den User um einen Backend-Neustart bitten (NIE eigenständig neu starten — vorher laufende Workflow-Runs prüfen, siehe CLAUDE.md). Dann auf `localhost:3000` (Playwright MCP oder manuell):
  1. Workflow „AutoPilot Backend Quality Gate" öffnen → Tab „Diagramm" → Gateway-Paar mit „ja"/„nein"-Kanten sichtbar, keine überlappenden Shapes
  2. Workflow „Frequenz-basierte Vokabel-Songs" → Tab „Diagramm" → Sub-Prozess sichtbar
  3. Export-Button klicken → `.bpmn`-Datei wird heruntergeladen, beginnt mit `<?xml`
  4. Dark Mode umschalten → Diagramm bleibt lesbar

- [ ] **Step 3: Doku ergänzen** — in `AGENTS.md` in der Workflow-Endpoint-Liste und in `CLAUDE.md` unter „Key API Action Endpoints" je eine Zeile:

```markdown
| Export workflow as BPMN | GET | `/api/v1/workflows/{id}/export/bpmn` |
```

- [ ] **Step 4: TODO abschließen** — das zu Implementierungsbeginn angelegte TODO via `PATCH /api/v1/todos/<id>/status` auf DONE setzen.

- [ ] **Step 5: Abschluss-Commit**

```bash
git add AGENTS.md CLAUDE.md
git commit -m "docs(bpmn): Export-Endpoint in API-Referenzen dokumentiert

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: superpowers:finishing-a-development-branch** aufrufen (Worktree-Integration: Merge in `main` oder PR — Entscheidung beim User einholen).
