package com.automation.integration;

import com.automation.config.RunRetentionConfig;
import com.automation.entity.*;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunCleanupService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beweist gegen echtes SQLite, dass die Run-Retention nur abgeschlossene, alte Runs ohne
 * Audit-Report trifft — und dass die JPQL-Query ueberhaupt bootet (Subquery auf AuditReport).
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RunRetentionIntegrationTest {

    @Autowired private RunCleanupService runCleanupService;
    @Autowired private RunRetentionConfig retentionConfig;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private AuditReportRepository auditReportRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private Project project;
    private Workflow workflow;
    private int originalDays;
    private int originalMax;

    @BeforeEach
    void setUp() {
        project = projectRepository.save(Project.builder()
                .name("Run Retention Test").type(ProjectType.CODE)
                .path("/tmp/run-retention-test").status(ProjectStatus.RUNNING).build());
        workflow = workflowRepository.save(Workflow.builder()
                .name("Retention Test Workflow").project(project).build());
        originalDays = retentionConfig.getRetentionDays();
        originalMax = retentionConfig.getMaxPerNight();
    }

    @AfterEach
    void restoreConfig() {
        retentionConfig.setRetentionDays(originalDays);
        retentionConfig.setMaxPerNight(originalMax);
    }

    private WorkflowRun run(RunStatus status, int ageDays, int steps) {
        Instant completed = ageDays > 0 ? Instant.now().minus(ageDays, ChronoUnit.DAYS) : null;
        WorkflowRun run = runRepository.save(WorkflowRun.builder()
                .workflow(workflow).workflowName(workflow.getName())
                .status(status).startedAt(Instant.now().minus(ageDays, ChronoUnit.DAYS).minusSeconds(60))
                .completedAt(completed).build());
        for (int i = 0; i < steps; i++) {
            stepResultRepository.save(StepResult.builder()
                    .workflowRun(run).stepIndex(i).status(StepStatus.COMPLETED).output("out " + i).build());
        }
        flush();
        return run;
    }

    private void auditReportFor(WorkflowRun run) {
        auditReportRepository.save(AuditReport.builder()
                .projectId(project.getId()).workflowId(workflow.getId()).workflowRunId(run.getId())
                .auditKey("mysuricates.test").category("test").title("Report")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(Instant.now()).build());
        flush();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findTerminalRunIdsOlderThan_returnsOnlyOldFinishedRunsWithoutAuditReport_oldestFirst() {
        WorkflowRun oldest = run(RunStatus.FAILED, 200, 1);
        WorkflowRun old = run(RunStatus.COMPLETED, 100, 2);
        run(RunStatus.COMPLETED, 1, 1);               // zu jung
        run(RunStatus.RUNNING, 0, 0);                 // nicht terminal, kein completedAt
        run(RunStatus.INTERRUPTED, 150, 1);           // eigene Regel, nicht Teil der Retention
        WorkflowRun audited = run(RunStatus.COMPLETED, 120, 1);
        auditReportFor(audited);

        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        List<String> ids = runRepository.findTerminalRunIdsOlderThan(
                RunCleanupService.TERMINAL_STATUSES, cutoff, PageRequest.of(0, 10));

        assertThat(ids).containsExactly(oldest.getId(), old.getId());
        assertThat(runRepository.findTerminalRunIdsOlderThan(
                RunCleanupService.TERMINAL_STATUSES, cutoff, PageRequest.of(0, 1)))
                .containsExactly(oldest.getId());
    }

    @Test
    void cleanupTerminalRuns_deletesOldestFinishedRunsUpToBatchLimit_andKeepsAuditedOnes() {
        WorkflowRun oldest = run(RunStatus.CANCELLED, 200, 2);
        WorkflowRun old = run(RunStatus.COMPLETED_WITH_ERRORS, 100, 2);
        WorkflowRun fresh = run(RunStatus.COMPLETED, 1, 1);
        WorkflowRun audited = run(RunStatus.COMPLETED, 120, 1);
        auditReportFor(audited);
        retentionConfig.setRetentionDays(90);
        retentionConfig.setMaxPerNight(1);

        runCleanupService.cleanupTerminalRuns();
        flush();

        assertThat(runRepository.findById(oldest.getId())).isEmpty();
        assertThat(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(oldest.getId())).isEmpty();
        assertThat(runRepository.findById(old.getId())).isPresent();
        assertThat(runRepository.findById(fresh.getId())).isPresent();
        assertThat(runRepository.findById(audited.getId())).isPresent();

        retentionConfig.setMaxPerNight(10);
        runCleanupService.cleanupTerminalRuns();
        flush();

        assertThat(runRepository.findById(old.getId())).isEmpty();
        assertThat(runRepository.findById(fresh.getId())).isPresent();
        assertThat(runRepository.findById(audited.getId())).isPresent();
    }

    @Test
    void cleanupTerminalRuns_disabled_deletesNothing() {
        WorkflowRun old = run(RunStatus.COMPLETED, 400, 1);
        retentionConfig.setRetentionDays(0);

        runCleanupService.cleanupTerminalRuns();
        flush();

        assertThat(runRepository.findById(old.getId())).isPresent();
    }
}
