package com.automation.service.todo;

import com.automation.entity.GoalProject;
import com.automation.entity.Todo;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the specification building blocks against a mocked Criteria API; the query
 * semantics against real SQLite are locked in {@code TodoSpecificationsIntegrationTest}.
 *
 * <p>Guards the list order table. The CASE rank in
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

    /** {@code goalId=none} selects TODOs without a goal – the candidates of the relation map (TODO c6ea8f5c). */
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

    @Test
    void escapeLikePattern_escapesBackslashFirst_thenWildcards() {
        assertThat(TodoSpecifications.escapeLikePattern("plain")).isEqualTo("plain");
        assertThat(TodoSpecifications.escapeLikePattern("50%")).isEqualTo("50\\%");
        assertThat(TodoSpecifications.escapeLikePattern("x_y")).isEqualTo("x\\_y");
        assertThat(TodoSpecifications.escapeLikePattern("a\\b")).isEqualTo("a\\\\b");
        assertThat(TodoSpecifications.escapeLikePattern("\\%_")).isEqualTo("\\\\\\%\\_");
    }

    /** Mocked Criteria API around one query; every attribute path is its own mock. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Criteria {
        final Root<Todo> root = mock(Root.class);
        final CriteriaQuery<Object> query = mock(CriteriaQuery.class);
        final CriteriaBuilder cb = mock(CriteriaBuilder.class);
        final Path<Object> projectId = mock(Path.class);
        final Path<Object> status = mock(Path.class);
        final Path<Object> priority = mock(Path.class);
        final Path<Object> source = mock(Path.class);
        final Path<String> tags = mock(Path.class);
        final Path<String> title = mock(Path.class);
        final Path<String> description = mock(Path.class);
        final Path<Object> createdAt = mock(Path.class);
        final Predicate notArchived = mock(Predicate.class);
        final Predicate combined = mock(Predicate.class);

        Criteria() {
            when(root.get("projectId")).thenReturn(projectId);
            when(root.get("status")).thenReturn(status);
            when(root.get("priority")).thenReturn((Path) priority);
            when(root.get("source")).thenReturn(source);
            when(root.<String>get("tags")).thenReturn(tags);
            when(root.<String>get("title")).thenReturn(title);
            when(root.<String>get("description")).thenReturn(description);
            when(root.get("createdAt")).thenReturn(createdAt);
            when(cb.notEqual(status, Todo.Status.ARCHIVED)).thenReturn(notArchived);
            when(cb.and(any(Predicate[].class))).thenReturn(combined);
            doReturn(Todo.class).when(query).getResultType();
        }

        Predicate apply(Specification<Todo> spec) {
            return spec.toPredicate(root, query, cb);
        }
    }

    @Nested
    class BuildSpec {

        @Test
        void withoutFilters_onlyHidesArchived() {
            Criteria c = new Criteria();

            assertThat(c.apply(TodoSpecifications.buildSpec(null, null, null, null, null, null))).isSameAs(c.combined);

            verify(c.cb).and(new Predicate[]{c.notArchived});
        }

        @Test
        void blankFilters_areIgnored() {
            Criteria c = new Criteria();

            c.apply(TodoSpecifications.buildSpec(" ", List.of(" ", ""), "", " ", "", "  "));

            verify(c.cb).and(new Predicate[]{c.notArchived});
            verify(c.root, never()).get("projectId");
            verify(c.root, never()).get("tags");
        }

        @Test
        void projectAndSingleStatus_matchExactly_andNoLongerHideArchived() {
            Criteria c = new Criteria();
            Predicate inProject = mock(Predicate.class);
            Predicate archived = mock(Predicate.class);
            when(c.cb.equal(c.projectId, "p1")).thenReturn(inProject);
            when(c.cb.equal(c.status, Todo.Status.ARCHIVED)).thenReturn(archived);

            c.apply(TodoSpecifications.buildSpec("p1", List.of("archived"), null, null, null, null));

            verify(c.cb).and(new Predicate[]{inProject, archived});
            verify(c.cb, never()).notEqual(c.status, Todo.Status.ARCHIVED);
        }

        @Test
        void severalStatuses_becomeAnInClause() {
            Criteria c = new Criteria();
            Predicate in = mock(Predicate.class);
            when(c.status.in(List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS))).thenReturn(in);

            c.apply(TodoSpecifications.buildSpec(null, List.of("OPEN", " in_progress "), null, null, null, null));

            verify(c.cb).and(new Predicate[]{in});
        }

        @Test
        void priorityAndSource_filterByTheParsedEnum() {
            Criteria c = new Criteria();
            Predicate high = mock(Predicate.class);
            Predicate ai = mock(Predicate.class);
            when(c.cb.equal(c.priority, Todo.Priority.HIGH)).thenReturn(high);
            when(c.cb.equal(c.source, Todo.Source.AI)).thenReturn(ai);

            c.apply(TodoSpecifications.buildSpec(null, null, "high", "AI", null, null));

            verify(c.cb).and(new Predicate[]{c.notArchived, high, ai});
        }

        @Test
        void tag_isAnEscapedSubstringMatch() {
            Criteria c = new Criteria();
            Predicate tagged = mock(Predicate.class);
            when(c.cb.like(c.tags, "%audit:x\\_y%", '\\')).thenReturn(tagged);

            c.apply(TodoSpecifications.buildSpec(null, null, null, null, "audit:x_y", null));

            verify(c.cb).and(new Predicate[]{c.notArchived, tagged});
        }

        @Test
        @SuppressWarnings("unchecked")
        void freeText_isLowercasedEscapedAndSearchedInTitleOrDescription() {
            Criteria c = new Criteria();
            Expression<String> lowerTitle = mock(Expression.class);
            Expression<String> lowerDescription = mock(Expression.class);
            Predicate inTitle = mock(Predicate.class);
            Predicate inDescription = mock(Predicate.class);
            Predicate either = mock(Predicate.class);
            when(c.cb.lower(c.title)).thenReturn(lowerTitle);
            when(c.cb.lower(c.description)).thenReturn(lowerDescription);
            when(c.cb.like(lowerTitle, "%100\\% login%", '\\')).thenReturn(inTitle);
            when(c.cb.like(lowerDescription, "%100\\% login%", '\\')).thenReturn(inDescription);
            when(c.cb.or(inTitle, inDescription)).thenReturn(either);

            c.apply(TodoSpecifications.buildSpec(null, null, null, null, null, "100% LOGIN"));

            verify(c.cb).and(new Predicate[]{c.notArchived, either});
        }

        @Test
        void unknownEnumValues_areRejectedWhenTheSpecIsBuilt_notInsideTheQuery() {
            assertThatThrownBy(() -> TodoSpecifications.buildSpec(null, List.of("OPEN", "BOGUS"), null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("status").hasMessageContaining("BOGUS");
            assertThatThrownBy(() -> TodoSpecifications.buildSpec(null, null, "URGENT", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("priority");
            assertThatThrownBy(() -> TodoSpecifications.buildSpec(null, null, null, "ROBOT", null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        }
    }

    @Nested
    @SuppressWarnings({"unchecked", "rawtypes"})
    class ListOrder {

        private final CriteriaBuilder.SimpleCase<Todo.Priority, Integer> rank = mock(CriteriaBuilder.SimpleCase.class);
        private final Expression<Integer> rankOtherwise = mock(Expression.class);
        private final jakarta.persistence.criteria.Order byRank = mock(jakarta.persistence.criteria.Order.class);
        private final jakarta.persistence.criteria.Order newestFirst = mock(jakarta.persistence.criteria.Order.class);

        private void stubOrder(Criteria c) {
            when(c.cb.selectCase((Expression) c.priority)).thenReturn((CriteriaBuilder.SimpleCase) rank);
            when(rank.when(any(Todo.Priority.class), anyInt())).thenReturn(rank);
            when(rank.otherwise(TodoSpecifications.PRIORITY_ORDER.size())).thenReturn(rankOtherwise);
            when(c.cb.asc(rankOtherwise)).thenReturn(byRank);
            when(c.cb.desc(c.createdAt)).thenReturn(newestFirst);
        }

        @Test
        void ranksEveryPriorityInOrder_thenNewestFirst_andAddsNoPredicate() {
            Criteria c = new Criteria();
            stubOrder(c);

            assertThat(c.apply(TodoSpecifications.orderByPriorityThenNewest())).isNull();

            for (int i = 0; i < TodoSpecifications.PRIORITY_ORDER.size(); i++) {
                verify(rank).when(TodoSpecifications.PRIORITY_ORDER.get(i), i);
            }
            verify(c.query).orderBy(byRank, newestFirst);
        }

        @Test
        void leavesACountQueryUnordered() {
            Criteria c = new Criteria();
            doReturn(Long.class).when(c.query).getResultType();

            assertThat(c.apply(TodoSpecifications.orderByPriorityThenNewest())).isNull();

            verify(c.query, never()).orderBy(any(jakarta.persistence.criteria.Order[].class));
            verify(c.cb, never()).selectCase(any(Expression.class));
        }

        @Test
        void toleratesAMissingQuery() {
            Criteria c = new Criteria();

            assertThat(TodoSpecifications.orderByPriorityThenNewest().toPredicate(c.root, null, c.cb)).isNull();
            verifyNoInteractions(c.cb);
        }

        @Test
        void listSpec_combinesTheFiltersWithTheOrder() {
            Criteria c = new Criteria();
            stubOrder(c);

            Predicate predicate = c.apply(TodoSpecifications.listSpec(null, null, null, null, null, null));

            assertThat(predicate).isSameAs(c.combined);
            verify(c.cb).and(new Predicate[]{c.notArchived});
            verify(c.query).orderBy(byRank, newestFirst);
        }

        @Test
        void listSpec_countQuery_keepsTheFiltersWithoutOrder() {
            Criteria c = new Criteria();
            doReturn(Long.class).when(c.query).getResultType();

            assertThat(c.apply(TodoSpecifications.listSpec(null, null, null, null, null, null))).isSameAs(c.combined);
            verify(c.query, never()).orderBy(any(jakarta.persistence.criteria.Order[].class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void inProjectsOfGoal_isASubqueryOnTheGoalsProjects() {
        Criteria c = new Criteria();
        Subquery<String> projects = mock(Subquery.class);
        Root<GoalProject> link = mock(Root.class);
        Path<Object> linkProjectId = mock(Path.class);
        Path<Object> linkGoalId = mock(Path.class);
        Predicate ofGoal = mock(Predicate.class);
        Predicate inProjects = mock(Predicate.class);
        when(c.query.subquery(String.class)).thenReturn(projects);
        when(projects.select(any())).thenReturn(projects);
        when(projects.from(GoalProject.class)).thenReturn(link);
        when(link.get("projectId")).thenReturn(linkProjectId);
        when(link.get("goalId")).thenReturn(linkGoalId);
        when(c.cb.equal(linkGoalId, "g1")).thenReturn(ofGoal);
        when(c.projectId.in(projects)).thenReturn(inProjects);

        assertThat(c.apply(TodoSpecifications.inProjectsOfGoal("g1"))).isSameAs(inProjects);

        verify(projects).select((Expression) linkProjectId);
        verify(projects).where(ofGoal);
    }

    @Test
    void inGoal_trimsTheNoGoalMarker_andNamesItNone() {
        Criteria c = new Criteria();
        Path<Object> goalId = mock(Path.class);
        Predicate isNull = mock(Predicate.class);
        when(c.root.get("goalId")).thenReturn(goalId);
        when(c.cb.isNull(goalId)).thenReturn(isNull);

        assertThat(TodoSpecifications.NO_GOAL).isEqualTo("none");
        assertThat(c.apply(TodoSpecifications.inGoal(" none "))).isSameAs(isNull);
        assertThat(c.apply(TodoSpecifications.inGoal(null))).isNull();
    }
}
