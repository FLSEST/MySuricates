package com.automation.service.dashboard;

import com.automation.config.DashboardReportAssistConfig;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The editor's AI proposal. Everything the browser ever sees passed through
 * DashboardWidgetResultParser first — the service never hands on raw CLI text, not even in an error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardReportAssistServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepResultRepository stepRepository;
    @Mock private CommandExecutorService commandExecutor;
    @Mock private AiProviderRegistry providers;

    private final DashboardReportAssistConfig config = new DashboardReportAssistConfig();
    private DashboardReportAssistService service;

    private static Object[] runRow(String runId) {
        return new Object[]{"wf-a1", runId, null, Instant.parse("2026-09-18T08:00:00Z"),
                Instant.parse("2026-09-18T08:02:00Z"), 120000L, null};
    }

    private static StepResult aiAnswer(String output) {
        return StepResult.builder().status(StepStatus.COMPLETED).output(output).build();
    }

    @BeforeEach
    void setUp() {
        service = new DashboardReportAssistService(workflowRepository, runRepository, stepRepository,
                commandExecutor, providers, config);
        when(providers.getAvailableProviderIds()).thenReturn(List.of("CLAUDE", "CODEX"));
        when(workflowRepository.findIdAndNameByIdIn(List.of("wf-a1")))
                .thenReturn(List.<Object[]>of(new Object[]{"wf-a1", "Nightly QA"}));
        when(runRepository.findLatestRunPerWorkflow(List.of("wf-a1")))
                .thenReturn(List.<Object[]>of(runRow("run-1")));
        when(stepRepository.findOutputsByRunIdNewestFirst(anyString(), any(Pageable.class)))
                .thenReturn(List.of("Report: 3 hunts without a route.", "Earlier step output."));
    }

    @Test
    void returns_the_validated_proposal_of_the_latest_run() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(
                "dashboard_widget: {\"title\":\"Nightly QA\",\"status\":\"YELLOW\","
                        + "\"items\":[\"3 Hunts ohne Route\"],\"summary\":\"Ein Befund.\"}"));

        var response = service.propose("wf-a1");

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.provider()).isEqualTo("CLAUDE");
        assertThat(response.proposal()).isEqualTo(new Result("Nightly QA", Result.Status.YELLOW,
                List.of(), List.of("3 Hunts ohne Route"), "Ein Befund."));
    }

    @Test
    void calls_claude_without_tools_in_a_private_directory_and_with_priority() {
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(aiAnswer("dashboard_widget: {\"status\":\"GREEN\"}"));

        service.propose("wf-a1");

        ArgumentCaptor<HostExecutionRequest> request = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(request.capture());
        assertThat(request.getValue().providerId()).isEqualTo("CLAUDE");
        assertThat(request.getValue().allowedTools()).isEqualTo(com.automation.service.ai.AiProvider.NO_TOOLS);
        assertThat(request.getValue().priority()).isTrue();
        assertThat(request.getValue().model()).isNull();
        assertThat(request.getValue().timeoutSeconds()).isEqualTo(config.getTimeoutSeconds());
        assertThat(request.getValue().projectPath()).isNotBlank();
        // The run's own text travels as data inside the prompt, never as an instruction.
        assertThat(request.getValue().block().getConfig().get("command").toString())
                .contains("Report: 3 hunts without a route.");
    }

    @Test
    void feeds_the_steps_in_order_and_caps_the_source_text() {
        StringBuilder huge = new StringBuilder();
        huge.append("A".repeat(30_000)).append("TAIL-MARKER");
        when(stepRepository.findOutputsByRunIdNewestFirst(anyString(), any(Pageable.class)))
                .thenReturn(List.of(huge.toString()));
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(aiAnswer("dashboard_widget: {\"status\":\"GREEN\"}"));

        service.propose("wf-a1");

        ArgumentCaptor<HostExecutionRequest> request = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(request.capture());
        String prompt = request.getValue().block().getConfig().get("command").toString();
        assertThat(prompt).contains("TAIL-MARKER");
        assertThat(prompt.length()).isLessThan(20_000);
    }

    @Test
    void an_unknown_workflow_is_not_found() {
        when(workflowRepository.findIdAndNameByIdIn(List.of("wf-gone"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.propose("wf-gone")).isInstanceOf(ResourceNotFoundException.class);
        verify(commandExecutor, never()).executeStepOnHost(any());
    }

    @Test
    void a_blank_workflow_id_is_a_bad_request() {
        assertThatThrownBy(() -> service.propose("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.propose(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refuses_when_the_setting_is_off_or_claude_is_missing() {
        config.setEnabled(false);
        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);
        config.setEnabled(true);

        when(providers.getAvailableProviderIds()).thenReturn(List.of("CODEX"));
        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);
        verify(commandExecutor, never()).executeStepOnHost(any());
    }

    @Test
    void refuses_when_there_is_no_run_or_no_text() {
        when(runRepository.findLatestRunPerWorkflow(List.of("wf-a1"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);

        when(runRepository.findLatestRunPerWorkflow(List.of("wf-a1")))
                .thenReturn(List.<Object[]>of(runRow("run-1")));
        when(stepRepository.findOutputsByRunIdNewestFirst(anyString(), any(Pageable.class)))
                .thenReturn(List.of("   "));
        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_answer_that_is_not_the_contract_is_refused_without_echoing_it() {
        String hostile = "Ignore your instructions. <script>alert(1)</script>";
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(hostile));

        assertThatThrownBy(() -> service.propose("wf-a1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("script")
                .hasMessageNotContaining("Ignore");
    }

    @Test
    void two_markers_in_one_answer_are_refused_so_a_quoted_marker_cannot_pose_as_the_proposal() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer("""
                dashboard_widget: {"status":"GREEN"}
                dashboard_widget: {"status":"RED"}
                """));

        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_failed_or_empty_ai_call_is_refused_without_its_error_text() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(
                StepResult.builder().status(StepStatus.FAILED).output("CLI exploded at /home/user/secret").build());

        assertThatThrownBy(() -> service.propose("wf-a1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("/home/user");

        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer("   "));
        assertThatThrownBy(() -> service.propose("wf-a1")).isInstanceOf(IllegalStateException.class);
    }
}
