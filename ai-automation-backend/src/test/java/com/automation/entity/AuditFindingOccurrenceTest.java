package com.automation.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFindingOccurrenceTest {

    @Test
    void anOccurrenceIsUniquePerFindingAndReport() {
        UniqueConstraint constraint = AuditFindingOccurrence.class.getAnnotation(Table.class).uniqueConstraints()[0];

        assertThat(constraint.name()).isEqualTo("uq_audit_occurrence");
        assertThat(constraint.columnNames()).containsExactly("finding_id", "report_id");
    }

    @Test
    void builder_snapshotsTheFindingAttributesOfThatRun() {
        Instant at = Instant.parse("2026-09-12T10:30:00Z");
        AuditFindingOccurrence occurrence = AuditFindingOccurrence.builder().findingId("f1").reportId("r1")
                .severity(AuditSeverity.LOW).title("t").description("d").evidence("e").tags("[\"x\"]")
                .observedAt(at).build();

        assertThat(occurrence.getFindingId()).isEqualTo("f1");
        assertThat(occurrence.getReportId()).isEqualTo("r1");
        assertThat(occurrence.getSeverity()).isEqualTo(AuditSeverity.LOW);
        assertThat(occurrence.getTags()).isEqualTo("[\"x\"]");
        assertThat(occurrence.getObservedAt()).isEqualTo(at);
    }
}
