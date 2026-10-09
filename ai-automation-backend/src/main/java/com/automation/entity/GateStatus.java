package com.automation.entity;

public enum GateStatus {
    GREEN, YELLOW, RED;

    /**
     * Priority of the auto-todo a gate of this status creates or updates — the one mapping for every
     * auto-todo mode. GREEN only reaches a todo in track mode; auto and approval mode close instead.
     */
    public Todo.Priority todoPriority() {
        return switch (this) {
            case RED -> Todo.Priority.HIGH;
            case YELLOW -> Todo.Priority.MEDIUM;
            case GREEN -> Todo.Priority.LOW;
        };
    }
}
