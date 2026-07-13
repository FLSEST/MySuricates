package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.export.ImportedWorkflow;
import com.automation.service.export.ProcessFormatImporter;
import com.automation.service.export.bpmn.BpmnImportException;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowImportController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowImportControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WorkflowService workflowService;
    @MockitoBean private WorkflowMapper workflowMapper;
    @MockitoBean private ProcessFormatImporter bpmnImportAdapter;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;
    @MockitoBean private ActivityLogService activityLogService;

    @Test
    void importiertBpmnAlsNeuenWorkflowImProjekt() throws Exception {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setConfig(Map.of("command", "ls"));
        Workflow created = Workflow.builder().id("new-1").name("Importiert").build();
        when(bpmnImportAdapter.importFrom("<definitions/>")).thenReturn(new ImportedWorkflow("Importiert", List.of(block)));
        when(workflowService.create(any())).thenReturn(created);
        when(workflowMapper.toResponse(created)).thenReturn(new WorkflowResponse(
                "new-1", "proj-1", "Projekt", null, null, "Importiert", null,
                null, List.of(), "1.0.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, Instant.now(), Instant.now(), null));

        mockMvc.perform(post("/api/v1/workflows/import/bpmn").param("projectId", "proj-1")
                        .contentType(MediaType.APPLICATION_XML).content("<definitions/>"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-1"))
                .andExpect(jsonPath("$.name").value("Importiert"));

        ArgumentCaptor<CreateWorkflowRequest> captor = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo("proj-1");
        assertThat(captor.getValue().name()).isEqualTo("Importiert");
        assertThat(captor.getValue().blocks()).hasSize(1);
        assertThat(captor.getValue().blocks().get(0).getType()).isEqualTo("shell");
    }

    @Test
    void fremdBpmnLiefert400MitMeldungUndLegtNichtsAn() throws Exception {
        when(bpmnImportAdapter.importFrom(anyString()))
                .thenThrow(new BpmnImportException("Diese BPMN stammt nicht aus MySuricates"));

        mockMvc.perform(post("/api/v1/workflows/import/bpmn").param("projectId", "proj-1")
                        .contentType(MediaType.APPLICATION_XML).content("<definitions/>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error").value("Diese BPMN stammt nicht aus MySuricates"));

        verify(workflowService, never()).create(any());
    }

    @Test
    void fehlendeProjectIdLiefert400() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/import/bpmn")
                        .contentType(MediaType.APPLICATION_XML).content("<definitions/>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(workflowService, never()).create(any());
    }
}
