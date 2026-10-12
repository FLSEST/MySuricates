package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.security.RunTokenService;
import com.automation.util.RunAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Terminal bookkeeping of a run: completion timestamps, merging the context's run metadata,
 * the final save, the status broadcast and the completion event.
 *
 * <p>Runs from {@code WorkflowExecutor}'s finally block — before the run is unregistered, so the
 * ghost-run watchdog never sees DB=RUNNING with an empty registry. A failing event listener is
 * logged, never rethrown: the run is already persisted at that point.
 *
 * <p>Extracted 1:1 from {@code WorkflowExecutor} (clean-code block 3c).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunFinalizer {

    private final WorkflowRunRepository runRepository;
    private final ExecutionBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final RunTokenService runTokens;

    public void finish(WorkflowRun run, ExecutionContext context, String workflowId, String workflowName,
                       Project project, Map<String, Object> workflowMeta) {
        // The run token ends before anything else, the completion listeners included (TODO cfb5d56b)
        RunAccess.clear();
        try {
            runTokens.revoke(run.getId(), workflowId);
        } catch (RuntimeException e) {
            log.warn("Could not log the run token summary of {}: {}", run.getId(), e.getMessage());
        }
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

        // Broadcast final status
        broadcaster.broadcastRunStatus(run.getId(), run.getStatus());

        // Publish domain event for listeners (e.g. AutoTodoService)
        try {
            eventPublisher.publishEvent(new WorkflowRunCompletedEvent(
                    this, run.getId(), workflowId, workflowName,
                    project != null ? project.getId() : null,
                    project != null ? project.getPath() : null,
                    run.getStatus(),
                    run.getMetadata(), workflowMeta, run.getError()));
        } catch (Exception e) {
            log.error("Failed to publish WorkflowRunCompletedEvent for run {}: {}", run.getId(), e.getMessage(), e);
        }
    }
}
