package com.automation.event;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TodoCreatedEventTest {

    private static final Object SOURCE = new Object();

    // --- Constructor & Getters ---

    @Test
    void should_returnTodo_when_constructed() {
        Todo todo = Todo.builder().id("todo-1").title("Test Todo").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getTodo()).isSameAs(todo);
    }

    @Test
    void should_returnSource_when_constructed() {
        Todo todo = Todo.builder().id("todo-1").title("Test Todo").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getSource()).isSameAs(SOURCE);
    }

    @Test
    void should_acceptNullTodo_when_constructed() {
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, null);
        assertThat(event.getTodo()).isNull();
        assertThat(event.getSource()).isSameAs(SOURCE);
    }

    @Test
    void should_throwIllegalArgumentException_when_sourceIsNull() {
        Todo todo = Todo.builder().id("todo-1").title("Test Todo").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TodoCreatedEvent(null, todo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_returnDifferentSources_when_constructedWithDifferentSources() {
        Todo todo = Todo.builder().id("todo-1").title("Test Todo").build();
        Object source1 = new Object();
        Object source2 = new Object();
        TodoCreatedEvent event1 = new TodoCreatedEvent(source1, todo);
        TodoCreatedEvent event2 = new TodoCreatedEvent(source2, todo);
        assertThat(event1.getSource()).isNotSameAs(event2.getSource());
    }

    // --- Todo-Felder durchreichen ---

    @Test
    void should_exposeTodoTitle_when_constructed() {
        Todo todo = Todo.builder().id("todo-1").title("My Title").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getTodo().getTitle()).isEqualTo("My Title");
    }

    @Test
    void should_exposeTodoId_when_constructed() {
        Todo todo = Todo.builder().id("abc-123").title("Test").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getTodo().getId()).isEqualTo("abc-123");
    }

    @Test
    void should_exposeTodoStatus_when_constructed() {
        Todo todo = Todo.builder().id("id").title("Test").status(Todo.Status.OPEN).build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getTodo().getStatus()).isEqualTo(Todo.Status.OPEN);
    }

    // --- Immutability ---

    @Test
    void should_returnSameTodoReference_when_calledMultipleTimes() {
        Todo todo = Todo.builder().id("todo-1").title("Test").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event.getTodo()).isSameAs(event.getTodo());
    }

    @Test
    void should_reflectMutationsToTodo_when_todoIsChangedExternally() {
        // Since Todo is a mutable entity, the event holds only the reference —
        // changes to the Todo are visible (no defensive copying).
        Todo todo = Todo.builder().id("id").title("Original").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        todo.setTitle("Changed");
        assertThat(event.getTodo().getTitle()).isEqualTo("Changed");
    }

    // --- ApplicationEvent-Vererbung ---

    @Test
    void should_setTimestamp_when_constructed() {
        Instant before = Instant.now().minusMillis(100);
        Todo todo = Todo.builder().id("id").title("Test").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        Instant after = Instant.now().plusMillis(100);
        assertThat(event.getTimestamp()).isBetween(before.toEpochMilli(), after.toEpochMilli());
    }

    @Test
    void should_extendApplicationEvent() {
        Todo todo = Todo.builder().id("id").title("Test").build();
        TodoCreatedEvent event = new TodoCreatedEvent(SOURCE, todo);
        assertThat(event).isInstanceOf(org.springframework.context.ApplicationEvent.class);
    }
}
