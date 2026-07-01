package com.automation.service.assistant.context;

import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunsContextSectionTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private SystemIdentityService systemIdentity;

    private RunsContextSection section;

    @BeforeEach
    void setUp() {
        section = new RunsContextSection(workflowRunRepository, systemIdentity);
    }

    private WorkflowRun buildRun(String id, RunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(status);
        run.setStartedAt(Instant.now());
        return run;
    }

    @Nested
    class CompactModeTests {

        @Test
        void showsSummary() {
            when(systemIdentity.getVersion()).thenReturn("0.6.4");
            when(workflowRunRepository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);

            WorkflowRun failed = buildRun("abcdef12-3456-7890", RunStatus.FAILED);
            WorkflowRun completed = buildRun("11111111-2222-3333", RunStatus.COMPLETED);
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc())
                    .thenReturn(List.of(failed, completed));

            StringBuilder sb = new StringBuilder();
            section.append(sb, true);

            String result = sb.toString();
            assertThat(result)
                    .contains("Version: 0.6.4")
                    .contains("2 recent")
                    .contains("2 running")
                    .contains("1 FAILED")
                    .contains("abcdef12");
        }

        @Test
        void noFailed_noIds() {
            when(systemIdentity.getVersion()).thenReturn("0.6.4");
            when(workflowRunRepository.countByStatus(RunStatus.RUNNING)).thenReturn(0L);
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc())
                    .thenReturn(List.of(buildRun("r1", RunStatus.COMPLETED)));

            StringBuilder sb = new StringBuilder();
            section.append(sb, true);

            assertThat(sb.toString())
                    .contains("0 FAILED")
                    .doesNotContain("ids:");
        }
    }

    @Nested
    class FullModeTests {

        @Test
        void showsActiveAndRecentRuns() {
            when(systemIdentity.getVersion()).thenReturn("0.6.4");
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc())
                    .thenReturn(List.of(buildRun("r1", RunStatus.COMPLETED)));

            StringBuilder sb = new StringBuilder();
            section.append(sb, false);

            assertThat(sb.toString())
                    .contains("Active Runs:** 0")
                    .contains("Last 10 Runs:");
        }

        @Test
        void failedRun_includesError() {
            when(systemIdentity.getVersion()).thenReturn("0.6.4");
            when(workflowRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());

            WorkflowRun failed = buildRun("r1", RunStatus.FAILED);
            failed.setError(Map.of("message", "Timeout after 300s"));
            when(workflowRunRepository.findTop10ByOrderByStartedAtDesc())
                    .thenReturn(List.of(failed));

            StringBuilder sb = new StringBuilder();
            section.append(sb, false);

            assertThat(sb.toString()).contains("Error: Timeout after 300s");
        }
    }

    @Nested
    class HelperTests {

        @Test
        void runLink_withWorkflow() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);
            Workflow wf = new Workflow();
            wf.setId("wf-1");
            run.setWorkflow(wf);

            assertThat(section.runLink(run)).isEqualTo("/workflows/wf-1/run/run-1");
        }

        @Test
        void runLink_withoutWorkflow() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);

            assertThat(section.runLink(run)).isEqualTo("/runs/run-1");
        }

        @Test
        void runLabel_withWorkflowAndProject() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);
            Workflow wf = new Workflow();
            wf.setName("Deploy");
            Project p = new Project();
            p.setName("MyProject");
            wf.setProject(p);
            run.setWorkflow(wf);

            assertThat(section.runLabel(run)).isEqualTo("Deploy (MyProject)");
        }

        @Test
        void runLabel_withoutWorkflow() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);

            assertThat(section.runLabel(run)).isEqualTo("Unknown");
        }

        @Test
        void runLabel_softDeletedWorkflow_usesSnapshot() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);
            run.setWorkflowName("Deploy");
            // workflow association resolved to null (e.g. soft-deleted via @SQLRestriction)

            assertThat(section.runLabel(run)).isEqualTo("Deploy [deleted]");
        }

        @Test
        void runLabel_snapshotPreferredOverLazyLoad() {
            WorkflowRun run = buildRun("run-1", RunStatus.COMPLETED);
            run.setWorkflowName("SnapshotName");
            Workflow wf = new Workflow();
            wf.setName("LiveName");
            Project p = new Project();
            p.setName("Proj");
            wf.setProject(p);
            run.setWorkflow(wf);

            // snapshot wins over lazy-loaded workflow name, project still appended
            assertThat(section.runLabel(run)).isEqualTo("SnapshotName (Proj)");
        }

        @Test
        void fmt_null_returnsDash() {
            assertThat(section.fmt(null)).isEqualTo("—");
        }

        @Test
        void fmt_instant_formatsCorrectly() {
            String result = section.fmt(Instant.parse("2026-04-04T10:30:00Z"));
            assertThat(result).matches("\\d{2}\\.\\d{2}\\. \\d{2}:\\d{2}");
        }

        @Test
        void extractError_noError_returnsNull() {
            WorkflowRun run = buildRun("r1", RunStatus.COMPLETED);
            assertThat(section.extractError(run)).isNull();
        }

        @Test
        void extractError_withMessage() {
            WorkflowRun run = buildRun("r1", RunStatus.FAILED);
            run.setError(Map.of("message", "something broke"));
            assertThat(section.extractError(run)).isEqualTo("something broke");
        }

        @Test
        void truncate_short_unchanged() {
            assertThat(section.truncate("short", 100)).isEqualTo("short");
        }

        @Test
        void truncate_long_truncated() {
            String result = section.truncate("a".repeat(200), 50);
            assertThat(result).hasSize(50 + "\n[Output truncated: 50 of 200 characters]".length());
            assertThat(result).contains("[Output truncated:");
        }

        @Test
        void truncate_null_returnsEmpty() {
            assertThat(section.truncate(null, 100)).isEmpty();
        }
    }
}
