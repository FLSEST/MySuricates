package com.automation.controller;

import com.automation.dto.request.CreateActivityLogRequest;
import com.automation.dto.response.ActivityLogResponse;
import com.automation.dto.response.PageResponse;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.mapper.ActivityLogMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activity-log")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;
    private final ActivityLogMapper activityLogMapper;

    @GetMapping
    public PageResponse<ActivityLogResponse> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String workflowId) {

        LogLevel logLevel = EnumParameter.parseOptional(LogLevel.class, "level", level);
        LogCategory logCategory = EnumParameter.parseOptional(LogCategory.class, "category", category);
        String wfId = workflowId != null && !workflowId.isBlank() ? workflowId : null;

        var result = activityLogService.findAll(page, size, logLevel, logCategory, wfId);

        return new PageResponse<>(
                activityLogMapper.toResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @PostMapping
    public ResponseEntity<ActivityLogResponse> createPluginLog(@RequestBody CreateActivityLogRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        LogLevel level = request.level() != null ? request.level() : LogLevel.INFO;
        var entry = activityLogService.log(
                level,
                LogCategory.PLUGIN,
                request.message().trim(),
                request.source() != null && !request.source().isBlank() ? request.source().trim() : "plugin",
                request.referenceId(),
                request.metadata(),
                request.workflowId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(activityLogMapper.toResponse(entry));
    }
}
