package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.cli.ShellCommandService;
import com.automation.service.cli.ShellCommandService.ShellResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;

@Component
@RequiredArgsConstructor
public class ShellBlockExecutor implements BlockExecutor {

    private final ShellCommandService shellService;
    private final ContainerShellService containerShellService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        Object rawCommand = config.get("command");
        if (rawCommand == null) {
            throw new WorkflowExecutionException("Shell block requires a 'command' in config");
        }

        boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
        String command = escapeVariables
                ? context.resolveTemplateShellSafe(rawCommand.toString())
                : context.resolveTemplate(rawCommand.toString());
        int timeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        String containerImage = config.get("container_image") instanceof String s ? s : null;
        boolean mountDockerSocket = Boolean.TRUE.equals(config.get("mount_docker_socket"));

        ShellResult result;
        String executionMode;

        if (containerImage != null && !containerImage.isBlank()) {
            result = containerShellService.execute(command, context.getProjectPath(), timeout,
                    containerImage, mountDockerSocket);
            executionMode = "container:" + containerImage;
        } else {
            result = shellService.execute(command, context.getProjectPath(), timeout, proc -> {
                context.registerProcess(proc);
            });
            executionMode = "shell";
        }

        context.clearProcess();

        String output = result.exitCode() != 0
                ? result.output() + "\n\n[RECOVERY] " + buildRecoveryHint(result.exitCode(), command)
                : result.output();
        step.setOutput(output);
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(Map.of(
                "exitCode", result.exitCode(),
                "executionMode", executionMode
        ));

        return step;
    }

    @Override
    public boolean supports(String blockType) {
        return "shell".equals(blockType);
    }

    private String buildRecoveryHint(int exitCode, String command) {
        String firstToken = command.trim().split("\\s+")[0];
        return switch (exitCode) {
            case 124 -> "Timeout. Consider: (1) optimizing the command, (2) increasing `timeout_seconds` in block config, (3) splitting the task into smaller steps.";
            case 127 -> "Command not found. Verify the tool is installed and available in PATH. Try: `which " + firstToken + "`.";
            case 126 -> "Permission denied. Check file permissions: `chmod +x <path>`.";
            case 130 -> "Interrupted. Retry the command.";
            case 1   -> "General failure (exit 1). See stderr above. Try running the command manually to reproduce.";
            default  -> "Exit " + exitCode + ": Command failed. See output above for details.";
        };
    }
}
