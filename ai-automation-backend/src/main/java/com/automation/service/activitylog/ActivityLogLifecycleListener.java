package com.automation.service.activitylog;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.util.RuntimeClasspathGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityLogLifecycleListener {

    private final ActivityLogService activityLogService;
    private final Environment environment;

    private Instant startupTime;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        startupTime = Instant.now();

        var runtime = Runtime.getRuntime();
        var memoryBean = ManagementFactory.getMemoryMXBean();
        var heapInit = memoryBean.getHeapMemoryUsage().getInit() / (1024 * 1024);
        var heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        var pid = ProcessHandle.current().pid();
        var javaVersion = System.getProperty("java.version");
        var profiles = String.join(", ", environment.getActiveProfiles());
        var workingDir = System.getProperty("user.dir");
        var runtimeMode = RuntimeClasspathGuard.mode(System.getProperty("java.class.path"));

        log.info("========================================================");
        log.info("  MySuricates STARTED");
        log.info("  PID:            {}", pid);
        log.info("  Java:           {}", javaVersion);
        log.info("  Heap:           {} MB init / {} MB max", heapInit, heapMax);
        log.info("  CPUs:           {}", runtime.availableProcessors());
        log.info("  Profiles:       {}", profiles.isEmpty() ? "default" : profiles);
        log.info("  Working Dir:    {}", workingDir);
        log.info("  Runtime:        {}", runtimeMode);
        log.info("========================================================");

        var details = String.format("PID=%d, Java=%s, Heap=%dMB/%dMB, Profiles=%s",
                pid, javaVersion, heapInit, heapMax, profiles.isEmpty() ? "default" : profiles);
        activityLogService.info(LogCategory.SYSTEM, "MySuricates server started — " + details, "System");

        if (RuntimeClasspathGuard.MODE_CLASSES_DIR.equals(runtimeMode)) {
            var warning = "Server runs from target/classes (escape hatch set): any IDE or Maven build in this "
                    + "checkout can kill it with NoClassDefFoundError — restart via scripts/start-autotrigger-dev.sh";
            log.warn(warning);
            activityLogService.warn(LogCategory.SYSTEM, warning, "System");
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        var uptime = startupTime != null ? Duration.between(startupTime, Instant.now()) : Duration.ZERO;
        var uptimeStr = formatDuration(uptime);

        log.info("========================================================");
        log.info("  MySuricates CLEAN SHUTDOWN");
        log.info("  Uptime:         {}", uptimeStr);
        log.info("========================================================");

        activityLogService.info(LogCategory.SYSTEM,
                "MySuricates server shutting down — clean shutdown, uptime: " + uptimeStr, "System");
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
