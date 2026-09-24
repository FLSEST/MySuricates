package com.automation.service.todo;

import com.automation.entity.Todo;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /** {@code goalId=none} waehlt TODOs ohne Ziel – die Kandidaten der Relationskarte (TODO c6ea8f5c). */
    @Test
    @SuppressWarnings("unchecked")
    void inGoal_none_selectsTodosWithoutGoal_anIdSelectsThatGoal_blankSelectsAll() {
        Root<Todo> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> goalId = mock(Path.class);
        Predicate isNull = mock(Predicate.class);
        Predicate equal = mock(Predicate.class);
        when(root.get("goalId")).thenReturn(goalId);
        when(cb.isNull(goalId)).thenReturn(isNull);
        when(cb.equal(goalId, "g1")).thenReturn(equal);

        assertThat(TodoSpecifications.inGoal("none").toPredicate(root, query, cb)).isSameAs(isNull);
        assertThat(TodoSpecifications.inGoal("g1").toPredicate(root, query, cb)).isSameAs(equal);
        assertThat(TodoSpecifications.inGoal(" ").toPredicate(root, query, cb)).isNull();
        verify(cb, never()).equal(goalId, "none");
    }
}
