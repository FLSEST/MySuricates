package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.factory")
@Getter
@Setter
public class FactoryConfig {

    /**
     * Default AI provider for workflow factory: CLAUDE or CODEX.
     */
    private String provider = "CLAUDE";

    /**
     * Base directory for factory working dirs (claude/, codex/).
     */
    private String baseDir = System.getProperty("user.home") + "/.autotrigger/factory";

    /**
     * Timeout in seconds for AI generation calls.
     */
    private int timeoutSeconds = 600;
}
