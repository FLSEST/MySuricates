package com.automation.service.execution;

import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Read model for the Workflow-Debug page: workflows whose most recent run failed
 * ("what is currently broken"). One entry per workflow.
 *
 * <p>Single source of truth shared by the REST endpoint
 * ({@code GET /api/v1/debug/failed-workflows}) and the assistant's
 * {@code FailedWorkflowsContextSection}, so the page and the assistant always see the same set.
 *
 * <p>Lean by design: the underlying query fetches workflow + project but never the
 * {@code StepResult} blobs (pool-size=1). The error overview is derived from the run's
 * {@code error} map alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowDebugService {

    /** A run counts as "broken" if it failed outright or completed with step errors. */
    public static final List<RunStatus> BROKEN_STATUSES =
            List.of(RunStatus.FAILED, RunStatus.COMPLETED_WITH_ERRORS);

    private final WorkflowRunRepository workflowRunRepository;

    @Transactional(readOnly = true)
    public List<BrokenWorkflowResponse> getBrokenWorkflows() {
        return workflowRunRepository.findBrokenWorkflows(BROKEN_STATUSES).stream()
                .map(this::toResponse)
                .toList();
    }

    private BrokenWorkflowResponse toResponse(WorkflowRun run) {
        Workflow wf = run.getWorkflow();
        String workflowName = run.getWorkflowName() != null && !run.getWorkflowName().isBlank()
                ? run.getWorkflowName()
                : (wf != null ? wf.getName() : null);

        String projectName = null;
        try {
            if (wf != null && wf.getProject() != null) {
                projectName = wf.getProject().getName();
            }
        } catch (Exception e) {
            // Defensive: project association edge cases (soft-delete / OSIV disabled).
            log.debug("Could not resolve project name for run {}", run.getId(), e);
        }

        Map<String, Object> error = run.getError() != null ? run.getError() : Map.of();
        return new BrokenWorkflowResponse(
                wf != null ? wf.getId() : null,
                workflowName,
                projectName,
                run.getId(),
                run.getStatus(),
                asString(error.get("message")),
                asInteger(error.get("step")),
                asString(error.get("blockType")),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getDurationMs(),
                run.getTriggeredByScheduleId()
        );
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private static Integer asInteger(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
