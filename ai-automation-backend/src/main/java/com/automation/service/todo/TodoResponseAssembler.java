package com.automation.service.todo;

import com.automation.dto.response.TodoPlanningResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Goal;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.mapper.TodoMapper;
import com.automation.repository.GoalRepository;
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
 * linked project, workflow and goal. {@link #toResponses(List)} batch-loads the names (one query
 * per table) instead of N+1 lookups; {@link #toResponse(Todo)} is the single-entity variant.
 */
@Component
@RequiredArgsConstructor
public class TodoResponseAssembler {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final GoalRepository goalRepository;
    private final TodoMapper todoMapper;

    public TodoResponse toResponse(Todo todo) {
        String projectName = todo.getProjectId() == null ? null
                : projectRepository.findById(todo.getProjectId()).map(Project::getName).orElse(null);
        String workflowName = todo.getLinkedWorkflowId() == null ? null
                : workflowRepository.findById(todo.getLinkedWorkflowId()).map(Workflow::getName).orElse(null);
        String goalTitle = todo.getGoalId() == null ? null
                : goalRepository.findById(todo.getGoalId()).map(Goal::getTitle).orElse(null);
        return withNames(todo, projectName, workflowName, goalTitle);
    }

    public List<TodoResponse> toResponses(List<Todo> todos) {
        Map<String, String> projectNames = namesFor(todos, Todo::getProjectId,
                ids -> projectRepository.findAllById(ids).stream().collect(Collectors.toMap(Project::getId, Project::getName)));
        Map<String, String> workflowNames = namesFor(todos, Todo::getLinkedWorkflowId,
                ids -> workflowRepository.findAllById(ids).stream().collect(Collectors.toMap(Workflow::getId, Workflow::getName)));
        Map<String, String> goalTitles = namesFor(todos, Todo::getGoalId,
                ids -> goalRepository.findAllById(ids).stream().collect(Collectors.toMap(Goal::getId, Goal::getTitle)));
        return todos.stream()
                .map(todo -> withNames(todo, nameOf(projectNames, todo.getProjectId()),
                        nameOf(workflowNames, todo.getLinkedWorkflowId()), nameOf(goalTitles, todo.getGoalId())))
                .toList();
    }

    private static Map<String, String> namesFor(List<Todo> todos, Function<Todo, String> idOf,
                                                Function<Set<String>, Map<String, String>> loader) {
        Set<String> ids = todos.stream().map(idOf).filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        return ids.isEmpty() ? new HashMap<>() : loader.apply(ids);
    }

    // Null-safe lookup: todos without a projectId/linkedWorkflowId must not trigger a map lookup
    // with a null key.
    private static String nameOf(Map<String, String> names, String id) {
        return id == null ? null : names.get(id);
    }

    private TodoResponse withNames(Todo todo, String projectName, String linkedWorkflowName, String goalTitle) {
        TodoResponse base = todoMapper.toResponse(todo);
        return new TodoResponse(
                base.id(), base.title(), base.description(),
                base.status(), base.priority(), base.tags(),
                base.projectId(), projectName,
                base.linkedWorkflowId(), linkedWorkflowName,
                base.source(), base.sourceAgent(), base.sessionId(),
                base.agentSessionRef(),
                base.workflowRunId(),
                base.referenceType(), base.referenceId(),
                base.exportedProvider(), base.exportedRef(), base.exportedUrl(),
                base.fixAttempts(), base.lastFixRunId(),
                base.resolutionState(),
                base.dueDate(),
                base.createdAt(), base.updatedAt(),
                planningOf(todo, goalTitle)
        );
    }

    /** Null, solange weder Ziel noch Einschaetzung noch offene Frage gesetzt sind (TODO dc0566f8). */
    static TodoPlanningResponse planningOf(Todo todo, String goalTitle) {
        if (todo.getGoalId() == null && todo.getComplexity() == null && todo.getSuggestedEffort() == null
                && todo.getRatingNote() == null && todo.getOpenQuestion() == null && todo.getWorkPackageId() == null) {
            return null;
        }
        return new TodoPlanningResponse(todo.getGoalId(), goalTitle, todo.getGoalRank(),
                todo.getComplexity(), todo.getSuggestedEffort(), todo.getRatingNote(),
                todo.getRatedBy(), todo.getRatedAt(), todo.getOpenQuestion(), todo.getWorkPackageId());
    }
}
