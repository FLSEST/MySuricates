package com.automation.integration;

import com.automation.service.audit.AuditCenterService.AuditFindingResponse;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.service.audit.AuditCenterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings list sorts by severity, not by the alphabet of the stored enum names —
 * otherwise LOW would come before MEDIUM (TODO f4ddc836). Against real SQLite, because that is where the sorting happens.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFindingSeverityOrderIntegrationTest {

    @Autowired private AuditCenterService auditCenterService;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditReportRepository reportRepository;

    @Test
    void findingsAreOrderedBySeverityThenByLastSeen() {
        String projectId = UUID.randomUUID().toString();
        AuditReport report = reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf").workflowRunId(UUID.randomUUID().toString())
                .auditKey("test.order").category("test").title("Order")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(Instant.now()).build());

        save(report, "low", AuditSeverity.LOW, Instant.parse("2026-09-21T10:00:00Z"));
        save(report, "medium-old", AuditSeverity.MEDIUM, Instant.parse("2026-09-20T10:00:00Z"));
        save(report, "critical", AuditSeverity.CRITICAL, Instant.parse("2026-09-19T10:00:00Z"));
        save(report, "medium-new", AuditSeverity.MEDIUM, Instant.parse("2026-09-21T11:00:00Z"));
        save(report, "high", AuditSeverity.HIGH, Instant.parse("2026-09-18T10:00:00Z"));

        var page = auditCenterService.listFindings(0, 10, projectId, null, null, null);

        assertThat(page.content()).extracting(AuditFindingResponse::title)
                .containsExactly("critical", "high", "medium-new", "medium-old", "low");
        assertThat(page.totalElements()).isEqualTo(5);
    }

    private void save(AuditReport report, String title, AuditSeverity severity, Instant lastSeen) {
        findingRepository.save(AuditFinding.builder()
                .projectId(report.getProjectId()).auditKey(report.getAuditKey())
                .fingerprint(title).ruleKey("rule").subject(title).severity(severity).title(title)
                .firstSeenReportId(report.getId()).lastSeenReportId(report.getId())
                .firstSeenRunId(report.getWorkflowRunId()).lastSeenRunId(report.getWorkflowRunId())
                .firstSeenAt(lastSeen).lastSeenAt(lastSeen).build());
    }
}
