package com.automation.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFindingTodoTest {

    @Test
    void aTodoIsLinkedToAFindingAtMostOnce() {
        UniqueConstraint constraint = AuditFindingTodo.class.getAnnotation(Table.class).uniqueConstraints()[0];

        assertThat(constraint.name()).isEqualTo("uq_audit_finding_todo");
        assertThat(constraint.columnNames()).containsExactly("finding_id", "todo_id");
    }

    @Test
    void managed_defaultsToFalse_aManualLinkIsNotManaged() {
        AuditFindingTodo link = AuditFindingTodo.builder().findingId("f1").todoId("t1").build();

        assertThat(link.isManaged()).isFalse();
    }

    @Test
    void managed_canBePromoted() {
        // AuditFindingMerger lifts an existing link to managed when the duplicate was managed.
        AuditFindingTodo link = AuditFindingTodo.builder().findingId("f1").todoId("t1").build();
        link.setManaged(true);

        assertThat(link.isManaged()).isTrue();
    }
}
