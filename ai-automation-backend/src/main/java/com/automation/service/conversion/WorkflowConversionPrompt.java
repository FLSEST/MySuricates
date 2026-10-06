package com.automation.service.conversion;

import com.automation.executor.spec.BlockSpecs;

import java.util.List;

/**
 * The prompt that turns a successful agentic run into workflow blocks for
 * {@link WorkflowConversionService}. The block schema comes from {@link BlockSpecs} — the old text
 * recommended retry without {@code execution_mode}, so a shell command in it ran as an AI prompt
 * (TODO b07704ca) — and {@code PromptBlockSchemaGateTest} checks every type and config key the text
 * names.
 */
public final class WorkflowConversionPrompt {

    /** The block types a suggestion may use. */
    static final List<String> BLOCK_TYPES = List.of("shell", "simple", "retry", "conditional");

    private WorkflowConversionPrompt() {
    }

    /** {@code actions}: goal and numbered actions of the run's agentic steps. */
    public static String build(String actions) {
        return """
                Analyze this successful agentic execution and convert it into optimized workflow blocks.

                %s

                Output ONLY a JSON object with these fields:
                {
                  "suggestedName": "short name for the workflow",
                  "suggestedDescription": "brief description",
                  "blocks": [
                    {"type": "shell", "config": {"command": "set -euo pipefail; npm ci", "on_failure": "stop"}},
                    {"type": "retry", "config": {"command": "npm test", "execution_mode": "shell", "max_retries": 3, "on_failure": "stop"}},
                    {"type": "simple", "config": {"command": "Summarize the failing tests: {{ previous_output }}", "on_failure": "stop"}}
                  ]
                }

                Rules (types and config keys exactly as in the block reference below):
                - Use "shell" blocks for deterministic commands
                - Use "simple" blocks for AI reasoning tasks
                - Use "retry" blocks for commands that might fail transiently. A shell command needs execution_mode "shell": without it, retry hands the command to the AI as a prompt
                - Use "conditional" blocks if the sequence has clear if-then logic, with execution_mode "shell" for shell commands as well
                - Merge related steps when possible
                - Remove redundant or exploratory steps
                - Keep only the essential action chain
                - Follow the "Ausfuehrungsumgebung" rules for shell block container_image
                - Output ONLY valid JSON, no explanations

                Block reference:

                %s""".formatted(actions, BlockSpecs.reference(BLOCK_TYPES));
    }
}
