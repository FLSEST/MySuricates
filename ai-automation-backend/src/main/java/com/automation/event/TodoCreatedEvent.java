package com.automation.event;

import com.automation.entity.Todo;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class TodoCreatedEvent extends ApplicationEvent {

    private final Todo todo;

    public TodoCreatedEvent(Object source, Todo todo) {
        super(source);
        this.todo = todo;
    }
}
