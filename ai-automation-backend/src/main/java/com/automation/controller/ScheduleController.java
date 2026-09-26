package com.automation.controller;

import com.automation.dto.request.CreateScheduleRequest;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.CalendarDataResponse;
import com.automation.dto.response.ScheduleRunsResponse;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.dto.response.SchedulerPauseResponse;
import com.automation.dto.response.UpcomingScheduleResponse;
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
import java.util.LinkedHashMap;
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

    /** {@code projectId} (optional): only the schedules of that project's workflows — the dashboard filter. */
    @GetMapping("/remaining-today")
    public UpcomingScheduleResponse getRemainingToday(@RequestParam(required = false) String projectId) {
        return upcomingRunsCalculator.getRemainingToday(projectId);
    }

    @GetMapping("/remaining-this-week")
    public UpcomingScheduleResponse getRemainingThisWeek(@RequestParam(required = false) String projectId) {
        return upcomingRunsCalculator.getRemainingThisWeek(projectId);
    }

    @GetMapping("/{id}")
    public ScheduledWorkflowResponse getSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.findById(id));
    }

    /** Default and ceiling of {@code limit} on {@code /{id}/runs} (TODO 86777fb4). */
    static final int SCHEDULE_RUNS_LIMIT = 50;
    static final int SCHEDULE_RUNS_MAX_LIMIT = 500;
    /** Default and ceiling of {@code limit} on {@code /calendar}: a month of an hourly schedule fits. */
    static final int CALENDAR_RUNS_LIMIT = 1000;
    static final int CALENDAR_RUNS_MAX_LIMIT = 5000;

    /**
     * Die neuesten Runs eines Zeitplans als Listenzeilen: Anzahl und Vorschau des letzten Schritts
     * statt aller Schritte (TODO 168e3d7f) — die Detail-Route eines Runs liefert den Volltext. Je Art
     * hoechstens {@code limit}, dazu die Gesamtzahl (TODO 86777fb4).
     */
    @GetMapping("/{id}/runs")
    @Transactional(readOnly = true)
    public ScheduleRunsResponse getScheduleRuns(@PathVariable String id,
                                                @RequestParam(defaultValue = "" + SCHEDULE_RUNS_LIMIT) int limit) {
        int capped = Math.clamp(limit, 1, SCHEDULE_RUNS_MAX_LIMIT);
        var workflowRuns = schedulerService.findWorkflowRunsByScheduleId(id, capped);
        var chainRuns = schedulerService.findChainRunsByScheduleId(id, capped);
        return new ScheduleRunsResponse(workflowMapper.toRunListItemsWithOwners(workflowRuns.runs()),
                chainMapper.toRunResponseList(chainRuns.runs()), workflowRuns.total(), chainRuns.total());
    }

    @GetMapping("/workflow/{workflowId}")
    public List<ScheduledWorkflowResponse> getByWorkflow(@PathVariable String workflowId) {
        return scheduleMapper.toResponseList(schedulerService.findByWorkflowId(workflowId));
    }

    @GetMapping("/chain/{chainId}")
    public List<ScheduledWorkflowResponse> getByChain(@PathVariable String chainId) {
        return scheduleMapper.toResponseList(schedulerService.findByChainId(chainId));
    }

    /**
     * Upcoming = schedules whose next real run lies in the period (behind a skip-until pause). Past
     * runs: the newest {@code limit} of the period plus their total (TODO 86777fb4).
     */
    @GetMapping("/calendar")
    @Transactional(readOnly = true)
    public CalendarDataResponse getCalendarData(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "" + CALENDAR_RUNS_LIMIT) int limit) {
        var upcoming = scheduleMapper.toResponseList(upcomingRunsCalculator.findWithNextRunBetween(from, to));
        var pastRuns = schedulerService.findWorkflowRunsBetween(from, to, Math.clamp(limit, 1, CALENDAR_RUNS_MAX_LIMIT));
        return new CalendarDataResponse(upcoming, workflowMapper.toRunListItemsWithOwners(pastRuns.runs()), pastRuns.total());
    }

    @PostMapping("/preview")
    public ResponseEntity<?> previewCron(@RequestBody Map<String, String> body) {
        String cronExpression = body.get("cronExpression");
        if (cronExpression == null || cronExpression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cronExpression is required"));
        }
        try {
            List<Instant> runs = upcomingRunsCalculator.getNextRuns(cronExpression, 5);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("runs", runs);
            // The runs start after an active skip-until pause; say so, or the list looks wrong.
            upcomingRunsCalculator.pausedUntil().ifPresent(until -> preview.put("pausedUntil", until));
            return ResponseEntity.ok(preview);
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

    /** Both pauses: the maintenance switch and an active skip-until window from the configuration. */
    @GetMapping("/maintenance")
    public SchedulerPauseResponse getMaintenanceMode() {
        return new SchedulerPauseResponse(maintenanceMode.isEnabled(),
                upcomingRunsCalculator.pausedUntil().orElse(null));
    }

    @PostMapping("/maintenance")
    public SchedulerPauseResponse setMaintenanceMode(@RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        maintenanceMode.setEnabled(enabled);
        return new SchedulerPauseResponse(enabled, upcomingRunsCalculator.pausedUntil().orElse(null));
    }
}
