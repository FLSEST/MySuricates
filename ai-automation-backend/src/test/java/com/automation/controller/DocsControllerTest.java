package com.automation.controller;

import com.automation.config.DocsConfig;
import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocsController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DocsControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private DocsConfig docsConfig;
    @MockBean private ApiKeyService apiKeyService;
    @MockBean private ActivityLogService activityLogService;
    @MockBean private RateLimitConfig rateLimitConfig;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        when(docsConfig.getDirectory()).thenReturn(tempDir.toString());

        // Create sample docs
        Files.writeString(tempDir.resolve("WORKFLOW-GUIDE.md"), "# Workflow Guide\nSome content");
        Files.writeString(tempDir.resolve("architektur.md"), "# Architecture\nDetails");

        // Create en subdirectory
        Path enDir = tempDir.resolve("en");
        Files.createDirectories(enDir);
        Files.writeString(enDir.resolve("deployment.md"), "# Deployment\nEnglish content");
    }

    @Test
    void listDocs_returnsAllDocsWithLabels() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                // German docs (sorted alphabetically)
                .andExpect(jsonPath("$[0].name").value("WORKFLOW-GUIDE.md"))
                .andExpect(jsonPath("$[0].label").value("Workflow Guide"))
                .andExpect(jsonPath("$[0].lang").value("de"))
                .andExpect(jsonPath("$[1].name").value("architektur.md"))
                .andExpect(jsonPath("$[1].label").value("Architektur"))
                .andExpect(jsonPath("$[1].lang").value("de"))
                // English docs
                .andExpect(jsonPath("$[2].name").value("deployment.md"))
                .andExpect(jsonPath("$[2].label").value("Deployment"))
                .andExpect(jsonPath("$[2].lang").value("en"));
    }

    @Test
    void listDocs_emptyDirectory_returnsEmptyList() throws Exception {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        when(docsConfig.getDirectory()).thenReturn(emptyDir.toString());

        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getDoc_existingGerman_returnsContent() throws Exception {
        mockMvc.perform(get("/api/v1/docs/WORKFLOW-GUIDE.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("# Workflow Guide\nSome content"));
    }

    @Test
    void getDoc_existingEnglish_returnsContent() throws Exception {
        mockMvc.perform(get("/api/v1/docs/deployment.md")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("# Deployment\nEnglish content"));
    }

    @Test
    void getDoc_notFound_returnsError() throws Exception {
        // ResponseStatusException is caught by GlobalExceptionHandler's catch-all
        mockMvc.perform(get("/api/v1/docs/nonexistent.md"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void getDoc_pathTraversal_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/docs/..%2Fsecret.md"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void getDoc_invalidLang_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/docs/WORKFLOW-GUIDE.md")
                        .param("lang", "fr"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void getDoc_nonMdFile_returnsError() throws Exception {
        Files.writeString(tempDir.resolve("secret.txt"), "secret");

        mockMvc.perform(get("/api/v1/docs/secret.txt"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
