package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.project.ContainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final List<BlockExecutor> blockExecutors;
    private final ContainerService containerService;
    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository runRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext) {
        Project project = workflow.getProject();
        String containerId = containerService.getDockerContainerId(project);

        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        run = runRepository.save(run);

        ExecutionContext context = ExecutionContext.builder()
                .containerId(containerId)
                .cliType("claude")
                .workflowRunId(run.getId())
                .build();

        // Set input context variables
        if (inputContext != null) {
            inputContext.forEach(context::setVariable);
        }

        activeExecutions.put(run.getId(), context);

        try {
            // Inject skill context
            commandExecutorService.injectSkillContext(
                    containerId, "claude", workflow.getActivatedSkills()
            );

            // Execute blocks sequentially
            for (BlockDefinition block : workflow.getBlocks()) {
                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
                }

                // Wait while paused
                while (context.isPaused() && !context.shouldStop()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                BlockExecutor executor = findExecutor(block.getType());
                StepResult step = executor.execute(block, context);

                run.addStep(step);
                context.setPreviousOutput(step.getOutput());

                // Broadcast step result via WebSocket
                broadcastStepResult(run.getId(), step);

                // Save intermediate state
                runRepository.save(run);

                // Stop on failure if configured
                if (step.getStatus() == StepStatus.FAILED && block.shouldStopOnFailure()) {
                    run.setStatus(RunStatus.FAILED);
                    run.setError(Map.of("step", step.getStepIndex(), "message",
                            step.getOutput() != null ? step.getOutput() : "Step failed"));
                    break;
                }
            }

            if (run.getStatus() == RunStatus.RUNNING) {
                run.setStatus(RunStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("Workflow execution failed: {}", workflow.getId(), e);
            run.setStatus(RunStatus.FAILED);
            run.setError(Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        } finally {
            run.setCompletedAt(Instant.now());
            run.setDurationMs(
                    run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli()
            );
            runRepository.save(run);
            activeExecutions.remove(run.getId());
        }

        return run;
    }

    public void pauseExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.pause();
            log.info("Paused execution: {}", runId);
        }
    }

    public void resumeExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resume();
            log.info("Resumed execution: {}", runId);
        }
    }

    public void cancelExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.cancel();
            log.info("Cancelled execution: {}", runId);
        }
    }

    private BlockExecutor findExecutor(String blockType) {
        return blockExecutors.stream()
                .filter(e -> e.supports(blockType))
                .findFirst()
                .orElseThrow(() -> new WorkflowExecutionException(
                        "No executor found for block type: " + blockType));
    }

    private void broadcastStepResult(String runId, StepResult step) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    Map.of(
                            "stepIndex", step.getStepIndex(),
                            "status", step.getStatus(),
                            "input", step.getInput() != null ? step.getInput() : "",
                            "output", step.getOutput() != null ? step.getOutput() : "",
                            "durationMs", step.getDurationMs() != null ? step.getDurationMs() : 0
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast step result: {}", e.getMessage());
        }
    }
}
