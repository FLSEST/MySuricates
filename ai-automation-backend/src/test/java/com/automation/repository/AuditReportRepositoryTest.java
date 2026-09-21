package com.automation.repository;

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

        Page<AuditReport> first = reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc(
                projectId, AUDIT_KEY, PageRequest.of(0, 2));

        assertThat(first.getContent()).extracting(AuditReport::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void findByProjectId_listsAllAuditsOfTheProjectNewestFirst() {
        AuditReport a = save(projectId, AUDIT_KEY, T1, AuditIngestionStatus.VALID);
        AuditReport b = save(projectId, "test.other-key", T3, AuditIngestionStatus.INVALID);
        save("p-other-" + UUID.randomUUID(), AUDIT_KEY, T2, AuditIngestionStatus.VALID);

        assertThat(reportRepository.findByProjectIdOrderByGeneratedAtDesc(projectId))
                .extracting(AuditReport::getId).containsExactly(b.getId(), a.getId());
    }
}
