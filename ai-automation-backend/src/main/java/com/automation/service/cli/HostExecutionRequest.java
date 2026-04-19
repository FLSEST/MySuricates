package com.automation.service.cli;

import com.automation.entity.BlockDefinition;
import lombok.Builder;

import java.util.List;

/**
 * Consolidated request for host-based CLI execution.
 * Replaces the 7+ overloads of executeCommandOnHost / executeStepOnHost.
 *
 * <p>Usage:
 * <pre>
 * var request = HostExecutionRequest.builder()
 *     .projectPath("/workspace/project")
 *     .command("Review the code")
 *     .model("claude-opus-4-6")
 *     .systemPrompt("You are a code reviewer")
 *     .timeoutSeconds(300)
 *     .build();
 * var result = commandExecutorService.executeOnHost(request);
 * </pre>
 */
@Builder
public record HostExecutionRequest(
        /** Working directory for the CLI process (required). */
        String projectPath,

        /** The prompt/command to execute. Required for command execution, ignored for step execution. */
        String command,

        /** Block definition for step-based execution. If set, command is extracted from block config. */
        BlockDefinition block,

        /** Step index for step result tracking. Only used with block. */
        Integer stepIndex,

        /** Claude model (e.g. claude-opus-4-6). Optional. */
        String model,

        /** System prompt / instruction prefix. Optional. */
        String systemPrompt,

        /** Per-call timeout override in seconds. Falls back to global default. */
        Integer timeoutSeconds,

        /** AI provider ID (e.g. "CLAUDE", "CODEX"). Null defaults to Claude. */
        String providerId,

        /** If true, execute as pure shell (no Claude CLI). Default false. */
        boolean shell,

        /** If true, this is a priority/interactive call (e.g. assistant chat) that uses reserved permits. */
        boolean priority,

        /**
         * Strict whitelist of Claude CLI tools this call is allowed to use. If set, the CLI runs
         * without {@code --dangerously-skip-permissions} and with {@code --allowed-tools} — other
         * tools (Edit, Write, Bash, …) are unavailable to the model. Used by assistant chat to
         * prevent ungated file mutations. Null/empty = fall back to global permissions config.
         */
        List<String> allowedTools,

        /**
         * Blacklist of Claude CLI tools to disallow via {@code --disallowed-tools}. Applied in
         * addition to allowedTools. Null/empty = no extra restriction.
         */
        List<String> disallowedTools
) {
    public HostExecutionRequest {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath is required");
        }
        if (block == null && (command == null || command.isBlank()) && !shell) {
            throw new IllegalArgumentException("Either command or block must be provided");
        }
    }
}
