package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the local Taskwarrior ({@code task}) CLI integration.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.task-command")
@Getter
@Setter
public class TaskCommandConfig {

    /**
     * Timeout in seconds for a single {@code task} CLI call on the host.
     * Taskwarrior commands are typically sub-second; the default leaves ample headroom.
     * Runtime-configurable via {@code ExecutionSettingsService} (key {@code task-command-timeout-seconds}).
     */
    private int timeoutSeconds = 60;
}
