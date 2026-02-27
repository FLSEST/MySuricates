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

        String command = context.resolveTemplate(rawCommand.toString());
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

        step.setOutput(result.output());
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
}
