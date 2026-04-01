package com.automation.service.trigger;

import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.todo.GateStatusResolver;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TodoFeedbackListener {

    private final TodoRepository todoRepository;
    private final TodoTriggerRepository todoTriggerRepository;
    private final GateStatusResolver gateStatusResolver;
    private final ActivityLogService activityLogService;

    @Async
    @EventListener
    @Transactional
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        // Only process runs that were triggered by a TodoTrigger
        Object todoTriggerIdObj = event.getRunMetadata().get("todoTriggerId");
        Object todoIdObj = event.getRunMetadata().get("todoId");
        if (todoTriggerIdObj == null || todoIdObj == null) {
            return;
        }

        String todoTriggerId = todoTriggerIdObj.toString();
        String todoId = todoIdObj.toString();

        TodoTrigger trigger = todoTriggerRepository.findById(todoTriggerId).orElse(null);
        Todo todo = todoRepository.findById(todoId).orElse(null);
        if (trigger == null || todo == null) {
            log.debug("TodoFeedbackListener: trigger or todo not found (trigger={}, todo={})", todoTriggerId, todoId);
            return;
        }

        // Already done — don't overwrite
        if (todo.getStatus() == Todo.Status.DONE) {
            return;
        }

        GateResult gate = gateStatusResolver.resolve(event);
        if (gate == null) {
            log.debug("TodoFeedbackListener: no gate result for run {}", event.getRunId());
            return;
        }

        switch (gate.status()) {
            case GREEN -> {
                if (trigger.isAutoClose()) {
                    todo.setStatus(Todo.Status.DONE);
                    todoRepository.save(todo);
                    activityLogService.info(LogCategory.EXECUTION,
                            "TodoTrigger auto-closed TODO: " + todo.getTitle(),
                            "TodoFeedbackListener", event.getRunId(),
                            trigger.getTargetWorkflow().getId());
                    log.info("TodoFeedbackListener: auto-closed todo '{}' after successful fix (run {})",
                            todo.getTitle(), event.getRunId());
                }
            }
            case RED, YELLOW -> {
                if (todo.getFixAttempts() >= trigger.getMaxAttempts()) {
                    activityLogService.warn(LogCategory.EXECUTION,
                            "TodoTrigger exhausted fix attempts for TODO: " + todo.getTitle()
                                    + " (" + todo.getFixAttempts() + "/" + trigger.getMaxAttempts() + ")",
                            "TodoFeedbackListener", event.getRunId(),
                            trigger.getTargetWorkflow().getId());
                    log.warn("TodoFeedbackListener: exhausted fix attempts for todo '{}' ({}/{})",
                            todo.getTitle(), todo.getFixAttempts(), trigger.getMaxAttempts());
                } else {
                    log.info("TodoFeedbackListener: fix attempt {}/{} failed for todo '{}' (run {})",
                            todo.getFixAttempts(), trigger.getMaxAttempts(), todo.getTitle(), event.getRunId());
                }
            }
        }
    }
}
