package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;

import java.util.Map;

/**
 * The inner command of a wrapper block — foreach, while, conditional, retry and a switch case — and how it
 * runs: as a shell step or as an AI step of its own, with the settings of the block it belongs to. The one
 * copy of this call; the five wrappers each used to carry their own, and every change to it (provider and
 * model per block, execution_mode, prompt taint) had to reach all five.
 *
 * <p>A shell step carries the block's {@code container_image}, {@code mount_docker_socket} and {@code mounts}
 * (before ee37f4c8 a conditional branch silently ran on the host); an AI step gets the run's instruction
 * prefix and the block's provider, model and effort.
 *
 * @param block           the block whose config and AI settings apply: the wrapper itself, or a switch case
 * @param shell           a shell step instead of an AI step
 * @param escapeVariables {@code shell_escape_variables}: quote every value in a shell command, not only untrusted ones
 * @param shellTimeout    timeout of a shell step in seconds
 * @param aiTimeout       timeout of an AI step in seconds, {@code null} for the provider's default
 * @param name            the name the step carries ({@code null}: unnamed) — a switch case is recorded under it
 */
record InnerStep(BlockDefinition block, boolean shell, boolean escapeVariables, int shellTimeout, Integer aiTimeout,
                 String name) {

    /** The unnamed step of a wrapper's own command. */
    InnerStep(BlockDefinition block, boolean shell, boolean escapeVariables, int shellTimeout, Integer aiTimeout) {
        this(block, shell, escapeVariables, shellTimeout, aiTimeout, null);
    }

    /** {@code command} with its placeholders filled in: quoted for a shell step, as prompt text for an AI step. */
    String resolve(ExecutionContext context, String command) {
        return shell
                ? context.resolveShellTemplate(command, escapeVariables)
                : context.resolvePromptTemplate(command);
    }

    /** Fills in {@code command} and runs it. */
    StepResult run(CommandExecutorService executor, ExecutionContext context, String command) {
        return runResolved(executor, context, resolve(context, command));
    }

    /** Runs a command that {@link #resolve} already filled in — retry resolves once and runs it per attempt. */
    StepResult runResolved(CommandExecutorService executor, ExecutionContext context, String resolvedCommand) {
        if (shell) {
            BlockDefinition shellBlock =
                    CommandExecutorService.buildResolvedShellBlock(block.getConfig(), resolvedCommand, context);
            shellBlock.setName(name);
            return executor.executeStepAsShell(
                    context.getProjectPath(), shellBlock, context.nextStepIndex(), shellTimeout);
        }
        return executor.executeStepOnHost(HostExecutionRequest.builder()
                .projectPath(context.getProjectPath())
                .block(BlockDefinition.builder()
                        .type("simple")
                        .name(name)
                        .config(Map.of("command", resolvedCommand))
                        .build())
                .stepIndex(context.nextStepIndex())
                .systemPrompt(context.getInstructionPrefix())
                .model(context.modelFor(block))
                .timeoutSeconds(aiTimeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .build());
    }
}
