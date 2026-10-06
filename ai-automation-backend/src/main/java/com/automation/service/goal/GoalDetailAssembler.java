package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.dto.response.GoalTodoItem;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalWorkPackage;
import com.automation.mapper.TodoMapper;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the goal view (TODO dc0566f8, 259f4f66, c60bf914): TODOs in working order,
 * progress per project, the goal projects, work packages and milestones with state. Extracted from
 * {@link GoalService} so that it stays at creating, changing and reading goals.
 *
 * <p>Three queries besides the packages: TODO rows, goal projects, milestones including links –
 * project names only for TODOs from projects not assigned to the goal (legacy data), in one
 * further one. Must run in a write transaction: if it recognizes a package or a milestone as completed
 * or reached for the first time, it sets the timestamp on the entity.
 */
@Component
@RequiredArgsConstructor
class GoalDetailAssembler {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final GoalGuardLookup guardLookup;
    private final Clock clock;

    GoalDetailResponse detailOf(Goal goal, List<GoalWorkPackage> workPackages) {
        List<GoalTodoRow> rows = todoRepository.findGoalRows(goal.getId()).stream().sorted(GoalService.WORK_ORDER).toList();
        List<GoalProjectRow> projects = goalProjectRepository.findRows(goal.getId());
        Map<String, String> projectNames = projectNames(rows, projects);
        Map<String, Integer> positions = new HashMap<>();
        projects.forEach(p -> positions.put(p.projectId(), p.position()));
        Instant now = clock.instant();
        List<GoalWorkPackageResponse> packages = WorkPackageProgress.evaluate(workPackages, rows, positions, now);
        List<GoalMilestoneResponse> milestones = MilestoneProgress.evaluate(milestoneRepository.findWithLinks(goal.getId()),
                GoalMilestoneService.completed(packages), now);
        List<GoalTodoItem> items = rows.stream().map(row -> toItem(row, projectNames.get(row.projectId()))).toList();
        GoalResponse response = GoalService.toResponse(goal, GoalService.progressOf(rows), guardLookup.of(goal),
                GoalProjectSummaries.refs(projects), MilestoneProgress.next(milestones));
        return new GoalDetailResponse(response, items, GoalProjectSummaries.byProject(rows, projectNames),
                GoalProjectSummaries.projects(projects, rows), packages, milestones);
    }

    /** Project ID → name; a HashMap because TODOs without a project look up with {@code null}. */
    private Map<String, String> projectNames(List<GoalTodoRow> rows, List<GoalProjectRow> projects) {
        Map<String, String> names = new HashMap<>();
        projects.forEach(p -> names.put(p.projectId(), p.projectName()));
        Set<String> ids = rows.stream().map(GoalTodoRow::projectId).filter(Objects::nonNull)
                .filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            // Only ID and name – findAllById cost one select containers per project (TODO 08224ed6)
            projectRepository.findIdAndNameByIdIn(ids).forEach(row -> names.put((String) row[0], (String) row[1]));
        }
        return names;
    }

    private static GoalTodoItem toItem(GoalTodoRow row, String projectName) {
        return new GoalTodoItem(row.id(), row.title(), row.status(), row.priority(), row.projectId(), projectName,
                TodoMapper.jsonToList(row.tags()), row.goalRank(), row.complexity(), row.suggestedEffort(),
                row.ratingNote(), row.ratedBy(), row.ratedAt(), row.openQuestion(), row.updatedAt(),
                row.dueDate(), row.workPackageId(), row.decision(), row.decidedAt(), row.decidedBy(), row.decisionState());
    }
}
