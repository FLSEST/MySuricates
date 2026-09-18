package com.automation.service.todo;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the list order table. The CASE rank in
 * {@link TodoSpecifications#orderByPriorityThenNewest()} ranks anything it does not know last —
 * a priority added to the enum without a place here would silently sink to the bottom of the
 * todo list.
 */
class TodoSpecificationsTest {

    @Test
    void priorityOrder_ranksEveryPriority_mostUrgentFirst() {
        assertThat(TodoSpecifications.PRIORITY_ORDER)
                .containsExactlyInAnyOrder(Todo.Priority.values())
                .startsWith(Todo.Priority.CRITICAL)
                .endsWith(Todo.Priority.LOW);
    }
}
