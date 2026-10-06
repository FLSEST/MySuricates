package com.automation.service.factory;

import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.executor.spec.BlockSpecs;
import com.automation.service.workflow.AgentInstructionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The three prompts of the workflow factory: the architect system prompt (with the agent base
 * instructions in front), the generation prompt for a topic, and the auto-fix prompt for a
 * failed test run.
 *
 * <p>Extracted from {@code WorkflowFactoryService} (Audit-TODO 3b0a021c). Pure text assembly. The
 * block schema comes from {@link BlockSpecs}: the old list named the type forEach and keys no executor
 * reads (TODO cf8db6f8); {@code PromptBlockSchemaGateTest} holds the prompts to the executors.
 */
@Service
@RequiredArgsConstructor
public class FactoryPromptBuilder {

    /** The block types a factory workflow may use. */
    static final List<String> BLOCK_TYPES = List.of("simple", "shell", "retry", "conditional", "foreach", "agentic");

    private final AgentInstructionsService agentInstructionsService;

    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // Inject base agent instructions (includes execution environment info)
        String agentBase = agentInstructionsService.buildInstructionPrefix(null);
        if (!agentBase.isBlank()) {
            sb.append(agentBase).append("\n\n---\n\n");
        }

        sb.append("""
                You are a workflow architect for MySuricates, an AI-powered workflow automation platform.
                You create workflow definitions as JSON.

                Available block types: use only these types and config keys, spelled exactly as below — a block of \
                another type is refused, and the executors ignore any other key.

                """).append(BlockSpecs.reference(BLOCK_TYPES)).append("""
                Rules:
                - Use {{ variable_name }} syntax for variables
                - Use {{ previous_output }} to pass data between steps
                - Shell blocks for concrete commands (npm, git, curl, find, etc.)
                - Simple blocks for AI tasks (analysis, summarization, generation)
                - Retry blocks when failures are likely (API calls, network, tests)
                - conditional, foreach and retry run their commands as an AI prompt unless `execution_mode` is "shell"
                - Agentic blocks only for complex, multi-step autonomous tasks
                - Keep workflows practical and focused (3-7 blocks typical)
                - Follow the "Ausfuehrungsumgebung" rules for shell block container_image

                Output ONLY valid JSON (no markdown fences).
                """);
        return sb.toString();
    }

    public String buildGeneratorPrompt(WorkflowFactoryRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Create a workflow for the following topic.\n\n");
        sb.append("TOPIC: ").append(request.topic()).append("\n");
        if (request.category() != null) {
            sb.append("CATEGORY: ").append(request.category()).append("\n");
        }
        if (request.description() != null) {
            sb.append("DESCRIPTION: ").append(request.description()).append("\n");
        }
        if (request.testInputs() != null && !request.testInputs().isEmpty()) {
            sb.append("TEST INPUTS: ").append(request.testInputs()).append("\n");
        }
        sb.append("""

                Output JSON with this structure:
                {
                  "name": "Short workflow name",
                  "description": "What the workflow does",
                  "icon": "fitting emoji",
                  "blocks": [
                    { "type": "shell", "config": { "command": "set -euo pipefail; npm test" } }
                  ],
                  "defaultContext": { "variable": "default_value" },
                  "instructions": "Workflow-specific instructions for the AI agent",
                  "testInputs": { "variable": "test_value" }
                }
                Each block's type is one of %s; its config holds the keys of that type from the block types above.
                """.formatted(String.join(", ", BLOCK_TYPES)));
        return sb.toString();
    }

    public String buildAutoFixPrompt(String workflowName, String workflowDescription, String blocksJson, String errorMessage) {
        return """
                A workflow failed during automated testing. Analyze the error and fix the workflow blocks.

                WORKFLOW NAME: %s
                WORKFLOW DESCRIPTION: %s

                CURRENT BLOCKS (JSON):
                %s

                ERROR:
                %s

                Fix the blocks to resolve this error. Output ONLY the corrected blocks array as JSON.
                Do not wrap in markdown code fences.
                """.formatted(workflowName, workflowDescription, blocksJson, errorMessage);
    }
}
