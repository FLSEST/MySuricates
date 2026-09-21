package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardReportAssistConfigTest {

    private static DashboardReportAssistConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("automation.dashboard.report-assist", DashboardReportAssistConfig.class)
                .orElseGet(DashboardReportAssistConfig::new);
    }

    @Test
    void enabled_with_a_two_minute_timeout_by_default() {
        DashboardReportAssistConfig config = bind(Map.of());

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void binds_both_properties() {
        DashboardReportAssistConfig config = bind(Map.of(
                "automation.dashboard.report-assist.enabled", "false",
                "automation.dashboard.report-assist.timeout-seconds", "45"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getTimeoutSeconds()).isEqualTo(45);
    }

    @Test
    void can_be_switched_off_at_runtime() {
        DashboardReportAssistConfig config = new DashboardReportAssistConfig();
        config.setEnabled(false);
        config.setTimeoutSeconds(10);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getTimeoutSeconds()).isEqualTo(10);
    }
}
