package com.automation.service.todo;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.event.TodoCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRUD + status changes; resolution in {@link TodoResolutionService}, response assembly in
 * {@link TodoResponseAssembler}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoService {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final TodoResponseAssembler responseAssembler;
    private final ApplicationEventPublisher eventPublisher;
    private final TodoGoalConsistency goalConsistency;
    private final ModuleRegistry modules;

    @Transactional(readOnly = true)
    public PageResponse<TodoResponse> findAll(int page, int size, String projectId, List<String> status, String priority, String source, String tag, String q) {
        return findAll(page, size, projectId, status, priority, source, tag, q, null);
    }

    /** As above, additionally filtered to a goal (TODO dc0566f8). */
    @Transactional(readOnly = true)
    public PageResponse<TodoResponse> findAll(int page, int size, String projectId, List<String> status, String priority,
                                              String source, String tag, String q, String goalId) {
        if (goalId != null && !goalId.isBlank() && !modules.isEnabled(ModuleCatalog.GOALS)) {
            // Silently ignoring would give a caller who wants "the TODOs of this goal" the whole list.
            throw new IllegalArgumentException(ModuleCatalog.GOALS.disabledMessage("Filter goalId"));
        }
        // The order (priority rank, then newest) sits in the specification — an unsorted
        // PageRequest is required, a Sort would overwrite that order-by (aa434aee).
        Specification<Todo> spec = TodoSpecifications.listSpec(projectId, status, priority, source, tag, q)
                .and(TodoSpecifications.inGoal(goalId));
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Todo> result = todoRepository.findAll(spec, pageRequest);

        List<TodoResponse> content = responseAssembler.toResponses(result.getContent());
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Todo findById(String id) {
        return todoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Todo", id));
    }

    @Transactional(readOnly = true)
    public TodoResponse findByIdResponse(String id) {
        return responseAssembler.toResponse(findById(id));
    }

    @Transactional
    public Todo create(CreateTodoRequest request) {
        Todo todo = Todo.builder()
                .title(request.title())
                .description(request.description())
                .status(request.status() != null ? request.status() : Todo.Status.OPEN)
                .priority(request.priority() != null ? request.priority() : Todo.Priority.MEDIUM)
                .tags(TodoMapper.listToJson(request.tags()))
                .projectId(resolveProjectId(request.projectId()))
                .linkedWorkflowId(validateLinkedWorkflowId(request.linkedWorkflowId()))
                .source(request.source() != null ? request.source() : Todo.Source.USER)
                .sourceAgent(request.sourceAgent())
                .sessionId(request.sessionId())
                .agentSessionRef(normalizeSessionRef(request.agentSessionRef()))
                .workflowRunId(request.workflowRunId())
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .resolutionState(request.linkedWorkflowId() != null && !request.linkedWorkflowId().isBlank()
                        ? Todo.ResolutionState.IDLE : null)
                .dueDate(request.dueDate() != null && !request.dueDate().isBlank() ? Instant.parse(request.dueDate()) : null)
                .build();

        Todo saved = todoRepository.save(todo);
        log.info("Created todo: {} ({})", saved.getTitle(), saved.getId());
        if (saved.getStatus() == Todo.Status.OPEN) {
            eventPublisher.publishEvent(new TodoCreatedEvent(this, saved));
        }
        return saved;
    }

    @Transactional
    public Todo update(String id, UpdateTodoRequest request) {
        Todo todo = findById(id);

        if (request.title() != null) todo.setTitle(request.title());
        if (request.description() != null) todo.setDescription(request.description());
        if (request.status() != null) todo.setStatus(request.status());
        if (request.priority() != null) todo.setPriority(request.priority());
        if (request.tags() != null) todo.setTags(TodoMapper.listToJson(request.tags()));
        if (request.projectId() != null) {
            String before = todo.getProjectId();
            todo.setProjectId(resolveProjectId(request.projectId()));
            if (!java.util.Objects.equals(before, todo.getProjectId())) goalConsistency.afterProjectChange(todo);
        }
        if (request.linkedWorkflowId() != null) {
            todo.setLinkedWorkflowId(validateLinkedWorkflowId(request.linkedWorkflowId()));
            todo.setResolutionState(todo.getLinkedWorkflowId() != null ? Todo.ResolutionState.IDLE : null);
        }
        if (request.referenceType() != null) todo.setReferenceType(request.referenceType());
        if (request.referenceId() != null) todo.setReferenceId(request.referenceId());
        if (request.agentSessionRef() != null) {
            todo.setAgentSessionRef(normalizeSessionRef(request.agentSessionRef()));
        }
        if (request.dueDate() != null) {
            todo.setDueDate(request.dueDate().isBlank() ? null : Instant.parse(request.dueDate()));
        }

        // A change to DONE reports the TODO itself on save (Todo.Completed) - the audit finding follows there.
        Todo saved = todoRepository.save(todo);
        log.info("Updated todo: {} ({})", saved.getTitle(), saved.getId());
        return saved;
    }

    @Transactional
    public TodoResponse updateAndReturn(String id, UpdateTodoRequest request) {
        Todo saved = update(id, request);
        return responseAssembler.toResponse(saved);
    }

    @Transactional
    public Todo updateStatus(String id, UpdateTodoStatusRequest request) {
        Todo todo = findById(id);
        todo.setStatus(request.status());
        if (request.sessionId() != null) {
            todo.setSessionId(request.sessionId());
        }
        // A change to DONE reports the TODO itself on save (Todo.Completed) - the audit finding follows there.
        Todo saved = todoRepository.save(todo);
        log.info("Updated todo status: {} → {} ({})", todo.getTitle(), request.status(), todo.getId());
        return saved;
    }

    @Transactional
    public TodoResponse updateStatusAndReturn(String id, UpdateTodoStatusRequest request) {
        Todo saved = updateStatus(id, request);
        return responseAssembler.toResponse(saved);
    }

    @Transactional
    public void delete(String id) {
        Todo todo = findById(id);
        todoRepository.delete(todo);
        log.info("Deleted todo: {} ({})", todo.getTitle(), todo.getId());
    }

    @Transactional(readOnly = true)
    public TodoCountResponse count(String projectId) {
        return projectId == null || projectId.isBlank()
                ? todoRepository.countGroupedByStatus()
                : todoRepository.countGroupedByStatusForProject(projectId);
    }

    /**
     * Bulk status change (e.g. archive a project's stale todos in one click).
     * Uses a single bulk UPDATE — no entities loaded, short transaction, safe under pool-size=1.
     */
    @Transactional
    public int bulkUpdateStatus(List<String> ids, Todo.Status status) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // The UPDATE loads no entities: anyone switching to DONE this way does not report themselves
        // (Todo.Completed) - so collected here so that linked audit findings follow (ab1ed481).
        List<String> completing = status == Todo.Status.DONE
                ? todoRepository.findIdsNotInStatus(ids, Todo.Status.DONE) : List.of();
        int updated = todoRepository.bulkSetStatus(ids, status, Instant.now());
        if (!completing.isEmpty()) {
            eventPublisher.publishEvent(new Todo.CompletedInBulk(completing));
        }
        log.info("Bulk updated {} todo(s) → {}", updated, status);
        return updated;
    }

    /**
     * Resolves a caller-supplied projectId to a real project id, or null.
     * <p>The chat assistant surfaces ids shortened to 8 chars ("23e55750…") and the model
     * occasionally echoes that trimmed prefix back as a projectId. An exact FK insert then
     * fails with an opaque {@code SQLITE_CONSTRAINT_FOREIGNKEY}. We recover the intended
     * project when the value uniquely prefixes exactly one project; a genuine miss throws a
     * clear {@link ResourceNotFoundException} instead of the raw SQLite error. Mirrors
     * {@link #validateLinkedWorkflowId(String)} for the {@code project_id → projects(id)} FK.
     */
    /**
     * Accepts the UUID of an agent session - even if a whole resume command arrives.
     *
     * <p>An agent experience-wise sends what it also writes into the description:
     * {@code claude --resume 4fde1b91-…} or the UUID in backticks. Accepting both here
     * costs three lines and prevents the field from being filled with command text instead of an identifier
     * - a field that first has to be cleaned is as unreliable as the
     * free-text convention it replaces (TODO 3497533c). Anything that does not look like a UUID
     * is discarded, not half adopted.
     */
    static String normalizeSessionRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = SESSION_REF.matcher(raw);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    private static final Pattern SESSION_REF = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private String resolveProjectId(String projectId) {
        if (projectId == null) {
            return null;
        }
        String normalized = projectId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (projectRepository.existsById(normalized)) {
            return normalized;
        }
        List<Project> prefixMatches = projectRepository.findByIdStartingWith(normalized);
        if (prefixMatches.size() == 1) {
            return prefixMatches.get(0).getId();
        }
        throw new ResourceNotFoundException("Project", projectId);
    }

    private String validateLinkedWorkflowId(String linkedWorkflowId) {
        if (linkedWorkflowId == null) {
            return null;
        }
        String normalized = linkedWorkflowId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (!workflowRepository.existsById(normalized)) {
            throw new ResourceNotFoundException("Workflow", normalized);
        }
        return normalized;
    }

}
