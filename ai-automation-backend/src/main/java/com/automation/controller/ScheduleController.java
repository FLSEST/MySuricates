package com.automation.controller;

import com.automation.dto.request.CreateScheduleRequest;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.CalendarDataResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.mapper.ChainMapper;
import com.automation.mapper.ScheduleMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.scheduler.CatchUpQueue;
import com.automation.service.scheduler.SchedulerMaintenanceMode;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.scheduler.UpcomingRunsCalculator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final SchedulerService schedulerService;
    private final SchedulerMaintenanceMode maintenanceMode;
    private final CatchUpQueue catchUpQueue;
    private final UpcomingRunsCalculator upcomingRunsCalculator;
    private final ScheduleMapper scheduleMapper;
    private final WorkflowMapper workflowMapper;
    private final ChainMapper chainMapper;

    @GetMapping
    public List<ScheduledWorkflowResponse> listSchedules() {
        return scheduleMapper.toResponseList(schedulerService.findAll());
    }

    @GetMapping("/remaining-today")
    public UpcomingScheduleResponse getRemainingToday() {
        return upcomingRunsCalculator.getRemainingToday();
    }

    @GetMapping("/remaining-this-week")
    public UpcomingScheduleResponse getRemainingThisWeek() {
        return upcomingRunsCalculator.getRemainingThisWeek();
    }

    @GetMapping("/{id}")
    public ScheduledWorkflowResponse getSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.findById(id));
    }

    @GetMapping("/{id}/runs")
    @Transactional(readOnly = true)
    public Map<String, Object> getScheduleRuns(@PathVariable String id) {
        List<WorkflowRunResponse> workflowRuns = workflowMapper.toRunResponseListWithOwners(
                schedulerService.findWorkflowRunsByScheduleId(id));
        List<ChainRunResponse> chainRuns = chainMapper.toRunResponseList(
                schedulerService.findChainRunsByScheduleId(id));
        return Map.of("workflowRuns", workflowRuns, "chainRuns", chainRuns);
    }

    @GetMapping("/workflow/{workflowId}")
    public List<ScheduledWorkflowResponse> getByWorkflow(@PathVariable String workflowId) {
        return scheduleMapper.toResponseList(schedulerService.findByWorkflowId(workflowId));
    }

    @GetMapping("/chain/{chainId}")
    public List<ScheduledWorkflowResponse> getByChain(@PathVariable String chainId) {
        return scheduleMapper.toResponseList(schedulerService.findByChainId(chainId));
    }

    @GetMapping("/calendar")
    @Transactional(readOnly = true)
    public CalendarDataResponse getCalendarData(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        var upcoming = scheduleMapper.toResponseList(schedulerService.findSchedulesInRange(from, to));
        var pastRuns = workflowMapper.toRunResponseListWithOwners(schedulerService.findWorkflowRunsBetween(from, to));
        return new CalendarDataResponse(upcoming, pastRuns);
    }

    @PostMapping("/preview")
    public ResponseEntity<?> previewCron(@RequestBody Map<String, String> body) {
        String cronExpression = body.get("cronExpression");
        if (cronExpression == null || cronExpression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cronExpression is required"));
        }
        try {
            List<Instant> runs = upcomingRunsCalculator.getNextRuns(cronExpression, 5);
            return ResponseEntity.ok(Map.of("runs", runs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ScheduledWorkflowResponse> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request) {
        var scheduled = schedulerService.create(
                request.workflowId(), request.chainId(),
                request.targetType(), request.cronExpression(),
                request.description(), request.inputContext(),
                request.scheduleType(), request.scheduledAt(),
                request.delayExpression(), request.endDate(), request.maxRuns(),
                request.conflictBehavior(), request.catchUpOnResume());
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleMapper.toResponse(scheduled));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduledWorkflowResponse> updateSchedule(
            @PathVariable String id,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.update(id, request)));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<ScheduledWorkflowResponse> enableSchedule(@PathVariable String id) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.enable(id)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ScheduledWorkflowResponse> disableSchedule(@PathVariable String id) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.disable(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        schedulerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Sequential catch-up queue: the missed occurrence currently running and the ones waiting. */
    @GetMapping("/catch-up")
    public CatchUpQueue.Snapshot getCatchUpQueue() {
        return catchUpQueue.snapshot();
    }

    @GetMapping("/maintenance")
    public Map<String, Object> getMaintenanceMode() {
        return Map.of("enabled", maintenanceMode.isEnabled());
    }

    @PostMapping("/maintenance")
    public Map<String, Object> setMaintenanceMode(@RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        maintenanceMode.setEnabled(enabled);
        return Map.of("enabled", enabled);
    }
}
