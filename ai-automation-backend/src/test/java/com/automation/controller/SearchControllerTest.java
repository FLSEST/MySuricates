package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.search.SemanticWorkflowSearchService;
import com.automation.service.search.SemanticWorkflowSearchService.SemanticHit;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SearchControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WorkflowService workflowService;
    @MockitoBean private WorkflowMapper workflowMapper;
    @MockitoBean private SemanticWorkflowSearchService semanticSearchService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    // ---------------- GET /api/v1/search ----------------

    @Test
    void search_returnsEmpty_whenQueryIsNull() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows").isArray())
                .andExpect(jsonPath("$.workflows.length()").value(0));
    }

    @Test
    void search_returnsEmpty_whenQueryIsBlank() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows.length()").value(0));
    }

    @Test
    void search_returnsWorkflows_whenQueryMatches() throws Exception {
        Workflow w1 = Workflow.builder().id("w-1").name("Build Pipeline").description("ci/cd").build();
        when(workflowService.findAll()).thenReturn(List.of(w1));
        // Die Suche mappt seit TODO 13d1e2a2 gebuendelt (eine GROUP-BY-Abfrage fuer die
        // Run-Zahlen statt einer je Treffer) — deshalb hier die Listen-Variante stubben.
        when(workflowMapper.toResponseListWithRunCounts(anyList())).thenAnswer(inv -> {
            List<Workflow> workflows = inv.getArgument(0);
            return workflows.stream().map(w -> minimalResponse(w.getId(), w.getName())).toList();
        });

        mockMvc.perform(get("/api/v1/search").param("q", "build"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[0].id").value("w-1"));
    }

    @Test
    void search_returnsEmpty_whenTypesDoesNotIncludeWorkflow() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "anything")
                        .param("types", "skill,integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows.length()").value(0));
    }

    // ---------------- POST /api/v1/search/workflows/semantic ----------------

    @Test
    void semantic_returnsNoContent_whenServiceDisabled() throws Exception {
        when(semanticSearchService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/v1/search/workflows/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "find me workflows", "limit", 3))))
                .andExpect(status().isNoContent());
    }

    @Test
    void semantic_returnsEmpty_whenNoHits() throws Exception {
        when(semanticSearchService.isEnabled()).thenReturn(true);
        when(semanticSearchService.search(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/search/workflows/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "deploy something", "limit", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void semantic_returnsHits_withWorkflowNames() throws Exception {
        when(semanticSearchService.isEnabled()).thenReturn(true);
        SemanticHit hit = new SemanticHit("w-42", "matches deployment intent", 0.87);
        when(semanticSearchService.search(anyString(), anyInt())).thenReturn(List.of(hit));

        Workflow w = Workflow.builder().id("w-42").name("Deploy to Prod").build();
        when(workflowService.findAll()).thenReturn(List.of(w));

        mockMvc.perform(post("/api/v1/search/workflows/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "deploy", "limit", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId").value("w-42"))
                .andExpect(jsonPath("$[0].workflowName").value("Deploy to Prod"))
                .andExpect(jsonPath("$[0].reason").value("matches deployment intent"))
                .andExpect(jsonPath("$[0].score").value(0.87));
    }

    @Test
    void semantic_usesUnknownPlaceholder_whenWorkflowNotFound() throws Exception {
        when(semanticSearchService.isEnabled()).thenReturn(true);
        SemanticHit hit = new SemanticHit("w-missing", "stale embedding", 0.5);
        when(semanticSearchService.search(anyString(), anyInt())).thenReturn(List.of(hit));
        when(workflowService.findAll()).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/search/workflows/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "x", "limit", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowName").value("(unknown)"));
    }

    @Test
    void semantic_returnsOkEmpty_whenQueryIsBlank() throws Exception {
        // Controller has no @Valid on @RequestBody — blank query handled by guard → 200 OK empty
        when(semanticSearchService.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/v1/search/workflows/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "", "limit", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static WorkflowResponse minimalResponse(String id, String name) {
        return new WorkflowResponse(id, null, null, null, null, name, null, null,
                null, null, null, null, null, 0L, null, null, null, null, null,
                null, null, null, null, null);
    }
}
