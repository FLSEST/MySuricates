package com.automation.service.factory;

import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.service.workflow.AgentInstructionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The three prompts of the workflow factory: the architect system prompt (with the agent base
 * instructions in front), the generation prompt for a topic, and the auto-fix prompt for a
 * failed test run.
 *
 * <p>Extracted from {@code WorkflowFactoryService} (Audit-TODO 3b0a021c). Pure text assembly.
 */
@Service
@RequiredArgsConstructor
public class FactoryPromptBuilder {

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

                Available block types:
                - simple: AI-powered tasks (analysis, summarization, generation). Config: { "command": "..." }
                - shell: Execute shell commands. Config: { "command": "...", "container_image": "image:tag" }. See "Ausfuehrungsumgebung" section for available host tools and container images.
                - retry: Wraps a command with retry logic. Config: { "command": "...", "max_retries": 3, "delay_seconds": 5 }
                - conditional: Branch based on condition. Config: { "condition": "...", "if_true": "...", "if_false": "..." }
                - forEach: Loop over items. Config: { "items_expression": "...", "item_variable": "item", "command": "..." }
                - agentic: Complex multi-step AI task with goal. Config: { "goal": "...", "max_iterations": 5 }

                Rules:
                - Use {{ variable_name }} syntax for variables
                - Use {{ previous_output }} to pass data between steps
                - Shell blocks for concrete commands (npm, git, curl, find, etc.)
                - Simple blocks for AI tasks (analysis, summarization, generation)
                - Retry blocks when failures are likely (API calls, network, tests)
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
                    { "type": "simple|shell|retry|conditional|forEach|agentic", "config": { "command": "..." } }
                  ],
                  "defaultContext": { "variable": "default_value" },
                  "instructions": "Workflow-specific instructions for the AI agent",
                  "testInputs": { "variable": "test_value" }
                }
                """);
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
