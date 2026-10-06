package com.automation.service.conversion;

import com.automation.executor.spec.BlockSpecs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowConversionPromptTest {

    @Test
    void blockTypesSindImSchemaBekanntUndOhneDuplikate() {
        assertThat(BlockSpecs.types()).containsAll(WorkflowConversionPrompt.BLOCK_TYPES);
        assertThat(WorkflowConversionPrompt.BLOCK_TYPES).doesNotHaveDuplicates();
    }

    @Test
    void buildFuegtDieAktionenEinUndEndetMitDerBlockReferenz() {
        String prompt = WorkflowConversionPrompt.build("Goal: Tests fixen\n1. npm test");

        assertThat(prompt).contains("Goal: Tests fixen\n1. npm test")
                .endsWith(BlockSpecs.reference(WorkflowConversionPrompt.BLOCK_TYPES));
    }

    @Test
    void beispielRetryTraegtExecutionModeShell() {
        String prompt = WorkflowConversionPrompt.build("a");

        assertThat(prompt).contains("\"type\": \"retry\"").contains("\"execution_mode\": \"shell\"")
                .contains("\"suggestedName\"", "\"blocks\"");
    }

    @Test
    void aktionenMitProzentzeichenBleibenUnveraendert() {
        assertThat(WorkflowConversionPrompt.build("100% %d %s")).contains("100% %d %s");
    }
}
