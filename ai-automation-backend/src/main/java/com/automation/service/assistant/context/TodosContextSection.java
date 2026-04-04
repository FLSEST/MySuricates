package com.automation.service.assistant.context;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TodosContextSection implements AssistantContextSection {

    private final TodoRepository todoRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<Todo> open = todoRepository.findAll().stream()
                    .filter(t -> t.getStatus() != Todo.Status.DONE)
                    .collect(Collectors.toList());
            if (compact) {
                if (!open.isEmpty()) {
                    long high   = open.stream().filter(t -> t.getPriority() == Todo.Priority.HIGH).count();
                    long medium = open.stream().filter(t -> t.getPriority() == Todo.Priority.MEDIUM).count();
                    long low    = open.stream().filter(t -> t.getPriority() == Todo.Priority.LOW).count();
                    sb.append("**Todos:** ").append(open.size()).append(" offen (")
                            .append(high).append(" HIGH, ").append(medium).append(" MEDIUM, ")
                            .append(low).append(" LOW)\n");
                }
                return;
            }
            List<Todo> top = open.stream()
                    .sorted((a, b) -> b.getPriority().compareTo(a.getPriority()))
                    .limit(10)
                    .collect(Collectors.toList());
            if (!top.isEmpty()) {
                sb.append("**Offene Todos (").append(top.size()).append("):**\n");
                top.forEach(t -> sb.append("  - [").append(t.getPriority()).append("] ")
                        .append(t.getTitle()).append("\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load todos for assistant context", e);
        }
    }
}
