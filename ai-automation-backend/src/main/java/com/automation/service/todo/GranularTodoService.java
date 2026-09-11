package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.automation.service.todo.AutoTodoService.REFERENCE_TYPE;
import static com.automation.service.todo.AutoTodoService.SOURCE_AGENT;
import static com.automation.service.todo.AutoTodoService.TAG_AUTO_WORKFLOW;

/**
 * Keeps the granular finding TODOs of a workflow ({@code todo_items} in the run metadata) in sync:
 * one TODO per item (composite key {@code item:<title-hash>}), updated in place when reported
 * again, created otherwise, closed when no longer reported. Each DB access is its own short
 * transaction; the AI similarity check runs outside any transaction (pool-size=1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GranularTodoService {

    private final TodoRepository todoRepository;
    private final TodoSimilarityService similarityService;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AutoTodoTextBuilder textBuilder;

    /** One reported finding, parsed from the {@code todo_items} map. */
    record Item(String title, Todo.Priority priority, String tagsJson, String description) {
        String compositeTag() {
            return "item:" + title.hashCode();
        }
    }

    public boolean hasItems(WorkflowRunCompletedEvent event) {
        return event.getRunMetadata().get("todo_items") instanceof List<?> list && !list.isEmpty();
    }

    public void syncItems(String projectId, WorkflowRunCompletedEvent event, GateResult gate) {
        List<Item> items = parseItems(event, gate);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Item item : items) {
            upsert(tx, projectId, event, item);
        }
        closeStale(tx, projectId, event, items.stream().map(Item::title).toList());
        log.info("Auto-todo (granular): created/updated {} items for workflow {}", items.size(), event.getWorkflowName());
    }

    private List<Item> parseItems(WorkflowRunCompletedEvent event, GateResult gate) {
        Object raw = event.getRunMetadata().get("todo_items");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Item> items = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) continue;
            String title = String.valueOf(map.get("title"));
            List<String> tags = new ArrayList<>(map.get("tags") instanceof List<?> tagList
                    ? tagList.stream().map(String::valueOf).toList() : List.of());
            tags.add(TAG_AUTO_WORKFLOW);
            tags.add("item:" + title.hashCode());
            if (gate != null && gate.category() != null) {
                tags.add("category:" + gate.category());
            }
            String description = map.get("description") != null ? String.valueOf(map.get("description")) : null;
            items.add(new Item(title, parsePriority(map.get("priority")), TodoMapper.listToJson(tags),
                    textBuilder.buildGranularDescription(description, event)));
        }
        return items;
    }

    private static Todo.Priority parsePriority(Object raw) {
        try {
            return Todo.Priority.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Todo.Priority.MEDIUM;
        }
    }

    private void upsert(TransactionTemplate tx, String projectId, WorkflowRunCompletedEvent event, Item item) {
        Optional<Todo> existing = tx.execute(status ->
                todoRepository.findOpenByCompositeKey(projectId, REFERENCE_TYPE, event.getWorkflowId(), item.compositeTag()));
        if (existing != null && existing.isPresent()) {
            tx.execute(status -> {
                Todo todo = existing.get();
                todo.setDescription(item.description());
                todo.setWorkflowRunId(event.getRunId());
                todo.setPriority(item.priority());
                todo.setTags(item.tagsJson());
                return todoRepository.save(todo);
            });
            log.debug("Auto-todo (granular) updated: {}", item.title());
            return;
        }
        // No transaction: the AI similarity check must not hold the single DB connection.
        Todo related = similarityService.findSimilar(projectId, item.title(), null).stream()
                .filter(s -> s.similarity() == TodoSimilarityService.Similarity.SAME
                        || s.similarity() == TodoSimilarityService.Similarity.RELATED)
                .map(TodoSimilarityService.SimilarTodo::todo)
                .findFirst().orElse(null);
        tx.execute(status -> {
            Todo saved = todoRepository.save(Todo.builder()
                    .title(item.title()).description(item.description())
                    .status(Todo.Status.OPEN).priority(item.priority()).tags(item.tagsJson())
                    .projectId(projectId).source(Todo.Source.WORKFLOW).sourceAgent(SOURCE_AGENT)
                    .workflowRunId(event.getRunId()).referenceType(REFERENCE_TYPE).referenceId(event.getWorkflowId())
                    .relatedTodoId(related != null ? related.getId() : null)
                    .build());
            eventPublisher.publishEvent(new TodoCreatedEvent(this, saved));
            log.info("Auto-todo (granular) created: {}{}", item.title(),
                    related != null ? " (related to: " + related.getTitle() + ")" : "");
            return saved;
        });
    }

    private void closeStale(TransactionTemplate tx, String projectId, WorkflowRunCompletedEvent event, List<String> currentTitles) {
        tx.execute(status -> {
            List<Todo> open = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    projectId, Todo.Source.WORKFLOW, REFERENCE_TYPE, event.getWorkflowId(), Todo.Status.DONE);
            for (Todo todo : open) {
                boolean granular = todo.getTags() != null && todo.getTags().contains("item:")
                        && todo.getTags().contains(TAG_AUTO_WORKFLOW);
                if (granular && !currentTitles.contains(todo.getTitle())) {
                    todo.setStatus(Todo.Status.DONE);
                    todoRepository.save(todo);
                    log.info("Auto-todo (granular) closed — no longer in findings: {}", todo.getTitle());
                }
            }
            return null;
        });
    }
}
