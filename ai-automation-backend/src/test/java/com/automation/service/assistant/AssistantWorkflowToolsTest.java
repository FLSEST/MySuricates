package com.automation.service.assistant;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantWorkflowToolsTest {

    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private RunControlService runControlService;
    @Mock private ActivityLogService activityLogService;
    private AssistantWorkflowTools tools;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        tools = new AssistantWorkflowTools(workflowService, workflowExecutor, runControlService, mapper,
                new GeneratedWorkflowCheck(new WorkflowAnalyzer(), mapper, activityLogService));
    }

    @Test
    void createWorkflow_exactSuccessLine_andDefaultIcon() {
        when(workflowService.create(any(), eq(ChangeSource.ASSISTANT))).thenReturn(Workflow.builder().id("wf-1").name("Daily").build());

        String r = tools.createWorkflow("{\"projectId\":\"p\",\"name\":\"Daily\"}");

        assertThat(r).isEqualTo("✓ Workflow created: \"Daily\" (ID: wf-1)");
        ArgumentCaptor<CreateWorkflowRequest> req = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(req.capture(), eq(ChangeSource.ASSISTANT));
        assertThat(req.getValue().icon()).isEqualTo("⚡");
        assertThat(req.getValue().blocks()).isEmpty();
        assertThat(req.getValue().metadata()).isNull();
        assertThat(req.getValue().defaultContext()).isNull();
    }

    @Test
    void createWorkflow_missingProjectOrName_errorLine() {
        assertThat(tools.createWorkflow("{\"name\":\"x\"}"))
                .isEqualTo("⚠ create_workflow: projectId and name are required fields.");
        assertThat(tools.createWorkflow("{\"projectId\":\"p\",\"name\":\" \"}"))
                .isEqualTo("⚠ create_workflow: projectId and name are required fields.");
        verify(workflowService, never()).create(any(), eq(ChangeSource.ASSISTANT));
    }

    @Test
    void updateWorkflow_exactSuccessLine_andNullIconWhenAbsent() {
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenReturn(Workflow.builder().id("wf-1").name("Renamed").build());

        String r = tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"name\":\"Renamed\"}");

        assertThat(r).isEqualTo("✓ Workflow updated: \"Renamed\" (ID: wf-1)");
        ArgumentCaptor<UpdateWorkflowRequest> req = ArgumentCaptor.forClass(UpdateWorkflowRequest.class);
        verify(workflowService).update(eq("wf-1"), req.capture(), eq(ChangeSource.ASSISTANT));
        assertThat(req.getValue().icon()).isNull();
        assertThat(req.getValue().blocks()).isNull();
    }

    /** TODO 5e2e73a5: like PUT — a named version label is passed on, an absent one keeps the label. */
    @Test
    void updateWorkflow_passesTheVersionLabel_evenWrittenAsANumber() {
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenReturn(Workflow.builder().id("wf-1").name("W").build());

        tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"version\":\"4.4.0\"}");
        tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"version\":2}");
        tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"name\":\"W\"}");

        ArgumentCaptor<UpdateWorkflowRequest> req = ArgumentCaptor.forClass(UpdateWorkflowRequest.class);
        verify(workflowService, org.mockito.Mockito.times(3)).update(eq("wf-1"), req.capture(), eq(ChangeSource.ASSISTANT));
        assertThat(req.getAllValues()).extracting(UpdateWorkflowRequest::version).containsExactly("4.4.0", "2", null);
    }

    /** A blank label is refused by the service, as on PUT; the tool line names the reason. */
    @Test
    void updateWorkflow_refusedVersion_isReportedWithItsReason() {
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenThrow(
                new IllegalArgumentException("Invalid version: must not be blank (omit it to keep the current one)"));

        assertThat(tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"version\":\" \"}"))
                .isEqualTo("⚠ Could not update workflow: Invalid version: must not be blank (omit it to keep the current one)");
    }

    @Test
    void updateWorkflow_missingId_errorLine() {
        assertThat(tools.updateWorkflow("{\"name\":\"x\"}"))
                .isEqualTo("⚠ update_workflow: workflowId is a required field.");
    }

    @Test
    void deleteWorkflow_exactSuccessLine_usesNameBeforeDelete() {
        when(workflowService.findById("wf-1")).thenReturn(Workflow.builder().id("wf-1").name("Old").build());

        assertThat(tools.deleteWorkflow("{\"workflowId\":\"wf-1\"}"))
                .isEqualTo("✓ Workflow deleted: \"Old\" (ID: wf-1)");
        verify(workflowService).delete("wf-1");
    }

    @Test
    void deleteWorkflow_missingId_errorLine() {
        assertThat(tools.deleteWorkflow("{}")).isEqualTo("⚠ delete_workflow: workflowId is a required field.");
        verify(workflowService, never()).delete(any());
    }

    @Test
    void deleteWorkflow_serviceThrows_errorLine() {
        when(workflowService.findById("wf-1")).thenThrow(new RuntimeException("not found"));

        assertThat(tools.deleteWorkflow("{\"workflowId\":\"wf-1\"}"))
                .isEqualTo("⚠ Could not delete workflow: not found");
    }

    @Test
    void startWorkflow_exactSuccessLine_withShortRunId_andContext() {
        Workflow wf = Workflow.builder().id("wf-1").name("Daily").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);
        when(workflowExecutor.execute(eq(wf), any(), eq(TriggerSource.ASSISTANT))).thenReturn(WorkflowRun.builder().id("run-123456789").build());

        String r = tools.startWorkflow("{\"workflowId\":\"wf-1\",\"context\":{\"k\":\"v\"}}");

        assertThat(r).isEqualTo("✓ Workflow started: \"Daily\" (Run ID: run-1234…)");
        verify(workflowExecutor).execute(wf, Map.of("k", "v"), TriggerSource.ASSISTANT);
    }

    @Test
    void startWorkflow_noContext_passesEmptyMap() {
        Workflow wf = Workflow.builder().id("wf-1").name("Daily").build();
        when(workflowService.findById("wf-1")).thenReturn(wf);
        when(workflowExecutor.execute(eq(wf), any(), eq(TriggerSource.ASSISTANT))).thenReturn(WorkflowRun.builder().id("r").build());

        tools.startWorkflow("{\"workflowId\":\"wf-1\"}");

        verify(workflowExecutor).execute(wf, Map.of(), TriggerSource.ASSISTANT);
    }

    @Test
    void startWorkflow_missingId_errorLine() {
        assertThat(tools.startWorkflow("{}")).isEqualTo("⚠ start_workflow: workflowId is a required field.");
    }

    @Test
    void startWorkflow_executorThrows_errorLine() {
        when(workflowService.findById("wf-1")).thenReturn(Workflow.builder().id("wf-1").name("D").build());
        when(workflowExecutor.execute(any(), any(), eq(TriggerSource.ASSISTANT))).thenThrow(new IllegalStateException("busy"));

        assertThat(tools.startWorkflow("{\"workflowId\":\"wf-1\"}")).isEqualTo("⚠ Could not start workflow: busy");
    }

    @Test
    void cancelRun_exactSuccessLine() {
        assertThat(tools.cancelRun("{\"runId\":\"run-123456789\"}")).isEqualTo("✓ Run cancelled (ID: run-1234…)");
        verify(runControlService).cancelExecution("run-123456789");
    }

    @Test
    void cancelRun_missingOrFailing_errorLines() {
        assertThat(tools.cancelRun("{}")).isEqualTo("⚠ cancel_run: runId is a required field.");
        doThrow(new RuntimeException("gone")).when(runControlService).cancelExecution("r");
        assertThat(tools.cancelRun("{\"runId\":\"r\"}")).isEqualTo("⚠ Could not cancel run: gone");
    }

    // ── build check after create/update (TODO 326d5987) ──

    /** simple block with {@code prompt} instead of {@code command}: one HIGH and one MEDIUM finding. */
    private static Workflow withPromptInsteadOfCommand(String id, String name) {
        return Workflow.builder().id(id).name(name).blocks(new java.util.ArrayList<>(java.util.List.of(
                com.automation.entity.BlockDefinition.builder().type("simple")
                        .config(new java.util.HashMap<>(Map.of("prompt", "Fasse zusammen"))).build()))).build();
    }

    @Test
    void createWorkflow_withFindings_appendsTheBuildCheckHighFirst() {
        when(workflowService.create(any(), eq(ChangeSource.ASSISTANT))).thenReturn(withPromptInsteadOfCommand("wf-1", "Daily"));

        String r = tools.createWorkflow("{\"projectId\":\"p\",\"name\":\"Daily\",\"blocks\":[{\"type\":\"simple\",\"config\":{\"prompt\":\"Fasse zusammen\"}}]}");

        assertThat(r).startsWith("✓ Workflow created: \"Daily\" (ID: wf-1)\n\nBaufehler-Prüfung: 2 Befund(e) ab MEDIUM\n")
                .endsWith("\nMit update_workflow lassen sie sich beheben.");
        assertThat(r.indexOf("- HIGH missing-required-config-key")).isPositive()
                .isLessThan(r.indexOf("- MEDIUM unknown-config-key"));
    }

    @Test
    void updateWorkflow_withFindings_appendsTheBuildCheck() {
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.ASSISTANT))).thenReturn(withPromptInsteadOfCommand("wf-1", "Daily"));

        String r = tools.updateWorkflow("{\"workflowId\":\"wf-1\",\"name\":\"Daily\"}");

        assertThat(r).startsWith("✓ Workflow updated: \"Daily\" (ID: wf-1)\n\nBaufehler-Prüfung:")
                .contains("HIGH missing-required-config-key");
    }

    @Test
    void createWorkflow_cleanDefinition_lineStaysExactlyAsBefore() {
        Workflow clean = Workflow.builder().id("wf-1").name("Daily").blocks(new java.util.ArrayList<>(java.util.List.of(
                com.automation.entity.BlockDefinition.builder().type("simple")
                        .config(new java.util.HashMap<>(Map.of("command", "Fasse zusammen"))).build()))).build();
        when(workflowService.create(any(), eq(ChangeSource.ASSISTANT))).thenReturn(clean);

        assertThat(tools.createWorkflow("{\"projectId\":\"p\",\"name\":\"Daily\"}"))
                .isEqualTo("✓ Workflow created: \"Daily\" (ID: wf-1)");
    }
}
