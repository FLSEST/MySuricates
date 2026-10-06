package com.automation.service.execution;

import com.automation.dto.response.PendingInputResponse;
import com.automation.executor.ApprovalRequest;
import com.automation.repository.WorkflowRunProjectionRepository;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import com.automation.service.workflow.WorkflowExecutionRegistry.PendingInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingInputServiceTest {

    @Mock
    private WorkflowExecutionRegistry executionRegistry;
    @Mock
    private WorkflowRunProjectionRepository runProjections;
    @InjectMocks
    private PendingInputService service;

    @Test
    void nothingWaiting_touchesNoDatabase() {
        when(executionRegistry.listPendingInputs()).thenReturn(List.of());

        assertThat(service.listPendingInputs()).isEmpty();
        verify(runProjections, never()).findWorkflowRefsByRunIds(any());
    }

    @Test
    void waitingRuns_carryWorkflowAndTimeout_inRegistryOrder() {
        Instant since = Instant.parse("2026-09-21T12:50:00Z");
        ApprovalRequest approval = ApprovalRequest.builder()
                .blockId("approval-30").question("Satz uebernehmen?")
                .options(List.of("Ja", "Nein")).rejectOptions(List.of("Nein"))
                .stepIndex(30).requestedAt(since).timeoutMinutes(240).build();
        ApprovalRequest input = ApprovalRequest.builder()
                .blockId("human-input-2").question("Welche Datei?").type("human-input")
                .stepIndex(2).requestedAt(since.plusSeconds(60)).build();
        when(executionRegistry.listPendingInputs()).thenReturn(List.of(
                new PendingInput("run-1", approval), new PendingInput("run-gone", input)));
        when(runProjections.findWorkflowRefsByRunIds(List.of("run-1", "run-gone")))
                .thenReturn(List.<Object[]>of(new Object[] {"run-1", "wf-1", "Rundgang-Illustrationen erzeugen"}));

        List<PendingInputResponse> result = service.listPendingInputs();

        assertThat(result).hasSize(2);
        PendingInputResponse first = result.get(0);
        assertThat(first.runId()).isEqualTo("run-1");
        assertThat(first.workflowId()).isEqualTo("wf-1");
        assertThat(first.workflowName()).isEqualTo("Rundgang-Illustrationen erzeugen");
        assertThat(first.type()).isEqualTo("approval");
        assertThat(first.rejectOptions()).containsExactly("Nein");
        assertThat(first.requestedAt()).isEqualTo(since);
        assertThat(first.timeoutAt()).isEqualTo(Instant.parse("2026-09-21T16:50:00Z"));

        PendingInputResponse second = result.get(1);
        assertThat(second.workflowId()).isNull();
        assertThat(second.workflowName()).isNull();
        assertThat(second.options()).isEmpty();
        assertThat(second.timeoutAt()).isNull();
    }

    /** A human-input form comes with its fields, an approval without (ec913801). */
    @Test
    void humanInputForm_carriesItsFields_approvalNone() {
        Instant since = Instant.parse("2026-10-01T08:00:00Z");
        List<java.util.Map<String, Object>> fields = List.of(
                java.util.Map.of("variable_name", "destination", "label", "Ziel"),
                java.util.Map.of("variable_name", "nights", "input_type", "number"));
        ApprovalRequest form = ApprovalRequest.builder()
                .blockId("human-input-3").question("Reise planen").type("human-input")
                .stepIndex(3).requestedAt(since).fields(fields).build();
        ApprovalRequest approval = ApprovalRequest.builder()
                .blockId("approval-4").question("Weiter?").options(List.of("Ja", "Nein"))
                .stepIndex(4).requestedAt(since).build();
        when(executionRegistry.listPendingInputs()).thenReturn(List.of(
                new PendingInput("run-1", form), new PendingInput("run-2", approval)));
        when(runProjections.findWorkflowRefsByRunIds(List.of("run-1", "run-2"))).thenReturn(List.of());

        List<PendingInputResponse> result = service.listPendingInputs();

        assertThat(result.get(0).fields()).isEqualTo(fields);
        assertThat(result.get(1).fields()).isNull();
    }
}
