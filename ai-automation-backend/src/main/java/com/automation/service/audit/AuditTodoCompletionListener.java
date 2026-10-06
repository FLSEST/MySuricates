package com.automation.service.audit;

import com.automation.entity.Todo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
  * A TODO became {@code DONE}: its open findings wait for the next audit run
  * ({@link AuditFindingTodoLinker#markAwaitingVerification}). The TODO itself reports the event on
  * save/saveAll ({@link Todo.Completed}) so that no write path forgets it — REST, TodoTrigger auto-close,
  * linked workflow, bulk close (follow-up finding to ab1ed481).
 *
  * <p>Synchronous in the saver's transaction, not AFTER_COMMIT: it is pure DB work (no
  * slow call), and if the status change rolls back, the finding should not be switched either. Like the
  * earlier direct call from {@code TodoService}, this runs independently of the module switch: without the audit module
  * there are no links, and findings from the time before stay consistent.
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

    /** Bulk UPDATE to DONE: it loads no entities, therefore the service reports the IDs collectively. */
    @EventListener
    public void onTodosCompletedInBulk(Todo.CompletedInBulk event) {
        auditCenterService.markAwaitingVerification(event.todoIds());
    }
}
