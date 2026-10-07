package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Starts the linked fix workflow for a TODO ("resolve"): guards against double runs, injects the
 * TODO as context variables and marks the TODO IN_PROGRESS/RUNNING. Extracted from TodoService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoResolutionService {

    private final TodoService todoService;
    private final TodoRepository todoRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final ObjectProvider<WorkflowExecutor> workflowExecutorProvider;

    @Transactional
    public WorkflowRun resolve(String todoId) {
        Todo todo = todoService.findById(todoId);
        requireResolvable(todo);

        Workflow workflow = workflowService.findByIdEager(todo.getLinkedWorkflowId());
        WorkflowRun run = workflowExecutorProvider.getObject()
                .execute(workflow, TodoContextVariables.of(todo), null, runMetadataFor(todo, workflow), TriggerSource.TODO_RESOLUTION);

        todo.setStatus(Todo.Status.IN_PROGRESS);
        todo.setLastFixRunId(run.getId());
        todo.setResolutionState(Todo.ResolutionState.RUNNING);
        todoRepository.save(todo);
        log.info("Started resolution workflow {} for todo {} ({})", workflow.getName(), todo.getTitle(), todo.getId());
        return run;
    }

    private void requireResolvable(Todo todo) {
        if (todo.getLinkedWorkflowId() == null || todo.getLinkedWorkflowId().isBlank()) {
            throw new IllegalArgumentException("Todo has no linked workflow");
        }
        if (todo.getStatus() == Todo.Status.DONE) {
            throw new IllegalStateException("Done todo does not need resolution");
        }
        if (TodoFixRuns.isStillRunning(todo, workflowRunRepository)) {
            throw new IllegalStateException("A resolution workflow is already running for this todo");
        }
    }

    private static Map<String, Object> runMetadataFor(Todo todo, Workflow workflow) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("linkedTodoRun", true);
        metadata.put("linkedTodoId", todo.getId());
        metadata.put("linkedTodoPreviousStatus", todo.getStatus().name());
        metadata.put("linkedTodoWorkflowId", workflow.getId());
        return metadata;
    }
}
