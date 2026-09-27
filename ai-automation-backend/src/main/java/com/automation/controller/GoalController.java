package com.automation.controller;

import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.GoalGuardRequest;
import com.automation.dto.request.GoalIdsRequest;
import com.automation.dto.request.GoalProjectRequest;
import com.automation.dto.request.GoalTodoIdsRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.Goal;
import com.automation.service.goal.GoalMembershipService;
import com.automation.service.goal.GoalMilestoneService;
import com.automation.service.goal.GoalProjectService;
import com.automation.service.goal.GoalRelationsService;
import com.automation.service.goal.GoalService;
import com.automation.service.goal.guard.GoalGuardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ziele: TODOs projektuebergreifend auf ein Ergebnis hin sammeln, ordnen und den Fortschritt
 * zeigen (TODO dc0566f8). Die Einschaetzung eines einzelnen TODOs laeuft ueber
 * {@code PATCH /api/v1/todos/{id}/planning} – dort auch die Zuordnung zu einem Meilenstein.
 * Meilensteine und Ziel-Waechter: TODO 259f4f66. Projekte des Ziels, Teilziele mit Projekt und
 * Reihenfolge: TODO c60bf914 (im Code heissen Teilziele weiter {@code milestones}).
 */
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final GoalMembershipService membershipService;
    private final GoalRelationsService relationsService;
    private final GoalMilestoneService milestoneService;
    private final GoalProjectService projectService;
    private final GoalGuardService guardService;

    /**
     * Ohne {@code status}: aktive und erledigte Ziele, neueste zuerst. Mit {@code projectId} nur die
     * Ziele, denen dieses Projekt zugeordnet ist.
     */
    @GetMapping
    public List<GoalResponse> listGoals(@RequestParam(required = false) List<Goal.Status> status,
                                        @RequestParam(required = false) String projectId) {
        return goalService.list(status, projectId);
    }

    @PostMapping
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(request));
    }

    @GetMapping("/{id}")
    public GoalDetailResponse getGoal(@PathVariable String id) {
        return goalService.detail(id);
    }

    /**
     * Relationskarte (TODO c6ea8f5c): je TODO Herkunft, Bearbeitung (Fix-Workflow, passende
     * TodoTrigger) und Ergebnis. {@code include} holt bis zu 20 noch nicht zugeordnete TODOs als
     * Kandidaten dazu ({@code candidate: true}).
     */
    @GetMapping("/{id}/relations")
    public GoalRelationsResponse getRelations(@PathVariable String id,
                                              @RequestParam(required = false) List<String> include) {
        return relationsService.relations(id, include);
    }

    @PutMapping("/{id}")
    public GoalResponse updateGoal(@PathVariable String id, @RequestBody UpdateGoalRequest request) {
        return goalService.update(id, request);
    }

    /** Loescht das Ziel; seine TODOs bleiben und verlieren nur die Zuordnung, ein Waechter pausiert. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(@PathVariable String id) {
        guardService.pauseForDeletedGoal(id); // sein Waechter-Workflow bleibt, laeuft aber nicht mehr
        goalService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Ordnet TODOs dem Ziel zu (ohne Rang – sie stehen hinter den eingeordneten). */
    @PostMapping("/{id}/todos")
    public GoalDetailResponse assignTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.assign(id, request.todoIds());
    }

    /** Reihenfolge: die genannten TODOs bekommen Rang 1..n, alle uebrigen des Ziels keinen. */
    @PutMapping("/{id}/order")
    public GoalDetailResponse reorderTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.reorder(id, request.todoIds());
    }

    /** Projekt ans Ende der Zielprojekte haengen; schon zugeordnet = unveraendert (idempotent). */
    @PostMapping("/{id}/projects")
    public GoalDetailResponse addProject(@PathVariable String id, @Valid @RequestBody GoalProjectRequest request) {
        return projectService.add(id, request.projectId());
    }

    /** Zielprojekte neu ordnen: die genannten vorn, die uebrigen in bisheriger Folge dahinter. */
    @PutMapping("/{id}/projects/order")
    public GoalDetailResponse reorderProjects(@PathVariable String id, @Valid @RequestBody GoalIdsRequest request) {
        return projectService.reorder(id, request.ids());
    }

    /**
     * Projekt vom Ziel loesen. Haengen daran im Ziel noch Teilziele oder TODOs: 409 – mit
     * {@code cascade=true} werden die Teilziele geloescht und die TODOs vom Ziel geloest.
     */
    @DeleteMapping("/{id}/projects/{projectId}")
    public ResponseEntity<Void> removeProject(@PathVariable String id, @PathVariable String projectId,
                                              @RequestParam(defaultValue = "false") boolean cascade) {
        projectService.remove(id, projectId, cascade);
        return ResponseEntity.noContent().build();
    }

    /**
     * Meilensteine (Teilziele) des Ziels nach Projekt-Position und Position im Projekt, mit Zustand
     * (OPEN/DUE_SOON/OVERDUE/REACHED; ohne Datum nur OPEN/REACHED) und Zaehlern.
     */
    @GetMapping("/{id}/milestones")
    public List<GoalMilestoneResponse> listMilestones(@PathVariable String id) {
        return milestoneService.list(id);
    }

    @PostMapping("/{id}/milestones")
    public ResponseEntity<GoalMilestoneResponse> createMilestone(@PathVariable String id,
                                                                 @Valid @RequestBody CreateGoalMilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(milestoneService.create(id, request));
    }

    /** Teilziele EINES Projekts neu ordnen; Antwort: alle Teilziele des Ziels. */
    @PutMapping("/{id}/milestones/order")
    public List<GoalMilestoneResponse> reorderMilestones(@PathVariable String id, @Valid @RequestBody GoalIdsRequest request) {
        return milestoneService.reorder(id, request.ids());
    }

    @PutMapping("/{id}/milestones/{milestoneId}")
    public GoalMilestoneResponse updateMilestone(@PathVariable String id, @PathVariable String milestoneId,
                                                 @RequestBody UpdateGoalMilestoneRequest request) {
        return milestoneService.update(id, milestoneId, request);
    }

    /** Loescht den Meilenstein; seine TODOs bleiben im Ziel und verlieren nur die Zuordnung. */
    @DeleteMapping("/{id}/milestones/{milestoneId}")
    public ResponseEntity<Void> deleteMilestone(@PathVariable String id, @PathVariable String milestoneId) {
        milestoneService.delete(id, milestoneId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ziel-Waechter ein- oder ausschalten: beim ersten Einschalten Workflow aus der Vorlage
     * {@code goal-guard} plus taeglicher Zeitplan, danach nur noch Zeitplan an/aus.
     */
    @PostMapping("/{id}/guard")
    public GoalResponse setGuard(@PathVariable String id, @Valid @RequestBody GoalGuardRequest request) {
        return guardService.setGuard(id, request);
    }
}
