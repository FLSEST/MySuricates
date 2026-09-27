package com.automation.service.goal;

import com.automation.dto.request.UpdateTodoPlanningRequest;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.entity.GoalMilestone;
import com.automation.repository.GoalMilestoneRepository;
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
 * Einordnung und Einschaetzung eines einzelnen TODOs (TODO dc0566f8): Ziel, Rang, Komplexitaet,
 * empfohlener Effort, Begruendung und offene Frage. Mensch und KI schreiben ueber denselben Weg;
 * {@code ratedBy} haelt fest, wer zuletzt eingeschaetzt hat.
 */
@Service
@RequiredArgsConstructor
public class TodoPlanningService {

    static final int MAX_TEXT = 2000;

    private final TodoRepository todoRepository;
    private final GoalRepository goalRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final TodoResponseAssembler responseAssembler;
    private final GoalProjectRepository goalProjectRepository;

    @Transactional
    public TodoResponse update(String todoId, UpdateTodoPlanningRequest request) {
        Todo todo = todoRepository.findById(todoId).orElseThrow(() -> new ResourceNotFoundException("Todo", todoId));

        if (request.goalId() != null) {
            applyGoal(todo, request.goalId().trim());
        }
        if (request.milestoneId() != null) {
            applyMilestone(todo, request.milestoneId().trim());
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
        return responseAssembler.toResponse(todoRepository.save(todo));
    }

    /**
     * Ziel setzen oder loesen. Ist das Projekt des TODOs dem Ziel noch nicht zugeordnet, kommt es dazu
     * (TODO c60bf914) – Kandidatenliste, Plugin-Triage und Planungsfeld arbeiten projektuebergreifend.
     */
    private void applyGoal(Todo todo, String goalId) {
        if (goalId.isEmpty()) {
            todo.setGoalId(null);
            todo.setGoalRank(null);
            todo.setMilestoneId(null);
            return;
        }
        if (!goalId.equals(todo.getGoalId())) {
            if (!goalRepository.existsById(goalId)) {
                throw new ResourceNotFoundException("Goal", goalId);
            }
            todo.setGoalId(goalId);
            todo.setGoalRank(null);
            todo.setMilestoneId(null);
        }
        if (todo.getProjectId() != null) {
            goalProjectRepository.linkIfAbsent(goalId, todo.getProjectId());
        }
    }

    /**
     * Meilenstein zuordnen (TODO 259f4f66): nur einer des eigenen Ziels – sonst 400, damit ein TODO
     * nie auf einen Meilenstein zeigt, dessen Ziel es nicht zaehlt. Seit TODO c60bf914 auch nur einer
     * des eigenen Projekts; ein TODO ohne Projekt passt in kein Teilziel mit Projekt. Altdaten mit
     * Abweichung bleiben lesbar – geprueft wird nur beim Schreiben. {@code ""} loest die Zuordnung.
     */
    private void applyMilestone(Todo todo, String milestoneId) {
        if (milestoneId.isEmpty()) {
            todo.setMilestoneId(null);
            return;
        }
        GoalMilestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("GoalMilestone", milestoneId));
        if (todo.getGoalId() == null || !todo.getGoalId().equals(milestone.getGoalId())) {
            throw new IllegalArgumentException("Meilenstein " + milestoneId
                    + " gehoert nicht zum Ziel des TODOs – erst goalId setzen");
        }
        if (milestone.getProjectId() != null && !milestone.getProjectId().equals(todo.getProjectId())) {
            throw new IllegalArgumentException("Teilziel " + milestoneId + " gehoert zum Projekt " + milestone.getProjectId()
                    + ", das TODO " + (todo.getProjectId() == null ? "hat kein Projekt" : "zum Projekt " + todo.getProjectId()));
        }
        todo.setMilestoneId(milestoneId);
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
