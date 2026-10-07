package com.automation.service.audit;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuditTodoCompletionListenerTest {

    private final AuditCenterService auditCenterService = mock(AuditCenterService.class);
    private final AuditTodoCompletionListener listener = new AuditTodoCompletionListener(auditCenterService);

    @Test
    void completedTodo_marksItsFindingsAwaitingVerification() {
        Todo todo = new Todo();
        todo.setId("todo-1");

        listener.onTodoCompleted(new Todo.Completed(todo));

        verify(auditCenterService).markAwaitingVerification("todo-1");
    }

    @Test
    void completedTodoWithoutId_isIgnored() {
        listener.onTodoCompleted(new Todo.Completed(new Todo()));

        verifyNoInteractions(auditCenterService);
    }

    @Test
    void bulkCompletion_passesAllIdsAtOnce() {
        List<String> ids = List.of("a", "b", "c");

        listener.onTodosCompletedInBulk(new Todo.CompletedInBulk(ids));

        verify(auditCenterService).markAwaitingVerification(ids);
    }

    @Test
    void bothHandlers_areSynchronousEventListeners() throws NoSuchMethodException {
        // synchronous in the saver's transaction on purpose: no @TransactionalEventListener, no @Async
        assertThat(AuditTodoCompletionListener.class.getMethod("onTodoCompleted", Todo.Completed.class)
                .getAnnotations()).hasSize(1).hasOnlyElementsOfType(EventListener.class);
        assertThat(AuditTodoCompletionListener.class.getMethod("onTodosCompletedInBulk", Todo.CompletedInBulk.class)
                .getAnnotations()).hasSize(1).hasOnlyElementsOfType(EventListener.class);
    }
}
