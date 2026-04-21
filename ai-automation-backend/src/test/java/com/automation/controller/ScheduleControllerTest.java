package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.ChainMapper;
import com.automation.mapper.ScheduleMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.scheduler.SchedulerService;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(ScheduleController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SchedulerService schedulerService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private ScheduleMapper scheduleMapper;

    @MockitoBean
    private WorkflowMapper workflowMapper;

    @MockitoBean
    private ChainMapper chainMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private Workflow testWorkflow;
    private ScheduledWorkflow testScheduled;
    private ScheduledWorkflowResponse testResponse;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .build();

        testScheduled = ScheduledWorkflow.builder()
                .id("sched-1")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .description("Hourly run")
                .inputContext(Map.of())
                .nextRunAt(Instant.parse("2026-02-16T00:00:00Z"))
                .createdAt(Instant.now())
                .build();

        testResponse = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "Test Workflow", null,
                null, null, "WORKFLOW",
                "0 0 * * * *", "CRON", null, null,
                true, "Hourly run", Map.of(), null,
                Instant.parse("2026-02-16T00:00:00Z"), testScheduled.getCreatedAt(),
                null, null, 0, 0, "SKIP"
        );
    }

    @Test
    void listSchedules_returnsOk() throws Exception {
        when(schedulerService.findAll()).thenReturn(List.of(testScheduled));
        when(scheduleMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sched-1"))
                .andExpect(jsonPath("$[0].cronExpression").value("0 0 * * * *"));
    }

    @Test
    void getSchedule_existingId_returnsOk() throws Exception {
        when(schedulerService.findById("sched-1")).thenReturn(testScheduled);
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(testResponse);

        mockMvc.perform(get("/api/v1/schedules/sched-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sched-1"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"));
    }

    @Test
    void getSchedule_nonExistent_returns404() throws Exception {
        when(schedulerService.findById("unknown"))
                .thenThrow(new ResourceNotFoundException("ScheduledWorkflow", "unknown"));

        mockMvc.perform(get("/api/v1/schedules/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByWorkflow_returnsOk() throws Exception {
        when(schedulerService.findByWorkflowId("wf-1")).thenReturn(List.of(testScheduled));
        when(scheduleMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/schedules/workflow/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId").value("wf-1"));
    }

    @Test
    void createSchedule_validCronRequest_returns201() throws Exception {
        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(testScheduled);
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(testResponse);

        String body = """
                {
                    "workflowId": "wf-1",
                    "cronExpression": "0 0 * * * *",
                    "description": "Hourly run"
                }
                """;

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sched-1"));
    }

    @Test
    void createSchedule_missingWorkflowId_returns400() throws Exception {
        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("workflowId is required for WORKFLOW schedules"));

        String body = """
                {
                    "cronExpression": "0 0 * * * *"
                }
                """;

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSchedule_onceSchedule_returns201() throws Exception {
        ScheduledWorkflow onceScheduled = ScheduledWorkflow.builder()
                .id("sched-once")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(Instant.parse("2026-03-01T10:00:00Z"))
                .enabled(true)
                .build();
        ScheduledWorkflowResponse onceResponse = new ScheduledWorkflowResponse(
                "sched-once", "wf-1", "Test Workflow", null,
                null, null, "WORKFLOW",
                "", "ONCE",
                Instant.parse("2026-03-01T10:00:00Z"), null,
                true, null, Map.of(), null,
                Instant.parse("2026-03-01T10:00:00Z"), null,
                null, null, 0, 0, "SKIP"
        );

        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(onceScheduled);
        when(scheduleMapper.toResponse(onceScheduled)).thenReturn(onceResponse);

        String body = """
                {
                    "workflowId": "wf-1",
                    "scheduleType": "ONCE",
                    "scheduledAt": "2026-03-01T10:00:00Z",
                    "description": "One-time run"
                }
                """;

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleType").value("ONCE"));
    }

    @Test
    void enableSchedule_returnsOk() throws Exception {
        testScheduled.setEnabled(true);
        when(schedulerService.enable("sched-1")).thenReturn(testScheduled);
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/schedules/sched-1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void disableSchedule_returnsOk() throws Exception {
        testScheduled.setEnabled(false);
        when(schedulerService.disable("sched-1")).thenReturn(testScheduled);
        ScheduledWorkflowResponse disabledResponse = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "Test Workflow", null,
                null, null, "WORKFLOW",
                "0 0 * * * *", "CRON", null, null,
                false, "Hourly run", Map.of(), null, null, testScheduled.getCreatedAt(),
                null, null, 0, 0, "SKIP"
        );
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(disabledResponse);

        mockMvc.perform(post("/api/v1/schedules/sched-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deleteSchedule_returns204() throws Exception {
        doNothing().when(schedulerService).delete("sched-1");

        mockMvc.perform(delete("/api/v1/schedules/sched-1"))
                .andExpect(status().isNoContent());

        verify(schedulerService).delete("sched-1");
    }
}
