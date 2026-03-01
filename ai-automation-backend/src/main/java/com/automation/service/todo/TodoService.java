package com.automation.service.todo;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TodoService {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final TodoMapper todoMapper;

    private static final Comparator<Todo> PRIORITY_COMPARATOR =
            Comparator.comparingInt((Todo t) -> switch (t.getPriority()) {
                case HIGH -> 0;
                case MEDIUM -> 1;
                case LOW -> 2;
            }).thenComparing(Todo::getCreatedAt, Comparator.reverseOrder());

    @Transactional(readOnly = true)
    public PageResponse<TodoResponse> findAll(int page, int size, String projectId, String status, String priority, String source, String tag, String q) {
        Specification<Todo> spec = buildSpec(projectId, status, priority, source, tag, q);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.asc("priority"), Sort.Order.desc("createdAt")));
        Page<Todo> result = todoRepository.findAll(spec, pageRequest);
        List<TodoResponse> content = result.getContent().stream().map(this::toResponseWithProject).toList();
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
                .source(request.source() != null ? request.source() : Todo.Source.USER)
                .sourceAgent(request.sourceAgent())
                .workflowRunId(request.workflowRunId())
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .build();

        Todo saved = todoRepository.save(todo);
        log.info("Created todo: {} ({})", saved.getTitle(), saved.getId());
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
        if (request.referenceType() != null) todo.setReferenceType(request.referenceType());
        if (request.referenceId() != null) todo.setReferenceId(request.referenceId());

        Todo saved = todoRepository.save(todo);
        log.info("Updated todo: {} ({})", saved.getTitle(), saved.getId());
        return saved;
    }

    @Transactional
    public Todo updateStatus(String id, Todo.Status newStatus) {
        Todo todo = findById(id);
        todo.setStatus(newStatus);
        Todo saved = todoRepository.save(todo);
        log.info("Updated todo status: {} → {} ({})", todo.getTitle(), newStatus, todo.getId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Todo todo = findById(id);
        todoRepository.delete(todo);
        log.info("Deleted todo: {} ({})", todo.getTitle(), todo.getId());
    }

    @Transactional(readOnly = true)
    public TodoCountResponse count() {
        long open = todoRepository.countByStatus(Todo.Status.OPEN);
        long inProgress = todoRepository.countByStatus(Todo.Status.IN_PROGRESS);
        long done = todoRepository.countByStatus(Todo.Status.DONE);
        long backlog = todoRepository.countByStatus(Todo.Status.BACKLOG);
        return new TodoCountResponse(open, inProgress, done, backlog);
    }

    private TodoResponse toResponseWithProject(Todo todo) {
        TodoResponse base = todoMapper.toResponse(todo);
        String projectName = null;
        if (todo.getProjectId() != null) {
            projectName = projectRepository.findById(todo.getProjectId())
                    .map(p -> p.getName())
                    .orElse(null);
        }
        return new TodoResponse(
                base.id(), base.title(), base.description(),
                base.status(), base.priority(), base.tags(),
                base.projectId(), projectName,
                base.source(), base.sourceAgent(), base.workflowRunId(),
                base.referenceType(), base.referenceId(),
                base.createdAt(), base.updatedAt()
        );
    }

    private Specification<Todo> buildSpec(String projectId, String status, String priority, String source, String tag, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (projectId != null && !projectId.isBlank()) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), Todo.Status.valueOf(status)));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(cb.equal(root.get("priority"), Todo.Priority.valueOf(priority)));
            }
            if (source != null && !source.isBlank()) {
                predicates.add(cb.equal(root.get("source"), Todo.Source.valueOf(source)));
            }
            if (tag != null && !tag.isBlank()) {
                predicates.add(cb.like(root.get("tags"), "%" + tag + "%"));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
