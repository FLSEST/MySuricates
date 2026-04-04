package com.automation.service.assistant.context;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodosContextSectionTest {

    @Mock private TodoRepository todoRepository;

    private TodosContextSection section;

    @BeforeEach
    void setUp() {
        section = new TodosContextSection(todoRepository);
    }

    private Todo buildTodo(String title, Todo.Priority priority, Todo.Status status) {
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setPriority(priority);
        todo.setStatus(status);
        return todo;
    }

    @Test
    void compact_showsCounts() {
        when(todoRepository.findAll()).thenReturn(List.of(
                buildTodo("Fix bug", Todo.Priority.HIGH, Todo.Status.OPEN),
                buildTodo("Update docs", Todo.Priority.LOW, Todo.Status.OPEN),
                buildTodo("Done task", Todo.Priority.MEDIUM, Todo.Status.DONE)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString())
                .contains("2 offen")
                .contains("1 HIGH")
                .contains("0 MEDIUM")
                .contains("1 LOW");
    }

    @Test
    void compact_noOpen_appendsNothing() {
        when(todoRepository.findAll()).thenReturn(List.of(
                buildTodo("Done", Todo.Priority.HIGH, Todo.Status.DONE)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsTodosWithPriority() {
        when(todoRepository.findAll()).thenReturn(List.of(
                buildTodo("Fix critical bug", Todo.Priority.HIGH, Todo.Status.OPEN),
                buildTodo("Nice to have", Todo.Priority.LOW, Todo.Status.OPEN)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Offene Todos (2):")
                .contains("[HIGH] Fix critical bug")
                .contains("[LOW] Nice to have");
    }

    @Test
    void full_sortsByPriorityDescending() {
        when(todoRepository.findAll()).thenReturn(List.of(
                buildTodo("Low", Todo.Priority.LOW, Todo.Status.OPEN),
                buildTodo("High", Todo.Priority.HIGH, Todo.Status.OPEN),
                buildTodo("Medium", Todo.Priority.MEDIUM, Todo.Status.OPEN)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String result = sb.toString();
        int highPos = result.indexOf("[HIGH]");
        int medPos = result.indexOf("[MEDIUM]");
        int lowPos = result.indexOf("[LOW]");
        assertThat(highPos).isLessThan(medPos);
        assertThat(medPos).isLessThan(lowPos);
    }

    @Test
    void full_limitsTo10() {
        List<Todo> todos = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            todos.add(buildTodo("Todo " + i, Todo.Priority.MEDIUM, Todo.Status.OPEN));
        }
        when(todoRepository.findAll()).thenReturn(todos);

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("Offene Todos (10):");
    }

    @Test
    void full_filtersDone() {
        when(todoRepository.findAll()).thenReturn(List.of(
                buildTodo("Open", Todo.Priority.HIGH, Todo.Status.OPEN),
                buildTodo("Done", Todo.Priority.HIGH, Todo.Status.DONE)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Open")
                .doesNotContain("Done");
    }

    @Test
    void full_noOpen_appendsNothing() {
        when(todoRepository.findAll()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void exception_doesNotThrow() {
        when(todoRepository.findAll()).thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);
        // Should not throw, just log
        assertThat(sb.toString()).isEmpty();
    }
}
