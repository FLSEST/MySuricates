package com.automation.service.chain;

import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
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

@Service
@Slf4j
public class ChainExecutor {

    private static final Duration MAX_WAIT = Duration.ofMinutes(30);

    private final ChainRunRepository chainRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public ChainExecutor(
            ChainRunRepository chainRunRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowService workflowService,
            @Lazy WorkflowExecutor workflowExecutor,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor
    ) {
        this.chainRunRepository = chainRunRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext) {
        ChainRun run = ChainRun.builder()
                .chain(chain)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .inputContext(inputContext != null ? new HashMap<>(inputContext) : new HashMap<>())
                .entryRuns(new ArrayList<>())
                .build();
        run = chainRunRepository.save(run);

        final String runId = run.getId();
        activeCancellations.put(runId, new AtomicBoolean(false));

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
                        .status("RUNNING")
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
                    runEntry.setStatus("FAILED");
                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), Instant.now()).toMillis());
                    run.getEntryRuns().add(runEntry);
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
                if (previousOutput != null) {
                    context.put("previous_chain_output", previousOutput);
                }

                run.getEntryRuns().add(runEntry);
                chainRunRepository.save(run);
                broadcastStatus(runId, run);

                try {
                    WorkflowRun workflowRun = workflowExecutor.execute(workflow, context);
                    runEntry.setWorkflowRunId(workflowRun.getId());
                    chainRunRepository.save(run);
                    broadcastStatus(runId, run);

                    WorkflowRun completed = waitForCompletion(workflowRun.getId(), cancelled);

                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), runEntry.getCompletedAt()).toMillis());

                    if (completed.getStatus() == RunStatus.COMPLETED) {
                        runEntry.setStatus("COMPLETED");
                        previousOutput = extractOutput(completed);
                    } else if (completed.getStatus() == RunStatus.CANCELLED) {
                        runEntry.setStatus("CANCELLED");
                        run.setStatus(RunStatus.CANCELLED);
                        chainFailed = true;
                    } else {
                        runEntry.setStatus("FAILED");
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
                    runEntry.setStatus("FAILED");
                    runEntry.setCompletedAt(Instant.now());
                    runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), Instant.now()).toMillis());
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
                run.setStatus(RunStatus.COMPLETED);
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
        }
    }

    private boolean shouldStop(WorkflowChain chain, ChainEntry entry) {
        return entry.isStopOnFailure() && chain.isStopOnFailure();
    }

    private WorkflowRun waitForCompletion(String runId, AtomicBoolean cancelled) {
        Instant deadline = Instant.now().plus(MAX_WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (cancelled != null && cancelled.get()) {
                throw new RuntimeException("Chain execution cancelled");
            }
            WorkflowRun run = workflowRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return run;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for workflow completion");
            }
        }
        throw new RuntimeException("Workflow timed out after " + MAX_WAIT.toMinutes() + " minutes");
    }

    private String extractOutput(WorkflowRun run) {
        if (run.getSteps() == null || run.getSteps().isEmpty()) {
            return null;
        }
        StepResult lastStep = run.getSteps().get(run.getSteps().size() - 1);
        return lastStep.getOutput();
    }

    private void broadcastStatus(String runId, ChainRun run) {
        try {
            messagingTemplate.convertAndSend("/topic/chains/" + runId + "/status", run.getStatus().name());
        } catch (Exception e) {
            log.debug("Failed to broadcast chain status", e);
        }
    }
}
