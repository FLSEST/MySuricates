package com.automation.service.scheduler;

import com.automation.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Persistent on/off switch that pauses the scheduler tick (setting {@code scheduler.maintenance-mode}). */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerMaintenanceMode {

    static final String SETTING_KEY = "scheduler.maintenance-mode";

    private final AppSettingsRepository appSettingsRepository;

    public boolean isEnabled() {
        return appSettingsRepository.get(SETTING_KEY).map("true"::equals).orElse(false);
    }

    public void setEnabled(boolean enabled) {
        appSettingsRepository.set(SETTING_KEY, String.valueOf(enabled));
        log.info("Scheduler maintenance mode {}", enabled ? "enabled" : "disabled");
    }
}
