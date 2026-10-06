package com.automation.service.system.settings;

import com.automation.config.DashboardLayoutConfig;
import com.automation.config.RunRetentionConfig;
import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.config.TodoSimilarityConfig;
import com.automation.service.workflow.RunDeletionPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Housekeeping and retention: TODO hygiene and watchdog, run retention, layout versions, duplicate threshold. */
@Component
@Order(3)
public class HousekeepingSettings implements RuntimeSettingsGroup {

    static final String RUN_RETENTION_DAYS = "run-retention-days";

    private final TodoHygieneConfig todoHygieneConfig;
    private final RunRetentionConfig runRetentionConfig;
    private final DashboardLayoutConfig dashboardLayoutConfig;
    private final TodoGhostWatchdogConfig todoGhostWatchdogConfig;
    private final TodoSimilarityConfig todoSimilarityConfig;
    private final RunDeletionPolicy runDeletionPolicy;

    public HousekeepingSettings(TodoHygieneConfig todoHygieneConfig, RunRetentionConfig runRetentionConfig,
                                DashboardLayoutConfig dashboardLayoutConfig, TodoGhostWatchdogConfig todoGhostWatchdogConfig,
                                TodoSimilarityConfig todoSimilarityConfig, RunDeletionPolicy runDeletionPolicy) {
        this.todoHygieneConfig = todoHygieneConfig;
        this.runRetentionConfig = runRetentionConfig;
        this.dashboardLayoutConfig = dashboardLayoutConfig;
        this.todoGhostWatchdogConfig = todoGhostWatchdogConfig;
        this.todoSimilarityConfig = todoSimilarityConfig;
        this.runDeletionPolicy = runDeletionPolicy;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("todo-hygiene-enabled", v -> todoHygieneConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("todo-stale-days", v -> todoHygieneConfig.setStaleDays(Integer.parseInt(v)));
        m.put("todo-hygiene-max-per-run", v -> todoHygieneConfig.setMaxPerRun(Integer.parseInt(v)));
        m.put(RUN_RETENTION_DAYS, v -> runRetentionConfig.setRetentionDays(Integer.parseInt(v)));
        m.put("run-retention-max-per-night", v -> runRetentionConfig.setMaxPerNight(Integer.parseInt(v)));
        m.put("dashboard-layout-retention-count", v -> dashboardLayoutConfig.setRetentionCount(Integer.parseInt(v)));
        m.put("todo-watchdog-enabled", v -> todoGhostWatchdogConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("todo-watchdog-stale-hours", v -> todoGhostWatchdogConfig.setStaleHours(Integer.parseInt(v)));
        m.put("todo-similarity-keyword-threshold", v -> todoSimilarityConfig.setKeywordThreshold(Double.parseDouble(v)));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        out.put("todoHygieneEnabled", todoHygieneConfig.isEnabled() ? 1 : 0);
        out.put("todoStaleDays", todoHygieneConfig.getStaleDays());
        out.put("todoHygieneMaxPerRun", todoHygieneConfig.getMaxPerRun());
        out.put("runRetentionDays", runRetentionConfig.getRetentionDays());
        out.put("runRetentionMaxPerNight", runRetentionConfig.getMaxPerNight());
        out.put("dashboardLayoutRetentionCount", dashboardLayoutConfig.getRetentionCount());
        out.put("todoWatchdogEnabled", todoGhostWatchdogConfig.isEnabled() ? 1 : 0);
        out.put("todoWatchdogStaleHours", todoGhostWatchdogConfig.getStaleHours());
        out.put("todoSimilarityKeywordThreshold", todoSimilarityConfig.getKeywordThreshold());
    }

    /** 8dbd7e10: a leaked key must not delete runs via a shorter retention. */
    @Override
    public void guard(String key, String value) {
        if (RUN_RETENTION_DAYS.equals(key)) {
            runDeletionPolicy.requireRetentionChangeAllowed(runRetentionConfig.getRetentionDays(), Integer.parseInt(value));
        }
    }
}
