package com.automation.service.goal;

import com.automation.dto.request.UpdateTodoPlanningRequest;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.entity.GoalWorkPackage;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.ai.ReasoningEffort;
import com.automation.service.todo.TodoResponseAssembler;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Classification and rating of a single TODO (TODO dc0566f8): goal, rank, complexity,
 * recommended effort, rationale and open question. Human and AI write through the same path;
 * {@code ratedBy} records who rated last.
 */
@Service
@RequiredArgsConstructor
public class TodoPlanningService {

    static final int MAX_TEXT = 2000;

    private final TodoRepository todoRepository;
    private final GoalRepository goalRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final TodoResponseAssembler responseAssembler;
    private final GoalProjectRepository goalProjectRepository;

    @Transactional
    public TodoResponse update(String todoId, UpdateTodoPlanningRequest request) {
        Todo todo = todoRepository.findById(todoId).orElseThrow(() -> new ResourceNotFoundException("Todo", todoId));
        if (notBlank(request.openQuestion()) && notBlank(request.decision())) {
            throw new IllegalArgumentException("Eine neue Frage und eine Entscheidung zugleich – erst fragen, dann entscheiden");
        }

        if (request.goalId() != null) {
            applyGoal(todo, request.goalId().trim());
        }
        if (request.workPackageId() != null) {
            applyWorkPackage(todo, request.workPackageId().trim());
        }
        if (request.goalRank() != null) {
            if (todo.getGoalId() == null && request.goalRank() > 0) {
                throw new IllegalArgumentException("Ein Rang braucht ein Ziel – erst goalId setzen");
            }
            todo.setGoalRank(request.goalRank() > 0 ? request.goalRank() : null);
        }

        boolean rated = false;
        if (request.complexity() != null) {
            todo.setComplexity(parseComplexity(request.complexity()));
            rated = true;
        }
        if (request.suggestedEffort() != null) {
            todo.setSuggestedEffort(parseEffort(request.suggestedEffort()));
            rated = true;
        }
        if (request.ratingNote() != null) {
            todo.setRatingNote(text(request.ratingNote(), "ratingNote"));
            rated = true;
        }
        if (rated) {
            todo.setRatedBy(request.ratedBy() != null ? request.ratedBy() : Todo.RatedBy.USER);
            todo.setRatedAt(Instant.now());
        }
        if (request.openQuestion() != null) {
            todo.setOpenQuestion(text(request.openQuestion(), "openQuestion"));
        }
        if (request.decision() != null) {
            applyDecision(todo, text(request.decision(), "decision"), request.ratedBy());
        }
        return responseAssembler.toResponse(todoRepository.save(todo));
    }

    /**
     * Record the decision (TODO 68a5e24e): text, time, who ({@code ratedBy}, otherwise USER) - and
     * the open question is thereby answered. {@code ""} ({@code null} after {@link #text}) withdraws
     * the decision. A later new question leaves it in place; the state is then QUESTION.
     */
    private static void applyDecision(Todo todo, String decision, Todo.RatedBy ratedBy) {
        if (decision == null) {
            todo.setDecision(null);
            todo.setDecidedAt(null);
            todo.setDecidedBy(null);
            return;
        }
        todo.setDecision(decision);
        todo.setDecidedAt(Instant.now());
        todo.setDecidedBy(ratedBy != null ? ratedBy : Todo.RatedBy.USER);
        todo.setOpenQuestion(null);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Set or detach the goal. If the TODO's project is not yet assigned to the goal, it is added
     * (TODO c60bf914) - candidate list, plugin triage and planning field work across projects.
     */
    private void applyGoal(Todo todo, String goalId) {
        if (goalId.isEmpty()) {
            todo.setGoalId(null);
            todo.setGoalRank(null);
            todo.setWorkPackageId(null);
            return;
        }
        if (!goalId.equals(todo.getGoalId())) {
            if (!goalRepository.existsById(goalId)) {
                throw new ResourceNotFoundException("Goal", goalId);
            }
            todo.setGoalId(goalId);
            todo.setGoalRank(null);
            todo.setWorkPackageId(null);
        }
        if (todo.getProjectId() != null) {
            goalProjectRepository.linkIfAbsent(goalId, todo.getProjectId());
        }
    }

    /**
     * Assign a work package (TODO c60bf914, until V92 "milestone"): only one of the own goal - otherwise
     * 400, so that a TODO never points to a package whose goal does not count it - and of the own
     * project; a TODO without a project fits no package with a project. Legacy data with a deviation stays
     * readable - it is only checked on write. {@code ""} detaches the assignment.
     */
    private void applyWorkPackage(Todo todo, String workPackageId) {
        if (workPackageId.isEmpty()) {
            todo.setWorkPackageId(null);
            return;
        }
        GoalWorkPackage workPackage = workPackageRepository.findById(workPackageId)
                .orElseThrow(() -> new ResourceNotFoundException("GoalWorkPackage", workPackageId));
        if (todo.getGoalId() == null || !todo.getGoalId().equals(workPackage.getGoalId())) {
            throw new IllegalArgumentException("Arbeitspaket " + workPackageId
                    + " gehoert nicht zum Ziel des TODOs – erst goalId setzen");
        }
        if (workPackage.getProjectId() != null && !workPackage.getProjectId().equals(todo.getProjectId())) {
            throw new IllegalArgumentException("Arbeitspaket " + workPackageId + " gehoert zum Projekt "
                    + workPackage.getProjectId() + ", das TODO "
                    + (todo.getProjectId() == null ? "hat kein Projekt" : "zum Projekt " + todo.getProjectId()));
        }
        todo.setWorkPackageId(workPackageId);
    }

    /** {@code ""} clears the rating; an unknown value names S, M, L and XL (EnumParameter, TODO a9bf13d5). */
    static Todo.Complexity parseComplexity(String value) {
        return EnumParameter.parseOptional(Todo.Complexity.class, "complexity", value);
    }

    static String parseEffort(String value) {
        if (value.isBlank()) {
            return null;
        }
        return ReasoningEffort.parse(value)
                .map(ReasoningEffort::cliValue)
                .orElseThrow(() -> new IllegalArgumentException(
                        "suggestedEffort muss low, medium, high oder xhigh sein, nicht '" + value + "'"));
    }

    static String text(String value, String field) {
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException(field + " ist laenger als " + MAX_TEXT + " Zeichen");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
