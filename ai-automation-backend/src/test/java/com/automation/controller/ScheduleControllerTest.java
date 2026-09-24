package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.StepStatus;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.ChainMapper;
import com.automation.mapper.ScheduleMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.CatchUpQueue;
import com.automation.service.scheduler.SchedulerMaintenanceMode;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
    private SchedulerMaintenanceMode maintenanceMode;
    @MockitoBean
    private CatchUpQueue catchUpQueue;
    @MockitoBean
    private com.automation.service.scheduler.UpcomingRunsCalculator upcomingRunsCalculator;
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
        , false, null, false);
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
        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(testScheduled);
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
        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
        , false, null, false);

        when(schedulerService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(onceScheduled);
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
        , false, null, false);
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

    @Test
    void getRemainingToday_returnsCountAndSchedules() throws Exception {
        var run = new UpcomingScheduleResponse.UpcomingRun(
                "sched-1", "Test Workflow", null, null, null, "WORKFLOW",
                Instant.parse("2026-02-16T10:00:00Z"));
        when(upcomingRunsCalculator.getRemainingToday()).thenReturn(new UpcomingScheduleResponse(1, List.of(run), null, false));

        mockMvc.perform(get("/api/v1/schedules/remaining-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.schedules[0].scheduleId").value("sched-1"))
                .andExpect(jsonPath("$.schedules[0].workflowName").value("Test Workflow"));
    }

    @Test
    void getRemainingThisWeek_returnsCountAndSchedules() throws Exception {
        when(upcomingRunsCalculator.getRemainingThisWeek()).thenReturn(new UpcomingScheduleResponse(0, List.of(), null, false));

        mockMvc.perform(get("/api/v1/schedules/remaining-this-week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.schedules").isArray());
    }

    /** Listenzeile eines Runs, wie {@code WorkflowMapper.toRunListItemsWithOwners} sie liefert. */
    private static WorkflowRunListItemResponse listItem(String id) {
        return new WorkflowRunListItemResponse(
                id, "wf-1", "Nightly", "Project", "/tmp/project", RunStatus.COMPLETED,
                Instant.parse("2026-09-01T10:00:00Z"), null, 5_000L, null, null, Map.of(), Map.of(),
                "sched-1", null, null, null, 3, StepStatus.COMPLETED, "last output");
    }

    /**
     * Workflow-Runs eines Zeitplans kommen als Listenzeilen: Anzahl und Vorschau des letzten
     * Schritts, keine Schritte (TODO 168e3d7f).
     */
    @Test
    void getScheduleRuns_returnsBothWorkflowAndChainRuns() throws Exception {
        when(schedulerService.findWorkflowRunsByScheduleId("sched-1")).thenReturn(List.of());
        when(schedulerService.findChainRunsByScheduleId("sched-1")).thenReturn(List.of());
        when(workflowMapper.toRunListItemsWithOwners(any())).thenReturn(List.of(listItem("run-1")));
        when(chainMapper.toRunResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/schedules/sched-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRuns[0].id").value("run-1"))
                .andExpect(jsonPath("$.workflowRuns[0].stepCount").value(3))
                .andExpect(jsonPath("$.workflowRuns[0].lastStepPreview").value("last output"))
                .andExpect(jsonPath("$.workflowRuns[0].steps").doesNotExist())
                .andExpect(jsonPath("$.chainRuns").isArray());
    }

    @Test
    void getByChain_returnsOk() throws Exception {
        when(schedulerService.findByChainId("chain-1")).thenReturn(List.of(testScheduled));
        when(scheduleMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/schedules/chain/chain-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sched-1"));
    }

    @Test
    void previewCron_validExpression_returnsNextRuns() throws Exception {
        Instant next = Instant.parse("2026-02-16T01:00:00Z");
        when(upcomingRunsCalculator.getNextRuns("0 0 * * * *", 5)).thenReturn(List.of(next));

        mockMvc.perform(post("/api/v1/schedules/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 * * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isArray())
                .andExpect(jsonPath("$.runs[0]").exists());
    }

    @Test
    void previewCron_invalidExpression_returns400WithError() throws Exception {
        when(upcomingRunsCalculator.getNextRuns("not-valid", 5))
                .thenThrow(new IllegalArgumentException("Invalid cron expression: not-valid"));

        mockMvc.perform(post("/api/v1/schedules/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"not-valid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void previewCron_missingExpression_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("cronExpression is required"));
    }

    @Test
    void updateSchedule_returnsOk() throws Exception {
        when(schedulerService.update(any(), any(UpdateScheduleRequest.class))).thenReturn(testScheduled);
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(testResponse);

        mockMvc.perform(put("/api/v1/schedules/sched-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 12 * * *\", \"description\": \"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sched-1"));
    }

    @Test
    void updateSchedule_passesConflictBehaviorToTheService() throws Exception {
        when(schedulerService.update(any(), any(UpdateScheduleRequest.class))).thenReturn(testScheduled);
        when(scheduleMapper.toResponse(testScheduled)).thenReturn(testResponse);

        mockMvc.perform(put("/api/v1/schedules/sched-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictBehavior\": \"OVERLAP\"}"))
                .andExpect(status().isOk());

        verify(schedulerService).update(eq("sched-1"), argThat(r -> "OVERLAP".equals(r.conflictBehavior())));
    }

    @Test
    void updateSchedule_nonExistent_returns404() throws Exception {
        when(schedulerService.update(any(), any(UpdateScheduleRequest.class)))
                .thenThrow(new ResourceNotFoundException("ScheduledWorkflow", "unknown"));

        mockMvc.perform(put("/api/v1/schedules/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 12 * * *\"}"))
                .andExpect(status().isNotFound());
    }

    /** Die vergangenen Runs des Kalenders sind Listenzeilen ohne Schritte (TODO 168e3d7f). */
    @Test
    void getCalendarData_returnsUpcomingAndPastRuns() throws Exception {
        when(upcomingRunsCalculator.findWithNextRunBetween(any(), any())).thenReturn(List.of(testScheduled));
        when(schedulerService.findWorkflowRunsBetween(any(), any())).thenReturn(List.of());
        when(scheduleMapper.toResponseList(any())).thenReturn(List.of(testResponse));
        when(workflowMapper.toRunListItemsWithOwners(any())).thenReturn(List.of(listItem("run-1")));

        mockMvc.perform(get("/api/v1/schedules/calendar")
                        .param("from", "2026-02-16T00:00:00Z")
                        .param("to", "2026-02-23T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming").isArray())
                .andExpect(jsonPath("$.pastRuns[0].id").value("run-1"))
                .andExpect(jsonPath("$.pastRuns[0].stepCount").value(3))
                .andExpect(jsonPath("$.pastRuns[0].steps").doesNotExist());
    }

    @Test
    void getMaintenanceMode_returnsEnabledFalse() throws Exception {
        when(maintenanceMode.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/schedules/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void setMaintenanceMode_enablesAndReturnsEnabled() throws Exception {
        doNothing().when(maintenanceMode).setEnabled(true);

        mockMvc.perform(post("/api/v1/schedules/maintenance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(maintenanceMode).setEnabled(true);
    }

    // --- skip-until pause visible to the views (TODO a776fd2a) ---

    @Test
    void getMaintenanceMode_withoutSkipUntilPause_pausedUntilIsNull() throws Exception {
        when(maintenanceMode.isEnabled()).thenReturn(false);
        when(upcomingRunsCalculator.pausedUntil()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/schedules/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.pausedUntil").isEmpty());
    }

    @Test
    void getMaintenanceMode_reportsAnActiveSkipUntilPause() throws Exception {
        when(maintenanceMode.isEnabled()).thenReturn(false);
        when(upcomingRunsCalculator.pausedUntil())
                .thenReturn(java.util.Optional.of(Instant.parse("2026-10-01T22:00:00Z")));

        mockMvc.perform(get("/api/v1/schedules/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.pausedUntil").value("2026-10-01T22:00:00Z"));
    }

    @Test
    void previewCron_duringSkipUntilPause_saysUntilWhen() throws Exception {
        when(upcomingRunsCalculator.getNextRuns("0 0 * * * *", 5))
                .thenReturn(List.of(Instant.parse("2026-10-01T22:00:00Z")));
        when(upcomingRunsCalculator.pausedUntil())
                .thenReturn(java.util.Optional.of(Instant.parse("2026-10-01T22:00:00Z")));

        mockMvc.perform(post("/api/v1/schedules/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 * * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0]").value("2026-10-01T22:00:00Z"))
                .andExpect(jsonPath("$.pausedUntil").value("2026-10-01T22:00:00Z"));
    }

    @Test
    void getRemainingToday_carriesPausedUntil() throws Exception {
        when(upcomingRunsCalculator.getRemainingToday()).thenReturn(
                new UpcomingScheduleResponse(0, List.of(), Instant.parse("2026-10-01T22:00:00Z"), false));

        mockMvc.perform(get("/api/v1/schedules/remaining-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.pausedUntil").value("2026-10-01T22:00:00Z"));
    }

    @Test
    void getCatchUpQueue_returnsActiveAndPending() throws Exception {
        var active = new CatchUpQueue.Active("s-1", "Nightly", "run-1", java.time.Instant.parse("2026-09-14T08:00:00Z"));
        var pending = new CatchUpQueue.Pending("s-2", "Radar", java.time.Instant.parse("2026-09-14T04:00:00Z"));
        when(catchUpQueue.snapshot()).thenReturn(new CatchUpQueue.Snapshot(active, java.util.List.of(pending)));

        mockMvc.perform(get("/api/v1/schedules/catch-up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.scheduleId").value("s-1"))
                .andExpect(jsonPath("$.active.runId").value("run-1"))
                .andExpect(jsonPath("$.pending[0].scheduleId").value("s-2"))
                .andExpect(jsonPath("$.pending[0].targetName").value("Radar"));
    }

    @Test
    void getCatchUpQueue_empty() throws Exception {
        when(catchUpQueue.snapshot()).thenReturn(new CatchUpQueue.Snapshot(null, java.util.List.of()));

        mockMvc.perform(get("/api/v1/schedules/catch-up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").doesNotExist())
                .andExpect(jsonPath("$.pending").isEmpty());
    }
}
