package com.automation.service.cli;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ActionResult;
import com.automation.service.cli.ShellCommandService.ShellResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandExecutorService {

    private final CliSessionManager sessionManager;
    private final HostClaudeService hostClaudeService;
    private final ShellCommandService shellCommandService;

    public CliSession.CommandResult executeCommand(String containerId, String cliType, String command) {
        try {
            CliSession session = sessionManager.getOrCreateSession(containerId, cliType);
            return session.sendCommand(command);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Failed to execute command in container: " + containerId, e);
        }
    }

    public CliSession.EnrichedCommandResult executeCommandEnriched(String containerId, String cliType, String command) {
        try {
            CliSession session = sessionManager.getOrCreateSession(containerId, cliType);
            return session.sendCommandEnriched(command);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Failed to execute command in container: " + containerId, e);
        }
    }

    public CliSession.EnrichedCommandResult executeCommandEnriched(String containerId, String cliType, String command, String model) {
        return executeCommandEnriched(containerId, cliType, command, model, null);
    }

    public CliSession.EnrichedCommandResult executeCommandEnriched(String containerId, String cliType,
                                                                     String command, String model, String systemPrompt) {
        try {
            CliSession session = sessionManager.getOrCreateSession(containerId, cliType);
            return session.sendCommandEnriched(command, model, systemPrompt);
        } catch (IOException e) {
            throw new WorkflowExecutionException("Failed to execute command in container: " + containerId, e);
        }
    }

    public void injectSkillContext(String containerId, String cliType, List<Skill> activatedSkills) {
        if (activatedSkills == null || activatedSkills.isEmpty()) {
            return;
        }

        String aggregatedContext = buildSkillContext(activatedSkills);
        if (!aggregatedContext.isEmpty()) {
            executeCommand(containerId, cliType, aggregatedContext);
        }
    }

    public String buildSkillContext(List<Skill> skills) {
        return skills.stream()
                .filter(s -> s.getAiContext() != null && !s.getAiContext().isBlank())
                .map(s -> "=== Skill: " + s.getName() + " ===\n" + s.getAiContext())
                .collect(Collectors.joining("\n\n"));
    }

    public StepResult executeStep(String containerId, String cliType, BlockDefinition block, int stepIndex,
                                   String instructionPrefix, String model) {
        String command = extractCommand(block);
        return executeStepInternal(containerId, cliType, block, stepIndex, model, instructionPrefix);
    }

    public StepResult executeStep(String containerId, String cliType, BlockDefinition block, int stepIndex) {
        return executeStepInternal(containerId, cliType, block, stepIndex, null, null);
    }

    private StepResult executeStepInternal(String containerId, String cliType, BlockDefinition block,
                                            int stepIndex, String model, String systemPrompt) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(block);
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        try {
            CliSession.EnrichedCommandResult result =
                    executeCommandEnriched(containerId, cliType, command, model, systemPrompt);
            step.setOutput(result.output());
            step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);

            if (result.parsed() != null) {
                Map<String, Object> meta = new LinkedHashMap<>(result.parsed().toMetadata());
                step.setMetadata(meta);
            }
        } catch (Exception e) {
            log.error("Step execution failed for command in container {}: {}", containerId, e.getMessage(), e);
            step.setOutput("Error: " + e.getMessage());
            step.setStatus(StepStatus.FAILED);
            step.setMetadata(Map.of("error", e.getMessage()));
        }

        step.setCompletedAt(Instant.now());
        step.setDurationMs(
                step.getCompletedAt().toEpochMilli() - step.getStartedAt().toEpochMilli()
        );

        return step;
    }

    // === Host-based execution methods (run Claude CLI on host, not in Docker) ===

    public CliSession.EnrichedCommandResult executeCommandOnHost(
            String projectPath, String command, String model, String systemPrompt) {
        return executeCommandOnHost(projectPath, command, model, systemPrompt, null);
    }

    public CliSession.EnrichedCommandResult executeCommandOnHost(
            String projectPath, String command, String model, String systemPrompt,
            Integer timeoutSeconds) {
        try {
            ActionResult result = hostClaudeService.executeAction(
                    command, model, systemPrompt, projectPath, timeoutSeconds);
            return new CliSession.EnrichedCommandResult(result.text(), result.exitCode(), result.parsed());
        } catch (IOException e) {
            throw new WorkflowExecutionException("Failed to execute command on host (project: " + projectPath + ")", e);
        }
    }

    public CliSession.CommandResult executeCommandOnHost(String projectPath, String command) {
        return executeCommandOnHost(projectPath, command, null, null).toCommandResult();
    }

    public StepResult executeStepOnHost(String projectPath, BlockDefinition block,
                                         int stepIndex, String instructionPrefix, String model) {
        return executeStepOnHost(projectPath, block, stepIndex, instructionPrefix, model, null);
    }

    public StepResult executeStepOnHost(String projectPath, BlockDefinition block,
                                         int stepIndex, String instructionPrefix, String model,
                                         Integer timeoutSeconds) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(block);
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        try {
            CliSession.EnrichedCommandResult result =
                    executeCommandOnHost(projectPath, command, model, instructionPrefix, timeoutSeconds);
            step.setOutput(result.output());
            step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);

            if (result.parsed() != null) {
                Map<String, Object> meta = new LinkedHashMap<>(result.parsed().toMetadata());
                step.setMetadata(meta);
            }
        } catch (Exception e) {
            log.error("Host step execution failed for project {}: {}", projectPath, e.getMessage(), e);
            step.setOutput("Error: " + e.getMessage());
            step.setStatus(StepStatus.FAILED);
            step.setMetadata(Map.of("error", e.getMessage()));
        }

        step.setCompletedAt(Instant.now());
        step.setDurationMs(
                step.getCompletedAt().toEpochMilli() - step.getStartedAt().toEpochMilli()
        );

        return step;
    }

    public void injectSkillContextOnHost(String projectPath, List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }

        String aggregatedContext = buildSkillContext(skills);
        if (!aggregatedContext.isEmpty()) {
            executeCommandOnHost(projectPath, aggregatedContext);
        }
    }

    // === Shell-based execution (no Claude CLI) ===

    public StepResult executeStepAsShell(String projectPath, BlockDefinition block,
                                          int stepIndex, int timeoutSeconds) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(block);
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        ShellResult result = shellCommandService.execute(command, projectPath, timeoutSeconds);
        step.setOutput(result.output());
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setMetadata(Map.of("exitCode", result.exitCode(), "executionMode", "shell"));

        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());

        return step;
    }

    public CliSession.CommandResult executeShellCommandOnHost(String projectPath, String command, int timeoutSeconds) {
        ShellResult result = shellCommandService.execute(command, projectPath, timeoutSeconds);
        return new CliSession.CommandResult(result.output(), result.exitCode());
    }

    public String prependInstructions(String command, String prefix) {
        return "INSTRUCTIONS:\n" + prefix + "\n---\nTASK:\n" + command;
    }

    private String extractCommand(BlockDefinition block) {
        Object command = block.getConfig().get("command");
        if (command == null) {
            throw new WorkflowExecutionException("Block has no 'command' in config");
        }
        return command.toString();
    }

}
