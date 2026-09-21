package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.WorkflowFixtures.block;
import static com.automation.service.export.bpmn.WorkflowFixtures.ordered;
import static org.assertj.core.api.Assertions.assertThat;

class BpmnBranchBlocksTest {

    private static Map<String, Object> inner(String type, String name, Map<String, Object> config) {
        return ordered("type", type, "name", name, "config", config);
    }

    @Test
    void idSchreibweise() {
        assertThat(BpmnBranchBlocks.member("b", 3)).isEqualTo("b_3");
        assertThat(BpmnBranchBlocks.casePrefix("b_1", 0)).isEqualTo("b_1_c0");
        assertThat(BpmnBranchBlocks.defaultPrefix("b_1")).isEqualTo("b_1_default");
        assertThat(BpmnBranchBlocks.member(BpmnBranchBlocks.casePrefix("b_1", 2), 1)).isEqualTo("b_1_c2_1");
    }

    @Test
    void nurSwitchHatZweigBloecke() {
        assertThat(BpmnBranchBlocks.of(null)).isEmpty();
        assertThat(BpmnBranchBlocks.of(block("conditional", null,
                ordered("then_command", "x", "cases", List.of())))).isEmpty();
        BlockDefinition noConfig = new BlockDefinition();
        noConfig.setType("switch");
        noConfig.setConfig(null);
        assertThat(BpmnBranchBlocks.of(noConfig)).isEmpty();
    }

    @Test
    void sammeltCasesUndDefaultMitPfadInReihenfolge() {
        BlockDefinition sw = block("switch", "s", ordered(
                "cases", List.of(
                        ordered("value", "a", "blocks", List.of(
                                inner("shell", "A0", ordered("command", "a")),
                                inner("simple", "A1", ordered("command", "b")))),
                        ordered("value", "b", "blocks", List.of(inner("shell", "B0", ordered())))),
                "default", List.of(inner("shell", "D0", ordered()))));

        List<BpmnBranchBlocks.Branch> branches = BpmnBranchBlocks.of(sw);

        assertThat(branches).extracting(BpmnBranchBlocks.Branch::path)
                .containsExactly("_c0_0", "_c0_1", "_c1_0", "_default_0");
        assertThat(branches).extracting(b -> b.block().getName())
                .containsExactly("A0", "A1", "B0", "D0");
        assertThat(branches.get(1).block().getType()).isEqualTo("simple");
        assertThat(branches.get(1).block().getConfig()).containsEntry("command", "b");
    }

    @Test
    void verschachtelterSwitchErweitertDenPfadRekursiv() {
        Map<String, Object> nested = inner("switch", "innen", ordered(
                "cases", List.of(ordered("value", "x", "blocks", List.of(inner("shell", "tief", ordered()))))));
        BlockDefinition sw = block("switch", "aussen", ordered(
                "cases", List.of(ordered("value", "a", "blocks", List.of(nested)))));

        assertThat(BpmnBranchBlocks.of(sw)).extracting(BpmnBranchBlocks.Branch::path)
                .containsExactly("_c0_0", "_c0_0_c0_0");
    }

    @Test
    void ungueltigeEintraegeWerdenUebergangen() {
        BlockDefinition sw = block("switch", null, ordered(
                "cases", List.of("kein Case", ordered("value", "a", "blocks", "keine Liste")),
                "default", "auch keine Liste"));

        assertThat(BpmnBranchBlocks.of(sw)).isEmpty();
    }

    @Test
    void ergebnisIstUnveraenderlich() {
        BlockDefinition sw = block("switch", null, ordered(
                "default", List.of(inner("shell", "D", ordered()))));
        List<BpmnBranchBlocks.Branch> branches = BpmnBranchBlocks.of(sw);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> branches.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
