package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final List<BlockExecutor> blockExecutors;
    private final ContainerService containerService;
    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository runRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;

    private final Map<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    /**
     * Starts workflow execution asynchronously.
     * Creates a WorkflowRun with RUNNING status and returns it immediately.
     * The actual block execution happens in a background virtual thread.
     */
    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext) {
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        run = runRepository.save(run);

        // Eagerly initialize lazy collections before leaving the transaction/session
        Project project = workflow.getProject();
        List<BlockDefinition> blocks = List.copyOf(workflow.getBlocks());
        List<Skill> skills = List.copyOf(workflow.getActivatedSkills());

        Map<String, Object> contextVars = inputContext != null ? Map.copyOf(inputContext) : Map.of();

        // Execute asynchronously — return the run immediately
        final String runId = run.getId();
        virtualThreadExecutor.submit(() -> executeBlocks(runId, project, blocks, skills, contextVars));

        return run;
    }

    private void executeBlocks(String runId, Project project, List<BlockDefinition> blocks,
                               List<Skill> skills, Map<String, Object> inputContext) {
        WorkflowRun run = runRepository.findById(runId).orElseThrow();
        // Replace lazy proxy with fresh list — run was just created with 0 steps
        run.setSteps(new ArrayList<>());

        try {
            String containerId = containerService.getDockerContainerId(project);

            ExecutionContext context = ExecutionContext.builder()
                    .containerId(containerId)
                    .workflowRunId(runId)
                    .build();
            inputContext.forEach(context::setVariable);
            activeExecutions.put(runId, context);

            commandExecutorService.injectSkillContext(containerId, "claude", skills);

            for (BlockDefinition block : blocks) {
                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
                }

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

                broadcastStepResult(run.getId(), step);
                runRepository.save(run);

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
            log.error("Workflow execution failed: {}", runId, e);
            run.setStatus(RunStatus.FAILED);
            run.setError(Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        } finally {
            run.setCompletedAt(Instant.now());
            run.setDurationMs(
                    run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli()
            );
            runRepository.save(run);
            activeExecutions.remove(run.getId());

            // Broadcast final status
            broadcastStepResult(run.getId(), run.getStatus());
        }
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

    public Set<String> getActiveContainerIds() {
        return activeExecutions.values().stream()
                .map(ExecutionContext::getContainerId)
                .collect(Collectors.toSet());
    }

    public String getContainerIdForRun(String runId) {
        ExecutionContext ctx = activeExecutions.get(runId);
        return ctx != null ? ctx.getContainerId() : null;
    }

    public void cancelExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.cancel();
            log.info("Cancelled execution: {}", runId);
        }
    }

    public void resolveApproval(String runId, String answer) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resolveApproval(answer);
            log.info("Resolved approval for run {}: {}", runId, answer);
        }
    }

    public ApprovalRequest getPendingApproval(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            return context.getCurrentApprovalRequest().get();
        }
        return null;
    }

    public String executeAdHocCommand(String runId, String command) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context == null) {
            throw new WorkflowExecutionException("No active execution found for run: " + runId);
        }
        log.info("Executing ad-hoc command in run {}: {}", runId, command);
        var result = commandExecutorService.executeCommand(context.getContainerId(), "claude", command);

        // Broadcast as a log message
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    Map.of(
                            "type", "command",
                            "input", command,
                            "output", result.output() != null ? result.output() : ""
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast command result: {}", e.getMessage());
        }

        return result.output() != null ? result.output() : "";
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

    private void broadcastStepResult(String runId, RunStatus status) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    Map.of("type", "status", "runId", runId, "status", status)
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast final status: {}", e.getMessage());
        }
    }
}
