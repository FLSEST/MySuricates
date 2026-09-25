package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.TodoRepository;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TodoResolutionListener {

    private final TodoRepository todoRepository;
    private final GateStatusResolver gateStatusResolver;

    @Async
    @EventListener
    @Transactional
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        Object linkedTodoIdObj = event.getRunMetadata().get("linkedTodoId");
        if (linkedTodoIdObj == null) {
            return;
        }

        Todo todo = todoRepository.findById(linkedTodoIdObj.toString()).orElse(null);
        if (todo == null) {
            log.debug("TodoResolutionListener: todo not found ({})", linkedTodoIdObj);
            return;
        }
        if (!event.getRunId().equals(todo.getLastFixRunId())) {
            log.debug("TodoResolutionListener: ignoring stale run {} for todo {}", event.getRunId(), todo.getId());
            return;
        }

        if (isSuccessful(event)) {
            todo.setStatus(Todo.Status.DONE);
            todo.setResolutionState(Todo.ResolutionState.SUCCEEDED);
            log.info("TodoResolutionListener: auto-closed todo '{}' after linked workflow success", todo.getTitle());
        } else {
            todo.setStatus(previousStatus(event));
            todo.setResolutionState(Todo.ResolutionState.FAILED);
            log.info("TodoResolutionListener: linked workflow failed for todo '{}', restoring status to {}",
                    todo.getTitle(), todo.getStatus());
        }
        todoRepository.save(todo);
    }

    private boolean isSuccessful(WorkflowRunCompletedEvent event) {
        if (event.getStatus() != RunStatus.COMPLETED) {
            return false;
        }
        var gate = gateStatusResolver.resolve(event);
        return gate == null || gate.status() == GateStatus.GREEN;
    }

    /** The status {@code TodoResolutionService} recorded before the fix run; OPEN when missing or unknown. */
    private Todo.Status previousStatus(WorkflowRunCompletedEvent event) {
        Object previous = event.getRunMetadata().get("linkedTodoPreviousStatus");
        if (previous instanceof String status) {
            try {
                Todo.Status parsed = EnumParameter.parseOptional(Todo.Status.class, "linkedTodoPreviousStatus", status);
                if (parsed != null) {
                    return parsed;
                }
            } catch (IllegalArgumentException e) {
                log.warn("TodoResolutionListener: {} — restoring OPEN", e.getMessage());
            }
        }
        return Todo.Status.OPEN;
    }
}
