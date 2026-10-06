package com.automation.service.goal;

import com.automation.dto.response.GoalDetailResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalProject;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The projects of a goal (TODO c60bf914): assign, remove, order. That a TODO from a
 * new project brings its project along automatically is handled by the TODOs' own write paths
 * ({@link GoalMembershipService}, {@link TodoPlanningService}, {@code TodoGoalConsistency}).
 */
@Service
@RequiredArgsConstructor
public class GoalProjectService {

    private final GoalService goalService;
    private final GoalProjectRepository goalProjectRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    /** Appends the project to the end of the goal projects; already assigned = nothing happens. */
    @Transactional
    public GoalDetailResponse add(String goalId, String projectId) {
        Goal goal = goalService.find(goalId);
        String id = projectId == null ? "" : projectId.trim();
        if (id.isEmpty() || !projectRepository.existsById(id)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        goalProjectRepository.linkIfAbsent(goal.getId(), id);
        return goalService.detailOf(goal);
    }

    /**
     * Detaches the project from the goal. If work packages or TODOs in the goal still hang on it, this is
     * a conflict without {@code cascade} (409, the message names the counts). With {@code cascade}, the
     * work packages of the project in this goal are deleted (with their milestone links)
     * and its TODOs are detached from the goal - the TODOs themselves stay.
     */
    @Transactional
    public void remove(String goalId, String projectId, boolean cascade) {
        Goal goal = goalService.find(goalId);
        long workPackages = workPackageRepository.countByGoalIdAndProjectId(goal.getId(), projectId);
        long todos = todoRepository.countByGoalIdAndProjectId(goal.getId(), projectId);
        if (!cascade && (workPackages > 0 || todos > 0)) {
            throw new IllegalStateException("Am Projekt haengen in diesem Ziel noch " + workPackages
                    + " Arbeitspaket(e) und " + todos
                    + " TODO(s) – mit cascade=true werden die Arbeitspakete geloescht und die TODOs vom Ziel geloest");
        }
        if (cascade) {
            Instant now = clock.instant();
            for (String workPackageId : workPackageRepository.findIdsByGoalAndProject(goal.getId(), projectId)) {
                todoRepository.detachFromWorkPackage(workPackageId, now); // auch TODOs anderer Projekte darin (Altdaten)
            }
            // die Meilenstein-Verknuepfungen der Pakete fallen per ON DELETE CASCADE mit
            workPackageRepository.deleteByGoalAndProject(goal.getId(), projectId);
            todoRepository.detachProjectFromGoal(goal.getId(), projectId, now);
        }
        goalProjectRepository.unlink(goal.getId(), projectId);
    }

    /**
     * New order: the named projects first in that sequence, unnamed goal projects behind them in
     * their previous sequence. A project that is not assigned to the goal is a 400.
     */
    @Transactional
    public GoalDetailResponse reorder(String goalId, List<String> projectIds) {
        Goal goal = goalService.find(goalId);
        List<GoalProject> links = goalProjectRepository.findByGoalIdOrderByPositionAscProjectIdAsc(goal.getId());
        Map<String, GoalProject> byProject = links.stream()
                .collect(Collectors.toMap(GoalProject::getProjectId, Function.identity()));
        List<GoalProject> ordered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(projectIds)) {
            GoalProject link = byProject.get(id);
            if (link == null) {
                throw new IllegalArgumentException("Projekt " + id + " ist dem Ziel nicht zugeordnet");
            }
            ordered.add(link);
        }
        links.stream().filter(link -> !ordered.contains(link)).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
        goalProjectRepository.saveAll(ordered);
        return goalService.detailOf(goal);
    }
}
