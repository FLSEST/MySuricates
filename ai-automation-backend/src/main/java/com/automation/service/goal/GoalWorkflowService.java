package com.automation.service.goal;

import com.automation.dto.projection.GoalWorkflowRow;
import com.automation.dto.request.AttachGoalWorkflowRequest;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalWorkflowResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalWorkflow;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Workflows on the goal or on a work package (TODO 52bc1e8c goals stage 3): attach, change
 * role, detach and read. Starting goes through the existing path {@code POST /workflows/{id}/execute}
 * with {@code goal_id} in the context, the state comes from {@code /dashboard/workflow-results} - there is
 * no separate start or read path here.
 *
 * <p>Uses only repositories (and the guard lookup of the same package). An enabled
 * goal guard appears as a synthetic first entry {@code "guard"} in the list; it cannot be
 * detached, its switch manages it.
 */
@Service
@RequiredArgsConstructor
public class GoalWorkflowService {

    /** ID des synthetischen Waechter-Eintrags. */
    public static final String GUARD_ID = "guard";

    private static final Comparator<GoalWorkflowRow> ORDER = Comparator
            .comparing((GoalWorkflowRow row) -> row.workPackageId() != null)
            .thenComparing(GoalWorkflowRow::projectPosition, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(GoalWorkflowRow::workPackagePosition, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(GoalWorkflowRow::workPackageId, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
            .thenComparing(row -> row.workflowName() == null ? "" : row.workflowName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(GoalWorkflowRow::id);

    private final GoalRepository goalRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final GoalWorkflowRepository goalWorkflowRepository;
    private final WorkflowRepository workflowRepository;
    private final GoalGuardLookup guardLookup;

    /**
     * The guard first (if enabled), then the assignments on the goal, then those on work packages
     * in package order, by workflow name. Soft-deleted workflows are missing.
     */
    @Transactional(readOnly = true)
    public List<GoalWorkflowResponse> list(String goalId) {
        Goal goal = goalRepository.findById(goalId).orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
        List<GoalWorkflowResponse> result = new ArrayList<>();
        guardEntry(goal).ifPresent(result::add);
        goalWorkflowRepository.findRows(goal.getId()).stream()
                .sorted(ORDER)
                .map(row -> new GoalWorkflowResponse(row.id(), row.goalId(), row.workPackageId(), row.workflowId(),
                        row.workflowName(), row.workflowProjectId(), row.role(), false, row.createdAt()))
                .forEach(result::add);
        return result;
    }

    /**
     * Attaches the workflow. Package not from this goal → 400, workflow unknown or deleted → 404,
     * same assignment already present → 409.
     */
    @Transactional
    public GoalWorkflowResponse attach(String goalId, AttachGoalWorkflowRequest request) {
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        GoalWorkflow.Role role = role(request.role(), GoalWorkflow.Role.WORKER);
        Workflow workflow = workflowRepository.findById(request.workflowId().trim())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", request.workflowId()));
        String workPackageId = request.workPackageId() == null || request.workPackageId().isBlank()
                ? null : request.workPackageId().trim();
        if (workPackageId != null && workPackageRepository.findById(workPackageId)
                .filter(p -> p.getGoalId().equals(goalId)).isEmpty()) {
            throw new IllegalArgumentException("Arbeitspaket " + workPackageId + " gehoert nicht zu diesem Ziel");
        }
        if (goalWorkflowRepository.existsAttachment(goalId, workPackageId, workflow.getId())) {
            throw new IllegalStateException("Der Workflow haengt hier schon");
        }
        GoalWorkflow saved = goalWorkflowRepository.save(GoalWorkflow.builder()
                .goalId(goalId).workPackageId(workPackageId).workflowId(workflow.getId()).role(role).build());
        return toResponse(saved, workflow);
    }

    /** New role; the guard entry cannot be changed (400), a foreign assignment is unknown (404). */
    @Transactional
    public GoalWorkflowResponse updateRole(String goalId, String attachmentId, String role) {
        GoalWorkflow attachment = find(goalId, attachmentId);
        attachment.setRole(role(role, null));
        GoalWorkflow saved = goalWorkflowRepository.save(attachment);
        Workflow workflow = workflowRepository.findById(saved.getWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", saved.getWorkflowId()));
        return toResponse(saved, workflow);
    }

    /** Detaches the assignment; the workflow itself stays. The guard goes through its switch (400). */
    @Transactional
    public void detach(String goalId, String attachmentId) {
        goalWorkflowRepository.delete(find(goalId, attachmentId));
    }

    private GoalWorkflow find(String goalId, String attachmentId) {
        if (GUARD_ID.equals(attachmentId)) {
            throw new IllegalArgumentException("Der Ziel-Waechter wird ueber seinen Schalter verwaltet, nicht hier");
        }
        return goalWorkflowRepository.findById(attachmentId)
                .filter(a -> a.getGoalId().equals(goalId))
                .orElseThrow(() -> new ResourceNotFoundException("GoalWorkflow", attachmentId));
    }

    private Optional<GoalWorkflowResponse> guardEntry(Goal goal) {
        if (goal.getGuardWorkflowId() == null) {
            return Optional.empty();
        }
        GoalGuardResponse guard = guardLookup.of(goal);
        if (!guard.enabled()) {
            return Optional.empty();
        }
        return workflowRepository.findById(goal.getGuardWorkflowId())
                .map(w -> new GoalWorkflowResponse(GUARD_ID, goal.getId(), null, w.getId(), w.getName(),
                        w.getProject() != null ? w.getProject().getId() : null, GoalWorkflow.Role.STATUS, true,
                        w.getCreatedAt()));
    }

    private static GoalWorkflowResponse toResponse(GoalWorkflow a, Workflow w) {
        return new GoalWorkflowResponse(a.getId(), a.getGoalId(), a.getWorkPackageId(), a.getWorkflowId(), w.getName(),
                w.getProject() != null ? w.getProject().getId() : null, a.getRole(), false, a.getCreatedAt());
    }

    /** {@code WORKER}/{@code STATUS}; leer = {@code fallback} (null = Pflicht, dann 400). */
    private static GoalWorkflow.Role role(String value, GoalWorkflow.Role fallback) {
        GoalWorkflow.Role role = EnumParameter.parseOptional(GoalWorkflow.Role.class, "role", value);
        if (role == null && fallback == null) {
            throw new IllegalArgumentException("role muss WORKER oder STATUS sein");
        }
        return role != null ? role : fallback;
    }
}
