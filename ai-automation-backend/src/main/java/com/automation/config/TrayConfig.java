package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.tray")
@Getter
@Setter
public class TrayConfig {

    private boolean enabled = true;

    private long pollIntervalMs = 15000;

    private String webUiUrl = "http://localhost:3000";

    private String stableUrl = "http://localhost:9091";
}
