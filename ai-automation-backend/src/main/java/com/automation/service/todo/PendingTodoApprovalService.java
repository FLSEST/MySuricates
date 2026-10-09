package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-todo "approval" mode: proposed todos are parked in the run metadata
 * ({@code pending_todos}) and only materialised once the user approves them via
 * {@code POST /executions/{id}/approve-todos}.
 *
 * <p>Extracted from {@code AutoTodoService} (Audit-TODO 9b4845ab). {@link #approvePendingTodos}
 * must stay {@code @Transactional}: it navigates {@code run.workflow} lazily (OSIV is off) and
 * the {@link TodoCreatedEvent} it publishes is consumed AFTER_COMMIT by the TodoTrigger — without
 * a transaction the event would be dropped silently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingTodoApprovalService {

    private final TodoRepository todoRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AutoTodoTextBuilder textBuilder;

    /** Parks one proposed todo per target project in the run's metadata. */
    void storePendingTodos(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        List<Map<String, Object>> pendingTodos = new ArrayList<>();
        for (String projectId : targetProjectIds) {
            pendingTodos.add(buildPendingTodo(projectId, event, gate));
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            workflowRunRepository.findById(event.getRunId()).ifPresent(run -> {
                run.getMetadata().put("pending_todos", pendingTodos);
                workflowRunRepository.save(run);
                log.info("Auto-todo (approval): stored {} pending todo(s) for run {}", pendingTodos.size(), event.getRunId());
            });
            return null;
        });
    }

    @Transactional
    public void approvePendingTodos(String runId, boolean approved) {
        WorkflowRun run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        Object pendingObj = run.getMetadata().get("pending_todos");
        if (approved && pendingObj instanceof List<?> pendingList) {
            for (Object item : pendingList) {
                if (item instanceof Map<?, ?> todoMap) {
                    createTodoFromPending(todoMap, run);
                }
            }
            log.info("Auto-todo approved: created todos for run {}", runId);
        } else {
            log.info("Auto-todo rejected: discarded pending todos for run {}", runId);
        }

        run.getMetadata().remove("pending_todos");
        workflowRunRepository.save(run);
    }

    @SuppressWarnings("unchecked")
    private void createTodoFromPending(Map<?, ?> todoMap, WorkflowRun run) {
        String title = String.valueOf(todoMap.get("title"));
        Todo.Priority priority = Todo.Priority.valueOf(String.valueOf(todoMap.get("priority")));
        String description = todoMap.containsKey("description") ? String.valueOf(todoMap.get("description")) : null;
        String projectId = String.valueOf(todoMap.get("projectId"));
        String category = String.valueOf(todoMap.get("category"));
        String tagsJson = todoMap.containsKey("tags") ? String.valueOf(todoMap.get("tags")) : "[]";

        Optional<Todo> existing = todoRepository.findOpenGateByCompositeKey(
                projectId, AutoTodoService.REFERENCE_TYPE, run.getWorkflow().getId(), "category:" + category);

        if (existing.isPresent()) {
            Todo todo = existing.get();
            todo.setDescription(description);
            todo.setWorkflowRunId(run.getId());
            todo.setPriority(priority);
            todo.setTitle(textBuilder.retitle(todo.getTitle(), priority));
            todo.setTags(tagsJson);
            todoRepository.save(todo);
            log.info("Auto-todo (approved) updated: {} ({})", todo.getTitle(), todo.getId());
        } else {
            Todo todo = Todo.builder()
                    .title(title)
                    .description(description)
                    .status(Todo.Status.OPEN)
                    .priority(priority)
                    .tags(tagsJson)
                    .projectId(projectId)
                    .source(Todo.Source.WORKFLOW)
                    .sourceAgent(AutoTodoService.SOURCE_AGENT)
                    .workflowRunId(run.getId())
                    .referenceType(AutoTodoService.REFERENCE_TYPE)
                    .referenceId(run.getWorkflow().getId())
                    .build();
            Todo saved = todoRepository.save(todo);
            eventPublisher.publishEvent(new TodoCreatedEvent(this, saved));
            log.info("Auto-todo (approved) created: {} ({})", saved.getTitle(), saved.getId());
        }
    }

    private Map<String, Object> buildPendingTodo(String projectId, WorkflowRunCompletedEvent event, GateResult gate) {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("title", textBuilder.buildTitle(event, gate));
        proposed.put("priority", gate.status().todoPriority().name());
        proposed.put("description", textBuilder.buildDescription(event, gate));
        proposed.put("category", gate.category());
        proposed.put("gateStatus", gate.status().name());
        proposed.put("projectId", projectId);
        proposed.put("tags", textBuilder.buildTagsJson(gate));
        return proposed;
    }
}
