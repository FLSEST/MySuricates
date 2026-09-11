package com.automation.service.todo;

import com.automation.dto.response.TodoResponse;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.mapper.TodoMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns {@link Todo} entities into {@link TodoResponse}s including the display names of the
 * linked project and workflow. {@link #toResponses(List)} batch-loads the names (one query per
 * table) instead of N+1 lookups; {@link #toResponse(Todo)} is the single-entity variant.
 */
@Component
@RequiredArgsConstructor
public class TodoResponseAssembler {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final TodoMapper todoMapper;

    public TodoResponse toResponse(Todo todo) {
        String projectName = todo.getProjectId() == null ? null
                : projectRepository.findById(todo.getProjectId()).map(Project::getName).orElse(null);
        String workflowName = todo.getLinkedWorkflowId() == null ? null
                : workflowRepository.findById(todo.getLinkedWorkflowId()).map(Workflow::getName).orElse(null);
        return withNames(todo, projectName, workflowName);
    }

    public List<TodoResponse> toResponses(List<Todo> todos) {
        Map<String, String> projectNames = namesFor(todos, Todo::getProjectId,
                ids -> projectRepository.findAllById(ids).stream().collect(Collectors.toMap(Project::getId, Project::getName)));
        Map<String, String> workflowNames = namesFor(todos, Todo::getLinkedWorkflowId,
                ids -> workflowRepository.findAllById(ids).stream().collect(Collectors.toMap(Workflow::getId, Workflow::getName)));
        return todos.stream()
                .map(todo -> withNames(todo, projectNames.get(todo.getProjectId()), workflowNames.get(todo.getLinkedWorkflowId())))
                .toList();
    }

    private static Map<String, String> namesFor(List<Todo> todos, Function<Todo, String> idOf,
                                                Function<Set<String>, Map<String, String>> loader) {
        Set<String> ids = todos.stream().map(idOf).filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        // A HashMap (not Map.of()) tolerates the null-key .get(...) lookups in withNames() for
        // todos that have no projectId/linkedWorkflowId at all.
        return ids.isEmpty() ? new HashMap<>() : loader.apply(ids);
    }

    private TodoResponse withNames(Todo todo, String projectName, String linkedWorkflowName) {
        TodoResponse base = todoMapper.toResponse(todo);
        return new TodoResponse(
                base.id(), base.title(), base.description(),
                base.status(), base.priority(), base.tags(),
                base.projectId(), projectName,
                base.linkedWorkflowId(), linkedWorkflowName,
                base.source(), base.sourceAgent(), base.sessionId(),
                base.workflowRunId(),
                base.referenceType(), base.referenceId(),
                base.exportedProvider(), base.exportedRef(), base.exportedUrl(),
                base.fixAttempts(), base.lastFixRunId(),
                base.resolutionState(),
                base.dueDate(),
                base.createdAt(), base.updatedAt()
        );
    }
}
