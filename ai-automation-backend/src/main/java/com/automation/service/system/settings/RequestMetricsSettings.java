package com.automation.service.system.settings;

import com.automation.config.RequestMetricsConfig;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Request metrics and drift detection (TODO fc0994d6). The settings page does not show their values yet. */
@Component
@Order(5)
public class RequestMetricsSettings implements RuntimeSettingsGroup {

    private final RequestMetricsConfig requestMetricsConfig;

    public RequestMetricsSettings(RequestMetricsConfig requestMetricsConfig) {
        this.requestMetricsConfig = requestMetricsConfig;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("request-metrics-enabled", v -> requestMetricsConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("request-metrics-retention-days", v -> requestMetricsConfig.setRetentionDays(Integer.parseInt(v)));
        m.put("request-metrics-slow-request-ms", v -> requestMetricsConfig.setSlowRequestMs(Long.parseLong(v)));
        m.put("request-metrics-drift-detection-enabled", v -> requestMetricsConfig.setDriftDetectionEnabled(Integer.parseInt(v) == 1));
        m.put("request-metrics-drift-factor", v -> requestMetricsConfig.setDriftFactor(Double.parseDouble(v)));
        m.put("request-metrics-drift-min-calls", v -> requestMetricsConfig.setDriftMinCalls(Integer.parseInt(v)));
        m.put("request-metrics-baseline-days", v -> requestMetricsConfig.setBaselineDays(Integer.parseInt(v)));
        m.put("request-metrics-trend-min-daily-calls", v -> requestMetricsConfig.setTrendMinDailyCalls(Integer.parseInt(v)));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        // Intentionally empty: GET /system/settings never returned the request-metrics values.
    }
}
