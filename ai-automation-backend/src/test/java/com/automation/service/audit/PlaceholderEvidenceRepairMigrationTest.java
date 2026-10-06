package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V99 (TODO 5fc15f50) against the real SQLite: a finding whose evidence was overwritten with the delta mode's
 * placeholder gets back the most recent concrete evidence of its occurrences – and only such a finding.
 * Flyway already ran the script on the empty test database; here it runs again on prepared rows.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class PlaceholderEvidenceRepairMigrationTest {

    private static final String PLACEHOLDER = "unveraendert seit dem letzten Urteil";
    private static final String AUDIT_KEY = "test.evidence-repair";
    private static final Instant T1 = Instant.parse("2026-10-06T09:47:00Z");
    private static final Instant T2 = Instant.parse("2026-10-06T10:50:00Z");
    private static final Instant T3 = Instant.parse("2026-10-06T11:50:00Z");

    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingOccurrenceRepository occurrenceRepository;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private String projectId;

    @BeforeEach
    void setUp() {
        projectId = "p-" + UUID.randomUUID();
    }

    private String report(Instant generatedAt) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey(AUDIT_KEY).category("architecture").title("Architecture Review")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(generatedAt).build()).getId();
    }

    private AuditFinding finding(String subject, String evidence, String firstReport, String lastReport) {
        return findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(AUDIT_KEY).fingerprint("fp-" + UUID.randomUUID())
                .ruleKey("arch.misplaced-logic").subject(subject).severity(AuditSeverity.MEDIUM).title("Titel " + subject)
                .evidence(evidence).status(AuditFindingStatus.OPEN)
                .firstSeenReportId(firstReport).lastSeenReportId(lastReport)
                .firstSeenRunId("run-1").lastSeenRunId("run-2").firstSeenAt(T1).lastSeenAt(T2).build());
    }

    private void occurrence(AuditFinding finding, String reportId, Instant observedAt, String evidence) {
        occurrenceRepository.saveAndFlush(AuditFindingOccurrence.builder()
                .findingId(finding.getId()).reportId(reportId).severity(AuditSeverity.MEDIUM)
                .title(finding.getTitle()).evidence(evidence).observedAt(observedAt).build());
    }

    private void runMigration() throws IOException {
        String sql = new ClassPathResource("db/migration/V99__restore_placeholder_audit_evidence.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.update(sql);
        entityManager.clear();
    }

    private String evidenceOf(AuditFinding finding) {
        return findingRepository.findById(finding.getId()).orElseThrow().getEvidence();
    }

    @Test
    void restoresTheMostRecentConcreteEvidence_andLeavesEverythingElseAlone() throws IOException {
        String r1 = report(T1);
        String r2 = report(T2);
        String r3 = report(T3);
        AuditFinding overwritten = finding("SimpleWorkflows.tsx", PLACEHOLDER, r1, r3);
        occurrence(overwritten, r1, T1, "openCreate L76-80, handleCreate L143-176");
        occurrence(overwritten, r2, T2, "openCreate L70-74, handleCreate L140-170");
        occurrence(overwritten, r3, T3, PLACEHOLDER);
        AuditFinding neverConcrete = finding("Only.java", PLACEHOLDER, r1, r1);
        occurrence(neverConcrete, r1, T1, PLACEHOLDER);
        AuditFinding concrete = finding("Fine.java", "parseResponse L49-78", r1, r1);
        occurrence(concrete, r1, T1, "older text");

        runMigration();

        assertThat(evidenceOf(overwritten)).isEqualTo("openCreate L70-74, handleCreate L140-170");
        assertThat(evidenceOf(neverConcrete)).as("nothing to restore").isEqualTo(PLACEHOLDER);
        assertThat(evidenceOf(concrete)).as("a concrete evidence is never replaced").isEqualTo("parseResponse L49-78");
    }
}
