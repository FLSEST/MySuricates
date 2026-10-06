package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSeverityTest {

    @Test
    void values_areOrderedFromMostToLeastSevere() {
        assertThat(AuditSeverity.values()).containsExactly(
                AuditSeverity.CRITICAL, AuditSeverity.HIGH, AuditSeverity.MEDIUM, AuditSeverity.LOW);
    }

    @Test
    void everySeverity_mapsOntoATodoPriorityOfTheSameName() {
        // ManagedTodoWriter/AuditCenterService map via Todo.Priority.valueOf(severity.name()) —
        // a severity value without a priority of the same name would make creating the TODO fail.
        for (AuditSeverity severity : AuditSeverity.values()) {
            assertThat(Todo.Priority.valueOf(severity.name()).name()).isEqualTo(severity.name());
        }
    }
}
