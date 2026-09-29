package com.automation.controller;

import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.BundlePreview;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.bundle.ProjectBundleExportService;
import com.automation.service.project.bundle.ProjectBundleImportService;
import com.automation.service.project.bundle.ProjectBundlePreviewService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectBundleController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectBundleControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ProjectBundleExportService exportService;
    @MockitoBean private ProjectBundlePreviewService previewService;
    @MockitoBean private ProjectBundleImportService importService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;
    @MockitoBean private ActivityLogService activityLogService;

    private static ProjectBundle bundle() {
        return new ProjectBundle(ProjectBundle.FORMAT, 1, null, "0.7.8",
                new ProjectBundle.BundleProject("ShopApp", null, null, "CODE", null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void exportIsAnAttachmentWithTheSlugAsFileName() throws Exception {
        when(exportService.export("p-1")).thenReturn(
                new ProjectBundleExportService.Export(bundle(), "shopapp.mysuricates-bundle.json"));

        mockMvc.perform(get("/api/v1/projects/p-1/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"shopapp.mysuricates-bundle.json\""))
                .andExpect(jsonPath("$.format").value("mysuricates-project-bundle"))
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.project.name").value("ShopApp"))
                .andExpect(jsonPath("$.project.subtitle").isEmpty());
    }

    @Test
    void exportOfUnknownOrArchivedProjectIs404() throws Exception {
        when(exportService.export("gone")).thenThrow(new ResourceNotFoundException("Project", "gone"));

        mockMvc.perform(get("/api/v1/projects/gone/export")).andExpect(status().isNotFound());
    }

    @Test
    void previewPassesTheRawBodyAndAnswers200() throws Exception {
        String body = "{\"bundle\":{}}";
        when(previewService.preview(body)).thenReturn(new BundlePreview(new BundlePreview.ProjectInfo("ShopApp", true),
                List.of(), List.of(), List.of(), List.of(), List.of("w")));

        mockMvc.perform(post("/api/v1/projects/import/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.nameTaken").value(true))
                .andExpect(jsonPath("$.warnings[0]").value("w"));
    }

    @Test
    void invalidBundleIs400WithTheMessage() throws Exception {
        String body = "{\"bundle\":{\"format\":\"x\"}}";
        when(previewService.preview(body)).thenThrow(new IllegalArgumentException("Invalid bundle: format must be ..."));

        mockMvc.perform(post("/api/v1/projects/import/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid bundle: format must be ..."));
    }

    @Test
    void importAnswers201WithTheResult() throws Exception {
        String body = "{\"bundle\":{}}";
        when(importService.importBundle(body)).thenReturn(new BundleImportResult("p-new",
                new BundleImportResult.Created(8, 0, 2), List.of(new BundleImportResult.Skipped("workflow", "a", "name exists")),
                List.of(), List.of()));

        mockMvc.perform(post("/api/v1/projects/import").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value("p-new"))
                .andExpect(jsonPath("$.created.workflows").value(8))
                .andExpect(jsonPath("$.created.schedules").value(2))
                .andExpect(jsonPath("$.skipped[0].reason").value("name exists"));
    }

    @Test
    void missingPathMappingIs400() throws Exception {
        String body = "{\"bundle\":{}}";
        when(importService.importBundle(body)).thenThrow(new IllegalArgumentException("Missing path mapping for: @@path:1@@ (/tmp/x)"));

        mockMvc.perform(post("/api/v1/projects/import").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
