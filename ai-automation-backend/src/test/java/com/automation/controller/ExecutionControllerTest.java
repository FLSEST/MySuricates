package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.execution.ExecutionCommandService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.RunDeletionService;
import com.automation.service.workflow.WorkflowExecutor;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
    private WorkflowExecutor workflowExecutor;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private PendingTodoApprovalService pendingTodoApprovalService;
    @MockitoBean
    private RunDeletionService runDeletionService;

    @MockitoBean
    private ExecutionQueryService executionQueryService;

    @MockitoBean
    private ExecutionCommandService executionCommandService;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private static PageResponse<WorkflowRunResponse> emptyPage(int page, int size) {
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

    @Test
    void getExecution_nonExistent_returns404() throws Exception {
        when(executionQueryService.getExecutionResponse("unknown"))
                .thenThrow(new ResourceNotFoundException("Execution", "unknown"));

        mockMvc.perform(get("/api/v1/executions/unknown"))
                .andExpect(status().isNotFound());
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

    @Test
    void pauseExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/pause"))
                .andExpect(status().isOk());

        verify(workflowExecutor).pauseExecution("run-1");
    }

    @Test
    void resumeExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/resume"))
                .andExpect(status().isOk());

        verify(workflowExecutor).resumeExecution("run-1");
    }

    @Test
    void cancelExecution_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/cancel"))
                .andExpect(status().isOk());

        verify(workflowExecutor).cancelExecution("run-1");
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

        verify(workflowExecutor).resolveApproval("run-1", "Yes");
    }

    @Test
    void approveExecution_answerNo_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "No"}
                                """))
                .andExpect(status().isOk());

        verify(workflowExecutor).resolveApproval("run-1", "No");
    }

    @Test
    void approveExecution_customStringAnswer_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/executions/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "Deploy to staging instead"}
                                """))
                .andExpect(status().isOk());

        verify(workflowExecutor).resolveApproval("run-1", "Deploy to staging instead");
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
                .when(workflowExecutor).resolveApproval("unknown", "Yes");

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

        verify(workflowExecutor).resolveApproval("exec-42", "Approved");
    }

    @Test
    void getPendingApproval_noPending_returnsPendingFalse() throws Exception {
        when(workflowExecutor.getPendingApproval("run-1")).thenReturn(null);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false));
    }

    @Test
    void getPendingApproval_hasPending_returnsPendingTrue() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("block-1").question("Approve?")
                .options(List.of("Yes", "No")).stepIndex(2).build();
        when(workflowExecutor.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.question").value("Approve?"))
                .andExpect(jsonPath("$.options[0]").value("Yes"));
    }

    @Test
    void getPendingApproval_humanInput_includesExtraFields() throws Exception {
        var approval = ApprovalRequest.builder()
                .blockId("block-1").question("Enter name:")
                .options(List.of()).stepIndex(0)
                .type("human-input").inputType("text")
                .defaultValue("default").variableName("name")
                .required(true).validationPattern("\\w+").build();
        when(workflowExecutor.getPendingApproval("run-1")).thenReturn(approval);

        mockMvc.perform(get("/api/v1/executions/run-1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("human-input"))
                .andExpect(jsonPath("$.inputType").value("text"))
                .andExpect(jsonPath("$.variableName").value("name"))
                .andExpect(jsonPath("$.required").value(true));
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
        when(workflowExecutor.executeAdHocCommand("run-1", "ls -la")).thenReturn("file1.txt\nfile2.txt");

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
