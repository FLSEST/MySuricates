package com.automation.service.assistant;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TODO 4e152029: confirmation cards name the object of an action instead of a shortened id. */
@ExtendWith(MockitoExtension.class)
class PendingToolDescriberTest {

    private static final String WF_ID = "76662293-aaaa-bbbb-cccc-000000000001";
    private static final String RUN_ID = "11112222-aaaa-bbbb-cccc-000000000002";
    private static final String TODO_ID = "33334444-aaaa-bbbb-cccc-000000000003";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private TodoRepository todoRepository;

    private PendingToolDescriber describer;

    @BeforeEach
    void setUp() {
        describer = new PendingToolDescriber(new ObjectMapper(), workflowRepository, workflowRunRepository, todoRepository);
        lenient().when(workflowRepository.findIdAndNameByIdIn(any())).thenReturn(List.of());
        lenient().when(workflowRunRepository.findWorkflowRefsByRunIds(any())).thenReturn(List.of());
        lenient().when(todoRepository.findById(any())).thenReturn(Optional.empty());
    }

    private void workflowNamed(String id, String name) {
        when(workflowRepository.findIdAndNameByIdIn(List.of(id))).thenReturn(List.<Object[]>of(new Object[]{id, name}));
    }

    @Test
    void createTodo_titleAndUppercasedPriority() {
        PendingToolLabel label = describer.describe("create_todo", "{\"title\":\"Call Anna\",\"priority\":\"high\"}");

        assertThat(label.subject()).isEqualTo("Call Anna");
        assertThat(label.params()).containsExactlyEntriesOf(Map.of("priority", "HIGH"));
        assertThat(label.description()).isEqualTo("Create TODO: \"Call Anna\" (priority: HIGH)");
        verifyNoInteractions(workflowRepository, workflowRunRepository, todoRepository);
    }

    @Test
    void createTodo_withoutPriority_defaultsToMedium() {
        PendingToolLabel label = describer.describe("create_todo", "{\"title\":\"X\"}");

        assertThat(label.params()).containsEntry("priority", "MEDIUM");
    }

    @Test
    void updateTodoStatus_resolvesTodoTitle() {
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(Todo.builder().id(TODO_ID).title("Fix login").build()));

        PendingToolLabel label = describer.describe("update_todo_status",
                "{\"id\":\"" + TODO_ID + "\",\"status\":\"done\"}");

        assertThat(label.subject()).isEqualTo("Fix login");
        assertThat(label.params()).containsExactlyEntriesOf(Map.of("status", "DONE"));
        assertThat(label.description()).isEqualTo("Change status of TODO \"Fix login\" → DONE");
    }

    @Test
    void updateTodoStatus_unknownTodo_fallsBackToShortId() {
        PendingToolLabel label = describer.describe("update_todo_status",
                "{\"id\":\"" + TODO_ID + "\",\"status\":\"IN_PROGRESS\"}");

        assertThat(label.subject()).isEqualTo("33334444…");
        assertThat(label.params()).containsEntry("status", "IN_PROGRESS").containsEntry("ref", "id");
        assertThat(label.description()).isEqualTo("Change TODO status (ID: 33334444…) → IN_PROGRESS");
    }

    @Test
    void workflowActions_resolveWorkflowName() {
        workflowNamed(WF_ID, "Daily Report");
        String payload = "{\"workflowId\":\"" + WF_ID + "\"}";

        PendingToolLabel start = describer.describe("start_workflow", payload);
        PendingToolLabel update = describer.describe("update_workflow", payload);
        PendingToolLabel delete = describer.describe("delete_workflow", payload);

        assertThat(start.subject()).isEqualTo("Daily Report");
        assertThat(start.params()).isEmpty();
        assertThat(start.description()).isEqualTo("Start workflow \"Daily Report\"");
        assertThat(update.description()).isEqualTo("Update workflow \"Daily Report\"");
        assertThat(delete.description()).isEqualTo("DELETE workflow \"Daily Report\"");
    }

    @Test
    void workflowAction_unknownWorkflow_fallsBackToShortId() {
        PendingToolLabel label = describer.describe("start_workflow", "{\"workflowId\":\"" + WF_ID + "\"}");

        assertThat(label.subject()).isEqualTo("76662293…");
        assertThat(label.params()).containsExactlyEntriesOf(Map.of("ref", "id"));
        assertThat(label.description()).isEqualTo("Start workflow (ID: 76662293…)");
    }

    @Test
    void workflowAction_lookupThrows_fallsBackToShortId() {
        when(workflowRepository.findIdAndNameByIdIn(any())).thenThrow(new IllegalStateException("db busy"));

        PendingToolLabel label = describer.describe("delete_workflow", "{\"workflowId\":\"" + WF_ID + "\"}");

        assertThat(label.subject()).isEqualTo("76662293…");
        assertThat(label.description()).isEqualTo("DELETE workflow (ID: 76662293…)");
    }

    @Test
    void workflowAction_missingId_isQuestionMark() {
        PendingToolLabel label = describer.describe("start_workflow", "{}");

        assertThat(label.subject()).isEqualTo("?");
        assertThat(label.params()).containsEntry("ref", "id");
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void cancelRun_usesWorkflowNameSnapshotOfRun() {
        when(workflowRunRepository.findWorkflowRefsByRunIds(List.of(RUN_ID)))
                .thenReturn(List.<Object[]>of(new Object[]{RUN_ID, WF_ID, "Nightly Build"}));

        PendingToolLabel label = describer.describe("cancel_run", "{\"runId\":\"" + RUN_ID + "\"}");

        assertThat(label.subject()).isEqualTo("Nightly Build");
        assertThat(label.params()).isEmpty();
        assertThat(label.description()).isEqualTo("Cancel run of \"Nightly Build\"");
    }

    @Test
    void cancelRun_withoutSnapshot_usesCurrentWorkflowName() {
        when(workflowRunRepository.findWorkflowRefsByRunIds(List.of(RUN_ID)))
                .thenReturn(List.<Object[]>of(new Object[]{RUN_ID, WF_ID, null}));
        workflowNamed(WF_ID, "Daily Report");

        PendingToolLabel label = describer.describe("cancel_run", "{\"runId\":\"" + RUN_ID + "\"}");

        assertThat(label.subject()).isEqualTo("Daily Report");
    }

    @Test
    void cancelRun_unknownRun_fallsBackToShortId() {
        PendingToolLabel label = describer.describe("cancel_run", "{\"runId\":\"" + RUN_ID + "\"}");

        assertThat(label.subject()).isEqualTo("11112222…");
        assertThat(label.params()).containsEntry("ref", "id");
        assertThat(label.description()).isEqualTo("Cancel run (ID: 11112222…)");
    }

    @Test
    void createWorkflowAndChain_useNameFromPayload() {
        PendingToolLabel wf = describer.describe("create_workflow", "{\"name\":\"Inbox Triage\"}");
        PendingToolLabel chain = describer.describe("create_chain", "{\"name\":\"Release\",\"entries\":[]}");

        assertThat(wf.subject()).isEqualTo("Inbox Triage");
        assertThat(wf.description()).isEqualTo("Create workflow: \"Inbox Triage\"");
        assertThat(chain.subject()).isEqualTo("Release");
        assertThat(chain.description()).isEqualTo("Create chain: \"Release\"");
        verifyNoInteractions(workflowRepository, workflowRunRepository, todoRepository);
    }

    @Test
    void createWorkflow_withoutName_hasNoSubject() {
        PendingToolLabel label = describer.describe("create_workflow", "{}");

        assertThat(label.subject()).isNull();
        assertThat(label.description()).isEqualTo("Create workflow: \"?\"");
    }

    @Test
    void unknownType_isTypeLabelOnly() {
        PendingToolLabel label = describer.describe("launch_rocket", "{\"x\":1}");

        assertThat(label.description()).isEqualTo("launch_rocket");
        assertThat(label.subject()).isNull();
        assertThat(label.params()).isEmpty();
    }

    @Test
    void invalidJson_isTypeLabelOnly() {
        PendingToolLabel label = describer.describe("start_workflow", "{not json");

        assertThat(label.description()).isEqualTo("start_workflow");
        assertThat(label.subject()).isNull();
        assertThat(label.params()).isEmpty();
        verifyNoInteractions(workflowRepository);
    }
}
