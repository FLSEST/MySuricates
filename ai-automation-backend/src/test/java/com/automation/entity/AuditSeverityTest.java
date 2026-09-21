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
        // ManagedTodoWriter/AuditCenterService bilden per Todo.Priority.valueOf(severity.name()) ab —
        // ein Severity-Wert ohne gleichnamige Priority liesse das Anlegen des TODOs scheitern.
        for (AuditSeverity severity : AuditSeverity.values()) {
            assertThat(Todo.Priority.valueOf(severity.name()).name()).isEqualTo(severity.name());
        }
    }
}
