package com.automation.service.todo;

import com.automation.entity.GoalProject;
import com.automation.entity.Todo;
import com.automation.util.EnumParameter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JPA {@link Specification} for the todo list filters (project, status, priority, source, tag,
 * free-text) and its order. ARCHIVED todos are hidden unless a status filter names them
 * explicitly.
 *
 * <p>Extracted from {@code TodoService} (Audit-TODO 671add26). Pure query construction, no
 * repository access.
 */
public final class TodoSpecifications {

    /**
     * Business order of the todo list: most urgent first. {@code Todo.priority} is persisted as
     * STRING, so ordering the column itself reads alphabetically (CRITICAL, HIGH, LOW, MEDIUM)
     * and buries MEDIUM behind LOW — hence the explicit rank (aa434aee). The enum's own ordinal
     * is no help either: it is declared lowest-first for historical reasons.
     */
    static final List<Todo.Priority> PRIORITY_ORDER = List.of(
            Todo.Priority.CRITICAL, Todo.Priority.HIGH, Todo.Priority.MEDIUM, Todo.Priority.LOW);

    private TodoSpecifications() {
    }

    /**
     * The todo list as the UI shows it: the filters of
     * {@link #buildSpec(String, List, String, String, String, String)} plus the list order
     * (priority first, newest first within a priority).
     *
     * <p>The order lives in the specification because it needs a CASE expression, which a
     * {@code Sort} cannot express. Callers must therefore pass an <b>unsorted</b> {@code Pageable}:
     * Spring Data applies a sorted one <i>after</i> the specification and would overwrite the
     * order-by.
     */
    public static Specification<Todo> listSpec(
            String projectId, List<String> status, String priority, String source, String tag, String q) {
        return buildSpec(projectId, status, priority, source, tag, q).and(orderByPriorityThenNewest());
    }

    /** Order-only specification (no predicate): priority rank ascending, then newest first. */
    static Specification<Todo> orderByPriorityThenNewest() {
        return (root, query, cb) -> {
            // Count queries have no order-by (Spring Data clears it anyway) and no createdAt need.
            if (query != null && !Long.class.equals(query.getResultType())) {
                Expression<Todo.Priority> priority = root.get("priority");
                CriteriaBuilder.SimpleCase<Todo.Priority, Integer> rank = cb.selectCase(priority);
                for (int i = 0; i < PRIORITY_ORDER.size(); i++) {
                    rank = rank.when(PRIORITY_ORDER.get(i), i);
                }
                query.orderBy(
                        cb.asc(rank.otherwise(PRIORITY_ORDER.size())),
                        cb.desc(root.get("createdAt")));
            }
            return null;
        };
    }

    /**
     * The filters of the todo list. The enum values are parsed here, before the query: inside the
     * specification Spring Data wrapped an unknown value's exception, and the API answered 500
     * instead of 400 (TODO 4e7a7401).
     *
     * @throws IllegalArgumentException for an unknown status, priority or source, naming the allowed values
     */
    public static Specification<Todo> buildSpec(String projectId, List<String> status, String priority, String source, String tag, String q) {
        List<Todo.Status> statuses = status == null ? List.of() : status.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> EnumParameter.parse(Todo.Status.class, "status", value))
                .toList();
        Todo.Priority priorityFilter = EnumParameter.parseOptional(Todo.Priority.class, "priority", priority);
        Todo.Source sourceFilter = EnumParameter.parseOptional(Todo.Source.class, "source", source);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (projectId != null && !projectId.isBlank()) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (statuses.size() == 1) {
                predicates.add(cb.equal(root.get("status"), statuses.get(0)));
            } else if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            } else {
                // ARCHIVED is "out of sight": hidden from the default list unless explicitly requested.
                predicates.add(cb.notEqual(root.get("status"), Todo.Status.ARCHIVED));
            }
            if (priorityFilter != null) {
                predicates.add(cb.equal(root.get("priority"), priorityFilter));
            }
            if (sourceFilter != null) {
                predicates.add(cb.equal(root.get("source"), sourceFilter));
            }
            if (tag != null && !tag.isBlank()) {
                predicates.add(cb.like(root.get("tags"), "%" + escapeLikePattern(tag) + "%", '\\'));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLikePattern(q.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Wert von {@code goalId}, der TODOs ohne Ziel waehlt – die Kandidaten der Relationskarte (TODO c6ea8f5c). */
    public static final String NO_GOAL = "none";

    /**
     * Nur TODOs dieses Ziels (TODO dc0566f8); {@value #NO_GOAL} waehlt die TODOs ohne Ziel, ohne ID
     * gibt es keine Einschraenkung.
     */
    public static Specification<Todo> inGoal(String goalId) {
        return (root, query, cb) -> {
            if (goalId == null || goalId.isBlank()) {
                return null;
            }
            return NO_GOAL.equals(goalId.trim()) ? cb.isNull(root.get("goalId")) : cb.equal(root.get("goalId"), goalId);
        };
    }

    /**
     * Nur TODOs aus Projekten, die dem Ziel zugeordnet sind (TODO c60bf914) – als Unterabfrage auf
     * {@code goal_projects}, ohne die Projekte vorher zu laden. Ein Ziel ohne Projekte waehlt nichts.
     */
    public static Specification<Todo> inProjectsOfGoal(String goalId) {
        return (root, query, cb) -> {
            Subquery<String> projects = query.subquery(String.class);
            Root<GoalProject> link = projects.from(GoalProject.class);
            projects.select(link.get("projectId")).where(cb.equal(link.get("goalId"), goalId));
            return root.get("projectId").in(projects);
        };
    }

    static String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
