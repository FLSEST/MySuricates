package com.automation.integration;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.GateStatus;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.service.audit.AuditCenterService;
import com.automation.service.audit.AuditCenterService.AuditSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /audits} liest Reports als Projektion ohne Markdown und zaehlt Findings per GROUP BY
 * (TODO 3d7e3987). Die Zusammenfassung muss feldgleich bleiben mit der, die aus den vollen
 * Entities entsteht – die Detailansicht ({@code getAudit}) baut sie weiter aus Report und Findings.
 * Gegen echtes SQLite, weil Konstruktor-Ausdruck, Enum-Spalten und GROUP BY dort laufen.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditSummaryProjectionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Autowired private AuditCenterService auditCenterService;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private AuditFindingRepository findingRepository;

    @Test
    void overviewSummaryMatchesTheSummaryBuiltFromFullEntities() {
        String projectId = UUID.randomUUID().toString();
        report(projectId, "a.health", GateStatus.YELLOW, AuditIngestionStatus.VALID, NOW.minusSeconds(300), null);
        AuditReport latestHealth = report(projectId, "a.health", GateStatus.GREEN, AuditIngestionStatus.VALID,
                NOW.minusSeconds(200), null);
        report(projectId, "a.health", null, AuditIngestionStatus.INVALID, NOW.minusSeconds(100), "kein Envelope");
        AuditReport security = report(projectId, "a.security", GateStatus.RED, AuditIngestionStatus.VALID, NOW, null);

        finding(latestHealth, "h1", AuditSeverity.HIGH, AuditFindingStatus.OPEN);
        finding(latestHealth, "h2", AuditSeverity.HIGH, AuditFindingStatus.OPEN);
        finding(latestHealth, "h3", AuditSeverity.LOW, AuditFindingStatus.RESOLVED);
        finding(latestHealth, "h4", AuditSeverity.CRITICAL, AuditFindingStatus.AWAITING_VERIFICATION);
        finding(security, "s1", AuditSeverity.MEDIUM, AuditFindingStatus.DISMISSED);

        List<AuditSummary> overview = auditCenterService.listAudits(projectId, null, null);

        assertThat(overview).extracting(AuditSummary::auditKey).containsExactly("a.security", "a.health");
        for (AuditSummary summary : overview) {
            AuditSummary fromEntities = auditCenterService.getAudit(projectId, summary.auditKey()).summary();
            assertThat(summary).as(summary.auditKey()).isEqualTo(fromEntities);
        }
        AuditSummary health = overview.get(1);
        assertThat(health.latestReportId()).isEqualTo(latestHealth.getId());
        assertThat(health.trend()).isEqualTo("IMPROVED");
        assertThat(health.latestAttemptStatus()).isEqualTo(AuditIngestionStatus.INVALID);
        assertThat(health.latestAttemptError()).isEqualTo("kein Envelope");
        assertThat(health.findingsByStatus()).isEqualTo(Map.of("OPEN", 2L, "RESOLVED", 1L, "AWAITING_VERIFICATION", 1L));
        assertThat(health.openFindingsBySeverity()).isEqualTo(Map.of("HIGH", 2L, "CRITICAL", 1L));
        assertThat(overview.get(0).findingsByStatus()).isEqualTo(Map.of("DISMISSED", 1L));
        assertThat(overview.get(0).openFindingsBySeverity()).isEmpty();
    }

    private AuditReport report(String projectId, String auditKey, GateStatus gate, AuditIngestionStatus status,
                               Instant generatedAt, String error) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf").workflowRunId(UUID.randomUUID().toString())
                .auditKey(auditKey).category("health").title("Title " + auditKey)
                .markdownContent("# Report\n" + "x".repeat(2_000)).gateStatus(gate).gateDescription("gate " + gate)
                .ingestionStatus(status).ingestionError(error).generatedAt(generatedAt).build());
    }

    private void finding(AuditReport report, String title, AuditSeverity severity, AuditFindingStatus status) {
        findingRepository.save(AuditFinding.builder()
                .projectId(report.getProjectId()).auditKey(report.getAuditKey())
                .fingerprint(title).ruleKey("rule").subject(title).severity(severity).title(title).status(status)
                .firstSeenReportId(report.getId()).lastSeenReportId(report.getId())
                .firstSeenRunId(report.getWorkflowRunId()).lastSeenRunId(report.getWorkflowRunId())
                .firstSeenAt(NOW).lastSeenAt(NOW).build());
    }
}
