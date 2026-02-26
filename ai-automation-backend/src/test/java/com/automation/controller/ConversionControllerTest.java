package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.conversion.WorkflowConversionService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.conversion.WorkflowSuggestion;
import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConversionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ConversionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowConversionService conversionService;

    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void suggest_validRunId_returnsOk() throws Exception {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "npm test", "on_failure", "stop"))
                .build();

        WorkflowSuggestion suggestion = new WorkflowSuggestion(
                "Auto: Run tests", "Generated from agentic run",
                List.of(block), "Run tests", 1, true);

        when(conversionService.suggestWorkflow("run-1")).thenReturn(suggestion);

        mockMvc.perform(get("/api/v1/conversions/runs/run-1/suggest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.suggestedName").value("Auto: Run tests"))
                .andExpect(jsonPath("$.blocks[0].type").value("simple"))
                .andExpect(jsonPath("$.originalGoal").value("Run tests"))
                .andExpect(jsonPath("$.agenticStepsAnalyzed").value(1));
    }

    @Test
    void suggest_unknownRunId_returns404() throws Exception {
        when(conversionService.suggestWorkflow("unknown"))
                .thenThrow(new ResourceNotFoundException("WorkflowRun", "unknown"));

        mockMvc.perform(get("/api/v1/conversions/runs/unknown/suggest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWorkflow_validRequest_returnsOk() throws Exception {
        WorkflowResponse response = new WorkflowResponse(
                "wf-new", "proj-1", "Project", null, null, "My Workflow",
                "desc", null, List.of(), null, Map.of(), List.of(), 0, null, null, null, Map.of(), null, null);

        when(conversionService.createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow"))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/conversions/runs/run-1/create-workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"proj-1\",\"name\":\"My Workflow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-new"))
                .andExpect(jsonPath("$.name").value("My Workflow"));
    }

    @Test
    void createWorkflow_missingProjectId_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/conversions/runs/run-1/create-workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Workflow\"}"))
                .andExpect(status().isBadRequest());
    }
}
