package com.automation.integration.scenario;

import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalMilestoneWorkPackage;
import com.automation.entity.GoalProject;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalMilestoneWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.TodoRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code goal} of a scenario (TODO 259f4f66, c60bf914): a goal assigned to the scenario's project,
 * with work packages, milestones and TODOs, dates relative to now ({@code targetInDays},
 * {@code startInDays}/{@code endInDays}, {@code dueInDays}), so a run with {@code "goal_id": "@goal"}
 * sees a fixed picture. Work packages belong to the scenario's project in the order given; one with
 * only {@code endInDays} starts on its end day, one with neither has no period. A milestone names its
 * work packages by index ({@code "workPackages": [0, 1]}), a TODO its work package
 * ({@code "workPackage": 0}).
 */
final class ScenarioGoals {

    private final GoalRepository goalRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final GoalMilestoneWorkPackageRepository linkRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final TodoRepository todoRepository;
    private final List<String> goalIds = new ArrayList<>();

    ScenarioGoals(GoalRepository goalRepository, GoalWorkPackageRepository workPackageRepository,
                  GoalMilestoneRepository milestoneRepository, GoalMilestoneWorkPackageRepository linkRepository,
                  GoalProjectRepository goalProjectRepository, TodoRepository todoRepository) {
        this.goalRepository = goalRepository;
        this.workPackageRepository = workPackageRepository;
        this.milestoneRepository = milestoneRepository;
        this.linkRepository = linkRepository;
        this.goalProjectRepository = goalProjectRepository;
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
        goalProjectRepository.saveAndFlush(new GoalProject(goal.getId(), project.getId(), 0));
        List<String> workPackageIds = new ArrayList<>();
        for (JsonNode workPackage : spec.path("workPackages")) {
            Instant end = workPackage.has("endInDays") ? days(now, workPackage.path("endInDays")) : null;
            Instant start = workPackage.has("startInDays") ? days(now, workPackage.path("startInDays")) : end;
            workPackageIds.add(workPackageRepository.saveAndFlush(GoalWorkPackage.builder()
                    .goalId(goal.getId())
                    .projectId(project.getId())
                    .position(workPackageIds.size())
                    .title(workPackage.path("title").asText())
                    .startDate(start)
                    .endDate(end)
                    .build()).getId());
        }
        for (JsonNode milestone : spec.path("milestones")) {
            String milestoneId = milestoneRepository.saveAndFlush(GoalMilestone.builder()
                    .goalId(goal.getId())
                    .title(milestone.path("title").asText())
                    .dueDate(days(now, milestone.path("dueInDays")))
                    .build()).getId();
            for (JsonNode index : milestone.path("workPackages")) {
                linkRepository.saveAndFlush(new GoalMilestoneWorkPackage(milestoneId, workPackageIds.get(index.asInt())));
            }
        }
        for (JsonNode todo : spec.path("todos")) {
            todoRepository.saveAndFlush(Todo.builder()
                    .title(todo.path("title").asText())
                    .status(Todo.Status.valueOf(todo.path("status").asText("OPEN")))
                    .priority(Todo.Priority.valueOf(todo.path("priority").asText("MEDIUM")))
                    .projectId(project.getId())
                    .goalId(goal.getId())
                    .workPackageId(todo.has("workPackage") ? workPackageIds.get(todo.path("workPackage").asInt()) : null)
                    .dueDate(todo.has("dueInDays") ? days(now, todo.path("dueInDays")) : null)
                    .build());
        }
        return goal.getId();
    }

    /**
     * Goals of this scenario; work packages, milestones, their links and goal projects go with them
     * ({@code ON DELETE CASCADE}), TODOs are the runner's.
     */
    void deleteAll() {
        goalIds.forEach(goalRepository::deleteById);
        goalIds.clear();
    }

    private static Instant days(Instant now, JsonNode days) {
        return now.plus(Duration.ofHours(Math.round(days.asDouble() * 24)));
    }
}
