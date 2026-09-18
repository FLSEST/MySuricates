package com.automation.service.todo;

import com.automation.entity.Todo;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

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

    public static Specification<Todo> buildSpec(String projectId, List<String> status, String priority, String source, String tag, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (projectId != null && !projectId.isBlank()) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (status != null && !status.isEmpty()) {
                List<Todo.Status> enums = status.stream()
                        .map(Todo.Status::valueOf)
                        .toList();
                if (enums.size() == 1) {
                    predicates.add(cb.equal(root.get("status"), enums.get(0)));
                } else {
                    predicates.add(root.get("status").in(enums));
                }
            } else {
                // ARCHIVED is "out of sight": hidden from the default list unless explicitly requested.
                predicates.add(cb.notEqual(root.get("status"), Todo.Status.ARCHIVED));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(cb.equal(root.get("priority"), Todo.Priority.valueOf(priority)));
            }
            if (source != null && !source.isBlank()) {
                predicates.add(cb.equal(root.get("source"), Todo.Source.valueOf(source)));
            }
            if (tag != null && !tag.isBlank()) {
                predicates.add(cb.like(root.get("tags"), "%" + escapeLikePattern(tag) + "%", '\\'));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLikePattern(q.toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
