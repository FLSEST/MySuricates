package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State of the work packages of a goal from its TODO rows (TODO 259f4f66, c60bf914) - without a
 * query of its own: the goal view has already loaded the rows anyway.
 *
 * <p>If {@link #evaluate} recognises a package as completed for the first time, it sets
 * {@code completedAt} on the (managed) entity; the surrounding write transaction stores it.
 *
 * <p>Order: by the position of the project in the goal (packages without an assigned project last),
 * then by position in the project; on a tie by end (without period last) and creation.
 */
final class WorkPackageProgress {

    private static final Set<Todo.Status> ACTIVE = Set.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    private WorkPackageProgress() {
    }

    static List<GoalWorkPackageResponse> evaluate(List<GoalWorkPackage> workPackages, List<GoalTodoRow> rows, Instant now) {
        return evaluate(workPackages, rows, Map.of(), now);
    }

    /** @param projectPositions Projekt-ID → Position im Ziel; fehlende Projekte stehen hinten */
    static List<GoalWorkPackageResponse> evaluate(List<GoalWorkPackage> workPackages, List<GoalTodoRow> rows,
                                                  Map<String, Integer> projectPositions, Instant now) {
        if (workPackages == null || workPackages.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> counts = new HashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.workPackageId() == null || row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] c = counts.computeIfAbsent(row.workPackageId(), id -> new long[4]);
            c[0]++;
            if (row.status() == Todo.Status.DONE) c[1]++;
            if (ACTIVE.contains(row.status()) && row.openQuestion() != null) c[2]++;
            if (row.decisionState() == Todo.DecisionState.DECIDED) c[3]++;
        }
        return workPackages.stream()
                .sorted(order(projectPositions))
                .map(p -> {
                    long[] c = counts.getOrDefault(p.getId(), new long[4]);
                    return toResponse(p, c[0], c[1], c[2], c[3], now);
                })
                .toList();
    }

    /**
     * State of a package; on first recognition as "completed" {@code completedAt} is set and
     * stays afterwards, even if the package is open again ({@code reopened}). Without a period it is only
     * OPEN or REACHED.
     */
    static GoalWorkPackageResponse toResponse(GoalWorkPackage p, long total, long done, long openQuestions, Instant now) {
        return toResponse(p, total, done, openQuestions, 0, now);
    }

    /** @param decided non-archived TODOs with a decision and without an open question (TODO 68a5e24e) */
    static GoalWorkPackageResponse toResponse(GoalWorkPackage p, long total, long done, long openQuestions, long decided,
                                              Instant now) {
        GoalDueState state = GoalWorkPackage.stateOf(p.getEndDate(), total, done, now);
        if (state == GoalDueState.REACHED && p.getCompletedAt() == null) {
            p.setCompletedAt(now);
        }
        boolean reopened = p.getCompletedAt() != null && state != GoalDueState.REACHED;
        return new GoalWorkPackageResponse(p.getId(), p.getGoalId(), p.getProjectId(), p.getTitle(), p.getStartDate(),
                p.getEndDate(), p.getPosition(), state, total, done, openQuestions, p.getCompletedAt(), reopened,
                p.getCreatedAt(), p.getUpdatedAt(), decided);
    }

    static Comparator<GoalWorkPackage> order(Map<String, Integer> projectPositions) {
        return Comparator
                .comparing((GoalWorkPackage p) -> p.getProjectId() == null ? null : projectPositions.get(p.getProjectId()),
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(GoalWorkPackage::getProjectId, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparingInt(GoalWorkPackage::getPosition)
                .thenComparing(GoalWorkPackage::getEndDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalWorkPackage::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalWorkPackage::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));
    }
}
