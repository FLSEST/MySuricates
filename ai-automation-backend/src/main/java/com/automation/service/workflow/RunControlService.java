package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Control of runs that already exist: pause, resume, cancel (including the ghost run without a
 * thread), answering an approval, an ad-hoc command in the run's container, and the startup cleanup
 * of runs a previous application lifecycle left RUNNING/PAUSED.
 *
 * <p>Every operation works on the {@link WorkflowExecutionRegistry}: the execution thread of a run
 * polls its {@link ExecutionContext}, so resuming a paused run only lifts the flag and never starts
 * anything. Starting a run — and resuming an INTERRUPTED one from a step — stays in
 * {@link WorkflowExecutor}, the only path through {@link RunAdmission}; neither class needs the
 * other. Status queries such as active run count or container ids go straight to the registry.
 *
 * <p>Extracted from {@code WorkflowExecutor} (Audit-TODO f004669f); bodies and activity-log texts
 * unchanged, including the source {@code "WorkflowExecutor"}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunControlService {

    private final WorkflowExecutionRegistry executionRegistry;
    private final WorkflowRunRepository runRepository;
    private final ExecutionBroadcaster broadcaster;
    private final ActivityLogService activityLogService;
    private final CommandExecutorService commandExecutorService;
    private final ApplicationEventPublisher events;

    /**
     * Cleans up runs stuck in RUNNING/PAUSED from a previous application lifecycle. Records the
     * status each run had ({@link RunInterruption}) and then announces the marked runs, so
     * {@link RunAutoResumer} can pick the opted-in ones — ordered by this call, not by listener order.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupZombieRuns() {
        cleanupZombieRuns(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    }

    /**
     * Only runs started before {@code jvmStart} belong to a previous lifecycle: backlog polls, REST
     * and webhook starts may already run before ApplicationReadyEvent. A run without
     * {@code startedAt} cannot be dated and counts as left over.
     */
    void cleanupZombieRuns(Instant jvmStart) {
        // Collect all runs that were in-flight when the application was last shut down
        List<WorkflowRun> zombies = new java.util.ArrayList<>();
        zombies.addAll(runRepository.findByStatus(RunStatus.RUNNING));
        zombies.addAll(runRepository.findByStatus(RunStatus.PAUSED));
        zombies.removeIf(run -> run.getStartedAt() != null && !run.getStartedAt().isBefore(jvmStart));
        if (zombies.isEmpty()) return;
        log.warn("Found {} run(s) stuck in RUNNING/PAUSED status — marking as INTERRUPTED", zombies.size());
        List<String> marked = new java.util.ArrayList<>();
        for (WorkflowRun zombie : zombies) {
            RunInterruption.record(zombie, zombie.getStatus(), RunInterruption.Cause.RESTART);
            zombie.setStatus(RunStatus.INTERRUPTED);
            zombie.setCompletedAt(Instant.now());
            if (zombie.getStartedAt() != null) {
                zombie.setDurationMs(zombie.getCompletedAt().toEpochMilli() - zombie.getStartedAt().toEpochMilli());
            }
            zombie.setError(Map.of("message", "Workflow was interrupted by an application restart"));
            runRepository.save(zombie);
            marked.add(zombie.getId());
            log.info("Marked interrupted run: {}", zombie.getId());
        }
        activityLogService.warn(LogCategory.EXECUTION,
                "Startup cleanup: marked " + zombies.size() + " run(s) as INTERRUPTED",
                "WorkflowExecutor");
        events.publishEvent(new RunInterruption.Event(marked, RunInterruption.Cause.RESTART));
    }

    public void pauseExecution(String runId) {
        executionRegistry.pauseExecution(runId);
    }

    public void resumeExecution(String runId) {
        executionRegistry.resumeExecution(runId);
    }

    public void cancelExecution(String runId) {
        // Covers the running and the still-queued case; only a run with no context is a ghost.
        if (executionRegistry.cancel(runId)) {
            log.info("Cancelled execution: {}", runId);
        } else {
            // Ghost run: no active thread, but possibly still RUNNING in the DB
            try {
                runRepository.findById(runId).ifPresent(run -> {
                    if (run.getStatus() == RunStatus.RUNNING) {
                        log.warn("Force-cancelling ghost run {} — not in activeExecutions but status=RUNNING", runId);
                        run.setStatus(RunStatus.CANCELLED);
                        run.setCompletedAt(Instant.now());
                        run.setDurationMs(run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
                        run.setError(Map.of("message", "Force-cancelled: no active execution thread found (ghost run)"));
                        runRepository.save(run);
                        broadcaster.broadcastRunStatus(runId, RunStatus.CANCELLED);
                        activityLogService.warn(LogCategory.EXECUTION,
                                "Ghost run force-cancelled: " + runId, "WorkflowExecutor", runId, null);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to force-cancel ghost run {}: {}", runId, e.getMessage());
            }
        }
    }

    public void resolveApproval(String runId, String answer) {
        executionRegistry.resolveApproval(runId, answer);
    }

    public ApprovalRequest getPendingApproval(String runId) {
        return executionRegistry.getPendingApproval(runId);
    }

    public String executeAdHocCommand(String runId, String command) {
        ExecutionContext context = executionRegistry.getStartedContext(runId); // null while queued
        if (context == null) {
            throw new WorkflowExecutionException("No active execution found for run: " + runId);
        }
        log.info("Executing ad-hoc command in run {}: {}", runId, command);
        var result = commandExecutorService.executeCommand(context.getContainerId(), "claude", command);

        // Broadcast as a log message
        broadcaster.broadcastLogMessage(runId, "command", Map.of(
                "input", command,
                "output", result.output() != null ? result.output() : ""
        ));

        return result.output() != null ? result.output() : "";
    }
}
