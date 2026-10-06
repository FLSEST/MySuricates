package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowSealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowSealController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowSealControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowSealService sealService;
    @MockitoBean
    private WorkflowMapper workflowMapper;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private static WorkflowResponse response(String id) {
        return new WorkflowResponse(
                id, "proj-1", "Test Project", null, null,
                "Test Workflow", "A test workflow",
                null, List.of(), "1.0.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void sealGibtDenGemapptenWorkflowZurueck() throws Exception {
        Workflow sealed = Workflow.builder().id("wf-1").name("Test Workflow").build();
        when(sealService.seal("wf-1")).thenReturn(sealed);
        when(workflowMapper.toResponse(sealed)).thenReturn(response("wf-1"));

        mockMvc.perform(post("/api/v1/workflows/wf-1/seal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-1"));

        verify(sealService).seal("wf-1");
        verify(sealService, never()).unseal("wf-1");
    }

    @Test
    void unsealRuftUnsealAuf() throws Exception {
        Workflow open = Workflow.builder().id("wf-2").name("Test Workflow").build();
        when(sealService.unseal("wf-2")).thenReturn(open);
        when(workflowMapper.toResponse(open)).thenReturn(response("wf-2"));

        mockMvc.perform(post("/api/v1/workflows/wf-2/unseal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-2"));

        verify(sealService, never()).seal("wf-2");
    }

    @Test
    void unbekannterWorkflowIst404() throws Exception {
        when(sealService.seal("nope")).thenThrow(new ResourceNotFoundException("Workflow", "nope"));

        mockMvc.perform(post("/api/v1/workflows/nope/seal"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Workflow not found with id: nope"));
    }

    @Test
    void nurPostIstErlaubt() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/wf-1/seal"))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Without the hardening switch the endpoint must not be reachable — the module guard takes care of that. */
    @Test
    void controllerHaengtAmSiegelModul() {
        RequiresModule requirement = WorkflowSealController.class.getAnnotation(RequiresModule.class);
        assertThat(requirement).isNotNull();
        assertThat(requirement.value()).isEqualTo(ModuleCatalog.SEALING);
    }
}
