package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.ChainEntry;
import com.automation.entity.WorkflowChain;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.BuiltInChainTemplate;
import com.automation.service.chain.ChainTemplateService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChainTemplateController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ChainTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChainTemplateService chainTemplateService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private BuiltInChainTemplate createTemplate() {
        return new BuiltInChainTemplate(
                "ci-cd-pipeline-chain",
                Map.of("de", "CI/CD Pipeline", "en", "CI/CD Pipeline"),
                Map.of("de", "Beschreibung", "en", "Description"),
                "rocket",
                List.of(
                        new BuiltInChainTemplate.Entry("code-quality", Map.of("de", "Qualität", "en", "Quality")),
                        new BuiltInChainTemplate.Entry("deploy-pipeline", Map.of("de", "Deploy", "en", "Deploy"))
                )
        );
    }

    @Test
    void listTemplates_returnsOk() throws Exception {
        when(chainTemplateService.findAll()).thenReturn(List.of(createTemplate()));

        mockMvc.perform(get("/api/v1/chain-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ci-cd-pipeline-chain"))
                .andExpect(jsonPath("$[0].name.de").value("CI/CD Pipeline"))
                .andExpect(jsonPath("$[0].entries").isArray())
                .andExpect(jsonPath("$[0].entries[0].templateId").value("code-quality"));
    }

    @Test
    void listTemplates_emptyList_returnsOk() throws Exception {
        when(chainTemplateService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/chain-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getTemplate_existingId_returnsOk() throws Exception {
        when(chainTemplateService.findById("ci-cd-pipeline-chain")).thenReturn(createTemplate());

        mockMvc.perform(get("/api/v1/chain-templates/ci-cd-pipeline-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ci-cd-pipeline-chain"))
                .andExpect(jsonPath("$.icon").value("rocket"));
    }

    @Test
    void getTemplate_nonExistent_returns404() throws Exception {
        when(chainTemplateService.findById("unknown"))
                .thenThrow(new NoSuchElementException("Chain template not found: unknown"));

        mockMvc.perform(get("/api/v1/chain-templates/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void installTemplate_validRequest_returns201() throws Exception {
        WorkflowChain chain = WorkflowChain.builder()
                .id("chain-1")
                .name("CI/CD Pipeline")
                .entries(List.of(
                        ChainEntry.builder().workflowId("wf-1").build(),
                        ChainEntry.builder().workflowId("wf-2").build()
                ))
                .build();
        when(chainTemplateService.install(eq("ci-cd-pipeline-chain"), eq("proj-1"), any()))
                .thenReturn(chain);

        mockMvc.perform(post("/api/v1/chain-templates/ci-cd-pipeline-chain/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1", "chainName": "My Chain"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chainId").value("chain-1"))
                .andExpect(jsonPath("$.chainName").value("CI/CD Pipeline"))
                .andExpect(jsonPath("$.workflowIds[0]").value("wf-1"))
                .andExpect(jsonPath("$.workflowIds[1]").value("wf-2"));
    }

    @Test
    void installTemplate_missingProjectId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/chain-templates/ci-cd-pipeline-chain/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chainName": "test"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void installTemplate_nonExistentTemplate_returns404() throws Exception {
        when(chainTemplateService.install(eq("unknown"), any(), any()))
                .thenThrow(new NoSuchElementException("Chain template not found: unknown"));

        mockMvc.perform(post("/api/v1/chain-templates/unknown/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1"}
                                """))
                .andExpect(status().isNotFound());
    }
}
