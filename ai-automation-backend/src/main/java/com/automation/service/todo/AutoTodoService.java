package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTodoService {

    static final String TAG_AUTO_WORKFLOW = "auto:workflow";
    static final String REFERENCE_TYPE = "workflow_run";
    static final String SOURCE_AGENT = "auto-todo";
    static final String SETTING_AUTO_TODO_DEFAULT = "auto-todo-default";

    private final TodoRepository todoRepository;
    private final GateStatusResolver gateStatusResolver;
    private final ProjectPathResolver projectPathResolver;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowRunRepository workflowRunRepository;

    @Async
    @EventListener
    @Transactional
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        if (!isAutoTodoEnabled(event)) {
            log.debug("Auto-todo disabled for workflow {}", event.getWorkflowId());
            return;
        }

        GateResult gate = gateStatusResolver.resolve(event);
        if (gate == null) {
            log.debug("No gate action for workflow {} (status={})", event.getWorkflowId(), event.getStatus());
            return;
        }

        log.info("Auto-todo: workflow={}, gate={}, category={}, mode={}", event.getWorkflowName(), gate.status(), gate.category(), getMode(event));

        List<String> targetProjectIds = resolveTargetProjectIds(event);

        if ("approval".equals(getMode(event))) {
            handleApprovalMode(event, gate, targetProjectIds);
        } else {
            handleAutoMode(event, gate, targetProjectIds);
        }
    }

    private void handleAutoMode(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        for (String projectId : targetProjectIds) {
            switch (gate.status()) {
                case GREEN -> closeOpenTodos(projectId, event);
                case YELLOW -> upsertTodo(projectId, event, gate, Todo.Priority.MEDIUM);
                case RED -> upsertTodo(projectId, event, gate, Todo.Priority.HIGH);
            }
        }
    }

    private void handleApprovalMode(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        if (gate.status() == GateStatus.GREEN) {
            // On GREEN, auto-close open todos even in approval mode
            for (String projectId : targetProjectIds) {
                closeOpenTodos(projectId, event);
            }
            return;
        }

        List<Map<String, Object>> pendingTodos = new ArrayList<>();
        for (String projectId : targetProjectIds) {
            pendingTodos.add(buildPendingTodo(projectId, event, gate));
        }

        workflowRunRepository.findById(event.getRunId()).ifPresent(run -> {
            run.getMetadata().put("pending_todos", pendingTodos);
            workflowRunRepository.save(run);
            log.info("Auto-todo (approval): stored {} pending todo(s) for run {}", pendingTodos.size(), event.getRunId());
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

        Optional<Todo> existing = todoRepository.findOpenByCompositeKey(
                projectId, REFERENCE_TYPE, run.getWorkflow().getId(), "category:" + category);

        if (existing.isPresent()) {
            Todo todo = existing.get();
            todo.setDescription(description);
            todo.setWorkflowRunId(run.getId());
            todo.setPriority(priority);
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
                    .sourceAgent(SOURCE_AGENT)
                    .workflowRunId(run.getId())
                    .referenceType(REFERENCE_TYPE)
                    .referenceId(run.getWorkflow().getId())
                    .build();
            todoRepository.save(todo);
            log.info("Auto-todo (approved) created: {} ({})", todo.getTitle(), todo.getId());
        }
    }

    private Map<String, Object> buildPendingTodo(String projectId, WorkflowRunCompletedEvent event, GateResult gate) {
        String prefix = gate.status() == GateStatus.RED ? "[Incident]" : "[Follow-up]";
        String title = prefix + " " + event.getWorkflowName() + " \u2014 " + gate.category();
        Todo.Priority priority = gate.status() == GateStatus.RED ? Todo.Priority.HIGH : Todo.Priority.MEDIUM;

        Map<String, Object> proposed = new HashMap<>();
        proposed.put("title", title);
        proposed.put("priority", priority.name());
        proposed.put("description", buildDescription(event, gate));
        proposed.put("category", gate.category());
        proposed.put("gateStatus", gate.status().name());
        proposed.put("projectId", projectId);
        proposed.put("tags", buildTagsJson(gate));
        return proposed;
    }

    @SuppressWarnings("unchecked")
    String getMode(WorkflowRunCompletedEvent event) {
        Object autoTodo = event.getWorkflowMetadata().get("autoTodo");
        if (autoTodo instanceof Map<?, ?> map) {
            Object mode = map.get("mode");
            if (mode instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return "auto";
    }

    /**
     * Determines if auto-todo is enabled for this workflow run.
     * Priority: explicit workflow metadata > global default > false
     */
    @SuppressWarnings("unchecked")
    boolean isAutoTodoEnabled(WorkflowRunCompletedEvent event) {
        Object autoTodo = event.getWorkflowMetadata().get("autoTodo");
        if (autoTodo instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            if (enabled instanceof Boolean b) {
                return b;
            }
        }
        // No explicit setting — fall back to global default
        return appSettingsRepository.get(SETTING_AUTO_TODO_DEFAULT)
                .map("true"::equals)
                .orElse(false);
    }

    private List<String> resolveTargetProjectIds(WorkflowRunCompletedEvent event) {
        // Check for affected_paths in run metadata (multi-repo support)
        Object affectedPaths = event.getRunMetadata().get("affected_paths");
        if (affectedPaths instanceof List<?> paths && !paths.isEmpty()) {
            List<String> filePaths = paths.stream()
                    .filter(p -> p instanceof String)
                    .map(p -> (String) p)
                    .toList();

            List<Project> resolved = projectPathResolver.resolveAll(filePaths);
            if (!resolved.isEmpty()) {
                return resolved.stream().map(Project::getId).toList();
            }
            // Fallback: use workflow's project
            log.warn("Could not resolve affected_paths to projects, falling back to workflow project");
        }

        return List.of(event.getProjectId());
    }

    private void closeOpenTodos(String projectId, WorkflowRunCompletedEvent event) {
        List<Todo> openTodos = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                projectId, Todo.Source.WORKFLOW, REFERENCE_TYPE, event.getWorkflowId(), Todo.Status.DONE);

        // Only close auto-generated todos (tagged with auto:workflow)
        List<Todo> autoTodos = openTodos.stream()
                .filter(t -> t.getTags() != null && t.getTags().contains(TAG_AUTO_WORKFLOW))
                .toList();

        for (Todo todo : autoTodos) {
            todo.setStatus(Todo.Status.DONE);
            todoRepository.save(todo);
            log.info("Auto-todo closed: {} ({})", todo.getTitle(), todo.getId());
        }
    }

    private void upsertTodo(String projectId, WorkflowRunCompletedEvent event,
                            GateResult gate, Todo.Priority priority) {
        String categoryTag = "category:" + gate.category();

        Optional<Todo> existing = todoRepository.findOpenByCompositeKey(
                projectId, REFERENCE_TYPE, event.getWorkflowId(), categoryTag);

        if (existing.isPresent()) {
            // Update existing todo
            Todo todo = existing.get();
            todo.setDescription(buildDescription(event, gate));
            todo.setWorkflowRunId(event.getRunId());
            todo.setPriority(priority);
            todo.setTags(buildTagsJson(gate));
            todoRepository.save(todo);
            log.info("Auto-todo updated: {} ({})", todo.getTitle(), todo.getId());
        } else {
            // Create new todo
            String prefix = priority == Todo.Priority.HIGH ? "[Incident]" : "[Follow-up]";
            Todo todo = Todo.builder()
                    .title(prefix + " " + event.getWorkflowName() + " \u2014 " + gate.category())
                    .description(buildDescription(event, gate))
                    .status(Todo.Status.OPEN)
                    .priority(priority)
                    .tags(buildTagsJson(gate))
                    .projectId(projectId)
                    .source(Todo.Source.WORKFLOW)
                    .sourceAgent(SOURCE_AGENT)
                    .workflowRunId(event.getRunId())
                    .referenceType(REFERENCE_TYPE)
                    .referenceId(event.getWorkflowId())
                    .build();
            todoRepository.save(todo);
            log.info("Auto-todo created: {} ({})", todo.getTitle(), todo.getId());
        }
    }

    private String buildDescription(WorkflowRunCompletedEvent event, GateResult gate) {
        StringBuilder sb = new StringBuilder();
        sb.append("Auto-generated by workflow: ").append(event.getWorkflowName()).append("\n");
        sb.append("Run ID: ").append(event.getRunId()).append("\n");
        sb.append("Gate Status: ").append(gate.status()).append("\n");
        sb.append("Category: ").append(gate.category());
        if (gate.description() != null) {
            sb.append("\n\n").append(gate.description());
        }
        return sb.toString();
    }

    private String buildTagsJson(GateResult gate) {
        List<String> tags = new ArrayList<>();
        tags.add(TAG_AUTO_WORKFLOW);
        tags.add("gate:" + gate.status().name().toLowerCase());
        tags.add("category:" + gate.category());
        return TodoMapper.listToJson(tags);
    }
}
