package com.automation.repository;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFindingOccurrenceRepositoryTest {

    private static final String AUDIT_KEY = "test.occurrence-repo";
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");

    @Autowired private AuditFindingOccurrenceRepository occurrenceRepository;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditReportRepository reportRepository;

    private String projectId;
    private String report1;
    private String report2;

    @BeforeEach
    void setUp() {
        projectId = "p-" + UUID.randomUUID();
        report1 = saveReport();
        report2 = saveReport();
    }

    private String saveReport() {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey(AUDIT_KEY).category("security").title("Security")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(T1).build()).getId();
    }

    private String saveFinding(String fingerprint) {
        return findingRepository.save(AuditFinding.builder()
                .projectId(projectId).auditKey(AUDIT_KEY).fingerprint(fingerprint).ruleKey("shell.injection")
                .subject("ContainerShellService").severity(AuditSeverity.HIGH).title("Shell-Aufruf")
                .firstSeenReportId(report1).lastSeenReportId(report1)
                .firstSeenRunId("run-1").lastSeenRunId("run-1").firstSeenAt(T1).lastSeenAt(T1).build()).getId();
    }

    private AuditFindingOccurrence saveOccurrence(String findingId, String reportId) {
        return occurrenceRepository.save(AuditFindingOccurrence.builder()
                .findingId(findingId).reportId(reportId).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf").observedAt(T1).build());
    }

    @Test
    void existsByFindingIdAndReportId_matchesOnlyTheExactPair() {
        String finding = saveFinding("fp-1");
        saveOccurrence(finding, report1);

        assertThat(occurrenceRepository.existsByFindingIdAndReportId(finding, report1)).isTrue();
        assertThat(occurrenceRepository.existsByFindingIdAndReportId(finding, report2)).isFalse();
    }

    @Test
    void findReportIdsByFindingId_listsTheReportsOfOneFinding() {
        String finding = saveFinding("fp-r");
        String other = saveFinding("fp-other");
        saveOccurrence(finding, report1);
        saveOccurrence(finding, report2);
        saveOccurrence(other, report1);

        assertThat(occurrenceRepository.findReportIdsByFindingId(finding)).containsExactlyInAnyOrder(report1, report2);
        assertThat(occurrenceRepository.findReportIdsByFindingId("f-none")).isEmpty();
    }

    @Test
    void findFindingIdsByReportId_listsTheFindingsOfOneReport() {
        String a = saveFinding("fp-ra");
        String b = saveFinding("fp-rb");
        saveOccurrence(a, report1);
        saveOccurrence(b, report1);
        saveOccurrence(a, report2);

        assertThat(occurrenceRepository.findFindingIdsByReportId(report1)).containsExactlyInAnyOrder(a, b);
        assertThat(occurrenceRepository.findFindingIdsByReportId(report2)).containsExactly(a);
    }

    /** Eine GROUP-BY-Abfrage fuer eine ganze Report-Seite; ein Report ohne Findings fehlt (TODO a3b89355). */
    @Test
    void countByReportIds_countsPerReport_inOneQuery() {
        String a = saveFinding("fp-a");
        String b = saveFinding("fp-b");
        saveOccurrence(a, report1);
        saveOccurrence(b, report1);
        saveOccurrence(a, report2);
        String empty = saveReport();

        Map<String, Long> counts = occurrenceRepository.countByReportIds(List.of(report1, report2, empty)).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(report1, 2L, report2, 1L));
    }

    @Test
    void findByFindingId_returnsTheFindingsHistory() {
        String a = saveFinding("fp-a");
        String b = saveFinding("fp-b");
        saveOccurrence(a, report1);
        saveOccurrence(a, report2);
        saveOccurrence(b, report2);

        assertThat(occurrenceRepository.findByFindingId(a))
                .extracting(AuditFindingOccurrence::getReportId).containsExactlyInAnyOrder(report1, report2);
        assertThat(occurrenceRepository.findByFindingId("unknown")).isEmpty();
    }
}
