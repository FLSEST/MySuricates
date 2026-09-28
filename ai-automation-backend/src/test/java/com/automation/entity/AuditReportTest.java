package com.automation.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditReportTest {

    @Test
    void builder_setsAllFields() {
        Instant at = Instant.parse("2026-09-12T10:30:00Z");
        AuditReport report = AuditReport.builder().id("r1").projectId("p1").workflowId("wf1")
                .workflowRunId("run1").auditKey("mysuricates.health").category("health").title("Health")
                .markdownContent("# Health").gateStatus(GateStatus.YELLOW).gateDescription("2 Warnungen")
                .provider("CLAUDE").model("opus").workflowVersion("7")
                .ingestionStatus(AuditIngestionStatus.INVALID).ingestionError("kein Envelope")
                .generatedAt(at).build();

        assertThat(report.getWorkflowRunId()).isEqualTo("run1");
        assertThat(report.getGateStatus()).isEqualTo(GateStatus.YELLOW);
        assertThat(report.getIngestionStatus()).isEqualTo(AuditIngestionStatus.INVALID);
        assertThat(report.getIngestionError()).isEqualTo("kein Envelope");
        assertThat(report.getGeneratedAt()).isEqualTo(at);
        assertThat(report.getCreatedAt()).as("setzt erst der AuditTimestampListener").isNull();
    }

    @Test
    void workflowRunId_isUnique_oneReportPerRun() throws Exception {
        Column column = AuditReport.class.getDeclaredField("workflowRunId").getAnnotation(Column.class);

        assertThat(column.unique()).isTrue();
        assertThat(column.nullable()).isFalse();
    }

    @Test
    void gateStatus_isOptional_invalidReportsHaveNone() throws Exception {
        Column column = AuditReport.class.getDeclaredField("gateStatus").getAnnotation(Column.class);

        assertThat(column.nullable()).isTrue();
    }
}
