package com.automation.controller;

import com.automation.dto.response.ActivityLogResponse;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.ActivityLogMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.automation.entity.ActivityLog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ActivityLogController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityLogControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ActivityLogMapper activityLogMapper;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void getLogs_returnsPagedResults() throws Exception {
        var response = new ActivityLogResponse("log-1", Instant.now(), "INFO", "SYSTEM",
                "Started", "backend", null, null, Map.of(), Instant.now());
        var page = new PageImpl<ActivityLog>(List.of(), PageRequest.of(0, 50), 1);
        when(activityLogService.findAll(0, 50, null, null, null)).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/activity-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("log-1"))
                .andExpect(jsonPath("$.content[0].level").value("INFO"));
    }

    @Test
    void getLogs_withFilters() throws Exception {
        var page = new PageImpl<ActivityLog>(List.of(), PageRequest.of(0, 20), 0);
        when(activityLogService.findAll(0, 20, LogLevel.ERROR, LogCategory.SECURITY, null)).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-log")
                        .param("page", "0").param("size", "20")
                        .param("level", "ERROR").param("category", "SECURITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void getLogs_withWorkflowIdFilter() throws Exception {
        var page = new PageImpl<ActivityLog>(List.of(), PageRequest.of(0, 50), 0);
        when(activityLogService.findAll(0, 50, null, null, "wf-123")).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-log").param("workflowId", "wf-123"))
                .andExpect(status().isOk());
    }

    @Test
    void getLogs_invalidLevel_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/activity-log").param("level", "NONEXISTENT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getLogs_invalidCategory_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/activity-log").param("category", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getLogs_customPageSize() throws Exception {
        var page = new PageImpl<ActivityLog>(List.of(), PageRequest.of(2, 10), 0);
        when(activityLogService.findAll(2, 10, null, null, null)).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-log").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void getLogs_emptyStringParams_treatedAsNull() throws Exception {
        var page = new PageImpl<ActivityLog>(List.of(), PageRequest.of(0, 50), 0);
        when(activityLogService.findAll(0, 50, null, null, null)).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/activity-log")
                        .param("level", "").param("category", "").param("workflowId", ""))
                .andExpect(status().isOk());
    }

    @Test
    void getLogs_returnsPageMetadata() throws Exception {
        var response = new ActivityLogResponse("log-1", Instant.now(), "INFO", "SYSTEM",
                "Started", "backend", null, null, Map.of(), Instant.now());
        var page = new PageImpl<ActivityLog>(List.of(new ActivityLog()), PageRequest.of(1, 25), 51);
        when(activityLogService.findAll(1, 25, null, null, null)).thenReturn(page);
        when(activityLogMapper.toResponseList(any())).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/activity-log").param("page", "1").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(51))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void createPluginLog_writesPluginCategoryAndReturnsCreated() throws Exception {
        var entry = ActivityLog.builder()
                .id("log-1")
                .timestamp(Instant.now())
                .level(LogLevel.WARN)
                .category(LogCategory.PLUGIN)
                .message("Skill failed")
                .source("mysuricates")
                .referenceId("run-workflow")
                .workflowId("wf-1")
                .metadata(Map.of("durationMs", 1200))
                .build();
        var response = new ActivityLogResponse("log-1", entry.getTimestamp(), "WARN", "PLUGIN",
                "Skill failed", "mysuricates", "run-workflow", "wf-1", entry.getMetadata(), Instant.now());

        when(activityLogService.log(eq(LogLevel.WARN), eq(LogCategory.PLUGIN), eq("Skill failed"),
                eq("mysuricates"), eq("run-workflow"), eq(Map.of("durationMs", 1200)), eq("wf-1")))
                .thenReturn(entry);
        when(activityLogMapper.toResponse(entry)).thenReturn(response);

        mockMvc.perform(post("/api/v1/activity-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "level": "WARN",
                                  "message": "Skill failed",
                                  "source": "mysuricates",
                                  "referenceId": "run-workflow",
                                  "workflowId": "wf-1",
                                  "metadata": { "durationMs": 1200 }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("log-1"))
                .andExpect(jsonPath("$.category").value("PLUGIN"));

        verify(activityLogService).log(LogLevel.WARN, LogCategory.PLUGIN, "Skill failed",
                "mysuricates", "run-workflow", Map.of("durationMs", 1200), "wf-1");
    }

    @Test
    void createPluginLog_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/activity-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
