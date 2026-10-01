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
 * Haelt verwaltete TODOs im Takt ihrer Findings: ein managed TODO ist genau dann {@code DONE},
 * wenn jedes damit verknuepfte Finding terminal ist (RESOLVED / ACCEPTED_RISK / DISMISSED), sonst
 * {@code OPEN}. Manuell verknuepfte TODOs bleiben unberuehrt.
 *
 * <p>Aus {@code AuditCenterService} herausgeloest, damit der Dispositionswechsel im Audit Center
 * und der Merge in {@link AuditFingerprintMigrationJob} dieselbe Regel benutzen statt zweier
 * auseinanderlaufender Kopien (Audit-TODO 53119eb8). Reine DB-Arbeit, laeuft in der Transaktion
 * des Aufrufers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagedTodoReconciler {

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;

    /** Gleicht die verwalteten TODOs eines Findings ab. */
    public int reconcileFinding(String findingId) {
        return reconcile(findingTodoRepository.findByFindingId(findingId));
    }

    /**
     * @param links Verknuepfungen, deren verwaltete TODOs neu bewertet werden
     * @return Anzahl der TODOs, deren Status sich dadurch geaendert hat
     */
    public int reconcile(List<AuditFindingTodo> links) {
        List<String> todoIds = links.stream().filter(AuditFindingTodo::isManaged)
                .map(AuditFindingTodo::getTodoId).distinct().toList();
        if (todoIds.isEmpty()) {
            return 0;
        }
        // Je Stufe eine Abfrage statt drei je Verknuepfung (TODO 8c791a45, wie ManagedTodoWriter):
        // TODOs, ihre Verknuepfungen und deren Findings werden gebuendelt geladen.
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
        // Geaenderte TODOs werden gesammelt und mit einem saveAll geschrieben (e41531e0).
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
     * Sind alle Findings terminal, wird geschlossen. Zurueck ins Offene geht nur, was der Abgleich
     * selbst geschlossen hat: ein {@code IN_PROGRESS}- oder {@code ARCHIVED}-TODO ist eine
     * menschliche Entscheidung und wird nicht ueberschrieben.
     *
     * @return Zielstatus oder {@code null}, wenn nichts zu tun ist
     */
    private static Todo.Status nextStatus(Todo todo, boolean allTerminal) {
        if (allTerminal) {
            return Todo.Status.DONE;
        }
        return todo.getStatus() == Todo.Status.DONE ? Todo.Status.OPEN : null;
    }

    /** Alle Verknuepfungen des TODOs, ob verwaltet oder nicht; ein geloeschtes Finding zaehlt nicht. */
    private static boolean allTerminal(List<AuditFindingTodo> todoLinks, Map<String, AuditFinding> findings) {
        return todoLinks.stream()
                .map(link -> findings.get(link.getFindingId()))
                .filter(Objects::nonNull)
                .allMatch(finding -> finding.getStatus().isTerminal());
    }

    /** Ist das Finding terminal? Kurzform fuer Aufrufer, die nur den Status in der Hand haben. */
    public static boolean isTerminal(AuditFinding finding) {
        return finding.getStatus() != null && finding.getStatus().isTerminal();
    }
}
