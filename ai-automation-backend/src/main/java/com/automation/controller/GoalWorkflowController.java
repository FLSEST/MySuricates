package com.automation.controller;

import com.automation.dto.request.AttachGoalWorkflowRequest;
import com.automation.dto.request.UpdateGoalWorkflowRequest;
import com.automation.dto.response.GoalWorkflowResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.goal.GoalWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workflows am Ziel oder an einem Arbeitspaket (TODO 52bc1e8c Ziele Stufe 3): anzeigen, anhaengen,
 * Rolle aendern, loesen. Gestartet wird ueber {@code POST /api/v1/workflows/{id}/execute} mit
 * {@code goal_id} (und optional {@code work_package_id}, {@code goal_locale}) im Kontext; den Stand
 * liefert {@code GET /api/v1/dashboard/workflow-results}. Ein eingeschalteter Ziel-Waechter steht als
 * Eintrag {@code "guard"} zuerst in der Liste.
 */
@RestController
@RequiresModule(ModuleCatalog.GOALS)
@RequestMapping("/api/v1/goals/{goalId}/workflows")
@RequiredArgsConstructor
public class GoalWorkflowController {

    private final GoalWorkflowService goalWorkflowService;

    /**
     * Lists the workflows attached to the goal: the guard first (if switched on), then those on the goal,
     * then those on work packages. Soft-deleted workflows are left out.
     */
    @GetMapping
    public List<GoalWorkflowResponse> list(@PathVariable String goalId) {
        return goalWorkflowService.list(goalId);
    }

    /**
     * Attaches a workflow to the goal or one of its work packages (201); {@code role} is {@code WORKER} (default) or {@code STATUS}.
     * A work package of another goal is a 400, an unknown workflow a 404, the same attachment twice a 409.
     */
    @PostMapping
    public ResponseEntity<GoalWorkflowResponse> attach(@PathVariable String goalId,
                                                       @Valid @RequestBody AttachGoalWorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalWorkflowService.attach(goalId, request));
    }

    /** Changes the role of an attachment ({@code WORKER} or {@code STATUS}); the {@code guard} entry cannot be changed (400). */
    @PatchMapping("/{attachmentId}")
    public GoalWorkflowResponse updateRole(@PathVariable String goalId, @PathVariable String attachmentId,
                                           @Valid @RequestBody UpdateGoalWorkflowRequest request) {
        return goalWorkflowService.updateRole(goalId, attachmentId, request.role());
    }

    /** Loest die Zuordnung, der Workflow bleibt; {@code guard} → 400 (der Waechter hat seinen Schalter). */
    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> detach(@PathVariable String goalId, @PathVariable String attachmentId) {
        goalWorkflowService.detach(goalId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
