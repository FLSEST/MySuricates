package com.automation.service.chain;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a workflow chain: one workflow after the other on a virtual thread, passing the previous
 * output along, honouring stop-on-failure and cancellation. Waiting for an entry's run lives in
 * {@link ChainRunWaiter}, the startup zombie cleanup in {@link ChainZombieRunCleaner}.
 */
@Service
@Slf4j
public class ChainExecutor {

    private final ChainRunRepository chainRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final ActivityLogService activityLogService;
    private final ChainRunWaiter runWaiter;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public ChainExecutor(
            ChainRunRepository chainRunRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowService workflowService,
            @Lazy WorkflowExecutor workflowExecutor,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor,
            ActivityLogService activityLogService,
            ChainRunWaiter runWaiter
    ) {
        this.chainRunRepository = chainRunRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.activityLogService = activityLogService;
        this.runWaiter = runWaiter;
    }

    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext) {
        return execute(chain, inputContext, null);
    }

    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext, String triggeredByScheduleId) {
        ChainRun run = ChainRun.builder()
                .chain(chain)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .inputContext(inputContext != null ? new HashMap<>(inputContext) : new HashMap<>())
                .entryRuns(new ArrayList<>())
                .triggeredByScheduleId(triggeredByScheduleId)
                .build();
        run = chainRunRepository.save(run);

        final String runId = run.getId();
        activeCancellations.put(runId, new AtomicBoolean(false));

        activityLogService.info(LogCategory.EXECUTION, "Chain execution started: " + chain.getName(), "ChainExecutor", runId, null);

        virtualThreadExecutor.submit(() -> executeEntries(runId, chain, inputContext));

        return run;
    }

    public void cancel(String runId) {
        AtomicBoolean flag = activeCancellations.get(runId);
        if (flag != null) {
            flag.set(true);
        }
    }

    private void executeEntries(String runId, WorkflowChain chain, Map<String, Object> inputContext) {
        ChainRun run = chainRunRepository.findById(runId).orElseThrow();
        AtomicBoolean cancelled = activeCancellations.get(runId);

        List<ChainEntry> sortedEntries = chain.getEntries().stream()
                .sorted(Comparator.comparingInt(ChainEntry::getSortOrder))
                .toList();

        String previousOutput = null;
        boolean chainFailed = false;
        boolean hasFailures = false;
        boolean hasWarnings = false;

        try {
            for (int i = 0; i < sortedEntries.size(); i++) {
                if (cancelled != null && cancelled.get()) {
                    run.setStatus(RunStatus.CANCELLED);
                    chainFailed = true;
                    break;
                }

                ChainEntry entry = sortedEntries.get(i);
                ChainRunEntry runEntry = ChainRunEntry.builder()
                        .entryIndex(i)
                        .workflowId(entry.getWorkflowId())
                        .status(RunStatus.RUNNING)
                        .startedAt(Instant.now())
                        .build();

                Workflow workflow;
                try {
                    workflow = workflowService.findByIdEager(entry.getWorkflowId());
                    runEntry.setWorkflowName(workflow.getName());
                    if (workflow.getProject() != null) {
                        runEntry.setProjectName(workflow.getProject().getName());
                    }
                } catch (Exception e) {
                    log.error("Chain {}: Workflow {} not found", chain.getName(), entry.getWorkflowId(), e);
                    runEntry.setStatus(RunStatus.FAILED);
                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), Instant.now()).toMillis());
                    run.getEntryRuns().add(runEntry);
                    hasFailures = true;
                    chainRunRepository.save(run);
                    broadcastStatus(runId, run);

                    if (shouldStop(chain, entry)) {
                        run.setStatus(RunStatus.FAILED);
                        run.setError("Workflow not found: " + entry.getWorkflowId());
                        chainFailed = true;
                        break;
                    }
                    continue;
                }

                // Build context: chain-level + entry-level + previous_chain_output
                Map<String, Object> context = new HashMap<>();
                if (inputContext != null) {
                    context.putAll(inputContext);
                }
                if (entry.getInputContext() != null) {
                    context.putAll(entry.getInputContext());
                }
                // Always set it (empty string for the first entry) so {{ previous_chain_output }}
                // resolves instead of leaking the literal placeholder to the LLM.
                context.put("previous_chain_output", previousOutput != null ? previousOutput : "");

                run.getEntryRuns().add(runEntry);
                chainRunRepository.save(run);
                broadcastStatus(runId, run);

                try {
                    WorkflowRun workflowRun = workflowExecutor.execute(workflow, context);
                    runEntry.setWorkflowRunId(workflowRun.getId());
                    chainRunRepository.save(run);
                    broadcastStatus(runId, run);

                    WorkflowRun completed = runWaiter.waitForCompletion(workflowRun.getId(), cancelled);
                    // Fresh read to avoid stale Hibernate cache
                    completed = workflowRunRepository.findById(completed.getId()).orElseThrow();

                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), runEntry.getCompletedAt()).toMillis());

                    if (completed.getStatus() == RunStatus.COMPLETED) {
                        runEntry.setStatus(RunStatus.COMPLETED);
                        previousOutput = runWaiter.extractOutput(completed);
                    } else if (completed.getStatus() == RunStatus.COMPLETED_WITH_ERRORS) {
                        runEntry.setStatus(RunStatus.COMPLETED_WITH_ERRORS);
                        previousOutput = runWaiter.extractOutput(completed);
                        hasWarnings = true;
                    } else if (completed.getStatus() == RunStatus.CANCELLED) {
                        runEntry.setStatus(RunStatus.CANCELLED);
                        run.setStatus(RunStatus.CANCELLED);
                        chainFailed = true;
                    } else {
                        runEntry.setStatus(RunStatus.FAILED);
                        hasFailures = true;
                        if (shouldStop(chain, entry)) {
                            run.setStatus(RunStatus.FAILED);
                            run.setError("Workflow '" + workflow.getName() + "' failed");
                            chainFailed = true;
                        }
                    }

                    chainRunRepository.save(run);
                    broadcastStatus(runId, run);

                    if (chainFailed) break;

                } catch (Exception e) {
                    log.error("Chain {}: Error executing workflow {}", chain.getName(), workflow.getName(), e);
                    runEntry.setStatus(RunStatus.FAILED);
                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), Instant.now()).toMillis());
                    hasFailures = true;
                    chainRunRepository.save(run);
                    broadcastStatus(runId, run);

                    if (shouldStop(chain, entry)) {
                        run.setStatus(RunStatus.FAILED);
                        run.setError(e.getMessage());
                        chainFailed = true;
                        break;
                    }
                }
            }

            if (!chainFailed) {
                long completedCount = run.getEntryRuns().stream()
                        .filter(e -> e.getStatus() == RunStatus.COMPLETED
                                  || e.getStatus() == RunStatus.COMPLETED_WITH_ERRORS)
                        .count();

                if (hasFailures && completedCount == 0) {
                    run.setStatus(RunStatus.FAILED);
                } else if (hasFailures || hasWarnings) {
                    run.setStatus(RunStatus.COMPLETED_WITH_ERRORS);
                } else {
                    run.setStatus(RunStatus.COMPLETED);
                }
            }

        } catch (Exception e) {
            log.error("Chain {} execution failed unexpectedly", chain.getName(), e);
            run.setStatus(RunStatus.FAILED);
            run.setError(e.getMessage());
        } finally {
            run.setCompletedAt(Instant.now());
            if (run.getStartedAt() != null) {
                run.setDurationMs(Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
            }
            chainRunRepository.save(run);
            broadcastStatus(runId, run);
            activeCancellations.remove(runId);

            if (run.getStatus() == RunStatus.COMPLETED) {
                activityLogService.info(LogCategory.EXECUTION, "Chain execution completed: " + chain.getName(), "ChainExecutor", runId, null);
            } else if (run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS) {
                activityLogService.warn(LogCategory.EXECUTION, "Chain completed with warnings: " + chain.getName(), "ChainExecutor", runId, null);
            } else if (run.getStatus() == RunStatus.FAILED) {
                activityLogService.error(LogCategory.EXECUTION, "Chain execution failed: " + chain.getName(), "ChainExecutor", runId, null);
            } else if (run.getStatus() == RunStatus.CANCELLED) {
                activityLogService.warn(LogCategory.EXECUTION, "Chain execution cancelled: " + chain.getName(), "ChainExecutor", runId, null);
            }
        }
    }

    private boolean shouldStop(WorkflowChain chain, ChainEntry entry) {
        return entry.isStopOnFailure() && chain.isStopOnFailure();
    }

    private void broadcastStatus(String runId, ChainRun run) {
        try {
            messagingTemplate.convertAndSend("/topic/chains/" + runId + "/status", run.getStatus().name());
        } catch (Exception e) {
            log.debug("Failed to broadcast chain status", e);
        }
    }
}
