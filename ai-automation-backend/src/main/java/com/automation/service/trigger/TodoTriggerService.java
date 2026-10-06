package com.automation.service.trigger;

import com.automation.config.TodoTriggerConfig;
import com.automation.dto.request.CreateTodoTriggerRequest;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.util.EnumParameter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@Slf4j
public class TodoTriggerService {

    private final TodoTriggerRepository repository;
    private final WorkflowRepository workflowRepository;
    private final TodoRepository todoRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final TodoTriggerConfig config;

    /** Last unparseable skip-until we already warned about, so the 5-min poll does not spam the log. */
    private volatile String lastInvalidSkipUntil = null;

    public TodoTriggerService(TodoTriggerRepository repository,
                              WorkflowRepository workflowRepository,
                              TodoRepository todoRepository,
                              WorkflowRunRepository workflowRunRepository,
                              WorkflowService workflowService,
                              @Lazy WorkflowExecutor workflowExecutor,
                              TodoTriggerConfig config) {
        this.repository = repository;
        this.workflowRepository = workflowRepository;
        this.todoRepository = todoRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.config = config;
    }

    /**
     * A configured pause is invisible in the UI — log it once at startup so „the loop stopped
     * picking up todos" is diagnosable without reading the config file.
     */
    @PostConstruct
    void logPauseWindowAtStartup() {
        parseSkipUntil().ifPresent(until -> {
            if (Instant.now().isBefore(until)) {
                log.warn("TodoTrigger mechanism paused until {} (automation.todo-trigger.skip-until) "
                        + "— no trigger will fire before that", until);
            }
        });
    }

    // ── CRUD ──────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TodoTrigger> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public TodoTrigger findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TodoTrigger", id));
    }

    @Transactional(readOnly = true)
    public List<TodoTrigger> findByTargetWorkflowId(String workflowId) {
        return repository.findByTargetWorkflowId(workflowId);
    }

    @Transactional
    public TodoTrigger create(CreateTodoTriggerRequest req) {
        // Stored as the constant's name (TODO a9bf13d5): the matcher compares against it, and an
        // unknown value is a 400 naming the allowed ones instead of a trigger that never fires.
        Todo.Source sourceFilter = EnumParameter.parseOptional(Todo.Source.class, "sourceFilter", req.sourceFilter());
        Todo.Priority minPriority = EnumParameter.parseOptional(Todo.Priority.class, "minPriority", req.minPriority());
        Workflow target = workflowRepository.findById(req.targetWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", req.targetWorkflowId()));

        TodoTrigger trigger = TodoTrigger.builder()
                .tagPattern(req.tagPattern())
                .projectId(req.projectId())
                .sourceFilter(sourceFilter != null ? sourceFilter.name() : null)
                .minPriority(minPriority != null ? minPriority.name() : null)
                .targetWorkflow(target)
                .contextTemplate(req.contextTemplate() != null ? new HashMap<>(req.contextTemplate()) : new HashMap<>())
                .description(req.description())
                .maxAttempts(req.maxAttempts() != null ? req.maxAttempts() : 3)
                .autoClose(req.autoClose() != null ? req.autoClose() : true)
                .processBacklog(req.processBacklog() != null ? req.processBacklog() : true)
                .build();

        return repository.save(trigger);
    }

    @Transactional
    public TodoTrigger enable(String id) {
        TodoTrigger trigger = findById(id);
        trigger.setEnabled(true);
        return repository.save(trigger);
    }

    @Transactional
    public TodoTrigger disable(String id) {
        TodoTrigger trigger = findById(id);
        trigger.setEnabled(false);
        return repository.save(trigger);
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("TodoTrigger", id);
        }
        repository.deleteById(id);
    }

    // ── Event handling ──────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTodoCreated(TodoCreatedEvent event) {
        Todo todo = event.getTodo();
        if (todo.getStatus() != Todo.Status.OPEN) {
            return;
        }

        if (isPaused()) {
            log.debug("TodoTrigger paused — ignoring created todo '{}'", todo.getTitle());
            return;
        }

        List<TodoTrigger> triggers = repository.findByEnabledTrue();
        if (triggers.isEmpty()) {
            return;
        }

        for (TodoTrigger trigger : triggers) {
            if (matches(trigger, todo) && canFire(trigger, todo)) {
                try {
                    fireTrigger(trigger, todo);
                } catch (Exception e) {
                    log.error("TodoTrigger {} failed for todo '{}': {}",
                            trigger.getId(), todo.getTitle(), e.getMessage());
                }
            }
        }
    }

    // ── Scheduled backlog processing ───────────────────

    @Scheduled(fixedDelayString = "${automation.todo-trigger.poll-interval-ms:300000}")
    public void processBacklog() {
        if (isPaused()) {
            log.debug("TodoTrigger paused — skipping backlog poll");
            return;
        }

        List<TodoTrigger> triggers = repository.findByEnabledTrue().stream()
                .filter(TodoTrigger::isProcessBacklog)
                .toList();
        if (triggers.isEmpty()) {
            return;
        }

        // Per-trigger scoped candidate query instead of one global top-N page:
        // the global page filled up with old high-priority todos no trigger
        // matches, starving trigger-relevant todos further down the list.
        for (TodoTrigger trigger : triggers) {
            Todo.Source sourceFilter = TodoTriggerMatcher.sourceFilter(trigger.getSourceFilter());
            List<Todo> candidates = todoRepository.findBacklogCandidatesForTrigger(
                    trigger.getProjectId(), sourceFilter, trigger.getMaxAttempts(), PageRequest.of(0, 50));

            for (Todo todo : candidates) {
                if (matches(trigger, todo) && canFire(trigger, todo)) {
                    try {
                        fireTrigger(trigger, todo);
                        log.info("Backlog processing: triggered fix for todo '{}'", todo.getTitle());
                    } catch (Exception e) {
                        log.error("Backlog processing: trigger {} failed for todo '{}': {}",
                                trigger.getId(), todo.getTitle(), e.getMessage());
                    }
                    return; // Only 1 per tick
                }
            }
        }
    }

    /** True while the configured pause window (automation.todo-trigger.skip-until) is still open. */
    private boolean isPaused() {
        return parseSkipUntil().map(Instant.now()::isBefore).orElse(false);
    }

    /**
     * Mirrors {@code SchedulerService.parseSkipUntil}: fail open. A misconfigured (unparseable)
     * value must NOT silently disable the whole self-improvement loop, so it is treated as „no
     * pause" and warned about once per distinct value.
     */
    private Optional<Instant> parseSkipUntil() {
        String raw = config.getSkipUntil();
        if (raw == null || raw.isBlank()) {
            lastInvalidSkipUntil = null;
            return Optional.empty();
        }

        try {
            Optional<Instant> parsed = Optional.of(Instant.parse(raw.trim()));
            lastInvalidSkipUntil = null;
            return parsed;
        } catch (DateTimeParseException e) {
            if (!raw.equals(lastInvalidSkipUntil)) {
                lastInvalidSkipUntil = raw;
                log.error("Invalid automation.todo-trigger.skip-until value '{}'; ignoring it and "
                        + "firing triggers normally", raw);
            }
            return Optional.empty();
        }
    }

    private boolean canFire(TodoTrigger trigger, Todo todo) {
        if (todo.getFixAttempts() >= trigger.getMaxAttempts()) {
            log.debug("TodoTrigger {}: skipping todo '{}' — max attempts reached ({}/{})",
                    trigger.getId(), todo.getTitle(), todo.getFixAttempts(), trigger.getMaxAttempts());
            return false;
        }
        if (isFixRunStillRunning(todo)) {
            log.debug("TodoTrigger {}: skipping todo '{}' — fix run still running ({})",
                    trigger.getId(), todo.getTitle(), todo.getLastFixRunId());
            return false;
        }
        return true;
    }

    private void fireTrigger(TodoTrigger trigger, Todo todo) {
        Map<String, Object> context = buildContext(trigger, todo);
        Map<String, Object> runMeta = new HashMap<>();
        runMeta.put("todoTriggerRun", true);
        runMeta.put("todoTriggerId", trigger.getId());
        runMeta.put("todoId", todo.getId());

        Workflow target = workflowService.findByIdEager(trigger.getTargetWorkflow().getId());
        WorkflowRun run = workflowExecutor.execute(target, context, null, runMeta, com.automation.entity.TriggerSource.TODO_TRIGGER);

        // Update todo with fix attempt tracking
        todo.setFixAttempts(todo.getFixAttempts() + 1);
        todo.setLastFixRunId(run.getId());
        todoRepository.save(todo);

        // Update trigger stats
        trigger.setLastTriggeredAt(Instant.now());
        trigger.setTriggerCount(trigger.getTriggerCount() + 1);
        repository.save(trigger);

        log.info("TodoTrigger fired: '{}' → workflow '{}' (todo: '{}', attempt {}/{})",
                trigger.getDescription() != null ? trigger.getDescription() : trigger.getId(),
                target.getName(), todo.getTitle(), todo.getFixAttempts(), trigger.getMaxAttempts());
    }

    // ── Matching logic ──────────────────────────────

    /** Matching lives in {@link TodoTriggerMatcher}, which the goals' relation map also uses. */
    boolean matches(TodoTrigger trigger, Todo todo) {
        return TodoTriggerMatcher.matches(TodoTriggerMatcher.Criteria.of(trigger), TodoTriggerMatcher.Subject.of(todo));
    }

    private boolean isFixRunStillRunning(Todo todo) {
        if (todo.getLastFixRunId() == null) {
            return false;
        }
        return workflowRunRepository.findById(todo.getLastFixRunId())
                .map(run -> run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PENDING)
                .orElse(false);
    }

    private Map<String, Object> buildContext(TodoTrigger trigger, Todo todo) {
        Map<String, Object> context = new HashMap<>();

        // Static context from trigger template
        if (trigger.getContextTemplate() != null) {
            context.putAll(trigger.getContextTemplate());
        }

        // Dynamic todo context variables
        context.put("todo_id", todo.getId());
        context.put("todo_title", todo.getTitle());
        context.put("todo_description", todo.getDescription() != null ? todo.getDescription() : "");
        context.put("todo_tags", todo.getTags() != null ? todo.getTags() : "[]");
        context.put("todo_priority", todo.getPriority().name());
        context.put("todo_project_id", todo.getProjectId() != null ? todo.getProjectId() : "");
        context.put("todo_source", todo.getSource() != null ? todo.getSource().name() : "");
        context.put("todo_reference_type", todo.getReferenceType() != null ? todo.getReferenceType() : "");
        context.put("todo_reference_id", todo.getReferenceId() != null ? todo.getReferenceId() : "");
        context.put("todo_trigger_id", trigger.getId());

        return context;
    }
}
