package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Stored dashboard layout versions. Mutated at runtime by ExecutionSettingsService
 * (key: dashboard-layout-retention-count).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.dashboard.layout")
@Getter
@Setter
public class DashboardLayoutConfig {
    /** Unnamed versions kept; older ones are deleted on every save. Named versions are always kept. */
    private int retentionCount = 100;
}
