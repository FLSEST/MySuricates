package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutConfigTest {

    private static DashboardLayoutConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("automation.dashboard.layout", DashboardLayoutConfig.class)
                .orElseGet(DashboardLayoutConfig::new);
    }

    @Test
    void keeps_one_hundred_unnamed_versions_by_default() {
        assertThat(new DashboardLayoutConfig().getRetentionCount()).isEqualTo(100);
        assertThat(bind(Map.of()).getRetentionCount()).isEqualTo(100);
    }

    @Test
    void binds_the_retention_count_in_kebab_case() {
        assertThat(bind(Map.of("automation.dashboard.layout.retention-count", "25")).getRetentionCount()).isEqualTo(25);
    }

    @Test
    void setter_changes_the_value_at_runtime() {
        DashboardLayoutConfig config = new DashboardLayoutConfig();
        config.setRetentionCount(0);

        assertThat(config.getRetentionCount()).isZero();
    }
}
