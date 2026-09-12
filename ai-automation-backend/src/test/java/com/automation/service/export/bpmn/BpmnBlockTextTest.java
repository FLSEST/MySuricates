package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnBlockTextTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    @Test
    void label_knownTypeGerman_unknownTypeVerbatim_blankTask() {
        assertThat(BpmnBlockText.label("shell")).isEqualTo("Shell");
        assertThat(BpmnBlockText.label("weird")).isEqualTo("weird");
        assertThat(BpmnBlockText.label("")).isEqualTo("Task");
    }

    @Test
    void documentationFor_perType() {
        assertThat(BpmnBlockText.documentationFor(block("agentic", Map.of("goal", "Ziel X")))).isEqualTo("Ziel: Ziel X");
        assertThat(BpmnBlockText.documentationFor(block("integration", Map.of("provider", "github", "action", "create_issue"))))
                .isEqualTo("Integration: github.create_issue");
        assertThat(BpmnBlockText.documentationFor(block("shell", Map.of("command", "echo a\necho b")))).isEqualTo("echo a");
    }

    @Test
    void firstLine_truncatesWithEllipsis() {
        assertThat(BpmnBlockText.firstLine("x".repeat(10), 8)).isEqualTo("xxxxx...");
        assertThat(BpmnBlockText.firstLine("  \n", 8)).isEmpty();
    }

    @Test
    void toBlockDefinitions_ignoresNonMapsAndNullSafeStrings() {
        List<BlockDefinition> defs = BpmnBlockText.toBlockDefinitions(List.of(
                Map.of("type", "shell", "config", Map.of("command", "ls")), "junk", Map.of("type", 5)));
        assertThat(defs).hasSize(2);
        assertThat(defs.get(0).getType()).isEqualTo("shell");
        assertThat(defs.get(0).getConfig()).containsEntry("command", "ls");
        assertThat(defs.get(1).getType()).isNull();
        assertThat(defs.get(1).getConfig()).isEmpty();
    }
}
