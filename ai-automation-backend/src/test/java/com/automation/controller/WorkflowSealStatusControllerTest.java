package com.automation.controller;

import com.automation.dto.response.WorkflowSealEventResponse;
import com.automation.dto.response.WorkflowSealStatusResponse;
import com.automation.entity.WorkflowSealEvent;
import com.automation.service.workflow.WorkflowSealHistory;
import com.automation.service.workflow.WorkflowSealStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Lesende Siegel-Endpunkte: Ereignisse mit Kettenstatus je Eintrag, Status aller Workflows. */
@ExtendWith(MockitoExtension.class)
class WorkflowSealStatusControllerTest {

    @Mock private WorkflowSealStatusService statusService;
    @Mock private WorkflowSealHistory history;

    private WorkflowSealStatusController controller() {
        return new WorkflowSealStatusController(statusService, history);
    }

    @Test
    void sealEvents_marksOnlyBrokenChainEntriesAsNotIntact() {
        WorkflowSealEvent intact = event("e-1", WorkflowSealEvent.Action.SEALED, "sig");
        WorkflowSealEvent broken = event("e-2", WorkflowSealEvent.Action.UNSEALED, null);
        when(history.brokenChainEntries()).thenReturn(List.of("e-2"));
        when(history.forWorkflow("wf-1")).thenReturn(List.of(broken, intact));

        List<WorkflowSealEventResponse> events = controller().sealEvents("wf-1");

        assertThat(events).extracting(WorkflowSealEventResponse::id).containsExactly("e-2", "e-1");
        assertThat(events).extracting(WorkflowSealEventResponse::chainIntact).containsExactly(false, true);
        assertThat(events).extracting(WorkflowSealEventResponse::action).containsExactly("UNSEALED", "SEALED");
        assertThat(events).extracting(WorkflowSealEventResponse::signed).containsExactly(false, true);
    }

    @Test
    void sealEvents_withoutHistoryIsEmpty() {
        when(history.brokenChainEntries()).thenReturn(List.of());
        when(history.forWorkflow("wf-1")).thenReturn(List.of());

        assertThat(controller().sealEvents("wf-1")).isEmpty();
    }

    @Test
    void sealStatus_returnsWhatTheServiceReports() {
        WorkflowSealStatusResponse status = new WorkflowSealStatusResponse("wf-1", "Name", "INTACT", "NONE", null,
                List.of(), true, true, 2, Instant.parse("2026-09-01T00:00:00Z"), 0, null, null);
        when(statusService.statusAll()).thenReturn(List.of(status));

        assertThat(controller().sealStatus()).containsExactly(status);
    }

    private static WorkflowSealEvent event(String id, WorkflowSealEvent.Action action, String signature) {
        return WorkflowSealEvent.builder().id(id).workflowId("wf-1").workflowName("Name").action(action)
                .occurredAt(Instant.parse("2026-09-01T00:00:00Z")).definitionHash("hash").signature(signature)
                .build();
    }
}
