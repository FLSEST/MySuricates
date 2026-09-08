package com.automation.service.todo;

import com.automation.entity.Todo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA {@link Specification} for the todo list filters (project, status, priority, source, tag,
 * free-text). ARCHIVED todos are hidden unless a status filter names them explicitly.
 *
 * <p>Extracted from {@code TodoService} (Audit-TODO 671add26). Pure query construction, no
 * repository access.
 */
public final class TodoSpecifications {

    private TodoSpecifications() {
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
