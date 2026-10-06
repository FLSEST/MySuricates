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
import java.util.Locale;
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

    /** Minimum length of a truncated title to count as a fragment. */
    private static final int PREFIX_MIN_LENGTH = 20;

    private final TodoRepository todoRepository;
    private final TodoSimilarityService similarityService;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AutoTodoTextBuilder textBuilder;

    /** One reported finding, parsed from the {@code todo_items} map. */
    record Item(String title, Todo.Priority priority, String tagsJson, String description) {
        static String compositeTag(String title) {
            return "item:" + title.hashCode();
        }

        String compositeTag() {
            return compositeTag(title);
        }
    }

    public boolean hasItems(WorkflowRunCompletedEvent event) {
        return event.getRunMetadata().get("todo_items") instanceof List<?> list && !list.isEmpty();
    }

    /** Call only when {@link #hasItems(WorkflowRunCompletedEvent)} is true — an empty item list closes every open granular TODO of the workflow. */
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
            tags.add(Item.compositeTag(title));
            if (gate != null && gate.category() != null) {
                tags.add("category:" + gate.category());
            }
            String description = map.get("description") != null ? String.valueOf(map.get("description")) : null;
            items.add(new Item(title, parsePriority(map.get("priority")), TodoMapper.listToJson(tags),
                    textBuilder.buildGranularDescription(description, event)));
        }
        return deduplicate(items);
    }

    /**
     * One finding, two lines: wrapper blocks (compression, passing on an output) shorten
     * or distort titles, which made the same line produce a second TODO (TODO 8d02ed3f).
     * The normalised title is compared; of two duplicates the longer one wins, because the
     * shorter one is as a rule the truncated one.
     */
    private static List<Item> deduplicate(List<Item> items) {
        List<Item> kept = new ArrayList<>();
        for (Item candidate : items) {
            String normalized = normalizeTitle(candidate.title());
            int existing = indexOfDuplicate(kept, candidate);
            if (existing < 0) {
                kept.add(candidate);
            } else if (normalizeTitle(kept.get(existing).title()).length() < normalized.length()) {
                log.info("Auto-todo (granular): '{}' ist die gekuerzte Fassung von '{}' — nur die laengere zaehlt",
                        kept.get(existing).title(), candidate.title());
                kept.set(existing, candidate);
            } else {
                log.info("Auto-todo (granular): '{}' ist eine Dublette von '{}' — uebersprungen",
                        candidate.title(), kept.get(existing).title());
            }
        }
        return kept;
    }

    private static int indexOfDuplicate(List<Item> kept, Item candidate) {
        for (int i = 0; i < kept.size(); i++) {
            if (isDuplicate(kept.get(i), candidate)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Same normalised title - or the shorter one is demonstrably the truncated version
     * of the longer one. Demonstrably means: it ends in "…"/"..." <em>and</em> is at least
     * {@link #PREFIX_MIN_LENGTH} characters long.
     *
     * <p>Both conditions protect an independent finding from being discarded - the
     * rule deletes a whole row including priority, tags and description. "Fix error in module A"
     * is a prefix of "Fix error in module A, part 2", but not a fragment,
     * rather a task of its own; without the truncation mark both stay. And a title
     * that breaks off after only a few characters is not good as proof of a fragment either.
     */
    private static boolean isDuplicate(Item a, Item b) {
        String normalizedA = normalizeTitle(a.title());
        String normalizedB = normalizeTitle(b.title());
        if (normalizedA.equals(normalizedB)) {
            return true;
        }
        boolean aIsShorter = normalizedA.length() <= normalizedB.length();
        Item shorter = aIsShorter ? a : b;
        String shorterTitle = aIsShorter ? normalizedA : normalizedB;
        String longerTitle = aIsShorter ? normalizedB : normalizedA;
        return wasTruncated(shorter.title())
                && shorterTitle.length() >= PREFIX_MIN_LENGTH
                && longerTitle.startsWith(shorterTitle);
    }

    /** If the title has a truncation mark, it is a fragment and not a task of its own. */
    private static boolean wasTruncated(String title) {
        String trimmed = title == null ? "" : title.trim();
        return trimmed.endsWith("…") || trimmed.endsWith("...");
    }

    /** Trim, Whitespace-Folgen zu einem Leerzeichen, Kleinschreibung, Kuerzungssuffix („…", „...") weg. */
    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String normalized = title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        while (normalized.endsWith("…") || normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - (normalized.endsWith("…") ? 1 : 3)).trim();
        }
        return normalized;
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

    /** Closes, in one transaction, all granular TODOs no longer reported, collected via saveAll (TODO dfb3fee8). */
    private void closeStale(TransactionTemplate tx, String projectId, WorkflowRunCompletedEvent event, List<String> currentTitles) {
        tx.execute(status -> {
            List<Todo> open = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    projectId, Todo.Source.WORKFLOW, REFERENCE_TYPE, event.getWorkflowId(), Todo.Status.DONE);
            List<Todo> closed = new ArrayList<>();
            for (Todo todo : open) {
                boolean granular = todo.getTags() != null && todo.getTags().contains("item:")
                        && todo.getTags().contains(TAG_AUTO_WORKFLOW);
                if (granular && !currentTitles.contains(todo.getTitle())) {
                    todo.setStatus(Todo.Status.DONE);
                    closed.add(todo);
                    log.info("Auto-todo (granular) closed — no longer in findings: {}", todo.getTitle());
                }
            }
            if (!closed.isEmpty()) {
                todoRepository.saveAll(closed);
            }
            return null;
        });
    }
}
