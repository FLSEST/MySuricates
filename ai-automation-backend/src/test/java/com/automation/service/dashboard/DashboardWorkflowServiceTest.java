package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class DashboardWorkflowServiceTest {

    private static final Instant STARTED = Instant.parse("2026-09-18T08:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-09-18T08:02:00Z");

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;

    @InjectMocks private DashboardWorkflowService service;

    private static Object[] row(String workflowId, String runId, RunStatus status, Map<String, Object> metadata) {
        return new Object[]{workflowId, runId, status, STARTED, ENDED, 120_000L, metadata};
    }

    private void given(List<Object[]> rows, Map<String, String> names) {
        lenient().when(runRepository.findLatestRunPerWorkflow(anyCollection())).thenReturn(rows);
        lenient().when(workflowRepository.findIdAndNameByIdIn(anyCollection()))
                .thenReturn(names.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList());
    }

    @Test
    void returns_one_entry_per_requested_id_in_request_order() {
        given(List.<Object[]>of(row("wf-b1", "run-1", RunStatus.COMPLETED, Map.of())),
                Map.of("wf-a1", "First", "wf-b1", "Second"));

        List<DashboardWorkflowResultResponse> results = service.results(List.of("wf-b1", "wf-a1"));

        assertThat(results).extracting(DashboardWorkflowResultResponse::workflowId).containsExactly("wf-b1", "wf-a1");
        assertThat(results.get(1).available()).isTrue();
        assertThat(results.get(1).workflowName()).isEqualTo("First");
        assertThat(results.get(1).run()).isNull();
        assertThat(results.get(1).resultState()).isEqualTo(ResultState.MISSING);
    }

    @Test
    void maps_the_latest_run_with_its_gate_status_and_contract() {
        given(List.<Object[]>of(row("wf-a1", "run-1", RunStatus.COMPLETED_WITH_ERRORS, Map.of(
                        "gate_status", "YELLOW",
                        "dashboard_widget", Map.of("status", "YELLOW", "title", "Nightly", "items", List.of("one"))))),
                Map.of("wf-a1", "Nightly QA"));

        DashboardWorkflowResultResponse entry = service.results(List.of("wf-a1")).getFirst();

        assertThat(entry.run()).isEqualTo(new DashboardWorkflowResultResponse.RunSummary(
                "run-1", RunStatus.COMPLETED_WITH_ERRORS, STARTED, ENDED, 120_000L, GateStatus.YELLOW));
        assertThat(entry.resultState()).isEqualTo(ResultState.VALID);
        assertThat(entry.result()).isEqualTo(
                new Result("Nightly", Result.Status.YELLOW, List.of(), List.of("one"), null));
    }

    @Test
    void reports_a_rejected_or_tampered_contract_as_invalid_without_a_result() {
        given(List.of(row("wf-a1", "run-1", RunStatus.COMPLETED, Map.of("dashboard_widget_error", "INVALID")),
                        row("wf-b1", "run-2", RunStatus.COMPLETED, Map.of("dashboard_widget", Map.of("status", "PINK")))),
                Map.of("wf-a1", "One", "wf-b1", "Two"));

        List<DashboardWorkflowResultResponse> results = service.results(List.of("wf-a1", "wf-b1"));

        assertThat(results).allSatisfy(entry -> {
            assertThat(entry.resultState()).isEqualTo(ResultState.INVALID);
            assertThat(entry.result()).isNull();
        });
    }

    @Test
    void a_deleted_workflow_shows_as_unavailable_without_run_data() {
        given(List.<Object[]>of(row("wf-a1", "run-1", RunStatus.COMPLETED, Map.of("gate_status", "GREEN"))), Map.of());

        DashboardWorkflowResultResponse entry = service.results(List.of("wf-a1")).getFirst();

        assertThat(entry.available()).isFalse();
        assertThat(entry.workflowName()).isNull();
        assertThat(entry.run()).isNull();
        assertThat(entry.result()).isNull();
        assertThat(entry.resultState()).isEqualTo(ResultState.MISSING);
    }

    @Test
    void keeps_the_first_row_per_workflow_when_two_runs_share_a_start_time() {
        given(List.of(row("wf-a1", "run-newer", RunStatus.FAILED, Map.of()),
                        row("wf-a1", "run-older", RunStatus.COMPLETED, Map.of())),
                Map.of("wf-a1", "Nightly QA"));

        assertThat(service.results(List.of("wf-a1")).getFirst().run().runId()).isEqualTo("run-newer");
    }

    @Test
    void deduplicates_ids_and_skips_the_query_when_nothing_is_asked_for() {
        given(List.of(), Map.of("wf-a1", "One"));

        assertThat(service.results(List.of("wf-a1", "wf-a1"))).hasSize(1);
        assertThat(service.results(List.of())).isEmpty();
    }

    @Test
    void refuses_ids_outside_the_pattern_and_too_many_of_them() {
        assertThatThrownBy(() -> service.results(List.of("../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowIds");
        assertThatThrownBy(() -> service.results(List.of("WF-A1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.results(
                IntStream.rangeClosed(0, DashboardWorkflowService.MAX_WORKFLOWS).mapToObj(i -> "wf-" + i).toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(DashboardWorkflowService.MAX_WORKFLOWS));
        verifyNoInteractions(runRepository, workflowRepository);
    }

    /** The two lean projections are the only thing the service asks for — never a run with its steps. */
    @Test
    void asks_both_repositories_with_the_distinct_ids() {
        given(List.of(), Map.of());

        service.results(List.of("wf-b1", "wf-a1", "wf-b1"));

        verify(runRepository).findLatestRunPerWorkflow(List.of("wf-b1", "wf-a1"));
        verify(workflowRepository).findIdAndNameByIdIn(List.of("wf-b1", "wf-a1"));
        verifyNoMoreInteractions(runRepository, workflowRepository);
    }
}
