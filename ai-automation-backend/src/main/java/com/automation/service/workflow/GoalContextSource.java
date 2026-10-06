package com.automation.service.workflow;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Supplies the context variables of a goal ({@code goal_status}, {@code goal_guard_*}) when a run
 * carries {@code goal_id}. Implemented in {@code service.goal}: this way {@code service.workflow} needs no
 * edge to {@code service.goal}, which would otherwise pull goal, todo, trigger, scheduler, chain, audit and mapper
 * back into the big cycle component (TODO d2844a58).
 */
public interface GoalContextSource {

    /** Free slots for items of the tile: {@code goal_guard_item_1} … {@code _5}. */
    int ITEM_SLOTS = 5;

    /** All variables that get set - on a gap, each with the gap format. */
    List<String> VARIABLES = Stream.concat(
            Stream.of("goal_status", "goal_guard_status", "goal_guard_title", "goal_guard_summary",
                    "goal_guard_progress", "goal_guard_overdue", "goal_guard_due_soon", "goal_guard_items"),
            IntStream.rangeClosed(1, ITEM_SLOTS).mapToObj(i -> "goal_guard_item_" + i)).toList();

    /** Name → value for the goal; throws if the goal does not exist. */
    Map<String, String> variables(String goalId, String locale);
}
