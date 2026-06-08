package com.automation.service.todo;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import jakarta.persistence.criteria.Predicate;
import com.automation.event.TodoCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TodoService {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final TodoMapper todoMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final Comparator<Todo> PRIORITY_COMPARATOR =
            Comparator.comparingInt((Todo t) -> switch (t.getPriority()) {
                case CRITICAL -> 0;
                case HIGH -> 1;
                case MEDIUM -> 2;
                case LOW -> 3;
            }).thenComparing(Todo::getCreatedAt, Comparator.reverseOrder());

    @Transactional(readOnly = true)
    public PageResponse<TodoResponse> findAll(int page, int size, String projectId, List<String> status, String priority, String source, String tag, String q) {
        Specification<Todo> spec = buildSpec(projectId, status, priority, source, tag, q);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.asc("priority"), Sort.Order.desc("createdAt")));
        Page<Todo> result = todoRepository.findAll(spec, pageRequest);

        // Batch-load all project names in one query instead of N+1
        Set<String> projectIds = result.getContent().stream()
                .map(Todo::getProjectId)
                .filter(pid -> pid != null && !pid.isBlank())
                .collect(Collectors.toSet());
        Set<String> linkedWorkflowIds = result.getContent().stream()
                .map(Todo::getLinkedWorkflowId)
                .filter(wid -> wid != null && !wid.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> projectNames = projectIds.isEmpty()
                ? Map.of()
                : projectRepository.findAllById(projectIds).stream()
                    .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));
        Map<String, String> workflowNames = linkedWorkflowIds.isEmpty()
                ? Map.of()
                : workflowRepository.findAllById(linkedWorkflowIds).stream()
                    .collect(Collectors.toMap(w -> w.getId(), w -> w.getName()));

        List<TodoResponse> content = result.getContent().stream()
                .map(todo -> toResponse(
                        todo,
                        todo.getProjectId() != null ? projectNames.get(todo.getProjectId()) : null,
                        todo.getLinkedWorkflowId() != null ? workflowNames.get(todo.getLinkedWorkflowId()) : null))
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Todo findById(String id) {
        return todoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Todo", id));
    }

    @Transactional(readOnly = true)
    public TodoResponse findByIdResponse(String id) {
        return toResponseWithProject(findById(id));
    }

    @Transactional
    public Todo create(CreateTodoRequest request) {
        Todo todo = Todo.builder()
                .title(request.title())
                .description(request.description())
                .status(request.status() != null ? request.status() : Todo.Status.OPEN)
                .priority(request.priority() != null ? request.priority() : Todo.Priority.MEDIUM)
                .tags(TodoMapper.listToJson(request.tags()))
                .projectId(request.projectId())
                .linkedWorkflowId(validateLinkedWorkflowId(request.linkedWorkflowId()))
                .source(request.source() != null ? request.source() : Todo.Source.USER)
                .sourceAgent(request.sourceAgent())
                .sessionId(request.sessionId())
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
        if (request.projectId() != null) todo.setProjectId(request.projectId().isBlank() ? null : request.projectId());
        if (request.linkedWorkflowId() != null) {
            todo.setLinkedWorkflowId(validateLinkedWorkflowId(request.linkedWorkflowId()));
            todo.setResolutionState(todo.getLinkedWorkflowId() != null ? Todo.ResolutionState.IDLE : null);
        }
        if (request.referenceType() != null) todo.setReferenceType(request.referenceType());
        if (request.referenceId() != null) todo.setReferenceId(request.referenceId());
        if (request.dueDate() != null) {
            todo.setDueDate(request.dueDate().isBlank() ? null : Instant.parse(request.dueDate()));
        }

        Todo saved = todoRepository.save(todo);
        log.info("Updated todo: {} ({})", saved.getTitle(), saved.getId());
        return saved;
    }

    @Transactional
    public TodoResponse updateAndReturn(String id, UpdateTodoRequest request) {
        Todo saved = update(id, request);
        return toResponseWithProject(saved);
    }

    @Transactional
    public Todo updateStatus(String id, UpdateTodoStatusRequest request) {
        Todo todo = findById(id);
        todo.setStatus(request.status());
        if (request.sessionId() != null) {
            todo.setSessionId(request.sessionId());
        }
        Todo saved = todoRepository.save(todo);
        log.info("Updated todo status: {} → {} ({})", todo.getTitle(), request.status(), todo.getId());
        return saved;
    }

    @Transactional
    public TodoResponse updateStatusAndReturn(String id, UpdateTodoStatusRequest request) {
        Todo saved = updateStatus(id, request);
        return toResponseWithProject(saved);
    }

    @Transactional
    public WorkflowRun resolve(String id) {
        Todo todo = findById(id);
        if (todo.getLinkedWorkflowId() == null || todo.getLinkedWorkflowId().isBlank()) {
            throw new IllegalArgumentException("Todo has no linked workflow");
        }
        if (todo.getStatus() == Todo.Status.DONE) {
            throw new IllegalStateException("Done todo does not need resolution");
        }
        if (isFixRunStillRunning(todo)) {
            throw new IllegalStateException("A resolution workflow is already running for this todo");
        }

        Todo.Status previousStatus = todo.getStatus();
        var workflow = workflowService.findByIdEager(todo.getLinkedWorkflowId());
        var runMetadata = new java.util.HashMap<String, Object>();
        runMetadata.put("linkedTodoRun", true);
        runMetadata.put("linkedTodoId", todo.getId());
        runMetadata.put("linkedTodoPreviousStatus", previousStatus.name());
        runMetadata.put("linkedTodoWorkflowId", workflow.getId());

        WorkflowRun run = workflowExecutor.execute(workflow, buildResolveContext(todo), null, runMetadata);
        todo.setStatus(Todo.Status.IN_PROGRESS);
        todo.setLastFixRunId(run.getId());
        todo.setResolutionState(Todo.ResolutionState.RUNNING);
        todoRepository.save(todo);
        log.info("Started resolution workflow {} for todo {} ({})", workflow.getName(), todo.getTitle(), todo.getId());
        return run;
    }

    @Transactional
    public void delete(String id) {
        Todo todo = findById(id);
        todoRepository.delete(todo);
        log.info("Deleted todo: {} ({})", todo.getTitle(), todo.getId());
    }

    @Transactional(readOnly = true)
    public TodoCountResponse count() {
        return todoRepository.countGroupedByStatus();
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
        int updated = todoRepository.bulkSetStatus(ids, status, Instant.now());
        log.info("Bulk updated {} todo(s) → {}", updated, status);
        return updated;
    }

    private TodoResponse toResponseWithProject(Todo todo) {
        String projectName = null;
        if (todo.getProjectId() != null) {
            projectName = projectRepository.findById(todo.getProjectId())
                    .map(p -> p.getName())
                    .orElse(null);
        }
        String linkedWorkflowName = null;
        if (todo.getLinkedWorkflowId() != null) {
            linkedWorkflowName = workflowRepository.findById(todo.getLinkedWorkflowId())
                    .map(w -> w.getName())
                    .orElse(null);
        }
        return toResponse(todo, projectName, linkedWorkflowName);
    }

    private TodoResponse toResponse(Todo todo, String projectName, String linkedWorkflowName) {
        TodoResponse base = todoMapper.toResponse(todo);
        return new TodoResponse(
                base.id(), base.title(), base.description(),
                base.status(), base.priority(), base.tags(),
                base.projectId(), projectName,
                base.linkedWorkflowId(), linkedWorkflowName,
                base.source(), base.sourceAgent(), base.sessionId(),
                base.workflowRunId(),
                base.referenceType(), base.referenceId(),
                base.fixAttempts(), base.lastFixRunId(),
                base.resolutionState(),
                base.dueDate(),
                base.createdAt(), base.updatedAt()
        );
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

    private boolean isFixRunStillRunning(Todo todo) {
        if (todo.getLastFixRunId() == null || todo.getLastFixRunId().isBlank()) {
            return false;
        }
        return workflowRunRepository.findById(todo.getLastFixRunId())
                .map(run -> run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PENDING)
                .orElse(false);
    }

    private java.util.Map<String, Object> buildResolveContext(Todo todo) {
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("todo_id", todo.getId());
        context.put("todo_title", todo.getTitle());
        context.put("todo_description", todo.getDescription() != null ? todo.getDescription() : "");
        context.put("todo_tags", todo.getTags() != null ? todo.getTags() : "[]");
        context.put("todo_priority", todo.getPriority().name());
        context.put("todo_project_id", todo.getProjectId() != null ? todo.getProjectId() : "");
        context.put("todo_source", todo.getSource() != null ? todo.getSource().name() : "");
        context.put("todo_reference_type", todo.getReferenceType() != null ? todo.getReferenceType() : "");
        context.put("todo_reference_id", todo.getReferenceId() != null ? todo.getReferenceId() : "");
        return context;
    }

    private Specification<Todo> buildSpec(String projectId, List<String> status, String priority, String source, String tag, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (projectId != null && !projectId.isBlank()) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (status != null && !status.isEmpty()) {
                List<Todo.Status> enums = status.stream()
                        .map(Todo.Status::valueOf)
                        .toList();
                if (enums.size() == 1) {
                    predicates.add(cb.equal(root.get("status"), enums.get(0)));
                } else {
                    predicates.add(root.get("status").in(enums));
                }
            } else {
                // ARCHIVED is "out of sight": hidden from the default list unless explicitly requested.
                predicates.add(cb.notEqual(root.get("status"), Todo.Status.ARCHIVED));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(cb.equal(root.get("priority"), Todo.Priority.valueOf(priority)));
            }
            if (source != null && !source.isBlank()) {
                predicates.add(cb.equal(root.get("source"), Todo.Source.valueOf(source)));
            }
            if (tag != null && !tag.isBlank()) {
                predicates.add(cb.like(root.get("tags"), "%" + escapeLikePattern(tag) + "%", '\\'));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLikePattern(q.toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
