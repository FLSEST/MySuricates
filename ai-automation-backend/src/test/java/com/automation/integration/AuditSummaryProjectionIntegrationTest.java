package com.automation.integration;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingOccurrence;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.ChainRun;
import com.automation.entity.GateStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.entity.WorkflowVersion;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunProjectionRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.automation.service.audit.AuditCenterService;
import com.automation.service.audit.AuditCenterService.AuditReportResponse;
import com.automation.service.audit.AuditCenterService.AuditSummary;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /audits} reads reports as a projection without markdown and counts findings via GROUP BY
 * (TODO 3d7e3987). The summary must stay field-identical to the one built from the full
 * entities — the detail view ({@code getAudit}) still builds it from report and findings.
 * Against real SQLite, because constructor expression, enum columns and GROUP BY run there.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditSummaryProjectionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Autowired private AuditCenterService auditCenterService;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingOccurrenceRepository occurrenceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;
    @Autowired private WorkflowChainRepository chainRepository;
    @Autowired private ChainRunRepository chainRunRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowRunProjectionRepository runProjections;
    @Autowired private EntityManager entityManager;

    @Test
    void overviewSummaryMatchesTheSummaryBuiltFromFullEntities() {
        String projectId = UUID.randomUUID().toString();
        report(projectId, "a.health", "health", GateStatus.YELLOW, AuditIngestionStatus.VALID, NOW.minusSeconds(300), null);
        AuditReport latestHealth = report(projectId, "a.health", "health", GateStatus.GREEN, AuditIngestionStatus.VALID,
                NOW.minusSeconds(200), null);
        report(projectId, "a.health", "health", null, AuditIngestionStatus.INVALID, NOW.minusSeconds(100), "kein Envelope");
        AuditReport security = report(projectId, "a.security", "health", GateStatus.RED, AuditIngestionStatus.VALID, NOW, null);

        finding(latestHealth, "h1", AuditSeverity.HIGH, AuditFindingStatus.OPEN);
        finding(latestHealth, "h2", AuditSeverity.HIGH, AuditFindingStatus.OPEN);
        finding(latestHealth, "h3", AuditSeverity.LOW, AuditFindingStatus.RESOLVED);
        finding(latestHealth, "h4", AuditSeverity.CRITICAL, AuditFindingStatus.AWAITING_VERIFICATION);
        finding(security, "s1", AuditSeverity.MEDIUM, AuditFindingStatus.DISMISSED);

        List<AuditSummary> overview = auditCenterService.listAudits(projectId, null, null);

        assertThat(overview).extracting(AuditSummary::auditKey).containsExactly("a.security", "a.health");
        assertMatchesSummariesFromEntities(overview);
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

    /**
     * The same auditKey in two projects: grouping and counting keep the projects apart,
     * the findings of one project do not end up in the summary of the other.
     */
    @Test
    void sameAuditKeyInTwoProjectsStaysApart() {
        String category = "cat-" + UUID.randomUUID(); // only the reports of this test
        String projectA = UUID.randomUUID().toString();
        String projectB = UUID.randomUUID().toString();
        AuditReport reportA = report(projectA, "a.shared", category, GateStatus.RED, AuditIngestionStatus.VALID, NOW, null);
        AuditReport reportB = report(projectB, "a.shared", category, GateStatus.GREEN, AuditIngestionStatus.VALID,
                NOW.minusSeconds(10), null);
        finding(reportA, "a1", AuditSeverity.CRITICAL, AuditFindingStatus.OPEN);
        finding(reportA, "a2", AuditSeverity.HIGH, AuditFindingStatus.OPEN);
        finding(reportB, "b1", AuditSeverity.LOW, AuditFindingStatus.RESOLVED);

        List<AuditSummary> overview = auditCenterService.listAudits(null, category, null);

        assertThat(overview).extracting(AuditSummary::projectId).containsExactly(projectA, projectB);
        assertThat(overview).extracting(AuditSummary::latestReportId).containsExactly(reportA.getId(), reportB.getId());
        assertMatchesSummariesFromEntities(overview);
        assertThat(overview.get(0).findingsByStatus()).isEqualTo(Map.of("OPEN", 2L));
        assertThat(overview.get(0).openFindingsBySeverity()).isEqualTo(Map.of("CRITICAL", 1L, "HIGH", 1L));
        assertThat(overview.get(1).findingsByStatus()).isEqualTo(Map.of("RESOLVED", 1L));
        assertThat(overview.get(1).openFindingsBySeverity()).isEmpty();
    }

    /** An audit with a report but without findings: empty counts, exactly as from the entities. */
    @Test
    void auditWithReportButWithoutFindingsHasEmptyCounts() {
        String projectId = UUID.randomUUID().toString();
        AuditReport report = report(projectId, "a.clean", "health", GateStatus.GREEN, AuditIngestionStatus.VALID, NOW, null);

        List<AuditSummary> overview = auditCenterService.listAudits(projectId, null, null);

        assertThat(overview).singleElement().satisfies(summary -> {
            assertThat(summary.latestReportId()).isEqualTo(report.getId());
            assertThat(summary.trend()).isEqualTo("NEW");
            assertThat(summary.findingsByStatus()).isEmpty();
            assertThat(summary.openFindingsBySeverity()).isEmpty();
        });
        assertMatchesSummariesFromEntities(overview);
    }

    /**
     * The report page resolves the origin of its runs against real SQLite (TODO a3b89355): chain entry
     * with chain, schedule of the chain and definition version, direct run, chain run from before V89 and a
     * deleted run. The number of statements does not depend on the number of reports — a page with four
     * reports costs as much as one with one.
     */
    @Test
    void reportPageResolvesTheRunOrigins_inAFixedNumberOfStatements() {
        String projectId = UUID.randomUUID().toString();
        Workflow workflow = workflow();
        WorkflowVersion v7 = version(workflow, 7);
        WorkflowVersion v8 = version(workflow, 8);
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder().name("Wochen-Audits").build());
        ChainRun chainRun = chainRunRepository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .startedAt(NOW).triggeredByScheduleId("sched-chain").build());
        WorkflowRun viaChain = run(workflow, TriggerSource.CHAIN, null, chainRun.getId(), v7.getId(), "SCHEDULE");
        WorkflowRun direct = run(workflow, TriggerSource.SCHEDULE, "sched-direct", null, v8.getId(), null);
        WorkflowRun oldChain = run(workflow, TriggerSource.CHAIN, null, null, null, "MANUAL");
        AuditReport chainReport = report(projectId, "a.origin", viaChain.getId(), NOW);
        AuditReport directReport = report(projectId, "a.origin", direct.getId(), NOW.minusSeconds(60));
        report(projectId, "a.origin", oldChain.getId(), NOW.minusSeconds(120));
        report(projectId, "a.origin", UUID.randomUUID().toString(), NOW.minusSeconds(180));
        occurrence(chainReport, "c1");
        occurrence(chainReport, "c2");
        occurrence(directReport, "d1");
        WorkflowRun single = run(workflow, TriggerSource.CHAIN, null, chainRun.getId(), v7.getId(), "SCHEDULE");
        report(projectId, "a.single", single.getId(), NOW);
        entityManager.flush();
        entityManager.clear();

        Counted<List<AuditReportResponse>> page = counted(() -> auditCenterService.listReports(projectId, "a.origin", 0, 20).content());
        Counted<List<AuditReportResponse>> onePage = counted(() -> auditCenterService.listReports(projectId, "a.single", 0, 20).content());

        assertThat(page.result()).hasSize(4);
        assertThat(page.result().get(0)).satisfies(r -> {
            assertThat(r.triggerSource()).isEqualTo(TriggerSource.SCHEDULE);
            assertThat(r.scheduleId()).isEqualTo("sched-chain");
            assertThat(r.chainRunId()).isEqualTo(chainRun.getId());
            assertThat(r.chainId()).isEqualTo(chain.getId());
            assertThat(r.chainName()).isEqualTo("Wochen-Audits");
            assertThat(r.definitionVersionNumber()).isEqualTo(7);
            assertThat(r.findingCount()).isEqualTo(2);
        });
        assertThat(page.result().get(1)).satisfies(r -> {
            assertThat(r.triggerSource()).isEqualTo(TriggerSource.SCHEDULE);
            assertThat(r.scheduleId()).isEqualTo("sched-direct");
            assertThat(r.chainRunId()).isNull();
            assertThat(r.definitionVersionNumber()).isEqualTo(8);
            assertThat(r.findingCount()).isEqualTo(1);
        });
        assertThat(page.result().get(2)).satisfies(r -> {
            assertThat(r.triggerSource()).isEqualTo(TriggerSource.CHAIN);
            assertThat(r.chainRunId()).isNull();
            assertThat(r.chainName()).isNull();
            assertThat(r.findingCount()).isZero();
        });
        assertThat(page.result().get(3).triggerSource()).isNull();
        assertThat(onePage.result()).singleElement().satisfies(r -> assertThat(r.chainName()).isEqualTo("Wochen-Audits"));
        assertThat(page.statements())
                .as("reports, finding counts, runs, chain runs with chain, versions — not per report")
                .isEqualTo(5)
                .isEqualTo(onePage.statements());
    }

    /**
     * Of a run's context only the parent trigger comes to Java, read in SQLite: a chain entry
     * with an untruncated previous_chain_output yields a row with exactly this value. A broken context
     * (not JSON) lets the page load — the trigger falls back to CHAIN, without a trigger on the run
     * to null. With the context going through the JsonMapConverter the whole page failed on it.
     */
    @Test
    void reportPageReadsOnlyTheParentTriggerSource_andSurvivesABrokenContext() {
        String projectId = UUID.randomUUID().toString();
        Workflow workflow = workflow();
        WorkflowChain chain = chainRepository.save(WorkflowChain.builder().name("Nacht-Audits").build());
        ChainRun chainRun = chainRunRepository.save(ChainRun.builder().chain(chain).status(RunStatus.COMPLETED)
                .startedAt(NOW).triggeredByScheduleId("sched-night").build());
        Map<String, Object> entryContext = new HashMap<>(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE",
                TriggerSource.CHAIN_RUN_CONTEXT_KEY, chainRun.getId(), "previous_chain_output", "x".repeat(200_000)));
        WorkflowRun bigContext = runRepository.save(WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.COMPLETED).startedAt(NOW).triggerSource(TriggerSource.CHAIN)
                .chainRunId(chainRun.getId()).inputContext(entryContext).build());
        WorkflowRun broken = run(workflow, TriggerSource.CHAIN, null, chainRun.getId(), null, "SCHEDULE");
        WorkflowRun brokenWithoutSource = run(workflow, null, null, null, null, "MANUAL");
        report(projectId, "a.context", bigContext.getId(), NOW);
        report(projectId, "a.context", broken.getId(), NOW.minusSeconds(60));
        report(projectId, "a.context", brokenWithoutSource.getId(), NOW.minusSeconds(120));
        entityManager.flush();
        for (WorkflowRun run : List.of(broken, brokenWithoutSource)) {
            entityManager.createNativeQuery("UPDATE workflow_runs SET input_context = :context WHERE id = :id")
                    .setParameter("context", "{\"" + TriggerSource.PARENT_CONTEXT_KEY + "\": \"SCHED")
                    .setParameter("id", run.getId()).executeUpdate();
        }
        entityManager.clear();

        List<Object[]> columns = runProjections.findOriginColumns(List.of(bigContext.getId()));
        Counted<List<AuditReportResponse>> page = counted(() -> auditCenterService.listReports(projectId, "a.context", 0, 20).content());

        assertThat(columns).singleElement().satisfies(row -> {
            assertThat(row).as("id, source, schedule, chain run, version, parent source — no context").hasSize(6);
            assertThat(row[5]).isEqualTo("SCHEDULE");
        });
        assertThat(page.result()).extracting(AuditReportResponse::triggerSource)
                .containsExactly(TriggerSource.SCHEDULE, TriggerSource.CHAIN, null);
        assertThat(page.result()).extracting(AuditReportResponse::chainName)
                .containsExactly("Nacht-Audits", "Nacht-Audits", null);
        assertThat(page.result().get(1).scheduleId()).isEqualTo("sched-night");
        assertThat(page.statements()).as("reports, finding counts, runs, chain runs; no versions on this page")
                .isEqualTo(4);
    }

    private record Counted<T>(T result, int statements) {}

    private static <T> Counted<T> counted(Supplier<T> call) {
        SqlStatementCounter.start();
        try {
            T result = call.get();
            return new Counted<>(result, SqlStatementCounter.count());
        } finally {
            SqlStatementCounter.stop();
        }
    }

    private Workflow workflow() {
        Project project = projectRepository.save(Project.builder().name("Audit-Origin-" + UUID.randomUUID())
                .type(ProjectType.CODE).path("/tmp/audit-origin-" + UUID.randomUUID()).status(ProjectStatus.STOPPED).build());
        return workflowRepository.save(Workflow.builder().name("Health").project(project).version("4.4.0").build());
    }

    private WorkflowVersion version(Workflow workflow, int number) {
        return versionRepository.save(WorkflowVersion.builder().workflowId(workflow.getId()).versionNumber(number)
                .snapshot("{}").changeSource("pool-install").definitionHash("hash-" + number).build());
    }

    private WorkflowRun run(Workflow workflow, TriggerSource source, String scheduleId, String chainRunId,
                            String versionId, String parentSource) {
        Map<String, Object> context = new HashMap<>();
        if (parentSource != null) context.put(TriggerSource.PARENT_CONTEXT_KEY, parentSource);
        if (chainRunId != null) context.put(TriggerSource.CHAIN_RUN_CONTEXT_KEY, chainRunId);
        return runRepository.save(WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.COMPLETED).startedAt(NOW).triggerSource(source).triggeredByScheduleId(scheduleId)
                .chainRunId(chainRunId).definitionVersionId(versionId).inputContext(context).build());
    }

    private AuditReport report(String projectId, String auditKey, String runId, Instant generatedAt) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf").workflowRunId(runId)
                .auditKey(auditKey).category("health").title("Title " + auditKey).markdownContent("# Report")
                .gateStatus(GateStatus.GREEN).ingestionStatus(AuditIngestionStatus.VALID).generatedAt(generatedAt).build());
    }

    private void occurrence(AuditReport report, String title) {
        AuditFinding finding = findingRepository.save(AuditFinding.builder()
                .projectId(report.getProjectId()).auditKey(report.getAuditKey())
                .fingerprint(title).ruleKey("rule").subject(title).severity(AuditSeverity.LOW).title(title)
                .status(AuditFindingStatus.OPEN)
                .firstSeenReportId(report.getId()).lastSeenReportId(report.getId())
                .firstSeenRunId(report.getWorkflowRunId()).lastSeenRunId(report.getWorkflowRunId())
                .firstSeenAt(NOW).lastSeenAt(NOW).build());
        occurrenceRepository.save(AuditFindingOccurrence.builder().findingId(finding.getId()).reportId(report.getId())
                .severity(AuditSeverity.LOW).title(title).observedAt(NOW).build());
    }

    /** Every summary of the overview equals the one {@code getAudit} builds from the full entities. */
    private void assertMatchesSummariesFromEntities(List<AuditSummary> overview) {
        for (AuditSummary summary : overview) {
            AuditSummary fromEntities = auditCenterService.getAudit(summary.projectId(), summary.auditKey()).summary();
            assertThat(summary).as(summary.projectId() + "/" + summary.auditKey()).isEqualTo(fromEntities);
        }
    }

    private AuditReport report(String projectId, String auditKey, String category, GateStatus gate,
                               AuditIngestionStatus status, Instant generatedAt, String error) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf").workflowRunId(UUID.randomUUID().toString())
                .auditKey(auditKey).category(category).title("Title " + auditKey)
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
