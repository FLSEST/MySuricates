package com.automation.controller;

import com.automation.dto.request.CreateScheduleRequest;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.mapper.ScheduleMapper;
import com.automation.service.scheduler.SchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final SchedulerService schedulerService;
    private final ScheduleMapper scheduleMapper;

    @GetMapping
    public List<ScheduledWorkflowResponse> listSchedules() {
        return scheduleMapper.toResponseList(schedulerService.findAll());
    }

    @GetMapping("/{id}")
    public ScheduledWorkflowResponse getSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.findById(id));
    }

    @GetMapping("/workflow/{workflowId}")
    public List<ScheduledWorkflowResponse> getByWorkflow(@PathVariable String workflowId) {
        return scheduleMapper.toResponseList(schedulerService.findByWorkflowId(workflowId));
    }

    @PostMapping
    public ResponseEntity<ScheduledWorkflowResponse> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request) {
        var scheduled = schedulerService.create(
                request.workflowId(), request.cronExpression(),
                request.description(), request.inputContext());
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleMapper.toResponse(scheduled));
    }

    @PostMapping("/{id}/enable")
    public ScheduledWorkflowResponse enableSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public ScheduledWorkflowResponse disableSchedule(@PathVariable String id) {
        return scheduleMapper.toResponse(schedulerService.disable(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        schedulerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
