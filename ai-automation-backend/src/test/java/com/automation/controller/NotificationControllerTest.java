package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.NotificationConfigResponse;
import com.automation.entity.NotificationConfig;
import com.automation.entity.NotificationConfig.ChannelType;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.NotificationConfigMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.notification.NotificationService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService service;

    @MockBean
    private NotificationConfigMapper mapper;

    @MockBean
    private ActivityLogService activityLogService;

    @MockBean
    private ApiKeyService apiKeyService;
    @MockBean
    private RateLimitConfig rateLimitConfig;

    private NotificationConfig testConfig;
    private NotificationConfigResponse testResponse;

    @BeforeEach
    void setUp() {
        testConfig = NotificationConfig.builder()
                .id("nc-1")
                .name("Slack Alert")
                .enabled(true)
                .channelType(ChannelType.WEBHOOK)
                .webhookUrl("https://hooks.slack.com/test")
                .triggerOnSuccess(true)
                .triggerOnFailure(true)
                .build();

        testResponse = new NotificationConfigResponse(
                "nc-1", "Slack Alert", true, ChannelType.WEBHOOK,
                "https://hooks.slack.com/test", null, true, true, false, null, Instant.now());
    }

    @Test
    void listAll_returnsOk() throws Exception {
        when(service.findAll()).thenReturn(List.of(testConfig));
        when(mapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("nc-1"))
                .andExpect(jsonPath("$[0].name").value("Slack Alert"));
    }

    @Test
    void getById_existingId_returnsOk() throws Exception {
        when(service.findById("nc-1")).thenReturn(testConfig);
        when(mapper.toResponse(any())).thenReturn(testResponse);

        mockMvc.perform(get("/api/v1/notifications/nc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("nc-1"));
    }

    @Test
    void getById_nonExistent_returns404() throws Exception {
        when(service.findById("unknown")).thenThrow(new ResourceNotFoundException("NotificationConfig", "unknown"));

        mockMvc.perform(get("/api/v1/notifications/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(service.create(any())).thenReturn(testConfig);
        when(mapper.toResponse(any())).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Slack Alert",
                                    "channelType": "WEBHOOK",
                                    "webhookUrl": "https://hooks.slack.com/test",
                                    "triggerOnSuccess": true,
                                    "triggerOnFailure": true,
                                    "triggerAlways": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("nc-1"));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelType": "WEBHOOK"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enable_existingId_returnsOk() throws Exception {
        when(service.enable("nc-1")).thenReturn(testConfig);
        when(mapper.toResponse(any())).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/notifications/nc-1/enable"))
                .andExpect(status().isOk());
    }

    @Test
    void disable_existingId_returnsOk() throws Exception {
        when(service.disable("nc-1")).thenReturn(testConfig);
        when(mapper.toResponse(any())).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/notifications/nc-1/disable"))
                .andExpect(status().isOk());
    }

    @Test
    void delete_existingId_returns204() throws Exception {
        doNothing().when(service).delete("nc-1");

        mockMvc.perform(delete("/api/v1/notifications/nc-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_nonExistent_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("NotificationConfig", "unknown"))
                .when(service).delete("unknown");

        mockMvc.perform(delete("/api/v1/notifications/unknown"))
                .andExpect(status().isNotFound());
    }
}
