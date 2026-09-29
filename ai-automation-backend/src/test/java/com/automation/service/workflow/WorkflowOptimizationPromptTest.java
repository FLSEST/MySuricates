package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.assistant.fetch.BlockSpecs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOptimizationPromptTest {

    private static BlockDefinition block(String type) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        return b;
    }

    @Test
    void grundTypenSindAlleImSchemaBekannt() {
        assertThat(BlockSpecs.types()).containsAll(WorkflowOptimizationPrompt.BLOCK_TYPES);
    }

    @Test
    void blockTypesOhneBloeckeSindDieGrundTypen() {
        assertThat(WorkflowOptimizationPrompt.blockTypes(null)).isEqualTo(WorkflowOptimizationPrompt.BLOCK_TYPES);
        assertThat(WorkflowOptimizationPrompt.blockTypes(List.of())).isEqualTo(WorkflowOptimizationPrompt.BLOCK_TYPES);
    }

    @Test
    void blockTypesHaengtWeitereBekannteTypenDesWorkflowsAnUndIgnoriertUnbekannte() {
        var types = WorkflowOptimizationPrompt.blockTypes(
                List.of(block("shell"), block("human-input"), block("gibt-es-nicht"), block(null), block("human-input")));

        assertThat(types).startsWith(WorkflowOptimizationPrompt.BLOCK_TYPES.toArray(new String[0]))
                .endsWith("human-input").doesNotContain("gibt-es-nicht").doesNotHaveDuplicates();
        assertThat(types).hasSize(WorkflowOptimizationPrompt.BLOCK_TYPES.size() + 1);
    }

    @Test
    void buildRendertWorkflowFelderUndBlocksJson() {
        Workflow wf = new Workflow();
        wf.setName("Mein Flow");
        wf.setDescription("Beschreibung X");
        wf.setInstructions("Anweisung Y");
        wf.setBlocks(new ArrayList<>(List.of(block("shell"))));

        String prompt = WorkflowOptimizationPrompt.build(wf, "[{\"type\":\"shell\"}]");

        assertThat(prompt).contains("Name: Mein Flow", "Beschreibung: Beschreibung X",
                "Instructions: Anweisung Y", "Blocks: [{\"type\":\"shell\"}]",
                BlockSpecs.reference(WorkflowOptimizationPrompt.BLOCK_TYPES),
                "{{ previous_output }}", "\"suggestedName\"", "\"changes\"");
    }

    @Test
    void fehlendeFelderWerdenLeerStattNullGerendert() {
        String prompt = WorkflowOptimizationPrompt.build(new Workflow(), "[]");

        assertThat(prompt).doesNotContain("null").contains("Name: \n", "Beschreibung: \n", "Instructions: \n");
    }

    @Test
    void zusaetzlicherTypDesWorkflowsKommtInDieReferenz() {
        Workflow wf = new Workflow();
        wf.setBlocks(new ArrayList<>(List.of(block("human-input"))));

        assertThat(WorkflowOptimizationPrompt.build(wf, "[]"))
                .contains(BlockSpecs.reference(List.of("human-input")));
    }
}
