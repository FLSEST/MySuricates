package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.conversion.DialogWorkflowSuggestion;
import com.automation.service.dialog.DialogConversionService;
import com.automation.service.dialog.DialogSessionScanner;
import com.automation.service.system.ApiKeyService;
import com.automation.service.dialog.DialogSessionScanner.DialogSessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DialogController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DialogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DialogSessionScanner scanner;

    @MockBean
    private DialogConversionService conversionService;

    @MockBean
    private ActivityLogService activityLogService;

    @MockBean
    private ApiKeyService apiKeyService;
    @MockBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void countSessionsPerProject_returnsOk() throws Exception {
        when(scanner.countPerProject()).thenReturn(Map.of("/home/user/project", 5));

        mockMvc.perform(get("/api/v1/dialog/sessions/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$./home/user/project").value(5));
    }

    @Test
    void listSessions_withProjectPath_returnsOk() throws Exception {
        DialogSessionInfo info = new DialogSessionInfo(
                "session-1", "/path/to/file.jsonl", "/home/user/project",
                Instant.now(), Instant.now(), 5, 10,
                List.of("Read", "Edit"), "Hello", true);
        when(scanner.scanSessions(eq("/path"), eq(50))).thenReturn(List.of(info));

        mockMvc.perform(get("/api/v1/dialog/sessions")
                        .param("projectPath", "/path")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("session-1"));
    }

    @Test
    void listSessions_withoutProjectPath_returnsOk() throws Exception {
        when(scanner.scanSessions(any(), eq(50))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dialog/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listSessions_limitCapped_withoutProjectPath() throws Exception {
        when(scanner.scanSessions(any(), eq(500))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dialog/sessions")
                        .param("limit", "9999"))
                .andExpect(status().isOk());

        verify(scanner).scanSessions(null, 500);
    }

    @Test
    void listProjectPaths_returnsOk() throws Exception {
        when(scanner.listProjectPaths()).thenReturn(List.of("/path/a", "/path/b"));

        mockMvc.perform(get("/api/v1/dialog/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("/path/a"))
                .andExpect(jsonPath("$[1]").value("/path/b"));
    }

    @Test
    void analyzeSession_validPath_returnsOk() throws Exception {
        String validPath = System.getProperty("user.home") + "/.claude/projects/my-project/session.jsonl";
        DialogWorkflowSuggestion suggestion = DialogWorkflowSuggestion.notAvailable("test");
        when(conversionService.suggestFromSession(eq(validPath))).thenReturn(suggestion);

        mockMvc.perform(post("/api/v1/dialog/sessions/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filePath\": \"" + validPath + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void analyzeSession_blankFilePath_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/dialog/sessions/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filePath": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeSession_invalidPath_notUnderClaude_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/dialog/sessions/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filePath": "/etc/passwd.jsonl"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeSession_invalidPath_notJsonl_returns400() throws Exception {
        String path = System.getProperty("user.home") + "/.claude/projects/my-project/session.txt";

        mockMvc.perform(post("/api/v1/dialog/sessions/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filePath\": \"" + path + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
