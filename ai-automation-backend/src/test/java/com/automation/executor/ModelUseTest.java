package com.automation.executor;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUseTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(new HashMap<>(config)).build();
    }

    private static Map<String, Object> switchWith(Map<String, Object> caseBlock) {
        return Map.of("expression", "{{ previous_output }}",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(caseBlock))));
    }

    @Test
    void nothingOrOnlyDeterministicBlocks_needNoAi() {
        assertThat(ModelUse.needsAi(null)).isFalse();
        assertThat(ModelUse.needsAi(List.of())).isFalse();
        assertThat(ModelUse.needsAi(List.of(
                block("shell", Map.of("command", "date")),
                block("approval", Map.of("question", "Go?")),
                block("human-input", Map.of("prompt", "?")),
                block("integration", Map.of("provider", "github", "action", "list_issues")),
                block("todo-create", Map.of("title", "x")),
                block("document", Map.of("action", "read")),
                block("compress", Map.of("source", "previous_output")),
                block("dashboard-report", Map.of("status", "GREEN"))))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "agentic", "skill-capability", "compress-ai"})
    void modelBlockTypes_alwaysNeedAi(String type) {
        assertThat(ModelUse.needsAi(List.of(block("shell", Map.of()), block(type, Map.of())))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreach", "while", "conditional", "retry"})
    void commandWrappers_askTheAiUnlessTheyRunAsShell(String type) {
        assertThat(ModelUse.needsAi(List.of(block(type, Map.of("command", "x"))))).isTrue();
        assertThat(ModelUse.needsAi(List.of(block(type, Map.of("execution_mode", "ai"))))).isTrue();
        assertThat(ModelUse.needsAi(List.of(block(type, Map.of("execution_mode", "claude"))))).isTrue();
        assertThat(ModelUse.needsAi(List.of(block(type, Map.of("execution_mode", "shell"))))).isFalse();
    }

    @Test
    void anUnknownExecutionMode_countsAsAi() {
        assertThat(ModelUse.needsAi(List.of(block("foreach", Map.of("execution_mode", "shel"))))).isTrue();
    }

    @Test
    void switch_needsAiOnlyThroughASimpleCaseBlockThatIsNotShell() {
        assertThat(ModelUse.needsAi(List.of(block("switch",
                switchWith(Map.of("type", "shell", "config", Map.of("command", "echo a"))))))).isFalse();
        assertThat(ModelUse.needsAi(List.of(block("switch",
                switchWith(Map.of("type", "simple", "config", Map.of("command", "Summarize"))))))).isTrue();
        assertThat(ModelUse.needsAi(List.of(block("switch",
                switchWith(Map.of("type", "simple", "config", Map.of("command", "date", "execution_mode", "shell")))))))
                .isFalse();
        assertThat(ModelUse.needsAi(List.of(block("switch", Map.of("expression", "x",
                "default", List.of(Map.of("type", "simple", "config", Map.of("command", "y")))))))).isTrue();
    }

    @Test
    void aSubWorkflow_countsAsAi_becauseTheWorkflowItStartsIsNotPartOfTheDefinition() {
        assertThat(ModelUse.needsAi(List.of(block("sub-workflow", Map.of("workflow_id", "w-1"))))).isTrue();
    }

    /**
     * TODO 093d2f11: before installation a blueprint package names its part, whose cost BlueprintGateTest
     * pins — but only a blueprint may claim that; any other definition with the prefix still counts as AI.
     */
    @Test
    void aSubWorkflowNamingABlueprint_isModelFreeOnlyInsideABlueprint() {
        List<BlockDefinition> pkg = List.of(block("sub-workflow", Map.of("workflow_id", "blueprint-sub-workflow-part")));

        assertThat(ModelUse.needsAiAsBlueprint(pkg)).isFalse();
        assertThat(ModelUse.needsAi(pkg)).as("ein Workflow oder eine Vorlage mit dem Präfix").isTrue();
        assertThat(ModelUse.needsAiAsBlueprint(List.of(block("sub-workflow", Map.of("workflow_id", "w-1"))))).isTrue();
        assertThat(ModelUse.needsAiAsBlueprint(List.of(block("simple", Map.of("command", "x"))))).isTrue();
    }

    @Test
    void blocksWithoutTypeOrConfig_areNoModelCalls() {
        List<BlockDefinition> blocks = new java.util.ArrayList<>();
        blocks.add(null);
        blocks.add(BlockDefinition.builder().build());
        assertThat(ModelUse.needsAi(blocks)).isFalse();
    }
}
