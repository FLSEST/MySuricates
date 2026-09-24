package com.automation.integration.scenario;

import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.TodoRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code goal} of a scenario (TODO 259f4f66): a goal with milestones and TODOs in the scenario's
 * project, dates relative to now ({@code targetInDays}, {@code dueInDays}), so a run with
 * {@code "goal_id": "@goal"} sees a fixed picture. A TODO names its milestone by index
 * ({@code "milestone": 0}).
 */
final class ScenarioGoals {

    private final GoalRepository goalRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final TodoRepository todoRepository;
    private final List<String> goalIds = new ArrayList<>();

    ScenarioGoals(GoalRepository goalRepository, GoalMilestoneRepository milestoneRepository, TodoRepository todoRepository) {
        this.goalRepository = goalRepository;
        this.milestoneRepository = milestoneRepository;
        this.todoRepository = todoRepository;
    }

    /** Creates the goal and returns its id. */
    String create(Project project, JsonNode spec) {
        Instant now = Instant.now();
        Goal goal = goalRepository.saveAndFlush(Goal.builder()
                .title(spec.path("title").asText("Scenario goal"))
                .targetDate(spec.has("targetInDays") ? days(now, spec.path("targetInDays")) : null)
                .build());
        goalIds.add(goal.getId());
        List<String> milestoneIds = new ArrayList<>();
        for (JsonNode milestone : spec.path("milestones")) {
            milestoneIds.add(milestoneRepository.saveAndFlush(GoalMilestone.builder()
                    .goalId(goal.getId())
                    .title(milestone.path("title").asText())
                    .dueDate(days(now, milestone.path("dueInDays")))
                    .build()).getId());
        }
        for (JsonNode todo : spec.path("todos")) {
            todoRepository.saveAndFlush(Todo.builder()
                    .title(todo.path("title").asText())
                    .status(Todo.Status.valueOf(todo.path("status").asText("OPEN")))
                    .priority(Todo.Priority.valueOf(todo.path("priority").asText("MEDIUM")))
                    .projectId(project.getId())
                    .goalId(goal.getId())
                    .milestoneId(todo.has("milestone") ? milestoneIds.get(todo.path("milestone").asInt()) : null)
                    .dueDate(todo.has("dueInDays") ? days(now, todo.path("dueInDays")) : null)
                    .build());
        }
        return goal.getId();
    }

    /** Goals of this scenario; their milestones go with them ({@code ON DELETE CASCADE}), TODOs are the runner's. */
    void deleteAll() {
        goalIds.forEach(goalRepository::deleteById);
        goalIds.clear();
    }

    private static Instant days(Instant now, JsonNode days) {
        return now.plus(Duration.ofHours(Math.round(days.asDouble() * 24)));
    }
}
