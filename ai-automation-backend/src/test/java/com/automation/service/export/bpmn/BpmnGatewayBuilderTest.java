package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnModelBuilder.GAP;
import static com.automation.service.export.bpmn.BpmnModelBuilder.GW;
import static com.automation.service.export.bpmn.BpmnModelBuilder.ROW_STEP;
import static com.automation.service.export.bpmn.BpmnModelBuilder.TASK_H;
import static com.automation.service.export.bpmn.BpmnModelBuilder.TASK_W;
import static com.automation.service.export.bpmn.WorkflowFixtures.block;
import static com.automation.service.export.bpmn.WorkflowFixtures.ordered;
import static org.assertj.core.api.Assertions.assertThat;

/** Layout und Kanten der Gateway-Segmente (conditional/switch) — isoliert von BpmnModelBuilder. */
class BpmnGatewayBuilderTest {

    private final BpmnNodeFactory f = new BpmnNodeFactory(new ObjectMapper());
    private final FakeChains chains = new FakeChains();
    private final BpmnGatewayBuilder builder = new BpmnGatewayBuilder(f, chains);

    /** Eine Aufgabe pro Block, nebeneinander; Knotenhöhe über {@code h} steuerbar. */
    private final class FakeChains implements BpmnGatewayBuilder.ChainBuilder {
        final Map<String, String> registered = new LinkedHashMap<>();
        final List<String> chainPrefixes = new ArrayList<>();
        double taskHeight = TASK_H;

        @Override
        public BpmnSegment buildChain(List<BlockDefinition> blocks, String idPrefix) {
            chainPrefixes.add(idPrefix);
            BpmnSegment seg = new BpmnSegment();
            double x = 0;
            for (int i = 0; i < blocks.size(); i++) {
                BpmnModel.Node n = f.node(BpmnBranchBlocks.member(idPrefix, i), BpmnModel.Kind.TASK,
                        blocks.get(i).getName(), TASK_W, taskHeight);
                n.configJson = "{}";
                n.blockType = blocks.get(i).getType();
                n.blockName = blocks.get(i).getName();
                f.place(n, x, 0);
                if (seg.entryId == null) {
                    seg.entryId = n.id;
                } else {
                    seg.edges.add(f.edge(seg.exitId, n.id, null));
                }
                seg.exitId = n.id;
                seg.nodes.add(n);
                x += TASK_W + GAP;
            }
            seg.width = blocks.isEmpty() ? 0 : x - GAP;
            return seg;
        }

        @Override
        public void registerActivity(String idBase, String nodeId) {
            registered.put(idBase, nodeId);
        }
    }

    private static Map<String, Object> branch(String type, String name) {
        return ordered("type", type, "name", name, "config", ordered("command", "x"));
    }

    private static BpmnModel.Node node(BpmnSegment seg, String id) {
        return seg.nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow();
    }

    private static BpmnModel.Edge edge(BpmnSegment seg, String from, String to) {
        return seg.edges.stream().filter(e -> e.sourceRef.equals(from) && e.targetRef.equals(to))
                .findFirst().orElseThrow();
    }

    @Test
    void conditionalOhneElseFuehrtNeinDirektZumMergeUndMachtDiesenPfadZumDefault() {
        BlockDefinition cond = block("conditional", "Pruefen",
                ordered("condition", "Ist es gruen?", "then_command", "echo ja"));

        BpmnSegment seg = builder.buildConditional(cond, "b_0");

        assertThat(seg.nodes).extracting(n -> n.id).containsExactly("b_0_split", "b_0_then", "b_0_merge");
        BpmnModel.Node split = node(seg, "b_0_split");
        assertThat(split.kind).isEqualTo(BpmnModel.Kind.EXCLUSIVE_GATEWAY);
        assertThat(split.name).isEqualTo("Ist es gruen?");
        assertThat(split.blockType).isEqualTo("conditional");
        assertThat(split.blockName).isEqualTo("Pruefen");
        assertThat(split.configJson).contains("\"then_command\":\"echo ja\"");

        BpmnModel.Edge nein = edge(seg, "b_0_split", "b_0_merge");
        assertThat(nein.name).isEqualTo("nein");
        assertThat(split.defaultFlowId).isEqualTo(nein.id);
        assertThat(edge(seg, "b_0_split", "b_0_then").name).isEqualTo("ja");
        assertThat(edge(seg, "b_0_then", "b_0_merge").name).isNull();

        assertThat(seg.entryId).isEqualTo("b_0_split");
        assertThat(seg.exitId).isEqualTo("b_0_merge");
        assertThat(seg.width).isEqualTo(GW + GAP + TASK_W + GAP + GW);
        // Then-Task eine Zeile unter der Hauptlinie, KI-Modus ohne execution_mode=shell
        BpmnModel.Node then = node(seg, "b_0_then");
        assertThat(then.kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
        assertThat(then.x).isEqualTo(GW + GAP);
        assertThat(then.y).isEqualTo(ROW_STEP - TASK_H / 2);
        assertThat(node(seg, "b_0_merge").x).isEqualTo(GW + GAP + TASK_W + GAP);
        assertThat(chains.registered).containsExactly(Map.entry("b_0_then", "b_0_then"));
    }

    @Test
    void conditionalMitElseLegtZweiteZeileAnUndRegistriertBeideZweigTasks() {
        BlockDefinition cond = block("conditional", null, ordered("condition", "c",
                "execution_mode", "shell", "then_command", "echo a", "else_command", "echo b"));

        BpmnSegment seg = builder.buildConditional(cond, "b_3");

        BpmnModel.Node elseTask = node(seg, "b_3_else");
        assertThat(elseTask.kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(elseTask.script).isEqualTo("echo b");
        assertThat(elseTask.y).isEqualTo(2 * ROW_STEP - TASK_H / 2);
        BpmnModel.Edge elseEdge = edge(seg, "b_3_split", "b_3_else");
        assertThat(elseEdge.name).isEqualTo("nein");
        assertThat(node(seg, "b_3_split").defaultFlowId).isEqualTo(elseEdge.id);
        assertThat(seg.edges).noneMatch(e -> e.sourceRef.equals("b_3_split") && e.targetRef.equals("b_3_merge"));
        assertThat(chains.registered).containsOnlyKeys("b_3_then", "b_3_else");
    }

    @Test
    void conditionalMitLeeremElseKommandoGiltAlsOhneElse() {
        BlockDefinition cond = block("conditional", null,
                ordered("condition", "c", "then_command", "a", "else_command", "   "));

        BpmnSegment seg = builder.buildConditional(cond, "b_0");

        assertThat(seg.nodes).extracting(n -> n.id).doesNotContain("b_0_else");
        assertThat(edge(seg, "b_0_split", "b_0_merge").name).isEqualTo("nein");
    }

    @Test
    void switchOhneDefaultBekommtDirektkanteAlsDefaultPfad() {
        BlockDefinition sw = block("switch", "Weiche", ordered("expression", "{{ x }}",
                "cases", List.of(
                        ordered("value", "a", "blocks", List.of(branch("shell", "A1"), branch("shell", "A2"))),
                        ordered("value", "", "blocks", List.of(branch("simple", "B1"))))));

        BpmnSegment seg = builder.buildSwitch(sw, "b_1");

        assertThat(chains.chainPrefixes).containsExactly("b_1_c0", "b_1_c1");
        BpmnModel.Node split = node(seg, "b_1_split");
        assertThat(split.name).isEqualTo("{{ x }}");
        assertThat(split.blockType).isEqualTo("switch");
        assertThat(edge(seg, "b_1_split", "b_1_c0_0").name).isEqualTo("a");
        assertThat(edge(seg, "b_1_split", "b_1_c1_0").name).as("leerer Case-Wert").isEqualTo("case1");
        assertThat(edge(seg, "b_1_c0_1", "b_1_merge").name).isNull();

        BpmnModel.Edge direct = edge(seg, "b_1_split", "b_1_merge");
        assertThat(direct.name).isEqualTo("default");
        assertThat(split.defaultFlowId).isEqualTo(direct.id);

        // breiteste Zeile (2 Tasks) bestimmt die Merge-Position
        double chainWidth = 2 * TASK_W + GAP;
        double mergeX = GW + GAP + chainWidth + GAP;
        assertThat(node(seg, "b_1_merge").x).isEqualTo(mergeX);
        assertThat(seg.width).isEqualTo(mergeX + GW);

        // Zeilen untereinander, um GW + GAP eingerückt
        assertThat(node(seg, "b_1_c0_0").x).isEqualTo(GW + GAP);
        assertThat(node(seg, "b_1_c0_0").y).isEqualTo(ROW_STEP - TASK_H / 2);
        assertThat(node(seg, "b_1_c1_0").y).isEqualTo(2 * ROW_STEP - TASK_H / 2);
    }

    @Test
    void switchZweigKnotenVerlierenIhreBlockConfigDamitDerReimportNichtDoppeltZaehlt() {
        BlockDefinition sw = block("switch", null, ordered("expression", "e",
                "cases", List.of(ordered("value", "a", "blocks", List.of(branch("shell", "A")))),
                "default", List.of(branch("simple", "D"))));

        BpmnSegment seg = builder.buildSwitch(sw, "b_0");

        for (String id : List.of("b_0_c0_0", "b_0_default_0")) {
            BpmnModel.Node n = node(seg, id);
            assertThat(n.configJson).as(id).isNull();
            assertThat(n.blockType).as(id).isNull();
            assertThat(n.blockName).as(id).isNull();
        }
        assertThat(node(seg, "b_0_split").configJson).contains("\"default\"");
    }

    @Test
    void switchMitDefaultZeileMachtDerenEingangskanteZumDefaultPfad() {
        BlockDefinition sw = block("switch", null, ordered("expression", "e",
                "cases", List.of(ordered("value", "a", "blocks", List.of(branch("shell", "A")))),
                "default", List.of(branch("simple", "D"))));

        BpmnSegment seg = builder.buildSwitch(sw, "b_0");

        BpmnModel.Edge in = edge(seg, "b_0_split", "b_0_default_0");
        assertThat(in.name).isEqualTo("default");
        assertThat(node(seg, "b_0_split").defaultFlowId).isEqualTo(in.id);
        assertThat(seg.edges).noneMatch(e -> e.sourceRef.equals("b_0_split") && e.targetRef.equals("b_0_merge"));
        assertThat(node(seg, "b_0_default_0").y).isEqualTo(2 * ROW_STEP - TASK_H / 2);
    }

    @Test
    void leererCaseWirdZurDirektkanteOhneEigeneZeile() {
        BlockDefinition sw = block("switch", null, ordered("expression", "e",
                "cases", List.of(
                        ordered("value", "leer", "blocks", List.of()),
                        ordered("value", "voll", "blocks", List.of(branch("shell", "V"))))));

        BpmnSegment seg = builder.buildSwitch(sw, "b_0");

        assertThat(seg.edges).anyMatch(e -> e.sourceRef.equals("b_0_split")
                && e.targetRef.equals("b_0_merge") && "leer".equals(e.name));
        // der volle Case rückt auf die erste Zeile — der leere verbraucht keine Achse
        assertThat(node(seg, "b_0_c1_0").y).isEqualTo(ROW_STEP - TASK_H / 2);
        // ohne Zweig-Inhalt bleibt die Mindestbreite eines Tasks
        assertThat(node(seg, "b_0_merge").x).isEqualTo(GW + GAP + TASK_W + GAP);
    }

    @Test
    void hoheZeileSchiebtDieNaechsteZeileWeiterNachUnten() {
        chains.taskHeight = 400; // Ausdehnung unter der Achse: 200
        BlockDefinition sw = block("switch", null, ordered("expression", "e",
                "cases", List.of(
                        ordered("value", "a", "blocks", List.of(branch("shell", "A"))),
                        ordered("value", "b", "blocks", List.of(branch("shell", "B"))))));

        BpmnSegment seg = builder.buildSwitch(sw, "b_0");

        double secondAxis = ROW_STEP + 200 + BpmnModelBuilder.SUB_PAD * 2;
        assertThat(node(seg, "b_0_c1_0").y).isEqualTo(secondAxis - 200);
    }
}
