package com.automation.controller;

import com.automation.config.ReportsConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportsController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportsControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ReportsConfig reportsConfig;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(reportsConfig.getDirectory()).thenReturn(tempDir.toString());
    }

    // --- GET /api/v1/reports ---

    @Test
    void listReports_emptyDir_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listReports_returnsOnlyMdFiles() throws Exception {
        Files.writeString(tempDir.resolve("report.md"), "# Report");
        Files.writeString(tempDir.resolve("notes.txt"), "notes");
        Files.writeString(tempDir.resolve("data.json"), "{}");

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("report.md"));
    }

    @Test
    void listReports_sortedByDateDescending() throws Exception {
        Files.writeString(tempDir.resolve("report-2025-01.md"), "old");
        Files.writeString(tempDir.resolve("report-2025-03.md"), "new");
        Files.writeString(tempDir.resolve("report-2025-02.md"), "mid");

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("report-2025-03.md"))
                .andExpect(jsonPath("$[1].name").value("report-2025-02.md"))
                .andExpect(jsonPath("$[2].name").value("report-2025-01.md"));
    }

    @Test
    void listReports_formatsLabel() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report-2025-03.md"), "content");

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Weekly Report 2025 03"));
    }

    @Test
    void listReports_invalidDir_returnsEmptyList() throws Exception {
        when(reportsConfig.getDirectory()).thenReturn(tempDir.resolve("nonexistent").toString());

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- GET /api/v1/reports/{name} ---

    @Test
    void getReport_existingFile_returnsContent() throws Exception {
        Files.writeString(tempDir.resolve("test-report.md"), "# Test\nSome content");

        mockMvc.perform(get("/api/v1/reports/test-report.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("# Test\nSome content"));
    }

    @Test
    void getReport_notFound_returnsError() throws Exception {
        // The status the controller gives, not the catch-all's 500 (TODO 1bad5275)
        mockMvc.perform(get("/api/v1/reports/missing.md"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Report not found"));
    }

    @Test
    void getReport_pathTraversal_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/reports/..%2Fsecret.md"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void getReport_nonMdFile_returnsError() throws Exception {
        Files.writeString(tempDir.resolve("data.txt"), "secret");

        mockMvc.perform(get("/api/v1/reports/data.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void getReport_backslashTraversal_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/reports/..\\secret.md"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void listReports_dateInFilenameWithDay_sortedCorrectly() throws Exception {
        Files.writeString(tempDir.resolve("audit-2025-01-15.md"), "a");
        Files.writeString(tempDir.resolve("audit-2025-03-01.md"), "b");

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("audit-2025-03-01.md"))
                .andExpect(jsonPath("$[1].name").value("audit-2025-01-15.md"));
    }

    @Test
    void listReports_noDateInFilename_sortedByName() throws Exception {
        Files.writeString(tempDir.resolve("beta-report.md"), "b");
        Files.writeString(tempDir.resolve("alpha-report.md"), "a");

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("alpha-report.md"))
                .andExpect(jsonPath("$[1].name").value("beta-report.md"));
    }

    @Test
    void getReport_emptyFile_returnsEmptyContent() throws Exception {
        Files.writeString(tempDir.resolve("empty.md"), "");

        mockMvc.perform(get("/api/v1/reports/empty.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""));
    }

    @Test
    void listReports_manyFiles_allReturned() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(tempDir.resolve(String.format("report-%02d.md", i)), "content " + i);
        }

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(20)));
    }

    @Test
    void getReport_slashInName_returnsError() throws Exception {
        // "sub/file.md" doesn't match the single-segment {name} path variable, so this falls
        // into Spring's NoResourceFoundException fallback. TODO c8f122ac fixed that path to
        // answer 404 (routine "no such endpoint") instead of the previous 500 INTERNAL_ERROR.
        mockMvc.perform(get("/api/v1/reports/sub/file.md"))
                .andExpect(status().isNotFound());
    }
}
