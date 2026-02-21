package com.automation.service.activitylog;

import com.automation.entity.ActivityLog.LogCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActivityLogLifecycleListener {

    private final ActivityLogService activityLogService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        activityLogService.info(LogCategory.SYSTEM, "MySuricates server started", "System");
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        activityLogService.info(LogCategory.SYSTEM, "MySuricates server shutting down", "System");
    }
}
