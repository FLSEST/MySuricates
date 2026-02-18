package com.automation.service.cli;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
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

    public StepResult executeStep(String containerId, String cliType, BlockDefinition block, int stepIndex) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());

        String command = extractCommand(block);
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        try {
            CliSession.EnrichedCommandResult result = executeCommandEnriched(containerId, cliType, command);
            step.setOutput(result.output());
            step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);

            if (result.parsed() != null) {
                Map<String, Object> meta = new LinkedHashMap<>(result.parsed().toMetadata());
                step.setMetadata(meta);
            }
        } catch (Exception e) {
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

    private String extractCommand(BlockDefinition block) {
        Object command = block.getConfig().get("command");
        if (command == null) {
            throw new WorkflowExecutionException("Block has no 'command' in config");
        }
        return command.toString();
    }

}
