package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.ContainerRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.project.ContainerService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final List<BlockExecutor> blockExecutors;
    private final ContainerService containerService;
    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository runRepository;
    private final ContainerRepository containerRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final Semaphore executionSemaphore;
    private final AgentInstructionsService agentInstructionsService;
    private final ActivityLogService activityLogService;
    private final WorkflowMemoryService workflowMemoryService;
    private final ProjectContextService projectContextService;
    private final ApplicationEventPublisher eventPublisher;
    private final TemplateVerificationService templateVerificationService;
    private final SystemIdentityService systemIdentity;

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
        String instructions = workflow.getInstructions();
        String model = workflow.getModel();
        String provider = workflow.getProvider();
        Map<String, Object> workflowMeta = Map.copyOf(workflow.getMetadata());

        String templateId = workflow.getTemplateId();

        Map<String, Object> mergedContext = new HashMap<>(workflow.getDefaultContext());
        if (inputContext != null) {
            mergedContext.putAll(inputContext);
        }
        Map<String, Object> contextVars = Map.copyOf(mergedContext);

        // Persist input context so it's visible in execution details
        if (!contextVars.isEmpty()) {
            run.setInputContext(new HashMap<>(contextVars));
            run = runRepository.save(run);
        }

        // Execute asynchronously — return the run immediately
        final String runId = run.getId();
        final String workflowId = workflow.getId();
        final String workflowName = workflow.getName();
        activityLogService.info(LogCategory.EXECUTION,
                "Workflow execution started: " + workflowName,
                "WorkflowExecutor", runId, workflowId);
        virtualThreadExecutor.submit(() -> executeBlocks(runId, workflowId, workflowName, project, blocks, skills, contextVars, instructions, model, provider, workflowMeta, templateId));

        return run;
    }

    private void executeBlocks(String runId, String workflowId, String workflowName, Project project, List<BlockDefinition> blocks,
                               List<Skill> skills, Map<String, Object> inputContext,
                               String instructions, String model, String provider, Map<String, Object> workflowMeta,
                               String templateId) {
        try {
            executionSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            run.setStatus(RunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setError(Map.of("message", "Interrupted while waiting for execution slot"));
            runRepository.save(run);
            return;
        }

        WorkflowRun run = runRepository.findById(runId).orElseThrow();
        // Replace lazy proxy with fresh list — run was just created with 0 steps
        run.setSteps(new ArrayList<>());

        ExecutionContext context = null;
        try {
            String containerId = ensureContainerRunning(project, runId);

            context = ExecutionContext.builder()
                    .containerId(containerId)
                    .workflowRunId(runId)
                    .build();
            context.setProjectPath(project.getPath());
            inputContext.forEach(context::setVariable);

            // Stamp run metadata with system identity
            context.setRunMetadata("system", systemIdentity.getName());
            context.setRunMetadata("systemVersion", systemIdentity.getVersion());

            // Set instruction prefix and model from workflow
            String instructionPrefix = agentInstructionsService.buildInstructionPrefix(instructions);
            if (!instructionPrefix.isBlank()) {
                context.setInstructionPrefix(instructionPrefix);
            }
            if (model != null && !model.isBlank()) {
                context.setModel(model);
            }
            if (provider != null && !provider.isBlank()) {
                context.setProvider(provider);
            }

            activeExecutions.put(runId, context);

            commandExecutorService.injectSkillContextOnHost(project.getPath(), skills);

            // Write active workflow memory to project for executing agent
            workflowMemoryService.writeActiveMemoryToProject(workflowId, project.getPath());

            // Write project AI context to .claude/rules/ for executing agent
            projectContextService.writeProjectContextToProject(project.getAiContext(), project.getPath());

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

                // Pre-create RUNNING placeholder so UI shows the step immediately
                int stepIndex = context.preAllocateStepIndex();
                StepResult step = new StepResult();
                step.setStepIndex(stepIndex);
                step.setStartedAt(Instant.now());
                step.setStatus(StepStatus.RUNNING);
                step.setMetadata(Map.of("blockType", block.getType()));
                run.addStep(step);
                runRepository.save(run);
                broadcastStepResult(run.getId(), step);

                // Execute the block — nextStepIndex() returns the pre-allocated index
                StepResult result = executor.execute(block, context);

                // Update the persisted step with the actual results
                step.setInput(result.getInput());
                step.setOutput(result.getOutput());
                step.setStatus(result.getStatus());
                step.setCompletedAt(result.getCompletedAt());
                step.setDurationMs(result.getDurationMs());
                if (result.getMetadata() != null) {
                    step.setMetadata(result.getMetadata());
                }
                context.setPreviousOutput(step.getOutput());
                context.setVariable("step_" + step.getStepIndex() + "_output",
                        step.getOutput() != null ? step.getOutput() : "");

                // Update container last activity (skip if no container)
                if (containerId != null) {
                    updateContainerActivity(containerId);
                }

                broadcastStepResult(run.getId(), step);
                runRepository.save(run);

                if (step.getStatus() == StepStatus.FAILED && block.shouldStopOnFailure()) {
                    run.setStatus(RunStatus.FAILED);
                    String output = step.getOutput() != null ? step.getOutput() : "";
                    String message = "Step " + step.getStepIndex() + " (" + block.getType() + ") failed: "
                            + (output.length() > 500 ? output.substring(0, 500) + "..." : output);
                    run.setError(Map.of("step", step.getStepIndex(), "blockType", block.getType(), "message", message));
                    break;
                }
            }

            if (run.getStatus() == RunStatus.RUNNING) {
                run.setStatus(RunStatus.COMPLETED);
            }

            if (run.getStatus() == RunStatus.COMPLETED) {
                // Record template verification if workflow was created from a template
                if (templateId != null && !templateId.isBlank()) {
                    try {
                        templateVerificationService.recordSuccessfulRun(workflowId, templateId, project.getId(), run.getId());
                    } catch (Exception e) {
                        log.warn("Failed to record template verification: {}", e.getMessage());
                    }
                }
                activityLogService.info(LogCategory.EXECUTION,
                        "Workflow execution completed: " + runId,
                        "WorkflowExecutor", runId, workflowId);
            } else if (run.getStatus() == RunStatus.FAILED) {
                activityLogService.error(LogCategory.EXECUTION,
                        "Workflow execution failed: " + runId,
                        "WorkflowExecutor", runId, workflowId);
            }

        } catch (Exception e) {
            log.error("Workflow execution failed: {}", runId, e);
            run.setStatus(RunStatus.FAILED);
            run.setError(Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    "WorkflowExecutor", runId, workflowId);
        } finally {
            run.setCompletedAt(Instant.now());
            run.setDurationMs(
                    run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli()
            );

            // Merge context runMetadata into WorkflowRun.metadata
            if (context != null && !context.getRunMetadata().isEmpty()) {
                Map<String, Object> merged = new HashMap<>(run.getMetadata());
                merged.putAll(context.getRunMetadata());
                run.setMetadata(merged);
            }

            runRepository.save(run);
            activeExecutions.remove(run.getId());
            executionSemaphore.release();

            // Broadcast final status
            broadcastStepResult(run.getId(), run.getStatus());

            // Publish domain event for listeners (e.g. AutoTodoService)
            try {
                eventPublisher.publishEvent(new WorkflowRunCompletedEvent(
                        this, run.getId(), workflowId, workflowName,
                        project.getId(), project.getPath(), run.getStatus(),
                        run.getMetadata(), workflowMeta, run.getError()));
            } catch (Exception e) {
                log.warn("Failed to publish WorkflowRunCompletedEvent: {}", e.getMessage());
            }
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

    public int getActiveRunCount() {
        return activeExecutions.size();
    }

    public int getPendingApprovalCount() {
        return (int) activeExecutions.values().stream()
                .filter(ctx -> ctx.getCurrentApprovalRequest().get() != null)
                .count();
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

    private String ensureContainerRunning(Project project, String runId) {
        // Try to get existing running container
        try {
            return containerService.getDockerContainerId(project);
        } catch (Exception e) {
            log.warn("No running container for project {} ({}), attempting to start one...",
                    project.getName(), e.getMessage());
        }

        // Try to start a new container
        try {
            broadcastLogMessage(runId, "container_starting",
                    Map.of("message", "Container wird gestartet für Projekt: " + project.getName()));

            Container container = containerService.createAndStartContainer(project);

            broadcastLogMessage(runId, "container_started",
                    Map.of("message", "Container gestartet", "containerId", container.getDockerContainerId()));

            return container.getDockerContainerId();
        } catch (Exception e) {
            // Container creation failed — continue without container (host-based execution still works)
            log.warn("Failed to start container for project {} — continuing with host-based execution: {}",
                    project.getName(), e.getMessage());
            broadcastLogMessage(runId, "container_skipped",
                    Map.of("message", "Container konnte nicht gestartet werden, Ausführung erfolgt auf dem Host"));
            return null;
        }
    }

    private void broadcastLogMessage(String runId, String type, Map<String, Object> data) {
        try {
            Map<String, Object> msg = new HashMap<>(data);
            msg.put("type", type);
            msg.put("runId", runId);
            msg.put("timestamp", Instant.now().toString());
            messagingTemplate.convertAndSend("/topic/executions/" + runId + "/logs", msg);
        } catch (Exception e) {
            log.warn("Failed to broadcast log message: {}", e.getMessage());
        }
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

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedRuns() {
        List<WorkflowRun> orphaned = runRepository.findByStatus(RunStatus.RUNNING);
        for (WorkflowRun run : orphaned) {
            log.warn("Marking orphaned run {} as FAILED (server restart)", run.getId());
            run.setStatus(RunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setError(Map.of("error", "Server restarted during execution"));
            runRepository.save(run);
        }
        if (!orphaned.isEmpty()) {
            log.info("Recovered {} orphaned runs", orphaned.size());
            activityLogService.warn(LogCategory.EXECUTION,
                    "Recovered " + orphaned.size() + " orphaned runs after server restart",
                    "WorkflowExecutor");
        }
    }

    private void updateContainerActivity(String containerId) {
        try {
            containerRepository.findByDockerContainerId(containerId).ifPresent(container -> {
                container.setLastActivityAt(Instant.now());
                containerRepository.save(container);
            });
        } catch (Exception e) {
            log.debug("Failed to update container activity: {}", e.getMessage());
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
