package com.automation.service.cli;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandExecutorService {

    private final CliSessionManager sessionManager;

    public String executeCommand(String containerId, String cliType, String command) {
        try {
            CliSession session = sessionManager.getOrCreateSession(containerId, cliType);
            return session.sendCommand(command);
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
            String response = executeCommand(containerId, cliType, command);
            step.setOutput(response);
            step.setStatus(evaluateResponse(response));
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

    private StepStatus evaluateResponse(String response) {
        if (response == null || response.isBlank()) {
            return StepStatus.COMPLETED;
        }

        String lower = response.toLowerCase();
        if (lower.contains("error") || lower.contains("failed") || lower.contains("exception")) {
            return StepStatus.FAILED;
        }

        return StepStatus.COMPLETED;
    }
}
