package com.automation.service.todo;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.GateStatus;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reacts to completed workflow runs: decides mode (auto / track / approval), resolves the target
 * projects and creates, updates or closes the gate todo. Text building lives in
 * {@link AutoTodoTextBuilder}, approval mode in {@link PendingTodoApprovalService}, the granular
 * finding todos in {@link GranularTodoService}.
 */
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
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AutoTodoTextBuilder textBuilder;
    private final PendingTodoApprovalService approvalService;
    private final GranularTodoService granularTodoService;
    private final ActivityLogService activityLogService;

    /**
     * Runs async, so an escaping exception only reaches the {@code AsyncUncaughtExceptionHandler}
     * and the incident todo is silently missing (2026-09-13: three failed runs, no todo, because the
     * resolver itself died with a NoClassDefFoundError). Every failure is therefore logged and
     * recorded in the activity log with the run id (TODO 3a4d238c).
     */
    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        try {
            handleRunCompleted(event);
        } catch (Throwable t) {
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            log.error("Auto-todo failed for run {} (workflow {}): {}", event.getRunId(), event.getWorkflowName(), detail, t);
            activityLogService.error(LogCategory.SYSTEM,
                    "Auto-TODO für Run " + event.getRunId() + " fehlgeschlagen: " + t.getClass().getSimpleName() + ": " + detail,
                    "AutoTodoService", event.getRunId(), event.getWorkflowId());
        }
    }

    private void handleRunCompleted(WorkflowRunCompletedEvent event) {
        // Loop prevention: skip TODO creation for fix-attempt runs triggered by TodoTrigger
        Object todoTriggerRun = event.getRunMetadata().get("todoTriggerRun");
        if (Boolean.TRUE.equals(todoTriggerRun)) {
            log.debug("Skipping auto-todo for run {} (todo trigger fix attempt)", event.getRunId());
            return;
        }

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

        String mode = getMode(event);
        switch (mode) {
            case "track" -> handleTrackMode(event, gate, targetProjectIds);
            case "approval" -> handleApprovalMode(event, gate, targetProjectIds);
            default -> handleAutoMode(event, gate, targetProjectIds);
        }
    }

    private void handleAutoMode(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        for (String projectId : targetProjectIds) {
            if (granularTodoService.hasItems(event)) {
                granularTodoService.syncItems(projectId, event, gate);
            }
            switch (gate.status()) {
                case GREEN -> closeOpenTodos(projectId, event);
                case YELLOW -> upsertTodo(projectId, event, gate, Todo.Priority.MEDIUM);
                case RED -> upsertTodo(projectId, event, gate, Todo.Priority.HIGH);
            }
        }
    }

    private void handleTrackMode(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        Todo.Priority priority = switch (gate.status()) {
            case RED -> Todo.Priority.HIGH;
            case YELLOW -> Todo.Priority.MEDIUM;
            case GREEN -> Todo.Priority.LOW;
        };
        for (String projectId : targetProjectIds) {
            if (granularTodoService.hasItems(event)) {
                granularTodoService.syncItems(projectId, event, gate);
            }
            upsertTodo(projectId, event, gate, priority);
        }
    }

    private void handleApprovalMode(WorkflowRunCompletedEvent event, GateResult gate, List<String> targetProjectIds) {
        if (gate.status() == GateStatus.GREEN) {
            for (String projectId : targetProjectIds) {
                closeOpenTodos(projectId, event);
            }
            return;
        }

        approvalService.storePendingTodos(event, gate, targetProjectIds);
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
        Object audit = event.getWorkflowMetadata().get("audit");
        if (audit instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("enabled"))) {
            return false;
        }
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

        if (event.getProjectId() == null) {
            log.warn("WorkflowRunCompletedEvent has no projectId for workflow '{}' — skipping auto-todo creation",
                    event.getWorkflowName());
            return List.of();
        }
        return List.of(event.getProjectId());
    }

    private void closeOpenTodos(String projectId, WorkflowRunCompletedEvent event) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            List<Todo> openTodos = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    projectId, Todo.Source.WORKFLOW, REFERENCE_TYPE, event.getWorkflowId(), Todo.Status.DONE);

            List<Todo> toClose = openTodos.stream()
                    .filter(t -> t.getTags() != null && t.getTags().contains(TAG_AUTO_WORKFLOW))
                    .toList();
            if (toClose.isEmpty()) {
                return null;
            }
            toClose.forEach(todo -> todo.setStatus(Todo.Status.DONE));
            // One batched write instead of a save() per todo inside the loop.
            todoRepository.saveAll(toClose);
            toClose.forEach(todo -> log.info("Auto-todo closed: {} ({})", todo.getTitle(), todo.getId()));
            return null;
        });
    }

    private void upsertTodo(String projectId, WorkflowRunCompletedEvent event,
                            GateResult gate, Todo.Priority priority) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            String categoryTag = "category:" + gate.category();

            Optional<Todo> existing = todoRepository.findOpenGateByCompositeKey(
                    projectId, REFERENCE_TYPE, event.getWorkflowId(), categoryTag);

            if (existing.isPresent()) {
                Todo todo = existing.get();
                String mode = getMode(event);
                if ("track".equals(mode)) {
                    // Track mode: append update entry instead of overwriting
                    String newReport = textBuilder.loadReportFromSteps(event.getRunId());
                    String updateEntry = String.format("\n\n---\n### Update %s\n*Run: `%s`*\n\n%s",
                            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            event.getRunId(),
                            newReport != null ? newReport : gate.description());
                    String currentDesc = todo.getDescription() != null ? todo.getDescription() : "";
                    // Cap total length to avoid unbounded growth
                    String merged = currentDesc + updateEntry;
                    if (merged.length() > 8000) {
                        merged = merged.substring(merged.length() - 8000);
                        merged = "...\n" + merged.substring(merged.indexOf("\n---\n") + 1);
                    }
                    todo.setDescription(merged);
                } else {
                    todo.setDescription(textBuilder.buildDescription(event, gate));
                }
                todo.setWorkflowRunId(event.getRunId());
                todo.setPriority(priority);
                todo.setTags(textBuilder.buildTagsJson(gate));
                todoRepository.save(todo);
                log.info("Auto-todo updated: {} ({})", todo.getTitle(), todo.getId());
            } else {
                String prefix = priority == Todo.Priority.HIGH ? "[Incident]" : "[Follow-up]";
                Todo todo = Todo.builder()
                        .title(prefix + " " + event.getWorkflowName() + " — " + gate.category())
                        .description(textBuilder.buildDescription(event, gate))
                        .status(Todo.Status.OPEN)
                        .priority(priority)
                        .tags(textBuilder.buildTagsJson(gate))
                        .projectId(projectId)
                        .source(Todo.Source.WORKFLOW)
                        .sourceAgent(SOURCE_AGENT)
                        .workflowRunId(event.getRunId())
                        .referenceType(REFERENCE_TYPE)
                        .referenceId(event.getWorkflowId())
                        .build();
                Todo saved = todoRepository.save(todo);
                eventPublisher.publishEvent(new TodoCreatedEvent(AutoTodoService.this, saved));
                log.info("Auto-todo created: {} ({})", saved.getTitle(), saved.getId());
            }
            return null;
        });
    }
}
