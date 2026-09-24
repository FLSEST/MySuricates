package com.automation.service.factory;

import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.service.workflow.AgentInstructionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Locks the factory prompts before extraction (Audit-TODO 3b0a021c). */
@ExtendWith(MockitoExtension.class)
class FactoryPromptBuilderTest {

    @Mock private AgentInstructionsService agentInstructionsService;
    private FactoryPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FactoryPromptBuilder(agentInstructionsService);
    }

    private static WorkflowFactoryRequest request(String description, String category, Map<String, Object> testInputs) {
        return new WorkflowFactoryRequest("Nightly build", description, category, "proj-1", testInputs, false, false, 2, null);
    }

    @Test
    void buildSystemPrompt_withAgentBase_prefixesItWithSeparator() {
        when(agentInstructionsService.buildInstructionPrefix(isNull())).thenReturn("BASE INSTRUCTIONS");

        String prompt = builder.buildSystemPrompt();

        assertThat(prompt).startsWith("BASE INSTRUCTIONS\n\n---\n\nYou are a workflow architect for MySuricates");
        assertThat(prompt).contains("Available block types:").endsWith("Output ONLY valid JSON (no markdown fences).\n");
    }

    /** The schema comes from BlockSpecs — the old list named forEach, if_true, delay_seconds (TODO cf8db6f8). */
    @Test
    void buildSystemPrompt_describesItsBlockTypesWithTheirSpecifications() {
        when(agentInstructionsService.buildInstructionPrefix(isNull())).thenReturn("");

        String prompt = builder.buildSystemPrompt();

        for (String type : FactoryPromptBuilder.BLOCK_TYPES) {
            assertThat(prompt).contains("**Block type: " + type + "**");
        }
        assertThat(prompt).contains("`delay`", "`then_command`", "`items_command`")
                .doesNotContain("forEach", "if_true", "items_expression", "delay_seconds");
    }

    @Test
    void buildSystemPrompt_blankAgentBase_noPrefix() {
        when(agentInstructionsService.buildInstructionPrefix(isNull())).thenReturn("  ");

        assertThat(builder.buildSystemPrompt()).startsWith("You are a workflow architect for MySuricates");
    }

    @Test
    void buildGeneratorPrompt_allOptionalFields_present() {
        String prompt = builder.buildGeneratorPrompt(request("Builds nightly", "DEVOPS", Map.of("branch", "main")));

        assertThat(prompt).startsWith("Create a workflow for the following topic.\n\nTOPIC: Nightly build\nCATEGORY: DEVOPS\nDESCRIPTION: Builds nightly\nTEST INPUTS: {branch=main}\n");
        assertThat(prompt).contains("Output JSON with this structure:").contains("\"defaultContext\": { \"variable\": \"default_value\" }");
        assertThat(prompt).contains("type is one of simple, shell, retry, conditional, foreach, agentic;")
                .doesNotContain("forEach");
    }

    @Test
    void buildGeneratorPrompt_optionalFieldsAbsent_omitsLines() {
        String prompt = builder.buildGeneratorPrompt(request(null, null, Map.of()));

        assertThat(prompt).startsWith("Create a workflow for the following topic.\n\nTOPIC: Nightly build\n\nOutput JSON");
        assertThat(prompt).doesNotContain("CATEGORY:").doesNotContain("DESCRIPTION:").doesNotContain("TEST INPUTS:");
    }

    @Test
    void buildAutoFixPrompt_embedsNameDescriptionBlocksAndError() {
        String prompt = builder.buildAutoFixPrompt("WF", "does things", "[{\"type\":\"shell\"}]", "exit 1");

        assertThat(prompt).startsWith("A workflow failed during automated testing.");
        assertThat(prompt).contains("WORKFLOW NAME: WF\nWORKFLOW DESCRIPTION: does things\n\nCURRENT BLOCKS (JSON):\n[{\"type\":\"shell\"}]\n\nERROR:\nexit 1\n");
        assertThat(prompt).endsWith("Do not wrap in markdown code fences.\n");
    }
}
