package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.CapabilityResponse;
import com.automation.dto.response.ExportSkillResponse;
import com.automation.dto.response.SkillResponse;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.exception.ErrorCode;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.HardeningException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.SkillMapper;
import com.automation.service.skill.SkillImportExportService;
import com.automation.service.skill.SkillService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(SkillController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SkillService skillService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private SkillImportExportService importExportService;

    @MockitoBean
    private SkillMapper skillMapper;

    @MockitoBean
    private com.automation.service.workflow.WorkflowSealStatusService sealStatusService;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private Skill testSkill;
    private SkillResponse testResponse;

    @BeforeEach
    void setUp() {
        testSkill = Skill.builder()
                .id("skill-1")
                .name("Document Analyzer")
                .description("Analyzes documents")
                .category(SkillCategory.DOCUMENT)
                .builtin(true)
                .build();

        testResponse = new SkillResponse(
                "skill-1", "Document Analyzer", "Analyzes documents", null,
                SkillCategory.DOCUMENT, "AI context", Map.of(), true,
                "1.0.0", List.of(), Instant.now()
        );
    }

    @Test
    void listSkills_returnsOk() throws Exception {
        when(skillService.findAll()).thenReturn(List.of(testSkill));
        when(skillMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("skill-1"))
                .andExpect(jsonPath("$[0].name").value("Document Analyzer"));
    }

    @Test
    void getSkill_existingId_returnsOk() throws Exception {
        when(skillService.findById("skill-1")).thenReturn(testSkill);
        when(skillMapper.toResponse(testSkill)).thenReturn(testResponse);

        mockMvc.perform(get("/api/v1/skills/skill-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("skill-1"));
    }

    @Test
    void getSkill_nonExistent_returns404() throws Exception {
        when(skillService.findById("unknown"))
                .thenThrow(new ResourceNotFoundException("Skill", "unknown"));

        mockMvc.perform(get("/api/v1/skills/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCapabilities_returnsOk() throws Exception {
        Capability cap = Capability.builder()
                .id("cap-1").name("ocr").description("Extract text").build();
        CapabilityResponse capResponse = new CapabilityResponse(
                "cap-1", "ocr", "Extract text", Map.of(), Map.of(), false, false
        );

        when(skillService.findCapabilities("skill-1")).thenReturn(List.of(cap));
        when(skillMapper.toCapabilityResponseList(any())).thenReturn(List.of(capResponse));

        mockMvc.perform(get("/api/v1/skills/skill-1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ocr"));
    }

    @Test
    void createSkill_validRequest_returns201() throws Exception {
        when(skillService.create(any())).thenReturn(testSkill);
        when(skillMapper.toResponse(testSkill)).thenReturn(testResponse);

        String body = """
                {
                    "name": "Custom Skill",
                    "description": "A custom skill",
                    "category": "CODE",
                    "aiContext": "You are a custom skill."
                }
                """;

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("skill-1"));
    }

    @Test
    void createSkill_missingName_returns400() throws Exception {
        String body = """
                {
                    "category": "CODE"
                }
                """;

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSkill_missingCategory_returns400() throws Exception {
        String body = """
                {
                    "name": "Custom Skill"
                }
                """;

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateSkill_validRequest_returns200() throws Exception {
        when(skillService.update(eq("skill-1"), any())).thenReturn(testSkill);
        when(skillMapper.toResponse(testSkill)).thenReturn(testResponse);

        mockMvc.perform(put("/api/v1/skills/skill-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Renamed", "category": "RESEARCH", "aiContext": "ctx"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("skill-1"));

        verify(skillService).update(eq("skill-1"), argThat(r -> r.name().equals("Renamed")
                && r.category() == SkillCategory.RESEARCH && r.aiContext().equals("ctx")));
    }

    @Test
    void updateSkill_blankName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/skills/skill-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " ", "category": "CODE"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(skillService);
    }

    @Test
    void updateSkill_pinnedBySeal_returns409WithCode() throws Exception {
        when(skillService.update(eq("skill-1"), any())).thenThrow(new HardeningException(
                ErrorCode.SKILL_PINNED_BY_SEAL, "Skill 'X' is used by sealed workflow(s) Deploy"));

        mockMvc.perform(put("/api/v1/skills/skill-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "X", "category": "CODE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_PINNED_BY_SEAL"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Deploy")));
    }

    @Test
    void updateSkill_builtinSkill_returns409() throws Exception {
        when(skillService.update(eq("skill-1"), any()))
                .thenThrow(new IllegalStateException("Cannot edit built-in skill: Document Analyzer"));

        mockMvc.perform(put("/api/v1/skills/skill-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "X", "category": "CODE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void deleteSkill_existingId_returns204() throws Exception {
        doNothing().when(skillService).delete("skill-1");

        mockMvc.perform(delete("/api/v1/skills/skill-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteSkill_builtinSkill_returns409() throws Exception {
        doThrow(new IllegalStateException("Cannot delete built-in skill"))
                .when(skillService).delete("skill-1");

        mockMvc.perform(delete("/api/v1/skills/skill-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void exportSkill_returnsOk() throws Exception {
        ExportSkillResponse exportResponse = new ExportSkillResponse(
                "Document Analyzer", "Analyzes documents", null,
                SkillCategory.DOCUMENT, "AI context", Map.of(), "1.0.0", List.of()
        );
        when(importExportService.exportSkill("skill-1")).thenReturn(exportResponse);

        mockMvc.perform(get("/api/v1/skills/export/skill-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Document Analyzer"));
    }

    @Test
    void importSkill_validRequest_returns201() throws Exception {
        when(importExportService.importSkill(any())).thenReturn(testSkill);
        when(skillMapper.toResponse(testSkill)).thenReturn(testResponse);

        String body = """
                {
                    "name": "Imported Skill",
                    "description": "An imported skill",
                    "category": "DOCUMENT",
                    "aiContext": "context",
                    "version": "1.0.0",
                    "capabilities": [
                        {
                            "name": "ocr",
                            "description": "Extract text",
                            "handlerClass": "com.Handler",
                            "async": false
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/skills/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void importSkill_capabilityWithoutName_returns400() throws Exception {
        String body = """
                {
                    "name": "Imported Skill",
                    "category": "DOCUMENT",
                    "capabilities": [ { "description": "no name" } ]
                }
                """;

        mockMvc.perform(post("/api/v1/skills/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(importExportService);
    }

    @Test
    void sealPins_returnsSealedWorkflowsPerSkill() throws Exception {
        when(sealStatusService.sealedWorkflowsBySkill()).thenReturn(java.util.Map.of("skill-1", java.util.List.of("Deploy")));

        mockMvc.perform(get("/api/v1/skills/seal-pins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['skill-1'][0]").value("Deploy"));
    }
}
