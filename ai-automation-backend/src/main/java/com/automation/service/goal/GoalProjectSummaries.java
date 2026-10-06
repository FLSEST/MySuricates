package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.Todo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Counters and references per project of a goal, purely from already loaded rows (TODO dc0566f8,
 * c60bf914): {@code byProject} from the TODOs, {@code projects} from the explicitly assigned
 * projects. Extracted from {@link GoalService} so that it stays with its own tasks.
 */
final class GoalProjectSummaries {

    private static final Set<Todo.Status> ACTIVE = Set.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    private GoalProjectSummaries() {
    }

    /** The goal projects with counters from the non-archived TODOs of the goal in this project. */
    static List<GoalDetailResponse.ProjectSummary> projects(List<GoalProjectRow> projects, List<GoalTodoRow> rows) {
        Map<String, long[]> counts = new HashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.projectId() == null || row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] c = counts.computeIfAbsent(row.projectId(), id -> new long[4]);
            c[0]++;
            if (row.status() == Todo.Status.DONE) c[1]++;
            if (ACTIVE.contains(row.status()) && row.openQuestion() != null) c[2]++;
            if (row.decisionState() == Todo.DecisionState.DECIDED) c[3]++;
        }
        return projects.stream().map(p -> {
            long[] c = counts.getOrDefault(p.projectId(), new long[4]);
            return new GoalDetailResponse.ProjectSummary(p.projectId(), p.projectName(), p.archived(), p.position(),
                    c[0], c[1], c[2], c[3]);
        }).toList();
    }

    static List<GoalResponse.ProjectRef> refs(List<GoalProjectRow> projects) {
        return projects.stream().map(p -> new GoalResponse.ProjectRef(p.projectId(), p.projectName())).toList();
    }

    /** Ziel-ID → Projektverweise in Reihenfolge, fuer die Zielliste. */
    static Map<String, List<GoalResponse.ProjectRef>> refsByGoal(List<GoalProjectRow> rows) {
        return rows.stream().collect(Collectors.groupingBy(GoalProjectRow::goalId, LinkedHashMap::new,
                Collectors.mapping(p -> new GoalResponse.ProjectRef(p.projectId(), p.projectName()), Collectors.toList())));
    }

    /** Progress per project from the TODOs (without ARCHIVED); TODOs without a project under {@code null}. */
    static List<GoalDetailResponse.ProjectProgress> byProject(List<GoalTodoRow> rows, Map<String, String> names) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] t = totals.computeIfAbsent(Objects.toString(row.projectId(), ""), k -> new long[2]);
            t[0]++;
            if (row.status() == Todo.Status.DONE) t[1]++;
        }
        return totals.entrySet().stream()
                .map(e -> {
                    String projectId = e.getKey().isEmpty() ? null : e.getKey();
                    return new GoalDetailResponse.ProjectProgress(projectId, names.get(projectId), e.getValue()[0], e.getValue()[1]);
                })
                .toList();
    }
}
