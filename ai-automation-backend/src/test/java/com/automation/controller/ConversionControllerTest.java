package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.security.RateLimitConfig;
import com.automation.service.conversion.WorkflowConversionService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.conversion.WorkflowSuggestion;
import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(ConversionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ConversionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowConversionService conversionService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

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
                "desc", null, List.of(), null, Map.of(), List.of(), List.of(), 0, null, null, null, null, Map.of(), null, null, null, null, null);

        // Old clients send only project and name: without blocks the server regenerates the proposal.
        when(conversionService.createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow", null, null))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/conversions/runs/run-1/create-workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"proj-1\",\"name\":\"My Workflow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-new"))
                .andExpect(jsonPath("$.name").value("My Workflow"));
    }

    /** The checked proposal from the preview is passed to the service unchanged (TODO 935af6d3). */
    @Test
    void createWorkflow_withReviewedBlocks_passesThemThrough() throws Exception {
        WorkflowResponse response = new WorkflowResponse(
                "wf-new", "proj-1", "Project", null, null, "Geprüft",
                "Aus der Vorschau", null, List.of(), null, Map.of(), List.of(), List.of(), 0, null, null, null, null, Map.of(), null, null, null, null, null);
        ArgumentCaptor<List<BlockDefinition>> blocks = ArgumentCaptor.captor();
        when(conversionService.createWorkflowFromSuggestion(eq("run-1"), eq("proj-1"), eq("Geprüft"),
                eq("Aus der Vorschau"), blocks.capture())).thenReturn(response);

        mockMvc.perform(post("/api/v1/conversions/runs/run-1/create-workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"proj-1","name":"Geprüft","description":"Aus der Vorschau",
                                 "blocks":[{"type":"shell","config":{"command":"npm test"}}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-new"));

        assertThat(blocks.getValue()).singleElement().satisfies(b -> {
            assertThat(b.getType()).isEqualTo("shell");
            assertThat(b.getConfig()).containsEntry("command", "npm test");
        });
    }

    @Test
    void createWorkflow_missingProjectId_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/conversions/runs/run-1/create-workflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Workflow\"}"))
                .andExpect(status().isBadRequest());
    }
}
