package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps managed TODOs in step with their findings: a managed TODO is {@code DONE} exactly when
 * every finding linked to it is terminal (RESOLVED / ACCEPTED_RISK / DISMISSED), otherwise
 * {@code OPEN}. Manually linked TODOs are left untouched.
 *
 * <p>Extracted from {@code AuditCenterService} so that the disposition change in the Audit Center
 * and the merge in {@link AuditFingerprintMigrationJob} use the same rule instead of two
 * diverging copies (audit TODO 53119eb8). Pure DB work, runs in the caller's transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagedTodoReconciler {

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;

    /** Reconciles the managed TODOs of one finding. */
    public int reconcileFinding(String findingId) {
        return reconcile(findingTodoRepository.findByFindingId(findingId));
    }

    /**
     * @param links links whose managed TODOs are re-evaluated
     * @return number of TODOs whose status changed as a result
     */
    public int reconcile(List<AuditFindingTodo> links) {
        List<String> todoIds = links.stream().filter(AuditFindingTodo::isManaged)
                .map(AuditFindingTodo::getTodoId).distinct().toList();
        if (todoIds.isEmpty()) {
            return 0;
        }
        // One query per level instead of three per link (TODO 8c791a45, like ManagedTodoWriter):
        // TODOs, their links and their findings are loaded in bulk.
        Map<String, Todo> todos = todoRepository.findAllById(todoIds).stream()
                .collect(Collectors.toMap(Todo::getId, Function.identity()));
        if (todos.isEmpty()) {
            return 0;
        }
        Map<String, List<AuditFindingTodo>> linksByTodo = findingTodoRepository.findByTodoIdIn(todos.keySet())
                .stream().collect(Collectors.groupingBy(AuditFindingTodo::getTodoId));
        List<String> findingIds = linksByTodo.values().stream().flatMap(List::stream)
                .map(AuditFindingTodo::getFindingId).distinct().toList();
        Map<String, AuditFinding> findings = findingIds.isEmpty() ? Map.of()
                : findingRepository.findAllById(findingIds).stream()
                        .collect(Collectors.toMap(AuditFinding::getId, Function.identity()));
        // Changed TODOs are collected and written with one saveAll (e41531e0).
        List<Todo> changed = new ArrayList<>();
        for (String todoId : todoIds) {
            Todo todo = todos.get(todoId);
            if (todo == null) {
                continue;
            }
            Todo.Status target = nextStatus(todo, allTerminal(linksByTodo.getOrDefault(todoId, List.of()), findings));
            if (target == null || todo.getStatus() == target) {
                continue;
            }
            log.info("Managed audit TODO {} ({}) moves {} → {}", todo.getId(), todo.getTitle(),
                    todo.getStatus(), target);
            todo.setStatus(target);
            changed.add(todo);
        }
        if (!changed.isEmpty()) {
            todoRepository.saveAll(changed);
        }
        return changed.size();
    }

    /**
     * If all findings are terminal, the TODO is closed. Only what the reconciliation itself closed
     * goes back to open: an {@code IN_PROGRESS} or {@code ARCHIVED} TODO is a human decision and
     * is not overwritten.
     *
     * @return target status, or {@code null} if there is nothing to do
     */
    private static Todo.Status nextStatus(Todo todo, boolean allTerminal) {
        if (allTerminal) {
            return Todo.Status.DONE;
        }
        return todo.getStatus() == Todo.Status.DONE ? Todo.Status.OPEN : null;
    }

    /** All links of the TODO, managed or not; a deleted finding does not count. */
    private static boolean allTerminal(List<AuditFindingTodo> todoLinks, Map<String, AuditFinding> findings) {
        return todoLinks.stream()
                .map(link -> findings.get(link.getFindingId()))
                .filter(Objects::nonNull)
                .allMatch(finding -> finding.getStatus().isTerminal());
    }

    /** Is the finding terminal? Short form for callers that only hold the status. */
    public static boolean isTerminal(AuditFinding finding) {
        return finding.getStatus() != null && finding.getStatus().isTerminal();
    }
}
