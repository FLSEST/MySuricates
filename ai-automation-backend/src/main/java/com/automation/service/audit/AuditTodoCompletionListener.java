package com.automation.service.audit;

import com.automation.entity.Todo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ein TODO wurde {@code DONE}: seine offenen Befunde warten auf den naechsten Audit-Lauf
 * ({@link AuditFindingTodoLinker#markAwaitingVerification}). Das Ereignis meldet das TODO selbst beim
 * save/saveAll ({@link Todo.Completed}), damit kein Schreibpfad es vergisst — REST, TodoTrigger-Auto-Close,
 * verknuepfter Workflow, Sammelschliessen (Folgebefund zu ab1ed481).
 *
 * <p>Synchron in der Transaktion des Speichernden, nicht AFTER_COMMIT: Es ist reine DB-Arbeit (kein
 * langsamer Aufruf), und rollt der Statuswechsel zurueck, soll auch der Befund nicht umgestellt sein. Wie der
 * fruehere direkte Aufruf aus {@code TodoService} laeuft das unabhaengig vom Modulschalter: Ohne Audit-Modul
 * gibt es keine Verknuepfungen, und Befunde aus der Zeit davor bleiben stimmig.
 */
@Component
@RequiredArgsConstructor
class AuditTodoCompletionListener {

    private final AuditCenterService auditCenterService;

    @EventListener
    public void onTodoCompleted(Todo.Completed event) {
        String todoId = event.todo().getId();
        if (todoId != null) {
            auditCenterService.markAwaitingVerification(todoId);
        }
    }
}
