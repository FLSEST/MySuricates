package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.ai")
@Getter
@Setter
public class AiProviderConfig {

    /**
     * Global default AI provider: CLAUDE or CODEX.
     * Can be overridden per workflow via the provider field.
     */
    private String defaultProvider = "CLAUDE";
}
