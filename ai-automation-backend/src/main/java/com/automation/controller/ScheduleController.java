package com.automation.controller;

import com.automation.dto.request.CreateScheduleRequest;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.CalendarDataResponse;
import com.automation.dto.response.ScheduleRunsResponse;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.dto.response.SchedulerPauseResponse;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorMessage;
import com.automation.exception.ErrorResponse;
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

/** Time-based starts of workflows and chains: cron, one-off and delayed schedules, calendar and the scheduler pauses. */
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

    /**
     * Lists all schedules.
     * {@code nextRunAt} is the next run that will actually start; during a skip-until pause {@code pausedUntil} names
     * its end, and while the maintenance switch is on {@code nextRunAt} is null and {@code maintenance} true.
     */
    @GetMapping
    public List<ScheduledWorkflowResponse> listSchedules() {
        return scheduleMapper.toResponseList(schedulerService.findAll());
    }

    /**
     * Lists the runs still due today.
     * {@code projectId} (optional): only the schedules of that project's workflows — the dashboard filter.
     */
    @GetMapping("/remaining-today")
    public UpcomingScheduleResponse getRemainingToday(@RequestParam(required = false) String projectId) {
        return upcomingRunsCalculator.getRemainingToday(projectId);
    }

    /** Lists the runs still due this week; {@code projectId} (optional) filters like {@code remaining-today}. */
    @GetMapping("/remaining-this-week")
    public UpcomingScheduleResponse getRemainingThisWeek(@RequestParam(required = false) String projectId) {
        return upcomingRunsCalculator.getRemainingThisWeek(projectId);
    }

    /** Returns one schedule; unknown id is 404. */
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

    /** Lists the schedules of one workflow. */
    @GetMapping("/workflow/{workflowId}")
    public List<ScheduledWorkflowResponse> getByWorkflow(@PathVariable String workflowId) {
        return scheduleMapper.toResponseList(schedulerService.findByWorkflowId(workflowId));
    }

    /** Lists the schedules of one chain. */
    @GetMapping("/chain/{chainId}")
    public List<ScheduledWorkflowResponse> getByChain(@PathVariable String chainId) {
        return scheduleMapper.toResponseList(schedulerService.findByChainId(chainId));
    }

    /**
     * Returns the calendar data between {@code from} and {@code to}: upcoming schedules and past runs.
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

    /**
     * Previews the next run times of the cron expression in {@code cronExpression}.
     * A missing or invalid expression is 400; during a skip-until pause the times start after it and {@code pausedUntil} is set.
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewCron(@RequestBody Map<String, String> body) {
        String cronExpression = body.get("cronExpression");
        if (cronExpression == null || cronExpression.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorMessage.CRON_REQUIRED, Map.of()));
        }
        try {
            List<Instant> runs = upcomingRunsCalculator.getNextRuns(cronExpression, 5);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("runs", runs);
            // The runs start after an active skip-until pause; say so, or the list looks wrong.
            upcomingRunsCalculator.pausedUntil().ifPresent(until -> preview.put("pausedUntil", until));
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        }
    }

    /**
     * Creates a schedule for a workflow ({@code workflowId}) or a chain ({@code targetType: CHAIN}, {@code chainId}) and answers 201.
     * Unknown enum values, an invalid cron expression or a schedule without any run left are 400.
     */
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

    /**
     * Updates a schedule; only the fields sent are changed.
     * If an enabled cron schedule is then left without a run before {@code endDate} or {@code maxRuns}, the answer is 400.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ScheduledWorkflowResponse> updateSchedule(
            @PathVariable String id,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.update(id, request)));
    }

    /**
     * Enables a schedule and computes its next run.
     * An unparsable cron expression, a passed {@code endDate} or used-up {@code maxRuns} is 400.
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ScheduledWorkflowResponse> enableSchedule(@PathVariable String id) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.enable(id)));
    }

    /** Disables a schedule and clears its next run. */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ScheduledWorkflowResponse> disableSchedule(@PathVariable String id) {
        return ResponseEntity.ok(scheduleMapper.toResponse(schedulerService.disable(id)));
    }

    /** Deletes a schedule (204). */
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

    /**
     * Switches the scheduler maintenance mode on or off with {@code enabled}; while it is on, no schedule starts.
     * The switch is stored and survives a restart.
     */
    @PostMapping("/maintenance")
    public SchedulerPauseResponse setMaintenanceMode(@RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        maintenanceMode.setEnabled(enabled);
        return new SchedulerPauseResponse(enabled, upcomingRunsCalculator.pausedUntil().orElse(null));
    }
}
