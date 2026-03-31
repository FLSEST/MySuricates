package com.automation.controller;

import com.automation.dto.response.ActivityLogResponse;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.ActivityLogMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import com.automation.entity.ActivityLog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ActivityLogController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityLogControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ActivityLogService activityLogService;
    @MockBean private ActivityLogMapper activityLogMapper;
    @MockBean private ApiKeyService apiKeyService;

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
}
