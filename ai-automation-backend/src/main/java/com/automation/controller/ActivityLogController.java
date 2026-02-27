package com.automation.controller;

import com.automation.dto.response.ActivityLogResponse;
import com.automation.dto.response.PageResponse;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.mapper.ActivityLogMapper;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
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
            @RequestParam(required = false) String category) {

        LogLevel logLevel = level != null && !level.isBlank() ? LogLevel.valueOf(level) : null;
        LogCategory logCategory = category != null && !category.isBlank() ? LogCategory.valueOf(category) : null;

        var result = activityLogService.findAll(page, size, logLevel, logCategory);

        return new PageResponse<>(
                activityLogMapper.toResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}
