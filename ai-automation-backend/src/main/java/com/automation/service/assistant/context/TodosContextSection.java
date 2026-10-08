package com.automation.service.assistant.context;

import com.automation.dto.projection.PriorityCount;
import com.automation.dto.projection.TodoHeadline;
import com.automation.entity.Todo;
import com.automation.repository.TodoSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The open TODOs in the assistant's system prompt, built for every chat message.
 *
 * <p>"Open" means OPEN and IN_PROGRESS, as in the {@code open_todos} context variable of workflows;
 * BACKLOG, DONE and ARCHIVED stay out. Each mode asks the database for exactly what it prints – the
 * counts per priority, or the most urgent titles – instead of loading every TODO with its
 * description and filtering in memory (TODO f1074d2e): with pool-size 1 that held the only
 * connection, and it grew with every finished TODO.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodosContextSection implements AssistantContextSection {

    /** Same definition as the {@code open_todos} context variable ({@code OpenTodosVariable}). */
    static final List<Todo.Status> OPEN_STATUSES = List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS);

    /** Titles listed in full mode. */
    static final int MAX_LISTED = 10;

    /** Order of the counts in compact mode: most urgent first. */
    private static final List<Todo.Priority> PRIORITIES_DESCENDING =
            List.of(Todo.Priority.CRITICAL, Todo.Priority.HIGH, Todo.Priority.MEDIUM, Todo.Priority.LOW);

    private final TodoSummaryRepository todoSummaryRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            if (compact) {
                appendCounts(sb);
            } else {
                appendHeadlines(sb);
            }
        } catch (Exception e) {
            log.debug("Could not load todos for assistant context", e);
        }
    }

    private void appendCounts(StringBuilder sb) {
        Map<Todo.Priority, Long> counts = new EnumMap<>(Todo.Priority.class);
        for (PriorityCount row : todoSummaryRepository.countByPriority(OPEN_STATUSES)) {
            counts.merge(row.priority(), row.cnt(), Long::sum);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return;
        }
        sb.append("**Todos:** ").append(total).append(" open (");
        for (int i = 0; i < PRIORITIES_DESCENDING.size(); i++) {
            Todo.Priority priority = PRIORITIES_DESCENDING.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(counts.getOrDefault(priority, 0L)).append(' ').append(priority);
        }
        sb.append(")\n");
    }

    private void appendHeadlines(StringBuilder sb) {
        List<TodoHeadline> top = todoSummaryRepository.findHeadlines(OPEN_STATUSES, PageRequest.of(0, MAX_LISTED));
        if (top.isEmpty()) {
            return;
        }
        sb.append("**Open Todos (").append(top.size()).append("):**\n");
        top.forEach(t -> sb.append("  - [").append(t.priority()).append("] ")
                .append(t.title()).append("\n"));
        sb.append("\n");
    }
}
