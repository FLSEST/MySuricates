package com.automation.repository;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFindingRepositoryTest {

    private static final String AUDIT_KEY = "test.finding-repo";
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");

    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditReportRepository reportRepository;

    private String projectId;
    private String reportId;

    @BeforeEach
    void setUp() {
        projectId = "p-" + UUID.randomUUID();
        reportId = saveReport(projectId);
    }

    private String saveReport(String project) {
        return reportRepository.save(AuditReport.builder()
                .projectId(project).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey(AUDIT_KEY).category("security").title("Security")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(T1).build()).getId();
    }

    private AuditFinding save(String project, String auditKey, String fingerprint, AuditFindingStatus status) {
        return findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(project).auditKey(auditKey).fingerprint(fingerprint).ruleKey("shell.injection")
                .subject("ContainerShellService").severity(AuditSeverity.HIGH).title("Shell-Aufruf")
                .status(status).firstSeenReportId(reportId).lastSeenReportId(reportId)
                .firstSeenRunId("run-1").lastSeenRunId("run-1").firstSeenAt(T1).lastSeenAt(T1).build());
    }

    @Test
    void findByFingerprint_isScopedToProjectAndAuditKey() {
        AuditFinding finding = save(projectId, AUDIT_KEY, "fp-1", AuditFindingStatus.OPEN);
        save(projectId, "test.other-key", "fp-1", AuditFindingStatus.OPEN);

        assertThat(findingRepository.findByProjectIdAndAuditKeyAndFingerprint(projectId, AUDIT_KEY, "fp-1"))
                .get().extracting(AuditFinding::getId).isEqualTo(finding.getId());
        assertThat(findingRepository.findByProjectIdAndAuditKeyAndFingerprint("p-other", AUDIT_KEY, "fp-1"))
                .isEmpty();
    }

    @Test
    void findByProjectIdAndAuditKey_returnsEveryStatus() {
        AuditFinding open = save(projectId, AUDIT_KEY, "fp-1", AuditFindingStatus.OPEN);
        AuditFinding resolved = save(projectId, AUDIT_KEY, "fp-2", AuditFindingStatus.RESOLVED);
        save(projectId, "test.other-key", "fp-3", AuditFindingStatus.OPEN);

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactlyInAnyOrder(open.getId(), resolved.getId());
    }

    @Test
    void findDistinctScopes_returnsEachProjectAuditPairOnce() {
        save(projectId, AUDIT_KEY, "fp-1", AuditFindingStatus.OPEN);
        save(projectId, AUDIT_KEY, "fp-2", AuditFindingStatus.OPEN);
        save(projectId, "test.other-key", "fp-3", AuditFindingStatus.OPEN);

        List<List<Object>> scopes = findingRepository.findDistinctScopes().stream()
                .filter(row -> projectId.equals(row[0])).map(row -> List.of(row)).toList();

        assertThat(scopes).containsExactlyInAnyOrder(
                List.of(projectId, AUDIT_KEY), List.of(projectId, "test.other-key"));
    }

    @Test
    void specification_filtersByStatus() {
        save(projectId, AUDIT_KEY, "fp-1", AuditFindingStatus.OPEN);
        AuditFinding accepted = save(projectId, AUDIT_KEY, "fp-2", AuditFindingStatus.ACCEPTED_RISK);

        Specification<AuditFinding> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("projectId"), projectId), cb.equal(root.get("status"), AuditFindingStatus.ACCEPTED_RISK));

        assertThat(findingRepository.findAll(spec)).extracting(AuditFinding::getId).containsExactly(accepted.getId());
    }

    @Test
    void fingerprintIsUniquePerProjectAndAuditKey() {
        save(projectId, AUDIT_KEY, "fp-dup", AuditFindingStatus.OPEN);

        assertThatThrownBy(() -> save(projectId, AUDIT_KEY, "fp-dup", AuditFindingStatus.OPEN))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed: audit_findings.project_id, audit_findings.audit_key, audit_findings.fingerprint");
    }
}
