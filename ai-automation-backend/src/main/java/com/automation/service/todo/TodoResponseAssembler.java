package com.automation.service.todo;

import com.automation.dto.response.TodoPlanningResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Goal;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.mapper.TodoMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
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
 * per table, project and workflow as id/name rows) instead of N+1 lookups;
 * {@link #toResponse(Todo)} is the single-entity variant.
 */
@Component
@RequiredArgsConstructor
public class TodoResponseAssembler {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final GoalRepository goalRepository;
    private final TodoMapper todoMapper;
    private final ModuleRegistry modules;

    public TodoResponse toResponse(Todo todo) {
        String projectName = todo.getProjectId() == null ? null
                : projectRepository.findById(todo.getProjectId()).map(Project::getName).orElse(null);
        String workflowName = todo.getLinkedWorkflowId() == null ? null
                : workflowRepository.findById(todo.getLinkedWorkflowId()).map(Workflow::getName).orElse(null);
        String goalTitle = todo.getGoalId() == null || !goalsEnabled() ? null
                : goalRepository.findById(todo.getGoalId()).map(Goal::getTitle).orElse(null);
        return withNames(todo, projectName, workflowName, goalTitle);
    }

    public List<TodoResponse> toResponses(List<Todo> todos) {
        // Only ID and name: findAllById loads whole projects or workflows including project, and the
        // container relation of the project (nullable one-to-one, not possible as a proxy) costs one
        // more query per project - 30 statements for a large page (TODO 08224ed6).
        Map<String, String> projectNames = namesFor(todos, Todo::getProjectId,
                ids -> idToName(projectRepository.findIdAndNameByIdIn(ids)));
        Map<String, String> workflowNames = namesFor(todos, Todo::getLinkedWorkflowId,
                ids -> idToName(workflowRepository.findIdAndNameByIdIn(ids)));
        Map<String, String> goalTitles = !goalsEnabled() ? Map.of() : namesFor(todos, Todo::getGoalId,
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

    private static Map<String, String> idToName(List<Object[]> rows) {
        Map<String, String> names = new HashMap<>();
        rows.forEach(row -> names.put((String) row[0], (String) row[1]));
        return names;
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
                goalsEnabled() ? planningOf(todo, goalTitle) : null
        );
    }

    /**
     * Goal and assessment belong to module {@code goals}: if it is off, {@code planning} stays empty
     * and no goal title is looked up - the data is kept and is back after
     * switching on again (TODO c60bf914). Checked per call, no startup snapshot.
     */
    private boolean goalsEnabled() {
        return modules.isEnabled(ModuleCatalog.GOALS);
    }

    /** Null as long as neither goal nor assessment nor open question is set (TODO dc0566f8). */
    static TodoPlanningResponse planningOf(Todo todo, String goalTitle) {
        if (todo.getGoalId() == null && todo.getComplexity() == null && todo.getSuggestedEffort() == null
                && todo.getRatingNote() == null && todo.getOpenQuestion() == null && todo.getWorkPackageId() == null
                && todo.getDecision() == null) {
            return null;
        }
        return new TodoPlanningResponse(todo.getGoalId(), goalTitle, todo.getGoalRank(),
                todo.getComplexity(), todo.getSuggestedEffort(), todo.getRatingNote(),
                todo.getRatedBy(), todo.getRatedAt(), todo.getOpenQuestion(), todo.getWorkPackageId(),
                todo.getDecision(), todo.getDecidedAt(), todo.getDecidedBy(), todo.getDecisionState());
    }
}
