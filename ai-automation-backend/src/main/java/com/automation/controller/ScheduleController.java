package com.automation.controller;

import com.automation.dto.request.CreateScheduleRequest;
import com.automation.dto.response.CalendarDataResponse;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.mapper.ScheduleMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.scheduler.SchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final SchedulerService schedulerService;
    private final ScheduleMapper scheduleMapper;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowMapper workflowMapper;

    @GetMapping
    public List<ScheduledWorkflowResponse> listSchedules() {
        return scheduleMapper.toResponseList(schedulerService.findAll());
    }

    @GetMapping("/remaining-today")
    public UpcomingScheduleResponse getRemainingToday() {
        return schedulerService.getRemainingToday();
    }

    @GetMapping("/remaining-this-week")
    public UpcomingScheduleResponse getRemainingThisWeek() {
        return schedulerService.getRemainingThisWeek();
    }

    @GetMapping("/{id}")
    public ScheduledWorkflowResponse getSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.findById(id));
    }

    @GetMapping("/workflow/{workflowId}")
    public List<ScheduledWorkflowResponse> getByWorkflow(@PathVariable String workflowId) {
        return scheduleMapper.toResponseList(schedulerService.findByWorkflowId(workflowId));
    }

    @GetMapping("/calendar")
    public CalendarDataResponse getCalendarData(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        var upcoming = scheduleMapper.toResponseList(schedulerService.findSchedulesInRange(from, to));
        var pastRuns = workflowMapper.toRunResponseList(workflowRunRepository.findByStartedAtBetween(from, to));
        return new CalendarDataResponse(upcoming, pastRuns);
    }

    @PostMapping
    public ResponseEntity<ScheduledWorkflowResponse> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request) {
        var scheduled = schedulerService.create(
                request.workflowId(), request.cronExpression(),
                request.description(), request.inputContext(),
                request.scheduleType(), request.scheduledAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleMapper.toResponse(scheduled));
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
}
