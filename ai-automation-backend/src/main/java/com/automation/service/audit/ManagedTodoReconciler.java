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

import java.util.List;
import java.util.Objects;

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
        int changed = 0;
        for (AuditFindingTodo link : links) {
            if (!link.isManaged()) {
                continue;
            }
            Todo todo = todoRepository.findById(link.getTodoId()).orElse(null);
            if (todo == null) {
                continue;
            }
            Todo.Status target = allTerminal(todo.getId()) ? Todo.Status.DONE : Todo.Status.OPEN;
            if (todo.getStatus() == target) {
                continue;
            }
            log.info("Managed audit TODO {} ({}) moves {} → {}", todo.getId(), todo.getTitle(),
                    todo.getStatus(), target);
            todo.setStatus(target);
            todoRepository.save(todo);
            changed++;
        }
        return changed;
    }

    private boolean allTerminal(String todoId) {
        return findingTodoRepository.findByTodoId(todoId).stream()
                .map(link -> findingRepository.findById(link.getFindingId()).orElse(null))
                .filter(Objects::nonNull)
                .allMatch(finding -> finding.getStatus().isTerminal());
    }

    /** Ist das Finding terminal? Kurzform fuer Aufrufer, die nur den Status in der Hand haben. */
    public static boolean isTerminal(AuditFinding finding) {
        return finding.getStatus() != null && finding.getStatus().isTerminal();
    }
}
