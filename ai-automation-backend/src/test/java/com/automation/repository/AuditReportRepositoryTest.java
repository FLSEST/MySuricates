package com.automation.repository;

import com.automation.dto.projection.AuditReportSummaryRow;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.GateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditReportRepositoryTest {

    private static final String AUDIT_KEY = "test.report-repo";
    private static final Instant T1 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-08T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired private AuditReportRepository reportRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        projectId = "p-" + UUID.randomUUID();
    }

    private AuditReport save(String project, String auditKey, Instant at, AuditIngestionStatus status) {
        return reportRepository.save(AuditReport.builder()
                .projectId(project).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey(auditKey).category("health").title("Health")
                .gateStatus(status == AuditIngestionStatus.VALID ? GateStatus.GREEN : null)
                .ingestionStatus(status).generatedAt(at).build());
    }

    @Test
    void existsByWorkflowRunId_findsOnlyIngestedRuns() {
        AuditReport report = save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);

        assertThat(reportRepository.existsByWorkflowRunId(report.getWorkflowRunId())).isTrue();
        assertThat(reportRepository.existsByWorkflowRunId("run-never-ingested")).isFalse();
    }

    @Test
    void findTopValid_skipsANewerInvalidAttempt() {
        AuditReport valid = save(projectId, AUDIT_KEY, T2, AuditIngestionStatus.VALID);
        save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        save(projectId, AUDIT_KEY, T3, AuditIngestionStatus.INVALID);

        assertThat(reportRepository.findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                projectId, AUDIT_KEY, AuditIngestionStatus.VALID)).get().extracting(AuditReport::getId)
                .isEqualTo(valid.getId());
    }

    @Test
    void findTopAttempt_returnsTheNewestRegardlessOfStatus_andIsScopedToProjectAndKey() {
        save(projectId, AUDIT_KEY, T2, AuditIngestionStatus.VALID);
        AuditReport invalid = save(projectId, AUDIT_KEY, T3, AuditIngestionStatus.INVALID);
        save("p-other-" + UUID.randomUUID(), AUDIT_KEY, T3.plusSeconds(60), AuditIngestionStatus.VALID);
        save(projectId, "test.other-key", T3.plusSeconds(60), AuditIngestionStatus.VALID);

        assertThat(reportRepository.findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc(projectId, AUDIT_KEY))
                .get().extracting(AuditReport::getId).isEqualTo(invalid.getId());
        assertThat(reportRepository.findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc(projectId, "test.none"))
                .isEmpty();
    }

    @Test
    void pagedValidHistory_isNewestFirstAndExcludesInvalid() {
        AuditReport oldest = save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        AuditReport middle = save(projectId, AUDIT_KEY, T2, AuditIngestionStatus.VALID);
        save(projectId, AUDIT_KEY, T3, AuditIngestionStatus.INVALID);

        Page<AuditReport> page = reportRepository.findByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                projectId, AUDIT_KEY, AuditIngestionStatus.VALID, PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(AuditReport::getId).containsExactly(middle.getId(), oldest.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void pagedHistory_includesInvalidAttempts_andPages() {
        save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        AuditReport middle = save(projectId, AUDIT_KEY, T2, AuditIngestionStatus.VALID);
        AuditReport newest = save(projectId, AUDIT_KEY, T3, AuditIngestionStatus.INVALID);

        Page<AuditReportSummaryRow> first = reportRepository.findSummaryRows(projectId, AUDIT_KEY, PageRequest.of(0, 2));

        assertThat(first.getContent()).extracting(AuditReportSummaryRow::id).containsExactly(newest.getId(), middle.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    /** Die Koepfe der Historie tragen Provider, Modell und Version an der richtigen Stelle, aber kein Markdown (TODO cd50d011). */
    @Test
    void summaryRows_carryEveryHeadField() {
        AuditReport stored = save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        stored.setMarkdownContent("# Body");
        stored.setGateDescription("6/10");
        stored.setProvider("CLAUDE");
        stored.setModel("opus");
        stored.setWorkflowVersion("3.0.0");
        reportRepository.save(stored);

        AuditReportSummaryRow row = reportRepository.findSummaryRows(projectId, AUDIT_KEY, PageRequest.of(0, 1))
                .getContent().getFirst();

        assertThat(row).isEqualTo(AuditReportSummaryRow.of(stored));
        assertThat(row.provider()).isEqualTo("CLAUDE");
        assertThat(row.model()).isEqualTo("opus");
        assertThat(row.workflowVersion()).isEqualTo("3.0.0");
        assertThat(row.gateDescription()).isEqualTo("6/10");
    }

    @Test
    void findByProjectId_listsAllAuditsOfTheProjectNewestFirst() {
        AuditReport a = save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        AuditReport b = save(projectId, "test.other-key", T3, AuditIngestionStatus.INVALID);
        save("p-other-" + UUID.randomUUID(), AUDIT_KEY, T2, AuditIngestionStatus.VALID);

        assertThat(reportRepository.findByProjectIdOrderByGeneratedAtDesc(projectId))
                .extracting(AuditReport::getId).containsExactly(b.getId(), a.getId());
    }

    /** Die Zeitpunkte gueltiger Vorlaeufe — Grundlage der Drei-Laeufe-Regel fuer eingeordnete Befunde (b2e3da6a). */
    @Test
    void findValidGeneratedAtBefore_listsOnlyValidEarlierReportsOfTheScope_newestFirst() {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        save(projectId, AUDIT_KEY, now, AuditIngestionStatus.VALID);                              // der aktuelle selbst
        save(projectId, AUDIT_KEY, now.minus(Duration.ofDays(1)), AuditIngestionStatus.VALID);
        save(projectId, AUDIT_KEY, now.minus(Duration.ofDays(2)), AuditIngestionStatus.INVALID);  // zaehlt nicht als Lauf
        save(projectId, AUDIT_KEY, now.minus(Duration.ofDays(3)), AuditIngestionStatus.VALID);
        save(projectId, AUDIT_KEY, now.minus(Duration.ofDays(4)), AuditIngestionStatus.VALID);
        save(projectId, "test.other-key", now.minus(Duration.ofHours(1)), AuditIngestionStatus.VALID);
        save("p-other-" + UUID.randomUUID(), AUDIT_KEY, now.minus(Duration.ofHours(2)), AuditIngestionStatus.VALID);

        assertThat(reportRepository.findValidGeneratedAtBefore(projectId, AUDIT_KEY, now, PageRequest.of(0, 2)))
                .containsExactly(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(3)));
    }
}
