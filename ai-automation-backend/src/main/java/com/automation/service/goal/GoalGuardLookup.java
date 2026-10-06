package com.automation.service.goal;

import com.automation.dto.projection.GuardScheduleRow;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.entity.Goal;
import com.automation.repository.ScheduledWorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * State of the goal guards for a goal list in at most one query (TODO 259f4f66):
 * no guard workflow means none, otherwise one for all. A workflow with several schedules shows
 * the active one, otherwise the newest.
 */
@Component
@RequiredArgsConstructor
public class GoalGuardLookup {

    private static final Comparator<GuardScheduleRow> PREFERRED = Comparator
            .comparing(GuardScheduleRow::enabled).reversed()
            .thenComparing(GuardScheduleRow::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));

    private final ScheduledWorkflowRepository scheduleRepository;

    /** Goal ID → guard; goals without a guard get {@link GoalGuardResponse#NONE}. */
    public Map<String, GoalGuardResponse> byGoal(Collection<Goal> goals) {
        List<String> workflowIds = goals.stream().map(Goal::getGuardWorkflowId).filter(Objects::nonNull).distinct().toList();
        Map<String, GuardScheduleRow> schedules = workflowIds.isEmpty() ? Map.of()
                : scheduleRepository.findGuardRows(workflowIds).stream()
                        .sorted(PREFERRED)
                        .collect(Collectors.toMap(GuardScheduleRow::workflowId, Function.identity(), (first, other) -> first));
        return goals.stream().collect(Collectors.toMap(Goal::getId, goal -> of(goal, schedules)));
    }

    public GoalGuardResponse of(Goal goal) {
        return byGoal(List.of(goal)).get(goal.getId());
    }

    private static GoalGuardResponse of(Goal goal, Map<String, GuardScheduleRow> schedules) {
        if (goal.getGuardWorkflowId() == null) {
            return GoalGuardResponse.NONE;
        }
        GuardScheduleRow schedule = schedules.get(goal.getGuardWorkflowId());
        if (schedule == null) {
            return new GoalGuardResponse(false, goal.getGuardWorkflowId(), null, null);
        }
        return new GoalGuardResponse(schedule.enabled(), goal.getGuardWorkflowId(), schedule.scheduleId(),
                schedule.enabled() ? schedule.nextRunAt() : null);
    }
}
