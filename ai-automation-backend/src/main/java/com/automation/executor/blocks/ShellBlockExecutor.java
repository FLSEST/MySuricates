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
import java.util.ArrayList;
import java.util.List;
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

        // Untrusted variables (webhook payloads) are always escaped; shell_escape_variables escapes everything.
        boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
        String command = context.resolveShellTemplate(rawCommand.toString(), escapeVariables);
        if (Boolean.TRUE.equals(config.get("checked_command"))) {
            command = "set -e -o pipefail\n" + command;
        }
        int timeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        String containerImage = config.get("container_image") instanceof String s ? s : null;
        boolean mountDockerSocket = Boolean.TRUE.equals(config.get("mount_docker_socket"));
        List<String> extraMounts = resolveMounts(config.get("mounts"), context);

        ShellResult result;
        String executionMode;

        if (containerImage != null && !containerImage.isBlank()) {
            if (context.getRunMetadata().containsKey("workspace")) {
                command = command.replace(context.getProjectPath(), "/workspace");
                step.setInput(command);
                result = containerShellService.execute(command, context.getProjectPath(), timeout,
                        containerImage, mountDockerSocket, extraMounts, context::shouldStop);
            } else {
                result = containerShellService.execute(command, context.getProjectPath(), timeout,
                        containerImage, mountDockerSocket, extraMounts);
            }
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
        if (Boolean.TRUE.equals(config.get("checked_command"))) {
            String category = String.valueOf(config.getOrDefault("check_category", "build-check"))
                    .replaceAll("[^a-zA-Z0-9_-]", "-");
            output = "gate_status: " + (result.exitCode() == 0 ? "GREEN" : "RED")
                    + "\ngate_category: " + category
                    + "\ngate_description: Command exit " + result.exitCode() + "\n\n" + output;
        }
        step.setOutput(output);
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(Map.of(
                "exitCode", result.exitCode(),
                "checkedCommand", Boolean.TRUE.equals(config.get("checked_command")),
                "executionMode", executionMode
        ));

        WorkspaceCheckEvidence.record(context, step, result);
        return step;
    }

    @Override
    public boolean supports(String blockType) {
        return "shell".equals(blockType);
    }

    /**
     * Reads the optional `mounts` config (List of strings in docker-volume syntax
     * `host:container[:ro|rw]`) and resolves template variables like {{ home_dir }}
     * against the current execution context.
     */
    static List<String> resolveMounts(Object raw, ExecutionContext context) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry == null) continue;
            String s = entry.toString();
            if (s.isBlank()) continue;
            resolved.add(context.resolveTemplate(s));
        }
        return resolved;
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
