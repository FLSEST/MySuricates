package com.automation.controller;

import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.exception.ErrorCode;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.HardeningException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.security.RateLimitConfig;
import com.automation.service.execution.ExecutionCommandService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.execution.PendingInputService;
import com.automation.dto.response.PendingInputResponse;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.RunDeletionPolicy;
import com.automation.service.workflow.RunDeletionService;
import com.automation.service.workflow.RunLiveLogArchive;
import com.automation.service.workflow.RunLiveLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.automation.executor.ApprovalRequest;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.todo.PendingTodoApprovalService;

@WebMvcTest(ExecutionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunControlService runControlService;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private PendingTodoApprovalService pendingTodoApprovalService;
    @MockitoBean
    private RunDeletionService runDeletionService;
    /** A mock's {@code requireAllowed()} does nothing — deletion allowed, as by default. */
    @MockitoBean
    private RunDeletionPolicy runDeletionPolicy;
    @MockitoBean
    private RunLiveLogArchive runLiveLogArchive;
    @MockitoBean
    private PendingInputService pendingInputService;

    @MockitoBean
    private ExecutionQueryService executionQueryService;

    @MockitoBean
    private ExecutionCommandService executionCommandService;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    /**
     * List routes have answered with {@link WorkflowRunListItemResponse} since TODO a05c9504 - without
     * steps. The detail route still returns {@link WorkflowRunResponse} with everything.
     */
    private static PageResponse<WorkflowRunListItemResponse> emptyPage(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0L, 0);
    }

    @Test
    void getExecution_existingId_returnsOk() throws Exception {
        WorkflowRunResponse response = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, null, RunStatus.COMPLETED, List.of(),
                Instant.now(), Instant.now(), 1000L, null, null, Map.of(), Map.of(), null
        );

        when(executionQueryService.getExecutionResponse("run-1")).thenReturn(response);

        mockMvc.perform(get("/api/v1/executions/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    /** The run detail names its chain run — null outside a chain, but always present (TODO a3b89355). */
    @Test
    void getExecution_carriesTheChainRunId() throws Exception {
        WorkflowRunResponse inChain = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, null, RunStatus.COMPLETED, List.of(),
                Instant.now(), Instant.now(), 1000L, null, null, Map.of(), Map.of(), null,
                null, null, TriggerSource.CHAIN, false, null, "chain-run-7");
        WorkflowRunResponse single = new WorkflowRunResponse(
                "run-2", "wf-1", "Test Workflow", null, null, RunStatus.COMPLETED, List.of(),
                Instant.now(), Instant.now(), 1000L, null, null, Map.of(), Map.of(), null);
        when(executionQueryService.getExecutionResponse("run-1")).thenReturn(inChain);
        when(executionQueryService.getExecutionResponse("run-2")).thenReturn(single);

        mockMvc.perform(get("/api/v1/executions/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainRunId").value("chain-run-7"))
                .andExpect(jsonPath("$.triggerSource").value("CHAIN"));
        mockMvc.perform(get("/api/v1/executions/run-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainRunId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getExecution_nonExistent_returns404() throws Exception {
        when(executionQueryService.getExecutionResponse("unknown"))
                .thenThrow(new ResourceNotFoundException("Execution", "unknown"));

        mockMvc.perform(get("/api/v1/executions/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getLiveLog_returnsSnapshotOfBufferedLines() throws Exception {
        when(runLiveLogArchive.snapshot("run-1", 4)).thenReturn(new RunLiveLogService.Snapshot(
                "run-1", "buf-1", true, 3, 5, 0, Map.of(2, "Build"),
                List.of(new RunLiveLogService.LiveLine(5, 2, "tool", "Bash: mvn test", 1_700_000_000_000L))));

        mockMvc.perform(get("/api/v1/executions/run-1/live-log").param("afterSeq", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.bufferId").value("buf-1"))
                .andExpect(jsonPath("$.firstSeq").value(3))
                .andExpect(jsonPath("$.lastSeq").value(5))
                .andExpect(jsonPath("$.stepNames.2").value("Build"))
                .andExpect(jsonPath("$.lines[0].seq").value(5))
                .andExpect(jsonPath("$.lines[0].stepIndex").value(2))
                .andExpect(jsonPath("$.lines[0].kind").value("tool"))
                .andExpect(jsonPath("$.lines[0].text").value("Bash: mvn test"))
                .andExpect(jsonPath("$.lines[0].ts").value(1_700_000_000_000L))
                .andExpect(jsonPath("$.stored").value(false));
    }

    @Test
    void getLiveLog_afterEviction_servesTheStoredLog() throws Exception {
        when(runLiveLogArchive.snapshot("run-3", -1)).thenReturn(new RunLiveLogService.Snapshot(
                "run-3", "buf-3", true, 0, 0, 0, Map.of(0, "Build"),
                List.of(new RunLiveLogService.LiveLine(0, 0, "stdout", "ok", 1L)), true));

        mockMvc.perform(get("/api/v1/executions/run-3/live-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stored").value(true))
                .andExpect(jsonPath("$.lines[0].text").value("ok"));
    }

    @Test
    void getLiveLog_withoutBuffer_returnsUnavailableInsteadOf404() throws Exception {
        when(runLiveLogArchive.snapshot("run-9", -1)).thenReturn(
                new RunLiveLogService.Snapshot("run-9", null, false, 0, -1, 0, Map.of(), List.of()));

        mockMvc.perform(get("/api/v1/executions/run-9/live-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void deleteExecution_returns204AndDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/executions/run-1"))
                .andExpect(status().isNoContent());

        verify(runDeletionService).delete("run-1");
    }

    @Test
    void deleteExecution_activeRun_returns409() throws Exception {
        doThrow(new IllegalStateException("Run run-1 is still active (RUNNING) — cancel it before deleting"))
                .when(runDeletionService).delete("run-1");

        mockMvc.perform(delete("/api/v1/executions/run-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteExecution_nonExistent_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("WorkflowRun", "unknown"))
                .when(runDeletionService).delete("unknown");

        mockMvc.perform(delete("/api/v1/executions/unknown"))
                .andExpect(status().isNotFound());
    }

    /** automation.executions.allow-delete=false: no run can be deleted, existing or not. */
    @Test
    void deleteExecution_deletionDisabled_returns403AndNeverTouchesTheService() throws Exception {
        doThrow(new HardeningException(ErrorCode.RUN_DELETION_DISABLED, "Deleting runs is disabled"))
                .when(runDeletionPolicy).requireAllowed();

        mockMvc.perform(delete("/api/v1/executions/run-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RUN_DELETION_DISABLED"));

        verify(runDeletionService, never()).delete(anyString());
    }

    @Test
    void pauseExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/pause"))
                .andExpect(status().isOk());

        verify(runControlService).pauseExecution("run-1");
    }

    @Test
    void resumeExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/resume"))
                .andExpect(status().isOk());

        verify(runControlService).resumeExecution("run-1");
    }

    @Test
    void cancelExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/cancel"))
                .andExpect(status().isOk());

        verify(runControlService).cancelExecution("run-1");
    }

    // --- New tests for missing endpoints ---

    @Test
    void listExecutions_defaultPaging_returnsOk() throws Exception {
        when(executionQueryService.listExecutionResponses(0, 50, null, null, null, null))
                .thenReturn(emptyPage(0, 50));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listExecutions_withStatusFilter_returnsOk() throws Exception {
        when(executionQueryService.listExecutionResponses(0, 50, "RUNNING", null, null, null))
                .thenReturn(emptyPage(0, 50));

        mockMvc.perform(get("/api/v1/executions").param("status", "RUNNING"))
                .andExpect(status().isOk());
    }

    @Test
    void retryExecution_existingRun_returnsOk() throws Exception {
        var response = new WorkflowRunResponse(
                "run-2", "wf-1", "Test", null, null, RunStatus.RUNNING, List.of(),
                Instant.now(), null, null, null, null, Map.of(), Map.of(), null);

        when(executionCommandService.retry("run-1")).thenReturn(response);

        mockMvc.perform(post("/api/v1/executions/run-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-2"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void retryExecution_nonExistent_returns404() throws Exception {
        when(executionCommandService.retry("unknown"))
                .thenThrow(new ResourceNotFoundException("Execution", "unknown"));

        mockMvc.perform(post("/api/v1/executions/unknown/retry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resumeFromStep_returnsOk() throws Exception {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Test", null, null, RunStatus.RUNNING, List.of(),
                Instant.now(), null, null, null, null, Map.of(), Map.of(), null);

        when(executionCommandService.resumeFromStep("run-1")).thenReturn(response);

        mockMvc.perform(post("/api/v1/executions/run-1/resume-from-step"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void approveExecution_answerYes_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "Yes"}
                                """))
                .andExpect(status().isOk());

        verify(runControlService).resolveApproval("run-1", "Yes");
    }

    @Test
    void approveExecution_answerNo_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "No"}
                                """))
                .andExpect(status().isOk());

        verify(runControlService).resolveApproval("run-1", "No");
    }

    @Test
    void approveExecution_customStringAnswer_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "Deploy to staging instead"}
                                """))
                .andExpect(status().isOk());

        verify(runControlService).resolveApproval("run-1", "Deploy to staging instead");
    }

    @Test
    void approveExecution_blankAnswer_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveExecution_nullAnswer_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveExecution_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveExecution_unknownExecution_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Execution", "unknown"))
                .when(runControlService).resolveApproval("unknown", "Yes");

        mockMvc.perform(post("/api/v1/executions/unknown/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "Yes"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveExecution_delegatesCorrectIdAndAnswer() throws Exception {
        mockMvc.perform(post("/api/v1/executions/exec-42/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "Approved"}
                                """))
                .andExpect(status().isOk());

        verify(runControlService).resolveApproval("exec-42", "Approved");
    }

    @Test
    void getPendingApproval_noPending_returnsPendingFalse() throws Exception {
        when(runControlService.getPendingApproval("run-1")).thenReturn(null);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false));
    }

    @Test
    void getPendingApproval_hasPending_returnsPendingTrue() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("block-1").question("Approve?")
                .options(List.of("Yes", "No")).stepIndex(2).build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.question").value("Approve?"))
                .andExpect(jsonPath("$.options[0]").value("Yes"));
    }

    @Test
    void getPendingApproval_carriesRejectOptions() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("approval-30").question("Satz uebernehmen?")
                .options(List.of("Ja", "Nein")).rejectOptions(List.of("Nein")).stepIndex(30).build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectOptions[0]").value("Nein"));
    }

    @Test
    void approveExecution_answerNotOffered_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Answer 'No' is not one of the offered options [Ja, Nein]"))
                .when(runControlService).resolveApproval("run-1", "No");

        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "No"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("offered options")));
    }

    @Test
    void listPendingInputs_returnsWaitingRunsAcrossWorkflows() throws Exception {
        Instant since = Instant.parse("2026-09-21T12:50:00Z");
        when(pendingInputService.listPendingInputs()).thenReturn(List.of(new PendingInputResponse(
                "run-1", "wf-1", "Rundgang-Illustrationen erzeugen", "approval", "approval-30",
                "Satz uebernehmen?", List.of("Ja", "Nein"), List.of("Nein"), 30,
                since, since.plusSeconds(240 * 60))));

        mockMvc.perform(get("/api/v1/executions/pending-input"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("run-1"))
                .andExpect(jsonPath("$[0].workflowId").value("wf-1"))
                .andExpect(jsonPath("$[0].workflowName").value("Rundgang-Illustrationen erzeugen"))
                .andExpect(jsonPath("$[0].rejectOptions[0]").value("Nein"))
                .andExpect(jsonPath("$[0].timeoutAt").value("2026-09-21T16:50:00Z"))
                .andExpect(jsonPath("$[0].fields").doesNotExist());
    }

    /** Human-Input mit Formular: die Felder kommen mit (ec913801). */
    @Test
    void listPendingInputs_humanInputForm_carriesFields() throws Exception {
        Instant since = Instant.parse("2026-10-01T08:00:00Z");
        when(pendingInputService.listPendingInputs()).thenReturn(List.of(new PendingInputResponse(
                "run-1", "wf-1", "Reise", "human-input", "human-input-3", "Reise planen", List.of(), List.of(), 3,
                since, null, List.of(Map.of("variable_name", "destination", "label", "Ziel")))));

        mockMvc.perform(get("/api/v1/executions/pending-input"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fields[0].variable_name").value("destination"))
                .andExpect(jsonPath("$[0].fields[0].label").value("Ziel"));
    }

    @Test
    void listPendingInputs_nothingWaiting_returnsEmptyArray() throws Exception {
        when(pendingInputService.listPendingInputs()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/executions/pending-input"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verify(executionQueryService, org.mockito.Mockito.never()).getExecutionResponse(anyString());
    }

    /** The typed answer keeps the keys of the former map: null values stay, human-input keys stay out (contract). */
    @Test
    void getPendingApproval_approval_keepsNullKeys_andNoHumanInputKeys() throws Exception {
        var approval = ApprovalRequest.builder()
                .question("Approve?").options(List.of("Yes")).stepIndex(2).build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasKey("blockId")))
                .andExpect(jsonPath("$.blockId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.type").value("approval"))
                .andExpect(jsonPath("$.stepIndex").value(2))
                .andExpect(jsonPath("$.inputType").doesNotExist())
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void getPendingApproval_humanInputWithoutFields_omitsFields_keepsNullDefault() throws Exception {
        var approval = ApprovalRequest.builder()
                .question("Name?").options(List.of()).stepIndex(0)
                .type("human-input").inputType("text").variableName("name").build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasKey("defaultValue")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasKey("validationPattern")))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void getPendingApproval_humanInput_includesExtraFields() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("block-1").question("Enter name:")
                .options(List.of()).stepIndex(0)
                .type("human-input").inputType("text")
                .defaultValue("default").variableName("name")
                .required(true).validationPattern("\\w+").build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("human-input"))
                .andExpect(jsonPath("$.inputType").value("text"))
                .andExpect(jsonPath("$.variableName").value("name"))
                .andExpect(jsonPath("$.required").value(true));
    }

    /** A form with several fields arrives with its fields, as in the WebSocket event (ec913801). */
    @Test
    void getPendingApproval_humanInput_carriesFormFields() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("human-input-3").question("Reise planen")
                .options(List.of()).stepIndex(3)
                .type("human-input").inputType("text").variableName("user_input").required(true)
                .fields(List.of(
                        Map.of("variable_name", "destination", "label", "Ziel"),
                        Map.of("variable_name", "nights", "input_type", "number")))
                .build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.length()").value(2))
                .andExpect(jsonPath("$.fields[0].variable_name").value("destination"))
                .andExpect(jsonPath("$.fields[1].input_type").value("number"));
    }

    @Test
    void getPendingApproval_humanInputWithoutForm_hasNoFields() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("human-input-1").question("Name?").options(List.of()).stepIndex(1)
                .type("human-input").inputType("text").variableName("name").required(true).build();
        when(runControlService.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void approveTodos_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/todos/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved": true}
                                """))
                .andExpect(status().isOk());

        verify(pendingTodoApprovalService).approvePendingTodos("run-1", true);
    }

    @Test
    void executeCommand_validCommand_returnsOutput() throws Exception {
        when(runControlService.executeAdHocCommand("run-1", "ls -la")).thenReturn("file1.txt\nfile2.txt");

        mockMvc.perform(post("/api/v1/executions/run-1/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"command": "ls -la"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("file1.txt\nfile2.txt"));
    }

    @Test
    void executeCommand_blankCommand_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"command": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void executeCommand_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/command")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resumeFromStep_nonExistent_returns404() throws Exception {
        when(executionCommandService.resumeFromStep("unknown"))
                .thenThrow(new ResourceNotFoundException("Execution", "unknown"));

        mockMvc.perform(post("/api/v1/executions/unknown/resume-from-step"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveTodos_rejectFalse_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/todos/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved": false}
                                """))
                .andExpect(status().isOk());

        verify(pendingTodoApprovalService).approvePendingTodos("run-1", false);
    }

    /**
     * TODO 8697722b: a body without {@code approved} used to bind as {@code false} and discarded every
     * proposed TODO for good. A missing or null decision is a 400 now and touches nothing.
     */
    @Test
    void approveTodos_withoutADecision_returns400AndDiscardsNothing() throws Exception {
        for (String body : List.of("{}", "{\"answer\": \"Yes\"}", "{\"approved\": null}")) {
            mockMvc.perform(post("/api/v1/executions/run-1/todos/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                    .andExpect(jsonPath("$.fieldErrors.approved").exists());
        }

        verify(pendingTodoApprovalService, never()).approvePendingTodos(anyString(), anyBoolean());
    }

    @Test
    void listExecutions_withWorkflowIdFilter_returnsOk() throws Exception {
        when(executionQueryService.listExecutionResponses(0, 50, null, "wf-1", null, null))
                .thenReturn(emptyPage(0, 50));

        mockMvc.perform(get("/api/v1/executions").param("workflowId", "wf-1"))
                .andExpect(status().isOk());
    }

    @Test
    void listExecutions_withProjectIdFilter_returnsOk() throws Exception {
        when(executionQueryService.listExecutionResponses(0, 50, null, null, "proj-1", null))
                .thenReturn(emptyPage(0, 50));

        mockMvc.perform(get("/api/v1/executions").param("projectId", "proj-1"))
                .andExpect(status().isOk());
    }

    @Test
    void listExecutions_customPaging_returnsOk() throws Exception {
        when(executionQueryService.listExecutionResponses(2, 10, null, null, null, null))
                .thenReturn(emptyPage(2, 10));

        mockMvc.perform(get("/api/v1/executions").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void updateNotes_withNotes_returnsOk() throws Exception {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Test", null, null, RunStatus.COMPLETED, List.of(),
                Instant.now(), Instant.now(), null, null, null, Map.of("notes", "my note"), Map.of(), null);

        when(executionCommandService.updateNotes("run-1", "my note")).thenReturn(response);

        mockMvc.perform(patch("/api/v1/executions/run-1/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notes": "my note"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"));

        verify(executionCommandService).updateNotes("run-1", "my note");
    }

    @Test
    void updateNotes_withBlankNotes_removesNote_returnsOk() throws Exception {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Test", null, null, RunStatus.COMPLETED, List.of(),
                Instant.now(), Instant.now(), null, null, null, Map.of(), Map.of(), null);

        when(executionCommandService.updateNotes("run-1", "")).thenReturn(response);

        mockMvc.perform(patch("/api/v1/executions/run-1/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notes": ""}
                                """))
                .andExpect(status().isOk());

        verify(executionCommandService).updateNotes("run-1", "");
    }

    @Test
    void updateNotes_nonExistent_returns404() throws Exception {
        when(executionCommandService.updateNotes("unknown", "my note"))
                .thenThrow(new ResourceNotFoundException("Execution", "unknown"));

        mockMvc.perform(patch("/api/v1/executions/unknown/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notes": "my note"}
                                """))
                .andExpect(status().isNotFound());
    }
}
