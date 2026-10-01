package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse.TopWorkflowEntry;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardOverviewTest {

    private static final Instant TODAY = Instant.parse("2026-10-01T00:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void zaehltOhneProjektAllesUndErfolgEinschliesslichMitFehlern(String projectId) {
        when(workflows.count()).thenReturn(12L);
        when(runs.count()).thenReturn(100L);
        when(runs.countByStatus(RunStatus.COMPLETED)).thenReturn(70L);
        when(runs.countByStatus(RunStatus.COMPLETED_WITH_ERRORS)).thenReturn(5L);
        when(runs.countByStatus(RunStatus.FAILED)).thenReturn(20L);
        when(runs.countByStartedAtAfter(TODAY)).thenReturn(4L);
        when(runs.findTopWorkflows()).thenReturn(List.<Object[]>of(
                new Object[]{"w1", "Nightly", 40L}, new Object[]{"w2", "Audit", 15L}));

        DashboardOverview overview = DashboardOverview.load(runs, workflows, projectId, TODAY);

        assertThat(overview).isEqualTo(new DashboardOverview(12, 100, 75, 20, 4, List.of(
                new TopWorkflowEntry("w1", "Nightly", 40), new TopWorkflowEntry("w2", "Audit", 15))));
        assertThat(overview.successRate()).isEqualTo(0.75);
        verify(runs, never()).countByProject(anyString());
        verify(workflows, never()).countByProjectId(anyString());
    }

    @Test
    void zaehltMitProjektNurDessenWorkflowsUndRuns() {
        when(workflows.countByProjectId("p1")).thenReturn(3L);
        when(runs.countByProject("p1")).thenReturn(10L);
        when(runs.countByProjectAndStatus("p1", RunStatus.COMPLETED)).thenReturn(6L);
        when(runs.countByProjectAndStatus("p1", RunStatus.COMPLETED_WITH_ERRORS)).thenReturn(1L);
        when(runs.countByProjectAndStatus("p1", RunStatus.FAILED)).thenReturn(2L);
        when(runs.countByProjectAndStartedAtAfter("p1", TODAY)).thenReturn(1L);
        when(runs.findTopWorkflowsByProject("p1")).thenReturn(List.<Object[]>of(new Object[]{"w9", "Build", 10L}));

        DashboardOverview overview = DashboardOverview.load(runs, workflows, "p1", TODAY);

        assertThat(overview).isEqualTo(new DashboardOverview(3, 10, 7, 2, 1,
                List.of(new TopWorkflowEntry("w9", "Build", 10))));
        assertThat(overview.successRate()).isEqualTo(0.7);
        verify(runs, never()).count();
        verify(workflows, never()).count();
        verify(runs, never()).countByStatus(any());
        verify(runs, never()).findTopWorkflows();
    }

    @Test
    void erfolgsquoteOhneRunsIstNull() {
        DashboardOverview overview = new DashboardOverview(5, 0, 0, 0, 0, List.of());

        assertThat(overview.successRate()).isZero();
    }

    @Test
    void ohneRunsKeineTopWorkflows() {
        when(runs.findTopWorkflowsByProject("leer")).thenReturn(List.of());

        DashboardOverview overview = DashboardOverview.load(runs, workflows, "leer", TODAY);

        assertThat(overview.topWorkflows()).isEmpty();
        assertThat(overview.totalRuns()).isZero();
        assertThat(overview.successRate()).isZero();
    }
}
