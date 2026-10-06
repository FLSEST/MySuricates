package com.automation.controller;

import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.CreateGoalWorkPackageRequest;
import com.automation.dto.request.GoalGuardRequest;
import com.automation.dto.request.GoalIdsRequest;
import com.automation.dto.request.GoalProjectRequest;
import com.automation.dto.request.GoalTodoIdsRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.request.UpdateGoalWorkPackageRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Goal;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.goal.GoalCandidateService;
import com.automation.service.goal.GoalMembershipService;
import com.automation.service.goal.GoalMilestoneService;
import com.automation.service.goal.GoalProjectService;
import com.automation.service.goal.GoalRelationsService;
import com.automation.service.goal.GoalService;
import com.automation.service.goal.GoalWorkPackageService;
import com.automation.service.goal.guard.GoalGuardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Goals: collect TODOs across projects towards one outcome, order them and show the progress
 * (TODO dc0566f8). A single TODO is assessed through {@code PATCH /api/v1/todos/{id}/planning},
 * which also assigns it to a work package. Goal guard: TODO 259f4f66. Projects of the goal, work
 * packages (per project, with period and order) and milestones (dates of the goal, optionally tied to
 * packages): TODO c60bf914.
 */
@RestController
@RequiresModule(ModuleCatalog.GOALS)
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final GoalMembershipService membershipService;
    private final GoalRelationsService relationsService;
    private final GoalWorkPackageService workPackageService;
    private final GoalMilestoneService milestoneService;
    private final GoalProjectService projectService;
    private final GoalCandidateService candidateService;
    private final GoalGuardService guardService;

    /**
     * Lists goals, newest first; without {@code status} the active and done ones. With {@code projectId}
     * only the goals this project is assigned to.
     */
    @GetMapping
    public List<GoalResponse> listGoals(@RequestParam(required = false) List<Goal.Status> status,
                                        @RequestParam(required = false) String projectId) {
        return goalService.list(status, projectId);
    }

    /**
     * Creates a goal (201); {@code title} is required, optional {@code projectIds} are attached in that order.
     * An unknown project id is a 404.
     */
    @PostMapping
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(request));
    }

    /**
     * Returns the goal with its TODOs, progress per project, projects, work packages and milestones.
     * Reading records the moment a work package or milestone is first found complete or reached.
     */
    @GetMapping("/{id}")
    public GoalDetailResponse getGoal(@PathVariable String id) {
        return goalService.detail(id);
    }

    /**
     * Returns the goal's relation map: per TODO its origin, its handling (fix workflow, matching TODO
     * triggers) and its result (TODO c6ea8f5c). {@code include} adds up to 20 TODOs not yet assigned
     * as candidates ({@code candidate: true}); ids of TODOs that already belong to a goal are skipped.
     */
    @GetMapping("/{id}/relations")
    public GoalRelationsResponse getRelations(@PathVariable String id,
                                              @RequestParam(required = false) List<String> include) {
        return relationsService.relations(id, include);
    }

    /** Partially updates a goal: {@code null} keeps a field, an empty {@code description} or {@code targetDate} clears it. */
    @PutMapping("/{id}")
    public GoalResponse updateGoal(@PathVariable String id, @RequestBody UpdateGoalRequest request) {
        return goalService.update(id, request);
    }

    /**
     * Deletes the goal (204); its TODOs stay and only lose the assignment, a guard is paused.
     * Work packages, milestones and workflow attachments go with it; the guard's workflow stays.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(@PathVariable String id) {
        guardService.pauseForDeletedGoal(id); // its guard workflow stays but no longer runs
        goalService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists open TODOs without a goal that could belong to it, by default only from the goal's projects
     * ({@code allProjects=true}: from all). Paged like {@code GET /api/v1/todos}.
     */
    @GetMapping("/{id}/candidates")
    public PageResponse<TodoResponse> candidates(@PathVariable String id,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(defaultValue = "false") boolean allProjects,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return candidateService.candidates(id, q, tag, allProjects, page, size);
    }

    /**
     * Assigns TODOs to the goal without a rank – they come after the ranked ones.
     * A TODO of another goal moves here and loses its work package.
     */
    @PostMapping("/{id}/todos")
    public GoalDetailResponse assignTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.assign(id, request.todoIds());
    }

    /**
     * Sets the order: the TODOs named get rank 1..n, all other TODOs of the goal none.
     * Every id must already belong to the goal (else 400).
     */
    @PutMapping("/{id}/order")
    public GoalDetailResponse reorderTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.reorder(id, request.todoIds());
    }

    /** Appends a project to the goal's projects; one already assigned stays unchanged (idempotent). */
    @PostMapping("/{id}/projects")
    public GoalDetailResponse addProject(@PathVariable String id, @Valid @RequestBody GoalProjectRequest request) {
        return projectService.add(id, request.projectId());
    }

    /**
     * Reorders the goal's projects: those named first, the others behind them in their previous order.
     * A project not in the goal is 400.
     */
    @PutMapping("/{id}/projects/order")
    public GoalDetailResponse reorderProjects(@PathVariable String id, @Valid @RequestBody GoalIdsRequest request) {
        return projectService.reorder(id, request.ids());
    }

    /**
     * Detaches a project from the goal (204). While work packages or TODOs of the goal still belong to it,
     * the answer is 409 – with {@code cascade=true} the work packages are deleted and the TODOs detached.
     */
    @DeleteMapping("/{id}/projects/{projectId}")
    public ResponseEntity<Void> removeProject(@PathVariable String id, @PathVariable String projectId,
                                              @RequestParam(defaultValue = "false") boolean cascade) {
        projectService.remove(id, projectId, cascade);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists the goal's work packages by project position and position in the project, with state and counters.
     * The state is OPEN/DUE_SOON/OVERDUE/REACHED based on the end; without a period only OPEN/REACHED.
     */
    @GetMapping("/{id}/work-packages")
    public List<GoalWorkPackageResponse> listWorkPackages(@PathVariable String id) {
        return workPackageService.list(id);
    }

    /**
     * Adds a work package at the end of its project (201); without {@code projectId} only if the goal has exactly one project.
     * A named project not yet in the goal is added to it; {@code startDate} and {@code endDate} come together or not at all.
     * The title has at most 200 characters.
     */
    @PostMapping("/{id}/work-packages")
    public ResponseEntity<GoalWorkPackageResponse> createWorkPackage(@PathVariable String id,
                                                                     @Valid @RequestBody CreateGoalWorkPackageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workPackageService.create(id, request));
    }

    /**
     * Reorders the work packages of ONE project; the answer lists all work packages of the goal.
     * That project's packages not named follow behind; packages of different projects are 400, one of another goal 404.
     */
    @PutMapping("/{id}/work-packages/order")
    public List<GoalWorkPackageResponse> reorderWorkPackages(@PathVariable String id, @Valid @RequestBody GoalIdsRequest request) {
        return workPackageService.reorder(id, request.ids());
    }

    /**
     * Partially updates a work package; {@code ""} clears a date, the result needs both dates or none (else 400).
     * {@code projectId} can change only while no TODOs are assigned to the package; the package then moves
     * to the end of the new project.
     */
    @PutMapping("/{id}/work-packages/{workPackageId}")
    public GoalWorkPackageResponse updateWorkPackage(@PathVariable String id, @PathVariable String workPackageId,
                                                     @RequestBody UpdateGoalWorkPackageRequest request) {
        return workPackageService.update(id, workPackageId, request);
    }

    /** Deletes the work package (204); its TODOs stay in the goal, milestones lose the link, its workflow attachments go. */
    @DeleteMapping("/{id}/work-packages/{workPackageId}")
    public ResponseEntity<Void> deleteWorkPackage(@PathVariable String id, @PathVariable String workPackageId) {
        workPackageService.delete(id, workPackageId);
        return ResponseEntity.noContent().build();
    }

    /** Lists the goal's milestones by date, then title, with state and package counters. */
    @GetMapping("/{id}/milestones")
    public List<GoalMilestoneResponse> listMilestones(@PathVariable String id) {
        return milestoneService.list(id);
    }

    /** Creates a milestone (201); {@code dueDate} is required, {@code workPackageIds} must belong to the same goal (else 400). */
    @PostMapping("/{id}/milestones")
    public ResponseEntity<GoalMilestoneResponse> createMilestone(@PathVariable String id,
                                                                 @Valid @RequestBody CreateGoalMilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(milestoneService.create(id, request));
    }

    /** Updates a milestone; {@code workPackageIds} replaces its links, {@code reached} only without linked packages (else 400). */
    @PutMapping("/{id}/milestones/{milestoneId}")
    public GoalMilestoneResponse updateMilestone(@PathVariable String id, @PathVariable String milestoneId,
                                                 @RequestBody UpdateGoalMilestoneRequest request) {
        return milestoneService.update(id, milestoneId, request);
    }

    /** Deletes the milestone (204); the linked work packages stay. */
    @DeleteMapping("/{id}/milestones/{milestoneId}")
    public ResponseEntity<Void> deleteMilestone(@PathVariable String id, @PathVariable String milestoneId) {
        milestoneService.delete(id, milestoneId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Switches the goal guard on or off. The first switch-on creates a workflow from the template
     * {@code goal-guard} plus a daily schedule; after that only the schedule is enabled or disabled.
     * An invalid {@code cron} or no project to put the workflow in is 400.
     */
    @PostMapping("/{id}/guard")
    public GoalResponse setGuard(@PathVariable String id, @Valid @RequestBody GoalGuardRequest request) {
        return guardService.setGuard(id, request);
    }
}
