package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.TemplateVerificationService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(WorkflowTemplateController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowTemplateControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WorkflowTemplateService templateService;
    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean private WorkflowTemplateMapper templateMapper;
    @MockitoBean private TemplateVerificationService verificationService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private WorkflowTemplateResponse sampleResponse() {
        return new WorkflowTemplateResponse("t-1", Map.of("en", "Test"), Map.of("en", "Desc"),
                "icon", "devops", List.of(), null, null, false, 0, null,
                List.of(), null, Instant.now(), Instant.now());
    }

    @Test
    void listTemplates_returnsAll() throws Exception {
        when(templateService.findAll()).thenReturn(List.of());
        when(templateMapper.toResponseList(any())).thenReturn(List.of(sampleResponse()));
        when(verificationService.enrichAllWithVerifications(any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/workflow-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"));
    }

    @Test
    void getTemplate_returnsById() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.findById("t-1")).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());
        when(verificationService.enrichWithVerifications(any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/workflow-templates/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-1"));
    }

    @Test
    void getTemplate_withLocale_returnsTheStepsOfABilingualTemplateInThatLanguage() throws Exception {
        String source = com.automation.util.LocalizedJson.localizedSource(objectMapper.readTree("""
                {"i18n": ["de", "en"], "blocks": [
  {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
  {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
   "config": {"question": {"de": "Weiter?", "en": "Continue?"},
              "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}}],
 "instructions": {"de": "Deutsch", "en": "English"}}"""));
        var template = WorkflowTemplate.builder().id("t-1").i18nSource(source).build();
        var english = new WorkflowTemplateResponse("t-1", Map.of("en", "Test"), Map.of("en", "Desc"),
                "icon", "EXAMPLE", List.of(BlockDefinition.builder().type("shell").name("measure").build()),
                "English", null, true, 0, null, List.of(), "1.0.0", Instant.now(), Instant.now());
        when(templateService.findById("t-1")).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(english);
        when(verificationService.enrichWithVerifications(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/api/v1/workflow-templates/t-1").param("locale", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].name").value("messen"))
                .andExpect(jsonPath("$.blocks[1].config.options[1]").value("Nein"))
                .andExpect(jsonPath("$.instructions").value("Deutsch"));

        mockMvc.perform(get("/api/v1/workflow-templates/t-1"))
                .andExpect(jsonPath("$.blocks[0].name").value("measure"))
                .andExpect(jsonPath("$.instructions").value("English"));
    }

    @Test
    void listTemplates_withLocale_localizesOnlyBilingualTemplates() throws Exception {
        String source = com.automation.util.LocalizedJson.localizedSource(objectMapper.readTree("""
                {"i18n": ["de", "en"], "blocks": [
  {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
  {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
   "config": {"question": {"de": "Weiter?", "en": "Continue?"},
              "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}}],
 "instructions": {"de": "Deutsch", "en": "English"}}"""));
        var bilingual = WorkflowTemplate.builder().id("t-1").i18nSource(source).build();
        var plain = WorkflowTemplate.builder().id("t-2").build();
        var plainResponse = new WorkflowTemplateResponse("t-2", Map.of("en", "Plain"), Map.of(), "icon", "CODE",
                List.of(BlockDefinition.builder().type("shell").name("build").build()), "Keep", null, true, 1, null,
                List.of(), "1.0.0", Instant.now(), Instant.now());
        when(templateService.findAll()).thenReturn(List.of(bilingual, plain));
        when(templateMapper.toResponseList(any())).thenReturn(List.of(sampleResponse(), plainResponse));
        when(verificationService.enrichAllWithVerifications(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/api/v1/workflow-templates").param("locale", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].blocks[0].name").value("messen"))
                .andExpect(jsonPath("$[1].blocks[0].name").value("build"))
                .andExpect(jsonPath("$[1].instructions").value("Keep"));
    }

    @Test
    void createTemplate_returns201() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.create(any())).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());

        var request = Map.of("name", Map.of("en", "Test"), "blocks", List.of());

        mockMvc.perform(post("/api/v1/workflow-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteTemplate_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/workflow-templates/t-1"))
                .andExpect(status().isNoContent());
        verify(templateService).delete("t-1");
    }

    @Test
    void createFromWorkflow_returns201() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.createFromWorkflow("w-1")).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/workflow-templates/from-workflow/w-1"))
                .andExpect(status().isCreated());
    }
}
